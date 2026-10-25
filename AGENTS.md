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

The framework has no season singleton. A consumer supplies its typed
`BaseRobot<RobotInstance>` singleton and `RobotFactory<T>` using `Context`.
`BaseRobotInstance` owns the component registry, action controller, and concrete
motion controller. Components resolve devices only through the protected
component-scoped `HardwareAccess`; disabled components never query hardware and
must remain safe to call. All components, including disabled ones, are released
at reset. Attempt every cleanup and preserve later failures as suppressed.

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

Full initialization starts match recording; empty initialization does not.
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
