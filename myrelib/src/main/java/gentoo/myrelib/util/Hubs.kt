package gentoo.myrelib.util

import com.qualcomm.hardware.lynx.LynxModule
import com.qualcomm.robotcore.hardware.HardwareMap

class Hubs(hardwareMap: HardwareMap) {
    val hubs: List<LynxModule> = hardwareMap.getAll(LynxModule::class.java)

    fun setBulkCachingMode(mode: LynxModule.BulkCachingMode) {
        for(hub in hubs) {
            hub.setBulkCachingMode(mode)
        }
    }

    fun clearBulkCache() {
        for(hub in hubs) {
            hub.clearBulkCache()
        }
    }
}