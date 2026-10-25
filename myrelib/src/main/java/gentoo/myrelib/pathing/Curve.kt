package gentoo.myrelib.pathing

import gentoo.myrelib.math.Pose

sealed class Curve {
    abstract val start: Pose

    abstract fun mirrored(): Curve
}