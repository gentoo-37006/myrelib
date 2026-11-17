package gentoo.myrelib.component

import com.pedropathing.follower.Follower
import com.qualcomm.robotcore.hardware.HardwareMap
import gentoo.myrelib.config.ComponentConfig
import gentoo.myrelib.robot.BaseRobotInstance
import gentoo.myrelib.robot.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val testFollowerFactory: (HardwareMap) -> Follower = {
    error("a disabled test instance must not construct a follower")
}

private class ConfigurableInstance : BaseRobotInstance(Context(followerFactory = testFollowerFactory)) {
    val component by component { hardware, disabled ->
        object : Component(hardware, disabled) {
            override fun tick() = Unit
        }
    }
}

private class MutableComponentConfig : ComponentConfig() {
    var disabled = false

    override fun componentBindings() = bind(
        ConfigurableInstance::component to disabled
    )
}

class ComponentConfigTest {
    @Test
    fun `base robot instance enables components by default`() {
        val instance = ConfigurableInstance()

        assertFalse(instance.actions.disabled)
        assertFalse(instance.component.disabled)
    }

    @Test
    fun `each instance captures the current bindings`() {
        val config = MutableComponentConfig()
        val enabledSnapshot = config.instance()

        config.disabled = true
        val disabledSnapshot = config.instance()

        assertFalse(enabledSnapshot.isDisabled(ConfigurableInstance::component.name))
        assertTrue(disabledSnapshot.isDisabled(ConfigurableInstance::component.name))
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
