package gentoo.myrelib.pathing

import com.pedropathing.math.Vector2D
import com.pedropathing.paths.curves.Line
import gentoo.myrelib.math.EPSILON
import gentoo.myrelib.math.assertAngleEquals
import gentoo.myrelib.math.toPose
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Heading interpolation has broken twice in this repo's history, and it is
 * invisible until the robot is on the field, so pin the behaviour down here.
 *
 * Interpolators are evaluated directly, which needs no follower or hardware.
 * Results come back as pedro headings, so they are converted to myrelib degrees
 * before comparing.
 */
class HeadingInterpolationTest {
    /** the tangent direction is distinctive so a fall through to it is obvious */
    private val tangentDirection = 123.0

    private val curve = Line(
        Vector2D.zero(),
        Vector2D.polar(1.0, Math.toRadians(tangentDirection))
    )

    /** @return the interpolated heading at [t] as a myrelib heading in degrees */
    private fun Path.Heading.headingAt(t: Double): Double =
        asPedro().interpolate(curve, t).toPose()

    @Test
    fun `a constant heading holds all the way along the path`() {
        val heading = Path.ConstantHeading(30.0)
        for(t in 0..10) {
            assertAngleEquals(30.0, heading.headingAt(t / 10.0))
        }
    }

    @Test
    fun `a linear heading sweeps from start to end`() {
        val heading = Path.LinearHeading(0.0, 90.0)
        assertAngleEquals(0.0, heading.headingAt(0.0))
        assertAngleEquals(45.0, heading.headingAt(0.5))
        assertAngleEquals(90.0, heading.headingAt(1.0))
    }

    @Test
    fun `a linear heading that finishes early holds its end heading afterwards`() {
        // without a node covering the tail of the path, pedro's piecewise
        // interpolator falls back to tangent following, which whips the robot
        // around to face along the path instead of holding the heading it was
        // told to finish at
        val heading = Path.LinearHeading(0.0, 90.0, 0.2, 0.8)

        assertAngleEquals(90.0, heading.headingAt(0.8))
        assertAngleEquals(90.0, heading.headingAt(0.9))
        assertAngleEquals(90.0, heading.headingAt(1.0))
    }

    @Test
    fun `a linear heading holds its start heading before the sweep begins`() {
        val heading = Path.LinearHeading(0.0, 90.0, 0.2, 0.8)
        assertAngleEquals(0.0, heading.headingAt(0.0))
        assertAngleEquals(0.0, heading.headingAt(0.1))
        assertAngleEquals(0.0, heading.headingAt(0.2))
    }

    @Test
    fun `a linear heading sweeps across the window it was given`() {
        val heading = Path.LinearHeading(0.0, 90.0, 0.2, 0.8)
        // halfway through the 0.2 to 0.8 window
        assertAngleEquals(45.0, heading.headingAt(0.5))
    }

    @Test
    fun `a tangent heading follows the path direction`() {
        val heading = Path.TangentHeading()
        assertEquals(
            Math.toRadians(tangentDirection).toPose(),
            heading.headingAt(0.5),
            EPSILON
        )
    }

    @Test
    fun `alliance transform rotates a constant heading 180 degrees`() {
        val transformed = Path.ConstantHeading(30.0).mirrored()
        assertAngleEquals(-150.0, transformed.headingAt(0.5))
    }

    @Test
    fun `alliance transform rotates both linear headings and keeps the window`() {
        val transformed = Path.LinearHeading(0.0, 90.0, 0.2, 0.8).mirrored()

        assertAngleEquals(-180.0, transformed.startHeading)
        assertAngleEquals(-90.0, transformed.endHeading)
        assertEquals(0.2, transformed.startT, EPSILON)
        assertEquals(0.8, transformed.endT, EPSILON)

        assertAngleEquals(-180.0, transformed.headingAt(0.1))
        assertAngleEquals(-135.0, transformed.headingAt(0.5))
        assertAngleEquals(-90.0, transformed.headingAt(0.9))
    }

    @Test
    fun `alliance transform leaves tangent strategy unchanged`() {
        val heading = Path.TangentHeading()
        assertEquals(heading, heading.mirrored())
    }
}
