package gentoo.myrelib.hardware

import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.HardwareMap
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class HardwareLeaseTest {
    private class Device {
        var reads = 0
        val motor = Proxy.newProxyInstance(DcMotorEx::class.java.classLoader, arrayOf(DcMotorEx::class.java)) { _, method, _ ->
            check(method.name == "getCurrentPosition")
            reads++
            42
        }
        var lookups = 0
        val source = HardwareAccess.from(object : HardwareMap(null, null) {
            override fun <T : Any?> get(classOrInterface: Class<out T>, deviceName: String): T {
                lookups++
                return classOrInterface.cast(motor)!!
            }
        })
    }

    @Test fun `disabled attachment never queries the source and cached leases expire permanently`() {
        val device = Device()
        val scope = HardwareAccess.componentScope()
        scope.attach(device.source, disabled = true)
        assertNull(scope.get<DcMotorEx>("motor"))
        assertEquals(0, device.lookups)
        scope.revoke()
        scope.attach(device.source, disabled = false)
        val old = scope.get<DcMotorEx>("motor")!!
        assertSame(old, scope.get<DcMotorEx>("motor"))
        assertEquals(42, old.currentPosition)
        scope.revoke()
        assertThrows(IllegalStateException::class.java) { old.currentPosition }
        scope.attach(device.source, disabled = false)
        val fresh = scope.get<DcMotorEx>("motor")!!
        assertNotSame(old, fresh)
        assertThrows(IllegalStateException::class.java) { old.currentPosition }
        assertEquals(42, fresh.currentPosition)
        assertEquals(2, device.reads)
        scope.revoke()
    }

    @Test fun `lookup and device use are restricted to the attachment thread`() {
        val device = Device()
        val scope = HardwareAccess.componentScope()
        scope.attach(device.source, disabled = false)
        val motor = scope.get<DcMotorEx>("motor")!!
        val failures = AtomicReference<List<Throwable>>()
        Thread {
            failures.set(listOf(
                assertThrows(IllegalStateException::class.java) { scope.get<DcMotorEx>("motor") },
                assertThrows(IllegalStateException::class.java) { motor.currentPosition },
                assertThrows(IllegalStateException::class.java) { scope.hardwareMapOrNull },
            ))
        }.apply { start(); join() }
        assertEquals(3, failures.get().size)
        assertEquals(0, device.reads)
        assertEquals(42, motor.currentPosition)
        scope.revoke()
    }
}
