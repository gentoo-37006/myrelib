package gentoo.myrelib.field

import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class FieldPoseBridgeTest {
    @After
    fun stopFeed() = FieldPoseBridge.stop()

    @Test
    fun `a new opmode clears the old pose and changes session`() {
        FieldPoseBridge.begin("Auto", 16.0, 18.0)
        FieldPoseBridge.publish(24.0, -48.0, 90.0, 1_000_000)
        val first = FieldPoseBridge.snapshot(51_000_000)
        assertEquals(50L, first.ageMs)
        assertEquals(90.0, first.pose!!.heading, 0.0)
        assertEquals(18.0, first.robot!!.length, 0.0)
        FieldPoseBridge.begin("Teleop", 16.0, 16.0)
        val next = FieldPoseBridge.snapshot()
        assertNotEquals(first.sessionId, next.sessionId)
        assertNull(next.pose)
        assertNull(next.ageMs)
        assertTrue(next.active)
    }

    @Test
    fun `disabled or invalid localization stays null instead of becoming the origin`() {
        FieldPoseBridge.begin("Debug", 16.0, 16.0)
        FieldPoseBridge.publish(null, null, null, 0)
        assertNull(FieldPoseBridge.snapshot(0).pose)
        FieldPoseBridge.publish(Double.NaN, 0.0, 0.0, 0)
        assertNull(FieldPoseBridge.snapshot(0).pose)
        val json = JsonParser().parse(FieldPoseBridge.json()).asJsonObject
        assertTrue(json.get("pose").isJsonNull)
        assertEquals(1, json.get("version").asInt)
    }

    @Test
    fun `stopping freezes the last sample and prevents later publications`() {
        FieldPoseBridge.begin("Auto", 16.0, 16.0)
        FieldPoseBridge.publish(1.0, 2.0, 3.0, 0)
        val first = FieldPoseBridge.snapshot(0)
        FieldPoseBridge.stop()
        FieldPoseBridge.publish(4.0, 5.0, 6.0, 10_000_000)
        val stopped = FieldPoseBridge.snapshot(20_000_000)
        assertFalse(stopped.active)
        assertEquals(first.sequence, stopped.sequence)
        assertEquals(first.pose, stopped.pose)
        assertEquals(20L, stopped.ageMs)
    }
}
