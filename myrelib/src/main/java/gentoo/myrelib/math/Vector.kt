package gentoo.myrelib.math

import kotlin.math.cos
import kotlin.math.sin

/**
 * A free (positionless) [Vector] with a magnitude and a direction.
 *
 * A direction of zero degrees points straight up, towards positive y.
 *
 * @param magnitude inches
 * @param direction degrees
 */
data class Vector(@JvmField val magnitude: Double, @JvmField val direction: Double) {
    constructor() : this(0.0, 0.0)

    val x: Double
        get() = magnitude * sin(Math.toRadians(direction))

    val y: Double
        get() = magnitude * cos(Math.toRadians(direction))

    fun dotProduct(target: Vector): Double {
        return (target.x * this.x) + (target.y * this.y)
    }
}
