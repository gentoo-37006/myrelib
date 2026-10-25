package gentoo.myrelib.robot

import com.pedropathing.follower.Follower
import com.qualcomm.robotcore.eventloop.opmode.OpMode
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.util.RobotLog
import gentoo.myrelib.action.ActionInstance
import gentoo.myrelib.action.ActionType
import gentoo.myrelib.component.ActionController
import gentoo.myrelib.component.Component
import gentoo.myrelib.component.ComponentRegistry
import gentoo.myrelib.component.HardwareAccess
import gentoo.myrelib.component.MotionController
import gentoo.myrelib.config.ComponentConfig
import gentoo.myrelib.field.FieldPoseBridge
import gentoo.myrelib.field.RecordingFiles
import gentoo.myrelib.math.Pose
import gentoo.myrelib.recording.MatchRecorder
import gentoo.myrelib.recording.RecordingConfig
import gentoo.myrelib.util.Alliance
import gentoo.myrelib.util.PoseRepository
import org.jetbrains.annotations.TestOnly
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import com.pedropathing.paths.Path as PedroPath

/** The two supported ways to build robot state for an opmode. */
enum class InitType {
    FULL,
    EMPTY
}

typealias FollowerFactory = (HardwareMap) -> Follower

typealias RobotFactory<T> = (Context) -> T

data class Context(
    val hardware: HardwareAccess = HardwareAccess.empty(),
    val config: ComponentConfig = ComponentConfig.enableAll(),
    val followerFactory: FollowerFactory,
    val alliance: Alliance? = null
)

/**
 * The reusable robot instance, created once per [OpMode].
 * It is the only place where components are registered.
 */
open class BaseRobotInstance(private val context: Context) {
    private val hardware: HardwareAccess = context.hardware
    private val config = context.config.instance()
    val alliance: Alliance? = context.alliance

    val components = ComponentRegistry()
    val actions by register(::ActionController)
    val motion by register { hardware, disabled ->
        MotionController(hardware, context.followerFactory, disabled)
    }

    /** Registers a component whose registry/configuration key is its property name. */
    protected fun <T : Component> register(
        factory: (hardware: HardwareAccess, disabled: Boolean) -> T
    ) = ComponentDelegate { name ->
        val component = factory(hardware, config.isDisabled(name))
        components.register(name, component)
    }

    internal fun release() {
        var stopFailure: Throwable? = null
        try {
            actions.removeAll()
        }
        catch(failure: Throwable) {
            stopFailure = failure
        }

        // A failed action stop must not skip drivetrain or camera cleanup.
        try {
            components.release()
        }
        catch(releaseFailure: Throwable) {
            if(stopFailure == null) throw releaseFailure
            if(stopFailure !== releaseFailure) {
                stopFailure.addSuppressed(releaseFailure)
            }
        }
        stopFailure?.let { throw it }
    }

    /** Property delegate that registers one component under the declaring field's name. */
    class ComponentDelegate<T : Component> internal constructor(
        private val register: (name: String) -> T
    ) {
        operator fun provideDelegate(
            thisRef: BaseRobotInstance,
            property: KProperty<*>
        ): ReadOnlyProperty<BaseRobotInstance, T> {
            val component = register(property.name)
            return ReadOnlyProperty { _, _ -> component }
        }
    }
}

/**
 * Process-wide robot lifecycle with explicit full and empty initialization.
 * A season object extends this class and only supplies its full composition and
 * season-specific state/accessors.
 */
abstract class BaseRobot<T : BaseRobotInstance> {
    companion object {
        val INSTANCE: BaseRobot<*>
            get() = singleton
                ?: throw IllegalStateException("The robot singleton has not been created")

        private var singleton: BaseRobot<*>? = null
    }

    private var _instance: T? = null
    private var recorder: MatchRecorder? = null

    var initType: InitType? = null
        private set

    val isInited: Boolean get() = _instance != null
    val isFullyInited: Boolean get() = initType == InitType.FULL

