package org.firstinspires.ftc.teamcode

import android.content.Context
import com.qualcomm.robotcore.hardware.HardwareDevice
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.Servo
import gentoo.myrelib.hardware.HardwareAccess
import org.firstinspires.ftc.teamcode.component.Claw
import java.lang.reflect.Proxy

/** An FTC map seam that avoids the SDK's Android/native lookup implementation. */
internal class FakeHardware(context: Context? = null) {
    val positions = mutableListOf<Double>()

    private val servo = Proxy.newProxyInstance(
        Servo::class.java.classLoader,
        arrayOf(Servo::class.java)
    ) { proxy, method, arguments ->
        when(method.name) {
            "setPosition" -> { positions.add(arguments!![0] as Double); null }
            "getPosition" -> positions.lastOrNull() ?: 0.0
            "getDirection" -> Servo.Direction.FORWARD
            "getManufacturer" -> HardwareDevice.Manufacturer.Other
            "getDeviceName" -> "Fake claw"
            "getConnectionInfo" -> "JVM fixture"
            "getVersion", "getPortNumber" -> 0
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments!![0]
            "toString" -> "Fake claw"
            else -> null
        }
    } as Servo

    val map = object : HardwareMap(context, null) {
        override fun <T : Any?> getAll(type: Class<out T>): MutableList<T> = mutableListOf()

        override fun <T : Any?> get(classOrInterface: Class<out T>, deviceName: String): T {
            require(deviceName == Claw.HARDWARE_NAME) { "Unexpected device: $deviceName" }
            return requireNotNull(classOrInterface.cast(this@FakeHardware.servo))
        }
    }
    val access = HardwareAccess.from(map)
}
