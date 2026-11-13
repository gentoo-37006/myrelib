package gentoo.myrelib3.action

import java.util.function.Supplier

/** Creates and runs a fresh child exactly [times] times. */
class Repeat(
    private val times: Int,
    private val actionFactory: Supplier<ActionInstance>,
) : GroupAction() {
    private val firstChild: ActionInstance?
    private var unusedFirstChild: ActionInstance?
    private var currentAction: ActionInstance? = null
    private var repetitions = 0

    init {
        require(times >= 0) { "Repeat count cannot be negative" }
        firstChild = if(times == 0) null else actionFactory.get()
        unusedFirstChild = firstChild
    }

    override val requirementChildren: Collection<ActionInstance>
        get() = listOfNotNull(firstChild)

    override fun start() {
        repetitions = 0
        currentAction = null
        if(times == 0) {
            return
        }
        startNext()
    }

    override fun canContinue(): Boolean {
        return repetitions < times
    }

    override fun tick() {
        val child = currentAction ?: return
        // If the child stopped this repeat from its tick or stop hook,
        // currentAction is already cleared; starting another child then would
        // leave one running that nothing ticks or stops.
        if(child.tickOrStop().stopped() && currentAction === child) {
            repetitions++
            if(repetitions >= times) {
                currentAction = null
                return
            }
            startNext()
        }
    }

    override fun stop(interrupted: Boolean) {
        val action = currentAction
        currentAction = null
        action?.doStop(interrupted)
    }

    override fun debugChildren() = listOfNotNull(currentAction)

    private fun startNext() {
        val child = unusedFirstChild?.also { unusedFirstChild = null } ?: actionFactory.get().also {
            firstChild?.let { template -> requireMatchingContract(template, it) }
        }
        // Track the child before starting it: if its start cancels this repeat,
        // stop() has to find it to stop it.
        currentAction = child
        startFresh(child)
    }
}