    protected val instance: T
        get() = _instance ?: throw IllegalStateException("Robot has not been initialized")

    val alliance: Alliance? get() = instance.alliance

    val components: ComponentRegistry get() = instance.components

    internal val actions: ActionController get() = instance.actions

    val motion: MotionController get() = instance.motion

    val runningActions: Map<ActionType, ActionInstance> get() = actions.runningActions

    fun follow(path: PedroPath) = motion.follow(path)

    val following: Boolean get() = motion.following

    val pathT: Double get() = motion.pathT

    /**
     * Current follower estimate, cached by the follower exactly once per component tick.
     * Once the robot is initialized this always returns a pose, substituting the
     * field origin when the follower is disabled or has not localized yet. It
     * throws before initialization, as RobotTest pins. Use [strictPose] where the
     * origin would be a wrong answer rather than a harmless default.
     */
    val pose: Pose
        get() = motion.pose ?: Pose()

    /**
     * Current follower estimate, cached by the follower exactly once per component tick.
     * This will return null if the follower is disabled or uninitialized.
     */
    val strictPose: Pose?
        get() = if(isFullyInited) motion.pose else null

    abstract val config: ComponentConfig
    abstract val followerFactory: FollowerFactory
    abstract val robotFactory: RobotFactory<T>

    protected open val recordingConfig = RecordingConfig()
    protected open fun configureRecording(recorder: MatchRecorder) {}

    fun recordState(phase: String = "RUNNING", force: Boolean = false) {
        recorder?.sample(phase, force)
    }

    internal fun recordPath(path: gentoo.myrelib.field.FieldPath) {
        recorder?.recordPath(path)
    }

    private fun beginRecording(opModeName: String) {
        if(!recordingConfig.enabled) return
        val file = RecordingFiles.newFile() ?: return
        try {
            val next = MatchRecorder(recordingConfig)
            configureRecording(next)
            next.addState("motionPath") {
                motion.activePath?.let { mapOf("id" to it.id, "t" to motion.pathT) }
            }
            next.start(file, opModeName, alliance?.name, WIDTH, LENGTH)
            recorder = next
        }
        catch(error: Exception) {
            RobotLog.ee("MatchRecorder", error, "Could not start match recording")
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

    fun initFull(
        alliance: Alliance?,
        hardwareMap: HardwareMap,
        opModeName: String = "Robot"
    ) {
        PoseRepository.attach(hardwareMap.appContext)
        RecordingFiles.attach(hardwareMap.appContext.filesDir)
        val hardware = HardwareAccess.from(hardwareMap)
        initFull(alliance, opModeName) {
            robotFactory(
                Context(
                    hardware,
                    config,
                    followerFactory,
                    alliance
                )
            )
        }
    }

    /** JVM-test and specialized initialization seam that does not require FTC hardware. */
    @TestOnly
    protected fun initFull(
        alliance: Alliance?,
        opModeName: String = "Robot",
        factory: (Alliance?) -> T
    ) {
        reset()
        FieldPoseBridge.begin(opModeName, WIDTH, LENGTH)
        try {
            _instance = factory(alliance)
            initType = InitType.FULL
            beginRecording(opModeName)
        }
        catch(failure: Throwable) {
            FieldPoseBridge.stop()
            throw failure
        }
    }

    fun initEmpty(opModeName: String = "Robot") {
        reset()
        FieldPoseBridge.begin(opModeName, WIDTH, LENGTH)
        try {
            _instance = robotFactory(
                Context(
                    HardwareAccess.empty(),
                    ComponentConfig.disableAll(),
                    followerFactory,
                    null,
                )
            )
            initType = InitType.EMPTY
        }
        catch(failure: Throwable) {
            FieldPoseBridge.stop()
            throw failure
        }
    }

    fun removeAllActions() = actions.removeAll()

    fun reset() {
        recorder?.stop()
        recorder = null
        FieldPoseBridge.stop()
        try {
            _instance?.release()
        }
        finally {
            _instance = null
            initType = null
        }
    }
}
