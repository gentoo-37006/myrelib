package gentoo.myrelib.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StatefulComponentTest {
    private enum class State { READY, PAUSED, RUNNING, DONE }

    private class TestComponent(initial: State = State.READY, disabled: Boolean = false) :
        StatefulComponent<State>(disabled) {
        override val fsm = FSM(initial)
        val transitions = mutableListOf<Pair<State, State>>()

        init {
            for(source in State.entries) {
                for(destination in State.entries) {
                    fsm.transition[source to destination] = {
                        transitions.add(source to destination)
                    }
                }
            }
        }

        fun change(config: StateChangeRules<State>.() -> Unit) = changeState(config)

        fun start() = changeState {
            from(State.READY, State.PAUSED) to State.RUNNING
        }

        fun onTransition(callback: () -> Unit) {
            fsm.transition[State.READY to State.RUNNING] = callback
        }
    }

    @Test
    fun `grouped sources reach one destination through the FSM callback`() {
        for(source in listOf(State.READY, State.PAUSED)) {
            val component = TestComponent(source)
            component.start()
            assertEquals(State.RUNNING, component.state)
            assertEquals(listOf(source to State.RUNNING), component.transitions)
        }
    }

    @Test
    fun `separate rules can select different destinations`() {
        for(source in listOf(State.READY, State.RUNNING)) {
            val component = TestComponent(source)
            component.change {
                from(State.READY) to State.PAUSED
                from(State.RUNNING) to State.DONE
            }
            val expected = if(source == State.READY) State.PAUSED else State.DONE
            assertEquals(expected, component.state)
            assertEquals(listOf(source to expected), component.transitions)
        }
    }

    @Test
    fun `only the first matching rule applies and rules do not chain`() {
        val component = TestComponent()
        component.change {
            from(State.READY) to State.RUNNING
            from(State.READY) to State.PAUSED
            from(State.RUNNING) to State.DONE
            assertEquals(State.READY, component.state)
        }
        assertEquals(State.RUNNING, component.state)
        assertEquals(listOf(State.READY to State.RUNNING), component.transitions)
    }

    @Test
    fun `unmatched empty and matched self transition blocks leave state unchanged`() {
        val component = TestComponent()
        component.change {}
        component.change { from(State.RUNNING) to State.DONE }
        component.change {
            from(State.READY) to State.READY
            from(State.READY) to State.RUNNING
        }
        assertEquals(State.READY, component.state)
        assertEquals(emptyList<Pair<State, State>>(), component.transitions)
    }

    @Test
    fun `disabled components do not evaluate rule blocks`() {
        val component = TestComponent(disabled = true)
        component.change { error("Must not evaluate") }
        component.start()
        assertEquals(State.READY, component.state)
        assertEquals(emptyList<Pair<State, State>>(), component.transitions)
    }

    @Test
    fun `a failed rule block does not commit its selected destination`() {
        val component = TestComponent()
        assertThrows(IllegalStateException::class.java) {
            component.change {
                from(State.READY) to State.RUNNING
                error("Failed rule construction")
            }
        }
        assertEquals(State.READY, component.state)
        component.start()
        assertEquals(State.RUNNING, component.state)
    }

    @Test
    fun `rules require at least one source`() {
        val component = TestComponent()
        assertThrows(IllegalArgumentException::class.java) {
            component.change { from() to State.RUNNING }
        }
        assertEquals(State.READY, component.state)
    }

    @Test
    fun `transition callback failures still leave the previous FSM state`() {
        val component = TestComponent()
        component.onTransition { error("Failed transition") }
        assertThrows(IllegalStateException::class.java) { component.start() }
        assertEquals(State.READY, component.state)
    }
}
