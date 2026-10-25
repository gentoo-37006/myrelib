package gentoo.myrelib.action

/**
 * Records the lifecycle callbacks it receives so tests can assert on the order,
 * and stops on its own after [ticksToRun] ticks.
 */
class FakeAction(
    private val name: String = "action",
    private val ticksToRun: Int = 1,
    private val log: MutableList<String> = mutableListOf()
) : Action() {
    val events: List<String> get() = log
    var ticks = 0
        private set
    var interrupted: Boolean? = null
        private set

    override fun start() {
        log.add("$name:start")
    }

    override fun canContinue() = ticks < ticksToRun

    override fun tick() {
        ticks++
        log.add("$name:tick")
    }

    override fun stop(interrupted: Boolean) {
        this.interrupted = interrupted
        log.add("$name:stop")
    }
}

/** Drives an action to completion the way [gentoo.myrelib.component.ActionController] would. */
fun ActionInstance.runToCompletion(maxTicks: Int = 100): Int {
    doStart()
    var ticks = 0
    while(ticks < maxTicks) {
        ticks++
        if(tickOrStop().stopped()) {
            return ticks
        }
    }
    throw AssertionError("action did not stop within $maxTicks ticks")
}
