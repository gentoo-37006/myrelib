package gentoo.myrelib.math

import gentoo.myrelib.math.FieldCoordinateSystem.HALF_FIELD_SIZE
import gentoo.myrelib.math.Pose.Companion.startPose
import gentoo.myrelib.robot.BaseRobot
import java.lang.Math.toRadians
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A [Pose] that represents the robot's x position, y position, and heading.
 *
 * The positive y-axis always points towards the top of the field from the audience's perspective,
 * and the positive x-axis towards the right of the field.
 *
 * The heading is positive clockwise, and negative counter-clockwise. Zero degrees is pointing
 * straight up, parallel to the y-axis.
 *
 * @param x inches
 * @param y inches
 * @param theta degrees
 */
data class Pose(
    @JvmField val x: Double,
    @JvmField val y: Double,
    @JvmField val theta: Double = 0.0
) {
    constructor() : this(0.0, 0.0)

    /**
     * @return the angle of the vector from this pose to the target pose, in degrees,
     * from the perspective of the field
     */
    fun angleTo(target: Pose): Double {
        return Math.toDegrees(atan2(target.x - this.x, target.y - this.y))
    }

    /**
     * @return the difference in theta between this pose and the target pose, in degrees
     */
    fun deltaTheta(target: Pose): Double {
        return normalizeAngle(target.theta - this.theta)
    }

    /**
     * Order does not matter; `A.distanceTo(B) == B.distanceTo(A)`
     *
     * @return the distance (hypotenuse) from this pose to the target pose, in inches
     */
    fun distanceTo(target: Pose): Double {
        return hypot(target.x - this.x, target.y - this.y)
    }

    /**
     * @return a free vector from this pose to the target pose
     */
    fun vectorTo(target: Pose): Vector {
        return Vector(distanceTo(target), angleTo(target))
    }

    /**
     * @return a free vector representing the x and y components of this pose
     */
    fun asVector(): Vector {
        return Vector(hypot(x, y), Math.toDegrees(atan2(x, y)))
    }

    /**
     * @return a new pose that is the result of offsetting this pose by the given distance
     * in the direction of the given angle
     */
    fun offset(distance: Double, angle: Double): Pose {
        return Pose(
            x + distance * sin(toRadians(angle)),
            y + distance * cos(toRadians(angle)),
            theta
        )
    }

    /**
     * @param angle the angle to rotate by
     * @param rotateTheta whether to adjust the Pose theta
     * @return a new pose that is the result of rotating this pose by the given angle
     */
    fun rotate(angle: Double, rotateTheta: Boolean): Pose {
        val radAngle = -toRadians(angle)
        return Pose(
            x * cos(radAngle) - y * sin(radAngle),
            x * sin(radAngle) + y * cos(radAngle),
            if(rotateTheta) normalizeAngle(theta + angle) else theta
        )
    }

    /**
     * @return a new pose with the given x
     */
    fun withX(x: Double): Pose {
        return Pose(x, y, theta)
    }

    /**
     * @return a new pose with the given y
     */
    fun withY(y: Double): Pose {
        return Pose(x, y, theta)
    }

    /**
     * @return a new pose with the given theta
     */
    fun withTheta(theta: Double): Pose {
        return Pose(x, y, theta)
    }

    /**
     * @return a new pose that is the result of mirroring this pose across the x-axis
     */
    fun mirrorX(): Pose {
        return Pose(x, -y, theta.mirrorX())
    }

    /**
     * @return a new pose that is the result of mirroring this pose across the y-axis
     */
    fun mirrorY(): Pose {
        return Pose(-x, y, theta.mirrorY())
    }

    /**
     * @return a new pose that is the result of mirroring this pose across the line y = x
     */
    fun mirrorPosDiagonal(): Pose {
        return Pose(y, x, theta.mirrorPosDiagonal())
    }

    /**
     * @return a new pose that is the result of mirroring this pose across the line y = -x
     */
    fun mirrorNegDiagonal(): Pose {
        return Pose(-y, -x, theta.mirrorNegDiagonal())
    }

    /**
     * @return a new pose that is the result of rotating this pose 180 degrees around the origin (0, 0)
     */
    fun invert(): Pose {
        return Pose(-x, -y, theta.invert())
    }

    /**
     * @return a new pose with the opposite theta angle
     */
    fun reverseTheta(): Pose {
        return Pose(x, y, theta.invert())
    }

    /**
     * @return an identical copy of this pose
     */
    fun copy(): Pose {
        return Pose(x, y, theta)
    }

    override fun toString(): String {
        return "pos: (${"%.2f".format(x)}, ${"%.2f".format(y)}), theta: ${"%.2f".format(theta)}"
    }

    companion object {
        val NORTH_WALL = Vertical(HALF_FIELD_SIZE, Offset.NEG)
        val EAST_WALL = Horizontal(HALF_FIELD_SIZE, Offset.NEG)
        val SOUTH_WALL = Vertical(-HALF_FIELD_SIZE, Offset.POS)
        val WEST_WALL = Horizontal(-HALF_FIELD_SIZE, Offset.POS)

        /**
         * A pose for the robot lined up against a [horizontal] and [vertical] coordinate,
         * such as a wall or a tile seam, facing [heading].
         *
         * Each offset says which side of its coordinate the robot sits on. For
         * example, `startPose(Horizontal(-24.0, Offset.NEG), Vertical(-72.0, Offset.POS), Heading.NORTH)`
         * puts the robot against the bottom wall with its right-hand edge on
         * x = -24, which is `Pose(-24.0 - Robot.HALF_WIDTH, -72.0 + Robot.HALF_LENGTH, 0.0)`.
         */
        fun startPose(
            horizontal: Horizontal,
            vertical: Vertical,
            heading: Heading,
            halfLength: Double = BaseRobot.INSTANCE.HALF_LENGTH,
            halfWidth: Double = BaseRobot.INSTANCE.HALF_WIDTH,
        ): Pose {
            val sideways = heading == Heading.EAST || heading == Heading.WEST
            val reachX = if(sideways) halfLength else halfWidth
            val reachY = if(sideways) halfWidth else halfLength
            return Pose(
                horizontal.x + horizontal.offset.sign * reachX,
                vertical.y + vertical.offset.sign * reachY,
                heading.theta
            )
        }
    }

    /** Which side of a field coordinate the robot sits on in [startPose]. */
    enum class Offset(internal val sign: Int) {
        /** On the negative side, with the robot's edge on the coordinate. */
        NEG(-1),

        /** Centered on the coordinate. */
        NONE(0),

        /** On the positive side, with the robot's edge on the coordinate. */
        POS(1),
    }

    data class Horizontal(val x: Double, val offset: Offset)

    data class Vertical(val y: Double, val offset: Offset)

    /** The way the robot faces, as a myrelib heading: clockwise from +y. */
    enum class Heading(val theta: Double) {
        NORTH(0.0),
        EAST(90.0),
        SOUTH(180.0),
        WEST(-90.0),
    }
}
