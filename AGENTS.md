# myrelib

This is the standalone reusable framework repository. It contains the Android
`:myrelib` library, sibling JVM `:processor` KSP module, and Android library
`:testbot` consumer sandbox. Keep sample robots and opmodes in
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
```

FTC SDK, Pedro, and KSP API versions live in root `gradle.properties`. Use those
properties in module dependencies instead of duplicating version strings.
Consumers must align their FTC SDK and Pedro versions with the library.
The sandbox applies the real KSP plugin using `kspVersion` and consumes
`:processor` through `ksp`. Keep its fixtures season-neutral and hardwareless
desktop tests runnable without a Robot Controller. Its motion component stays
disabled until a real follower factory is supplied; path tests check authoring
and scheduling, not simulated drivetrain motion.

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
each run's alliance and INIT/PLAY state; reset ends execution without discarding
the robot or components.

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
Bare FTC opmodes must call init/start/reset and manage inputs/caches themselves.
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
Opmodes opt into recording through `BaseRobot.startRecording()` and explicitly
sample with `recordState(force = ...)`, which derives its phase from the active
robot session state (`INIT`/`PLAY`) rather than caller-supplied strings. Neither
initialization nor `RobotOpMode` starts or samples recordings automatically. `configureRecording` on the robot registers
cached state suppliers. Repeated starts in a session are harmless, and reset
always closes that session's recording without carrying it into the next run.
State suppliers read cached values on the opmode thread; background writers
receive detached bounded values and never block robot ticks. `motionPath` is a
reserved framework state. Path events preserve bounded geometry between samples.
Recording filenames use UTC creation timestamps with UUID suffixes; legacy
epoch filenames remain readable. Match timing uses a monotonic clock.

The read-only `/visualizer/` handlers, immutable feed bridge, recording file
manager, and assets live in this library. Do not retain robot objects, Pedro
paths, or callbacks in app-lifetime bridges. Do not put this library in Sloth's
replacement packages; framework or asset changes need a full consumer APK push.
The viewer source is in sibling `auton-visualizer/src/tracker`; rebuild its assets
using the command in README.md rather than hand-editing the generated bundle.

`@Auton` marks a base class. The processor generates blue/red FTC wrappers only
for its direct subclasses, in the annotated base's `.generated` package. Base
and indirect subclasses do not generate wrappers. The processor must not depend
on a season project. Keep qualified names derived from class references.

Never modify the original `biobuzz-2027` project as part of work in this repository.
