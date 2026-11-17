package gentoo.myrelib.pathing

import gentoo.myrelib.math.Pose

class Linear(
    override val start: Pose,
    val end: Pose
) : Curve() {
    override fun mirrored() = Linear(
        start.invert(),
        end.invert()
    )
}