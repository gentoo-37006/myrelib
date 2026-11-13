package gentoo.myrelib.robot

import com.pedropathing.follower.Follower
import com.qualcomm.robotcore.hardware.HardwareMap
import gentoo.myrelib.action.Action
import gentoo.myrelib.action.actionType
import gentoo.myrelib.component.Component
import gentoo.myrelib.config.ComponentConfig
import gentoo.myrelib.component.HardwareAccess
import gentoo.myrelib.math.Pose
import gentoo.myrelib.pathing.Linear
import gentoo.myrelib.pathing.Path
import gentoo.myrelib.util.Alliance
import gentoo.myrelib.field.FieldPoseBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal class LifecycleComponent(
    val suppliedHardware: HardwareAccess,
    disabled: Boolean = false
) : Component(suppliedHardware, disabled) {
    var ticks = 0
    var releases = 0
    var releaseFailure: Throwable? = null

    override fun tick() {
        ticks++
    }

    override fun release() {
        releases++
        releaseFailure?.let { throw it }
    }
}

private class LifecycleComponentConfig(
    private val disabledComponents: Set<String>
) : ComponentConfig() {
    override fun componentBindings() = disabledComponents.associateWith { true }
}

private val lifecycleFollowerFactory: (HardwareMap) -> Follower = {
    error("a hardware-free test instance must not construct a follower")
}

internal open class LifecycleInstance(
    hardwareAccess: HardwareAccess = HardwareAccess.empty(),
    config: ComponentConfig = ComponentConfig.enableAll(),
    alliance: Alliance? = null,
    followerFactory: FollowerFactory = lifecycleFollowerFactory,
) : BaseRobotInstance(
    Context(
        hardware = hardwareAccess,
        config = config,
        followerFactory = followerFactory,
        alliance = alliance,
    ),
) {
    val component by register { hardware, disabled ->
        LifecycleComponent(hardware, disabled)
    }
}

private class ConfiguredLifecycleInstance(
    hardwareAccess: HardwareAccess,
    config: ComponentConfig,
) : BaseRobotInstance(Context(hardwareAccess, config, lifecycleFollowerFactory)) {
    val component by register { hardware, disabled ->
        LifecycleComponent(hardware, disabled)
    }
}

internal object LifecycleRobot : BaseRobot<LifecycleInstance>() {
    override val LENGTH = 16.0
    override val WIDTH = 16.0
    override val config: ComponentConfig = ComponentConfig.enableAll()
    override val followerFactory = lifecycleFollowerFactory
    override val robotFactory: RobotFactory<LifecycleInstance> =
        { context -> LifecycleInstance(context.hardware, context.config, context.alliance) }

    fun initializeFullRobot(
        instance: LifecycleInstance? = null,
        alliance: Alliance? = null,
    ) {
        init(alliance) { capturedAlliance ->
            instance ?: LifecycleInstance(alliance = capturedAlliance)
        }
    }

    fun failFullInitialization(alliance: Alliance) {
        init(alliance) { throw IllegalStateException("Runtime construction failed") }
    }

    fun initializeEmptyRobot() {
        initEmpty()
    }

    val seasonInstance: LifecycleInstance get() = instance
}


/**
 * Exists only to prove the singleton guard still rejects a genuine duplicate.
 * Its initialization is expected to fail, so nothing else may touch it.
 */
internal object DuplicateRobot : BaseRobot<LifecycleInstance>() {
    override val LENGTH = 16.0
    override val WIDTH = 16.0
    override val config: ComponentConfig = ComponentConfig.enableAll()
    override val followerFactory = lifecycleFollowerFactory
    override val robotFactory: RobotFactory<LifecycleInstance> =
        { context -> LifecycleInstance(context.hardware, context.config, context.alliance) }
}

class BaseRobotTest {
    // BaseRobot.INSTANCE is process-wide because FTC has one season Robot singleton.
    // Keep exactly one robot fixture per test and clean up both sides of the test.
    private val robot = LifecycleRobot

    @Before
    fun clearRobotState() {
        robot.reset()
    }

    @After
    fun resetRobotState() {
        robot.reset()
    }

    @Test
    fun `half dimensions are derived after subclass initialization`() {
        assertEquals(8.0, robot.HALF_LENGTH, 0.0)
        assertEquals(8.0, robot.HALF_WIDTH, 0.0)
    }

