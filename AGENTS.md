# myrelib

This is the standalone reusable framework repository. It contains the Android
`:myrelib` library, JVM `:processor` KSP module, `:yb` Gradle tooling module,
and Android library `:testbot` consumer sandbox. Keep sample robots and opmodes in
`:testbot` only. Do not add real season code, an RC app, or dependencies
on an external consuming robot project. The library and processor must never
depend on the sandbox.

Build with JDK 17 or newer and Android SDK platform 36. Keep Gradle's current-JDK
behavior; target bytecode versions instead of pinning `jvmToolchain`. Android
library and sandbox bytecode target Java 8; the processor targets Java 17.

Commands:

```sh
./gradlew :myrelib:testDebugUnitTest
./gradlew :myrelib:assembleRelease :processor:jar
./gradlew :testbot:testDebugUnitTest :testbot:assembleRelease
python3 -B -m unittest discover -s yb/src/test/python
```

FTC SDK, Pedro, and KSP API versions live in root `gradle.properties`. Use those
properties in module dependencies instead of duplicating version strings.
Consumers must align their FTC SDK and Pedro versions with the library.
The sandbox applies the real KSP plugin using `kspVersion` and consumes
`:processor` through `ksp`. Keep its fixtures season-neutral and hardwareless
desktop tests runnable without a Robot Controller. Its motion component stays
disabled until a real follower factory is supplied; path tests check authoring
and scheduling, not simulated drivetrain motion.

Publish the Android AAR, KSP JAR, and `myrelib-yb` Gradle plugin at the same
version through the manual Maven Central workflow. The workflow starts each
new major at minor `0` and increments the minor from Maven Central's published
versions. Consumers can use a major-version selector such as `0.+`.
The workflow uses `-PverifyPublication` to stage artifacts in a local Maven
repository and run the sandbox against the library and processor before publishing.

Keep this file synchronized with framework architecture and extension contracts.
Test stable, general behaviors and shared contracts: scheduling, lifecycle,
hardware scoping, coordinates, persistence, configuration, and file protocols.
Do not require a test per component or action, or pin changing season behavior.
Regression tests should fail against the previous implementation.

The framework has no season singleton. A consumer supplies one `BaseRobot`
singleton that owns its components, action controller, and concrete motion
controller directly; there is no separate robot-instance type or robot factory.
Declare `val turret by component(::Turret)` on that singleton. Component delegates
register factories at declaration and construct them in declaration order only
during the first opmode INIT. Early property reads fail without constructing a
component; property references used for configuration do not read components.
Successfully created components are retained if a later factory fails, and only
unfinished factories are retried. Use no-argument `Component()` /
`StatefulComponent()` constructors. Ordinary fields and FSM state survive normal
opmode transitions without persistence annotations. A fresh `RobotSession` owns
each run's FTC opmode reference and alliance. `session.opMode`
exposes the current opmode's telemetry, gamepads, timing, and other FTC data.
The viewer and recordings derive the opmode name from its class's simple name.
`startOpMode(...)` takes the opmode and derives hardware access from its map,
attaching the robot at each FTC INIT. The first call invokes internal `init()`
to construct components once. Robot and session state follow the framework
opmode's INIT/PLAY phase, with IDLE after reset. `start()` runs component start
hooks and signals PLAY for bare FTC opmodes.
Hardwareless tests supply a lightweight FTC opmode stub to the protected
`startOpMode` seam.
Reset drops the robot's session reference without discarding its components.
Do not retain sessions or opmodes in persistent component fields or app-lifetime
bridges; use the current session when opmode data is needed.

Components resolve devices only through protected component-scoped
`HardwareAccess` in `onAttach`, which configures hardware and refreshes current
measurements at every enabled INIT without implicitly zeroing encoders.
`onInitialize` runs once after the first successful enabled attachment, allowing
physical setup to wait for the first opmode; disabled sessions defer it.
`onStart` runs at PLAY. `onDetach` releases per-run handles/tasks while retaining
calibration and logical state. Recreate hardware wrappers during attachment;
managed lookups use SDK interfaces, and direct handles expire at detach and only
work on the attachment thread. Low-level raw HardwareMap integrations must drop
all references at detach. Disabled components never query hardware and must
remain safe to call. Reset cancels running/queued actions and detaches all
attached components, including disabled ones. Attempt every cleanup and preserve
later failures as suppressed. Partial attachment failure rolls back acquired
resources and allows retry with the same component objects.

