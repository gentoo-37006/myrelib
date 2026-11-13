package gentoo.myrelib3.component

/** An opmode-thread state machine with optional transition callbacks and per-state ticks. */
class FSM<State : Enum<State>>(initialState: State) {
    constructor(initialState: State, config: FSM<State>.() -> Unit) : this(initialState) {
        config()
    }

    private var transitioning = false

    var state = initialState
        set(value) {
            if(field == value) return
            check(!transitioning) { "Cannot change FSM state inside a transition callback" }
            transitioning = true
            try {
                transition[field to value]?.invoke()
                field = value
            }
            finally {
                transitioning = false
            }
        }
    val transition: MutableMap<Pair<State, State>, () -> Unit> = mutableMapOf()
    val tick: MutableMap<State, () -> Unit> = mutableMapOf()

    fun tick() {
        tick[state]?.invoke()
    }
}
