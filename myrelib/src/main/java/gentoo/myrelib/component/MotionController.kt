package gentoo.myrelib.component

import com.pedropathing.algorithm.Foresight
import com.pedropathing.drivetrain.DrivePowers
import com.pedropathing.follower.Follower
import com.pedropathing.follower.ManualDrive
import com.pedropathing.paths.Path
import com.qualcomm.robotcore.hardware.HardwareMap
import gentoo.myrelib.math.Pose
import gentoo.myrelib.math.toPedro
import gentoo.myrelib.math.toPose
import gentoo.myrelib.util.PoseRepository
import gentoo.myrelib.field.FieldPoseBridge
import gentoo.myrelib.field.FieldPath
import gentoo.myrelib.robot.BaseRobot
import kotlin.math.hypot

open class MotionController(
    hardware: HardwareAccess,
    followerFactory: (HardwareMap) -> Follower,
    disabled: Boolean
) : Component(hardware, disabled) {
    private val follower = this.hardware.hardwareMapOrNull
        ?.let(followerFactory)
    private var followedSegmentLengths: List<Double> = emptyList()
    private var lockedHeading: Double? = null
    private var nextPathId = 0L
    var activePath: FieldPath? = null
        private set

    open val following get() = follower?.following() == true
    open var pathT = 0.0
        protected set

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
        follower ?: return
        val wasFollowing = follower.following()
        follower.update()
        pathT = when {
            follower.following() -> currentPathT(follower)
            wasFollowing -> 1.0
            else -> pathT
        }
        val updatedPose = follower.pose().toPose()
        pose = updatedPose
        val velocity = follower.velocity()
        speed = hypot(velocity.vx, velocity.vy)
        // Pedro turns counter-clockwise positive, in radians
        turnRate = -Math.toDegrees(velocity.omega)
        PoseRepository.record(updatedPose)
        if(!follower.following()) activePath = null
        FieldPoseBridge.publishPath(activePath, pathT)
        FieldPoseBridge.publish(updatedPose.x, updatedPose.y, updatedPose.theta)
    }

    open fun follow(path: Path) {
        follower ?: return
        pathT = 0.0
        followedSegmentLengths = path.getSegments().map { it.curve.length() }
        follower.follow(path)
        activePath = FieldPath.from(path, ++nextPathId)
        FieldPoseBridge.publishPath(activePath)
        BaseRobot.INSTANCE.recordPath(activePath!!)
    }

    /**
     * Pedro v3 reports a raw t-value for the current atomic segment. Convert it
     * to the raw t-value of the complete path, whose compound parameterization
     * weights each segment by its length.
     */
    private fun currentPathT(follower: Follower): Double {
        val localT = follower.parametricCompletion()
        val segmentIndex = follower.pathIndex()
        if(segmentIndex >= followedSegmentLengths.size) return 1.0
        if(followedSegmentLengths.size <= 1) return localT

        val totalLength = followedSegmentLengths.sum()
        if(totalLength <= 0.0) return localT

        val validSegmentIndex = segmentIndex.coerceIn(followedSegmentLengths.indices)
        val completedLength = followedSegmentLengths.take(validSegmentIndex).sum()
        return ((completedLength + followedSegmentLengths[validSegmentIndex] * localT) / totalLength)
            .coerceIn(0.0, 1.0)
    }

    fun hold(pose: Pose) {
        clearPath()
        follower?.hold(pose.toPedro())
    }

    fun stop() {
        clearPath()
        follower?.stop()
    }

    /**
     * Forces the robot to maintain a specific heading, overriding any heading
     * from manual control. The heading uses myrelib's field coordinates and
     * degree convention. Heading lock requires a Pedro [Foresight] algorithm;
     * other algorithms continue to receive the driver's requested turn power.
     */
    fun lockHeading(heading: Double) {
        lockedHeading = heading.toPedro()
    }

    /**
     * Releases any forced heading, allowing the robot to freely turn.
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
        val powers = DrivePowers(forward, -strafe, -turn)
        applyDrivePowers(powers)
    }

    /**
     * @param forward positive drives away from the driver
     * @param strafe positive strafes to the driver's right
     * @param turn positive rotates clockwise
     * @param forwardHeading the field heading the driver considers forward
     */
    fun moveFieldCentric(
        forward: Double,
        strafe: Double,
        turn: Double,
        forwardHeading: Double
    ) {
        val powers = ManualDrive.fieldCentric(
            forward,
            -strafe,
            -turn,
            follower?.pose()?.heading() ?: 0.0,
            -forwardHeading.toPedro()
        )
        applyDrivePowers(powers)
    }

    private fun applyDrivePowers(powers: DrivePowers) {
        follower ?: return
        val algorithm = follower.algorithm()
        follower.manual(
            if(lockedHeading != null && algorithm is Foresight) {
                ManualDrive.headingLock(
                    follower,
                    algorithm.config.headingFeedback.get(),
                    powers,
                    lockedHeading!!
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
        FieldPoseBridge.publish(pose.x, pose.y, pose.theta)
    }

    override fun release() {
        clearPath()
        follower?.drivetrain?.stop()
        if(follower != null) PoseRepository.flush()
    }

    private fun clearPath() {
        activePath = null
        FieldPoseBridge.publishPath(null)
    }
}
