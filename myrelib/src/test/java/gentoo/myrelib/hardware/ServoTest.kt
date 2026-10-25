package gentoo.myrelib.hardware

import com.qualcomm.robotcore.hardware.DcMotorSimple
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Both wrappers skip writes that would not move the hardware much. `position`
 * and `power` read back the last value actually committed, so a skipped write
 * shows up as a stale reading.
 */
class ServoTest {
    private fun servo() = Servo(testEmptyHardwareScope(), "unused")

    @Test
    fun `the first command always goes through`() {
        // a servo holds its position across opmodes, so where it is at init is
        // unknown. assuming it sits at zero drops the first small command and
        // leaves the mechanism wherever it happened to be
        val servo = servo()
        servo.position = 0.005
        assertEquals(0.005, servo.position, 1e-9)
    }

    @Test
    fun `the first command goes through even when it is exactly zero`() {
        val servo = servo()
        servo.position = 0.0
        assertEquals(0.0, servo.position, 1e-9)
    }

    @Test
    fun `a NaN position is ignored and does not use up the first command`() {
        // the REV hub rejects any position outside [0, 1] by throwing, and NaN
        // slips past the SDK's own clipping, so sending one crashes the opmode
        val servo = servo()
        servo.position = Double.NaN
        assertEquals(0.0, servo.position, 0.0)

        servo.position = 0.005
        assertEquals(0.005, servo.position, 1e-9)
    }

    @Test
    fun `a NaN position after a real one leaves the servo where it was`() {
        val servo = servo()
        servo.position = 0.5
        servo.position = Double.NaN
        assertEquals(0.5, servo.position, 1e-9)
    }

    @Test
    fun `a later change smaller than the threshold is skipped`() {
        val servo = servo()
        servo.position = 0.5
        servo.position = 0.505
        assertEquals(0.5, servo.position, 1e-9)
    }

    @Test
    fun `a later change larger than the threshold is committed`() {
        val servo = servo()
        servo.position = 0.5
        servo.position = 0.9
        assertEquals(0.9, servo.position, 1e-9)
    }

    @Test
    fun `small changes do not accumulate into a committed one`() {
        val servo = servo()
        servo.position = 0.5
        repeat(10) { servo.position = 0.5 + it * 0.001 }
        assertEquals(0.5, servo.position, 1e-9)
    }

    @Test
    fun `a disabled servo reports a default direction instead of throwing`() {
        assertEquals(com.qualcomm.robotcore.hardware.Servo.Direction.FORWARD, servo().direction)
    }
}

class CrServoTest {
    private fun crServo() = CrServo(testEmptyHardwareScope(), "unused")

    @Test
    fun `it starts stopped`() {
        assertEquals(0.0, crServo().power, 1e-9)
    }

    @Test
    fun `a change larger than the threshold is committed`() {
        val servo = crServo()
        servo.power = 0.5
        assertEquals(0.5, servo.power, 1e-9)
    }

    @Test
    fun `a change smaller than the threshold is skipped`() {
        val servo = crServo()
        servo.power = 0.5
        servo.power = 0.505
        assertEquals(0.5, servo.power, 1e-9)
    }

    @Test
    fun `stopping always commits, however small the step`() {
        val servo = crServo()
        servo.power = 0.5
        servo.power = 0.0
        assertEquals("a stop must never be skipped", 0.0, servo.power, 1e-9)
    }

    @Test
    fun `a disabled continuous servo reports a default direction`() {
        assertEquals(DcMotorSimple.Direction.FORWARD, crServo().direction)
    }
}
