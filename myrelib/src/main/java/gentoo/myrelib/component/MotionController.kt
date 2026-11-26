package gentoo.myrelib.component

import com.pedropathing.algorithm.Foresight
import com.pedropathing.drivetrain.DrivePowers
import com.pedropathing.follower.Follower
import com.pedropathing.follower.ManualDrive
import com.pedropathing.paths.Path
import com.pedropathing.utils.Angle
import com.qualcomm.robotcore.hardware.HardwareMap
import gentoo.myrelib.hardware.HardwareAccess
import gentoo.myrelib.robot.BaseRobot
import gentoo.myrelib.field.FieldPath
import gentoo.myrelib.math.Pose
import gentoo.myrelib.math.toPedro
import gentoo.myrelib.math.toPose
import gentoo.myrelib.pathing.PathArcLength
import gentoo.myrelib.util.PoseRepository
import gentoo.myrelib.util.cleanupAll
import kotlin.math.hypot
import kotlin.math.sign

open class MotionController : Component {
    private var followerFactory: ((HardwareMap) -> Follower)? = null

    constructor() : super()

    internal fun prepare(factory: (HardwareMap) -> Follower) { followerFactory = factory }
    private var follower: Follower? = null

    /** Standalone integration seam; robot components use the attachment constructor. */
    constructor(hardware: HardwareAccess, followerFactory: (HardwareMap) -> Follower, disabled: Boolean) :
        super(hardware, disabled) {
        this.followerFactory = followerFactory
        follower = this.hardware.hardwareMapOrNull?.let(followerFactory)
    }

    override fun onAttach() {
        follower = hardware.hardwareMapOrNull?.let { checkNotNull(followerFactory).invoke(it) }
        // Restore the established field reference without resuming an old path or drive command.
        pose?.let { follower?.setPose(it.toPedro()) }
        lockedHeading = null
        fieldCentricHeading = null
        pathT = 0.0
        pathProgress = 0.0
        speed = 0.0
        turnRate = 0.0
    }

    private var segmentDistances: List<Double> = emptyList()
    private var pathArcLength: PathArcLength? = null
    private var lockedHeading: Double? = null
    private var fieldCentricHeading: Double? = null
    private var nextPathId = 0L
    var activePath: FieldPath? = null
        private set

    open val following get() = follower?.following() == true
    open var pathT = 0.0
        protected set

    /** Furthest geometric progress along the full path, measured by arc length. */
    var pathProgress = 0.0
        private set

    /** The last pose read from Pedro; reading this never performs another localization update. */
    var pose: Pose? = null
        private set

    /** Translational speed from the same update as [pose], in inches per second. */
    var speed = 0.0
        private set

    /** Turn rate from the same update as [pose], in degrees per second, clockwise positive. */
    var turnRate = 0.0
        private set

    override fun tick() {
        val follower = follower ?: return
        val wasFollowing = follower.following()
        follower.update()
        when {
            follower.following() -> updatePathProgress(follower)
            wasFollowing -> finishPathProgress()
        }
        val updatedPose = follower.pose().toPose()
        pose = updatedPose
        val velocity = follower.velocity()
        speed = hypot(velocity.vx, velocity.vy)
        // Pedro turns counter-clockwise positive, in radians
        turnRate = -Math.toDegrees(velocity.omega)
        PoseRepository.record(updatedPose)
        if(!follower.following()) activePath = null
    }

    open fun follow(path: Path) {
        val follower = follower ?: return
        pathT = 0.0
        pathProgress = 0.0
        val curves = path.getSegments().map { it.curve }
        segmentDistances = curves.runningFold(0.0) { distance, curve ->
            distance + curve.length()
        }
        pathArcLength = PathArcLength(curves)
        follower.follow(path)
        val geometry = FieldPath.from(path, ++nextPathId)
        activePath = geometry
        BaseRobot.INSTANCE.recordPath(geometry)
    }

