package gentoo.myrelib.action

import gentoo.myrelib.component.ActionController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionOverridePromotionTest {
    private val controller = ActionController(disabled = false)
    private val events = mutableListOf<String>()

    private fun recording(name: String, onStop: () -> Unit = {}) = object : Action() {
        override fun start() { events += "$name:start" }
        override fun stop(interrupted: Boolean) {
            events += "$name:stop"
            onStop()
        }
    }

    @Test
    fun `removal from a stop callback does not resume the action being overridden`() {
        val requirement = Any()
        val other by actionType { recording("other") }
        val resumable by actionType {
            recording("resumable").requiring(requirement)
                .withInterruptedBehavior(InterruptedBehavior.SUSPEND)
                .onStop {
                    if(controller.isRunning(other)) controller.remove(other)
                }
        }
        val incoming by actionType {
            recording("incoming").requiring(requirement).withPriority(ActionPriority.HIGH)
        }
        controller.submit(other)
        controller.submit(resumable)
        val original = controller.runningActions.getValue(resumable)
        events.clear()

        controller.submit(incoming)

        assertEquals(listOf("other:stop", "resumable:stop", "incoming:start"), events)
        assertFalse(controller.isRunning(resumable))
        assertTrue(controller.isStaged(resumable))
        assertTrue(controller.isRunning(incoming))

        controller.remove(incoming)

        assertEquals(listOf("incoming:stop", "resumable:start"), events.takeLast(2))
        assertSame(original, controller.runningActions.getValue(resumable))
        assertFalse(controller.isStaged(resumable))
    }

    @Test
    fun `queued work is retained when an overridden action removes another action`() {
        val requirement = Any()
        val other by actionType { recording("other") }
        val incumbent by actionType {
            recording("incumbent") { controller.remove(other) }.requiring(requirement)
        }
        val queued by actionType {
            recording("queued").requiring(requirement)
                .withPriority(ActionPriority.LOW).withBlockedBehavior(BlockedBehavior.QUEUE)
        }
        val incoming by actionType { recording("incoming").requiring(requirement) }
        controller.submit(other)
        controller.submit(incumbent)
        controller.submit(queued)
        events.clear()

        controller.submit(incoming)

        assertEquals(listOf("incumbent:stop", "other:stop", "incoming:start"), events)
        assertTrue(controller.isStaged(queued))
        assertFalse(controller.isRunning(queued))

        controller.remove(incoming)

        assertTrue(controller.isRunning(queued))
        assertEquals(listOf("incoming:stop", "queued:start"), events.takeLast(2))
    }

    @Test
    fun `nested overrides defer queued work until the outer replacement starts`() {
        val drive = Any()
        val intake = Any()
        val other by actionType { recording("other") }
        val intakeIncumbent by actionType { recording("intake").requiring(intake) }
        val intakeReplacement by actionType { recording("intake replacement").requiring(intake) }
        val driveIncumbent by actionType {
            recording("drive") {
                controller.submit(intakeReplacement)
                controller.remove(other)
            }.requiring(drive)
        }
        val queued by actionType {
            recording("queued").requiring(drive)
                .withPriority(ActionPriority.LOW).withBlockedBehavior(BlockedBehavior.QUEUE)
        }
        val incoming by actionType { recording("incoming").requiring(drive) }
        controller.submit(other)
        controller.submit(intakeIncumbent)
        controller.submit(driveIncumbent)
        controller.submit(queued)
        events.clear()

        controller.submit(incoming)

        assertEquals(
            listOf("drive:stop", "intake:stop", "intake replacement:start", "other:stop", "incoming:start"),
            events,
        )
        assertTrue(controller.isStaged(queued))
        assertTrue(controller.isRunning(incoming))
        assertTrue(controller.isRunning(intakeReplacement))

        controller.remove(incoming)

        assertTrue(controller.isRunning(queued))
        assertEquals(listOf("incoming:stop", "queued:start"), events.takeLast(2))
    }

    @Test
    fun `unrelated queued work starts before the overriding submission returns`() {
        val drive = Any()
        val intake = Any()
        val intakeIncumbent by actionType { recording("intake").requiring(intake) }
        val driveIncumbent by actionType {
            recording("drive") { controller.remove(intakeIncumbent) }.requiring(drive)
        }
        val queued by actionType {
            recording("queued").requiring(intake)
                .withPriority(ActionPriority.LOW).withBlockedBehavior(BlockedBehavior.QUEUE)
        }
        val incoming by actionType { recording("incoming").requiring(drive) }
        controller.submit(intakeIncumbent)
        controller.submit(driveIncumbent)
        controller.submit(queued)

        controller.submit(incoming)

        assertTrue(controller.isRunning(incoming))
        assertTrue(controller.isRunning(queued))
        assertFalse(controller.isStaged(queued))
        assertEquals(1, events.count { it == "queued:start" })
        assertFalse("queued:stop" in events)
    }

    @Test
    fun `a failing override does not leave queued promotion disabled`() {
        val requirement = Any()
        val failure = IllegalStateException("stop failed")
        val incumbent by actionType {
            recording("incumbent") { throw failure }.requiring(requirement)
        }
        val queued by actionType {
            recording("queued").requiring(requirement)
                .withPriority(ActionPriority.LOW).withBlockedBehavior(BlockedBehavior.QUEUE)
        }
        val incoming by actionType { recording("incoming").requiring(requirement) }
        controller.submit(incumbent)
        controller.submit(queued)
        events.clear()

        assertSame(failure, assertThrows(IllegalStateException::class.java) { controller.submit(incoming) })
        assertEquals(listOf("incumbent:stop"), events)
        assertFalse(controller.isRunning(incoming))
        assertTrue(controller.isStaged(queued))

        controller.tick()

        assertTrue(controller.isRunning(queued))
        assertFalse(controller.isStaged(queued))
        assertEquals(listOf("incumbent:stop", "queued:start"), events)
    }
}
