package gentoo.myrelib.component

import com.pedropathing.algorithm.Algorithm
import com.pedropathing.api.Paths
import com.pedropathing.drivetrain.DrivePowers
import com.pedropathing.drivetrain.Drivetrain
import com.pedropathing.follower.Follower
import com.pedropathing.localization.Localizer
import com.pedropathing.localization.MotionState
import com.pedropathing.math.Pose as PedroPose
import com.pedropathing.math.Vector2D
import com.pedropathing.math.Velocity
import com.pedropathing.paths.PathTracker
import gentoo.myrelib.hardware.testHardwareScope
import gentoo.myrelib.math.Pose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionControllerTest {
    private class TestLocalizer : Localizer {
        private var current = MotionState.zero()

        override fun setPose(pose: PedroPose) {
            current = current.withPose(pose)
        }

        fun setVelocity(velocity: Velocity) {
            current = MotionState.ofVelocity(current.pose(), velocity)
        }

        override fun state(): MotionState = current

        override fun update() = Unit

        override fun reset() {
            current = MotionState.zero()
        }
    }

    private class TestDrivetrain : Drivetrain {
        var powers = DrivePowers.zero()
        var stopCount = 0

        override fun drive(powers: DrivePowers, manual: Boolean) {
            this.powers = powers
        }

        override fun maxScaling(current: DrivePowers, delta: DrivePowers) = 1.0

        override fun stop() {
            stopCount++
            powers = DrivePowers.zero()
        }

        override fun stop(brake: Boolean) = stop()

        override fun debug(): MutableMap<String, Any> = mutableMapOf()

        override fun interpolateVelocity(xRadius: Double, yRadius: Double, theta: Double) = 1.0
    }

    private class TestAlgorithm : Algorithm {
        var rawT = 0.0
        var advanceOnNextUpdate = false
        var holdTarget: PedroPose? = null

        override fun calculatePath(
            drivetrain: Drivetrain,
            pathTracker: PathTracker,
            state: MotionState,
            deltaTime: Double
        ): DrivePowers {
            if(advanceOnNextUpdate) {
                pathTracker.advance()
                advanceOnNextUpdate = false
            }
            return DrivePowers.zero()
        }

        override fun calculateHold(
            drivetrain: Drivetrain,
            target: PedroPose,
            state: MotionState,
            useScaling: Boolean,
            deltaTime: Double
        ): DrivePowers {
            holdTarget = target
            return DrivePowers.zero()
        }

        override fun completion() = rawT

        override fun parametricCompletion() = rawT

        override fun closestPose(): PedroPose = PedroPose.zero()

        override fun closestTangent(): Vector2D = Vector2D.iHat()

        override fun closestNormal(): Vector2D = Vector2D.jHat()

        override fun curvature() = 0.0

        override fun remainingDistance() = 0.0

        override fun atParametricEnd() = false

        override fun reset() = Unit

        override fun isBusy() = true

        override fun debug(): MutableMap<String, Any> = mutableMapOf()
    }

    private data class Fixture(
        val controller: MotionController,
        val follower: Follower,
        val localizer: TestLocalizer,
        val drivetrain: TestDrivetrain,
        val algorithm: TestAlgorithm
    )

    private fun fixture(): Fixture {
        val localizer = TestLocalizer()
        val drivetrain = TestDrivetrain()
        val algorithm = TestAlgorithm()
        val follower = Follower(localizer, drivetrain, algorithm)
        val controller = MotionController(testHardwareScope(), { follower }, disabled = false)
        return Fixture(controller, follower, localizer, drivetrain, algorithm)
    }

    @Test
    fun `setPose updates Pedro and the cached myrelib pose immediately`() {
        val fixture = fixture()
        val pose = Pose(12.0, -8.0, 30.0)

        fixture.controller.setPose(pose)

        assertEquals(pose, fixture.controller.pose)
        assertEquals(84.0, fixture.localizer.pose().x(), 1e-9)
        assertEquals(64.0, fixture.localizer.pose().y(), 1e-9)
        assertEquals(Math.toRadians(60.0), fixture.localizer.pose().heading(), 1e-9)
    }

    @Test
    fun `manual drive converts semantic axes to Pedro powers`() {
        val fixture = fixture()

        fixture.controller.moveRobotCentric(0.4, -0.3, 0.2)
        fixture.controller.tick()

        assertEquals(0.4, fixture.drivetrain.powers.forward(), 1e-9)
        assertEquals(0.3, fixture.drivetrain.powers.strafe(), 1e-9)
        assertEquals(-0.2, fixture.drivetrain.powers.turn(), 1e-9)
    }

    @Test
    fun `field forward remains forward when robot and driver face field up`() {
        val fixture = fixture()
        fixture.controller.setPose(Pose(0.0, 0.0, 0.0))

        fixture.controller.moveFieldCentric(1.0, 0.0, 0.0, 0.0)
        fixture.controller.tick()

        assertEquals(1.0, fixture.drivetrain.powers.forward(), 1e-9)
        assertEquals(0.0, fixture.drivetrain.powers.strafe(), 1e-9)
    }

    @Test
    fun `field forward rotates relative to the configured driver heading`() {
        val fixture = fixture()
        fixture.controller.setPose(Pose(0.0, 0.0, 0.0))

        fixture.controller.moveFieldCentric(1.0, 0.0, 0.0, 90.0)
        fixture.controller.tick()

        assertEquals(0.0, fixture.drivetrain.powers.forward(), 1e-9)
        assertEquals(-1.0, fixture.drivetrain.powers.strafe(), 1e-9)
    }

    @Test
    fun `single curve pathT is Pedro's raw parametric completion`() {
        val fixture = fixture()
        val path = Paths.curve(
            PedroPose(0.0, 0.0),
            PedroPose(4.0, 10.0),
            PedroPose(10.0, 0.0)
        ).constant(0.0)
        fixture.algorithm.rawT = 0.25

        fixture.controller.follow(path)
        fixture.controller.tick()

        assertTrue(fixture.controller.following)
        assertEquals(0.25, fixture.controller.pathT, 1e-9)
    }

    @Test
    fun `compound pathT remains monotonic across Pedro path segments`() {
        val fixture = fixture()
        val first = Paths.line(PedroPose(0.0, 0.0), PedroPose(10.0, 0.0)).constant(0.0)
        val second = Paths.line(PedroPose(10.0, 0.0), PedroPose(40.0, 0.0)).constant(0.0)
        fixture.algorithm.rawT = 0.25
        fixture.algorithm.advanceOnNextUpdate = true

        fixture.controller.follow(Paths.path(first, second))
        fixture.controller.tick()

        assertEquals(1, fixture.follower.pathIndex())
        assertEquals(0.4375, fixture.controller.pathT, 1e-9)
    }

    @Test
    fun `pathT reaches one as soon as Pedro advances past the final segment`() {
        val fixture = fixture()
        val path = Paths.line(PedroPose(0.0, 0.0), PedroPose(10.0, 0.0)).constant(0.0)
        fixture.algorithm.rawT = 0.0
        fixture.algorithm.advanceOnNextUpdate = true

        fixture.controller.follow(path)
        fixture.controller.tick()

        assertTrue(fixture.controller.following)
        assertEquals(1.0, fixture.controller.pathT, 1e-9)
    }

    @Test
    fun `tick caches speed and turn rate from the same update in myrelib units`() {
        val fixture = fixture()
        // Pedro reports counter-clockwise radians; myrelib turns clockwise in degrees
        fixture.localizer.setVelocity(Velocity(3.0, 4.0, Math.toRadians(30.0)))

        assertEquals(0.0, fixture.controller.speed, 0.0)
        fixture.controller.tick()

        assertEquals(5.0, fixture.controller.speed, 1e-9)
        assertEquals(-30.0, fixture.controller.turnRate, 1e-9)
    }

    @Test
    fun `hold delegates a converted pose to Pedro`() {
        val fixture = fixture()

        fixture.controller.hold(Pose(3.0, 4.0, -90.0))
        fixture.controller.tick()

        assertEquals(75.0, fixture.algorithm.holdTarget!!.x(), 1e-9)
        assertEquals(76.0, fixture.algorithm.holdTarget!!.y(), 1e-9)
        assertEquals(Math.PI, fixture.algorithm.holdTarget!!.heading(), 1e-9)
    }

    @Test
    fun `stop leaves follow mode and release immediately stops the drivetrain`() {
        val fixture = fixture()
        val path = Paths.line(PedroPose(0.0, 0.0), PedroPose(1.0, 0.0)).constant(0.0)

        fixture.controller.follow(path)
        assertTrue(fixture.controller.following)
        fixture.controller.stop()
        assertFalse(fixture.controller.following)

        fixture.controller.moveRobotCentric(1.0, 0.0, 0.0)
        fixture.controller.tick()
        fixture.controller.release()

        assertEquals(1, fixture.drivetrain.stopCount)
        assertEquals(0.0, fixture.drivetrain.powers.forward(), 1e-9)
    }
}
