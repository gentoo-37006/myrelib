package gentoo.myrelib.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SequentialTest {
    @Test
    fun `requirements are the union of every child and priority is the highest child`() {
        val firstRequirement = Any()
        val secondRequirement = Any()
        val sequence = Sequential(
            FakeAction().requiring(firstRequirement).withPriority(ActionPriority.LOW),
            FakeAction().requiring(secondRequirement).withPriority(ActionPriority.HIGH),
        )

        assertEquals(setOf(firstRequirement, secondRequirement), sequence.requires)
        assertEquals(ActionPriority.HIGH, sequence.priority)
    }

    @Test
    fun `children run one after another, never overlapping`() {
        val log = mutableListOf<String>()
        val sequence = Sequential(
            FakeAction("first", ticksToRun = 2, log = log),
            FakeAction("second", ticksToRun = 1, log = log),
        )

        sequence.runToCompletion()

        assertEquals(
            listOf(
                "first:start",
                "first:tick",
                "first:tick",
                "first:stop",
                "second:start",
                "second:tick",
                "second:stop"
            ),
            log
        )
    }

    @Test
    fun `an empty sequence stops immediately`() {
        val sequence = Sequential()
        sequence.doStart()
        assertTrue(sequence.tickOrStop().stopped())
    }

    @Test
    fun `stopping early stops the running child and never starts the rest`() {
        val log = mutableListOf<String>()
        val first = FakeAction("first", ticksToRun = 100, log = log)
        val second = FakeAction("second", ticksToRun = 1, log = log)
        val sequence = Sequential(first, second)

        sequence.doStart()
        sequence.tickOrStop()
        sequence.doStop(interrupted = true)

        assertEquals(listOf("first:start", "first:tick", "first:stop"), log)
        assertEquals(true, first.interrupted)
        assertEquals(null, second.interrupted)
    }

    @Test
    fun `debug lines show the child that is currently running`() {
        val sequence = Sequential(FakeAction("first", ticksToRun = 100))
        sequence.doStart()
        assertEquals(listOf("-> Sequential", "   -> FakeAction"), sequence.getDebugLines())
    }
}

class ParallelTest {
    @Test
    fun `parallel children with overlapping requirements are rejected`() {
        val requirement = Any()

        assertThrows(IllegalArgumentException::class.java) {
            Parallel(
                FakeAction().requiring(requirement),
                FakeAction().requiring(requirement),
            )
        }
    }

    @Test
    fun `separate parallel requirements are aggregated`() {
        val first = Any()
        val second = Any()
        val group = Parallel(
            FakeAction().requiring(first),
            FakeAction().requiring(second),
        )

        assertEquals(setOf(first, second), group.requires)
    }

    @Test
    fun `all children start together`() {
        val log = mutableListOf<String>()
        val parallel = Parallel(
            FakeAction("a", ticksToRun = 1, log = log),
            FakeAction("b", ticksToRun = 1, log = log),
        )

        parallel.doStart()

        assertEquals(listOf("a:start", "b:start"), log)
    }

    @Test
    fun `the group runs until its slowest child is done`() {
        val quick = FakeAction("quick", ticksToRun = 1)
        val slow = FakeAction("slow", ticksToRun = 5)
        val parallel = Parallel(quick, slow)

        parallel.runToCompletion()

        assertEquals(1, quick.ticks)
        assertEquals(5, slow.ticks)
        assertEquals(false, quick.interrupted)
        assertEquals(false, slow.interrupted)
    }

    @Test
    fun `stopping early interrupts every running child`() {
        val first = FakeAction("first", ticksToRun = 100)
        val second = FakeAction("second", ticksToRun = 100)
        val parallel = Parallel(first, second)

        parallel.doStart()
        parallel.doStop(interrupted = true)

        assertEquals(true, first.interrupted)
        assertEquals(true, second.interrupted)
    }

    @Test
    fun `a group with no children stops immediately`() {
        val parallel = Parallel()
        parallel.doStart()
        assertTrue(parallel.tickOrStop().stopped())
    }

    @Test
    fun `debug lines list every child`() {
        val parallel = Parallel(FakeAction("a"), FakeAction("b"))
        parallel.doStart()
        assertEquals(
            listOf("-> Parallel", "   -> FakeAction", "   -> FakeAction"),
            parallel.getDebugLines()
        )
    }
}

class RaceTest {
    @Test
    fun `race validates parallel child conflicts`() {
        val requirement = Any()
        assertThrows(IllegalArgumentException::class.java) {
            Race(FakeAction().requiring(requirement), FakeAction().requiring(requirement))
        }
    }

    @Test
    fun `the first child to finish stops the remaining children`() {
        val log = mutableListOf<String>()
        val quick = FakeAction("quick", ticksToRun = 1, log = log)
        val slow = FakeAction("slow", ticksToRun = 100, log = log)
        val race = Race(quick, slow)

        race.runToCompletion()

        assertEquals(1, quick.ticks)
        assertTrue(slow.ticks < 100)
        assertTrue("slow:stop" in log)
        assertEquals(false, quick.interrupted)
        assertEquals(true, slow.interrupted)
    }

    @Test
    fun `an empty race stops immediately`() {
        val race = Race()
        race.doStart()
        assertTrue(race.tickOrStop().stopped())
    }

