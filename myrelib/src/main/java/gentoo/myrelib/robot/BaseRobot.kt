package gentoo.myrelib.robot

import com.pedropathing.follower.Follower
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.util.RobotLog
import gentoo.myrelib.action.ActionInstance
import gentoo.myrelib.action.ActionType
import gentoo.myrelib.component.ActionController
import gentoo.myrelib.component.Component
import gentoo.myrelib.component.ComponentRegistry
import gentoo.myrelib.component.MotionController
import gentoo.myrelib.config.ComponentConfig
import gentoo.myrelib.field.FieldPoseBridge
import gentoo.myrelib.field.RecordingFiles
import gentoo.myrelib.hardware.HardwareAccess
import gentoo.myrelib.math.Pose
import gentoo.myrelib.recording.MatchRecorder
import gentoo.myrelib.recording.RecordingConfig
import gentoo.myrelib.util.Alliance
import gentoo.myrelib.util.PoseRepository
import gentoo.myrelib.util.cleanupAll
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import com.pedropathing.paths.Path as PedroPath

typealias FollowerFactory = (HardwareMap) -> Follower

enum class State { IDLE, INIT, PLAY }

/** One opmode execution; never owns persistent mechanism state or raw hardware. */
class RobotSession internal constructor(
    val alliance: Alliance?,
    val opModeName: String,
) {
    var state: State = State.INIT
        internal set
    internal val owner = Thread.currentThread()

    internal fun checkThread() {
        check(owner === Thread.currentThread()) { "Robot lifecycle must run on the opmode thread" }
    }
}

/**
 * Process-wide robot lifecycle. The robot defines and stores the hardware and logical components
 * that are used throughout a game.
 */
abstract class BaseRobot {
    companion object {
        val INSTANCE: BaseRobot
            get() = singleton
                ?: throw IllegalStateException("Robot singleton not present")

        private var singleton: BaseRobot? = null
    }

    private val definitions = linkedMapOf<String, ComponentDelegate<*>>()
    private var componentsCreated = false
    private var recorder: MatchRecorder? = null
    private var currentSession: RobotSession? = null
    private var resetting = false
    private var attaching = false
    private var startingRecording = false
    val components = ComponentRegistry()
    val actions by component(::ActionController)
    val motion by component(::MotionController)

    /** Registers a factory now; constructs the component only during the first INIT. */
    protected fun <T : Component> component(factory: () -> T) = ComponentDelegate(factory)

    class ComponentDelegate<T : Component> internal constructor(private var factory: (() -> T)?) {
        private var value: T? = null

        operator fun provideDelegate(
            thisRef: BaseRobot,
            property: KProperty<*>
        ): ReadOnlyProperty<BaseRobot, T> {
            check(!thisRef.attaching && !thisRef.componentsCreated) { "Declare components before the first INIT" }
            check(property.name !in thisRef.definitions) { "Component '${property.name}' is already declared" }
            thisRef.definitions[property.name] = this
            return ReadOnlyProperty { _, _ ->
                value
                    ?: error("Component '${property.name}' is unavailable before the first opmode INIT")
            }
        }

        internal fun create(name: String, registry: ComponentRegistry) {
            if(value != null) return
            val component = checkNotNull(factory).invoke()
            registry.register(name, component)
            value = component
            factory = null
        }
    }

    private fun createComponents() {
        if(componentsCreated) return
        definitions.forEach { (name, definition) -> definition.create(name, components) }
        componentsCreated = true
    }

    val session: RobotSession
        get() = currentSession ?: error("Robot has no active opmode session")
    val state: State get() = currentSession?.state ?: State.IDLE

    val isInited: Boolean get() = currentSession != null

    val alliance: Alliance? get() = session.alliance

    val runningActions: Map<ActionType, ActionInstance> get() = actions.runningActions

    fun follow(path: PedroPath) = motion.follow(path)

    val following: Boolean get() = motion.following

    val pathT: Double get() = motion.pathT

    /**
     * Current follower estimate, cached by the follower exactly once per component tick.
     * Once the robot is initialized this always returns a pose, substituting the
     * field origin when the follower is disabled or has not localized yet. It
     * throws before the first initialization. Use [strictPose] where the
     * origin would be a wrong answer rather than a harmless default.
     */
    val pose: Pose
        get() = motion.pose ?: Pose()

    /**
     * Current follower estimate, cached by the follower exactly once per component tick.
     * Null until a pose has been established. A retained pose can be stale while idle
     * or disabled; this getter never polls hardware.
     */
    val strictPose: Pose?
        get() = motion.pose

    abstract val config: ComponentConfig
    abstract val followerFactory: FollowerFactory

    protected open val recordingConfig = RecordingConfig()
    protected open fun configureRecording(recorder: MatchRecorder) {}

