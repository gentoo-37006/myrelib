package org.firstinspires.ftc.teamcode.robot

import com.qualcomm.robotcore.eventloop.opmode.OpMode
import gentoo.myrelib.hardware.HardwareAccess
import gentoo.myrelib.config.ComponentConfig
import gentoo.myrelib.robot.BaseRobot
import gentoo.myrelib.robot.FollowerFactory
import gentoo.myrelib.util.Alliance
import org.firstinspires.ftc.teamcode.component.Claw

object RobotConfig : ComponentConfig() {
    override fun componentBindings() = bind(
        Robot::motion to true,
        Robot::claw to false
    )
}

/** A consumer-owned singleton, independent of any real season or drivetrain. */
object Robot : BaseRobot() {
    override val config = RobotConfig
    override val followerFactory: FollowerFactory = {
        error("Test TeamCode has no drivetrain; supply a Pedro follower before enabling motion")
    }
    override val WIDTH = 16.0
    override val LENGTH = 16.0

    val claw by component(::Claw)

    /** Exercises full lifecycle and scheduling without an Android app context. */
    fun startOpModeForTest(
        opMode: OpMode,
        alliance: Alliance = Alliance.BLUE,
        hardware: HardwareAccess = HardwareAccess.empty(),
        componentConfig: ComponentConfig = config
    ) {
        startOpMode(opMode, alliance, hardware, componentConfig, followerFactory)
    }
}
