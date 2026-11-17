package gentoo.myrelib.component

import gentoo.myrelib.hardware.HardwareAccess

abstract class StatefulComponent<State : Enum<State>> : Component {
    protected abstract val fsm: FSM<State>

    var state: State
        get() = fsm.state
        protected set(value) {
            if(disabled) return
            fsm.state = value
        }

    constructor(disabled: Boolean) : super(disabled)
    constructor(hardware: HardwareAccess, disabled: Boolean) : super(hardware, disabled)

    /** Applies the first rule matching the current state, after the rules are built. */
    protected fun changeState(config: StateChangeRules<State>.() -> Unit) {
        if(disabled) return

        val rules = StateChangeRules(state).apply(config)
        rules.destination?.let { state = it }
    }

    override fun tick() {
        if(disabled) return
        fsm.tick()
    }
}
