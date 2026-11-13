package gentoo.myrelib3.action

/**
 * When an [ActionType] is run, a new [ActionInstance] instance is created. This way, the
 * variables contained within each action can be modified without influencing future instances.
 */
interface ActionInstance {
    /** Objects this action needs exclusive access to while it is running. */
    val requires: Set<Any>
        get() = emptySet()

    /** One of three deliberately coarse scheduling priorities. */
    val priority: ActionPriority
        get() = ActionPriority.DEFAULT

    /** What happens when a higher/equal-priority action overrides this action. */
    val interruptedBehavior: InterruptedBehavior
        get() = InterruptedBehavior.END

    /** What happens when this action is blocked by a higher-priority action. */
    val blockedBehavior: BlockedBehavior
        get() = BlockedBehavior.CANCEL

    /** What this action does when it meets an equal-priority conflict. */
    val conflictBehavior: ConflictBehavior
        get() = ConflictBehavior.OVERRIDE

    /**
     * called once when the action is started
     */
    fun doStart()

    /**
     * ticks the action
     * @return if the action is still running or if it stopped
     */
    fun tickOrStop(): Status

    /**
     * Called once when the action stops.
     *
     * @param interrupted whether it was stopped externally rather than finishing naturally
     */
    fun doStop(interrupted: Boolean)

    /**
     * @return lines that should be added to the logger
     */
    fun getDebugLines(): MutableList<String> {
        val list = mutableListOf<String>()
        list.add("-> ${this.javaClass.simpleName}")
        return list
    }

    enum class Status {
        RUNNING,
        STOPPED;

        fun stopped(): Boolean {
            return this == STOPPED
        }

        fun running(): Boolean {
            return this == RUNNING
        }
    }
}

enum class ActionPriority {
    LOW,
    DEFAULT,
    HIGH,
}

enum class InterruptedBehavior {
    END,
    SUSPEND,
}

enum class BlockedBehavior {
    CANCEL,
    QUEUE,
}

enum class ConflictBehavior {
    CANCEL,
    QUEUE,
    OVERRIDE,
}
