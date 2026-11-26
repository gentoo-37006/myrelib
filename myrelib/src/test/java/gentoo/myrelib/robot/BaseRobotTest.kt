package gentoo.myrelib.robot

import com.google.gson.JsonParser
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.HardwareMap
import gentoo.myrelib.action.Action
import gentoo.myrelib.action.actionType
import gentoo.myrelib.component.Component
import gentoo.myrelib.config.ComponentConfig
import gentoo.myrelib.field.RobotTelemetryBridge
import gentoo.myrelib.field.RecordingFiles
import gentoo.myrelib.hardware.HardwareAccess
import gentoo.myrelib.opmode.OpMode
import gentoo.myrelib.recording.MatchRecorder
import gentoo.myrelib.util.Alliance
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.qualcomm.robotcore.eventloop.opmode.OpMode as FTCOpMode

internal class HardwarelessOpMode : FTCOpMode() {
    var description = "initial"
    override fun init() = Unit
    override fun loop() = Unit
}

internal class LifecycleComponent : Component() {
    var ticks = 0
    var attachments = 0
    var initializations = 0
    var starts = 0
    var releases = 0
    var calibrationOffset = 0
    var measuredPosition = 0
    var attachFailure: Throwable? = null
    var releaseFailure: Throwable? = null
    private var motor: DcMotorEx? = null

    override fun onAttach() {
        attachments++
        motor = hardware.get("turret")
        measuredPosition = (motor?.currentPosition ?: 0) - calibrationOffset
        attachFailure?.let { throw it }
    }
    override fun onInitialize() { initializations++ }
    override fun onStart() { starts++ }
    override fun tick() { ticks++ }
    override fun onDetach() {
        motor = null
        releases++
        releaseFailure?.let { throw it }
    }
}

internal object LifecycleRobot : BaseRobot() {
    override val LENGTH = 16.0
    override val WIDTH = 16.0
    override val config = ComponentConfig.enableAll()
    override val followerFactory: FollowerFactory = { error("Hardwareless test must not construct a follower") }
    var constructions = 0
        private set
    val component by component {
        constructions++
        LifecycleComponent()
    }
    val last by component(::LifecycleComponent)
    var recordingSetups = 0
    var recordingValue = 0
    var stateReads = 0
    var lastRecorder: MatchRecorder? = null
    override fun configureRecording(recorder: MatchRecorder) {
        recordingSetups++
        lastRecorder = recorder
        recorder.addState("cached") {
            stateReads++
            recordingValue
        }
    }
    val constructionsAtDeclaration = constructions
    val prematureReadFailure = runCatching { component }.exceptionOrNull()

    fun initializeRobot(
        alliance: Alliance? = null,
        hardware: HardwareAccess = HardwareAccess.empty(),
        config: ComponentConfig = this.config,
        followerFactory: FollowerFactory = this.followerFactory,
        opMode: FTCOpMode = HardwarelessOpMode(),
    ) = startOpMode(opMode, alliance, hardware, config, followerFactory)
}

internal object DuplicateRobot : BaseRobot() {
    override val LENGTH = 16.0
    override val WIDTH = 16.0
    override val config = ComponentConfig.enableAll()
    override val followerFactory: FollowerFactory = { error("unused") }
}

class BaseRobotTest {
    @get:Rule val temporary = TemporaryFolder()
    private val robot = LifecycleRobot
    private val noMotion = object : ComponentConfig() {
        override fun componentBindings() = bind(BaseRobot::motion to true)
    }

    @Before fun clearRobotState() { robot.reset() }
    @After fun resetRobotState() {
        if(robot.constructions > 0) {
            robot.component.attachFailure = null
            robot.component.releaseFailure = null
            robot.last.releaseFailure = null
            robot.component.calibrationOffset = 0
        }
        robot.reset()
        robot.lastRecorder?.awaitClosed()
        robot.lastRecorder = null
    }

    @Test fun `half dimensions are derived after subclass initialization`() {
        assertEquals(8.0, robot.HALF_LENGTH, 0.0)
        assertEquals(8.0, robot.HALF_WIDTH, 0.0)
    }

