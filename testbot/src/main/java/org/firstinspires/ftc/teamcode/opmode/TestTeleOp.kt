package org.firstinspires.ftc.teamcode.opmode

import com.qualcomm.robotcore.eventloop.opmode.OpMode
import com.qualcomm.robotcore.eventloop.opmode.TeleOp
import gentoo.myrelib.input.FtcInputBindings
import org.firstinspires.ftc.teamcode.action.ClawActions
import org.firstinspires.ftc.teamcode.robot.Robot

@TeleOp(name = "Test TeamCode", group = "Framework tests")
class TestTeleOp : OpMode() {
    private var inputs: FtcInputBindings? = null

    override fun init() {
        Robot.init(null, hardwareMap, "Test TeamCode")
        inputs = FtcInputBindings(gamepad1, gamepad2).apply {
            driver.a.onChange(ClawActions.HOLD_OPEN)
        }
    }

    override fun loop() {
        inputs?.poll()
        // This also ticks the action controller; never tick it separately.
        Robot.components.tick()
        Robot.recordState()
        telemetry.addData("Claw (hold A to open)", Robot.claw.state)
    }

    override fun stop() {
        inputs?.clear()
        inputs = null
        Robot.reset()
    }
}
