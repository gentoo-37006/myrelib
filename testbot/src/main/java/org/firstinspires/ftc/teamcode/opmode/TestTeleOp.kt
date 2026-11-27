package org.firstinspires.ftc.teamcode.opmode

import com.qualcomm.robotcore.eventloop.opmode.TeleOp
import gentoo.myrelib.input.GamepadTriggers
import gentoo.myrelib.opmode.Teleop
import gentoo.myrelib.util.Alliance
import org.firstinspires.ftc.teamcode.action.ClawActions
import org.firstinspires.ftc.teamcode.robot.Robot

// Initialize the sandbox robot before Teleop looks up the singleton.
@TeleOp(name = "Test TeamCode", group = "Framework tests")
class TestTeleOp : Teleop(Robot.run { Alliance.BLUE }) {
    override fun driverBindings(gamepad: GamepadTriggers) {
        gamepad.a.onChange(ClawActions.HOLD_OPEN)
    }

    override fun operatorBindings(gamepad: GamepadTriggers) = Unit

    override fun playTick() {
        telemetry.addData("Claw (hold A to open)", Robot.claw.state)
    }
}
