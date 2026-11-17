package gentoo.myrelib.action

/** Runs every child together and finishes once every child has finished. */
class Parallel(vararg actions: ActionInstance) : GroupAction() {
    private val actions = actions.toList()
    private val running = mutableListOf<ActionInstance>()

    override val requirementChildren: Collection<ActionInstance> get() = actions
    override val validateRequirementConflicts = true

    init {
        validateParallelChildren(this.actions)
    }

    override fun start() {
        running.clear()
        for(child in actions) {
            // Track each child before starting it, so stop() finds it if its
            // start cancels this group, and start none once the group is stopped.
            running.add(child)
            child.doStart()
            if(child !in running) return
        }
    }

    override fun canContinue() = running.isNotEmpty()

    override fun tick() {
        // A child may stop this whole group from its own tick or stop hook,
        // which clears running mid-iteration. Walk a snapshot and skip any
        // child that is no longer running.
        for(child in running.toList()) {
            if(child !in running) continue
            if(child.tickOrStop().stopped()) running.remove(child)
        }
    }

    override fun stop(interrupted: Boolean) {
        val active = running.toList()
        running.clear()
        stopChildren(active, interrupted)
    }

    override fun debugChildren(): Collection<ActionInstance> = running
}
