package gentoo.myrelib.input

import gentoo.myrelib.action.ActionType

/**
 * Opmode-owned registry that converts live Boolean conditions into callbacks
 * and action submissions/removals.
 */
class InputBindings {
    private val bindings = mutableListOf<Binding>()

    fun trigger(condition: () -> Boolean): Trigger = Trigger(this) { condition }

    /** Polls a snapshot so callbacks may safely configure or clear bindings. */
    fun poll() {
        bindings.toList().forEach { binding ->
            if(binding in bindings) {
                binding.poll()
            }
        }
    }

    fun clear() {
        bindings.clear()
    }

    internal fun add(binding: Binding) {
        bindings.add(binding)
    }
}

class Trigger internal constructor(
    private val owner: InputBindings,
    private val conditionFactory: () -> (() -> Boolean),
) {
    fun onTrue(callback: () -> Unit): Trigger = apply {
        owner.add(EdgeBinding(conditionFactory(), onRising = callback))
    }

    fun onTrue(action: ActionType): Trigger = onTrue { action.submit() }

    fun onFalse(callback: () -> Unit): Trigger = apply {
        owner.add(EdgeBinding(conditionFactory(), onFalling = callback))
    }

    fun onFalse(action: ActionType): Trigger = onFalse { action.submit() }

    fun onChange(onTrue: () -> Unit, onFalse: () -> Unit): Trigger = apply {
        owner.add(EdgeBinding(conditionFactory(), onRising = onTrue, onFalling = onFalse))
    }

    fun onChange(action: ActionType): Trigger = onChange(
        onTrue = { action.submit() },
        onFalse = { action.remove() },
    )

    fun whileTrue(callback: () -> Unit): Trigger = apply {
        owner.add(ConstantBinding(conditionFactory(), whileTrue = callback))
    }

    fun whileFalse(callback: () -> Unit): Trigger = apply {
        owner.add(ConstantBinding(conditionFactory(), whileFalse = callback))
    }

    fun toggleOnTrue(
        onStart: () -> Unit,
        onStop: () -> Unit,
    ): Trigger {
        var active = false
        return onTrue {
            active = !active
            if(active) onStart() else onStop()
        }
    }

    fun toggleOnTrue(action: ActionType): Trigger = onTrue {
        if(action.isRunning() || action.isStaged()) {
            action.remove()
        }
        else {
            action.submit()
        }
    }

    /** Samples both triggers every poll so nested ordered chords keep their input history. */
    fun and(other: Trigger): Trigger {
        require(owner === other.owner) { "Combined triggers must belong to the same InputBindings" }
        return Trigger(owner) {
            val first = conditionFactory()
            val second = other.conditionFactory()
            val combinedCondition: () -> Boolean = {
                val firstCurrent = first()
                val secondCurrent = second()
                firstCurrent && secondCurrent
            }
            combinedCondition
        }
    }

    /**
     * Becomes true only when [other] rises while this trigger is already true.
     * It remains true while both inputs remain true.
     */
    fun then(other: Trigger): Trigger {
        require(owner === other.owner) { "Combined triggers must belong to the same InputBindings" }
        return Trigger(owner) {
            val first = conditionFactory()
            val second = other.conditionFactory()
            var previousSecond = false
            var active = false
            val orderedCondition: () -> Boolean = {
                val firstCurrent = first()
                val secondCurrent = second()
                active = when {
                    !firstCurrent || !secondCurrent -> false
                    !previousSecond -> true
                    else -> active
                }
                previousSecond = secondCurrent
                active
            }
            orderedCondition
        }
    }

    /** Samples both triggers even while one is already true, preserving nested chord history. */
    fun or(other: Trigger): Trigger {
        require(owner === other.owner) { "Combined triggers must belong to the same InputBindings" }
        return Trigger(owner) {
            val first = conditionFactory()
            val second = other.conditionFactory()
            val combinedCondition: () -> Boolean = {
                val firstCurrent = first()
                val secondCurrent = second()
                firstCurrent || secondCurrent
            }
            combinedCondition
        }
    }

    fun negate(): Trigger = Trigger(owner) {
        val condition = conditionFactory()
        val negatedCondition: () -> Boolean = { !condition() }
        negatedCondition
    }
}

internal fun interface Binding {
    fun poll()
}

private class EdgeBinding(
    private val condition: () -> Boolean,
    private val onRising: () -> Unit = {},
    private val onFalling: () -> Unit = {},
) : Binding {
    private var previous = false

    override fun poll() {
        val current = condition()
        when {
            current && !previous -> onRising()
            !current && previous -> onFalling()
        }
        previous = current
    }
}

private class ConstantBinding(
    private val condition: () -> Boolean,
    private val whileTrue: () -> Unit = {},
    private val whileFalse: () -> Unit = {},
) : Binding {
    override fun poll() {
        if(condition()) {
            whileTrue()
        }
        else {
            whileFalse()
        }
    }
}