    @Test
    fun `stopping early stops every running child`() {
        val log = mutableListOf<String>()
        val first = FakeAction("a", ticksToRun = 100, log = log)
        val second = FakeAction("b", ticksToRun = 100, log = log)
        val race = Race(first, second)

        race.doStart()
        race.doStop(interrupted = true)

        assertTrue("a:stop" in log)
        assertTrue("b:stop" in log)
        assertEquals(true, first.interrupted)
        assertEquals(true, second.interrupted)
    }
}

class DeadlineTest {
    @Test
    fun `deadline validates conflicts with its parallel children`() {
        val requirement = Any()
        assertThrows(IllegalArgumentException::class.java) {
            Deadline(FakeAction().requiring(requirement), FakeAction().requiring(requirement))
        }
    }

    @Test
    fun `deadline completion stops children that are still running`() {
        val log = mutableListOf<String>()
        val deadline = FakeAction("deadline", ticksToRun = 2, log = log)
        val secondary = FakeAction("secondary", ticksToRun = 100, log = log)

        Deadline(deadline, secondary).runToCompletion()

        assertEquals(2, deadline.ticks)
        assertEquals(2, secondary.ticks)
        assertTrue("secondary:stop" in log)
        assertEquals(false, deadline.interrupted)
        assertEquals(true, secondary.interrupted)
    }

    @Test
    fun `a secondary child finishing does not finish the deadline group`() {
        val deadline = FakeAction("deadline", ticksToRun = 4)
        val secondary = FakeAction("secondary", ticksToRun = 1)

        Deadline(deadline, secondary).runToCompletion()

        assertEquals(4, deadline.ticks)
        assertEquals(1, secondary.ticks)
    }

    @Test
    fun `stopping early stops the deadline and every running child`() {
        val log = mutableListOf<String>()
        val deadline = FakeAction("deadline", ticksToRun = 100, log = log)
        val secondary = FakeAction("secondary", ticksToRun = 100, log = log)
        val group = Deadline(deadline, secondary)

        group.doStart()
        group.doStop(interrupted = true)

        assertTrue("deadline:stop" in log)
        assertTrue("secondary:stop" in log)
        assertEquals(true, deadline.interrupted)
        assertEquals(true, secondary.interrupted)
    }
}

class RepeatTest {
    @Test
    fun `repeat exposes its child's scheduler contract before it starts`() {
        val requirement = Any()
        val repeat = Repeat(2) {
            FakeAction().requiring(requirement).withPriority(ActionPriority.HIGH)
        }

        assertEquals(setOf(requirement), repeat.requires)
        assertEquals(ActionPriority.HIGH, repeat.priority)
    }

    @Test
    fun `runs its child the requested number of times`() {
        var built = 0
        val repeat = Repeat(3) { FakeAction("child", ticksToRun = 1).also { built++ } }

        repeat.runToCompletion()

        assertEquals(3, built)
    }

    @Test
    fun `builds a fresh child for each repetition`() {
        val children = mutableListOf<FakeAction>()
        val repeat = Repeat(2) {
            FakeAction("child", ticksToRun = 1).also { children.add(it) }
        }

        repeat.runToCompletion()

        assertEquals(2, children.size)
        assertTrue("each repetition needs its own instance", children[0] !== children[1])
    }

    @Test
    fun `zero repetitions never builds a child`() {
        var built = 0
        val repeat = Repeat(0) { FakeAction().also { built++ } }

        repeat.runToCompletion()

        assertEquals(0, built)
    }

    @Test
    fun `stopping early stops the child that is mid-run`() {
        val log = mutableListOf<String>()
        lateinit var child: FakeAction
        val repeat = Repeat(5) {
            FakeAction("child", ticksToRun = 100, log = log).also { child = it }
        }

        repeat.doStart()
        repeat.tickOrStop()
        repeat.doStop(interrupted = true)

        assertEquals(listOf("child:start", "child:tick", "child:stop"), log)
        assertEquals(true, child.interrupted)
    }
}

class LoopTest {
    @Test
    fun `loop exposes its child's scheduler contract and uses that same first instance`() {
        val requirement = Any()
        val children = mutableListOf<ActionInstance>()
        val loop = Loop {
            FakeAction().requiring(requirement).also(children::add)
        }

        assertEquals(setOf(requirement), loop.requires)
        loop.doStart()

        assertEquals("start must reuse the child created for requirement inspection", 1, children.size)
    }

    @Test
    fun `builds a fresh child whenever the previous child finishes`() {
        val children = mutableListOf<FakeAction>()
        val loop = Loop {
            FakeAction("child", ticksToRun = 1).also { children.add(it) }
        }

        loop.doStart()
        assertTrue(loop.tickOrStop().running())
        assertTrue(loop.tickOrStop().running())

        assertEquals(2, children.size)
        assertTrue(children[0] !== children[1])
    }

    @Test
    fun `continues until stopped and stops its active child`() {
        val log = mutableListOf<String>()
        lateinit var child: FakeAction
        val loop = Loop {
            FakeAction("child", ticksToRun = 100, log = log).also { child = it }
        }

        loop.doStart()
        assertTrue(loop.tickOrStop().running())
        loop.doStop(interrupted = true)

        assertEquals(listOf("child:start", "child:tick", "child:stop"), log)
        assertEquals(true, child.interrupted)
    }

    @Test
    fun `a factory reusing a child is rejected before it can restart`() {
        val reused = FakeAction(ticksToRun = 1)
        val repeat = Repeat(2) { reused }

        assertThrows(IllegalStateException::class.java) {
            repeat.runToCompletion()
        }
    }
}
