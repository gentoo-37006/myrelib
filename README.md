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
processor JAR is written under `processor/build/libs/`. The Maven publications
include metadata so Gradle can resolve their transitive dependencies.

SDK, Pedro, and KSP API versions have one source in root `gradle.properties`.
Keep the consuming robot application's SDK and Pedro dependencies aligned.

## Use a published release

After the first Maven Central release, add these dependencies to your FTC
module. Apply the KSP plugin if you use the auton wrapper generator.

```groovy
def myrelibVersion = '0.+'
dependencies {
    implementation "io.github.gentoo-37006:myrelib:${myrelibVersion}"
    ksp "io.github.gentoo-37006:myrelib-processor:${myrelibVersion}"
}
```

Keep `mavenCentral()` and `google()` in your repositories. For Pedro Pathing,
also keep `maven { url 'https://repo.dairy.foundation/releases/' }`. The AAR
contains the viewer, so consumers can build without GitHub credentials.

`0.+` follows minor releases within major version `0`. Change the
selector to opt into a new major, or use the same exact version for both
dependencies to pin a build. Gradle caches dynamic versions for 24 hours by
default. Use `--refresh-dependencies` to check for a new release immediately.

## YB run configurations

Projects using Sloth can add the YB plugin to their root `build.gradle`.
After the plugin is available on Maven Central, add `mavenCentral()` to
`buildscript.repositories`, add the classpath dependency to
`buildscript.dependencies`, and apply the plugin after the `plugins` block.

```groovy
buildscript {
    repositories { mavenCentral() }
    dependencies { classpath 'io.github.gentoo-37006:myrelib-yb:4.+' }
}

apply plugin: 'io.github.gentoo-37006.myrelib.yb'
```

Open the project in Android Studio or sync it with Gradle. The plugin creates
local **Sloth Push** and **Full Push** configurations when Gradle loads the
project. Each runs the packaged YB script with Python 3. The **yb → genYbRuns**
task is also available in the Gradle panel for regenerating the local files.

YB reuses its existing Wi-Fi configuration. On a new computer, configure it
once from the project root with
`java -jar .gradle/myrelib/yb.jar config --ssid <SSID> --password <PASSWORD>`.

**Full Push** tries the Control Hub's built-in HTTP updater over Wi-Fi. It falls
back to ADB if the updater is unavailable before uploading. Both paths require
the installed app's signing key and leave the existing app installed if the
signatures conflict. Use `push full --adb` to force ADB and pair this computer
for Sloth HTTP hot loads, or `push full --usb` to push over a cable.

## Publish a release

