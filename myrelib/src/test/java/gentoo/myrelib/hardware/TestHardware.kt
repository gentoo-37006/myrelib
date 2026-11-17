package gentoo.myrelib.hardware

import com.qualcomm.robotcore.hardware.HardwareMap
import gentoo.myrelib.hardware.HardwareAccess

/**
 * A root [HardwareAccess] whose FTC map contains no devices.
 *
 * Disabled components must replace this with an empty component scope before
 * their bodies run. If they accidentally retain or query the root scope, the
 * lookup fails and exposes the isolation regression.
 */
fun testHardwareScope(): HardwareAccess = HardwareAccess.from(HardwareMap(null, null))

/** Hardwareless scope for testing wrappers without a component owner. */
fun testEmptyHardwareScope(): HardwareAccess = HardwareAccess.empty()
