package gentoo.myrelib.math

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

/**
 * Field convention under test: +y points to the top of the field, +x to the right,
 * headings are positive clockwise, and 0 degrees points straight up along +y.
 */
class PoseTest {
    private val origin = Pose(0.0, 0.0, 0.0)

    @Test
    fun `angleTo reads clockwise from the positive y axis`() {
        assertAngleEquals(0.0, origin.angleTo(Pose(0.0, 1.0)))
        assertAngleEquals(90.0, origin.angleTo(Pose(1.0, 0.0)))
        assertAngleEquals(180.0, origin.angleTo(Pose(0.0, -1.0)))
        assertAngleEquals(-90.0, origin.angleTo(Pose(-1.0, 0.0)))
        assertAngleEquals(45.0, origin.angleTo(Pose(1.0, 1.0)))
    }

    @Test
    fun `angleTo ignores the poses' own headings`() {
        val from = Pose(0.0, 0.0, 137.0)
        val to = Pose(1.0, 0.0, -42.0)
        assertAngleEquals(90.0, from.angleTo(to))
    }

    @Test
    fun `distanceTo is symmetric`() {
        val a = Pose(3.0, 4.0, 12.0)
        val b = Pose(0.0, 0.0, -80.0)
        assertEquals(5.0, a.distanceTo(b), EPSILON)
        assertEquals(5.0, b.distanceTo(a), EPSILON)
    }

    @Test
    fun `offset moves along the given heading, not the pose's own`() {
        assertPoseEquals(Pose(0.0, 5.0, 30.0), Pose(0.0, 0.0, 30.0).offset(5.0, 0.0))
        assertPoseEquals(Pose(5.0, 0.0, 30.0), Pose(0.0, 0.0, 30.0).offset(5.0, 90.0))
        assertPoseEquals(Pose(0.0, -5.0, 30.0), Pose(0.0, 0.0, 30.0).offset(5.0, 180.0))
        assertPoseEquals(Pose(-5.0, 0.0, 30.0), Pose(0.0, 0.0, 30.0).offset(5.0, -90.0))
    }

    @Test
    fun `offset by a negative distance goes backwards`() {
        assertPoseEquals(Pose(0.0, -5.0, 0.0), origin.offset(-5.0, 0.0))
    }

    @Test
    fun `rotate turns the position clockwise, matching the heading convention`() {
        // (1, 0) is due east; rotating clockwise a quarter turn puts it due south
        assertPoseEquals(Pose(0.0, -1.0, 90.0), Pose(1.0, 0.0, 0.0).rotate(90.0, true))
        assertPoseEquals(Pose(-1.0, 0.0, 180.0), Pose(1.0, 0.0, 0.0).rotate(180.0, true))
        assertPoseEquals(Pose(0.0, 1.0, -90.0), Pose(1.0, 0.0, 0.0).rotate(-90.0, true))
    }

    @Test
    fun `rotate leaves theta alone when asked to`() {
        assertPoseEquals(Pose(0.0, -1.0, 45.0), Pose(1.0, 0.0, 45.0).rotate(90.0, false))
    }

    @Test
    fun `rotate preserves distance from the origin`() {
        val pose = Pose(3.0, 4.0, 0.0)
        for(angle in 0..350 step 10) {
            val rotated = pose.rotate(angle.toDouble(), true)
            assertEquals(5.0, origin.distanceTo(rotated), 1e-9)
        }
    }

    @Test
    fun `deltaTheta is the normalized difference toward the target`() {
        assertAngleEquals(90.0, Pose(0.0, 0.0, 0.0).deltaTheta(Pose(0.0, 0.0, 90.0)))
        assertAngleEquals(-90.0, Pose(0.0, 0.0, 90.0).deltaTheta(Pose(0.0, 0.0, 0.0)))
        // takes the short way around rather than reporting 340 degrees
        assertAngleEquals(-20.0, Pose(0.0, 0.0, 170.0).deltaTheta(Pose(0.0, 0.0, 150.0)))
        assertAngleEquals(20.0, Pose(0.0, 0.0, 170.0).deltaTheta(Pose(0.0, 0.0, -170.0)))
    }

    @Test
    fun `vectorTo carries the distance and the field angle`() {
        val vector = origin.vectorTo(Pose(1.0, 1.0))
        assertEquals(sqrt(2.0), vector.magnitude, EPSILON)
        assertAngleEquals(45.0, vector.direction)
    }

    @Test
    fun `asVector treats the pose's position as a free vector`() {
        val vector = Pose(3.0, 4.0, 99.0).asVector()
        assertEquals(5.0, vector.magnitude, EPSILON)
        assertAngleEquals(Pose().angleTo(Pose(3.0, 4.0)), vector.direction)
    }

    @Test
    fun `withX withY and withTheta only change one field`() {
        val pose = Pose(1.0, 2.0, 3.0)
        assertPoseEquals(Pose(9.0, 2.0, 3.0), pose.withX(9.0))
        assertPoseEquals(Pose(1.0, 9.0, 3.0), pose.withY(9.0))
        assertPoseEquals(Pose(1.0, 2.0, 9.0), pose.withTheta(9.0))
    }

    @Test
    fun `reverseTheta flips the heading and keeps the position`() {
        assertPoseEquals(Pose(1.0, 2.0, 180.0), Pose(1.0, 2.0, 0.0).reverseTheta())
        assertPoseEquals(Pose(1.0, 2.0, 0.0), Pose(1.0, 2.0, 180.0).reverseTheta())
        assertPoseEquals(Pose(1.0, 2.0, -90.0), Pose(1.0, 2.0, 90.0).reverseTheta())
    }

    @Test
    fun `offset then angleTo round trips`() {
        for(heading in -180..170 step 10) {
            val degrees = heading.toDouble()
            val target = origin.offset(10.0, degrees)
            assertAngleEquals(degrees, origin.angleTo(target))
            assertEquals(10.0, origin.distanceTo(target), 1e-9)
        }
    }
}
