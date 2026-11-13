package gentoo.myrelib3.action

/** Coordinates a separately scheduled request without claiming its hardware requirements. */
class RunAction(
    val submission: ActionSubmission,
    private val timeoutSeconds: Double? = null,
) : Action() {
    private var awaiting = false

    override fun start() {
        awaiting = true
        if(timeoutSeconds == null) submission.submit() else submission.submit(timeoutSeconds)
    }

    override fun canContinue() = submission.type.isRunning() || submission.type.isStaged()

    override fun stop(interrupted: Boolean) {
        val remove = interrupted && awaiting
        awaiting = false
        if(remove) submission.type.remove()
    }
}