    @Test fun `single robot declarations defer component construction and reads until INIT`() {
        assertEquals(0, robot.constructionsAtDeclaration)
        assertTrue(robot.prematureReadFailure is IllegalStateException)
        assertTrue(robot.prematureReadFailure!!.message!!.contains("first opmode INIT"))
        robot.initializeRobot(config = gentoo.myrelib.config.ComponentConfig.disableAll())
        val component = robot.component
        val registryComponent = robot.components["component"]
        assertSame(component, registryComponent)
        assertEquals(1, robot.constructions)
        assertTrue(component.disabled)
        robot.reset()
        robot.initializeRobot()
        assertSame(component, robot.component)
        assertEquals(1, robot.constructions)
    }

    @Test fun `first disabled attachment defers one-time setup until the first enabled INIT`() {
        val component = LifecycleComponent()
        val registry = gentoo.myrelib.component.ComponentRegistry()
        registry.register("component", component)
        registry.attach(HardwareAccess.empty(), ComponentConfig.disableAll().instance())
        assertEquals(0, component.initializations)
        registry.detach()
        repeat(2) {
            registry.attach(HardwareAccess.empty(), ComponentConfig.enableAll().instance())
            registry.detach()
        }
        assertEquals(2, component.attachments)
        assertEquals(1, component.initializations)
        assertEquals(3, component.releases)
    }

