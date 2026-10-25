package gentoo.myrelib.math

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit
import org.firstinspires.ftc.robotcore.external.navigation.Pose2D
import org.junit.Assert.assertEquals
import org.junit.Test
import com.pedropathing.math.Pose as PedroPose

/**
 * Pedro puts the field origin in a corner and measures headings counter-clockwise from
 * +x in radians; myrelib puts the origin in the middle and measures clockwise from +y
 * in degrees. Getting this wrong sends the robot to the wrong quadrant.
 */
class CoordinateConversionTest {
    @Test
    fun `toPedro shifts the origin from the field center to the corner`() {
        val pedro = Pose(0.0, 0.0, 0.0).toPedro()
        assertEquals(72.0, pedro.x(), EPSILON)
        assertEquals(72.0, pedro.y(), EPSILON)
    }

    @Test
    fun `toPose shifts the origin back to the field center`() {
        val pose = PedroPose(72.0, 72.0, Math.toRadians(90.0)).toPose()
        assertEquals(0.0, pose.x, EPSILON)
        assertEquals(0.0, pose.y, EPSILON)
        assertAngleEquals(0.0, pose.theta)
    }

    @Test
    fun `zero degrees points up in both frames`() {
        // myrelib 0 degrees is +y, which is 90 degrees counter-clockwise from +x in pedro
        assertEquals(Math.toRadians(90.0), 0.0.toPedro(), EPSILON)
    }

    @Test
    fun `clockwise degrees become counter-clockwise radians`() {
        assertEquals(Math.toRadians(0.0), 90.0.toPedro(), EPSILON)
        assertEquals(Math.toRadians(-90.0), 180.0.toPedro(), EPSILON)
        // due west normalizes to -180 rather than 180, so pedro gets -pi, not pi
        assertEquals(Math.toRadians(-180.0), (-90.0).toPedro(), EPSILON)
        assertAngleEquals(180.0, Math.toDegrees((-90.0).toPedro()))
    }

    @Test
    fun `heading conversion round trips`() {
        for(degrees in -180..170 step 5) {
            val theta = degrees.toDouble()
            assertAngleEquals(theta, theta.toPedro().toPose())
        }
    }

    @Test
    fun `pose conversion round trips`() {
        val poses = listOf(
            Pose(0.0, 0.0, 0.0),
            Pose(24.0, -36.0, 45.0),
            Pose(-60.5, 71.25, -135.0),
            Pose(12.0, 12.0, 180.0)
        )
        for(pose in poses) {
            assertPoseEquals(pose, pose.toPedro().toPose())
        }
    }

    @Test
    fun `a pedro pose stays inside the field for in-field myrelib poses`() {
        // the field is 144 inches across, so pedro coordinates should land in [0, 144]
        for(pose in listOf(Pose(-72.0, -72.0), Pose(72.0, 72.0), Pose(0.0, 0.0))) {
            val pedro = pose.toPedro()
            org.junit.Assert.assertTrue(pedro.x() in 0.0..144.0)
            org.junit.Assert.assertTrue(pedro.y() in 0.0..144.0)
        }
    }

    @Test
    fun `ftc pose conversion round trips`() {
        val poses = listOf(
            Pose(0.0, 0.0, 0.0),
            Pose(24.0, -36.0, 45.0),
            Pose(-60.5, 71.25, -135.0)
        )
        for(pose in poses) {
            assertPoseEquals(pose, pose.toFTC().toPose())
        }
    }

    @Test
    fun `ftc conversion uses biobuzz axes`() {
        val ftc = Pose(3.0, 4.0, 0.0).toFTC()
        assertEquals(4.0, ftc.getX(DistanceUnit.INCH), EPSILON)
        assertEquals(-3.0, ftc.getY(DistanceUnit.INCH), EPSILON)
        assertAngleEquals(0.0, ftc.getHeading(AngleUnit.DEGREES))
    }

    @Test
    fun `an ftc pose maps back onto the myrelib axes`() {
        val ftc = Pose2D(DistanceUnit.INCH, 4.0, -3.0, AngleUnit.DEGREES, 0.0)
        assertEquals(3.0, ftc.toPose().x, EPSILON)
        assertEquals(4.0, ftc.toPose().y, EPSILON)
    }
}
