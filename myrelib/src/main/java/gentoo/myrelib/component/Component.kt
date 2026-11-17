package gentoo.myrelib.component

import gentoo.myrelib.hardware.HardwareAccess

/** Persistent mechanism state with a repeatable hardware attachment lifecycle. */
abstract class Component {
    protected val hardware: HardwareAccess
    var disabled: Boolean
        private set
    private var attached = false
    private var initialized = false

    constructor() {
        hardware = HardwareAccess.componentScope()
        disabled = true
    }

    // Standalone construction remains available for low-level integrations and tests.
    constructor(disabled: Boolean) : this(HardwareAccess.empty(), disabled)
    constructor(hardware: HardwareAccess, disabled: Boolean) {
        this.hardware = hardware.forComponent(disabled)
        this.disabled = disabled
    }

    internal fun attach(source: HardwareAccess, disabled: Boolean) {
        check(!attached) { "Component is already attached" }
        hardware.attach(source, disabled)
        this.disabled = disabled
        attached = true // Includes partially attached components in rollback.
        if(!disabled) {
            onAttach()
            if(!initialized) {
                onInitialize()
                initialized = true
            }
        }
    }

    internal fun detach() {
        if(!attached) return
        attached = false
        try { onDetach() }
        finally {
            disabled = true
            hardware.revoke()
        }
    }

    /** Resolve devices, configure and refresh measurements. Do not implicitly zero encoders. */
    protected open fun onAttach() {}

    /** One-time setup on the first enabled INIT, after hardware attachment. Never runs at declaration. */
    protected open fun onInitialize() {}

    /** Called once at PLAY for enabled components. */
    protected open fun onStart() {}

    internal fun startSession() { if(!disabled) onStart() }

    /** Release this run's handles/tasks; ordinary fields survive. Called even if disabled. */
    protected open fun onDetach() { release() }

    abstract fun tick()

    /** Legacy cleanup hook, also called by the default [onDetach]. Must support later attachment. */
    open fun release() {}
}
