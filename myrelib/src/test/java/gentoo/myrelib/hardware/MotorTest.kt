package gentoo.myrelib.hardware

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Motor] always writes the first command and stops; other writes require a
 * change larger than its threshold. `power` reads back the last value that
 * was actually committed, so a skipped write is visible as a stale reading.
 */
class MotorTest {
    private fun motor() = Motor(testEmptyHardwareScope(), "unused")

    @Test
    fun `a fresh motor reads zero`() {
        assertEquals(0.0, motor().power, 1e-9)
    }

    @Test
    fun `a change larger than the threshold is committed`() {
        val motor = motor()
        motor.power = 0.5
        assertEquals(0.5, motor.power, 1e-9)
    }

    @Test
    fun `a change smaller than the threshold is skipped`() {
        val motor = motor()
        motor.power = 0.5
        motor.power = 0.505
        assertEquals("too small a change to be worth a write", 0.5, motor.power, 1e-9)
    }

    @Test
    fun `small changes do not accumulate into a committed one`() {
        val motor = motor()
        motor.power = 0.5
        repeat(10) { motor.power = 0.5 + it * 0.001 }
        assertEquals(0.5, motor.power, 1e-9)
    }

    @Test
    fun `stopping always commits, however small the step`() {
        val motor = motor()
        motor.power = 0.005
        assertEquals(0.005, motor.power, 1e-9)

        motor.power = 0.0
        assertEquals("a stop must never be skipped", 0.0, motor.power, 1e-9)
    }

    @Test
    fun `setting zero when already stopped stays stopped`() {
        val motor = motor()
        motor.power = 0.0
        assertEquals(0.0, motor.power, 1e-9)
    }

    @Test
    fun `negative power works the same way`() {
        val motor = motor()
        motor.power = -0.5
        assertEquals(-0.5, motor.power, 1e-9)
        motor.power = -0.505
        assertEquals(-0.5, motor.power, 1e-9)
        motor.power = 0.0
        assertEquals(0.0, motor.power, 1e-9)
    }

    @Test
    fun `a disabled motor reports harmless defaults instead of throwing`() {
        val motor = motor()
        assertEquals(0, motor.position)
    }
}
