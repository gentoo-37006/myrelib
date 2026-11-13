package gentoo.myrelib.opmode

import gentoo.myrelib.util.Alliance
import gentoo.myrelib3.robot.BaseRobot

abstract class RobotOpMode(
    private val alliance: Alliance?,
) : OpMode() {
    override fun initStartPre() {
        BaseRobot.INSTANCE.initFull(alliance, hardwareMap, javaClass.simpleName)
    }

    override fun playTickPre() {
        BaseRobot.INSTANCE.components.tick()
    }
}