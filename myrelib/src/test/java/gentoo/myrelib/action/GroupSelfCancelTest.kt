package gentoo.myrelib.action

import gentoo.myrelib.component.ActionController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.function.Supplier

/**
 * A child may cancel the group it belongs to, for example an "abort this
 * routine" check that calls the group's `ActionType.remove()` from its own tick
 * or stop hook. The controller then calls `doStop(true)` on the group while the
 * group is still in the middle of ticking that child. AGENTS.md promises that a
 * tick or stop hook may start and stop other actions safely; these pin that for
 * every composition.
 */
class GroupSelfCancelTest {
    private class Hooked(
        private val ticksToRun: Int = 100,
        private val onTick: () -> Unit = {},
        private val onStopHook: () -> Unit = {},
        private val onStartHook: () -> Unit = {},
    ) : Action() {
        var started = 0
        var stopped = 0

        override fun start() {
            started++
            onStartHook()
        }

        override fun canContinue() = ticksToRun > 0 && ticks < ticksToRun

        var ticks = 0

        override fun tick() {
            ticks++
            onTick()
        }

        override fun stop(interrupted: Boolean) {
            stopped++
            onStopHook()
        }
    }

    @Test
    fun `a parallel child may cancel its group from its tick`() {
        lateinit var group: Parallel
        val canceller = Hooked(onTick = { group.doStop(true) })
        val sibling = Hooked()
        group = Parallel(canceller, sibling)

        group.doStart()
        group.tickOrStop()

        assertEquals(1, canceller.stopped)
        assertEquals(1, sibling.stopped)
    }

    @Test
    fun `a deadline's other child may cancel the group from its tick`() {
        lateinit var group: Deadline
        val deadline = Hooked()
        val canceller = Hooked(onTick = { group.doStop(true) })
        val sibling = Hooked()
        group = Deadline(deadline, canceller, sibling)

        group.doStart()
        group.tickOrStop()

        assertEquals(1, deadline.stopped)
        assertEquals(1, canceller.stopped)
        assertEquals(1, sibling.stopped)
    }

    @Test
    fun `a loop cancelled from its child's stop hook does not start another child`() {
        // the stranded child would be started and then never ticked or stopped,
        // leaving whatever it commanded - an intake, say - running
        lateinit var group: Loop
        val made = mutableListOf<Hooked>()
        group = Loop(Supplier {
            Hooked(ticksToRun = 1, onStopHook = { group.doStop(true) }).also { made += it }
        })

        group.doStart()
        group.tickOrStop()
        group.tickOrStop()

        assertFalse("a child was started after the loop stopped", made.drop(1).any { it.started > 0 })
    }

    @Test
    fun `a loop cancelled from its child's tick does not start another child`() {
        lateinit var group: Loop
        val made = mutableListOf<Hooked>()
        group = Loop(Supplier {
            Hooked(onTick = { group.doStop(true) }).also { made += it }
        })

        group.doStart()
        group.tickOrStop()

        assertFalse("a child was started after the loop stopped", made.drop(1).any { it.started > 0 })
    }

    @Test
    fun `a repeat cancelled from its child's stop hook does not start another child`() {
        lateinit var group: Repeat
        val made = mutableListOf<Hooked>()
        group = Repeat(5, Supplier {
            Hooked(ticksToRun = 1, onStopHook = { group.doStop(true) }).also { made += it }
        })

        group.doStart()
        group.tickOrStop()
        group.tickOrStop()

        assertFalse("a child was started after the repeat stopped", made.drop(1).any { it.started > 0 })
    }

    @Test
    fun `a repeat cancelled from its child's tick does not start another child`() {
        lateinit var group: Repeat
        val made = mutableListOf<Hooked>()
        group = Repeat(5, Supplier {
            Hooked(onTick = { group.doStop(true) }).also { made += it }
        })

        group.doStart()
        group.tickOrStop()

        assertFalse("a child was started after the repeat stopped", made.drop(1).any { it.started > 0 })
    }

