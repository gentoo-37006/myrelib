package gentoo.myrelib.action

import gentoo.myrelib.component.ActionController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An action that chains into another one from its own tick or stop. This is the
 * shape [Instant] exists to support, so the controller has to tolerate it.
 */
private class ChainingAction(
    private val controller: ActionController,
    private val onTick: () -> Unit = {},
    private val onStop: () -> Unit = {},
    private val ticksToRun: Int = 1
) : Action() {
    var ticks = 0

    override fun canContinue() = ticks < ticksToRun

    override fun tick() {
        ticks++
        onTick()
    }

    override fun stop(interrupted: Boolean) = onStop()
}

class ActionControllerTest {
    private val controller = ActionController(disabled = false)

    private val started = mutableListOf<String>()
    private val stopped = mutableListOf<String>()
    private val interruptions = mutableListOf<Pair<String, Boolean>>()

    private fun recording(name: String, ticksToRun: Int = 1) = object : Action() {
        var ticks = 0
        override fun start() { started += name }
        override fun canContinue() = ticks < ticksToRun
        override fun tick() { ticks++ }
        override fun stop(interrupted: Boolean) {
            stopped += name
            interruptions += name to interrupted
        }
    }

    @Test
    fun `an action runs until it reports it is done`() {
        val action by actionType { recording("solo", ticksToRun = 2) }
        controller.submit(action)
        assertTrue(controller.isRunning(action))

        controller.tick()
        assertTrue(controller.isRunning(action))
        controller.tick()
        assertTrue(controller.isRunning(action))

        // the tick after the last one sees canContinue go false
        controller.tick()
        assertFalse(controller.isRunning(action))
        assertEquals(listOf("solo"), started)
        assertEquals(listOf("solo"), stopped)
        assertEquals(listOf("solo" to false), interruptions)
    }

    @Test
    fun `starting an already running action does nothing`() {
        val action by actionType { recording("dupe", ticksToRun = 5) }
        controller.submit(action)
        controller.submit(action)
        controller.submit(action)
        assertEquals(listOf("dupe"), started)
    }

    @Test
    fun `stopping an action runs its stop hook once`() {
        val action by actionType { recording("halted", ticksToRun = 100) }
        controller.submit(action)
        controller.remove(action)
        assertFalse(controller.isRunning(action))
        assertEquals(listOf("halted"), stopped)
        assertEquals(listOf("halted" to true), interruptions)

        // stopping something that isn't running is a no-op, not a second stop
        controller.remove(action)
        assertEquals(listOf("halted"), stopped)
    }

    @Test
    fun `removeAll stops every running action`() {
        val first by actionType { recording("first", ticksToRun = 100) }
        val second by actionType { recording("second", ticksToRun = 100) }
        controller.submit(first)
        controller.submit(second)

        controller.removeAll()

        assertFalse(controller.isRunning(first))
        assertFalse(controller.isRunning(second))
        assertEquals(setOf("first", "second"), stopped.toSet())
        assertEquals(
            setOf("first" to true, "second" to true),
            interruptions.toSet()
        )
    }

    @Test
    fun `a disabled controller refuses to start actions`() {
        val disabled = ActionController(disabled = true)
        val action by actionType { recording("nope") }
        disabled.submit(action)
        assertFalse(disabled.isRunning(action))
        assertEquals(emptyList<String>(), started)
    }

    @Test
    fun `an action can start another action from its tick`() {
        val chained by actionType { recording("chained", ticksToRun = 5) }
        val starter by actionType {
            ChainingAction(controller, onTick = { controller.submit(chained) })
        }

        controller.submit(starter)
        controller.tick()

        assertTrue(controller.isRunning(chained))
    }

    @Test
    fun `an action can stop another action from its tick`() {
        val victim by actionType { recording("victim", ticksToRun = 100) }
        val stopper by actionType {
            ChainingAction(controller, onTick = { controller.remove(victim) })
        }

        controller.submit(victim)
        controller.submit(stopper)
        controller.tick()

        assertFalse(controller.isRunning(victim))
    }

    @Test
    fun `an action cannot restart itself from its own stop hook`() {
        // it is still registered while its stop hook runs, and submit is a
        // no-op for an action that is already running, so the restart is dropped.
        // worth knowing before someone tries to build a loop out of it
        lateinit var self: ActionType
        val restarting by actionType {
            ChainingAction(controller, onStop = { controller.submit(self) })
        }
        self = restarting

        controller.submit(restarting)
        controller.tick()
        controller.tick()

        assertFalse(controller.isRunning(restarting))
    }

