package gentoo.myrelib.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs

const val EPSILON = 1e-9

/**
 * Headings that differ by a multiple of 360 describe the same direction, so comparing
 * them with [assertEquals] gives false failures. The most common case is 180 vs -180,
 * which [normalizeAngle] maps onto each other.
 */
fun assertAngleEquals(expected: Double, actual: Double) {
    val delta = normalizeAngle(actual - expected)
    assertTrue(
        "expected heading <$expected> but was <$actual>, off by $delta degrees",
        abs(delta) < EPSILON
    )
}

fun assertPoseEquals(expected: Pose, actual: Pose) {
    assertEquals("x", expected.x, actual.x, EPSILON)
    assertEquals("y", expected.y, actual.y, EPSILON)
    assertAngleEquals(expected.theta, actual.theta)
}
