package gentoo.myrelib.opmode

import gentoo.myrelib.robot.BaseRobot
import gentoo.myrelib.util.Alliance

abstract class RobotOpMode(
    private val alliance: Alliance?,
) : OpMode() {
    override fun initStartPre() {
        BaseRobot.INSTANCE.init(alliance, hardwareMap, javaClass.simpleName)
    }

    override fun playTickPre() {
        BaseRobot.INSTANCE.components.tick()
    }
}