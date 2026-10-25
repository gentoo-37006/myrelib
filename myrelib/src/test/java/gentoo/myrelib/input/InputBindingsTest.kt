package gentoo.myrelib.input

import com.qualcomm.robotcore.hardware.Gamepad
import gentoo.myrelib.action.Action
import gentoo.myrelib.action.actionType
import gentoo.myrelib.robot.LifecycleRobot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private object BindingActions {
    val HELD by actionType { object : Action() {
        override fun canContinue() = true
    } }
}

class InputBindingsTest {
    private val robot = LifecycleRobot

    @Before
    fun initializeRobot() {
        robot.reset()
        robot.initializeFullRobot()
    }

    @After
    fun resetRobot() {
        robot.reset()
    }

    @Test
    fun `rising and falling callbacks run only on their respective edges`() {
        val inputs = InputBindings()
        var pressed = false
        val events = mutableListOf<String>()
        val trigger = inputs.trigger { pressed }
        trigger.onTrue { events += "pressed" }
        trigger.onFalse { events += "released" }

        inputs.poll()
        pressed = true
        inputs.poll()
        inputs.poll()
        pressed = false
        inputs.poll()
        inputs.poll()

        assertEquals(listOf("pressed", "released"), events)
    }

    @Test
    fun `on change submits on press and removes on release without resubmitting while held`() {
        val inputs = InputBindings()
        var held = false
        inputs.trigger { held }.onChange(BindingActions.HELD)

        inputs.poll()
        held = true
        inputs.poll()
        assertTrue(BindingActions.HELD.isRunning())

        val firstInstance = robot.runningActions.getValue(BindingActions.HELD)
        inputs.poll()
        assertTrue(firstInstance === robot.runningActions.getValue(BindingActions.HELD))

        held = false
        inputs.poll()
        assertFalse(BindingActions.HELD.isRunning())
        assertFalse(BindingActions.HELD.isStaged())
    }

    @Test
    fun `while true and while false run every poll in their respective states`() {
        val inputs = InputBindings()
        var enabled = false
        var trueCalls = 0
        var falseCalls = 0
        val trigger = inputs.trigger { enabled }
        trigger.whileTrue { trueCalls++ }
        trigger.whileFalse { falseCalls++ }

        inputs.poll()
        inputs.poll()
        enabled = true
        inputs.poll()
        inputs.poll()
        inputs.poll()

        assertEquals(3, trueCalls)
        assertEquals(2, falseCalls)
    }

    @Test
    fun `toggle reflects actual action state instead of retaining stale toggle state`() {
        val inputs = InputBindings()
        var pressed = false
        inputs.trigger { pressed }.toggleOnTrue(BindingActions.HELD)

        pressed = true
        inputs.poll()
        assertTrue(BindingActions.HELD.isRunning())

        pressed = false
        inputs.poll()
        BindingActions.HELD.remove()
        assertFalse(BindingActions.HELD.isRunning())

        pressed = true
        inputs.poll()
        assertTrue(BindingActions.HELD.isRunning())
    }

    @Test
    fun `ftc adapters read both supplied gamepads and live analog values`() {
        val first = Gamepad()
        val second = Gamepad()
        val inputs = FtcInputBindings(first, second)
        val events = mutableListOf<String>()
        inputs.driver.a.onTrue { events += "driver a" }
        inputs.operator.rightTrigger.onTrue { events += "operator trigger pressed" }
        inputs.operator.rightTrigger.onFalse { events += "operator trigger released" }
        inputs.operator.rightTriggerAxis.above(0.5).onTrue { events += "operator trigger high" }

        inputs.poll()
        first.a = true
        second.right_trigger = 0.25f
        inputs.poll()
        inputs.poll()
        second.right_trigger = 0.75f
        inputs.poll()
        second.right_trigger = 0.05f
        inputs.poll()

        assertEquals(
            listOf(
                "driver a",
                "operator trigger pressed",
                "operator trigger high",
                "operator trigger released",
            ),
            events,
        )
        assertEquals(0.05, inputs.operator.rightTriggerAxis.value, 0.0001)
    }

