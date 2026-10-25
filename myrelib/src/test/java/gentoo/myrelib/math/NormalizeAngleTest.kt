package gentoo.myrelib.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [normalizeAngle] returns a heading in `[-180, 180)`.
 */
class NormalizeAngleTest {
    @Test
    fun `leaves angles already in range alone`() {
        assertEquals(0.0, normalizeAngle(0.0), EPSILON)
        assertEquals(90.0, normalizeAngle(90.0), EPSILON)
        assertEquals(-90.0, normalizeAngle(-90.0), EPSILON)
        assertEquals(179.9, normalizeAngle(179.9), EPSILON)
        assertEquals(-180.0, normalizeAngle(-180.0), EPSILON)
    }

    @Test
    fun `maps positive 180 onto negative 180`() {
        assertEquals(-180.0, normalizeAngle(180.0), EPSILON)
    }

    @Test
    fun `wraps angles outside of one revolution`() {
        assertEquals(-90.0, normalizeAngle(270.0), EPSILON)
        assertEquals(90.0, normalizeAngle(-270.0), EPSILON)
        assertEquals(1.0, normalizeAngle(361.0), EPSILON)
        assertEquals(-1.0, normalizeAngle(-361.0), EPSILON)
    }

    @Test
    fun `wraps angles several revolutions out`() {
        assertEquals(45.0, normalizeAngle(45.0 + 360.0 * 5), EPSILON)
        assertEquals(45.0, normalizeAngle(45.0 - 360.0 * 5), EPSILON)
        assertEquals(-180.0, normalizeAngle(360.0 * 3 + 180.0), EPSILON)
    }

    @Test
    fun `always lands in range`() {
        var degrees = -1000.0
        while(degrees <= 1000.0) {
            val normalized = normalizeAngle(degrees)
            assertTrue(
                "normalizeAngle($degrees) returned $normalized, which is out of range",
                normalized >= -180.0 && normalized < 180.0
            )
            degrees += 0.5
        }
    }

    @Test
    fun `is idempotent`() {
        var degrees = -1000.0
        while(degrees <= 1000.0) {
            val once = normalizeAngle(degrees)
            assertEquals(once, normalizeAngle(once), EPSILON)
            degrees += 0.5
        }
    }
}
