package org.firstinspires.ftc.teamcode.opmode.routines

import gentoo.myrelib.math.Pose
import gentoo.myrelib.opmode.Auton
import gentoo.myrelib.pathing.AutonSequence
import gentoo.myrelib.pathing.Linear
import gentoo.myrelib.pathing.Path
import gentoo.myrelib.util.Alliance
import org.firstinspires.ftc.teamcode.action.ClawActions
import org.firstinspires.ftc.teamcode.component.Claw
import org.firstinspires.ftc.teamcode.opmode.TestAuton

/** KSP generates Blue_SmokeAuton and Red_SmokeAuton in this package. */
open class SmokeAuton(alliance: Alliance) : TestAuton(alliance) {
    override fun buildSequence() = smokeSequence(alliance)
}

internal fun smokeSequence(alliance: Alliance): AutonSequence =
    AutonSequence.Factory(alliance).autonSequence(Alliance.BLUE)
        .run(ClawActions.SET_CLAW(Claw.State.CLOSED))
        .path(Path(Linear(Pose(-24.0, -24.0, 0.0), Pose(-24.0, 0.0, 0.0))))
        .run(ClawActions.SET_CLAW(Claw.State.OPEN))
        .build()

/** Processor fixture: indirect subclasses must not receive alliance wrappers. */
class IndirectSmokeAuton(alliance: Alliance) : SmokeAuton(alliance)

/** Processor fixture for an annotated base class from the myrelib dependency. */
open class FrameworkSmokeAuton(alliance: Alliance) : Auton(alliance, { autonSequence(Alliance.BLUE) })
