package gentoo.myrelib3.component

import gentoo.myrelib.action.Action
import gentoo.myrelib.action.ActionInstance
import gentoo.myrelib.action.ActionPriority
import gentoo.myrelib.action.ActionType
import gentoo.myrelib.action.BlockedBehavior
import gentoo.myrelib.action.ConflictBehavior
import gentoo.myrelib.action.InterruptedBehavior
import gentoo.myrelib.component.Component
import gentoo.myrelib.hardware.HardwareAccess

class ActionController(
    hardware: HardwareAccess = HardwareAccess.empty(),
    disabled: Boolean
) : Component(hardware, disabled) {
    private data class ManagedAction(
        val type: ActionType,
        val instance: ActionInstance,
        val requirements: Set<Any> = instance.requires.toSet(),
        val priority: ActionPriority = instance.priority,
        val interruptedBehavior: InterruptedBehavior = instance.interruptedBehavior,
        val blockedBehavior: BlockedBehavior = instance.blockedBehavior,
        val conflictBehavior: ConflictBehavior = instance.conflictBehavior,
    )

    private val running = linkedMapOf<ActionType, ManagedAction>()
    private val staged = linkedMapOf<ActionType, ManagedAction>()
    private val terminating = mutableSetOf<ActionType>()
    private var promoting = false
    private var removingAll = false
    private var overrideDepth = 0

    val runningActions: Map<ActionType, ActionInstance>
        get() = running.mapValues { it.value.instance }

    override fun tick() {
        for((type, action) in running.entries.toList()) {
            if(running[type] !== action) {
                continue
            }
            if(action.instance.tickOrStop().stopped()) {
                if(running[type] === action) {
                    running.remove(type)
                }
            }
        }
        promoteStaged()
    }

    /**
     * Only one instance of an [ActionType] may be running, queued, or suspended.
     */
    internal fun submit(action: ActionType) {
        submit(action) { action.createNew() }
    }

    /**
     * Only one instance of an [ActionType] may be running, queued, or suspended.
     * @param timeout in seconds
     */
    internal fun submit(action: ActionType, timeout: Double) {
        submit(action, timeout) { action.createNew() }
    }

    internal fun submit(action: ActionType, timeout: Double, factory: () -> ActionInstance) {
        submit(action) {
            factory().also {
                if(it is Action) {
                    it.timeout(timeout)
                }
            }
        }
    }

    internal fun remove(action: ActionType) {
        staged.remove(action)
        running.remove(action)?.let { active ->
            terminating.add(action)
            try {
                active.instance.doStop(true)
            }
            finally {
                terminating.remove(action)
            }
        }
        promoteStaged()
    }

    internal fun removeAll() {
        if(removingAll) return
        removingAll = true
        try {
            val active = running.values.map { it.instance }
            running.clear()
            staged.clear()
            // Stop hooks may submit work or recursively request bulk removal.
            // Keep new work blocked until the outermost removal has finished.
            // One failing stop must not leave the actions after it running, so
            // stop them all, then rethrow the first failure with the rest
            // attached, as component release does.
            var failure: Throwable? = null
            for(action in active) {
                try {
                    action.doStop(true)
                }
                catch(stopFailure: Throwable) {
                    if(failure == null) {
                        failure = stopFailure
                    }
                    else if(failure !== stopFailure) {
                        failure.addSuppressed(stopFailure)
                    }
                }
            }
            failure?.let { throw it }
        }
        finally {
            removingAll = false
        }
    }

    internal fun isRunning(action: ActionType): Boolean {
        return running.containsKey(action)
    }

    internal fun isStaged(action: ActionType): Boolean {
        return staged.containsKey(action)
    }

    internal fun submit(action: ActionType, factory: () -> ActionInstance) {
        if(
            disabled || removingAll ||
            running.containsKey(action) ||
            staged.containsKey(action) ||
            terminating.contains(action)
        ) {
            return
        }

        schedule(ManagedAction(action, factory()))
    }

    private fun schedule(incoming: ManagedAction) {
        val conflicts = conflictsWith(incoming)
        if(conflicts.isEmpty()) {
            startNow(incoming)
            return
        }

        val highestPriority = conflicts.maxOf { it.priority }
        when {
            incoming.priority > highestPriority -> override(conflicts, incoming)
            incoming.priority < highestPriority -> when(incoming.blockedBehavior) {
                BlockedBehavior.CANCEL -> Unit
                BlockedBehavior.QUEUE -> stage(incoming)
            }

            else -> when(incoming.conflictBehavior) {
                ConflictBehavior.CANCEL -> Unit
                ConflictBehavior.QUEUE -> stage(incoming)
                ConflictBehavior.OVERRIDE -> override(conflicts, incoming)
            }
        }
    }

    private fun override(conflicts: Collection<ManagedAction>, incoming: ManagedAction) {
        // A stop hook can remove another action and request staged promotion.
        // Hold that promotion until the replacement has claimed its requirements,
        // including when a hook starts another, nested override.
        overrideDepth++
        try {
            conflicts.forEach(::interrupt)

            // Stop hooks may schedule actions, so only start once the requirements
            // are genuinely free. Apply the incoming action's normal policy again
            // if a callback installed a new conflict.
            if(!running.containsKey(incoming.type) && !staged.containsKey(incoming.type)) {
                schedule(incoming)
            }
        }
        finally {
            overrideDepth--
        }
        promoteStaged()
    }

    private fun interrupt(action: ManagedAction) {
        if(running[action.type] !== action) return

        running.remove(action.type)
        if(action.interruptedBehavior == InterruptedBehavior.SUSPEND) {
            stage(action)
        }
        else {
            terminating.add(action.type)
        }

        try {
            action.instance.doStop(true)
        }
        finally {
            terminating.remove(action.type)
        }
    }

    private fun conflictsWith(action: ManagedAction): List<ManagedAction> =
        running.values.filter { current ->
            current.type != action.type &&
                !(current.instance is Action && current.instance.stoppingNaturally) &&
                current.requirements.any(action.requirements::contains)
        }

    private fun startNow(action: ManagedAction) {
        running[action.type] = action
        action.instance.doStart()
    }

    private fun stage(action: ManagedAction) {
        staged[action.type] = action
    }

    private fun promoteStaged() {
        if(overrideDepth > 0 || promoting || staged.isEmpty()) return
        promoting = true
        try {
            // A fixed priority order prevents submission timing from giving a
            // low-priority staged action precedence over a high-priority one.
            val candidates = staged.values.sortedByDescending { it.priority }
            candidates.forEach { action ->
                if(staged[action.type] !== action || conflictsWith(action).isNotEmpty()) {
                    return@forEach
                }

                staged.remove(action.type)
                running[action.type] = action
                action.instance.doStart()
            }
        }
        finally {
            promoting = false
        }
    }
}
