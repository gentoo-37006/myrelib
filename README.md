# myrelib

Standalone FTC robot framework written in Kotlin, with Pedro Pathing integration
and a sibling JVM KSP processor. This repository contains only the reusable
library, processor, tests, packaged web assets, and a separate TeamCode consumer
sandbox. It has no real season robot, Robot Controller app, or Sloth runtime.

## Build

Use JDK 17 or newer and an Android SDK with platform 36 installed. Open this
folder in Android Studio, or set `ANDROID_HOME` / `ANDROID_SDK_ROOT`. A local
`local.properties` containing `sdk.dir=...` also works and is ignored by Git.

```sh
./gradlew :myrelib:testDebugUnitTest
./gradlew :myrelib:assembleRelease :processor:jar
./gradlew :testbot:testDebugUnitTest :testbot:assembleRelease
```

On Windows use `gradlew.bat` (or `.\gradlew.bat` in PowerShell).

The AAR is written to `myrelib/build/outputs/aar/myrelib-release.aar`; the
processor JAR is written under `processor/build/libs/`. These build
outputs do not include a Maven publication or bundle transitive dependencies.

SDK, Pedro, and KSP API versions have one source in root `gradle.properties`.
Keep the consuming robot application's SDK and Pedro dependencies aligned.

## Layout

- `myrelib/src/main/java/gentoo/myrelib/`: reusable framework.
- `myrelib/src/main/assets/field-viewer/`: static Control Hub viewer.
- `myrelib/src/test/`: framework JVM tests.
- `processor/`: KSP auton wrapper generator, built as a JVM JAR.
- `testbot/`: standalone consumer sandbox with sample robot and opmodes.

The three modules are separate top-level Gradle projects. The sandbox depends on
`:myrelib` and applies `:processor` through KSP; neither reusable module depends
on the sandbox. See [the sandbox guide](testbot/README.md) for its setup
and examples.

## Component lifetime

The consumer declares components directly on a single `object Robot : BaseRobot()`.
Component delegates store factories when the singleton loads and construct the
components in declaration order during the first opmode INIT. Reading a component
before then throws without constructing it; configuration can safely use property
references such as `Robot::turret`. Each opmode gets a new `RobotSession`, while
component objects, ordinary fields, calibration offsets, and FSM state persist
through `reset()` and subsequent initialization. Per-run alliance is
`Robot.session.alliance`; device access belongs in the attachment hooks.

Declare components with no-argument factories, then acquire devices during
attachment:

```kotlin
class Turret : Component() {
    private var motor: DcMotorEx? = null
    private var zeroOffset = 0
    var angleTicks = 0
        private set

    override fun onAttach() {
        motor = hardware.get("turret")
        // Reapply configuration here, without resetting the encoder reference.
        angleTicks = (motor?.currentPosition ?: 0) - zeroOffset
    }

    override fun onInitialize() {
        // Optional one-time physical setup, after the first enabled attachment.
        // This hook is deferred if the component is disabled.
    }

    override fun tick() {
        if(disabled) return
        angleTicks = (motor?.currentPosition ?: 0) - zeroOffset
    }

    override fun onDetach() {
        try { motor?.power = 0.0 }
        finally { motor = null }
    }
}

object Robot : BaseRobot() {
    val turret by component(::Turret)

    override val WIDTH = 16.0
    override val LENGTH = 16.0
    override val config = object : ComponentConfig() {
        override fun componentBindings() = bind(Robot::motion to true)
    }
    override val followerFactory: FollowerFactory = {
        error("Supply a Pedro follower before enabling motion")
    }
}
```

The hooks run in this order: `onAttach` at each enabled INIT, `onInitialize`
once after the first successful enabled attachment, `onStart` at PLAY, and
`onDetach` at the end of every attached session (including disabled sessions).
A failed one-time initialization is retried on the next enabled attachment.
If a component factory fails, the next INIT retries unfinished factories while
retaining components that were already constructed.
Use `onAttach` to refresh measurements even when the same component object is
reused. Changes made while idle are observed at the next attachment; there is
no background hardware polling. Calibration that depends on a known physical
setup belongs in the explicit one-time hook or a mechanism method, never in a
singleton/property initializer. No hook implicitly resets encoders.

Reset cancels all running and queued actions, revokes hardware access, releases
per-run resources, and closes recording/viewer sessions. It preserves mechanism
state; an action's own cancellation hook can still deliberately change that
state. Retaining a target does not resubmit an old action. Failed INIT or user
lifecycle hooks also trigger cleanup, with later failures suppressed.

Acquire SDK interface types such as `DcMotorEx`, SDK `Servo`, or `CRServo` through
the protected hardware scope. Recreate wrappers in `onAttach` so command caches
are fresh for each run. Scoped device handles work only on the opmode thread
and expire permanently at detach. Raw hardware-map access for integrations such
as Pedro is an explicit escape hatch; release those references at detach.

Extend `RobotOpMode(alliance, Robot)` and override `initStart`, `initTick`,
`playStart`, `playTick`, and `playStop`. It owns per-run `inputs`, refreshes bulk
caches before attachment/loops, ticks the scheduler and components once per
loop, and always attempts cleanup. Do not tick components or the
action controller again in these hooks. Bare FTC opmodes must call
`Robot.init(...)`, `Robot.start()`, and `Robot.reset()` themselves and manage
inputs/bulk caching. Persistence ends when the robot singleton is replaced or
the app restarts; physical references also depend on the underlying sensors.

## Initialization and recording

There is one initialization path. To run with every component disabled, pass
`ComponentConfig.disableAll()` to `Robot.init(..., componentConfig = ...)` or to
`RobotOpMode(robot = Robot, componentConfig = ...)`. It still creates a normal
session. Desktop integrations can use the protected initialization overload with
`HardwareAccess.empty()`; no separate empty/full initialization mode is needed.

Recording is an explicit opmode choice. Call `Robot.startRecording()` in the
opmode's `initStart` to include INIT, or in `playStart` to begin at PLAY. Register
robot-specific cached state suppliers by overriding
`configureRecording(recorder: MatchRecorder)` on the robot. Call `Robot.recordState()` from the
opmode hooks you want to capture. Each sample uses the current robot session
state (`INIT` or `PLAY`), so callers never supply a phase string. Neither initialization nor `RobotOpMode`
automatically starts or samples a recording. Calls to `recordState` before an
explicit start are harmless. Repeated `startRecording` calls in one session keep
the existing recording; a disabled component configuration can record too.

```kotlin
override fun initStart() {
    Robot.startRecording()
}

override fun initTick() {
    Robot.recordState()
}

override fun playTick() {
    Robot.recordState()
}
```

`reset()` always closes a started recording and releases its state suppliers.
The next opmode must opt in again. Recording hooks run on the opmode thread;
background writers receive only detached, bounded data. Path geometry events
are captured while recording, including an active path when recording starts.
The default recording configuration can be customized on `BaseRobot`.

The sources were copied from the current local `biobuzz-2027/myrelib` subtree,
including its current uncommitted library changes. That project was not changed
and still uses its existing in-repository library. There is no automatic sync.

The Hub viewer's editable source is currently in the separate sibling
`auton-visualizer/src/tracker` project. To refresh the packaged assets, run there:

```sh
npm run build:tracker -- ../myrelib/myrelib/src/main/assets/field-viewer
```

The checked-in assets require no Node.js installation to build the library.
