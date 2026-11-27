package gentoo.myrelib.network

import com.google.gson.GsonBuilder
import gentoo.myrelib.recording.StateSample
import java.util.concurrent.atomic.AtomicLong

/** The web thread sees detached snapshots and never invokes robot code. */
object RobotTelemetryBridge {
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
        val phase: String? = null,
        val states: Map<String, Any?> = emptyMap(),
        val errors: Map<String, String> = emptyMap(),
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
    internal fun publish(sample: StateSample, path: FieldPath?, nowNanos: Long) {
        val current = state.snapshot
        if (!current.active) return
        val fields = sample.states["pose"] as? Map<*, *>
        val x = (fields?.get("x") as? Number)?.toDouble()
        val y = (fields?.get("y") as? Number)?.toDouble()
        val heading = (fields?.get("heading") as? Number)?.toDouble()
        val pose = if(x?.isFinite() == true && y?.isFinite() == true && heading?.isFinite() == true)
            PoseData(x, y, heading) else null
        val progress = sample.states["motionPath"] as? Map<*, *>
        val pathT = (progress?.get("t") as? Number)?.toDouble()
            ?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        state = State(current.copy(
            sequence = current.sequence + 1,
            pose = pose,
            path = path,
            pathT = pathT,
            phase = sample.phase,
            states = sample.states,
            errors = sample.errors,
        ), nowNanos)
    }

    @Synchronized
    fun stop() {
        state = state.copy(snapshot = state.snapshot.copy(active = false, path = null, pathT = 0.0))
    }

    fun snapshot(nowNanos: Long = System.nanoTime()): Snapshot {
        val current = state
        return current.snapshot.copy(ageMs = current.publishedNanos?.let { ((nowNanos - it) / 1_000_000).coerceAtLeast(0) })
    }

    fun json(): String = gson.toJson(snapshot())
}
