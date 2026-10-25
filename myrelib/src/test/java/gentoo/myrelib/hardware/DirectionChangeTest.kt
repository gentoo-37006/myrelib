package gentoo.myrelib.hardware

import com.qualcomm.robotcore.hardware.CRServoImplEx
import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorControllerEx
import com.qualcomm.robotcore.hardware.DcMotorImplEx
import com.qualcomm.robotcore.hardware.DcMotorSimple.Direction
import com.qualcomm.robotcore.hardware.HardwareDevice
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.ServoControllerEx
import com.qualcomm.robotcore.hardware.ServoImplEx
import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType
import com.qualcomm.robotcore.hardware.configuration.typecontainers.ServoConfigurationType
import gentoo.myrelib.component.HardwareAccess
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test
import com.qualcomm.robotcore.hardware.Servo.Direction as ServoDirection

/** Use the SDK devices so their direction-to-controller mapping is exercised too. */
class DirectionChangeTest {
    @Test
    fun `motor resends power after changing direction`() {
        val hardware = MotorOutput()
        val motor = Motor(directionTestScope(hardware.device), "device")
        motor.power = 0.6

        motor.direction = Direction.REVERSE
        assertWrites(listOf(0.6), hardware.powers)
        motor.power = Double.NaN
        motor.power = 0.6

        assertWrites(listOf(0.6, -0.6), hardware.powers)

        // Reassigning the same direction must retain normal write throttling.
        motor.direction = Direction.REVERSE
        motor.power = 0.605
        assertWrites(listOf(0.6, -0.6), hardware.powers)

        // A direction change also admits a command below the usual threshold.
        motor.direction = Direction.FORWARD
        motor.power = 0.605
        assertWrites(listOf(0.6, -0.6, 0.605), hardware.powers)
    }

    @Test
    fun `continuous servo resends power after changing direction`() {
        val hardware = ServoOutput()
        val device = CRServoImplEx(hardware.controller, 0, ServoConfigurationType())
        val servo = CrServo(directionTestScope(device), "device")
        servo.power = 0.6

        servo.direction = Direction.REVERSE
        assertWrites(listOf(0.8), hardware.positions)
        servo.power = Double.NaN
        servo.power = 0.6

        assertWrites(listOf(0.8, 0.2), hardware.positions)

        servo.direction = Direction.REVERSE
        servo.power = 0.605
        assertWrites(listOf(0.8, 0.2), hardware.positions)

        servo.direction = Direction.FORWARD
        servo.power = 0.605
        assertWrites(listOf(0.8, 0.2, 0.8025), hardware.positions)
    }

    @Test
    fun `positional servo resends position after changing direction`() {
        val hardware = ServoOutput()
        val device = ServoImplEx(hardware.controller, 0, ServoConfigurationType())
        val servo = Servo(directionTestScope(device), "device")
        servo.position = 0.7

        servo.direction = ServoDirection.REVERSE
        assertWrites(listOf(0.7), hardware.positions)
        servo.position = Double.NaN
        servo.position = 0.7

        assertWrites(listOf(0.7, 0.3), hardware.positions)

        servo.direction = ServoDirection.REVERSE
        servo.position = 0.705
        assertWrites(listOf(0.7, 0.3), hardware.positions)

        servo.direction = ServoDirection.FORWARD
        servo.position = 0.705
        assertWrites(listOf(0.7, 0.3, 0.705), hardware.positions)
    }

    @Test
    fun `changing direction stays safe without hardware`() {
        val motor = Motor(testEmptyHardwareScope(), "unused")
        val continuous = CrServo(testEmptyHardwareScope(), "unused")
        val positional = Servo(testEmptyHardwareScope(), "unused")

        motor.direction = Direction.REVERSE
        continuous.direction = Direction.REVERSE
        positional.direction = ServoDirection.REVERSE
        motor.power = 0.0
        continuous.power = 0.0
        positional.position = 0.0

        assertEquals(Direction.FORWARD, motor.direction)
        assertEquals(Direction.FORWARD, continuous.direction)
        assertEquals(ServoDirection.FORWARD, positional.direction)
        assertEquals(0.0, motor.power, 0.0)
        assertEquals(0.0, continuous.power, 0.0)
        assertEquals(0.0, positional.position, 0.0)
    }
}

private fun assertWrites(expected: List<Double>, actual: List<Double>) {
    assertEquals("controller writes: $actual", expected.size, actual.size)
    expected.zip(actual).forEach { (wanted, received) -> assertEquals(wanted, received, 1e-9) }
}

private fun directionTestScope(device: HardwareDevice) = HardwareAccess.from(
    object : HardwareMap(null, null) {
        override fun <T : Any?> get(classOrInterface: Class<out T>, deviceName: String): T {
            require(deviceName == "device")
            return requireNotNull(classOrInterface.cast(device))
        }
    }
)

private class MotorOutput {
    val powers = mutableListOf<Double>()
    private val controller = Proxy.newProxyInstance(
        DcMotorControllerEx::class.java.classLoader,
        arrayOf(DcMotorControllerEx::class.java),
    ) { _, method, args ->
        when(method.name) {
            "setMotorType" -> null
            "getMotorMode" -> DcMotor.RunMode.RUN_WITHOUT_ENCODER
            "setMotorPower" -> {
                powers += args!![1] as Double
                null
            }
            else -> error("Unexpected motor controller call: ${method.name}")
        }
    } as DcMotorControllerEx
    val device = DcMotorImplEx(controller, 0, Direction.FORWARD, MotorConfigurationType())
}

private class ServoOutput {
    val positions = mutableListOf<Double>()
    val controller = Proxy.newProxyInstance(
        ServoControllerEx::class.java.classLoader,
        arrayOf(ServoControllerEx::class.java),
    ) { _, method, args ->
        when(method.name) {
            "setServoType", "forgetLastKnownPosition" -> null
            "setServoPosition" -> {
                positions += args!![1] as Double
                null
            }
            else -> error("Unexpected servo controller call: ${method.name}")
        }
    } as ServoControllerEx
}
