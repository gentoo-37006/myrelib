package gentoo.myrelib.component

/** A map of components that guarantees the ActionController component will always iterate first. */
class ComponentMap {
    private var actionController: Pair<String, ActionController>? = null
    private val components = linkedMapOf<String, Component>()

    operator fun set(name: String, component: Component) {
        if(component is ActionController) {
            check(actionController == null) { "Only one component of type ActionController can be added to the map" }
            actionController = name to component
        }
        else {
            components[name] = component
        }
    }

    operator fun contains(name: String): Boolean {
        if(actionController != null && actionController!!.first == name) {
            return true
        }
        return name in components
    }

    operator fun get(name: String): Component? {
        if(actionController != null && actionController!!.first == name) {
            return actionController!!.second
        }
        return components[name]
    }

    internal val entries: List<Pair<String, Component>>
        get() = listOfNotNull(actionController) + components.map { it.key to it.value }

    val values: Collection<Component>
        get() {
            val list = mutableListOf<Component>()
            actionController?.let { list.add(it.second) }
            list.addAll(components.values)
            return list
        }
}