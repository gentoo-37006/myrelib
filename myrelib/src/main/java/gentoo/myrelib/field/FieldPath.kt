package gentoo.myrelib.field

import com.pedropathing.paths.Path
import com.pedropathing.math.Vector2D
import com.pedropathing.paths.curves.Line
import com.pedropathing.paths.curves.bezier.BezierCurve
import gentoo.myrelib.math.FieldCoordinateSystem
import java.util.Collections

/** Detached, bounded field geometry. Never retains a Pedro path or season callback. */
data class FieldPath(val id: Long, val points: List<Point>, val curves: List<Curve>? = null) {
    data class Point(val x: Double, val y: Double)
    data class Curve(val start: Point, val end: Point, val control1: Point? = null, val control2: Point? = null)

    companion object {
        private fun point(value: Vector2D) = Point(
            value.x() - FieldCoordinateSystem.HALF_FIELD_SIZE,
            value.y() - FieldCoordinateSystem.HALF_FIELD_SIZE
        ).also { require(it.x.isFinite() && it.y.isFinite()) }

        fun from(path: Path, id: Long): FieldPath {
            val points = (0..128).map { point(path.curve.get(it / 128.0)) }
            val curves = path.getSegments().takeIf { it.size in 1..128 }?.map { segment ->
                when (val curve = segment.curve) {
                    is Line -> Curve(point(curve.get(0.0)), point(curve.get(1.0)))
                    is BezierCurve -> curve.controlPoints.takeIf { it.size == 4 }?.let {
                        Curve(point(it[0]), point(it[3]), point(it[1]), point(it[2]))
                    }
                    else -> null
                }
            }?.takeIf { curves -> curves.all { it != null } }?.filterNotNull()
            return FieldPath(id, Collections.unmodifiableList(points), curves?.let(Collections::unmodifiableList))
        }
    }
}
