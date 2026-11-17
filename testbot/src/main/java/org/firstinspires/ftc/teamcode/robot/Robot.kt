package org.firstinspires.ftc.teamcode.robot

import gentoo.myrelib.component.HardwareAccess
import gentoo.myrelib.config.ComponentConfig
import gentoo.myrelib.recording.RecordingConfig
import gentoo.myrelib.robot.BaseRobot
import gentoo.myrelib.robot.BaseRobotInstance
import gentoo.myrelib.robot.Context
import gentoo.myrelib.robot.FollowerFactory
import gentoo.myrelib.robot.RobotFactory
import gentoo.myrelib.util.Alliance
import org.firstinspires.ftc.teamcode.component.Claw

class RobotInstance(context: Context) : BaseRobotInstance(context) {
    val claw by component(::Claw)
}

object RobotConfig : ComponentConfig() {
    override fun componentBindings() = bind(
        RobotInstance::motion to true,
        RobotInstance::claw to false
    )
}

/** A consumer-owned singleton, independent of any real season or drivetrain. */
object Robot : BaseRobot<RobotInstance>() {
    override val config = RobotConfig
    override val robotFactory: RobotFactory<RobotInstance> = ::RobotInstance
    override val followerFactory: FollowerFactory = {
        error("Test TeamCode has no drivetrain; supply a Pedro follower before enabling motion")
    }
    override val WIDTH = 16.0
    override val LENGTH = 16.0

    // Desktop smoke tests do not create match files. Real consumers can use the default.
    override val recordingConfig = RecordingConfig(enabled = false)

    val claw: Claw get() = instance.claw

    /** Exercises full lifecycle and scheduling without an Android app context. */
    fun initForTest(
        alliance: Alliance = Alliance.BLUE,
        hardware: HardwareAccess = HardwareAccess.empty(),
        componentConfig: ComponentConfig = config
    ) {
        init(alliance, "Test TeamCode") { selectedAlliance ->
            robotFactory(Context(hardware, componentConfig, followerFactory, selectedAlliance))
        }
    }
}