    @Test
    fun `combined triggers share the same edge tracking`() {
        val inputs = InputBindings()
        var first = false
        var second = false
        var calls = 0
        inputs.trigger { first }
            .and(inputs.trigger { second })
            .onTrue { calls++ }

        first = true
        inputs.poll()
        second = true
        inputs.poll()
        inputs.poll()

        assertEquals(1, calls)
    }

    @Test
    fun `then requires the first trigger before the second trigger`() {
        val inputs = InputBindings()
        var first = false
        var second = false
        var calls = 0
        inputs.trigger { first }
            .then(inputs.trigger { second })
            .onTrue { calls++ }

        inputs.poll()
        second = true
        inputs.poll()
        first = true
        inputs.poll()
        assertEquals(0, calls)

        second = false
        inputs.poll()
        second = true
        inputs.poll()
        inputs.poll()

        assertEquals(1, calls)
    }

    @Test
    fun `then can drive multiple bindings without sharing edge state`() {
        val inputs = InputBindings()
        var first = false
        var second = false
        var firstCalls = 0
        var secondCalls = 0
        val ordered = inputs.trigger { first }.then(inputs.trigger { second })
        ordered.onTrue { firstCalls++ }
        ordered.onTrue { secondCalls++ }

        inputs.poll()
        first = true
        inputs.poll()
        second = true
        inputs.poll()

        assertEquals(1, firstCalls)
        assertEquals(1, secondCalls)
    }

    @Test
    fun `and keeps an ordered chord up to date while its gate is false`() {
        assertMaskedChordTracksButtons(maskedGate = false) { gate, chord -> gate.and(chord) }
    }

    @Test
    fun `or keeps an ordered chord up to date while its gate is true`() {
        assertMaskedChordTracksButtons(maskedGate = true) { gate, chord -> gate.or(chord) }
    }

    private fun assertMaskedChordTracksButtons(
        maskedGate: Boolean,
        combine: (Trigger, Trigger) -> Trigger,
    ) {
        val inputs = InputBindings()
        var gate = maskedGate
        var modifier = false
        var button = false
        var active = false
        val chord = inputs.trigger { modifier }.then(inputs.trigger { button })
        combine(inputs.trigger { gate }, chord)
            .onChange(onTrue = { active = true }, onFalse = { active = false })
        inputs.poll()

        // The wrong order must be remembered even while the gate masks the chord.
        button = true
        inputs.poll()
        modifier = true
        inputs.poll()
        gate = !maskedGate
        inputs.poll()
        assertFalse("unmasking must not turn the held button into a new press", active)

        button = false
        inputs.poll()
        button = true
        inputs.poll()
        assertTrue("a fresh press with the modifier held must still activate", active)

        // Releasing and repressing the modifier while masked cancels the latch.
        gate = maskedGate
        inputs.poll()
        modifier = false
        inputs.poll()
        modifier = true
        inputs.poll()
        gate = !maskedGate
        inputs.poll()
        assertFalse("a modifier release must not leave the old chord latched", active)

        button = false
        inputs.poll()
        button = true
        inputs.poll()
        assertTrue("the chord must activate again after another valid press", active)
    }

    @Test
    fun `and and or preserve their truth tables through repeated polls`() {
        val inputs = InputBindings()
        var first = false
        var second = false
        var conjunction = false
        var disjunction = false
        val a = inputs.trigger { first }
        val b = inputs.trigger { second }
        a.and(b).onChange(onTrue = { conjunction = true }, onFalse = { conjunction = false })
        a.or(b).onChange(onTrue = { disjunction = true }, onFalse = { disjunction = false })

        val states = listOf(false to false, true to false, true to true, false to true, false to false)
        for((left, right) in states) {
            first = left
            second = right
            repeat(2) {
                inputs.poll()
                assertEquals(left && right, conjunction)
                assertEquals(left || right, disjunction)
            }
        }
    }
}
