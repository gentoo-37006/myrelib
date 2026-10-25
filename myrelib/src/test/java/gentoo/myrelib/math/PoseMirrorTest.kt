package gentoo.myrelib.math

import org.junit.Test

/**
 * Reflection helpers remain useful for geometry, while the alliance transform is a
 * 180-degree inversion through the field center.
 */
class PoseMirrorTest {
    private val samples = listOf(
        Pose(0.0, 0.0, 0.0),
        Pose(12.0, -34.0, 45.0),
        Pose(-7.5, 60.25, -170.0),
        Pose(48.0, 48.0, 90.0),
        Pose(-3.0, 1.0, 179.0)
    )

    @Test
    fun `mirrorX reflects across the x axis`() {
        assertPoseEquals(Pose(5.0, -7.0, 180.0), Pose(5.0, 7.0, 0.0).mirrorX())
        // east is unchanged by a reflection across a horizontal axis
        assertPoseEquals(Pose(5.0, -7.0, 90.0), Pose(5.0, 7.0, 90.0).mirrorX())
        assertPoseEquals(Pose(5.0, -7.0, 135.0), Pose(5.0, 7.0, 45.0).mirrorX())
    }

    @Test
    fun `mirrorY reflects across the y axis`() {
        // north is unchanged by a reflection across a vertical axis
        assertPoseEquals(Pose(-5.0, 7.0, 0.0), Pose(5.0, 7.0, 0.0).mirrorY())
        assertPoseEquals(Pose(-5.0, 7.0, -90.0), Pose(5.0, 7.0, 90.0).mirrorY())
        assertPoseEquals(Pose(-5.0, 7.0, -45.0), Pose(5.0, 7.0, 45.0).mirrorY())
    }

    @Test
    fun `mirrorPosDiagonal reflects across the line y equals x`() {
        assertPoseEquals(Pose(7.0, 5.0, 90.0), Pose(5.0, 7.0, 0.0).mirrorPosDiagonal())
        assertPoseEquals(Pose(7.0, 5.0, 0.0), Pose(5.0, 7.0, 90.0).mirrorPosDiagonal())
        // 45 degrees lies on the mirror line, so it is a fixed point
        assertPoseEquals(Pose(7.0, 5.0, 45.0), Pose(5.0, 7.0, 45.0).mirrorPosDiagonal())
        assertPoseEquals(Pose(7.0, 5.0, -90.0), Pose(5.0, 7.0, 180.0).mirrorPosDiagonal())
    }

    @Test
    fun `mirrorNegDiagonal reflects across the line y equals minus x`() {
        assertPoseEquals(Pose(-7.0, -5.0, -90.0), Pose(5.0, 7.0, 0.0).mirrorNegDiagonal())
        assertPoseEquals(Pose(-7.0, -5.0, 180.0), Pose(5.0, 7.0, 90.0).mirrorNegDiagonal())
        // -45 degrees lies on the mirror line, so it is a fixed point
        assertPoseEquals(Pose(-7.0, -5.0, -45.0), Pose(5.0, 7.0, -45.0).mirrorNegDiagonal())
    }

    @Test
    fun `every mirror is its own inverse`() {
        for(pose in samples) {
            assertPoseEquals(pose, pose.mirrorX().mirrorX())
            assertPoseEquals(pose, pose.mirrorY().mirrorY())
            assertPoseEquals(pose, pose.mirrorPosDiagonal().mirrorPosDiagonal())
            assertPoseEquals(pose, pose.mirrorNegDiagonal().mirrorNegDiagonal())
        }
    }

    @Test
    fun `inversion rotates position and heading 180 degrees`() {
        assertPoseEquals(Pose(-5.0, -7.0, -180.0), Pose(5.0, 7.0, 0.0).invert())
        assertPoseEquals(Pose(5.0, -7.0, -90.0), Pose(-5.0, 7.0, 90.0).invert())
        assertPoseEquals(Pose(-5.0, 7.0, -135.0), Pose(5.0, -7.0, 45.0).invert())
    }

    @Test
    fun `inversion is its own inverse`() {
        for(pose in samples) {
            assertPoseEquals(pose, pose.invert().invert())
        }
    }

    @Test
    fun `mirroring preserves relative geometry between two poses`() {
        val a = Pose(10.0, 20.0, 30.0)
        val b = Pose(-40.0, 5.0, -60.0)
        // a reflection is an isometry, so distances survive it
        val mirrors = listOf(
            a.mirrorY() to b.mirrorY(),
            a.mirrorX() to b.mirrorX(),
            a.mirrorPosDiagonal() to b.mirrorPosDiagonal(),
            a.mirrorNegDiagonal() to b.mirrorNegDiagonal()
        )
        for((mirroredA, mirroredB) in mirrors) {
            org.junit.Assert.assertEquals(
                a.distanceTo(b),
                mirroredA.distanceTo(mirroredB),
                1e-9
            )
        }
    }

    @Test
    fun `inversion swaps blue and red diagonally opposite starting corners`() {
        val blueBottomCorner = Pose(-60.0, -58.0, 0.0)
        val redTopCorner = Pose(60.0, 58.0, 180.0)
        val blueTopCorner = Pose(-60.0, 58.0, 180.0)
        val redBottomCorner = Pose(60.0, -58.0, 0.0)

        assertPoseEquals(
            redTopCorner,
            blueBottomCorner.invert()
        )
        assertPoseEquals(
            redBottomCorner,
            blueTopCorner.invert()
        )
    }
}
