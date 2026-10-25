package gentoo.myrelib.pathing

import com.pedropathing.api.Paths
import com.pedropathing.math.Pose as PedroPose
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Pedro behavior that MotionController's compound pathT relies on.
 *
 * Path callbacks fire on MotionController.pathT, which maps Pedro's per-segment
 * parameter onto the whole path by weighting each segment by its length.
 * Heading interpolators are evaluated by Pedro itself, on its own compound
 * parameter. The two agree only because Pedro's CompoundCurve is also
 * length-weighted. MotionControllerTest checks the mapping against a fake
 * follower that assumes this; this test checks the assumption against the real
 * Pedro, so a Pedro upgrade that parameterizes compound paths any other way
 * fails here instead of quietly firing callbacks at different points than the
 * heading sweeps.
 */
class CompoundParameterTest {
    private val epsilon = 1e-9

    // a 10 inch segment then a 90 inch one, so an even split would put the
    // boundary at t = 0.5 and a length-weighted one puts it at t = 0.1
    private val short = Paths.line(PedroPose(0.0, 0.0, 0.0), PedroPose(10.0, 0.0, 0.0))
    private val long = Paths.line(PedroPose(10.0, 0.0, 0.0), PedroPose(100.0, 0.0, 0.0))

    /** A compound path whose heading reports the compound parameter it was asked for. */
    private fun compound() = Paths.path(short, long).heading { _, t -> t }

    @Test
    fun `the compound parameter is weighted by segment length`() {
        val path = compound()
        assertEquals(10.0, path.get(0.1).x(), epsilon)
        assertEquals(50.0, path.get(0.5).x(), epsilon)
    }

    @Test
    fun `a segment's local parameter maps to the length-weighted compound parameter`() {
        // this is exactly the arithmetic MotionController.currentPathT performs
        val segments = compound().getSegments()
        val lengths = segments.map { it.curve.length() }
        val total = lengths.sum()

        for((index, segment) in segments.withIndex()) {
            val before = lengths.take(index).sum()
            for(local in listOf(0.0, 0.25, 0.5, 1.0)) {
                assertEquals(
                    "segment $index at local t=$local",
                    (before + lengths[index] * local) / total,
                    segment.heading(local),
                    epsilon
                )
            }
        }
    }
}
