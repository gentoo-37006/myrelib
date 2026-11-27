package org.firstinspires.ftc.teamcode

import android.content.ContextWrapper
import com.qualcomm.robotcore.eventloop.opmode.Autonomous
import com.qualcomm.robotcore.eventloop.opmode.OpMode
import com.qualcomm.robotcore.hardware.Gamepad
import com.qualcomm.robotcore.hardware.HardwareMap
import gentoo.myrelib.hardware.HardwareAccess
import gentoo.myrelib.component.ComponentConfig
import gentoo.myrelib.input.GamepadTriggers
import gentoo.myrelib.math.Pose
import gentoo.myrelib.opmode.Teleop
import gentoo.myrelib.util.Alliance
import org.firstinspires.ftc.teamcode.action.ClawActions
import org.firstinspires.ftc.teamcode.component.Claw
import org.firstinspires.ftc.teamcode.opmode.routines.Blue_FrameworkSmokeAuton
import org.firstinspires.ftc.teamcode.opmode.routines.Blue_SmokeAuton
import org.firstinspires.ftc.teamcode.opmode.routines.FrameworkSmokeAuton
import org.firstinspires.ftc.teamcode.opmode.routines.Red_FrameworkSmokeAuton
import org.firstinspires.ftc.teamcode.opmode.routines.Red_SmokeAuton
import org.firstinspires.ftc.teamcode.opmode.routines.SmokeAuton
import org.firstinspires.ftc.teamcode.opmode.routines.smokeSequence
import org.firstinspires.ftc.teamcode.robot.Robot
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Uses only the public library API, compiled from a separate consuming module. */
class ConsumerContractTest {
    @get:Rule val files = TemporaryFolder()

    private fun initializeRobot(
        alliance: Alliance = Alliance.BLUE,
        hardware: HardwareAccess = HardwareAccess.empty(),
        componentConfig: ComponentConfig = Robot.config,
    ) {
        val opMode = object : OpMode() {
            override fun init() = Unit
            override fun loop() = Unit
        }
        Robot.startOpModeForTest(opMode, alliance, hardware, componentConfig)
        assertSame(opMode, Robot.session.opMode)
    }

    @Before fun initialize() {
        Robot.reset()
        initializeRobot()
        Robot.claw.close()
        Robot.reset()
    }

    @After fun cleanup() = Robot.reset()

    private fun appContext() = object : ContextWrapper(null) {
        override fun getApplicationContext() = this
        override fun getFilesDir() = files.root
    }

    @Test
    fun `teleop inputs drive the component loop and clear on stop`() {
        val hardware = FakeHardware(appContext())
        var operatorPresses = 0
        val opMode = object : Teleop(Alliance.BLUE) {
            override fun driverBindings(gamepad: GamepadTriggers) {
                gamepad.a.onChange(ClawActions.HOLD_OPEN)
            }

            override fun operatorBindings(gamepad: GamepadTriggers) {
                gamepad.b.onTrue { operatorPresses++ }
            }

            fun pollInputs() = inputs.poll()
        }
        opMode.hardwareMap = hardware.map
        opMode.gamepad1 = Gamepad()
        opMode.gamepad2 = Gamepad()
        opMode.init()
        assertSame(opMode, Robot.session.opMode)

        opMode.gamepad1.a = true
        opMode.init_loop()
        assertEquals(Claw.State.CLOSED, Robot.claw.state)

        opMode.start()
        opMode.gamepad2.b = true
        opMode.loop()
        assertEquals(Claw.State.OPEN, Robot.claw.state)
        assertEquals(Claw.OPEN_POSITION, hardware.positions.last(), 0.0)
        assertEquals(1, operatorPresses)
        val first = Robot.runningActions.getValue(ClawActions.HOLD_OPEN)

        opMode.gamepad1.a = false
        opMode.gamepad2.b = false
        opMode.loop()
        assertEquals(Claw.State.CLOSED, Robot.claw.state)
        assertEquals(Claw.CLOSED_POSITION, hardware.positions.last(), 0.0)
        assertTrue(Robot.runningActions.isEmpty())

        opMode.gamepad1.a = true
        opMode.loop()
        assertNotSame(first, Robot.runningActions.getValue(ClawActions.HOLD_OPEN))

        opMode.stop()
        opMode.gamepad2.b = true
        opMode.pollInputs()
        assertEquals(1, operatorPresses)
        assertFalse(Robot.isInited)
        assertEquals(Claw.State.CLOSED, Robot.claw.state)
    }

