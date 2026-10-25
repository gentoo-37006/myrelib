package gentoo.myrelib.math

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

/**
 * A [Vector]'s direction follows the same convention as a [Pose]'s heading:
 * zero degrees points along +y, and the angle grows clockwise.
 */
class VectorTest {
    @Test
    fun `components follow the clockwise-from-north convention`() {
        assertEquals(0.0, Vector(1.0, 0.0).x, EPSILON)
        assertEquals(1.0, Vector(1.0, 0.0).y, EPSILON)

        assertEquals(1.0, Vector(1.0, 90.0).x, EPSILON)
        assertEquals(0.0, Vector(1.0, 90.0).y, EPSILON)

        assertEquals(0.0, Vector(1.0, 180.0).x, EPSILON)
        assertEquals(-1.0, Vector(1.0, 180.0).y, EPSILON)

        assertEquals(-1.0, Vector(1.0, -90.0).x, EPSILON)
        assertEquals(0.0, Vector(1.0, -90.0).y, EPSILON)
    }

    @Test
    fun `magnitude scales the components`() {
        val vector = Vector(5.0, 45.0)
        assertEquals(5.0 / sqrt(2.0), vector.x, EPSILON)
        assertEquals(5.0 / sqrt(2.0), vector.y, EPSILON)
    }

    @Test
    fun `perpendicular vectors have a zero dot product`() {
        assertEquals(0.0, Vector(3.0, 0.0).dotProduct(Vector(4.0, 90.0)), EPSILON)
        assertEquals(0.0, Vector(3.0, 30.0).dotProduct(Vector(4.0, 120.0)), EPSILON)
    }

    @Test
    fun `parallel vectors multiply their magnitudes`() {
        assertEquals(12.0, Vector(3.0, 25.0).dotProduct(Vector(4.0, 25.0)), EPSILON)
        assertEquals(-12.0, Vector(3.0, 25.0).dotProduct(Vector(4.0, 205.0)), EPSILON)
    }

    @Test
    fun `dot product is commutative`() {
        val a = Vector(2.5, 37.0)
        val b = Vector(6.0, -112.0)
        assertEquals(a.dotProduct(b), b.dotProduct(a), EPSILON)
    }
}
