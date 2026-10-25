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
        startT: Double,
        endT: Double
    ): Path {
        this.heading = LinearHeading(startHeading, endHeading, startT, endT)
        return this
    }

    fun submitAt(tValue: Double, action: ActionType): Path = submitAt(tValue, action())

    fun submitAt(tValue: Double, action: ActionSubmission): Path {
        require(tValue in 0.0..1.0) { "callback t must be in [0, 1], got $tValue" }
        pathActions.add(tValue to ActionCallback(action.type, start = true, submission = action))
        return this
    }

    fun removeAt(tValue: Double, action: ActionType): Path {
        require(tValue in 0.0..1.0) { "callback t must be in [0, 1], got $tValue" }
        pathActions.add(tValue to ActionCallback(action, start = false))
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
        pathActions.sortedBy { it.first }.map { (t, callback) -> t to callback::run }

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
        val startT: Double,
        val endT: Double
    ) : Heading() {
        constructor(startHeading: Double, endHeading: Double) : this(
            startHeading,
            endHeading,
            0.0,
            1.0
        )

        init {
            if(startT !in 0.0..<endT) {
                throw IllegalArgumentException("startT must be in [0, $endT), got $startT")
            }
            if(endT <= startT || endT > 1.0) {
                throw IllegalArgumentException("endT must be in ($startT, 1], got $endT")
            }
        }

        override fun asPedro(): Interpolator {
            val start = startHeading.toPedro()
            val delta = Angle.error(start, endHeading.toPedro())
            return Interpolator { _, t ->
                val progress = ((t - startT) / (endT - startT)).coerceIn(0.0, 1.0)
                Angle.normalize(start + delta * progress)
            }
        }

        override fun mirrored() = LinearHeading(
            startHeading.invert(),
            endHeading.invert(),
            startT,
            endT
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
