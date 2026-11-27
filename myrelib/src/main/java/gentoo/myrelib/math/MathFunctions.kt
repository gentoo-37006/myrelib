package gentoo.myrelib.math

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit
import org.firstinspires.ftc.robotcore.external.navigation.Pose2D
import java.lang.Math.toDegrees
import java.lang.Math.toRadians
import com.pedropathing.math.Pose as PedroPose

fun PedroPose.toPose(): Pose {
    return Pose(
        x() - CoordinateSystem.HALF_FIELD_SIZE,
        y() - CoordinateSystem.HALF_FIELD_SIZE,
        heading().toPose()
    )
}

fun Pose.toPedro(): PedroPose {
    return PedroPose(
        x + CoordinateSystem.HALF_FIELD_SIZE,
        y + CoordinateSystem.HALF_FIELD_SIZE,
        theta.toPedro()
    )
}

fun Double.toPedro(): Double {
    return toRadians(normalizeAngle(90 - this))
}

fun Double.toPose(): Double {
    return normalizeAngle(90 - toDegrees(this))
}

fun Double.mirrorX(): Double {
    return normalizeAngle(180 - this)
}

fun Double.mirrorY(): Double {
    return normalizeAngle(-this)
}

fun Double.invert(): Double {
    return normalizeAngle(this + 180)
}

fun Double.mirrorPosDiagonal(): Double {
    return normalizeAngle(90 - this)
}

fun Double.mirrorNegDiagonal(): Double {
    return normalizeAngle(-90 - this)
}

/**
 * based on Biobuzz's coordinate system
 */
fun Pose2D.toPose(): Pose {
    return Pose(
        -this.getY(DistanceUnit.INCH),
        this.getX(DistanceUnit.INCH),
        normalizeAngle(-this.getHeading(AngleUnit.DEGREES))
    )
}

/**
 * based on Biobuzz's inverted coordinate system
 */
fun Pose.toFTC(): Pose2D {
    return Pose2D(
        DistanceUnit.INCH,
        y,
        -x,
        AngleUnit.DEGREES,
        normalizeAngle(-theta)
    )
}

fun normalizeAngle(degrees: Double): Double {
    var normalizedAngle = degrees % 360.0
    if(normalizedAngle >= 180.0) {
        normalizedAngle -= 360.0
    }

    if(normalizedAngle < -180.0) {
        normalizedAngle += 360.0
    }

    return normalizedAngle
}
