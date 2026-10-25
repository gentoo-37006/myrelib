package gentoo.myrelib.action

import gentoo.myrelib.component.ActionController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionCallbackLifecycleTest {
    private val controller = ActionController(disabled = false)
    private val events = mutableListOf<String>()

    private fun recording(name: String, shouldContinue: () -> Boolean = { true }) = object : Action() {
        override fun start() { events += "$name:start" }
        override fun canContinue() = shouldContinue()
        override fun tick() { events += "$name:tick" }
        override fun stop(interrupted: Boolean) { events += "$name:stop:$interrupted" }
    }

    @Test
    fun `cancelling from onStart skips the start hook`() {
        lateinit var self: ActionType
        val action by actionType {
            recording("cancelled").onStart { controller.remove(self) }
        }
        self = action

        controller.submit(action)
        controller.tick()

        assertFalse(controller.isRunning(action))
        assertEquals(listOf("cancelled:stop:true"), events)
    }

    @Test
    fun `a conflicting successor from onStart prevents the old start hook`() {
        val requirement = Any()
        val successor by actionType { recording("successor").requiring(requirement) }
        val predecessor by actionType {
            recording("predecessor").requiring(requirement)
                .onStart { controller.submit(successor) }
        }

        controller.submit(predecessor)

        assertFalse(controller.isRunning(predecessor))
        assertTrue(controller.isRunning(successor))
        assertEquals(listOf("predecessor:stop:true", "successor:start"), events)
    }

    @Test
    fun `removing all from a natural stop callback stops the action only once`() {
        val finishing by actionType {
            recording("finishing", shouldContinue = { false }).onStop {
                events += "finishing:callback"
                controller.removeAll()
            }
        }
        val other by actionType { recording("other") }
        controller.submit(finishing)
        controller.submit(other)
        events.clear()

        controller.tick()

        assertTrue(controller.runningActions.isEmpty())
        assertEquals(
            listOf("finishing:callback", "other:stop:true", "finishing:stop:false"),
            events,
        )
    }

    @Test
    fun `cancelling from canContinue prevents another tick`() {
        lateinit var self: ActionType
        val action by actionType {
            recording("cancelled") {
                controller.remove(self)
                true
            }
        }
        self = action
        controller.submit(action)
        events.clear()

        controller.tick()

        assertFalse(controller.isRunning(action))
        assertEquals(listOf("cancelled:stop:true"), events)
    }

    @Test
    fun `resuming during onStart does not run the old activation's start hook`() {
        val requirement = Any()
        val interrupter by actionType {
            recording("interrupter").requiring(requirement).withPriority(ActionPriority.HIGH)
        }
        var firstStart = true
        val resumable by actionType {
            recording("resumable").requiring(requirement)
                .withInterruptedBehavior(InterruptedBehavior.SUSPEND)
                .onStart {
                    if(firstStart) {
                        firstStart = false
                        controller.submit(interrupter)
                        controller.remove(interrupter)
                    }
                }
        }

        controller.submit(resumable)
        assertTrue(controller.isRunning(resumable))
        controller.tick()
        controller.remove(resumable)

        assertEquals(
            listOf(
                "resumable:stop:true",
                "interrupter:start",
                "interrupter:stop:true",
                "resumable:start",
                "resumable:tick",
                "resumable:stop:true",
            ),
            events,
        )
    }
}