    @Test
    fun `explicit removal rejects self submission until the stop hook returns`() {
        lateinit var self: ActionType
        var creations = 0
        val restarting by actionType {
            creations++
            recording("restarting", 100).onStop {
                controller.submit(self)
                controller.submit(self, 1.0)
            }
        }
        self = restarting
        controller.submit(restarting)

        controller.remove(restarting)

        assertFalse(controller.isRunning(restarting))
        assertEquals(1, creations)
        assertEquals(listOf("restarting" to true), interruptions)

        // Cancellation must not prevent a later, deliberate restart.
        controller.submit(restarting)
        assertTrue(controller.isRunning(restarting))
        assertEquals(2, creations)
    }

    @Test
    fun `a failing stop hook does not permanently block its action type`() {
        val failure = IllegalStateException("stop failed")
        val action by actionType {
            recording("failing", 100).onStop { throw failure }
        }
        controller.submit(action)

        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            controller.remove(action)
        })

        controller.submit(action)
        assertTrue(controller.isRunning(action))
        assertEquals(listOf("failing", "failing"), started)
    }

    @Test
    fun `an action replaced during the same tick keeps the replacement`() {
        // the controller must not mistake the fresh instance for the one it
        // snapshotted at the top of the tick and drop it on the floor
        val victim by actionType { recording("victim", ticksToRun = 100) }
        val replacer by actionType {
            ChainingAction(
                controller,
                onTick = {
                    controller.remove(victim)
                    controller.submit(victim)
                },
                ticksToRun = 100
            )
        }

        controller.submit(victim)
        controller.submit(replacer)
        controller.tick()

        assertTrue(controller.isRunning(victim))
        assertEquals("the replacement should have been started", 2, started.count { it == "victim" })
        assertEquals("only the original should have been stopped", 1, stopped.count { it == "victim" })
    }

    @Test
    fun `removeAll rejects new submissions from stop hooks`() {
        var lateCreations = 0
        val late by actionType {
            lateCreations++
            recording("late", ticksToRun = 5)
        }
        val closing by actionType {
            ChainingAction(controller, onStop = { controller.submit(late) }, ticksToRun = 100)
        }

        controller.submit(closing)
        controller.removeAll()

        assertFalse(controller.isRunning(closing))
        assertFalse(controller.isRunning(late))
        assertFalse(controller.isStaged(late))
        assertEquals(0, lateCreations)
        assertTrue(controller.runningActions.isEmpty())
    }

    @Test
    fun `nested removeAll keeps submissions blocked until all stop hooks return`() {
        lateinit var self: ActionType
        val late by actionType { recording("late", 100) }
        val first by actionType {
            recording("first", 100).onStop {
                controller.removeAll()
                controller.submit(self)
                controller.submit(late, 1.0)
            }
        }
        self = first
        val second by actionType {
            recording("second", 100).onStop { controller.submit(late) }
        }
        controller.submit(first)
        controller.submit(second)

        controller.removeAll()

        assertTrue(controller.runningActions.isEmpty())
        assertEquals(listOf("first", "second"), started)
        assertEquals(listOf("first" to true, "second" to true), interruptions)

        controller.submit(late)
        assertTrue(controller.isRunning(late))
    }

    @Test
    fun `a failing removeAll stop hook does not leave submissions blocked`() {
        val failure = IllegalStateException("stop failed")
        val failing by actionType {
            recording("failing", 100).onStop { throw failure }
        }
        val later by actionType { recording("later", 100) }
        controller.submit(failing)

        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            controller.removeAll()
        })

        controller.submit(later)
        assertTrue(controller.isRunning(later))
    }

    @Test
    fun `a failing stop hook during removeAll does not skip the other actions`() {
        // removeAll is the teardown reset relies on; one action's failing stop
        // must not leave the actions after it running with their hardware on
        val failure = IllegalStateException("stop failed")
        val failing by actionType {
            recording("failing", 100).onStop { throw failure }
        }
        val other by actionType { recording("other", 100) }
        controller.submit(failing)
        controller.submit(other)

        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            controller.removeAll()
        })

        assertEquals(listOf("failing", "other"), stopped)
        assertFalse(controller.isRunning(other))
    }

    @Test
    fun `removeAll reports every stop failure, the first as primary`() {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        val failingFirst by actionType { recording("a", 100).onStop { throw first } }
        val failingSecond by actionType { recording("b", 100).onStop { throw second } }
        controller.submit(failingFirst)
        controller.submit(failingSecond)

        val thrown = assertThrows(IllegalStateException::class.java) {
            controller.removeAll()
        }

        assertSame(first, thrown)
        assertEquals(listOf<Throwable>(second), thrown.suppressed.toList())
    }

    @Test
    fun `removing one action still permits a different successor from its stop hook`() {
        val requirement = Any()
        val successor by actionType { recording("successor", 100).requiring(requirement) }
        val predecessor by actionType {
            recording("predecessor", 100)
                .requiring(requirement)
                .onStop { controller.submit(successor) }
        }
        controller.submit(predecessor)

        controller.remove(predecessor)

        assertFalse(controller.isRunning(predecessor))
        assertTrue(controller.isRunning(successor))
        assertEquals(listOf("predecessor" to true), interruptions)
    }

    @Test
    fun `chaining works no matter where the action falls in iteration order`() {
        // the old implementation walked the live map, so whether it blew up
        // depended on hash order; run enough actions to shake that out
        repeat(16) { index ->
            val filler by actionType { recording("filler$index", ticksToRun = 100) }
            controller.submit(filler)
        }
        val chained by actionType { recording("chained", ticksToRun = 100) }
        val starter by actionType {
            ChainingAction(controller, onTick = { controller.submit(chained) }, ticksToRun = 100)
        }
        controller.submit(starter)

        repeat(3) { controller.tick() }

        assertTrue(controller.isRunning(chained))
    }

    @Test
    fun `an action can start another action as it stops`() {
        val successor by actionType { recording("successor", ticksToRun = 5) }
        val predecessor by actionType {
            ChainingAction(controller, onStop = { controller.submit(successor) })
        }

        controller.submit(predecessor)
        controller.tick()
        // the tick after this one ends the predecessor, whose stop starts the successor
        controller.tick()

        assertTrue(controller.isRunning(successor))
    }

    @Test
    fun `a naturally finishing action can start a conflicting successor from stop`() {
        val requirement = Any()
        val successor by actionType { recording("successor", 5).requiring(requirement) }
        val predecessor by actionType {
            ChainingAction(
                controller,
                onStop = { controller.submit(successor) },
            ).requiring(requirement)
        }

        controller.submit(predecessor)
        controller.tick()
        controller.tick()

        assertFalse(controller.isRunning(predecessor))
        assertTrue(controller.isRunning(successor))
        assertEquals("successor was not interrupted", 0, stopped.count { it == "successor" })
    }

    @Test
    fun `requirements can be arbitrary objects`() {
        val requirement = Any()
        val incumbent by actionType { recording("incumbent", 100).requiring(requirement) }
        val incoming by actionType {
            recording("incoming", 100)
                .requiring(requirement)
                .withConflictBehavior(ConflictBehavior.CANCEL)
        }

        controller.submit(incumbent)
        controller.submit(incoming)

        assertTrue(controller.isRunning(incumbent))
        assertFalse(controller.isRunning(incoming))
        assertFalse(controller.isStaged(incoming))
    }

    @Test
    fun `higher priority ends a conflicting incumbent by default`() {
        val requirement = Any()
        val incumbent by actionType { recording("incumbent", 100).requiring(requirement) }
        val incoming by actionType {
            recording("incoming", 100)
                .requiring(requirement)
                .withPriority(ActionPriority.HIGH)
        }

        controller.submit(incumbent)
        controller.submit(incoming)

        assertFalse(controller.isRunning(incumbent))
        assertFalse(controller.isStaged(incumbent))
        assertTrue(controller.isRunning(incoming))
        assertEquals(true, interruptions.single { it.first == "incumbent" }.second)
    }

    @Test
    fun `a suspended action restarts the same instance when its requirement is released`() {
        val requirement = Any()
        lateinit var instance: Action
        val incumbent by actionType {
            recording("incumbent", 100)
                .requiring(requirement)
                .withInterruptedBehavior(InterruptedBehavior.SUSPEND)
                .also { instance = it }
        }
        val incoming by actionType {
            recording("incoming", 100)
                .requiring(requirement)
                .withPriority(ActionPriority.HIGH)
        }

        controller.submit(incumbent)
        controller.submit(incoming)
        assertFalse(controller.isRunning(incumbent))
        assertTrue(controller.isStaged(incumbent))

        controller.remove(incoming)

        assertTrue(controller.isRunning(incumbent))
        assertFalse(controller.isStaged(incumbent))
        assertSame(instance, controller.runningActions.getValue(incumbent))
        assertEquals(listOf("incumbent" to true), interruptions.filter { it.first == "incumbent" })
        assertEquals(2, started.count { it == "incumbent" })
    }

    @Test
    fun `a blocked action can queue and starts when its requirement is released`() {
        val requirement = Any()
        val incumbent by actionType {
            recording("incumbent", 100)
                .requiring(requirement)
                .withPriority(ActionPriority.HIGH)
        }
        val incoming by actionType {
            recording("incoming", 100)
                .requiring(requirement)
                .withPriority(ActionPriority.LOW)
                .withBlockedBehavior(BlockedBehavior.QUEUE)
        }

        controller.submit(incumbent)
        controller.submit(incoming)
        assertTrue(controller.isStaged(incoming))
        assertEquals(0, started.count { it == "incoming" })

        controller.remove(incumbent)

        assertTrue(controller.isRunning(incoming))
        assertFalse(controller.isStaged(incoming))
        assertEquals(1, started.count { it == "incoming" })
    }

    @Test
    fun `equal priority queue waits instead of overriding`() {
        val requirement = Any()
        val incumbent by actionType { recording("incumbent", 100).requiring(requirement) }
        val incoming by actionType {
            recording("incoming", 100)
                .requiring(requirement)
                .withConflictBehavior(ConflictBehavior.QUEUE)
        }

        controller.submit(incumbent)
        controller.submit(incoming)

        assertTrue(controller.isRunning(incumbent))
        assertTrue(controller.isStaged(incoming))
    }

    @Test
    fun `equal priority override interrupts the incumbent by default`() {
        val requirement = Any()
        val incumbent by actionType { recording("incumbent", 100).requiring(requirement) }
        val incoming by actionType { recording("incoming", 100).requiring(requirement) }

        controller.submit(incumbent)
        controller.submit(incoming)

        assertFalse(controller.isRunning(incumbent))
        assertTrue(controller.isRunning(incoming))
    }

    @Test
    fun `remove discards a queued action without ever starting it`() {
        val requirement = Any()
        val incumbent by actionType {
            recording("incumbent", 100)
                .requiring(requirement)
                .withPriority(ActionPriority.HIGH)
        }
        val queued by actionType {
            recording("queued", 100)
                .requiring(requirement)
                .withBlockedBehavior(BlockedBehavior.QUEUE)
        }

        controller.submit(incumbent)
        controller.submit(queued)
        controller.remove(queued)
        controller.remove(incumbent)

        assertFalse(controller.isRunning(queued))
        assertFalse(controller.isStaged(queued))
        assertFalse("queued" in started)
        assertFalse("queued" in stopped)
    }

    @Test
    fun `stop all permanently clears running queued and suspended actions`() {
        val firstRequirement = Any()
        val secondRequirement = Any()
        val suspended by actionType {
            recording("suspended", 100)
                .requiring(firstRequirement)
                .withInterruptedBehavior(InterruptedBehavior.SUSPEND)
        }
        val suspender by actionType {
            recording("suspender", 100)
                .requiring(firstRequirement)
                .withPriority(ActionPriority.HIGH)
        }
        val blocker by actionType {
            recording("blocker", 100)
                .requiring(secondRequirement)
                .withPriority(ActionPriority.HIGH)
        }
        val queued by actionType {
            recording("queued", 100)
                .requiring(secondRequirement)
                .withBlockedBehavior(BlockedBehavior.QUEUE)
        }

        controller.submit(suspended)
        controller.submit(suspender)
        controller.submit(blocker)
        controller.submit(queued)
        controller.removeAll()

        listOf(suspended, suspender, blocker, queued).forEach {
            assertFalse(controller.isRunning(it))
            assertFalse(controller.isStaged(it))
        }
    }
}
