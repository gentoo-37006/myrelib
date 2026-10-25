package gentoo.myrelib.pathing

import gentoo.myrelib.math.Pose

class Spline(
    override val start: Pose,
    val control1: Pose,
    val control2: Pose,
    val end: Pose
) : Curve() {
    override fun mirrored() = Spline(
        start.invert(),
        control1.invert(),
        control2.invert(),
        end.invert()
    )
}