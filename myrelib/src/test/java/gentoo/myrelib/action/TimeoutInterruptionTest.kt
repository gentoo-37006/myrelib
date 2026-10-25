package gentoo.myrelib.action

import com.pedropathing.algorithm.Algorithm
import com.pedropathing.drivetrain.DrivePowers
import com.pedropathing.drivetrain.Drivetrain
import com.pedropathing.follower.Follower
import com.pedropathing.localization.Localizer
import com.pedropathing.localization.MotionState
import com.pedropathing.math.Pose as PedroPose
import com.pedropathing.math.Vector2D
import com.pedropathing.paths.PathTracker
import gentoo.myrelib.hardware.testHardwareScope
import gentoo.myrelib.robot.LifecycleInstance
import gentoo.myrelib.math.Pose
import gentoo.myrelib.pathing.AutonSequence
import gentoo.myrelib.pathing.Linear
import gentoo.myrelib.pathing.Path
import gentoo.myrelib.robot.LifecycleRobot
import gentoo.myrelib.util.Alliance
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TimeoutInterruptionTest {
    private val robot = LifecycleRobot
    @Before fun initialize() { robot.reset(); robot.initializeFullRobot() }
    @After fun cleanup() { robot.reset() }

    private class LocalizerStub : Localizer {
        override fun setPose(pose: PedroPose) = Unit
        override fun state() = MotionState.zero()
        override fun update() = Unit
        override fun reset() = Unit
    }
    private class DriveStub : Drivetrain {
        override fun drive(powers: DrivePowers, manual: Boolean) = Unit
        override fun maxScaling(current: DrivePowers, delta: DrivePowers) = 1.0
        override fun stop() = Unit
        override fun stop(brake: Boolean) = Unit
        override fun debug() = mutableMapOf<String, Any>()
        override fun interpolateVelocity(xRadius: Double, yRadius: Double, theta: Double) = 1.0
    }
    private class AlgorithmStub : Algorithm {
        override fun calculatePath(drivetrain: Drivetrain, pathTracker: PathTracker, state: MotionState, deltaTime: Double) = DrivePowers.zero()
        override fun calculateHold(drivetrain: Drivetrain, target: PedroPose, state: MotionState, useScaling: Boolean, deltaTime: Double) = DrivePowers.zero()
        override fun completion() = 0.0
        override fun parametricCompletion() = 0.0
        override fun closestPose() = PedroPose.zero()
        override fun closestTangent() = Vector2D.iHat()
        override fun closestNormal() = Vector2D.jHat()
        override fun curvature() = 0.0
        override fun remainingDistance() = 0.0
        override fun atParametricEnd() = false
        override fun reset() = Unit
        override fun isBusy() = true
        override fun debug() = mutableMapOf<String, Any>()
    }

    @Test fun `auton follow timeout must stop the follower`() {
        val follower = Follower(LocalizerStub(), DriveStub(), AlgorithmStub())
        robot.initializeFullRobot(LifecycleInstance(
            hardwareAccess = testHardwareScope(), followerFactory = { follower },
        ))
        val path = Path(Linear(Pose(), Pose(10.0, 0.0)))
        val sequence = AutonSequence.Factory(Alliance.BLUE).autonSequence(Alliance.BLUE)
            .run(BuiltInActions.FOLLOW(path), timeoutSeconds = 0.0).wait(30.0).build()
        sequence.start()
        assertTrue(robot.motion.following)
        robot.components.tick()
        assertFalse(BuiltInActions.FOLLOW.isRunning())
        assertFalse("Timed out Follow released its requirement but left Pedro following", robot.motion.following)
    }

    @Test fun `coordinator timeout must cancel its submitted work`() {
        val work by actionType { object : Action() {} }
        val coordinator by actionType { RunAction(work()) }
        coordinator.submit(timeoutSeconds = 0.0)
        robot.components.tick()
        assertFalse(coordinator.isRunning())
        assertFalse("Timed out RunAction left its submitted action running", work.isRunning())
    }

    @Test fun `coordinator timeout also discards queued work`() {
        val resource = Any()
        val blocker by actionType { object : Action() {}.requiring(resource) }
        val work by actionType {
            object : Action() {}.requiring(resource).withPriority(ActionPriority.LOW)
                .withBlockedBehavior(BlockedBehavior.QUEUE)
        }
        val coordinator by actionType { RunAction(work()) }
        blocker.submit()
        coordinator.submit(timeoutSeconds = 0.0)
        assertTrue(work.isStaged())
        robot.components.tick()
        assertFalse(coordinator.isRunning())
        assertFalse(work.isStaged())
        blocker.remove()
        robot.components.tick()
        assertFalse(work.isRunning())
    }

    @Test fun `expired actions stop once without ticking or suspending`() {
        lateinit var instance: FakeAction
        val work by actionType {
            FakeAction(ticksToRun = 100).also { instance = it }
                .withInterruptedBehavior(InterruptedBehavior.SUSPEND)
        }
        work.submit(timeoutSeconds = 0.0)
        repeat(3) { robot.components.tick() }
        assertEquals(true, instance.interrupted)
        assertEquals(0, instance.ticks)
        assertEquals(1, instance.events.count { it.endsWith(":stop") })
        assertFalse(work.isRunning())
        assertFalse(work.isStaged())
    }

    @Test fun `natural completion wins over a simultaneous timeout`() {
        val instance = FakeAction(ticksToRun = 0)
        instance.timeout(0.0)
        instance.doStart()
        assertTrue(instance.tickOrStop().stopped())
        assertEquals(false, instance.interrupted)
    }

    @Test fun `group timeouts interrupt every active child`() {
        val factories: List<(FakeAction, FakeAction) -> Action> = listOf(
            { a, b -> Parallel(a, b) },
            { a, b -> Race(a, b) },
            { a, b -> Deadline(a, b) },
        )
        for(factory in factories) {
            val first = FakeAction(ticksToRun = 100)
            val second = FakeAction(ticksToRun = 100)
            val group = factory(first, second).timeout(0.0)
            group.doStart()
            assertTrue(group.tickOrStop().stopped())
            assertEquals(true, first.interrupted)
            assertEquals(true, second.interrupted)
            assertEquals(0, first.ticks)
            assertEquals(0, second.ticks)
            assertTrue(group.tickOrStop().stopped())
            assertEquals(1, first.events.count { it.endsWith(":stop") })
            assertEquals(1, second.events.count { it.endsWith(":stop") })
        }
    }

    @Test fun `sequential timeout interrupts the active child but leaves pending children alone`() {
        val first = FakeAction(ticksToRun = 100)
        val pending = FakeAction(ticksToRun = 100)
        val group = Sequential(first, pending).timeout(0.0)
        group.doStart()
        assertTrue(group.tickOrStop().stopped())
        assertEquals(true, first.interrupted)
        assertTrue(pending.events.isEmpty())
    }

    @Test fun `loop and repeat timeouts do not construct another child`() {
        for(loop in listOf(false, true)) {
            val children = mutableListOf<FakeAction>()
            val factory = java.util.function.Supplier<ActionInstance> {
                FakeAction(ticksToRun = 100).also { children.add(it) }
            }
            val group: Action = if(loop) Loop(factory) else Repeat(3, factory)
            group.timeout(0.0).doStart()
            assertTrue(group.tickOrStop().stopped())
            assertEquals(1, children.size)
            assertEquals(true, children.single().interrupted)
            assertEquals(0, children.single().ticks)
        }
    }
}
