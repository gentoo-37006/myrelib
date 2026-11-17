package gentoo.myrelib.pathing

import com.pedropathing.api.Paths
import com.pedropathing.config.Modifier
import com.pedropathing.paths.interpolator.Interpolator
import com.pedropathing.utils.Angle
import gentoo.myrelib.action.ActionSubmission
import gentoo.myrelib.action.ActionType
import gentoo.myrelib.math.invert
import gentoo.myrelib.math.toPedro
import com.pedropathing.paths.Path as PedroPath

class Path(vararg curves: Curve) {
    private val segments = curves.toMutableList()
    private var heading: Heading = ConstantHeading()
    private var pathActions: MutableList<Pair<Double, ActionCallback>> = mutableListOf()
    private val modifiers: MutableList<Modifier> = mutableListOf()

    init {
        require(this.segments.isNotEmpty()) {
            "a Path needs at least 1 segment"
        }
    }

    val startPose get() = segments[0].start

    fun constantHeading(heading: Double): Path {
        this.heading = ConstantHeading(heading)
        return this
    }

    fun tangentHeading(): Path {
        this.heading = TangentHeading()
        return this
    }

    fun reversedTangentHeading(): Path {
        this.heading = TangentHeading(true)
        return this
    }

    fun linearHeading(startHeading: Double, endHeading: Double): Path {
        this.heading = LinearHeading(startHeading, endHeading)
        return this
    }

    fun linearHeading(
        startHeading: Double,
        endHeading: Double,
        start: Double,
        end: Double
    ): Path {
        this.heading = LinearHeading(startHeading, endHeading, start, end)
        return this
    }

    /** Submits [action] at [progress], the distance fraction of the entire path. */
    fun submitAt(progress: Double, action: ActionType): Path = submitAt(progress, action())

    /** Submits [action] at [progress], the distance fraction of the entire path. */
    fun submitAt(progress: Double, action: ActionSubmission): Path {
        require(progress in 0.0..1.0) { "callback progress must be in [0, 1], got $progress" }
        pathActions.add(progress to ActionCallback(action.type, start = true, submission = action))
        return this
    }

    /** Removes [action] at [progress], the distance fraction of the entire path. */
    fun removeAt(progress: Double, action: ActionType): Path {
        require(progress in 0.0..1.0) { "callback progress must be in [0, 1], got $progress" }
        pathActions.add(progress to ActionCallback(action, start = false))
        return this
    }

    fun modifiers(vararg modifiers: Modifier): Path {
        this.modifiers.addAll(modifiers)
        return this
    }

    fun mirrored(): Path {
        val newPath = Path(*segments.map { it.mirrored() }.toTypedArray())
        newPath.heading = heading.mirrored()
        newPath.pathActions = pathActions.toMutableList()
        return newPath
    }

    fun build(): PedroPath {
        val paths = segments.map {
            when(it) {
                is Linear -> Paths.line(
                    it.start.toPedro(),
                    it.end.toPedro()
                )

                is Spline -> Paths.curve(
                    it.start.toPedro(),
                    it.control1.toPedro(),
                    it.control2.toPedro(),
                    it.end.toPedro()
                )
            }
        }.toTypedArray()

        return (paths.singleOrNull() ?: Paths.path(*paths))
            .heading(heading.asPedro())
            .with(modifiers)
    }

    internal fun callbacks(): List<Pair<Double, () -> Unit>> =
        pathActions.sortedBy { it.first }.map { (progress, callback) -> progress to callback::run }

    /** Held as data so mirrored paths can copy callbacks without sharing run state. */
    private class ActionCallback(
        val action: ActionType,
        val start: Boolean,
        val submission: ActionSubmission? = null,
    ) {
        fun run() = if(start) submission!!.submit() else action.remove()
    }

    sealed class Heading {
        abstract fun asPedro(): Interpolator

        abstract fun mirrored(): Heading
    }

    class ConstantHeading(val heading: Double = 0.0) : Heading() {
        override fun asPedro(): Interpolator = Interpolator.constant(heading.toPedro())

        override fun mirrored() = ConstantHeading(heading.invert())
    }

    class LinearHeading(
        val startHeading: Double,
        val endHeading: Double,
        val start: Double,
        val end: Double
    ) : Heading() {
        constructor(startHeading: Double, endHeading: Double) : this(
            startHeading,
            endHeading,
            0.0,
            1.0
        )

        init {
            if(start !in 0.0..<end) {
                throw IllegalArgumentException("start % must be in [0, $end), got $start")
            }
            if(end <= start || end > 1.0) {
                throw IllegalArgumentException("end % must be in ($start, 1], got $end")
            }
        }

        override fun asPedro(): Interpolator {
            val start = startHeading.toPedro()
            val delta = Angle.error(start, endHeading.toPedro())
            return Interpolator { _, t ->
                val progress =
                    ((t - this@LinearHeading.start) / (end - this@LinearHeading.start)).coerceIn(
                        0.0,
                        1.0
                    )
                Angle.normalize(start + delta * progress)
            }
        }

        override fun mirrored() = LinearHeading(
            startHeading.invert(),
            endHeading.invert(),
            start,
            end
        )
    }

    class TangentHeading(
        val reversed: Boolean = false
    ) : Heading() {
        override fun asPedro(): Interpolator = if(reversed) {
            Interpolator.tangent.reverse()
        }
        else {
            Interpolator.tangent
        }

        override fun mirrored() = this
    }
}
