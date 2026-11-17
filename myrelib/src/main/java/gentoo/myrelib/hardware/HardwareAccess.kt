package gentoo.myrelib.hardware

import com.qualcomm.robotcore.hardware.HardwareMap
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** A device source, or a revocable, opmode-thread component lease on that source. */
class HardwareAccess private constructor(
    private val hardwareMap: HardwareMap? = null,
    private val managed: Boolean = false,
) {
    private var source: HardwareAccess? = null
    private var owner: Thread? = null
    private var generation = 0L
    private class Lease(var device: Any?)
    private val devices = mutableMapOf<Pair<Class<*>, String>, Any>()
    private val leases = mutableListOf<Lease>()

    val isAvailable: Boolean get() = if(managed) source?.isAvailable == true else hardwareMap != null

    /** Low-level escape hatch for integrations such as Pedro. Never retain this beyond detach. */
    val hardwareMapOrNull: HardwareMap?
        get() {
            checkThread()
            return if(managed) source?.hardwareMapOrNull else hardwareMap
        }

    @PublishedApi
    internal fun <T> get(deviceType: Class<T>, name: String): T? {
        checkThread()
        if(!managed) return hardwareMap?.get(deviceType, name)
        val currentSource = source ?: return null
        if(!currentSource.isAvailable) return null
        require(deviceType.isInterface) {
            "Managed hardware requires an SDK interface (for example DcMotorEx or Servo)"
        }
        val key = deviceType to name
        val device = devices.getOrPut(key) {
            val handle = Lease(checkNotNull(currentSource.get(deviceType, name)))
            leases += handle
            val lease = generation
            Proxy.newProxyInstance(deviceType.classLoader, arrayOf(deviceType)) { proxy, method, args ->
                when(method.name) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "HardwareLease($name, generation=$lease)"
                    else -> {
                        checkThread()
                        check(source != null && generation == lease) { "Hardware lease for '$name' has expired" }
                        try { method.invoke(checkNotNull(handle.device), *(args ?: emptyArray())) }
                        catch(error: InvocationTargetException) { throw error.targetException }
                    }
                }
            }
        }
        return deviceType.cast(device)
    }

    inline fun <reified T> get(name: String): T? = get(T::class.java, name)

    private fun checkThread() {
        check(!managed || owner == null || owner === Thread.currentThread()) {
            "Component hardware may only be used on the opmode thread"
        }
    }

    internal fun attach(source: HardwareAccess, disabled: Boolean) {
        check(managed) { "Persistent components must use the no-argument Component constructor" }
        revoke()
        owner = Thread.currentThread()
        this.source = if(disabled) null else source
    }

    internal fun revoke() {
        source = null
        owner = null
        generation++
        leases.forEach { it.device = null }
        leases.clear()
        devices.clear()
    }

    internal fun forComponent(disabled: Boolean): HardwareAccess = if(disabled) empty() else this

    companion object {
        fun from(hardwareMap: HardwareMap): HardwareAccess = HardwareAccess(hardwareMap)
        fun empty(): HardwareAccess = HardwareAccess()
        internal fun componentScope(): HardwareAccess = HardwareAccess(managed = true)
    }
}
