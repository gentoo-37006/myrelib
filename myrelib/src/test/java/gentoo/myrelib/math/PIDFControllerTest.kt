package gentoo.myrelib.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PIDFControllerTest {
    private fun proportional(kP: Double) = PIDFController(kP, 0.0, 0.0, 0.0, 0.0)
    private fun integral(kI: Double) = PIDFController(0.0, kI, 0.0, 0.0, 0.0)
    private fun derivative(kD: Double) = PIDFController(0.0, 0.0, kD, 0.0, 0.0)

    @Test
    fun `proportional output tracks the error`() {
        val controller = proportional(0.5)
        assertEquals(2.5, controller.update(5.0), 1e-9)
        assertEquals(-1.0, controller.update(-2.0), 1e-9)
        assertEquals(0.0, controller.update(0.0), 1e-9)
    }

    @Test
    fun `feedforward uses the target, not the error`() {
        val controller = PIDFController(0.0, 0.0, 0.0, 2.0, 3.0)
        // kV * targetVelocity + kA * targetAcceleration, with no P, I or D
        assertEquals(2.0 * 10.0 + 3.0 * 4.0, controller.update(7.0, 10.0, 4.0), 1e-9)
    }

    @Test
    fun `the velocity overload drives the pid terms from target minus current`() {
        val controller = proportional(1.0)
        assertEquals(4.0, controller.update(6.0, 10.0, 0.0), 1e-9)
    }

    @Test
    fun `the first update does not integrate the delay since construction`() {
        // the controller is built at init but first updated when the match starts.
        // integrating over that gap would wind the output up before the mechanism
        // has moved at all
        val controller = integral(1.0)
        Thread.sleep(250)

        val output = controller.update(10.0)

        assertTrue(
            "first update wound up to $output; it should start from zero",
            abs(output) < 1e-6
        )
    }

    @Test
    fun `the integral accumulates once it is running`() {
        val controller = integral(1.0)
        controller.update(0.0)

        Thread.sleep(50)
        val first = controller.update(10.0)
        assertTrue("expected the integral to start accumulating, got $first", first > 0.0)

        Thread.sleep(50)
        val second = controller.update(10.0)
        assertTrue("expected the integral to keep growing, got $second after $first", second > first)
    }

    @Test
    fun `the derivative is zero on the first update`() {
        assertEquals(0.0, derivative(1.0).update(100.0), 1e-9)
    }

    @Test
    fun `a steady error produces no derivative`() {
        val controller = derivative(1.0)
        controller.update(5.0)
        Thread.sleep(20)
        assertEquals(0.0, controller.update(5.0), 1e-9)
    }

    @Test
    fun `a growing error produces a positive derivative`() {
        val controller = derivative(1.0)
        controller.update(0.0)
        Thread.sleep(20)
        assertTrue(controller.update(5.0) > 0.0)
    }

    @Test
    fun `reset clears the integral and re-arms the first update guard`() {
        val controller = integral(1.0)
        controller.update(0.0)
        Thread.sleep(50)
        assertTrue(controller.update(10.0) > 0.0)

        controller.reset()
        Thread.sleep(50)

        assertEquals(
            "after a reset the next update should behave like the very first one",
            0.0,
            controller.update(10.0),
            1e-6
        )
    }

    @Test
    fun `coefficients can be retuned in place`() {
        val controller = proportional(1.0)
        assertEquals(5.0, controller.update(5.0), 1e-9)

        controller.setCoefficients(2.0, 0.0, 0.0, 0.0, 0.0)
        assertEquals(10.0, controller.update(5.0), 1e-9)
    }

    @Test
    fun `back to back updates do not divide by zero`() {
        val controller = PIDFController(1.0, 1.0, 1.0, 0.0, 0.0)
        controller.update(1.0)
        repeat(100) {
            val output = controller.update(1.0)
            assertTrue("output went non-finite: $output", output.isFinite())
        }
    }
}
