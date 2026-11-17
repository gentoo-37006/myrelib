package gentoo.myrelib.component

import gentoo.myrelib.hardware.HardwareAccess

/**
 * A [Component] of the robot that can be ticked.
 */
abstract class Component {
    protected val hardware: HardwareAccess
    val disabled: Boolean

    constructor(disabled: Boolean) {
        this.hardware = HardwareAccess.empty()
        this.disabled = disabled
    }

    constructor(hardware: HardwareAccess, disabled: Boolean) {
        this.hardware = hardware.forComponent(disabled)
        this.disabled = disabled
    }

    abstract fun tick()

    /**
     * Called once when the opmode ends, for components holding something that
     * outlives it: a camera that polls in the background, a thread, a stream.
     *
     * Deliberately not called stop. Components already use that for "stop the
     * mechanism", called from an action mid-match, which is a different thing
     * from letting go at the end of an opmode.
     *
     * Runs even if the component is [disabled]. It must tolerate being called
     * when the component never started.
     */
    open fun release() {
    }
}
