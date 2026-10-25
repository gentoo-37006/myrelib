package gentoo.myrelib.math

import gentoo.myrelib.math.Pose.Companion.startPose
import gentoo.myrelib.math.Pose.Heading
import gentoo.myrelib.math.Pose.Horizontal
import gentoo.myrelib.math.Pose.Offset
import gentoo.myrelib.math.Pose.Vertical
import org.junit.Test

class StartPoseTest {
    private val halfLength = 9.0
    private val halfWidth = 7.0

    @Test
    fun `each direction is a myrelib heading clockwise from positive y`() {
        val expected = mapOf(
            Heading.NORTH to 0.0,
            Heading.EAST to 90.0,
            Heading.SOUTH to 180.0,
            Heading.WEST to -90.0,
        )

        for ((direction, theta) in expected) {
            assertAngleEquals(
                theta,
                startPose(
                    Horizontal(0.0, Offset.NONE),
                    Vertical(0.0, Offset.NONE),
                    direction,
                    halfLength,
                    halfWidth,
                ).theta,
            )
        }
    }

    @Test
    fun `facing north or south puts width on x and length on y`() {
        for (direction in listOf(Heading.NORTH, Heading.SOUTH)) {
            assertPoseEquals(
                Pose(10.0 + halfWidth, 20.0 - halfLength, direction.theta),
                startPose(
                    Horizontal(10.0, Offset.POS),
                    Vertical(20.0, Offset.NEG),
                    direction,
                    halfLength,
                    halfWidth,
                ),
            )
        }
    }

    @Test
    fun `facing east or west puts length on x and width on y`() {
        for (direction in listOf(Heading.EAST, Heading.WEST)) {
            assertPoseEquals(
                Pose(10.0 + halfLength, 20.0 - halfWidth, direction.theta),
                startPose(
                    Horizontal(10.0, Offset.POS),
                    Vertical(20.0, Offset.NEG),
                    direction,
                    halfLength,
                    halfWidth,
                ),
            )
        }
    }
}
