package gentoo.myrelib.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class ConfigurableProperties {
    val component = Unit
}

private class MutableComponentConfig : ComponentConfig() {
    var disabled = false

    override fun componentBindings() = bind(
        ConfigurableProperties::component to disabled
    )
}

class ComponentConfigTest {
    @Test
    fun `each instance captures the current bindings`() {
        val config = MutableComponentConfig()
        val enabledSnapshot = config.instance()

        config.disabled = true
        val disabledSnapshot = config.instance()

        assertFalse(enabledSnapshot.isDisabled(ConfigurableProperties::component.name))
        assertTrue(disabledSnapshot.isDisabled(ConfigurableProperties::component.name))
    }

    @Test
    fun `disable all config disables unknown components`() {
        assertTrue(ComponentConfig.disableAll().instance().isDisabled("missing"))
    }

    @Test
    fun `enable all config enables unknown components`() {
        assertFalse(ComponentConfig.enableAll().instance().isDisabled("missing"))
    }
}
