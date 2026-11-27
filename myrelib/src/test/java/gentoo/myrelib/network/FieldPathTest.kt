package gentoo.myrelib.network

import gentoo.myrelib.math.Pose
import gentoo.myrelib.pathing.Linear
import gentoo.myrelib.pathing.Path
import gentoo.myrelib.recording.StateSample
import org.junit.Assert.*
import org.junit.Test

class FieldPathTest {
    @Test fun `detached geometry uses centered field coordinates and includes endpoints`() {
        val geometry = FieldPath.from(Path(Linear(Pose(-30.0, 20.0), Pose(40.0, -15.0))).build(), 7)
        assertEquals(7L, geometry.id)
        assertEquals(129, geometry.points.size)
        assertEquals(FieldPath.Point(-30.0, 20.0), geometry.points.first())
        assertEquals(FieldPath.Point(40.0, -15.0), geometry.points.last())
    }

    @Test fun `path feed is cleared between sessions and at stop`() {
        RobotTelemetryBridge.begin("Auto", 16.0, 16.0)
        val path = FieldPath(1, listOf(FieldPath.Point(0.0, 0.0)))
        val sample = StateSample("PLAY", mapOf("motionPath" to mapOf("id" to path.id, "t" to 0.5)), emptyMap())
        RobotTelemetryBridge.publish(sample, path, 0)
        assertEquals(path, RobotTelemetryBridge.snapshot().path)
        assertEquals(0.5, RobotTelemetryBridge.snapshot().pathT, 0.0)
        RobotTelemetryBridge.begin("Teleop", 16.0, 16.0)
        assertNull(RobotTelemetryBridge.snapshot().path)
        RobotTelemetryBridge.publish(sample, path, 0)
        RobotTelemetryBridge.stop()
        RobotTelemetryBridge.publish(sample, path, 0)
        assertNull(RobotTelemetryBridge.snapshot().path)
    }
}
