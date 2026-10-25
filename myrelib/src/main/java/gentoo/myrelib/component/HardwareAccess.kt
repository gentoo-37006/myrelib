package gentoo.myrelib.component

import com.qualcomm.robotcore.hardware.HardwareMap

/**
 * Hardware access owned by one component.
 *
 * A disabled component receives an empty scope. Looking up any device then
 * returns null without consulting the FTC [HardwareMap], so the component and
 * all of its public references can still be constructed when its hardware is
 * absent from the robot configuration.
 */
class HardwareAccess private constructor(
    private val hardwareMap: HardwareMap?
) {
    val isAvailable: Boolean get() = hardwareMap != null

    val hardwareMapOrNull: HardwareMap? get() = hardwareMap

    @PublishedApi
    internal fun <T> get(deviceType: Class<T>, name: String): T? {
        return hardwareMap?.get(deviceType, name)
    }

    inline fun <reified T> get(name: String): T? = get(T::class.java, name)

    internal fun forComponent(disabled: Boolean): HardwareAccess {
        return if(disabled) empty() else this
    }

    companion object {
        private val EMPTY = HardwareAccess(null)

        fun from(hardwareMap: HardwareMap): HardwareAccess = HardwareAccess(hardwareMap)

        fun empty(): HardwareAccess = EMPTY
    }
}
