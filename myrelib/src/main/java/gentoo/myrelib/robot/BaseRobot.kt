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
import gentoo.myrelib.field.RobotTelemetryBridge
import gentoo.myrelib.field.RecordingFiles
import gentoo.myrelib.hardware.HardwareAccess
import gentoo.myrelib.math.Pose
import gentoo.myrelib.opmode.OpMode
import gentoo.myrelib.recording.MatchRecorder
import gentoo.myrelib.recording.RecordingConfig
import gentoo.myrelib.util.Alliance
import gentoo.myrelib.util.PoseRepository
import gentoo.myrelib.util.cleanupAll
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import com.pedropathing.paths.Path as PedroPath
import com.qualcomm.robotcore.eventloop.opmode.OpMode as FTCOpMode

typealias FollowerFactory = (HardwareMap) -> Follower

enum class State { IDLE, INIT, PLAY }

/** One opmode execution and its FTC context, independent of persistent mechanism state. */
class RobotSession internal constructor(
    val opMode: FTCOpMode,
    val alliance: Alliance?,
) {
    val opModeName: String get() = opMode.javaClass.simpleName

    private var started = false
    private var ended = false
    private val frameworkOpMode = opMode as? OpMode

    val state: State
        get() {
            if(ended) return State.IDLE
            return when(frameworkOpMode?.phase) {
                OpMode.Phase.INIT -> State.INIT
                OpMode.Phase.PLAY -> State.PLAY
                // Bare FTC iterative opmodes signal PLAY by calling BaseRobot.start().
                null -> if(started) State.PLAY else State.INIT
            }
        }

    internal val owner = Thread.currentThread()

    internal fun checkThread() {
        check(owner === Thread.currentThread()) { "Robot lifecycle must run on the opmode thread" }
    }

    internal fun markStarted() {
        checkThread()
        check(!started) { "Session has already started" }
        check(frameworkOpMode?.phase != OpMode.Phase.INIT) { "The opmode has not entered PLAY" }
        started = true
    }

    internal fun end() {
        ended = true
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
    private var telemetry: MatchRecorder? = null
    private var currentSession: RobotSession? = null
    private var resetting = false
    private var attaching = false
    private var startingRecording = false
    val components = ComponentRegistry { publishState(record = false, force = false) }
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

    /** Constructs components once, during the first startOpMode call. */
    private fun init() {
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

    /** Registers cached states for the live feed and optional recording at each opmode INIT. */
    protected open fun configureRecording(recorder: MatchRecorder) {}

    /** Publishes current state and appends it to an active recording when due. */
    fun recordState(force: Boolean = false) = publishState(record = true, force = force)

    private fun publishState(record: Boolean, force: Boolean) {
        val sender = telemetry ?: return
        val active = session
        active.checkThread()
        sender.emit(active.state.name, force, record) { sample, now ->
            if(currentSession === active) RobotTelemetryBridge.publish(sample, motion.activePath, now)
        }
    }

    internal fun recordPath(path: gentoo.myrelib.field.FieldPath) {
        telemetry?.recordPath(path)
    }

    /** Opt in for this session from an opmode hook. Repeated calls keep the same recording. */
    fun startRecording() {
        val active = session
        active.checkThread()
        check(!attaching && !resetting && !startingRecording) { "Start recording from an opmode hook" }
        val sender = checkNotNull(telemetry)
        if(sender.hasStarted || !recordingConfig.enabled) return
        val file = RecordingFiles.newFile() ?: return
        startingRecording = true
        try {
            sender.start(file, active.opModeName, active.alliance?.name, WIDTH, LENGTH)
            motion.activePath?.let(sender::recordPath)
        }
        catch(error: Exception) {
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

    /** Attaches this robot to a new opmode session during FTC INIT. */
    fun startOpMode(
        opMode: FTCOpMode,
        alliance: Alliance? = null,
        config: ComponentConfig = this.config,
    ): RobotSession {
        check(!resetting && !attaching && !startingRecording) { "Cannot start an opmode session from a lifecycle callback" }
        reset()
        val hardwareMap = opMode.hardwareMap
        PoseRepository.attach(hardwareMap.appContext)
        RecordingFiles.attach(hardwareMap.appContext.filesDir)
        return startOpMode(
            opMode,
            alliance,
            HardwareAccess.from(hardwareMap),
            config,
            followerFactory,
        )
    }

    /** Specialized opmode attachment seam, also usable with hardwareless sources on the JVM. */
    protected fun startOpMode(
        opMode: FTCOpMode,
        alliance: Alliance? = null,
        hardware: HardwareAccess = HardwareAccess.empty(),
        config: ComponentConfig = this.config,
        followerFactory: FollowerFactory = this.followerFactory,
    ): RobotSession {
        check(!resetting && !attaching && !startingRecording) { "Cannot start an opmode session from a lifecycle callback" }
        reset()
        return attach(
            hardware,
            config,
            followerFactory,
            RobotSession(opMode, alliance)
        )
    }

    private fun attach(
        hardware: HardwareAccess,
        config: ComponentConfig,
        followerFactory: FollowerFactory,
        newSession: RobotSession,
    ): RobotSession {
        attaching = true
        try {
            RobotTelemetryBridge.begin(newSession.opModeName, WIDTH, LENGTH)
            val snapshot = config.instance()
            init()
            currentSession = newSession
            motion.prepare(followerFactory)
            components.attach(hardware, snapshot)
            val sender = MatchRecorder(recordingConfig)
            telemetry = sender
            configureRecording(sender)
            sender.addDefaultPose { motion.pose.takeUnless { motion.disabled } }
            sender.addState("motionPath") {
                motion.activePath?.let { mapOf("id" to it.id, "t" to motion.pathT) }
            }
            return newSession
        }
        catch(failure: Throwable) {
            attaching = false
            try {
                reset()
            }
            catch(cleanupFailure: Throwable) {
                if(failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
            }
            RobotTelemetryBridge.stop()
            throw failure
        }
        finally {
            attaching = false
        }
    }

    /** Runs component start hooks at PLAY. For bare FTC opmodes, also signals PLAY. */
    fun start() {
        val active = session
        active.markStarted()
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
                { telemetry?.stop() },
                { RobotTelemetryBridge.stop() },
            )
        }
        finally {
            telemetry = null
            active?.end()
            currentSession = null
            resetting = false
        }
    }
}
