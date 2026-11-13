package gentoo.myrelib.opmode

import com.qualcomm.hardware.lynx.LynxModule
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.eventloop.opmode.OpMode as FTCOpMode

abstract class OpMode : FTCOpMode() {
    protected var phase = Phase.INIT
    private lateinit var hubs: Hubs

    internal open fun initStartPre() {
    }

    open fun initStart() {
    }

    internal open fun initStartPost() {
    }

    internal open fun initTickPre() {
    }

    open fun initTick() {
    }

    internal open fun initTickPost() {
    }

    internal open fun playStartPre() {
    }

    open fun playStart() {
    }

    internal open fun playStartPost() {
    }

    internal open fun playTickPre() {
    }

    open fun playTick() {
    }

    internal open fun playTickPost() {
    }

    internal open fun playStopPre() {
    }

    open fun playStop() {
    }

    internal open fun playStopPost() {
    }

    enum class Phase {
        INIT,
        PLAY
    }

    final override fun init() {
        hubs = Hubs(hardwareMap)
        hubs.setBulkCachingMode(LynxModule.BulkCachingMode.MANUAL)
        initStartPre()
        initStart()
        initStartPost()
    }

    final override fun init_loop() {
        hubs.clearBulkCache()
        initTickPre()
        initTick()
        initTickPost()
    }

    final override fun start() {
        phase = Phase.PLAY
        playStartPre()
        playStart()
        playStartPost()
    }

    final override fun loop() {
        hubs.clearBulkCache()
        playTickPre()
        playTick()
        playTickPost()
    }

    @Suppress("KotlinConstantConditions")
    final override fun stop() {
        var stopFailure: Throwable? = null
        // attempt all hooks in order, even if one throws
        for(hook in listOf(::playStopPre, ::playStop, ::playStopPost)) {
            try {
                hook()
            }
            catch(failure: Throwable) {
                if(stopFailure == null) {
                    stopFailure = failure
                }
                else if(stopFailure !== failure) {
                    stopFailure.addSuppressed(failure)
                }
            }
        }
        stopFailure?.let { throw it }
    }

    private class Hubs(hardwareMap: HardwareMap) {
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
}
