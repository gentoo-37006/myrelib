package gentoo.myrelib.pathing

import gentoo.myrelib.action.Action
import gentoo.myrelib.action.BuiltInActions
import gentoo.myrelib.action.BlockedBehavior
import gentoo.myrelib.action.ActionPriority
import gentoo.myrelib.action.Instant
import gentoo.myrelib.action.InterruptedBehavior
import gentoo.myrelib.action.Sequential
import gentoo.myrelib.action.actionType
import gentoo.myrelib.math.Pose
import gentoo.myrelib.math.assertPoseEquals
import gentoo.myrelib.robot.LifecycleInstance
import gentoo.myrelib.robot.LifecycleRobot
import gentoo.myrelib.util.Alliance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Exercises auton actions through the same process-wide robot used in production. */
class AutonActionTest {
    private val robot = LifecycleRobot
    private fun AutonSequence.tick() { robot.components.tick() }
    private lateinit var instance: LifecycleInstance
    private val actionEvents = mutableListOf<String>()

    private val INTAKE by actionType { recordingAction("INTAKE") }
    private val SHOOTER by actionType { recordingAction("SHOOTER") }

    private fun recordingAction(name: String) = object : Action() {
        override fun start() {
            actionEvents.add("start:$name")
        }

        override fun stop(interrupted: Boolean) {
            actionEvents.add("stop:$name")
        }
    }

    @Before
    fun initializeRobot() {
        actionEvents.clear()
        instance = LifecycleInstance()
        robot.initializeFullRobot(instance)
    }

    @After
    fun resetRobot() {
        robot.reset()
    }

    private fun builder(
        running: Alliance = Alliance.BLUE,
        authored: Alliance = Alliance.BLUE
    ) = AutonSequence.Factory(running).autonSequence(authored)

    private fun path() = Path(Linear(Pose(0.0, 0.0), Pose(10.0, 10.0)))

    @Test
    fun `a path completes safely when the motion controller has no hardware`() {
        val sequence = builder().path(path()).build()
        sequence.start()

        assertEquals("RunAction(FOLLOW)", sequence.currentAction)
        repeat(2) { sequence.tick() }
        assertNull(sequence.currentAction)
    }

    @Test
    fun `the start pose comes from the first path`() {
        val sequence = builder()
            .path(Path(Linear(Pose(5.0, -5.0, 45.0), Pose(10.0, 10.0))))
            .path(path())
            .build()
        assertPoseEquals(Pose(5.0, -5.0, 45.0), sequence.startPose)
    }

    @Test
    fun `path callbacks fire once when a hardware-free path completes`() {
        val sequence = builder()
            .path(path().submitAt(0.25, INTAKE).removeAt(0.75, INTAKE))
            .build()
        sequence.start()

        sequence.tick()
        sequence.tick()

        assertEquals(listOf("start:INTAKE", "stop:INTAKE"), actionEvents)
    }

    @Test
    fun `remaining path callbacks fire when the follower completes`() {
        val sequence = builder().path(path().submitAt(1.0, INTAKE)).build()
        sequence.start()

        sequence.tick()

        assertEquals(listOf("start:INTAKE"), actionEvents)
    }

    @Test
    fun `run waits for the action to finish`() {
        val sequence = builder().run(INTAKE).build()
        sequence.start()
        assertEquals(listOf("start:INTAKE"), actionEvents)

        repeat(3) { sequence.tick() }
        assertEquals("RunAction(INTAKE)", sequence.currentAction)

        INTAKE.remove()
        sequence.tick()
        assertNull(sequence.currentAction)
    }

    @Test
    fun `run passes a timeout through when given one`() {
        val sequence = builder().run(INTAKE, 0.0).build()
        sequence.start()
        assertTrue(INTAKE.isRunning())

        robot.components.tick()
        sequence.tick()

        assertFalse(INTAKE.isRunning())
        assertNull(sequence.currentAction)
    }

    @Test
    fun `run with a repeat count queues one node per repetition`() {
        val sequence = builder().run(INTAKE, repeat = 3).build()
        sequence.start()

        repeat(3) {
            INTAKE.remove()
            sequence.tick()
        }

        assertEquals(3, actionEvents.count { it == "start:INTAKE" })
        assertNull(sequence.currentAction)
    }

    @Test
    fun `start does not wait for the action`() {
        val sequence = builder().submit(INTAKE).wait(0.0).build()
        sequence.start()
        assertTrue(INTAKE.isRunning())

        sequence.tick()

        assertEquals("Wait", sequence.currentAction)
        assertTrue(INTAKE.isRunning())
    }

    @Test
    fun `start passes a timeout through when given one`() {
        builder().submit(INTAKE, 0.0).build().start()
        assertTrue(INTAKE.isRunning())

        robot.components.tick()

        assertFalse(INTAKE.isRunning())
    }

    @Test
    fun `stop stops the action and moves on`() {
        val sequence = builder().submit(INTAKE).remove(INTAKE).build()
        sequence.start()
        sequence.tick()

        assertEquals(listOf("start:INTAKE", "stop:INTAKE"), actionEvents)
        assertFalse(INTAKE.isRunning())
        sequence.tick()
        assertNull(sequence.currentAction)
    }

    @Test
    fun `a mixed sequence runs all declared steps`() {
        val sequence = builder()
            .submit(INTAKE)
            .path(path())
            .run(SHOOTER)
            .remove(INTAKE)
            .build()

        sequence.start()
        sequence.tick()
        sequence.tick()
        sequence.tick()
        SHOOTER.remove()
        sequence.tick()
        sequence.tick()

        assertEquals(
            listOf("start:INTAKE", "start:SHOOTER", "stop:SHOOTER", "stop:INTAKE"),
            actionEvents
        )
        assertNull(sequence.currentAction)
        assertEquals(4, sequence.actionIndex)
    }

