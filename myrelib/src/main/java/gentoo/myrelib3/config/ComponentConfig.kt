package gentoo.myrelib3.config

import gentoo.myrelib.component.Component
import gentoo.myrelib.robot.BaseRobotInstance
import kotlin.reflect.KProperty1

abstract class ComponentConfig(private val disabledByDefault: Boolean = false) {
    fun instance() = Instance(componentBindings(), disabledByDefault)

    protected abstract fun componentBindings(): Map<String, Boolean>

    protected fun bind(
        vararg pairs: Pair<KProperty1<out BaseRobotInstance, Component>, Boolean>
    ): Map<String, Boolean> = pairs.associate { it.first.name to it.second }

    /** The immutable config instance for an initialized robot instance. */
    data class Instance(
        val componentsDisabled: Map<String, Boolean>,
        private val disabledByDefault: Boolean = false
    ) {
        fun isDisabled(componentName: String): Boolean =
            componentsDisabled[componentName] ?: disabledByDefault
    }

    companion object {
        fun disableAll(): ComponentConfig = DisabledConfig

        fun enableAll(): ComponentConfig = EnabledConfig
    }
}

private object DisabledConfig : ComponentConfig(disabledByDefault = true) {
    override fun componentBindings() = bind()
}

private object EnabledConfig : ComponentConfig(disabledByDefault = false) {
    override fun componentBindings() = bind()
}
