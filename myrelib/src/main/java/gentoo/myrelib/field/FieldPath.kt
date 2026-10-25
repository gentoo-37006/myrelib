package gentoo.myrelib.field

import com.pedropathing.paths.Path
import gentoo.myrelib.math.FieldCoordinateSystem

/** Detached, bounded field geometry. Never retains a Pedro path or season callback. */
data class FieldPath(val id: Long, val points: List<Point>) {
    data class Point(val x: Double, val y: Double)

    companion object {
        fun from(path: Path, id: Long): FieldPath = FieldPath(id, (0..128).map {
            val point = path.curve.get(it / 128.0)
            Point(point.x() - FieldCoordinateSystem.HALF_FIELD_SIZE,
                point.y() - FieldCoordinateSystem.HALF_FIELD_SIZE)
        }.also { points -> require(points.all { it.x.isFinite() && it.y.isFinite() }) })
    }
}
