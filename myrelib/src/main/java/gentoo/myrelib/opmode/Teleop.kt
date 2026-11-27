package gentoo.myrelib.opmode

import gentoo.myrelib.input.FtcInputBindings
import gentoo.myrelib.input.GamepadTriggers
import gentoo.myrelib.util.Alliance
import gentoo.myrelib.util.cleanupAll

/** Driver-controlled opmode with per-run gamepad bindings. */
abstract class Teleop(
    final override val alliance: Alliance,
) : RobotOpMode(alliance) {
    protected lateinit var inputs: FtcInputBindings
        private set

    final override fun initStartPre() {
        super.initStartPre()
        inputs = FtcInputBindings(gamepad1, gamepad2)
        driverBindings(inputs.driver)
        operatorBindings(inputs.operator)
    }

    protected abstract fun driverBindings(gamepad: GamepadTriggers)

    protected abstract fun operatorBindings(gamepad: GamepadTriggers)

    final override fun playTickPre() {
        inputs.poll()
        super.playTickPre()
    }

    final override fun cleanup() {
        cleanupAll({ if(::inputs.isInitialized) inputs.clear() }, { super.cleanup() })
    }
}
