package org.firstinspires.ftc.teamcode

import com.qualcomm.robotcore.eventloop.opmode.Autonomous
import com.qualcomm.robotcore.hardware.HardwareMap
import gentoo.myrelib.hardware.HardwareAccess
import gentoo.myrelib.config.ComponentConfig
import gentoo.myrelib.input.InputBindings
import gentoo.myrelib.math.Pose
import gentoo.myrelib.util.Alliance
import org.firstinspires.ftc.teamcode.action.ClawActions
import org.firstinspires.ftc.teamcode.component.Claw
import org.firstinspires.ftc.teamcode.opmode.generated.Blue_SmokeAuton
import org.firstinspires.ftc.teamcode.opmode.generated.Red_SmokeAuton
import org.firstinspires.ftc.teamcode.opmode.routines.SmokeAuton
import org.firstinspires.ftc.teamcode.opmode.routines.smokeSequence
import org.firstinspires.ftc.teamcode.robot.Robot
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Uses only the public library API, compiled from a separate consuming module. */
class ConsumerContractTest {
    @Before fun initialize() {
        Robot.reset()
        Robot.initForTest()
        Robot.claw.close()
        Robot.reset()
    }

    @After fun cleanup() = Robot.reset()

    @Test
    fun `input edges schedule and cancel a consumer action through the component loop`() {
        val hardware = FakeHardware()
        Robot.initForTest(hardware = hardware.access)
        var pressed = false
        val inputs = InputBindings()
        inputs.trigger { pressed }.onChange(ClawActions.HOLD_OPEN)

        pressed = true
        inputs.poll()
        Robot.components.tick()
        assertEquals(Claw.State.OPEN, Robot.claw.state)
        assertEquals(listOf(Claw.OPEN_POSITION), hardware.positions)
        val first = Robot.runningActions.getValue(ClawActions.HOLD_OPEN)

        pressed = false
        inputs.poll()
        Robot.components.tick()
        assertEquals(Claw.State.CLOSED, Robot.claw.state)
        assertEquals(Claw.CLOSED_POSITION, hardware.positions.last(), 0.0)
        assertTrue(Robot.runningActions.isEmpty())

        pressed = true
        inputs.poll()
        assertNotSame(first, Robot.runningActions.getValue(ClawActions.HOLD_OPEN))
    }

    @Test
    fun `conflicting consumer action cancels the previous owner's work`() {
        Robot.initForTest()
        ClawActions.HOLD_OPEN.submit()
        assertEquals(Claw.State.OPEN, Robot.claw.state)

        ClawActions.SET_CLAW(Claw.State.CLOSED).submit()
        Robot.components.tick()

        assertEquals(Claw.State.CLOSED, Robot.claw.state)
        assertTrue(Robot.runningActions.isEmpty())
    }

    @Test
    fun `disabled consumer component tolerates absent hardware and scheduled calls`() {
        val onlyActions = object : ComponentConfig(disabledByDefault = true) {
            override fun componentBindings() = bind(Robot::actions to false)
        }
        // An accidental lookup of testClaw on this available scope would throw.
        Robot.initForTest(
            hardware = HardwareAccess.from(object : HardwareMap(null, null) {
                override fun <T : Any?> get(classOrInterface: Class<out T>, deviceName: String): T {
                    error("Disabled component queried $deviceName")
                }
            }),
            componentConfig = onlyActions
        )

        ClawActions.HOLD_OPEN.submit()
        Robot.components.tick()
        ClawActions.HOLD_OPEN.remove()
        Robot.claw.open()
        Robot.claw.tick()

        assertTrue(Robot.claw.disabled)
        assertEquals(Claw.State.CLOSED, Robot.claw.state)
        Robot.reset()
        assertFalse(Robot.isInited)
    }

    @Test
    fun `reset cancels consumer actions and reattaches the same components`() {
        val hardware = FakeHardware()
        Robot.initForTest(hardware = hardware.access)
        val oldClaw = Robot.claw
        ClawActions.HOLD_OPEN.submit()
        Robot.components.tick()

        Robot.reset()

        assertEquals(Claw.State.CLOSED, oldClaw.state)
        assertEquals(Claw.CLOSED_POSITION, hardware.positions.last(), 0.0)
        assertFalse(Robot.isInited)

        Robot.initForTest()
        assertSame(oldClaw, Robot.claw)
        assertTrue(Robot.runningActions.isEmpty())
        assertEquals(Claw.State.CLOSED, Robot.claw.state)
    }

    @Test
    fun `ordinary component state persists across opmodes without a persistence field`() {
        val autonHardware = FakeHardware()
        Robot.initForTest(hardware = autonHardware.access)
        val claw = Robot.claw
        claw.open()
        Robot.components.tick()
        Robot.reset()
        assertEquals(Claw.State.OPEN, claw.state)
        assertTrue(claw.disabled)

        val teleopHardware = FakeHardware()
        Robot.initForTest(hardware = teleopHardware.access)
        assertSame(claw, Robot.claw)
        assertEquals(Claw.State.OPEN, Robot.claw.state)
        assertTrue(teleopHardware.positions.isEmpty())
        Robot.components.tick()
        assertEquals(listOf(Claw.OPEN_POSITION), teleopHardware.positions)
    }

    @Test
    fun `disabled consumer configuration uses normal initialization and rejects action submissions`() {
        Robot.initForTest(componentConfig = ComponentConfig.disableAll())
        ClawActions.HOLD_OPEN.submit()
        Robot.claw.open()
        Robot.components.tick()

        assertTrue(Robot.isInited)
        assertTrue(Robot.motion.disabled)
        assertTrue(Robot.claw.disabled)
        assertEquals(Claw.State.CLOSED, Robot.claw.state)
        assertTrue(Robot.runningActions.isEmpty())
    }

    @Test
    fun `consumer auton mirrors alliance geometry and completes through normal scheduling`() {
        for(alliance in Alliance.entries) {
            Robot.initForTest(alliance)
            val sequence = smokeSequence(alliance)
            val blueStart = Pose(-24.0, -24.0, 0.0)
            assertEquals(if(alliance == Alliance.BLUE) blueStart else blueStart.invert(), sequence.startPose)

            sequence.start()
            repeat(30) { Robot.components.tick() }

            assertFalse(sequence.isRunning)
            assertEquals(3, sequence.actionIndex)
            assertEquals(Claw.State.OPEN, Robot.claw.state)
            assertTrue(Robot.runningActions.isEmpty())
        }
    }

    @Test
    fun `real KSP consumer generates only direct subclass wrappers in the base package`() {
        val wrappers = listOf(Blue_SmokeAuton::class.java, Red_SmokeAuton::class.java)
        for((wrapper, group) in wrappers.zip(listOf("Blue", "Red"))) {
            assertEquals(SmokeAuton::class.java, wrapper.superclass)
            assertNotNull(wrapper.getDeclaredConstructor())
            val annotation = requireNotNull(wrapper.getAnnotation(Autonomous::class.java))
            assertEquals(group, annotation.group)
            assertEquals(group, annotation.preselectTeleOp)
        }

        for(prefix in listOf("Blue", "Red")) {
            for(name in listOf("TestAuton", "IndirectSmokeAuton")) {
                assertThrows(ClassNotFoundException::class.java) {
                    Class.forName("org.firstinspires.ftc.teamcode.opmode.generated.${prefix}_$name")
                }
            }
        }
    }
}
