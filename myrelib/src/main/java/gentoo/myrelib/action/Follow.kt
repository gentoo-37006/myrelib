package gentoo.myrelib.action

import gentoo.myrelib.pathing.Path
import gentoo.myrelib.component.MotionController
import gentoo.myrelib.robot.BaseRobot
import java.util.ArrayDeque
import com.pedropathing.paths.Path as PedroPath

class Follow internal constructor(
    private val path: PedroPath,
    callbacks: List<Pair<Double, () -> Unit>>,
) : Action() {
    constructor(path: Path) : this(path.build(), path.callbacks())

    private val pendingCallbacks = ArrayDeque(callbacks)
    private var motion: MotionController? = null
    private var active = false
    override val requirements get() = setOf(BaseRobot.INSTANCE.motion)

    override fun start() {
        motion = BaseRobot.INSTANCE.motion
        active = true
        motion!!.follow(path)
        runCallbacksThrough(0.0)
    }

    override fun canContinue(): Boolean {
        val following = motion?.following == true
        runCallbacksThrough(if(following) motion!!.pathT else 1.0)
        return active && following
    }

    private fun runCallbacksThrough(t: Double) {
        while(active && pendingCallbacks.peekFirst()?.first?.let { it <= t } == true) {
            pendingCallbacks.removeFirst().second.invoke()
        }
    }

    override fun stop(interrupted: Boolean) {
        active = false
        if(interrupted) motion?.stop()
        motion = null
    }
}
