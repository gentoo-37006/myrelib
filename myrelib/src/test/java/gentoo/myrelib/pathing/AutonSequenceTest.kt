package gentoo.myrelib.pathing

import gentoo.myrelib.math.Pose
import gentoo.myrelib.util.Alliance
import gentoo.myrelib.robot.LifecycleRobot
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises the sequential action through the normal component/controller tick.
 */
class AutonSequenceTest {
    private val robot = LifecycleRobot
    @Before fun initialize() { robot.reset(); robot.initializeFullRobot() }
    @After fun cleanup() { robot.reset() }
    private fun AutonSequence.tick() { robot.components.tick() }
    private fun builder(
        runningAlliance: Alliance = Alliance.BLUE,
        authoredAlliance: Alliance = runningAlliance
    ) = AutonSequence.Factory(runningAlliance).autonSequence(authoredAlliance)

    /** ticks until the sequence reports it is finished, or gives up */
    private fun AutonSequence.drain(maxTicks: Int = 10_000): Int {
        var ticks = 0
        while(isRunning && ticks < maxTicks) {
            tick()
            ticks++
        }
        return ticks
    }

    @Test
    fun `an empty sequence is finished before it starts`() {
        val sequence = builder().build()
        sequence.start()

        assertNull(sequence.currentAction)
        assertEquals(0, sequence.actionIndex)
        // ticking a finished sequence is harmless
        sequence.tick()
        assertEquals(0, sequence.actionIndex)
    }

    @Test
    fun `a sequence with no path starts at the origin`() {
        val sequence = builder().wait(0.0).build()
        assertEquals(Pose(), sequence.startPose)
    }

    @Test
    fun `matching alliances keep the authored start pose`() {
        val start = Pose(-60.0, -58.0, 15.0)
        val sequence = builder(Alliance.BLUE, Alliance.BLUE)
            .path(Path(Linear(start, Pose(-40.0, -20.0))))
            .build()

        assertEquals(start, sequence.startPose)
    }

    @Test
    fun `opposite alliances invert the start pose through the field center`() {
        val sequence = builder(Alliance.RED, Alliance.BLUE)
            .path(Path(Linear(Pose(-60.0, -58.0, 15.0), Pose(-40.0, -20.0))))
            .build()

        assertEquals(Pose(60.0, 58.0, -165.0), sequence.startPose)
    }

    @Test
    fun `the current node is named while it runs`() {
        val sequence = builder().wait(30.0).build()
        sequence.start()

        assertEquals("Wait", sequence.currentAction)
    }

    @Test
    fun `a zero length wait clears on the first tick`() {
        val sequence = builder().wait(0.0).build()
        sequence.start()

        sequence.tick()

        assertNull(sequence.currentAction)
        assertEquals(1, sequence.actionIndex)
    }

    @Test
    fun `actionIndex counts the nodes that have finished`() {
        val sequence = builder()
            .wait(0.0)
            .wait(0.0)
            .wait(0.0)
            .build()
        sequence.start()

        assertEquals(0, sequence.actionIndex)
        sequence.tick()
        assertEquals(1, sequence.actionIndex)
        sequence.tick()
        assertEquals(2, sequence.actionIndex)
        sequence.tick()
        assertEquals(3, sequence.actionIndex)
        assertNull(sequence.currentAction)
    }

    @Test
    fun `nodes run one at a time and in order`() {
        val sequence = builder()
            .wait(0.0)
            .waitUntil(0.0)
            .wait(0.0)
            .build()
        sequence.start()

        assertEquals("Wait", sequence.currentAction)
        sequence.tick()
        assertEquals("WaitUntil", sequence.currentAction)
        sequence.tick()
        assertEquals("Wait", sequence.currentAction)
        sequence.tick()
        assertNull(sequence.currentAction)
    }

    @Test
    fun `a wait holds the sequence while it is running`() {
        // long enough that no stall can outrun it. asserting that a short wait
        // has *not* elapsed yet races against the machine and fails for reasons
        // that have nothing to do with the code
        val sequence = builder().wait(30.0).build()
        sequence.start()

        repeat(5) { sequence.tick() }

        assertEquals("Wait", sequence.currentAction)
    }

    @Test
    fun `a wait clears once its duration has passed`() {
        val sequence = builder().wait(0.05).build()
        sequence.start()

        // overshooting only makes this more true, so load cannot break it
        Thread.sleep(200)
        sequence.tick()

        assertNull(sequence.currentAction)
    }

    @Test
    fun `waitUntil measures from the start of the sequence, not the node`() {
        val sequence = builder()
            .wait(0.1)
            .waitUntil(0.05)
            .build()
        sequence.start()

        // the wait alone outlasts the waitUntil deadline, so once it clears the
        // waitUntil is already in the past and should not hold things up
        Thread.sleep(150)
        sequence.tick()
        assertEquals("WaitUntil", sequence.currentAction)

        sequence.tick()
        assertNull(sequence.currentAction)
    }

    @Test
    fun `a finished sequence stays finished`() {
        val sequence = builder().wait(0.0).build()
        sequence.start()
        sequence.drain()

        assertNull(sequence.currentAction)
        val settled = sequence.actionIndex
        repeat(5) { sequence.tick() }
        assertEquals(settled, sequence.actionIndex)
    }

    @Test
    fun `restarting constructs a fresh action tree`() {
        val sequence = builder().wait(0.0).wait(0.0).build()
        sequence.start()
        sequence.drain()
        assertEquals(2, sequence.actionIndex)

        sequence.start()
        assertEquals("Wait", sequence.currentAction)
        assertEquals(0, sequence.actionIndex)
        sequence.drain()
        assertEquals(2, sequence.actionIndex)
    }

    @Test
    fun `duplicate start preserves the elapsed clock and current step`() {
        val sequence = builder().wait(30.0).build()
        sequence.start()
        val before = sequence.opmodeTimer.seconds()
        sequence.start()
        assertEquals("Wait", sequence.currentAction)
        org.junit.Assert.assertTrue(sequence.opmodeTimer.seconds() >= before)
        assertEquals(0, sequence.actionIndex)
    }
}
