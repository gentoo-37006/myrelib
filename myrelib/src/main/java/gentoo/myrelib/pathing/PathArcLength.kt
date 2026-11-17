package gentoo.myrelib.pathing

import com.pedropathing.paths.curves.Curve
import kotlin.math.hypot

/** Cached chord lengths at uniformly spaced parameters on each atomic curve. */
internal class PathArcLength(curves: List<Curve>) {
    private val samples = curves.map { curve ->
        val distances = DoubleArray(STEPS + 1)
        var previous = curve.get(0.0)
        for(i in 1..STEPS) {
            val point = curve.get(i.toDouble() / STEPS)
            distances[i] = distances[i - 1] + hypot(point.x() - previous.x(), point.y() - previous.y())
            previous = point
        }
        distances
    }
    private val offsets = samples.runningFold(0.0) { distance, segment -> distance + segment.last() }

    fun progress(segmentIndex: Int, t: Double): Double {
        val total = offsets.last()
        if(total <= 0.0 || !total.isFinite()) return 0.0
        val segment = samples[segmentIndex]
        val sample = t * STEPS
        val index = sample.toInt().coerceAtMost(STEPS - 1)
        val localDistance = segment[index] + (segment[index + 1] - segment[index]) * (sample - index)
        return ((offsets[segmentIndex] + localDistance) / total).coerceIn(0.0, 1.0)
    }

    private companion object {
        const val STEPS = 128
    }
}