    private fun updatePathProgress(follower: Follower) {
        if(segmentDistances.size < 2) return
        val segmentIndex = follower.pathIndex()
        if(segmentIndex >= segmentDistances.lastIndex) {
            finishPathProgress()
            return
        }
        if(segmentIndex < 0) return

        val rawT = follower.parametricCompletion()
        if(!rawT.isFinite()) return
        val localT = rawT.coerceIn(0.0, 1.0)
        val total = segmentDistances.last()
        if(total <= 0.0 || !total.isFinite()) {
            pathT = localT
            return
        }

        val completed = segmentDistances[segmentIndex]
        val length = segmentDistances[segmentIndex + 1] - completed
        // The viewer uses Pedro's compound parameter, not arc-length progress.
        pathT = ((completed + length * localT) / total).coerceIn(0.0, 1.0)
        pathProgress = maxOf(pathProgress, pathArcLength?.progress(segmentIndex, localT) ?: 0.0)
    }

    private fun finishPathProgress() {
        pathT = 1.0
        pathProgress = 1.0
    }

    fun hold(pose: Pose) {
        clearPath()
        follower?.hold(pose.toPedro())
    }

    fun stop() {
        fieldCentricHeading = null
        clearPath()
        follower?.stop()
    }

    /**
     * Forces the robot to maintain a specific heading, overriding any heading
     * from manual control
     */
    fun lockHeading(heading: Double) {
        lockedHeading = heading.toPedro()
    }

    /**
     * Releases the override, restoring field-centric heading or robot-centric turn power.
     */
    fun unlockHeading() {
        lockedHeading = null
    }

    /**
     * the driver's left stick y feeds [forward] and its x feeds [strafe]
     *
     * @param forward positive drives towards the front of the robot
     * @param strafe positive strafes to the robot's right
     * @param turn positive rotates clockwise
     */
    fun moveRobotCentric(forward: Double, strafe: Double, turn: Double) {
        fieldCentricHeading = null
        val powers = DrivePowers(forward, -strafe, -turn)
        applyDrivePowers(powers)
    }

    /**
     * @param forward positive drives away from the driver
     * @param strafe positive strafes to the driver's right
     * @param heading absolute field heading; null retains the last
     * target, or captures the current heading on first use
     * @param relativeNorth the field heading the driver considers forward
     */
    fun moveFieldCentric(
        forward: Double,
        strafe: Double,
        heading: Double?,
        relativeNorth: Double
    ) {
        val follower = follower ?: return
        if(heading != null && heading.isFinite()) {
            fieldCentricHeading = heading.toPedro()
        }
        if(fieldCentricHeading == null) fieldCentricHeading = follower.pose().heading()
        val powers = ManualDrive.fieldCentric(
            forward,
            -strafe,
            0.0,
            follower.pose().heading(),
            -relativeNorth.toPedro()
        )
        applyDrivePowers(powers, fieldCentricHeading)
    }

    private fun applyDrivePowers(powers: DrivePowers, targetHeading: Double? = null) {
        val follower = follower ?: return
        val algorithm = follower.algorithm()
        val heading = lockedHeading ?: targetHeading
        follower.manual(
            if(heading != null && algorithm is Foresight) {
                val braking = algorithm.config.headingBrakeCoefficients.get()
                val omega = follower.velocity().omega
                val stoppingRotation =
                    braking.x() * omega + braking.y() * omega * omega * sign(omega)
                val error = Angle.normalizeSigned(heading - follower.pose().heading())
                val turn = algorithm.config.headingFeedback.get()
                    .calculate(0.0, error - stoppingRotation)
                    .coerceIn(-1.0, 1.0)
                DrivePowers(
                    powers.forward(),
                    powers.strafe(),
                    turn
                )
            }
            else {
                powers
            }
        )
    }

    fun setPose(pose: Pose) {
        val activeFollower = follower ?: return
        this.pose = pose
        activeFollower.setPose(pose.toPedro())
        PoseRepository.record(pose)
    }

    override fun release() {
        val previous = follower
        try {
            cleanupAll(
                { previous?.drivetrain?.stop() },
                { if(previous != null) PoseRepository.flush() },
            )
        }
        finally {
            follower = null
            followerFactory = null
            lockedHeading = null
            fieldCentricHeading = null
            segmentDistances = emptyList()
            pathArcLength = null
            clearPath()
        }
    }

    private fun clearPath() {
        activePath = null
    }
}
