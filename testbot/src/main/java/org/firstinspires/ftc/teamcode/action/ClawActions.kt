package org.firstinspires.ftc.teamcode.action

import gentoo.myrelib.action.Action
import gentoo.myrelib.action.actionType
import org.firstinspires.ftc.teamcode.component.Claw
import org.firstinspires.ftc.teamcode.robot.Robot

object ClawActions {
    val SET_CLAW by actionType { state: Claw.State ->
        object : Action() {
            override val requirements = setOf(Robot.claw)

            override fun start() {
                when(state) {
                    Claw.State.CLOSED -> Robot.claw.close()
                    Claw.State.OPEN -> Robot.claw.open()
                }
            }

            override fun canContinue() = false
        }
    }

    val HOLD_OPEN by actionType {
        object : Action() {
            private val claw = Robot.claw
            override val requirements = setOf(claw)

            override fun start() = claw.open()

            override fun stop(interrupted: Boolean) = claw.close()
        }
    }
}