    @Test fun `failed one-time setup is cleaned up and retried only until it succeeds`() {
        var attempts = 0
        var detaches = 0
        val failure = IllegalStateException("not ready")
        val component = object : Component() {
            override fun onInitialize() {
                attempts++
                if(attempts == 1) throw failure
            }
            override fun onDetach() { detaches++ }
            override fun tick() = Unit
        }
        val registry = gentoo.myrelib.component.ComponentRegistry()
        registry.register("mechanism", component)
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            registry.attach(HardwareAccess.empty(), ComponentConfig.enableAll().instance())
        })
        registry.release()
        repeat(2) {
            registry.attach(HardwareAccess.empty(), ComponentConfig.enableAll().instance())
            registry.release()
        }
        registry.release()
        assertEquals(2, attempts)
        assertEquals(3, detaches)
        assertTrue(component.disabled)
    }

    @Test fun `a second robot object in the same class loader is rejected`() {
        assertSame(robot, BaseRobot.INSTANCE)
        val error = assertThrows(ExceptionInInitializerError::class.java) { DuplicateRobot.isInited }
        assertTrue(error.cause is IllegalStateException)
        assertSame(robot, BaseRobot.INSTANCE)
    }

    @Test fun `idle has no session or alliance`() {
        assertFalse(robot.isInited)
        assertEquals(State.IDLE, robot.state)
        assertThrows(IllegalStateException::class.java) { robot.session }
        assertThrows(IllegalStateException::class.java) { Alliance.pick("blue", "red") }
    }

    @Test fun `normal initialization with all components disabled keeps the viewer hardwareless`() {
        val opMode = HardwarelessOpMode()
        robot.initializeRobot(config = ComponentConfig.disableAll(), opMode = opMode)
        val snapshot = RobotTelemetryBridge.snapshot()
        assertTrue(snapshot.active)
        assertEquals(opMode.javaClass.simpleName, snapshot.opMode)
        assertEquals(robot.WIDTH, snapshot.robot!!.width, 0.0)
        assertNull(snapshot.pose)
        assertTrue(robot.component.disabled)
        assertTrue(robot.actions.disabled)
        assertNull(robot.alliance)
        robot.reset()
        assertFalse(RobotTelemetryBridge.snapshot().active)
    }

    @Test fun `opmodes reuse component identity and calibration but refresh measurements`() {
        var encoder = 165
        val motor = Proxy.newProxyInstance(DcMotorEx::class.java.classLoader, arrayOf(DcMotorEx::class.java)) { _, method, _ ->
            when(method.name) {
                "getCurrentPosition" -> encoder
                else -> error("Attachment must only read position, got ${method.name}")
            }
        }
        val hardware = HardwareAccess.from(object : HardwareMap(null, null) {
            override fun <T : Any?> get(classOrInterface: Class<out T>, deviceName: String): T = classOrInterface.cast(motor)!!
        })
        robot.initializeRobot(config = gentoo.myrelib.config.ComponentConfig.disableAll())
        val original = robot.component
        original.calibrationOffset = 100
        val initializations = original.initializations
        robot.initializeRobot(Alliance.BLUE, hardware, noMotion)
        val autonSession = robot.session
        assertSame(original, robot.component)
        assertEquals(65, original.measuredPosition)
        val afterFirstInit = original.initializations
        assertTrue(afterFirstInit == initializations || afterFirstInit == initializations + 1)
        robot.reset()
        encoder = 190 // Physical movement while idle; no component tick occurs.
        robot.initializeRobot(Alliance.RED, hardware, noMotion)
        assertSame(original, robot.component)
        assertNotSame(autonSession, robot.session)
        assertEquals(State.IDLE, autonSession.state)
        assertEquals(90, original.measuredPosition)
        assertEquals(100, original.calibrationOffset)
        assertEquals(afterFirstInit, original.initializations)
        assertEquals(1, robot.constructions)
    }

    @Test fun `each session snapshots configuration before attachment`() {
        var disabled = true
        val config = object : ComponentConfig() {
            override fun componentBindings() = bind(LifecycleRobot::component to disabled)
        }
        robot.initializeRobot(config = config)
        val component = robot.component
        val ticks = component.ticks
        disabled = false
        robot.components.tick()
        assertTrue(component.disabled)
        assertEquals(ticks, component.ticks)
        robot.initializeRobot(config = config)
        robot.components.tick()
        assertFalse(component.disabled)
        assertEquals(ticks + 1, component.ticks)
    }

    @Test fun `start runs once per session and reset disables retained references`() {
        robot.initializeRobot()
        val component = robot.component
        val starts = component.starts
        val releases = component.releases
        robot.start()
        assertEquals(State.PLAY, robot.state)
        assertEquals(starts + 1, component.starts)
        assertThrows(IllegalStateException::class.java) { robot.start() }
        robot.reset()
        robot.reset()
        assertTrue(component.disabled)
        assertEquals(releases + 1, component.releases)
    }

    @Test fun `robot state follows the framework opmode phase until the session ends`() {
        val opMode = object : OpMode() {
            override fun initStart() {
                robot.initializeRobot(config = ComponentConfig.disableAll(), opMode = this)
            }

            override fun playStart() {
                assertEquals(State.PLAY, robot.state)
                robot.start()
            }

            override fun playStop() {
                assertEquals(State.PLAY, robot.state)
            }

            override fun cleanup() = robot.reset()
        }
        opMode.hardwareMap = object : HardwareMap(null, null) {
            override fun <T : Any?> getAll(type: Class<out T>): MutableList<T> = mutableListOf()
        }

        assertEquals(State.IDLE, robot.state)
        opMode.init()
        val session = robot.session
        assertEquals(State.INIT, robot.state)
        assertThrows(IllegalStateException::class.java) { robot.start() }
        opMode.start()
        assertEquals(State.PLAY, session.state)
        opMode.stop()
        assertEquals(State.IDLE, robot.state)
        assertEquals(State.IDLE, session.state)
    }

    @Test fun `reset cancels running queued and reentrant work before the next session`() {
        robot.initializeRobot()
        val resource = Any()
        var stopped = false
        var queuedBuilds = 0
        val queued by actionType {
            queuedBuilds++
            object : Action() {}.requiring(resource).withPriority(gentoo.myrelib.action.ActionPriority.LOW)
                .withBlockedBehavior(gentoo.myrelib.action.BlockedBehavior.QUEUE)
        }
        val running by actionType {
            object : Action() {
                override fun canContinue() = true
                override fun stop(interrupted: Boolean) { stopped = interrupted; queued.submit() }
            }.requiring(resource)
        }
        running.submit()
        queued.submit()
        assertTrue(queued.isStaged())
        robot.reset()
        assertTrue(stopped)
        assertEquals(1, queuedBuilds)
        robot.initializeRobot()
        assertTrue(robot.runningActions.isEmpty())
        assertFalse(queued.isStaged())
    }

    @Test fun `an action factory ending its session cannot submit its result into the next one`() {
        robot.initializeRobot()
        var starts = 0
        val stale by actionType {
            robot.reset()
            robot.initializeRobot()
            object : Action() {
                override fun start() { starts++ }
            }
        }
        stale.submit()
        assertEquals(0, starts)
        assertTrue(robot.runningActions.isEmpty())
    }

    @Test fun `an override stop callback ending its session cannot restart incoming work`() {
        robot.initializeRobot()
        val resource = Any()
        var starts = 0
        val old by actionType { object : Action() {
            override fun stop(interrupted: Boolean) {
                robot.reset()
                robot.initializeRobot()
            }
        }.requiring(resource) }
        val incoming by actionType { object : Action() {
            override fun start() { starts++ }
        }.requiring(resource).withPriority(gentoo.myrelib.action.ActionPriority.HIGH) }
        old.submit()
        incoming.submit()
        assertEquals(0, starts)
        assertTrue(robot.runningActions.isEmpty())
    }

    @Test fun `failed INIT rolls back attached components and permits another session`() {
        robot.initializeRobot(config = gentoo.myrelib.config.ComponentConfig.disableAll())
        val component = robot.component
        val attachFailure = IllegalStateException("attachment failed")
        val cleanupFailure = IllegalArgumentException("detach failed")
        robot.reset()
        component.attachFailure = attachFailure
        component.releaseFailure = cleanupFailure
        val releases = component.releases
        val failure = assertThrows(IllegalStateException::class.java) { robot.initializeRobot(Alliance.BLUE) }
        assertSame(attachFailure, failure)
        assertEquals(listOf(cleanupFailure), failure.suppressed.toList())
        assertEquals(releases + 1, component.releases)
        assertFalse(robot.isInited)
        assertTrue(component.disabled)
        assertFalse(RobotTelemetryBridge.snapshot().active)
        assertThrows(IllegalStateException::class.java) { robot.alliance }
        component.attachFailure = null
        component.releaseFailure = null
        robot.initializeRobot()
        assertSame(component, robot.component)
    }

    @Test fun `reset attempts all cleanup and preserves action and component failures`() {
        robot.initializeRobot()
        val first = IllegalStateException("action failed")
        val second = IllegalArgumentException("component failed")
        val third = AssertionError("last failed")
        val instance = robot
        val releases = instance.last.releases
        instance.component.releaseFailure = second
        instance.last.releaseFailure = third
        val failing by actionType { object : Action() {
            override fun stop(interrupted: Boolean) { throw first }
        } }
        failing.submit()
        val thrown = assertThrows(IllegalStateException::class.java) { robot.reset() }
        assertSame(first, thrown)
        assertSame(second, thrown.suppressed.single())
        assertSame(third, second.suppressed.single())
        assertEquals(releases + 1, instance.last.releases)
        assertFalse(robot.isInited)
        assertFalse(RobotTelemetryBridge.snapshot().active)
        robot.reset()
    }

    @Test fun `initialization and component loops do not implicitly start recording`() {
        RecordingFiles.attach(temporary.root)
        for(config in listOf(ComponentConfig.enableAll(), ComponentConfig.disableAll())) {
            robot.recordingValue = 42
            robot.initializeRobot(config = config)
            robot.components.tick()
            assertEquals(42, RobotTelemetryBridge.snapshot().states["cached"])
            robot.recordState(force = true)
            robot.start()
            robot.components.tick()
            robot.recordState(force = true)
            robot.reset()
        }
        assertTrue(RecordingFiles.list().isEmpty())
    }

    @Test fun `opmodes explicitly start and sample recording during INIT or PLAY`() {
        RecordingFiles.attach(temporary.root)
        val files = mutableSetOf<java.io.File>()
        for((index, config) in listOf(ComponentConfig.enableAll(), ComponentConfig.disableAll()).withIndex()) {
            val setups = robot.recordingSetups
            val opMode = HardwarelessOpMode()
            robot.initializeRobot(Alliance.RED, config = config, opMode = opMode)
            if(index == 1) robot.start()
            robot.recordingValue = index + 10
            robot.startRecording()
            robot.startRecording() // A duplicate call must not create another writer or reconfigure states.
            assertEquals(setups + 1, robot.recordingSetups)
            robot.components.tick() // Live polling alone must not append to disk.
            val reads = robot.stateReads
            robot.recordState(force = true)
            assertEquals(reads + 1, robot.stateReads)
            val live = JsonParser().parse(RobotTelemetryBridge.json()).asJsonObject
            robot.reset()
            robot.lastRecorder!!.awaitClosed()
            val file = (RecordingFiles.list().toSet() - files).single()
            files += file
            val events = file.readLines().map { JsonParser().parse(it).asJsonObject }
            val header = events.first()
            assertEquals(opMode.javaClass.simpleName, header["opMode"].asString)
            assertEquals("RED", header["alliance"].asString)
            assertTrue(header["states"].asJsonArray.any { it.asString == "motionPath" })
            val sample = events.single { it["type"].asString == "sample" }
            assertEquals(index + 10, sample["states"].asJsonObject["cached"].asInt)
            assertEquals(live["states"], sample["states"])
            assertEquals(live["errors"], sample["errors"])
            assertEquals(live["phase"], sample["phase"])
            assertEquals(if(index == 0) "INIT" else "PLAY", sample["phase"].asString)
            assertEquals("end", events.last()["type"].asString)

            // Starting a subsequent session must not resume recording without another explicit call.
            robot.initializeRobot()
            robot.recordState(force = true)
            robot.reset()
            assertEquals(files, RecordingFiles.list().toSet())
        }
    }

    @Test fun `recording phases follow lifecycle transitions within the same session`() {
        RecordingFiles.attach(temporary.root)
        robot.initializeRobot(config = ComponentConfig.disableAll())
        robot.startRecording()
        robot.recordState(force = true)
        robot.start()
        robot.recordState(force = true)
        robot.reset()
        robot.recordState(force = true) // No recorder remains after reset.
        robot.lastRecorder!!.awaitClosed()

        val phases = RecordingFiles.list().single().readLines()
            .map { JsonParser().parse(it).asJsonObject }
            .filter { it["type"].asString == "sample" }
            .map { it["phase"].asString }
        assertEquals(listOf(State.INIT.name, State.PLAY.name), phases)
    }

    @Test fun `recording hooks require an active session and run on the opmode thread`() {
        assertThrows(IllegalStateException::class.java) { robot.startRecording() }
        RecordingFiles.attach(temporary.root)
        robot.initializeRobot(config = ComponentConfig.disableAll())
        robot.startRecording()
        val errors = java.util.concurrent.atomic.AtomicInteger()
        Thread {
            for(call in listOf({ robot.startRecording() }, { robot.recordState(force = true) })) {
                try { call() }
                catch(expected: IllegalStateException) { errors.incrementAndGet() }
            }
        }.apply { start(); join() }
        assertEquals(2, errors.get())
        robot.recordState(force = true)
        robot.reset()
        robot.lastRecorder!!.awaitClosed()
        assertEquals(1, RecordingFiles.list().single().readLines().count {
            JsonParser().parse(it).asJsonObject["type"].asString == "sample"
        })
    }

    @Test fun `each session owns its current opmode and exposes live opmode data`() {
        val firstOpMode = HardwarelessOpMode()
        robot.initializeRobot(opMode = firstOpMode)
        val firstSession = robot.session
        assertSame(firstOpMode, firstSession.opMode)
        robot.start()
        firstOpMode.description = "running"
        assertEquals("running", (robot.session.opMode as HardwarelessOpMode).description)

        robot.reset()
        assertThrows(IllegalStateException::class.java) { robot.session }
        val nextOpMode = HardwarelessOpMode()
        robot.initializeRobot(opMode = nextOpMode)
        assertNotSame(firstSession, robot.session)
        assertSame(nextOpMode, robot.session.opMode)
        assertEquals("initial", (robot.session.opMode as HardwarelessOpMode).description)
        assertEquals(State.IDLE, firstSession.state)
    }

    @Test fun `session alliance is replaced at boundaries without changing the robot`() {
        robot.initializeRobot(Alliance.RED)
        assertEquals("red", Alliance.pick("blue", "red"))
        robot.reset()
        assertThrows(IllegalStateException::class.java) { robot.alliance }
        robot.initializeRobot(Alliance.BLUE)
        assertEquals("blue", Alliance.pick("blue", "red"))
    }
}
