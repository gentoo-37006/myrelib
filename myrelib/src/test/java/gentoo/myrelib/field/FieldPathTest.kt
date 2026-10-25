package gentoo.myrelib.field

import gentoo.myrelib.math.Pose
import gentoo.myrelib.pathing.Linear
import gentoo.myrelib.pathing.Path
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
        FieldPoseBridge.begin("Auto", 16.0, 16.0)
        val path = FieldPath(1, listOf(FieldPath.Point(0.0, 0.0)))
        FieldPoseBridge.publishPath(path, 0.5)
        assertEquals(path, FieldPoseBridge.snapshot().path)
        assertEquals(0.5, FieldPoseBridge.snapshot().pathT, 0.0)
        FieldPoseBridge.begin("Teleop", 16.0, 16.0)
        assertNull(FieldPoseBridge.snapshot().path)
        FieldPoseBridge.publishPath(path)
        FieldPoseBridge.stop()
        FieldPoseBridge.publishPath(path)
        assertNull(FieldPoseBridge.snapshot().path)
    }
}