    @Test
    fun `a sequence cancelled from its child's stop hook does not start the next child`() {
        lateinit var group: Sequential
        val next = Hooked()
        group = Sequential(Hooked(ticksToRun = 1, onStopHook = { group.doStop(true) }), next)

        group.doStart()
        group.tickOrStop()
        group.tickOrStop()

        assertEquals(0, next.started)
    }

    // A child's start may cancel its group too, directly or by submitting an
    // action that overrides it. Every child started by then must be stopped, and
    // none may start afterwards: nothing would ever tick or stop it.

    @Test
    fun `a sequence cancelled from its child's start stops that child`() {
        lateinit var group: Sequential
        val canceller = Hooked(onStartHook = { group.doStop(true) })
        val next = Hooked()
        group = Sequential(canceller, next)

        group.doStart()

        assertEquals(1, canceller.stopped)
        assertEquals(0, next.started)
    }

    @Test
    fun `a parallel cancelled from a child's start stops started children and starts no more`() {
        lateinit var group: Parallel
        val earlier = Hooked()
        val canceller = Hooked(onStartHook = { group.doStop(true) })
        val later = Hooked()
        group = Parallel(earlier, canceller, later)

        group.doStart()

        assertEquals(1, earlier.stopped)
        assertEquals(1, canceller.stopped)
        assertEquals(0, later.started)
    }

    @Test
    fun `a race cancelled from a child's start stops started children and starts no more`() {
        lateinit var group: Race
        val earlier = Hooked()
        val canceller = Hooked(onStartHook = { group.doStop(true) })
        val later = Hooked()
        group = Race(earlier, canceller, later)

        group.doStart()

        assertEquals(1, earlier.stopped)
        assertEquals(1, canceller.stopped)
        assertEquals(0, later.started)
    }

    @Test
    fun `a deadline cancelled from its deadline's start stops it and starts no other child`() {
        lateinit var group: Deadline
        val deadline = Hooked(onStartHook = { group.doStop(true) })
        val other = Hooked()
        group = Deadline(deadline, other)

        group.doStart()

        assertEquals(1, deadline.stopped)
        assertEquals(0, other.started)
    }

    @Test
    fun `a deadline cancelled from another child's start stops started children and starts no more`() {
        lateinit var group: Deadline
        val deadline = Hooked()
        val earlier = Hooked()
        val canceller = Hooked(onStartHook = { group.doStop(true) })
        val later = Hooked()
        group = Deadline(deadline, earlier, canceller, later)

        group.doStart()

        assertEquals(1, deadline.stopped)
        assertEquals(1, earlier.stopped)
        assertEquals(1, canceller.stopped)
        assertEquals(0, later.started)
    }

    @Test
    fun `a child's start that submits an overriding action stops its group's children`() {
        val controller = ActionController(disabled = false)
        val drivetrain = Any()
        val urgent by actionType { Hooked().requiring(drivetrain).withPriority(ActionPriority.HIGH) }
        val earlier = Hooked()
        val trigger = Hooked(onStartHook = { controller.submit(urgent) })
        val later = Hooked()
        val routine by actionType { Parallel(earlier.requiring(drivetrain), trigger, later) }

        controller.submit(routine)

        assertTrue(controller.isRunning(urgent))
        assertFalse(controller.isRunning(routine))
        assertEquals(1, earlier.stopped)
        assertEquals(1, trigger.stopped)
        assertEquals(0, later.started)
    }

    @Test
    fun `a loop cancelled from its child's start stops that child`() {
        lateinit var group: Loop
        val made = mutableListOf<Hooked>()
        group = Loop(Supplier {
            Hooked(onStartHook = { group.doStop(true) }).also { made += it }
        })

        group.doStart()

        assertEquals(1, made.first().stopped)
    }

    @Test
    fun `a repeat cancelled from its child's start stops that child`() {
        lateinit var group: Repeat
        val made = mutableListOf<Hooked>()
        group = Repeat(5, Supplier {
            Hooked(onStartHook = { group.doStop(true) }).also { made += it }
        })

        group.doStart()

        assertEquals(1, made.first().stopped)
    }
}
