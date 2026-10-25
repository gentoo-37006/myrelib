package gentoo.myrelib.action

/** Waits relative to this action's activation. */
class Wait(private val seconds: Double) : Action() {
    override fun canContinue() = currentTime() - startTime() < seconds
}

/** Waits against a caller-owned clock, such as elapsed autonomous time. */
class WaitUntil(private val seconds: Double, private val elapsed: () -> Double) : Action() {
    override fun canContinue() = elapsed() < seconds
}
