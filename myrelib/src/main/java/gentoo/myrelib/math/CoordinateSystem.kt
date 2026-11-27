package gentoo.myrelib.math

/**
 * The fixed FTC field dimensions that define the myrelib/Pedro origin shift.
 * Changing these values changes the coordinate contract, not just robot tuning.
 */
object CoordinateSystem {
    /** inches */
    const val FIELD_SIZE: Double = 144.0

    /** inches */
    const val HALF_FIELD_SIZE: Double = FIELD_SIZE / 2.0
}
