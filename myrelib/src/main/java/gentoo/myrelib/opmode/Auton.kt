package gentoo.myrelib.opmode

import gentoo.myrelib.pathing.Auton
import gentoo.myrelib.pathing.AutonSequence
import gentoo.myrelib.robot.BaseRobot
import gentoo.myrelib.util.Alliance
import gentoo.myrelib.util.PoseRepository

@Auton
abstract class Auton(
    final override val alliance: Alliance,
    private val defineAuton: AutonSequence.Factory.() -> AutonSequence.Builder
) : RobotOpMode(alliance) {
    private lateinit var autonSequence: AutonSequence

    final override fun initStart() {
        PoseRepository.beginAutonTracking(alliance)
        autonSequence = defineAuton(AutonSequence.Factory(alliance)).build()
        BaseRobot.INSTANCE.motion.setPose(autonSequence.startPose)
    }

    final override fun initTick() {
    }

    final override fun playStart() {
        autonSequence.start()
    }

    final override fun playTick() {
    }

    final override fun playStop() {
        PoseRepository.completeAutonTracking(BaseRobot.INSTANCE.strictPose)
    }
}