1. Verify the `GROUP` namespace from `gradle.properties` in the [Central Portal](https://central.sonatype.com/).
   Generate a [publisher token](https://central.sonatype.org/publish/generate-portal-token/)
   and create a [GPG signing key](https://central.sonatype.org/publish/requirements/gpg/).
   Publish the public half of the signing key so Maven Central can verify signatures.
2. In the myrelib repository's **Settings → Secrets and variables → Actions**,
   add the secrets below. You can share these secrets with `visualizer` at
   the organization level.

   | Secret | Value |
   | --- | --- |
   | `MAVEN_CENTRAL_USERNAME` | Central Portal token username |
   | `MAVEN_CENTRAL_PASSWORD` | Central Portal token password |
   | `MAVEN_SIGNING_KEY` | ASCII-armored GPG private key |
   | `MAVEN_SIGNING_PASSWORD` | Passphrase for the signing key |

3. Publish `io.github.gentoo-37006:visualizer` to Maven Central first. Once
   the myrelib workflow is in the default branch, open **Actions → Publish
   myrelib to Maven Central → Run workflow**. Select the branch to publish.
   The workflow stages the library, processor, and YB plugin locally, builds
   and tests `testbot` against the library and processor, and sends one signed
   deployment named `myrelib-<version>` to Maven Central.

`releaseMajor` in `gradle.properties` sets the major version. Each successful
release increments the minor, starting at `0.0` for major `0`. Change
`releaseMajor` to start a new major at `.0`, for example `1.0`, then `1.1`.
The workflow uses Maven Central's published versions to select the next minor.

To check the Maven packages locally without uploading them, run both commands
in order:

```sh
./gradlew -PverifyPublication :myrelib:publishAllPublicationsToVerificationRepository :processor:publishAllPublicationsToVerificationRepository :yb:publishAllPublicationsToVerificationRepository
./gradlew -PverifyPublication :testbot:testDebugUnitTest :testbot:assembleRelease
```

## Layout

- `myrelib/src/main/java/gentoo/myrelib/`: reusable framework.
- `myrelib/src/test/`: framework JVM tests.
- `processor/`: KSP auton wrapper generator, built as a JVM JAR.
- `testbot/`: standalone consumer sandbox with sample robot and opmodes.
- `yb/`: Gradle plugin and launcher for YB in Android Studio.

The sandbox depends on `:myrelib` and applies `:processor` through KSP. The YB
plugin is used by the consumer's Gradle build. See [the sandbox guide](testbot/README.md) for its setup
and examples.

## Component lifetime

The consumer declares components directly on a single `object Robot : BaseRobot()`.
Component delegates store factories when the singleton loads and construct the
components in declaration order during the first opmode INIT. Reading a component
before then throws without constructing it; configuration can safely use property
references such as `Robot::turret`. Each opmode gets a new `RobotSession`, while
component objects, ordinary fields, calibration offsets, and FSM state persist
through `reset()` and subsequent initialization. Per-run alliance is
`Robot.session.alliance`. The session also owns the current FTC opmode as
`Robot.session.opMode`, so telemetry, gamepads, runtime, and other opmode data
are available through that reference. Read it from the current session rather
than retaining an opmode in persistent component fields. Reset releases the
robot's session reference; the next initialization supplies the new opmode.
Device access still belongs in the component attachment hooks.

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
`playStart`, `playTick`, and `playStop`. It refreshes bulk caches before
attachment/loops, ticks the scheduler and components once per loop, and always
attempts cleanup. Extend `Teleop(alliance)` and bind controls in
`driverBindings(gamepad)` and `operatorBindings(gamepad)` during INIT. Teleop
polls the per-run bindings before the component tick during PLAY.
Leave scheduling and component ticks to the framework. Bare FTC opmodes must
call `Robot.startOpMode(this, alliance = ...)`, `Robot.start()`, and
`Robot.reset()` themselves and manage inputs and bulk caching. Persistence ends
when the robot singleton is replaced or the app restarts. Physical references
also depend on the underlying sensors.

## Initialization and recording

There is one initialization path. To run with every component disabled, pass
`ComponentConfig.disableAll()` as `config` to `Robot.startOpMode(this, ...)` or
`RobotOpMode(robot = Robot, ...)`. It still creates a normal session.
The robot uses the current FTC opmode's hardware map. The viewer and recordings
use the opmode's class simple name. `startOpMode(...)` attaches the robot during each
opmode's INIT; its first call invokes the internal, one-time `init()` to construct
components. `Robot.state` follows the framework opmode's INIT/PLAY phase and
reports IDLE when there is no active session. `RobotOpMode` runs component
start hooks at PLAY. For bare FTC opmodes, calling `Robot.start()` also signals
the transition to PLAY. Desktop
integrations can use the protected `startOpMode` overload with a lightweight FTC opmode stub and `HardwareAccess.empty()`;
no separate empty/full initialization mode is needed.

Recording is an explicit opmode choice. Call `Robot.startRecording()` in the
opmode's `initStart` to include INIT, or in `playStart` to begin at PLAY. Register
robot-specific cached state suppliers by overriding
`configureRecording(recorder: MatchRecorder)` on the robot. This hook runs once
per session during INIT. The same cached states feed the live viewer and
recordings, including the default `pose` and reserved `motionPath`.
Component ticks refresh live state at the configured sample interval. Call
`Robot.recordState()` from the opmode hooks you want to capture on disk. Each
sample uses the current robot session state (`INIT` or `PLAY`). Recording to
disk starts only on request. A disabled component configuration can record too.

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

Open `/visualizer/` on the Control Hub to see the live viewer. Its browser
code requests the same URL with `Accept: application/json` to read current
state. The response includes `states` and `errors` alongside pose, path,
and session information.

The sources were copied from the current local `biobuzz-2027/myrelib` subtree,
including its current uncommitted library changes. That project was not changed
and still uses its existing in-repository library. There is no automatic sync.

The Hub viewer source lives in the `visualizer` repository under `src/tracker`.
Online myrelib builds download the latest `0.x` ZIP of
`io.github.gentoo-37006:visualizer` from Maven Central without credentials and
pack it under `assets/field-viewer/` in the myrelib AAR. The installed Robot
Controller serves the packaged assets locally.

If Gradle cannot resolve the viewer, myrelib reuses the last downloaded archive
in `.gradle/viewer-package/`. A build fails if neither is available. The cache
survives `gradlew clean`.
