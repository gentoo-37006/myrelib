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

The sources were copied from the current local `biobuzz-2027/myrelib` subtree,
including its current uncommitted library changes. That project was not changed
and still uses its existing in-repository library. There is no automatic sync.

The Hub viewer's editable source is currently in the separate sibling
`auton-visualizer/src/tracker` project. To refresh the packaged assets, run there:

```sh
npm run build:tracker -- ../myrelib/myrelib/src/main/assets/field-viewer
```

The checked-in assets require no Node.js installation to build the library.
