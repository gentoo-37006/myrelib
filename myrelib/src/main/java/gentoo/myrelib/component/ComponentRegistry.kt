package gentoo.myrelib.component

import gentoo.myrelib.config.ComponentConfig
import gentoo.myrelib.hardware.HardwareAccess
import gentoo.myrelib.util.cleanupAll

/** The ordered registry of components owned by one robot. */
class ComponentRegistry {
    private val components = ComponentMap()
    private var sealed = false
    private var owner: Thread? = null

    /**
     * Registers a component under its configuration key and returns the same
     * concrete reference. A key can be registered only once per robot.
     */
    fun <T : Component> register(name: String, component: T): T {
        check(!sealed) { "Declare robot components before the first session" }
        check(name !in components) { "Component '$name' is already registered" }
        components[name] = component
        return component
    }

    /** Retrieves a registered component by its declaration name. */
    operator fun get(name: String): Component = components[name]
        ?: throw IllegalStateException("Component '$name' is not registered")

    internal fun attach(hardware: HardwareAccess, config: ComponentConfig.Instance) {
        check(owner == null) { "Components are already attached" }
        sealed = true
        owner = Thread.currentThread()
        for((name, component) in components.entries) {
            component.attach(hardware, config.isDisabled(name))
        }
    }

    internal fun start() {
        checkThread()
        components.values.forEach { it.startSession() }
    }

    internal fun detach() {
        if(owner == null) return
        checkThread()
        try { cleanupAll(*components.values.map { component -> { component.detach() } }.toTypedArray()) }
        finally { owner = null }
    }

    private fun checkThread() {
        check(owner == null || owner === Thread.currentThread()) { "Components may only run on the opmode thread" }
    }

    fun tick() {
        checkThread()
        for(component in components.values) {
            if(!component.disabled) {
                component.tick()
            }
        }
    }

    /**
     * Ends a component attachment, revoking hardware even when cleanup fails.
     * A standalone registry instead invokes the legacy release hooks directly.
     * Every cleanup is attempted, preserving later failures as suppressed.
     */
    fun release() {
        if(sealed) detach()
        else cleanupAll(*components.values.map { component -> { component.release() } }.toTypedArray())
    }
}
