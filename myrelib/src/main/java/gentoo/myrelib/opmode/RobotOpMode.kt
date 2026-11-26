package gentoo.myrelib.opmode

import gentoo.myrelib.config.ComponentConfig
import gentoo.myrelib.input.FtcInputBindings
import gentoo.myrelib.robot.BaseRobot
import gentoo.myrelib.robot.RobotSession
import gentoo.myrelib.util.Alliance
import gentoo.myrelib.util.cleanupAll

/** Opmode-local input and execution, with persistent robot components attached during INIT. */
abstract class RobotOpMode(
    private val alliance: Alliance? = null,
    private val robot: BaseRobot = BaseRobot.INSTANCE,
    private val config: ComponentConfig = robot.config,
) : OpMode() {
    private var attachedSession: RobotSession? = null
    private var bindings: FtcInputBindings? = null
    protected val inputs: FtcInputBindings
        get() = checkNotNull(bindings) { "Inputs are available during an opmode session" }

    final override fun initStartPre() {
        attachedSession = robot.startOpMode(this, alliance, config)
        bindings = FtcInputBindings(gamepad1, gamepad2)
    }

    final override fun initTickPre() = robot.components.tick()
    final override fun playStartPre() = robot.start()

    final override fun playTickPre() {
        inputs.poll()
        robot.components.tick()
    }

    final override fun cleanup() {
        val previous = attachedSession
        try {
            cleanupAll(
                { bindings?.clear() },
                { if(previous != null) robot.reset(previous) },
            )
        }
        finally {
            bindings = null
            attachedSession = null
        }
    }
}
