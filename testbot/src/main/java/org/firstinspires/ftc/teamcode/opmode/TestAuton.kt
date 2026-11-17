package org.firstinspires.ftc.teamcode.opmode

import gentoo.myrelib.opmode.RobotOpMode
import gentoo.myrelib.pathing.Auton
import gentoo.myrelib.pathing.AutonSequence
import gentoo.myrelib.util.Alliance
import org.firstinspires.ftc.teamcode.robot.Robot

@Auton
abstract class TestAuton(protected val alliance: Alliance) : RobotOpMode(alliance, Robot) {
    private lateinit var sequence: AutonSequence
    protected abstract fun buildSequence(): AutonSequence

    override fun initStart() {
        sequence = buildSequence()
        Robot.motion.setPose(sequence.startPose)
        Robot.startRecording()
    }

    override fun initTick() = Robot.recordState()

    override fun playStart() = sequence.start()

    override fun playTick() {
        Robot.recordState()
        telemetry.addData("Step", sequence.actionIndex)
    }
}
