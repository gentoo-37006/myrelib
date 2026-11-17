package gentoo.myrelib.field

import com.google.gson.GsonBuilder
import java.util.concurrent.atomic.AtomicLong

/** Framework-owned app-lifetime state: never retain a hot-swapped robot, pose, or callback here. */
object FieldPoseBridge {
    data class PoseData(val x: Double, val y: Double, val heading: Double)
    data class RobotSize(val width: Double, val length: Double)
    data class Snapshot(
        val version: Int = 1,
        val sessionId: Long,
        val sequence: Long,
        val active: Boolean,
        val opMode: String?,
        val ageMs: Long?,
        val pose: PoseData?,
        val robot: RobotSize?,
        val path: FieldPath? = null,
        val pathT: Double = 0.0,
    )

    private data class State(val snapshot: Snapshot, val publishedNanos: Long?)
    private val sessions = AtomicLong(System.currentTimeMillis())
    private val gson = GsonBuilder().serializeNulls().create()
    @Volatile
    private var state = State(Snapshot(sessionId = 0, sequence = 0, active = false, opMode = null, ageMs = null, pose = null, robot = null), null)

    @Synchronized
    fun begin(opMode: String, width: Double, length: Double) {
        require(width.isFinite() && width > 0 && length.isFinite() && length > 0)
        state = State(Snapshot(sessionId = sessions.incrementAndGet(), sequence = 0, active = true, opMode = opMode, ageMs = null, pose = null, robot = RobotSize(width, length)), null)
    }

    @Synchronized
    fun publish(x: Double?, y: Double?, heading: Double?, nowNanos: Long = System.nanoTime()) {
        val current = state.snapshot
        if (!current.active) return
        val pose = if (x != null && y != null && heading != null && x.isFinite() && y.isFinite() && heading.isFinite()) PoseData(x, y, heading) else null
        state = State(current.copy(sequence = current.sequence + 1, pose = pose), nowNanos)
    }

    @Synchronized
    fun stop() {
        state = state.copy(snapshot = state.snapshot.copy(active = false, path = null))
    }

    @Synchronized
    fun publishPath(path: FieldPath?, t: Double = 0.0) {
        if(!state.snapshot.active) return
        state = state.copy(snapshot = state.snapshot.copy(path = path, pathT = t.coerceIn(0.0, 1.0)))
    }

    fun snapshot(nowNanos: Long = System.nanoTime()): Snapshot {
        val current = state
        return current.snapshot.copy(ageMs = current.publishedNanos?.let { ((nowNanos - it) / 1_000_000).coerceAtLeast(0) })
    }

    fun json(): String = gson.toJson(snapshot())
}
