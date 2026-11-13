package gentoo.myrelib3.action

/**
 * Runs every child together and finishes when [deadline] finishes. Other
 * children that are still running are stopped at that point.
 */
class Deadline(
    private val deadlineAction: ActionInstance,
    vararg actions: ActionInstance,
) : GroupAction() {
    private val actions = actions.toList()
    private val allActions = listOf(deadlineAction) + this.actions
    private var deadline: ActionInstance? = null
    private val running = mutableListOf<ActionInstance>()

    override val requirementChildren: Collection<ActionInstance> get() = allActions
    override val validateRequirementConflicts = true

    init {
        validateParallelChildren(allActions)
    }

    override fun start() {
        running.clear()
        // Track each child before starting it, so stop() finds it if its start
        // cancels this group, and start none once the group is stopped.
        deadline = deadlineAction
        deadlineAction.doStart()
        for(child in actions) {
            if(deadline == null) return
            running.add(child)
            child.doStart()
        }
    }

    override fun canContinue() = deadline != null

    override fun tick() {
        val activeDeadline = deadline ?: return
        if(activeDeadline.tickOrStop().stopped()) {
            deadline = null
            stopRunningChildren()
            return
        }
        // A child may stop this whole group from its own tick or stop hook,
        // which clears running mid-iteration. Walk a snapshot and skip any
        // child that is no longer running.
        for(child in running.toList()) {
            if(child !in running) continue
            if(child.tickOrStop().stopped()) running.remove(child)
        }
    }

    override fun stop(interrupted: Boolean) {
        val active = listOfNotNull(deadline) + running
        deadline = null
        running.clear()
        stopChildren(active, interrupted)
    }

    override fun debugChildren() =
        listOfNotNull(deadline) + running

    private fun stopRunningChildren(interrupted: Boolean = true) {
        val active = running.toList()
        running.clear()
        stopChildren(active, interrupted)
    }
}
