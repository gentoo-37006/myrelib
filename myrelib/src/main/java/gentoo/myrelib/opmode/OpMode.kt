package gentoo.myrelib.opmode

import com.qualcomm.hardware.lynx.LynxModule
import com.qualcomm.robotcore.hardware.HardwareMap
import gentoo.myrelib.util.cleanupAll
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

    /** Framework resource cleanup, attempted even when user hooks fail. */
    internal open fun cleanup() {}

    private inline fun lifecycle(block: () -> Unit) {
        try { block() }
        catch(failure: Throwable) {
            try { cleanup() }
            catch(cleanupFailure: Throwable) {
                if(failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    enum class Phase {
        INIT,
        PLAY
    }

    final override fun init() = lifecycle {
        phase = Phase.INIT
        hubs = Hubs(hardwareMap)
        hubs.setBulkCachingMode(LynxModule.BulkCachingMode.MANUAL)
        hubs.clearBulkCache()
        initStartPre()
        initStart()
        initStartPost()
    }

    final override fun init_loop() = lifecycle {
        hubs.clearBulkCache()
        initTickPre()
        initTick()
        initTickPost()
    }

    final override fun start() = lifecycle {
        hubs.clearBulkCache()
        phase = Phase.PLAY
        playStartPre()
        playStart()
        playStartPost()
    }

    final override fun loop() = lifecycle {
        hubs.clearBulkCache()
        playTickPre()
        playTick()
        playTickPost()
    }

    final override fun stop() {
        cleanupAll(::playStopPre, ::playStop, ::playStopPost, ::cleanup)
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