    @Test
    fun `framework lifecycle owns viewer sessions including failed initialization`() {
        robot.initEmpty("Debug Viewer")
        val initial = FieldPoseBridge.snapshot()
        assertTrue(initial.active)
        assertEquals("Debug Viewer", initial.opMode)
        assertEquals(robot.WIDTH, initial.robot!!.width, 0.0)
        assertNull(initial.pose)
        robot.reset()
        assertFalse(FieldPoseBridge.snapshot().active)

        assertThrows(IllegalStateException::class.java) {
            robot.failFullInitialization(Alliance.BLUE)
        }
        assertFalse(FieldPoseBridge.snapshot().active)
    }


    // The guard tolerates a sloth hot load replacing the season Robot from a new
    // class loader, so this locks in that it still rejects a real duplicate, which
    // in a JVM test shares this test's loader. The cross-loader replacement path
    // only exists on the robot and has no JVM seam.
    @Test
    fun `a second robot object in the same class loader is rejected`() {
        assertSame(LifecycleRobot, BaseRobot.INSTANCE)

        val error = assertThrows(ExceptionInInitializerError::class.java) { DuplicateRobot.isInited }
        assertTrue(error.cause is IllegalStateException)

        // the rejected robot must not have displaced the real one
        assertSame(LifecycleRobot, BaseRobot.INSTANCE)
    }
    @Test
    fun `uninitialized access fails loudly`() {
        assertFalse(robot.isInited)
        assertNull(robot.initType)
        assertThrows(IllegalStateException::class.java) { robot.components }
        assertThrows(IllegalStateException::class.java) { robot.seasonInstance }
        assertThrows(IllegalStateException::class.java) { robot.alliance }
        assertThrows(IllegalStateException::class.java) { Alliance.pick("blue", "red") }
    }

    @Test
    fun `empty initialization builds a disabled season instance`() {
        robot.initializeEmptyRobot()
        robot.components.tick()

        assertTrue(robot.isInited)
        assertFalse(robot.isFullyInited)
        assertEquals(InitType.EMPTY, robot.initType)
        assertTrue(robot.runningActions.isEmpty())
        assertTrue(robot.components[BaseRobotInstance::actions.name].disabled)
        assertTrue(robot.components[LifecycleInstance::component.name].disabled)
        assertNull(robot.alliance)
        assertNull(Alliance.pick("blue", "red"))
        assertTrue(robot.seasonInstance.component.disabled)
    }

    @Test
    fun `full initialization exposes and ticks the season instance`() {
        val instance = LifecycleInstance()

        robot.initializeFullRobot(instance)
        robot.components.tick()

        assertTrue(robot.isFullyInited)
        assertEquals(InitType.FULL, robot.initType)
        assertEquals(instance, robot.seasonInstance)
        assertEquals(1, instance.component.ticks)
    }

    @Test
    fun `instance configuration is applied while a registered component is constructed`() {
        val hardwareAccess = HardwareAccess.empty()
        val instance = LifecycleInstance(
            hardwareAccess = hardwareAccess,
            config = LifecycleComponentConfig(setOf(LifecycleInstance::component.name))
        )

        robot.initializeFullRobot(instance)
        robot.components.tick()

        assertTrue(instance.component.disabled)
        assertSame(hardwareAccess, instance.component.suppliedHardware)
        assertEquals(0, instance.component.ticks)
        assertSame(instance.component, robot.components[LifecycleInstance::component.name])
    }

    @Test
    fun `component config constructor applies an immutable snapshot`() {
        var disabled = true
        val config = object : ComponentConfig() {
            override fun componentBindings() = mapOf(
                ConfiguredLifecycleInstance::component.name to disabled
            )
        }
        val instance = ConfiguredLifecycleInstance(HardwareAccess.empty(), config)

        disabled = false
        instance.components.tick()

        assertTrue(instance.component.disabled)
        assertEquals(0, instance.component.ticks)
    }

    @Test
    fun `a plain base robot always has a motion controller`() {
        robot.initializeFullRobot()

        robot.follow(Path(Linear(Pose(), Pose(1.0, 1.0))).build())

        assertFalse(robot.following)
    }

    @Test
    fun `reinitializing releases the previous instance`() {
        val previous = LifecycleInstance()

        robot.initializeFullRobot(previous)
        robot.initializeEmptyRobot()

        assertEquals(1, previous.component.releases)
        assertEquals(InitType.EMPTY, robot.initType)
    }

