package gentoo.myrelib.config

import kotlin.reflect.KProperty

abstract class ComponentConfig(private val disabledByDefault: Boolean = false) {
    fun instance() = Instance(componentBindings().toMap(), disabledByDefault)

    protected abstract fun componentBindings(): Map<String, Boolean>

    protected fun bind(
        vararg pairs: Pair<KProperty<*>, Boolean>
    ): Map<String, Boolean> = pairs.associate { it.first.name to it.second }

    /** The immutable config instance for one opmode session. */
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