    @Test
    fun `teleop cleanup preserves an attachment failure before inputs exist`() {
        val failure = IllegalStateException("Test hardware lookup failed")
        val opMode = object : Teleop(Alliance.BLUE) {
            override fun driverBindings(gamepad: GamepadTriggers) = Unit
            override fun operatorBindings(gamepad: GamepadTriggers) = Unit
        }
        opMode.hardwareMap = object : HardwareMap(appContext(), null) {
            override fun <T : Any?> getAll(type: Class<out T>): MutableList<T> = mutableListOf()
            override fun <T : Any?> get(type: Class<out T>, deviceName: String): T = throw failure
        }

        assertSame(failure, assertThrows(IllegalStateException::class.java) { opMode.init() })
        assertTrue(failure.suppressed.isEmpty())
        assertFalse(Robot.isInited)
        opMode.stop()
    }

    @Test
    fun `conflicting consumer action cancels the previous owner's work`() {
        initializeRobot()
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
        initializeRobot(
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
        initializeRobot(hardware = hardware.access)
        val oldClaw = Robot.claw
        ClawActions.HOLD_OPEN.submit()
        Robot.components.tick()

        Robot.reset()

        assertEquals(Claw.State.CLOSED, oldClaw.state)
        assertEquals(Claw.CLOSED_POSITION, hardware.positions.last(), 0.0)
        assertFalse(Robot.isInited)

        initializeRobot()
        assertSame(oldClaw, Robot.claw)
        assertTrue(Robot.runningActions.isEmpty())
        assertEquals(Claw.State.CLOSED, Robot.claw.state)
    }

    @Test
    fun `ordinary component state persists across opmodes without a persistence field`() {
        val autonHardware = FakeHardware()
        initializeRobot(hardware = autonHardware.access)
        val claw = Robot.claw
        claw.open()
        Robot.components.tick()
        Robot.reset()
        assertEquals(Claw.State.OPEN, claw.state)
        assertTrue(claw.disabled)

        val teleopHardware = FakeHardware()
        initializeRobot(hardware = teleopHardware.access)
        assertSame(claw, Robot.claw)
        assertEquals(Claw.State.OPEN, Robot.claw.state)
        assertTrue(teleopHardware.positions.isEmpty())
        Robot.components.tick()
        assertEquals(listOf(Claw.OPEN_POSITION), teleopHardware.positions)
    }

    @Test
    fun `disabled consumer configuration uses normal initialization and rejects action submissions`() {
        initializeRobot(componentConfig = ComponentConfig.disableAll())
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
            initializeRobot(alliance)
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
    fun `real KSP consumer generates only direct subclass wrappers in the subclass package`() {
        val autons = mapOf(
            SmokeAuton::class.java to listOf(Blue_SmokeAuton::class.java, Red_SmokeAuton::class.java),
            FrameworkSmokeAuton::class.java to listOf(Blue_FrameworkSmokeAuton::class.java, Red_FrameworkSmokeAuton::class.java),
        )
        for((auton, wrappers) in autons) {
            for((wrapper, group) in wrappers.zip(listOf("Blue", "Red"))) {
                assertEquals(auton, wrapper.superclass)
                assertEquals(auton.packageName, wrapper.packageName)
                assertNotNull(wrapper.getDeclaredConstructor())
                val annotation = requireNotNull(wrapper.getAnnotation(Autonomous::class.java))
                assertEquals(group, annotation.group)
                assertEquals(group, annotation.preselectTeleOp)
            }
        }

        for(prefix in listOf("Blue", "Red")) {
            for(name in listOf(
                "gentoo.myrelib.opmode.${prefix}_Auton",
                "org.firstinspires.ftc.teamcode.opmode.${prefix}_TestAuton",
                "org.firstinspires.ftc.teamcode.opmode.routines.${prefix}_IndirectSmokeAuton",
            )) {
                assertThrows(ClassNotFoundException::class.java) {
                    Class.forName(name)
                }
            }
        }
    }
}
