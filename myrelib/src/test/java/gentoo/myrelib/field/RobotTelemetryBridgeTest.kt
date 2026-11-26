package gentoo.myrelib.field

import com.google.gson.JsonParser
import gentoo.myrelib.math.Pose
import gentoo.myrelib.recording.MatchRecorder
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RobotTelemetryBridgeTest {
    private fun publish(pose: Pose?, nowNanos: Long) {
        val sender = MatchRecorder(clock = { nowNanos })
        sender.addState("pose") { pose }
        sender.emit("INIT", force = true, record = false) { sample, now ->
            RobotTelemetryBridge.publish(sample, null, now)
        }
    }

    @After
    fun stopFeed() = RobotTelemetryBridge.stop()

    @Test
    fun `a new opmode clears the old pose and changes session`() {
        RobotTelemetryBridge.begin("Auto", 16.0, 18.0)
        publish(Pose(24.0, -48.0, 90.0), 1_000_000)
        val first = RobotTelemetryBridge.snapshot(51_000_000)
        assertEquals(50L, first.ageMs)
        assertEquals(90.0, first.pose!!.heading, 0.0)
        assertEquals(18.0, first.robot!!.length, 0.0)
        RobotTelemetryBridge.begin("Teleop", 16.0, 16.0)
        val next = RobotTelemetryBridge.snapshot()
        assertNotEquals(first.sessionId, next.sessionId)
        assertNull(next.pose)
        assertNull(next.ageMs)
        assertTrue(next.active)
    }

    @Test
    fun `disabled or invalid localization stays null instead of becoming the origin`() {
        RobotTelemetryBridge.begin("Debug", 16.0, 16.0)
        publish(null, 0)
        assertNull(RobotTelemetryBridge.snapshot(0).pose)
        publish(Pose(Double.NaN, 0.0, 0.0), 0)
        assertNull(RobotTelemetryBridge.snapshot(0).pose)
        val json = JsonParser().parse(RobotTelemetryBridge.json()).asJsonObject
        assertTrue(json.get("pose").isJsonNull)
        assertEquals(1, json.get("version").asInt)
    }

    @Test
    fun `stopping freezes the last sample and prevents later publications`() {
        RobotTelemetryBridge.begin("Auto", 16.0, 16.0)
        publish(Pose(1.0, 2.0, 3.0), 0)
        val first = RobotTelemetryBridge.snapshot(0)
        RobotTelemetryBridge.stop()
        publish(Pose(4.0, 5.0, 6.0), 10_000_000)
        val stopped = RobotTelemetryBridge.snapshot(20_000_000)
        assertFalse(stopped.active)
        assertEquals(first.sequence, stopped.sequence)
        assertEquals(first.pose, stopped.pose)
        assertEquals(20L, stopped.ageMs)
    }
}
