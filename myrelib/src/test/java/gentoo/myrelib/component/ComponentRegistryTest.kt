package gentoo.myrelib.component

import gentoo.myrelib.action.Action
import gentoo.myrelib.action.actionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

private class FakeComponent(
    private val name: String,
    private val log: MutableList<String>,
    disabled: Boolean = false,
    private val releaseFailure: Throwable? = null,
) : Component(disabled) {
    var ticks = 0
        private set
    var releases = 0
        private set

    override fun tick() {
        ticks++
        log.add(name)
    }

    override fun release() {
        releases++
        log.add("$name:release")
        releaseFailure?.let { throw it }
    }
}

class ComponentRegistryTest {
    private val log = mutableListOf<String>()

    @Test
    fun `every enabled component is ticked, in order`() {
        val first = FakeComponent("first", log)
        val second = FakeComponent("second", log)

        val registry = ComponentRegistry()
        registry.register("first", first)
        registry.register("second", second)
        registry.tick()

        assertEquals(listOf("first", "second"), log)
    }

    @Test
    fun `a disabled component is not ticked`() {
        val enabled = FakeComponent("enabled", log)
        val disabled = FakeComponent("disabled", log, disabled = true)

        val registry = ComponentRegistry()
        registry.register("enabled", enabled)
        registry.register("disabled", disabled)
        registry.tick()

        assertEquals(listOf("enabled"), log)
        assertEquals(0, disabled.ticks)
    }

    @Test
    fun `register keeps the concrete reference and adds it to the loop`() {
        val registry = ComponentRegistry()
        val component = FakeComponent("component", log)
        val name = "component"

        val registered = registry.register(name, component)
        registry.tick()

        assertEquals(component, registered)
        assertEquals(component, registry[name])
        assertEquals(1, component.ticks)
    }

    @Test
    fun `a key cannot be registered twice`() {
        val registry = ComponentRegistry()
        val name = "component"
        registry.register(name, FakeComponent("first", log))

        assertThrows(IllegalStateException::class.java) {
            registry.register(name, FakeComponent("second", log))
        }
    }

    @Test
    fun `looking up an unregistered key fails clearly`() {
        val registry = ComponentRegistry()
        assertThrows(IllegalStateException::class.java) { registry["missing"] }
    }

    @Test
    fun `actions are ticked before the components they command`() {
        // an action that sets a motor power has to run before the component
        // applies it, otherwise every action lands a loop late
        val controller = ActionController(disabled = false)
        val logging by actionType {
            object : Action() {
                override fun canContinue() = true
                override fun tick() {
                    log.add("action")
                }
            }
        }
        controller.submit(logging)

        ComponentRegistry().apply {
            register("component", FakeComponent("component", log))
            register("actions", controller)
        }.tick()

        assertEquals(listOf("action", "component"), log)
    }

    @Test
    fun `a disabled action controller still ticks without running anything`() {
        // the registry applies the same immutable disabled gate to every component
        val controller = ActionController(disabled = true)
        val logging by actionType {
            object : Action() {
                override fun canContinue() = true
                override fun tick() {
                    log.add("action")
                }
            }
        }
        controller.submit(logging)

        ComponentRegistry().apply {
            register("component", FakeComponent("component", log))
            register("actions", controller)
        }.tick()

        assertEquals(listOf("component"), log)
    }

    @Test
    fun `a registry with no action controller is fine`() {
        ComponentRegistry().apply {
            register("component", FakeComponent("component", log))
        }.tick()
        assertEquals(listOf("component"), log)
    }

    @Test
    fun `an empty registry is fine`() {
        ComponentRegistry().tick()
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `releasing tears every component down once`() {
        val first = FakeComponent("first", log)
        val second = FakeComponent("second", log)
        val registry = ComponentRegistry().apply {
            register("first", first)
            register("second", second)
        }

        registry.release()

        assertEquals(1, first.releases)
        assertEquals(1, second.releases)
    }

    @Test
    fun `releasing tears down disabled components too`() {
        val disabled = FakeComponent("disabled", log, disabled = true)

        ComponentRegistry().apply {
            register("disabled", disabled)
        }.release()

        assertEquals(1, disabled.releases)
    }

    @Test
    fun `release attempts every component and preserves failures in order`() {
        val firstFailure = IllegalStateException("first release failed")
        val laterFailure = AssertionError("disabled release failed")
        val first = FakeComponent("first", log, releaseFailure = firstFailure)
        val disabled = FakeComponent("disabled", log, disabled = true, releaseFailure = laterFailure)
        val last = FakeComponent("last", log)
        val registry = ComponentRegistry().apply {
            register("first", first)
            register("disabled", disabled)
            register("last", last)
        }

        val failure = assertThrows(IllegalStateException::class.java) { registry.release() }

        assertEquals(listOf("first:release", "disabled:release", "last:release"), log)
        listOf(first, disabled, last).forEach { assertEquals(1, it.releases) }
        assertSame(firstFailure, failure)
        assertEquals(listOf(laterFailure), failure.suppressed.toList())
    }

    @Test
    fun `rethrowing the same release failure does not abort remaining cleanup`() {
        val sharedFailure = IllegalStateException("shared hardware failure")
        val registry = ComponentRegistry().apply {
            register("first", FakeComponent("first", log, releaseFailure = sharedFailure))
            register("second", FakeComponent("second", log, releaseFailure = sharedFailure))
            register("last", FakeComponent("last", log))
        }

        val failure = assertThrows(IllegalStateException::class.java) { registry.release() }

        assertSame(sharedFailure, failure)
        assertEquals(emptyList<Throwable>(), failure.suppressed.toList())
        assertEquals(listOf("first:release", "second:release", "last:release"), log)
    }

    @Test
    fun `a component that never ticked can still be released`() {
        val component = FakeComponent("component", log)
        ComponentRegistry().apply {
            register("component", component)
        }.release()
        assertEquals(0, component.ticks)
        assertEquals(1, component.releases)
    }

    @Test
    fun `releasing an empty list is fine`() {
        ComponentRegistry().release()
    }

    @Test
    fun `ticking repeatedly keeps ticking each component once`() {
        val component = FakeComponent("component", log)
        val registry = ComponentRegistry().apply {
            register("component", component)
        }

        repeat(5) { registry.tick() }

        assertEquals(5, component.ticks)
    }
}
