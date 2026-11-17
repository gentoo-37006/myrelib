package gentoo.myrelib.hardware

import com.qualcomm.robotcore.hardware.CRServoImplEx
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.HardwareDevice
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.ServoControllerEx
import com.qualcomm.robotcore.hardware.configuration.typecontainers.ServoConfigurationType
import gentoo.myrelib.hardware.HardwareAccess
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks actual device writes, rather than only the wrappers' cached values. */
class InitialPowerCommandTest {
    @Test
    fun `first motor stop reaches hardware with unknown prior power`() {
        val hardware = RecordingMotor()
        val motor = Motor(deviceScope(hardware.device), "device")

        motor.power = 0.0
        motor.power = 0.0

        assertEquals(listOf(0.0), hardware.writes)
        assertEquals(0.0, hardware.device.power, 1e-9)
    }

    @Test
    fun `motor commits a small first command then throttles changes and always stops`() {
        val hardware = RecordingMotor()
        val motor = Motor(deviceScope(hardware.device), "device")

        motor.power = 0.005
        motor.power = 0.006
        assertEquals(listOf(0.005), hardware.writes)
        assertEquals(0.005, motor.power, 1e-9)

        motor.power = 0.0
        assertEquals(listOf(0.005, 0.0), hardware.writes)
        assertEquals(0.0, hardware.device.power, 1e-9)
    }

    @Test
    fun `first continuous servo stop reaches hardware with unknown prior power`() {
        val hardware = RecordingServo()
        val servo = CrServo(deviceScope(hardware.device), "device") { power = 0.0 }

        servo.power = 0.0

        // The SDK translates CRServo power 0 to controller position 0.5.
        assertEquals(listOf(0.5), hardware.positions)
        assertEquals(0.0, hardware.device.power, 1e-9)
    }

    @Test
    fun `continuous servo commits a small first command then throttles changes and always stops`() {
        val hardware = RecordingServo()
        val servo = CrServo(deviceScope(hardware.device), "device")

        servo.power = -0.005
        servo.power = -0.006
        assertEquals(1, hardware.positions.size)
        assertEquals(-0.005, hardware.device.power, 1e-9)
        assertEquals(-0.005, servo.power, 1e-9)

        servo.power = 0.0
        assertEquals(2, hardware.positions.size)
        assertEquals(0.5, hardware.positions.last(), 1e-9)
        assertEquals(0.0, hardware.device.power, 1e-9)
    }

    @Test
    fun `NaN is ignored without consuming the first power command`() {
        val motorHardware = RecordingMotor()
        val servoHardware = RecordingServo()
        val motor = Motor(deviceScope(motorHardware.device), "device")
        val servo = CrServo(deviceScope(servoHardware.device), "device")

        motor.power = Double.NaN
        servo.power = Double.NaN
        assertTrue(motorHardware.writes.isEmpty())
        assertTrue(servoHardware.positions.isEmpty())

        motor.power = 0.0
        servo.power = 0.0
        assertEquals(listOf(0.0), motorHardware.writes)
        assertEquals(listOf(0.5), servoHardware.positions)
    }
}

private fun deviceScope(device: HardwareDevice): HardwareAccess = HardwareAccess.from(
    object : HardwareMap(null, null) {
        override fun <T : Any?> get(classOrInterface: Class<out T>, deviceName: String): T {
            require(deviceName == "device")
            return requireNotNull(classOrInterface.cast(device))
        }
    }
)

private class RecordingMotor {
    val writes = mutableListOf<Double>()
    private var power = 0.6
    val device = Proxy.newProxyInstance(
        DcMotorEx::class.java.classLoader,
        arrayOf(DcMotorEx::class.java),
    ) { _, method, args ->
        when(method.name) {
            "setPower" -> {
                power = args!![0] as Double
                writes += power
                null
            }
            "getPower" -> power
            else -> error("Unexpected motor call: ${method.name}")
        }
    } as DcMotorEx
}

private class RecordingServo {
    val positions = mutableListOf<Double>()
    private var position = 0.8
    private val controller = Proxy.newProxyInstance(
        ServoControllerEx::class.java.classLoader,
        arrayOf(ServoControllerEx::class.java),
    ) { _, method, args ->
        when(method.name) {
            "setServoType" -> null
            "setServoPosition" -> {
                position = args!![1] as Double
                positions += position
                null
            }
            "getServoPosition" -> position
            else -> error("Unexpected servo controller call: ${method.name}")
        }
    } as ServoControllerEx
    val device = CRServoImplEx(controller, 0, ServoConfigurationType())
}
