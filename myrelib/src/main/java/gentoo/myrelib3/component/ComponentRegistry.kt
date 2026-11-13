package gentoo.myrelib3.component

/** The ordered registry of components owned by one robot instance. */
class ComponentRegistry {
    private val components = ComponentMap()

    /**
     * Registers a component under its configuration key and returns the same
     * concrete reference. A key can be registered only once per robot instance.
     */
    fun <T : Component> register(name: String, component: T): T {
        check(name !in components) { "Component '$name' is already registered" }
        components[name] = component
        return component
    }

    /** Retrieves a registered component by its declaration name. */
    operator fun get(name: String): Component = components[name]
        ?: throw IllegalStateException("Component '$name' is not registered")

    fun tick() {
        for(component in components.values) {
            if(!component.disabled) {
                component.tick()
            }
        }
    }

    /**
     * Tears every component down at the end of an opmode.
     *
     * Unlike [tick] this ignores the disabled flag. Teardown is harmless for a
     * component that never started and keeps the lifecycle contract uniform.
     * Every release is attempted before the first failure is rethrown, with
     * later failures attached as suppressed exceptions.
     */
    fun release() {
        var failure: Throwable? = null
        for(component in components.values) {
            try {
                component.release()
            }
            catch(releaseFailure: Throwable) {
                if(failure == null) {
                    failure = releaseFailure
                }
                else if(failure !== releaseFailure) {
                    failure.addSuppressed(releaseFailure)
                }
            }
        }
        failure?.let { throw it }
    }
}
