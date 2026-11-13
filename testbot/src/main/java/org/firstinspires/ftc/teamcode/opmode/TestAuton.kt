package org.firstinspires.ftc.teamcode.opmode

import com.qualcomm.robotcore.eventloop.opmode.OpMode
import gentoo.myrelib.pathing.Auton
import gentoo.myrelib.pathing.AutonSequence
import gentoo.myrelib.util.Alliance
import org.firstinspires.ftc.teamcode.robot.Robot

@Auton
abstract class TestAuton(protected val alliance: Alliance) : OpMode() {
    private lateinit var sequence: AutonSequence

    protected abstract fun buildSequence(): AutonSequence

    override fun init() {
        Robot.init(alliance, hardwareMap, javaClass.simpleName)
        sequence = buildSequence()
        Robot.motion.setPose(sequence.startPose)
    }

    override fun init_loop() {
        Robot.components.tick()
        Robot.recordState("INIT")
    }

    override fun start() = sequence.start()

    override fun loop() {
        Robot.components.tick()
        Robot.recordState()
        telemetry.addData("Step", sequence.actionIndex)
    }

    override fun stop() = Robot.reset()
}
