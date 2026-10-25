package gentoo.myrelib.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionLifecycleTest {
    @Test
    fun `an action starts, ticks, then stops exactly once`() {
        val action = FakeAction(ticksToRun = 3)
        action.runToCompletion()
        assertEquals(
            listOf(
                "action:start",
                "action:tick",
                "action:tick",
                "action:tick",
                "action:stop"
            ),
            action.events
        )
        assertEquals(false, action.interrupted)
    }

    @Test
    fun `canContinue is checked before tick, so a finished action never ticks again`() {
        val action = FakeAction(ticksToRun = 0)
        action.runToCompletion()
        assertEquals(listOf("action:start", "action:stop"), action.events)
        assertEquals(0, action.ticks)
    }

    @Test
    fun `callbacks fire around the action's own hooks`() {
        val log = mutableListOf<String>()
        val action = FakeAction(ticksToRun = 1, log = log)
            .onStart { log.add("callback:start") }
            .onStop { log.add("callback:stop") }

        action.runToCompletion()

        assertEquals(
            listOf(
                "callback:start",
                "action:start",
                "action:tick",
                "callback:stop",
                "action:stop"
            ),
            log
        )
    }

    @Test
    fun `a timeout stops an action that would otherwise run forever`() {
        val action = FakeAction(ticksToRun = Int.MAX_VALUE)
        action.timeout(0.02)
        action.doStart()

        // only the elapsed direction is asserted here; that the timeout doesn't
        // fire early is covered separately with a duration nothing can outrun,
        // so neither test races the machine
        Thread.sleep(200)

        assertTrue("should have timed out", action.tickOrStop().stopped())
        assertTrue(action.events.contains("action:stop"))
        assertEquals(true, action.interrupted)
    }

    @Test
    fun `an externally stopped action is told it was interrupted`() {
        val action = FakeAction(ticksToRun = 100)
        action.doStart()

        action.doStop(interrupted = true)

        assertEquals(true, action.interrupted)
    }

    @Test
    fun `an unexpired timeout does not stop the action`() {
        val action = FakeAction(ticksToRun = Int.MAX_VALUE)
        action.timeout(30.0)
        action.doStart()
        repeat(5) { assertTrue(action.tickOrStop().running()) }
    }

    @Test
    fun `no timeout means the action runs until it says otherwise`() {
        val action = FakeAction(ticksToRun = 50)
        assertEquals(51, action.runToCompletion())
    }

    @Test
    fun `an instant action runs once and immediately reports it is done`() {
        var runs = 0
        val instant = Instant { runs++ }

        instant.doStart()
        assertEquals(1, runs)
        assertTrue(instant.tickOrStop().stopped())
        assertEquals("tickOrStop should not run the body again", 1, runs)
    }

    @Test
    fun `debug lines name the action`() {
        assertEquals(listOf("-> FakeAction"), FakeAction().getDebugLines())
    }
}
