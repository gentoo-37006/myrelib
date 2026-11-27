package gentoo.myrelib.opmode

import gentoo.myrelib.component.ComponentConfig
import gentoo.myrelib.robot.BaseRobot
import gentoo.myrelib.robot.RobotSession
import gentoo.myrelib.util.Alliance

/** Opmode execution with persistent robot components attached during INIT. */
abstract class RobotOpMode(
    open val alliance: Alliance? = null,
    private val robot: BaseRobot = BaseRobot.INSTANCE,
    private val config: ComponentConfig = robot.config,
) : OpMode() {
    private var attachedSession: RobotSession? = null

    override fun initStartPre() {
        attachedSession = robot.startOpMode(this, alliance, config)
    }

    final override fun initTickPre() = robot.components.tick()
    final override fun playStartPre() = robot.start()
    override fun playTickPre() = robot.components.tick()

    override fun cleanup() {
        val previous = attachedSession ?: return
        try {
            robot.reset(previous)
        }
        finally {
            attachedSession = null
        }
    }
}
