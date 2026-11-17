package gentoo.myrelib.action

import gentoo.myrelib.robot.LifecycleRobot
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

private class PowerAction(val power: Double) : Action() {
    var starts = 0
    var interruption: Boolean? = null
    override fun canContinue() = true
    override fun start() { starts++ }
    override fun stop(interrupted: Boolean) { interruption = interrupted }
}

class CallableActionTypeTest {
    private val robot = LifecycleRobot
    @Before fun initialize() { robot.reset(); robot.initializeRobot() }
    @After fun cleanup() { robot.reset() }

    @Test fun `binding parameters is lazy and keeps one identity across arguments`() {
        val built = mutableListOf<PowerAction>()
        val shoot by actionType { power: Double -> PowerAction(power).also { built.add(it) } }
        val first = shoot(0.8)
        val other = shoot(0.2)
        assertEquals("shoot", shoot.name)
        assertEquals("shoot", shoot.toString())
        assertSame(shoot, first.type)
        assertSame(shoot, other.type)
        assertTrue(built.isEmpty())
        first.submit()
        other.submit(timeoutSeconds = 0.0)
        robot.components.tick()
        assertTrue(shoot.isRunning())
        assertEquals(1, built.size)
        assertEquals(0.8, built[0].power, 0.0)
        assertSame(built[0], robot.runningActions[shoot])
        shoot.remove()
        assertFalse(shoot.isRunning())
        assertEquals(true, built[0].interruption)
        first.submit()
        assertEquals(2, built.size)
        assertNotSame(built[0], built[1])
    }

    @Test fun `constructor and method references are supported`() {
        val constructor by actionType(::PowerAction)
        fun build(power: Double): ActionInstance = PowerAction(power)
        val method by actionType(::build)
        assertEquals(0.3, (constructor(0.3).createNew() as PowerAction).power, 0.0)
        assertEquals(0.4, (method(0.4).createNew() as PowerAction).power, 0.0)
        constructor(0.3).submit(); method(0.4).submit()
        assertTrue(constructor.isRunning()); assertTrue(method.isRunning())
    }

    @Test fun `legacy zero argument actions are callable without changing direct submission`() {
        var built = 0
        val intake by actionType { PowerAction(1.0).also { built++ } }
        val request = intake()
        assertEquals(0, built)
        request.submit(); intake.submit()
        assertEquals(1, built)
        intake.remove(); intake.submit(timeoutSeconds = 0.0)
        robot.components.tick()
        assertFalse(intake.isRunning())
        assertEquals(2, built)
    }

    @Test fun `bound double arguments do not get confused with timeouts`() {
        lateinit var instance: PowerAction
        val shoot by actionType { power: Double -> PowerAction(power).also { instance = it } }
        shoot(0.7).submit(timeoutSeconds = 0.0)
        assertEquals(0.7, instance.power, 0.0)
        robot.components.tick()
        assertFalse(shoot.isRunning())
        assertEquals(true, instance.interruption)
    }

    @Test fun `queued calls construct once and ignore replacement arguments`() {
        val resource = Any()
        val blocker by actionType { PowerAction(1.0).requiring(resource) }
        val built = mutableListOf<PowerAction>()
        val shoot by actionType { power: Double ->
            PowerAction(power).also { built.add(it) }.requiring(resource)
                .withPriority(ActionPriority.LOW).withBlockedBehavior(BlockedBehavior.QUEUE)
        }
        blocker.submit(); shoot(0.8).submit(); shoot(0.1).submit()
        assertTrue(shoot.isStaged()); assertFalse(shoot.isRunning())
        assertEquals(1, built.size); assertEquals(0, built[0].starts)
        blocker.remove()
        assertTrue(shoot.isRunning()); assertFalse(shoot.isStaged())
        assertEquals(1, built[0].starts); assertEquals(0.8, built[0].power, 0.0)
    }

    @Test fun `suspended calls resume the original instance and arguments`() {
        val resource = Any()
        var built = 0
        lateinit var instance: PowerAction
        val shoot by actionType { power: Double ->
            PowerAction(power).also { built++; instance = it }.requiring(resource)
                .withInterruptedBehavior(InterruptedBehavior.SUSPEND)
        }
        val override by actionType { PowerAction(1.0).requiring(resource).withPriority(ActionPriority.HIGH) }
        shoot(0.8).submit(); override.submit(); shoot(0.2).submit()
        assertTrue(shoot.isStaged()); assertEquals(1, built)
        override.remove()
        assertSame(instance, robot.runningActions[shoot])
        assertEquals(2, instance.starts); assertEquals(0.8, instance.power, 0.0)
    }

    @Test fun `disabled and stop hook submissions never construct an action`() {
        var built = 0
        lateinit var shoot: ParameterizedActionType<Double>
        val declaration by actionType { power: Double ->
            PowerAction(power).also { built++ }.onStop { shoot(0.2).submit() }
        }
        shoot = declaration
        shoot(0.8).submit(); shoot.remove()
        assertEquals(1, built); assertFalse(shoot.isRunning())
        shoot(0.8).submit(); robot.removeAllActions()
        assertEquals(2, built); assertFalse(shoot.isRunning())
        robot.initializeRobot(config = gentoo.myrelib.config.ComponentConfig.disableAll()); shoot(0.8).submit()
        assertEquals(2, built)
    }

    @Test fun `bound requests explicitly construct fresh composition children`() {
        var built = 0
        val child by actionType { value: Int -> Instant { built += value } }
        val request = child(1)
        val first = request.createNew(); val second = request.createNew()
        assertNotSame(first, second)
        Sequential(first, second).runToCompletion()
        assertEquals(2, built)
        assertFalse(child.isRunning())
    }

    @Test fun `unbound parameterized construction fails clearly`() {
        val shoot by actionType(::PowerAction)
        val failure = assertThrows(IllegalStateException::class.java) { shoot.submit() }
        assertTrue(failure.message!!.contains("requires arguments"))
        assertFalse(shoot.isRunning())
    }
}
