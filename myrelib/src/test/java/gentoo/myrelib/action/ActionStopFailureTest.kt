package gentoo.myrelib.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionStopFailureTest {
    @Test
    fun `a failing stop callback still runs the stop hook once`() {
        for(interrupted in listOf(false, true)) {
            val events = mutableListOf<String>()
            val failure = IllegalStateException("callback failed")
            val action = object : Action() {
                override fun canContinue() = false
                override fun stop(interrupted: Boolean) { events += "stop:$interrupted" }
            }.onStop {
                events += "callback"
                throw failure
            }
            action.doStart()

            assertSame(failure, assertThrows(IllegalStateException::class.java) {
                if(interrupted) action.doStop(true) else action.tickOrStop()
            })
            action.doStop(true)

            assertEquals(listOf("callback", "stop:$interrupted"), events)
            assertTrue(action.tickOrStop().stopped())
        }
    }

    @Test
    fun `callback failure stays primary when the stop hook also fails`() {
        val callbackFailure = IllegalStateException("callback failed")
        val hookFailure = IllegalArgumentException("hook failed")
        val action = object : Action() {
            override fun stop(interrupted: Boolean) { throw hookFailure }
        }.onStop { throw callbackFailure }
        action.doStart()

        val thrown = assertThrows(IllegalStateException::class.java) { action.doStop(true) }

        assertSame(callbackFailure, thrown)
        assertEquals(listOf<Throwable>(hookFailure), thrown.suppressed.toList())
        action.doStop(true)
        assertEquals(1, thrown.suppressed.size)
    }

    @Test
    fun `a shared callback and hook failure is not suppressed onto itself`() {
        val failure = AssertionError("shared failure")
        var stops = 0
        val action = object : Action() {
            override fun stop(interrupted: Boolean) {
                stops++
                throw failure
            }
        }.onStop { throw failure }
        action.doStart()

        assertSame(failure, assertThrows(AssertionError::class.java) { action.doStop(true) })
        assertEquals(1, stops)
        assertTrue(failure.suppressed.isEmpty())
    }

    @Test
    fun `parallel cancellation stops every child despite failures`() = assertStopsChildren {
        Parallel(*it.toTypedArray())
    }

    @Test
    fun `race cancellation stops every child despite failures`() = assertStopsChildren {
        Race(*it.toTypedArray())
    }

    @Test
    fun `deadline cancellation stops siblings when the deadline stop fails`() = assertStopsChildren {
        Deadline(it.first(), *it.drop(1).toTypedArray())
    }

    @Test
    fun `a winning race stops all losers even when one stop fails`() = assertStopsChildren(
        finishFirst = true,
    ) {
        Race(FakeAction(ticksToRun = 0), *it.toTypedArray())
    }

    @Test
    fun `a completed deadline stops all siblings even when one stop fails`() = assertStopsChildren(
        finishFirst = true,
    ) {
        Deadline(FakeAction(ticksToRun = 0), *it.toTypedArray())
    }

    @Test
    fun `a failing group stop callback still stops its children`() {
        val failure = IllegalStateException("group callback failed")
        val first = FakeAction(ticksToRun = 100)
        val second = FakeAction(ticksToRun = 100)
        val group = Parallel(first, second).onStop { throw failure }
        group.doStart()

        assertSame(failure, assertThrows(IllegalStateException::class.java) { group.doStop(true) })

        assertEquals(true, first.interrupted)
        assertEquals(true, second.interrupted)
    }

    private fun assertStopsChildren(
        finishFirst: Boolean = false,
        factory: (List<ActionInstance>) -> Action,
    ) {
        val events = mutableListOf<String>()
        val firstFailure = IllegalStateException("first stop failed")
        val secondFailure = IllegalArgumentException("second stop failed")
        fun child(name: String, failure: Throwable? = null) = object : Action() {
            override fun stop(interrupted: Boolean) {
                events += "$name:$interrupted"
                failure?.let { throw it }
            }
        }
        val group = factory(listOf(
            child("first", firstFailure),
            child("second", secondFailure),
            child("healthy"),
        ))
        group.doStart()

        val thrown = assertThrows(IllegalStateException::class.java) {
            if(finishFirst) group.tickOrStop() else group.doStop(true)
        }

        assertSame(firstFailure, thrown)
        assertEquals(listOf<Throwable>(secondFailure), thrown.suppressed.toList())
        group.doStop(true)
        assertTrue(group.tickOrStop().stopped())
        assertEquals(listOf("first:true", "second:true", "healthy:true"), events)
    }
}