    /** Samples cached state with the current session's INIT/PLAY phase. */
    fun recordState(force: Boolean = false) {
        val activeRecorder = recorder ?: return
        val active = session
        active.checkThread()
        activeRecorder.sample(active.state.name, force)
    }

    internal fun recordPath(path: gentoo.myrelib.field.FieldPath) {
        recorder?.recordPath(path)
    }

    /** Opt in for this session from an opmode hook. Repeated calls keep the same recording. */
    fun startRecording() {
        val active = session
        active.checkThread()
        check(!attaching && !resetting && !startingRecording) { "Start recording from an opmode hook" }
        if(recorder != null || !recordingConfig.enabled) return
        val file = RecordingFiles.newFile() ?: return
        val next = MatchRecorder(recordingConfig)
        startingRecording = true
        try {
            configureRecording(next)
            next.addState("motionPath") {
                motion.activePath?.let { mapOf("id" to it.id, "t" to motion.pathT) }
            }
            next.start(file, active.opModeName, active.alliance?.name, WIDTH, LENGTH)
            motion.activePath?.let(next::recordPath)
            recorder = next
        }
        catch(error: Exception) {
            next.stop()
            RobotLog.ee("MatchRecorder", error, "Could not start match recording")
        }
        finally {
            startingRecording = false
        }
    }

    /** inches */
    abstract val LENGTH: Double
    val HALF_LENGTH: Double get() = LENGTH / 2

    /** inches */
    abstract val WIDTH: Double
    val HALF_WIDTH: Double get() = WIDTH / 2

    init {
        val existing = singleton
        // make sure only sloth reload can create a new robot singleton
        check(existing == null || existing.javaClass.classLoader !== javaClass.classLoader) {
            "The robot singleton has already been created"
        }
        singleton = this
    }

    fun init(
        alliance: Alliance?,
        hardwareMap: HardwareMap,
        opModeName: String = "Robot",
        componentConfig: ComponentConfig = config,
    ): RobotSession {
        check(!resetting && !attaching && !startingRecording) { "Cannot initialize a session from a lifecycle callback" }
        reset()
        PoseRepository.attach(hardwareMap.appContext)
        RecordingFiles.attach(hardwareMap.appContext.filesDir)
        return init(
            alliance,
            HardwareAccess.from(hardwareMap),
            componentConfig,
            followerFactory,
            opModeName
        )
    }

    /** Specialized initialization seam, also usable with hardwareless sources on the JVM. */
    protected fun init(
        alliance: Alliance? = null,
        hardware: HardwareAccess = HardwareAccess.empty(),
        componentConfig: ComponentConfig = config,
        followerFactory: FollowerFactory = this.followerFactory,
        opModeName: String = "Robot",
    ): RobotSession {
        check(!resetting && !attaching && !startingRecording) { "Cannot initialize a session from a lifecycle callback" }
        reset()
        return attach(
            hardware,
            componentConfig,
            followerFactory,
            RobotSession(alliance, opModeName)
        )
    }

    private fun attach(
        hardware: HardwareAccess,
        config: ComponentConfig,
        followerFactory: FollowerFactory,
        next: RobotSession,
    ): RobotSession {
        attaching = true
        try {
            FieldPoseBridge.begin(next.opModeName, WIDTH, LENGTH)
            val snapshot = config.instance()
            createComponents()
            currentSession = next
            motion.prepare(followerFactory)
            components.attach(hardware, snapshot)
            return next
        }
        catch(failure: Throwable) {
            attaching = false
            try {
                reset()
            }
            catch(cleanupFailure: Throwable) {
                if(failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
            }
            FieldPoseBridge.stop()
            throw failure
        }
        finally {
            attaching = false
        }
    }

    fun start() {
        val active = session
        active.checkThread()
        check(active.state == State.INIT) { "Session has already started" }
        active.state = State.PLAY
        try {
            components.start()
        }
        catch(failure: Throwable) {
            try {
                reset()
            }
            catch(cleanupFailure: Throwable) {
                if(failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    fun removeAllActions() = actions.removeAll()

    internal fun reset(expected: RobotSession) {
        if(currentSession === expected) reset()
    }

    /** End execution and revoke hardware, retaining component identity and ordinary fields. */
    fun reset() {
        check(!attaching && !startingRecording) { "Cannot reset a session from an attachment or recording callback" }
        if(resetting) return
        currentSession?.checkThread()
        resetting = true
        val active = currentSession
        try {
            cleanupAll(
                { if(active != null) cleanupAll({ actions.removeAll() }, { components.detach() }) },
                { recorder?.stop() },
                { FieldPoseBridge.stop() },
            )
        }
        finally {
            recorder = null
            active?.state = State.IDLE
            currentSession = null
            resetting = false
        }
    }
}
