package gentoo.myrelib3.input

import com.qualcomm.robotcore.hardware.Gamepad
import kotlin.math.abs

/** FTC gamepad adapters backed by one opmode-local [InputBindings] registry. */
class FtcInputBindings(
    gamepad1: Gamepad,
    gamepad2: Gamepad,
) {
    private val bindings = InputBindings()

    val gamepad1 = GamepadTriggers(gamepad1, bindings)
    val gamepad2 = GamepadTriggers(gamepad2, bindings)

    val driver: GamepadTriggers get() = gamepad1
    val operator: GamepadTriggers get() = gamepad2

    fun trigger(condition: () -> Boolean): Trigger = bindings.trigger(condition)

    fun poll() = bindings.poll()

    fun clear() = bindings.clear()
}

class GamepadTriggers internal constructor(
    val raw: Gamepad,
    bindings: InputBindings,
) {
    val a = bindings.trigger { raw.a }
    val b = bindings.trigger { raw.b }
    val x = bindings.trigger { raw.x }
    val y = bindings.trigger { raw.y }

    val dpadUp = bindings.trigger { raw.dpad_up }
    val dpadDown = bindings.trigger { raw.dpad_down }
    val dpadLeft = bindings.trigger { raw.dpad_left }
    val dpadRight = bindings.trigger { raw.dpad_right }

    val leftBumper = bindings.trigger { raw.left_bumper }
    val rightBumper = bindings.trigger { raw.right_bumper }
    val leftStickButton = bindings.trigger { raw.left_stick_button }
    val rightStickButton = bindings.trigger { raw.right_stick_button }
    val start = bindings.trigger { raw.start }
    val back = bindings.trigger { raw.back }
    val guide = bindings.trigger { raw.guide }

    val leftStickX = AxisInput(bindings) { raw.left_stick_x.toDouble() }
    val leftStickY = AxisInput(bindings) { raw.left_stick_y.toDouble() }
    val rightStickX = AxisInput(bindings) { raw.right_stick_x.toDouble() }
    val rightStickY = AxisInput(bindings) { raw.right_stick_y.toDouble() }
    val leftTriggerAxis = AxisInput(bindings) { raw.left_trigger.toDouble() }
    val rightTriggerAxis = AxisInput(bindings) { raw.right_trigger.toDouble() }

    /** Binary trigger presets with the same edge API as buttons and bumpers. */
    val leftTrigger = leftTriggerAxis.above(TRIGGER_PRESS_THRESHOLD)
    val rightTrigger = rightTriggerAxis.above(TRIGGER_PRESS_THRESHOLD)

    // PlayStation aliases
    val cross: Trigger get() = a
    val circle: Trigger get() = b
    val square: Trigger get() = x
    val triangle: Trigger get() = y

    fun rumble(durationMs: Int) {
        raw.rumble(durationMs)
    }

    fun rumble(
        left: Double,
        right: Double,
        durationMs: Int,
    ) {
        raw.rumble(left, right, durationMs)
    }

    fun runRumbleEffect(effect: Gamepad.RumbleEffect) {
        raw.runRumbleEffect(effect)
    }

    fun stopRumble() {
        raw.stopRumble()
    }

    fun isRumbling(): Boolean = raw.isRumbling

    companion object {
        const val TRIGGER_PRESS_THRESHOLD = 0.1
    }
}

class AxisInput internal constructor(
    private val bindings: InputBindings,
    private val supplier: () -> Double,
) {
    val value: Double get() = supplier()

    fun above(threshold: Double): Trigger = bindings.trigger { value > threshold }

    fun below(threshold: Double): Trigger = bindings.trigger { value < threshold }

    fun outside(deadband: Double): Trigger {
        require(deadband >= 0.0) { "Deadband must be non-negative" }
        return bindings.trigger { abs(value) > deadband }
    }
}