    @Test
    fun `reset releases once and is idempotent`() {
        val instance = LifecycleInstance()
        robot.initializeFullRobot(instance)

        robot.reset()
        robot.reset()

        assertEquals(1, instance.component.releases)
        assertFalse(robot.isInited)
        assertNull(robot.initType)
    }

    @Test
    fun `reset releases components even when an action stop fails`() {
        val failure = IllegalStateException("action stop failed")
        val instance = LifecycleInstance()
        robot.initializeFullRobot(instance)
        var stops = 0
        val failing by actionType {
            object : Action() {
                override fun stop(interrupted: Boolean) {
                    stops++
                    throw failure
                }
            }
        }
        failing.submit()

        assertSame(failure, assertThrows(IllegalStateException::class.java) { robot.reset() })
        assertFalse(FieldPoseBridge.snapshot().active)
        robot.reset()

        assertEquals(1, stops)
        assertEquals(1, instance.component.releases)
        assertFalse(robot.isInited)
        assertNull(robot.initType)
    }

    @Test
    fun `reset preserves action and component failures after releasing later components`() {
        val actionFailure = IllegalStateException("action stop failed")
        val releaseFailure = IllegalArgumentException("component release failed")
        val instance = LifecycleInstance()
        instance.component.releaseFailure = releaseFailure
        val last = instance.components.register("last", LifecycleComponent(HardwareAccess.empty()))
        robot.initializeFullRobot(instance)
        val failing by actionType {
            object : Action() {
                override fun stop(interrupted: Boolean) { throw actionFailure }
            }
        }
        failing.submit()

        val failure = assertThrows(IllegalStateException::class.java) { robot.reset() }
        robot.reset()

        assertSame(actionFailure, failure)
        assertEquals(listOf(releaseFailure), failure.suppressed.toList())
        assertEquals(1, instance.component.releases)
        assertEquals(1, last.releases)
        assertFalse(robot.isInited)
        assertNull(robot.initType)
    }

    @Test
    fun `reset tolerates the same failure in action and component cleanup`() {
        val sharedFailure = IllegalStateException("shared hardware failure")
        val instance = LifecycleInstance()
        instance.component.releaseFailure = sharedFailure
        robot.initializeFullRobot(instance)
        val failing by actionType {
            object : Action() {
                override fun stop(interrupted: Boolean) { throw sharedFailure }
            }
        }
        failing.submit()

        val failure = assertThrows(IllegalStateException::class.java) { robot.reset() }

        assertSame(sharedFailure, failure)
        assertEquals(emptyList<Throwable>(), failure.suppressed.toList())
        assertEquals(1, instance.component.releases)
        assertFalse(robot.isInited)
    }

    @Test
    fun `component release failure still clears the robot instance`() {
        val releaseFailure = IllegalStateException("component release failed")
        val instance = LifecycleInstance()
        instance.component.releaseFailure = releaseFailure
        val last = instance.components.register("last", LifecycleComponent(HardwareAccess.empty()))
        robot.initializeFullRobot(instance)

        assertSame(releaseFailure, assertThrows(IllegalStateException::class.java) { robot.reset() })
        robot.reset()

        assertEquals(1, last.releases)
        assertFalse(robot.isInited)
        assertNull(robot.initType)
    }

    @Test
    fun `full initialization stores alliance on the robot instance until reset`() {
        robot.initializeFullRobot(alliance = Alliance.RED)

        assertEquals(Alliance.RED, robot.alliance)
        assertEquals(Alliance.RED, robot.seasonInstance.alliance)
        assertEquals("red", Alliance.pick("blue", "red"))

        robot.reset()

        assertThrows(IllegalStateException::class.java) { robot.alliance }
        assertThrows(IllegalStateException::class.java) { Alliance.pick("blue", "red") }
    }

    @Test
    fun `failed full initialization does not leak alliance`() {
        assertThrows(IllegalStateException::class.java) {
            robot.failFullInitialization(Alliance.BLUE)
        }

        assertFalse(robot.isInited)
        assertNull(robot.initType)
        assertThrows(IllegalStateException::class.java) { robot.alliance }
        assertThrows(IllegalStateException::class.java) { Alliance.pick("blue", "red") }
    }
}
