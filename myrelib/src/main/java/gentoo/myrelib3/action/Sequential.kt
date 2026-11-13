package gentoo.myrelib3.action

import java.util.ArrayDeque

/** Runs each child to completion before starting the next child. */
class Sequential(vararg actions: ActionInstance) : GroupAction() {
    private val actions = actions.toList()
    private val pending = ArrayDeque<ActionInstance>()
    private var current: ActionInstance? = null
    val currentAction: ActionInstance? get() = current
    var completedCount = 0
        private set

    override val requirementChildren: Collection<ActionInstance> get() = actions

    override fun start() {
        pending.clear()
        pending.addAll(actions)
        current = null
        completedCount = 0
        startNext()
    }

    override fun canContinue() = current != null

    override fun tick() {
        val action = current ?: return
        if(action.tickOrStop().stopped()) {
            if(current !== action) return
            current = null
            completedCount++
            startNext()
        }
    }

    override fun stop(interrupted: Boolean) {
        val action = current
        current = null
        pending.clear()
        action?.doStop(interrupted)
    }

    override fun debugChildren() = listOfNotNull(current)

    private fun startNext() {
        // Track the child before starting it: if its start cancels this group,
        // stop() has to find it to stop it.
        val next = pending.pollFirst()
        current = next
        next?.doStart()
    }
}
