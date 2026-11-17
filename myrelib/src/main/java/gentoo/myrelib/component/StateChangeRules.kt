package gentoo.myrelib.component

/** Rules evaluated against the state captured when a changeState block begins. */
class StateChangeRules<State : Enum<State>> internal constructor(private val current: State) {
    internal var destination: State? = null
        private set

    fun from(vararg states: State): Sources {
        require(states.isNotEmpty()) { "A state change rule needs at least one source" }
        return Sources(current in states)
    }

    inner class Sources internal constructor(private val matches: Boolean) {
        infix fun to(state: State) {
            if(matches && destination == null) destination = state
        }
    }
}
