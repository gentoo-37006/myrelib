package gentoo.myrelib.action

import java.util.function.Supplier

/** Repeatedly creates and runs a fresh child until this composition is stopped. */
class Loop(private val actionFactory: Supplier<ActionInstance>) : GroupAction() {
    private val firstChild = actionFactory.get()
    private var unusedFirstChild: ActionInstance? = firstChild
    private var current: ActionInstance? = null

    override val requirementChildren: Collection<ActionInstance> get() = listOf(firstChild)

    override fun start() {
        startNext()
    }

    override fun tick() {
        val child = current ?: return
        // If the child stopped this loop from its tick or stop hook, current is
        // already cleared; starting another child then would leave one running
        // that nothing ticks or stops.
        if(child.tickOrStop().stopped() && current === child) {
            current = null
            startNext()
        }
    }

    override fun stop(interrupted: Boolean) {
        val action = current
        current = null
        action?.doStop(interrupted)
    }

    override fun debugChildren() = listOfNotNull(current)

    private fun startNext() {
        val child = unusedFirstChild?.also { unusedFirstChild = null } ?: actionFactory.get().also {
            requireMatchingContract(firstChild, it)
        }
        // Track the child before starting it: if its start cancels this loop,
        // stop() has to find it to stop it.
        current = child
        startFresh(child)
    }
}
