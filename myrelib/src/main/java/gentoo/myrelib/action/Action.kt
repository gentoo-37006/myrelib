package gentoo.myrelib.action

/**
 * A highly configurable [Action] that the robot can perform.
 *
 * An [Action] instance is single-use, and will be discarded after the action completes.
 *
 * The action must be defined through an [ActionType]. The [ActionType] will then
 * be used to create an instance of this class, which will be run by the robot.
 */
abstract class Action : ActionInstance {
    private var startTime = -1.0
    // A resumed instance has a new run, even if an earlier callback is still returning.
    private var activeRun: Any? = null

    /** seconds */
    protected open var timeout = -1.0
    private var onStartCallback = Instant {}
    private var onStopCallback = Instant {}
    private val additionalRequirements = linkedSetOf<Any>()
    private var configuredPriority: ActionPriority? = null
    private var configuredInterruptedBehavior: InterruptedBehavior? = null
    private var configuredBlockedBehavior: BlockedBehavior? = null
    private var configuredConflictBehavior: ConflictBehavior? = null
    internal var stoppingNaturally = false
        private set

    /** Requirements inherent to this action. Group actions override this to aggregate children. */
    protected open val requirements: Set<Any> = emptySet()

    /** Priority inherent to this action. Groups default to their highest-priority child. */
    protected open val actionPriority: ActionPriority = ActionPriority.DEFAULT

    protected open val actionInterruptedBehavior: InterruptedBehavior = InterruptedBehavior.END

    protected open val actionBlockedBehavior: BlockedBehavior = BlockedBehavior.CANCEL

    protected open val actionConflictBehavior: ConflictBehavior = ConflictBehavior.OVERRIDE

    final override val requires: Set<Any>
        get() = requirements + additionalRequirements

    final override val priority: ActionPriority
        get() = configuredPriority ?: actionPriority

    final override val interruptedBehavior: InterruptedBehavior
        get() = configuredInterruptedBehavior ?: actionInterruptedBehavior

    final override val blockedBehavior: BlockedBehavior
        get() = configuredBlockedBehavior ?: actionBlockedBehavior

    final override val conflictBehavior: ConflictBehavior
        get() = configuredConflictBehavior ?: actionConflictBehavior

    fun requiring(vararg requirements: Any): Action {
        additionalRequirements.addAll(requirements)
        return this
    }

    fun withPriority(priority: ActionPriority): Action {
        configuredPriority = priority
        return this
    }

    fun withInterruptedBehavior(behavior: InterruptedBehavior): Action {
        configuredInterruptedBehavior = behavior
        return this
    }

    fun withBlockedBehavior(behavior: BlockedBehavior): Action {
        configuredBlockedBehavior = behavior
        return this
    }

    fun withConflictBehavior(behavior: ConflictBehavior): Action {
        configuredConflictBehavior = behavior
        return this
    }

    fun timeout(seconds: Double): Action {
        timeout = seconds
        return this
    }

    fun onStart(callback: Instant): Action {
        onStartCallback = callback
        return this
    }

    fun onStop(callback: Instant): Action {
        onStopCallback = callback
        return this
    }

    final override fun doStart() {
        val run = Any()
        activeRun = run
        startTime = currentTime()
        onStartCallback.run()
        if(activeRun === run) {
            start()
        }
    }

    final override fun tickOrStop(): ActionInstance.Status {
        val run = activeRun ?: return ActionInstance.Status.STOPPED
        val continueRunning = canContinue()
        if(activeRun !== run) return currentStatus()
        val expired = continueRunning && timedOut()
        if(activeRun !== run) return currentStatus()

        if(!continueRunning || expired) {
            // Both are terminal exits, not scheduler overrides that could suspend this instance.
            stoppingNaturally = true
            try {
                doStop(interrupted = expired)
            }
            finally {
                stoppingNaturally = false
            }
        }
        else {
            tick()
        }
        return currentStatus()
    }

    final override fun doStop(interrupted: Boolean) {
        if(activeRun == null) return
        activeRun = null
        var failure: Throwable? = null
        try {
            onStopCallback.run()
        }
        catch(callbackFailure: Throwable) {
            failure = callbackFailure
        }
        try {
            stop(interrupted)
        }
        catch(stopFailure: Throwable) {
            if(failure == null) throw stopFailure
            if(failure !== stopFailure) {
                failure.addSuppressed(stopFailure)
            }
        }
        failure?.let { throw it }
    }

    private fun currentStatus() = if(activeRun == null) {
        ActionInstance.Status.STOPPED
    }
    else {
        ActionInstance.Status.RUNNING
    }

    /**
     * called once when the action is started
     */
    protected open fun start() {
    }

    /**
     * @return whether the action has exceeded its time limit
     */
    protected open fun timedOut(): Boolean {
        if(timeout < 0) {
            return false
        }
        return currentTime() - startTime >= timeout
    }

    /**
     * @return true if the action should continue to run and false if the action has completed
     */
    protected open fun canContinue(): Boolean {
        return true
    }

    /**
     * called every tick, after [Action.canContinue]
     */
    protected open fun tick() {
    }

    /**
     * Called once when the action stops.
     *
     * @param interrupted whether it was stopped externally rather than finishing naturally
     */
    protected open fun stop(interrupted: Boolean) {
    }

    protected fun startTime(): Double {
        return startTime
    }

    protected fun currentTime(): Double {
        return System.nanoTime() * 1e-9
    }
}
