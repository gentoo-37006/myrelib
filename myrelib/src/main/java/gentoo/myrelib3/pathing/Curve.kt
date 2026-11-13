package gentoo.myrelib3.pathing

import gentoo.myrelib.math.Pose

sealed class Curve {
    abstract val start: Pose

    abstract fun mirrored(): Curve
}