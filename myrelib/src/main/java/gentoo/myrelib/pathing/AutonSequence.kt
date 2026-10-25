package gentoo.myrelib.pathing

import com.pedropathing.utils.Timer
import gentoo.myrelib.action.ActionInstance
import gentoo.myrelib.action.ActionSubmission
import gentoo.myrelib.action.ActionType
import gentoo.myrelib.action.BuiltInActions
import gentoo.myrelib.action.Follow
import gentoo.myrelib.action.Instant
import gentoo.myrelib.action.RunAction
import gentoo.myrelib.action.Sequential
import gentoo.myrelib.action.Wait
import gentoo.myrelib.action.WaitUntil
import gentoo.myrelib.action.actionType
import gentoo.myrelib.math.Pose
import gentoo.myrelib.util.Alliance

/** Alliance-aware authoring facade over a controller-managed sequential action. */
class AutonSequence private constructor(
    val startPose: Pose,
    private val steps: List<(Timer) -> ActionInstance>,
) {
    val opmodeTimer = Timer()
    private var execution: Sequential? = null
    private val AUTON_ROUTINE by actionType {
        Sequential(*steps.map { it(opmodeTimer) }.toTypedArray()).also { execution = it }
            .onStart { opmodeTimer.reset() }
    }

    val currentAction: String?
        get() = execution?.currentAction?.let {
            if(it is RunAction) "RunAction(${it.submission.type.name})" else it.javaClass.simpleName
        }
    val actionIndex: Int get() = execution?.completedCount ?: 0
    val isRunning: Boolean get() = AUTON_ROUTINE.isRunning() || AUTON_ROUTINE.isStaged()

    /** Repeated calls while active are ignored; after completion a fresh action tree is built. */
    fun start() = AUTON_ROUTINE.submit()
    fun stop() = AUTON_ROUTINE.remove()

    class Factory(private val alliance: Alliance) {
        fun autonSequence(alliance: Alliance): Builder = Builder(alliance != this.alliance)
    }

    class Builder(private val inverted: Boolean) {
        private val steps = mutableListOf<(Timer) -> ActionInstance>()
        private var firstPath: Path? = null

        /** Deadline relative to PLAY, not to this step. */
        fun waitUntil(seconds: Double): Builder = apply {
            steps.add { timer -> WaitUntil(seconds) { timer.seconds() } }
        }

        fun wait(seconds: Double): Builder = apply { steps.add { Wait(seconds) } }

        fun path(path: Path): Builder = apply {
            val alliancePath = if(inverted) path.mirrored() else path
            if(firstPath == null) firstPath = alliancePath
            val compiled = alliancePath.build()
            val callbacks = alliancePath.callbacks()
            run(ActionSubmission(BuiltInActions.FOLLOW) { Follow(compiled, callbacks) })
        }

        fun run(action: ActionType): Builder = run(action())
        fun run(action: ActionType, timeoutSeconds: Double): Builder = run(action(), timeoutSeconds)
        fun run(action: ActionType, repeat: Int): Builder = run(action(), repeat)
        fun run(action: ActionType, timeoutSeconds: Double, repeat: Int): Builder =
            run(action(), timeoutSeconds, repeat)

        fun run(action: ActionSubmission): Builder = apply { steps.add { RunAction(action) } }
        fun run(action: ActionSubmission, timeoutSeconds: Double): Builder = apply {
            steps.add { RunAction(action, timeoutSeconds) }
        }

        fun run(action: ActionSubmission, repeat: Int): Builder = apply {
            require(repeat >= 0) { "Repeat count cannot be negative" }
            repeat(repeat) { run(action) }
        }

        fun run(action: ActionSubmission, timeoutSeconds: Double, repeat: Int): Builder = apply {
            require(repeat >= 0) { "Repeat count cannot be negative" }
            repeat(repeat) { run(action, timeoutSeconds) }
        }

        fun submit(action: ActionType): Builder = submit(action())
        fun submit(action: ActionType, timeoutSeconds: Double): Builder =
            submit(action(), timeoutSeconds)

        fun submit(action: ActionSubmission): Builder =
            apply { steps.add { Instant { action.submit() } } }

        fun submit(action: ActionSubmission, timeoutSeconds: Double): Builder = apply {
            steps.add { Instant { action.submit(timeoutSeconds) } }
        }

        fun remove(action: ActionType): Builder =
            apply { steps.add { Instant { action.remove() } } }

        fun build(): AutonSequence = AutonSequence(firstPath?.startPose ?: Pose(), steps.toList())
    }
}