    @Test
    fun `bound run steps repeat with fresh instances and preserve parameters`() {
        val values = mutableListOf<Double>()
        val shoot by actionType { power: Double ->
            object : Action() {
                override fun start() { values.add(power) }
            }
        }
        val sequence = builder().run(shoot(0.8), timeoutSeconds = 0.0, repeat = 3).build()
        sequence.start()
        repeat(12) { sequence.tick() }
        assertEquals(listOf(0.8, 0.8, 0.8), values)
        assertFalse(sequence.isRunning)
        assertEquals(3, sequence.actionIndex)
        sequence.start()
        repeat(12) { sequence.tick() }
        assertEquals(6, values.size)
    }

    @Test
    fun `the routine is a controller managed sequential action`() {
        val sequence = builder().wait(30.0).build()
        sequence.start()
        val running = robot.runningActions.values.single()
        assertTrue(running is Sequential)
        assertTrue(running.requires.isEmpty())
        sequence.start()
        assertEquals(1, robot.runningActions.size)
        assertTrue(robot.runningActions.values.single() === running)
        sequence.stop()
        assertFalse(sequence.isRunning)
        assertNull(sequence.currentAction)
    }

    @Test
    fun `a routine waits for queued parameterized work without reserving its hardware`() {
        val resource = Any()
        val blocker by actionType { object : Action() {}.requiring(resource) }
        var built = 0
        val queued by actionType { value: Int ->
            object : Action() {}.also { built += value }.requiring(resource)
                .withPriority(ActionPriority.LOW).withBlockedBehavior(BlockedBehavior.QUEUE)
        }
        blocker.submit()
        val sequence = builder().run(queued(1)).wait(0.0).build()
        sequence.start()
        repeat(3) { sequence.tick() }
        assertTrue(queued.isStaged())
        assertEquals(0, sequence.actionIndex)
        assertEquals(1, built)
        blocker.remove()
        assertTrue(queued.isRunning())
        queued.remove()
        sequence.tick()
        assertEquals(1, sequence.actionIndex)
    }

    @Test
    fun `suspended parameterized work holds its sequence until the same instance finishes`() {
        val resource = Any()
        var built = 0
        val action by actionType { value: Int ->
            object : Action() {}.also { built += value }.requiring(resource)
                .withInterruptedBehavior(InterruptedBehavior.SUSPEND)
        }
        val urgent by actionType { object : Action() {}.requiring(resource).withPriority(ActionPriority.HIGH) }
        val sequence = builder().run(action(1)).submit(SHOOTER).build()
        sequence.start()
        val initial = robot.runningActions[action]
        urgent.submit()
        repeat(3) { sequence.tick() }
        assertTrue(action.isStaged())
        assertEquals(0, sequence.actionIndex)
        urgent.remove()
        assertTrue(initial === robot.runningActions[action])
        assertEquals(1, built)
        action.remove(); sequence.tick()
        assertEquals(1, sequence.actionIndex)
        assertTrue(SHOOTER.isRunning())
    }

    @Test
    fun `reset cancels the routine and its awaited action without advancing`() {
        val sequence = builder().run(INTAKE).submit(SHOOTER).build()
        sequence.start()
        robot.reset()
        assertNull(sequence.currentAction)
        assertEquals(0, sequence.actionIndex)
        assertEquals(listOf("start:INTAKE", "stop:INTAKE"), actionEvents)
    }

    @Test
    fun `stopping the routine discards queued work`() {
        val resource = Any()
        val blocker by actionType { object : Action() {}.requiring(resource) }
        val queued by actionType { value: Int ->
            object : Action() {}.requiring(resource).withPriority(ActionPriority.LOW)
                .withBlockedBehavior(BlockedBehavior.QUEUE)
        }
        blocker.submit()
        val sequence = builder().run(queued(1)).submit(SHOOTER).build()
        sequence.start()
        assertTrue(queued.isStaged())
        sequence.stop()
        assertFalse(queued.isStaged())
        blocker.remove()
        repeat(3) { sequence.tick() }
        assertFalse(queued.isRunning())
        assertTrue(actionEvents.isEmpty())
    }

    @Test
    fun `a path callback can cancel its routine without firing later callbacks`() {
        lateinit var sequence: AutonSequence
        val cancel by actionType { Instant { sequence.stop() } }
        val late by actionType { value: Int -> Instant { actionEvents.add("late:$value") } }
        sequence = builder().path(path().submitAt(0.0, cancel).submitAt(0.0, late(1)))
            .submit(SHOOTER).build()
        sequence.start()
        repeat(3) { sequence.tick() }
        assertFalse(sequence.isRunning)
        assertFalse(BuiltInActions.FOLLOW.isRunning())
        assertEquals(0, sequence.actionIndex)
        assertTrue(actionEvents.isEmpty())
    }

    @Test
    fun `mirrored path callbacks retain parameters and are rebuilt per run`() {
        val values = mutableListOf<Int>()
        val record by actionType { value: Int -> Instant { values.add(value) } }
        val sequence = builder(Alliance.RED, Alliance.BLUE)
            .path(path().submitAt(0.0, record(1)).removeAt(0.25, record)
                .submitAt(1.0, record(2))).build()
        repeat(2) {
            sequence.start()
            repeat(5) { sequence.tick() }
        }
        assertEquals(listOf(1, 2, 1, 2), values)
    }
}
