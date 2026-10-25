package gentoo.myrelib.action

import gentoo.myrelib.component.ActionController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private class Counter : Action() {
    override fun canContinue() = true
}

private object Registry {
    var built = 0

    val INTAKE by actionType { Counter().also { built++ } }
    val SHOOTER by actionType { Counter() }
}

class ActionTypeTest {
    @Test
    fun `an action type is named after the property it is declared on`() {
        assertEquals("INTAKE", Registry.INTAKE.name)
        assertEquals("SHOOTER", Registry.SHOOTER.name)
    }

    @Test
    fun `it prints as its name, which is what telemetry shows`() {
        assertEquals("INTAKE", Registry.INTAKE.toString())
    }

    @Test
    fun `reading the property twice gives the very same instance`() {
        // ActionController keys a HashMap on the action type. if each read built
        // a new one, submit followed by isRunning would be false and every
        // action would look like it never ran
        assertSame(Registry.INTAKE, Registry.INTAKE)
    }

    @Test
    fun `an action type works as a map key across separate reads`() {
        val controller = ActionController(disabled = false)
        controller.submit(Registry.INTAKE)

        assertTrue("the controller lost track of it", controller.isRunning(Registry.INTAKE))

        controller.remove(Registry.INTAKE)
        assertFalse(controller.isRunning(Registry.INTAKE))
    }

    @Test
    fun `separate properties are separate action types`() {
        assertNotSame(Registry.INTAKE, Registry.SHOOTER)

        val controller = ActionController(disabled = false)
        controller.submit(Registry.INTAKE)
        assertFalse(controller.isRunning(Registry.SHOOTER))
    }

    @Test
    fun `each start builds a fresh control, so state does not leak between runs`() {
        val first = Registry.INTAKE.createNew()
        val second = Registry.INTAKE.createNew()
        assertNotSame(first, second)
    }

    @Test
    fun `the factory is not run until an action is actually started`() {
        val before = Registry.built
        Registry.INTAKE.name
        Registry.INTAKE.toString()
        assertEquals("merely naming the action should not build one", before, Registry.built)

        Registry.INTAKE.createNew()
        assertEquals(before + 1, Registry.built)
    }

    @Test
    fun `a locally declared action type takes its local name`() {
        val hoodAngle by actionType { Counter() }
        assertEquals("hoodAngle", hoodAngle.name)
    }
}
