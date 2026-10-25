package gentoo.myrelib.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FSMTest {
    private enum class State { IDLE, RUNNING, DONE }

    @Test
    fun `transition callbacks see the old state and same state assignments do nothing`() {
        val observed = mutableListOf<State>()
        val fsm = FSM(State.IDLE) {
            transition[State.IDLE to State.RUNNING] = { observed.add(state) }
            transition[State.RUNNING to State.RUNNING] = { error("Same state callback") }
        }

        fsm.state = State.RUNNING
        fsm.state = State.RUNNING
        assertEquals(listOf(State.IDLE), observed)
        assertEquals(State.RUNNING, fsm.state)
        fsm.state = State.DONE
        assertEquals(State.DONE, fsm.state)
    }

    @Test
    fun `a ticker transition does not tick the destination until the next tick`() {
        val calls = mutableListOf<State>()
        val fsm = FSM(State.IDLE) {
            tick[State.IDLE] = {
                calls.add(state)
                state = State.RUNNING
            }
            tick[State.RUNNING] = { calls.add(state) }
        }

        fsm.tick()
        assertEquals(listOf(State.IDLE), calls)
        fsm.tick()
        assertEquals(listOf(State.IDLE, State.RUNNING), calls)
        fsm.state = State.DONE
        fsm.tick()
        assertEquals(2, calls.size)
    }

    @Test
    fun `a failed callback keeps the old state and allows a later transition`() {
        val fsm = FSM(State.IDLE) {
            transition[State.IDLE to State.RUNNING] = { error("Failed") }
        }

        assertThrows(IllegalStateException::class.java) { fsm.state = State.RUNNING }
        assertEquals(State.IDLE, fsm.state)
        fsm.transition.clear()
        fsm.state = State.RUNNING
        assertEquals(State.RUNNING, fsm.state)
    }

    @Test
    fun `recursive transitions fail instead of silently overwriting a nested state`() {
        val fsm = FSM(State.IDLE) {
            transition[State.IDLE to State.RUNNING] = { state = State.DONE }
        }

        assertThrows(IllegalStateException::class.java) { fsm.state = State.RUNNING }
        assertEquals(State.IDLE, fsm.state)
        fsm.state = State.DONE
        assertEquals(State.DONE, fsm.state)
    }
}