`RobotOpMode` owns per-opmode input bindings, ticks components once per loop,
clears bulk caches before attachment/loops, and guarantees cleanup after user
hook failures. Consumers override the public lifecycle hooks, not FTC methods.
Bare FTC opmodes must call startOpMode/start/reset and manage inputs/caches
themselves.
There is no background hardware polling between opmodes; movement while idle is
observed when attachment refreshes sensors. Persistence lasts for this robot
singleton, not across an app restart, power loss, or code reload.

`StatefulComponent` owns a protected enum FSM; keep transitions behind component
methods. Its declarative `changeState` selects the first matching rule based on
the entry state and commits after the block. Disabled components skip the block.

myrelib poses use centered field inches, +x right, +y up, clockwise degrees, and
heading zero along +y. Pedro uses corner-origin inches and counterclockwise
radians from +x. Use `toPedro` / `toPose` and `FieldCoordinateSystem`; do not
duplicate the 144-inch field size or 72-inch shift. `normalizeAngle` returns
[-180, 180). Alliance inversion rotates 180 degrees around field center.

The action controller owns scheduling and ticks once per component loop.
Action types are stable identities, each accepted submission creates a fresh
action, and requirements/conflict policies belong to instances. Group actions
must preserve cancellation, reentrant callbacks, and all-child cleanup.

Initialization has one lifecycle path. Disabling every component is ordinary
initialization with `ComponentConfig.disableAll()`, not a separate mode.
During INIT, `configureRecording` registers cached state suppliers for the live
viewer and optional match recording. Component ticks refresh the live feed at
the sample interval. Opmodes opt into disk recording with `startRecording()`
and append samples with `recordState(force = ...)`. A sample sent to both
destinations reads each supplier once. Phase comes from the active session
(`INIT`/`PLAY`). Reset closes the writer and clears the suppliers for the run.
State suppliers run on the opmode thread. The live feed and background writer
receive detached bounded data. `pose` defaults to cached localization and
`motionPath` is reserved. Path events preserve bounded geometry between samples.
Recording filenames use UTC creation timestamps with UUID suffixes; legacy
epoch filenames remain readable. Match timing uses a monotonic clock.

The viewer serves HTML and live JSON from `/visualizer/`. Its browser code
requests JSON explicitly with `Accept: application/json`. The read-only
handlers, immutable feed bridge, recording file
manager, and assets live in this library. Do not retain robot objects, Pedro
paths, or callbacks in app-lifetime bridges. Do not put this library in Sloth's
replacement packages; framework or asset changes need a full consumer APK push.
The optional `/yb/sloth` receiver is separate from the viewer. Enable it only
when Sloth is on the runtime classpath and YB has paired a key in the app's
private files over ADB. Require a fresh signed challenge for every upload.
YB Full Push uses the Control Hub's built-in HTTP updater when available.
ADB fallback is allowed before an HTTP upload starts. Cancel updater prompts
and use in-place ADB installs so Full Push never uninstalls the app.
The viewer source is in the `visualizer` repository under `src/tracker`.
The manual visualizer workflow publishes numbered ZIP releases to Maven Central
as `io.github.gentoo-37006:visualizer`. Online Gradle builds check for the latest
`0.x` release and package it into the myrelib AAR. If package resolution fails,
reuse the last successfully resolved archive from the project Gradle cache.
Fail when there is no cached archive.
Keep the built viewer out of the repository.

`@Auton` marks a base class. The processor generates blue/red FTC wrappers only
for its direct subclasses, in the annotated base's `.generated` package. Base
and indirect subclasses do not generate wrappers. The processor must not depend
on a season project. Derive qualified names and alliance constants from class
references when building the processor so its JVM runtime can run without
Android AARs on its classpath.

Never modify the original `biobuzz-2027` project as part of work in this repository.
