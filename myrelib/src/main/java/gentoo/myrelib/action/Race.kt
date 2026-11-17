package gentoo.myrelib.action

/** Runs every child together and finishes as soon as any child finishes. */
class Race(vararg actions: ActionInstance) : GroupAction() {
    private val actions = actions.toList()
    private val running = mutableListOf<ActionInstance>()
    private var finished = false

    override val requirementChildren: Collection<ActionInstance> get() = actions
    override val validateRequirementConflicts = true

    init {
        validateParallelChildren(this.actions)
    }

    override fun start() {
        running.clear()
        finished = false
        for(child in actions) {
            // Track each child before starting it, so stop() finds it if its
            // start cancels this group, and start none once the group is stopped.
            running.add(child)
            child.doStart()
            if(finished) return
        }
        finished = running.isEmpty()
    }

    override fun canContinue() = !finished

    override fun tick() {
        for(action in running.toList()) {
            if(action.tickOrStop().stopped()) {
                running.remove(action)
                finishRace()
                return
            }
        }
    }

    override fun stop(interrupted: Boolean) {
        finishRace(interrupted)
    }

    override fun debugChildren(): Collection<ActionInstance> = running

    private fun finishRace(interrupted: Boolean = true) {
        if(finished) return
        finished = true
        val active = running.toList()
        running.clear()
        stopChildren(active, interrupted)
    }
}
