package gentoo.myrelib.pathing

import gentoo.myrelib.math.Pose
import gentoo.myrelib.math.assertPoseEquals
import gentoo.myrelib.math.toPose
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Path construction and compilation are pure in Pedro v3 and need no follower.
 */
class PathTest {
    @Test
    fun `the start pose is the first control point`() {
        val path = Path(Linear(Pose(1.0, 2.0, 30.0), Pose(3.0, 4.0)))
        assertPoseEquals(Pose(1.0, 2.0, 30.0), path.startPose)
    }

    @Test
    fun `alliance transform inverts the start pose through the field center`() {
        val path = Path(Linear(Pose(10.0, 20.0, 90.0), Pose(30.0, 40.0))).mirrored()
        assertPoseEquals(Pose(-10.0, -20.0, -90.0), path.startPose)
    }

    @Test
    fun `alliance transform leaves the original alone`() {
        val path = Path(Linear(Pose(10.0, 20.0, 90.0), Pose(30.0, 40.0)))
        path.mirrored()
        assertPoseEquals(Pose(10.0, 20.0, 90.0), path.startPose)
    }

    @Test
    fun `applying the alliance transform twice gets back to where it started`() {
        val original = Path(Linear(Pose(10.0, 20.0, 90.0), Pose(30.0, 40.0)))
        assertPoseEquals(original.startPose, original.mirrored().mirrored().startPose)
    }

    @Test
    fun `alliance transform inverts every linear endpoint`() {
        val transformed = Linear(
            Pose(10.0, 20.0, 30.0),
            Pose(30.0, 40.0, 60.0)
        ).mirrored()

        assertPoseEquals(Pose(-10.0, -20.0, -150.0), transformed.start)
        assertPoseEquals(Pose(-30.0, -40.0, -120.0), transformed.end)
    }

    @Test
    fun `alliance transform inverts every spline control pose`() {
        val transformed = Spline(
            Pose(1.0, 2.0, 10.0),
            Pose(3.0, 4.0, 20.0),
            Pose(5.0, 6.0, 30.0),
            Pose(7.0, 8.0, 40.0)
        ).mirrored()

        assertPoseEquals(Pose(-1.0, -2.0, -170.0), transformed.start)
        assertPoseEquals(Pose(-3.0, -4.0, -160.0), transformed.control1)
        assertPoseEquals(Pose(-5.0, -6.0, -150.0), transformed.control2)
        assertPoseEquals(Pose(-7.0, -8.0, -140.0), transformed.end)
    }

    @Test
    fun `tangent heading follows the inverted curve direction`() {
        val transformed = Path(
            Linear(Pose(10.0, 20.0), Pose(20.0, 20.0))
        ).tangentHeading().mirrored().build()

        assertPoseEquals(Pose(-15.0, -20.0, -90.0), transformed.get(0.5).toPose())
    }

    @Test
    fun `reversed tangent heading follows opposite the inverted curve direction`() {
        val transformed = Path(
            Linear(Pose(10.0, 20.0), Pose(20.0, 20.0))
        ).reversedTangentHeading().mirrored().build()

        assertPoseEquals(Pose(-15.0, -20.0, 90.0), transformed.get(0.5).toPose())
    }

    @Test
    fun `a path needs at least one segment`() {
        val empty = assertThrows(IllegalArgumentException::class.java) { Path() }
        assertTrue(
            "the message should say what was wrong: ${empty.message}",
            empty.message!!.contains("at least 1")
        )
    }

    @Test
    fun `one linear segment is enough`() {
        Path(Linear(Pose(0.0, 0.0), Pose(1.0, 1.0)))
    }

    @Test
    fun `the heading builders return the same path for chaining`() {
        val path = Path(Linear(Pose(0.0, 0.0), Pose(1.0, 1.0)))
        assertTrue(path === path.constantHeading(0.0))
        assertTrue(path === path.tangentHeading())
        assertTrue(path === path.linearHeading(0.0, 90.0))
        assertTrue(path === path.linearHeading(0.0, 90.0, 0.2, 0.8))
    }

    @Test
    fun `windowed heading bounds are validated`() {
        assertThrows(IllegalArgumentException::class.java) {
            Path.LinearHeading(0.0, 90.0, -0.1, 0.8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Path.LinearHeading(0.0, 90.0, 0.8, 0.8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Path.LinearHeading(0.0, 90.0, 0.2, 1.1)
        }
    }
}
