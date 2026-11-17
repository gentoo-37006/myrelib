package org.firstinspires.ftc.teamcode.component

import com.qualcomm.robotcore.hardware.Servo
import gentoo.myrelib.component.FSM
import gentoo.myrelib.component.StatefulComponent

class Claw : StatefulComponent<Claw.State>() {
    enum class State { CLOSED, OPEN }

    private var servo: Servo? = null

    override fun onAttach() {
        servo = hardware.get(HARDWARE_NAME)
    }

    override val fsm = FSM(State.CLOSED)

    fun open() = changeState { from(State.CLOSED) to State.OPEN }

    fun close() = changeState { from(State.OPEN) to State.CLOSED }

    override fun tick() {
        if(disabled) return
        super.tick()
        applyState()
    }

    private fun applyState() {
        servo?.position = when(state) {
            State.CLOSED -> CLOSED_POSITION
            State.OPEN -> OPEN_POSITION
        }
    }

    override fun onDetach() {
        // Apply any explicit cancellation transition, then drop this run's handle.
        try { if(!disabled) applyState() }
        finally { servo = null }
    }

    companion object {
        const val HARDWARE_NAME = "testClaw"
        const val CLOSED_POSITION = 0.25
        const val OPEN_POSITION = 0.75
    }
}
