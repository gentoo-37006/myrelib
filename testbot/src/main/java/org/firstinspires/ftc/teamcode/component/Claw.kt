package org.firstinspires.ftc.teamcode.component

import com.qualcomm.robotcore.hardware.Servo
import gentoo.myrelib.component.FSM
import gentoo.myrelib.component.HardwareAccess
import gentoo.myrelib.component.StatefulComponent

class Claw(hardware: HardwareAccess, disabled: Boolean) : StatefulComponent<Claw.State>(hardware, disabled) {
    enum class State { CLOSED, OPEN }

    // Always use the component's scoped access, including during construction.
    private val servo = this.hardware.get<Servo>(HARDWARE_NAME)
    override val fsm = FSM(State.CLOSED)

    fun open() = changeState { from(State.CLOSED) to State.OPEN }

    fun close() = changeState { from(State.OPEN) to State.CLOSED }

    override fun tick() {
        if(disabled) return
        super.tick()
        servo?.position = when(state) {
            State.CLOSED -> CLOSED_POSITION
            State.OPEN -> OPEN_POSITION
        }
    }

    override fun release() {
        close()
        if(!disabled) servo?.position = CLOSED_POSITION
    }

    companion object {
        const val HARDWARE_NAME = "testClaw"
        const val CLOSED_POSITION = 0.25
        const val OPEN_POSITION = 0.75
    }
}
