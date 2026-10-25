package gentoo.myrelib.action

import java.util.Collections
import java.util.IdentityHashMap
import java.util.function.Supplier

/** Shared teardown and debug-tree behavior for actions that own child actions. */
abstract class GroupAction : Action() {
    private val childrenAlreadyStarted = Collections.newSetFromMap(
        IdentityHashMap<ActionInstance, Boolean>()
    )

    /** Every child whose scheduler contract is represented by this group. */
    protected abstract val requirementChildren: Collection<ActionInstance>

    /** Parallel-style groups enable this to enforce exclusive child ownership. */
    protected open val validateRequirementConflicts: Boolean = false

    protected abstract fun debugChildren(): Collection<ActionInstance>

    /** Clear active-child bookkeeping first, then stop every child before reporting failures. */
    protected fun stopChildren(children: Collection<ActionInstance>, interrupted: Boolean) {
        var failure: Throwable? = null
        for(child in children) {
            try {
                child.doStop(interrupted)
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

    final override val requirements: Set<Any>
        get() {
            if(validateRequirementConflicts) {
                validateParallelChildren(requirementChildren)
            }
            return requirementChildren.flatMapTo(linkedSetOf()) { it.requires }
        }

    override val actionPriority: ActionPriority
        get() = requirementChildren.maxOfOrNull { it.priority } ?: ActionPriority.DEFAULT

    /** Creates and starts a child that has not participated in an earlier run. */
    protected fun Supplier<ActionInstance>.startFresh(): ActionInstance = get().also { child ->
        startFresh(child)
    }

    /** Starts an already-created child while enforcing the single-use group contract. */
    protected fun startFresh(child: ActionInstance) {
        check(childrenAlreadyStarted.add(child)) {
            "Child factory returned an ActionInstance that this group already started"
        }
        child.doStart()
    }

    /** Parallel siblings may not claim the same requirement behind the scheduler's back. */
    protected fun validateParallelChildren(children: Collection<ActionInstance>) {
        val owners = mutableMapOf<Any, ActionInstance>()
        children.forEach { child ->
            child.requires.forEach { requirement ->
                val previous = owners.putIfAbsent(requirement, child)
                require(previous == null) {
                    "Parallel child actions ${previous?.javaClass?.simpleName} and " +
                        "${child.javaClass.simpleName} both require $requirement"
                }
            }
        }
    }

    /** Repeated factories must not change the scheduler contract after submission. */
    protected fun requireMatchingContract(template: ActionInstance, child: ActionInstance) {
        check(child.requires == template.requires && child.priority == template.priority) {
            "Repeated child factories must return actions with stable requirements and priority"
        }
    }

    final override fun getDebugLines(): MutableList<String> =
        mutableListOf("-> ${this.javaClass.simpleName}").also { lines ->
            debugChildren().forEach { child ->
                child.getDebugLines().forEach { line -> lines.add("   $line") }
            }
        }
}
