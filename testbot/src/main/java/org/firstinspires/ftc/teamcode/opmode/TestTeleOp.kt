package org.firstinspires.ftc.teamcode.opmode

import com.qualcomm.robotcore.eventloop.opmode.TeleOp
import gentoo.myrelib.opmode.RobotOpMode
import org.firstinspires.ftc.teamcode.action.ClawActions
import org.firstinspires.ftc.teamcode.robot.Robot

@TeleOp(name = "Test TeamCode", group = "Framework tests")
class TestTeleOp : RobotOpMode(robot = Robot) {
    override fun initStart() {
        inputs.driver.a.onChange(ClawActions.HOLD_OPEN)
    }

    override fun playTick() {
        telemetry.addData("Claw (hold A to open)", Robot.claw.state)
    }
}
