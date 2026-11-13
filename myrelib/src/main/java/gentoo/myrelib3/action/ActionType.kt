package gentoo.myrelib3.action

import gentoo.myrelib.action.ActionType.Delegate
import gentoo.myrelib.robot.BaseRobot
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

open class ActionType internal constructor(
    val name: String,
    private val factory: (() -> ActionInstance)?
) {
    fun createNew(): ActionInstance = checkNotNull(factory) {
        "Action '$name' requires arguments; call it before submitting or constructing it"
    }()

    operator fun invoke(): ActionSubmission = ActionSubmission(this, ::createNew)

    override fun toString() = name

    class Delegate(
        private val factory: () -> ActionInstance
    ) {
        operator fun provideDelegate(
            thisRef: Any?,
            property: KProperty<*>
        ): ReadOnlyProperty<Any?, ActionType> {
            val actionType = ActionType(property.name, factory)
            return ReadOnlyProperty { _, _ -> actionType }
        }
    }

    fun submit() {
        BaseRobot.INSTANCE.actions.submit(this)
    }

    fun submit(timeoutSeconds: Double) {
        BaseRobot.INSTANCE.actions.submit(this, timeoutSeconds)
    }

    fun remove() {
        BaseRobot.INSTANCE.actions.remove(this)
    }

    fun isRunning(): Boolean {
        return BaseRobot.INSTANCE.actions.isRunning(this)
    }

    fun isStaged(): Boolean {
        return BaseRobot.INSTANCE.actions.isStaged(this)
    }
}

fun actionType(factory: () -> ActionInstance) = Delegate(factory)

/** Captures arguments without constructing or starting an action. */
class ActionSubmission internal constructor(
    val type: ActionType,
    private val factory: () -> ActionInstance,
) {
    fun submit() = BaseRobot.INSTANCE.actions.submit(type, factory)

    fun submit(timeoutSeconds: Double) =
        BaseRobot.INSTANCE.actions.submit(type, timeoutSeconds, factory)

    /** Explicitly constructs a fresh child for a composition, without submitting it. */
    fun createNew(): ActionInstance = factory()
}

class ParameterizedActionType<P> internal constructor(
    name: String,
    private val parameterizedFactory: (P) -> ActionInstance,
) : ActionType(name, null) {
    operator fun invoke(parameter: P): ActionSubmission =
        ActionSubmission(this) { parameterizedFactory(parameter) }

    class Delegate<P>(private val factory: (P) -> ActionInstance) {
        operator fun provideDelegate(
            thisRef: Any?,
            property: KProperty<*>
        ): ReadOnlyProperty<Any?, ParameterizedActionType<P>> {
            val type = ParameterizedActionType(property.name, factory)
            return ReadOnlyProperty { _, _ -> type }
        }
    }
}

fun <P> actionType(factory: (P) -> ActionInstance) = ParameterizedActionType.Delegate(factory)
