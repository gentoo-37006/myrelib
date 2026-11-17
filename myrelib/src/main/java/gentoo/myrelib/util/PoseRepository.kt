package gentoo.myrelib.util

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import com.qualcomm.robotcore.util.RobotLog
import gentoo.myrelib.math.Pose
import gentoo.myrelib.util.Alliance
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

enum class PosePhase {
    AUTON_RUNNING,
    AUTON_COMPLETE,
    TELEOP_RUNNING,
    TELEOP_COMPLETE,
}

val DEFAULT_TELEOP_RESTORE_PHASES: Set<PosePhase> = setOf(
    PosePhase.AUTON_COMPLETE,
    PosePhase.AUTON_RUNNING,
    PosePhase.TELEOP_RUNNING,
)

/** A validated last-known pose together with the context needed to restore it safely. */
data class PoseSnapshot(
    val pose: Pose,
    val alliance: Alliance?,
    val phase: PosePhase,
    val savedAtElapsedMs: Long,
    val savedAtEpochMs: Long,
    val bootEpochMs: Long,
)

/** Immutable storage and restore policy for one pose repository. */
data class PoseCacheConfig(
    val fileName: String = "robot-pose.bin",
    val writeIntervalMs: Long = 250L,
    val maxAgeMs: Long = 10L * 60L * 1000L,
    val bootEpochToleranceMs: Long = 5_000L,
) {
    init {
        require(fileName.isNotBlank()) { "Pose cache filename cannot be blank" }
        require(writeIntervalMs >= 0L) { "Pose cache write interval cannot be negative" }
        require(maxAgeMs >= 0L) { "Pose cache maximum age cannot be negative" }
        require(bootEpochToleranceMs >= 0L) { "Pose cache boot tolerance cannot be negative" }
    }
}

internal interface PosePersistence {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun clear()
}

internal class AtomicPosePersistence(context: Context, fileName: String) : PosePersistence {
    private val file = AtomicFile(File(context.filesDir, fileName))

    override fun read(): ByteArray? = if(file.baseFile.exists()) file.readFully() else null

    override fun write(bytes: ByteArray) {
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        }
        catch(error: Exception) {
            runCatching { file.failWrite(stream) }
            throw error
        }
    }

    override fun clear() = file.delete()
}

internal object PoseSnapshotCodec {
    private const val MAGIC = 0x47504f53 // GPOS
    private const val VERSION = 1

    fun encode(snapshot: PoseSnapshot): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeDouble(snapshot.pose.x)
            data.writeDouble(snapshot.pose.y)
            data.writeDouble(snapshot.pose.theta)
            data.writeInt(snapshot.alliance?.ordinal ?: -1)
            data.writeInt(snapshot.phase.ordinal)
            data.writeLong(snapshot.savedAtElapsedMs)
            data.writeLong(snapshot.savedAtEpochMs)
            data.writeLong(snapshot.bootEpochMs)
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): PoseSnapshot {
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == MAGIC) { "Invalid pose snapshot" }
            require(data.readInt() == VERSION) { "Unsupported pose snapshot version" }

            val pose = Pose(data.readDouble(), data.readDouble(), data.readDouble())
            require(pose.x.isFinite() && pose.y.isFinite() && pose.theta.isFinite()) {
                "Pose snapshot contains non-finite coordinates"
            }

            val allianceOrdinal = data.readInt()
            val alliance = if(allianceOrdinal == -1) null
            else Alliance.entries.getOrNull(allianceOrdinal)
                ?: throw IllegalArgumentException("Invalid pose snapshot alliance")
            val phase = PosePhase.entries.getOrNull(data.readInt())
                ?: throw IllegalArgumentException("Invalid pose snapshot phase")

            PoseSnapshot(
                pose = pose,
                alliance = alliance,
                phase = phase,
                savedAtElapsedMs = data.readLong(),
                savedAtEpochMs = data.readLong(),
                bootEpochMs = data.readLong(),
            )
        }
    }
}

/** Injectable implementation behind the process-wide [PoseRepository]. */
internal class PoseRepositoryEngine(
    config: PoseCacheConfig = PoseCacheConfig(),
    private val elapsedNow: () -> Long = SystemClock::elapsedRealtime,
    private val epochNow: () -> Long = System::currentTimeMillis,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "pose-persistence").apply { isDaemon = true }
    },
) {
    private val lock = Any()
    // Storage operations may take the state lock briefly, never the reverse.
    // In particular, record must not wait for AtomicFile's writes or fsync.
    private val persistenceLock = Any()

    @Volatile
    private var config = config
    private var persistence: PosePersistence? = null
    private var currentAlliance: Alliance? = null
    private var currentPhase: PosePhase? = null
    private var writeScheduled = false
    private var lastWriteElapsedMs = 0L

    @Volatile
    var latest: PoseSnapshot? = null
        private set

    /**
     * Updates the storage and restore policy.
     *
     * Timing and validity settings may change after attachment. The filename
     * must be selected before attachment because the active persistence object
     * is already bound to that file.
     */
    fun configure(config: PoseCacheConfig) {
        synchronized(lock) {
            check(persistence == null || config.fileName == this.config.fileName) {
                "Pose cache filename cannot change after storage is attached"
            }
            this.config = config
        }
    }

    fun attach(context: Context) {
        attach(AtomicPosePersistence(context.applicationContext, config.fileName))
    }

    internal fun attach(persistence: PosePersistence) {
        synchronized(persistenceLock) {
            synchronized(lock) {
                if(this.persistence != null) return
                this.persistence = persistence
            }
            if(latest == null) {
                val restored = readPersistedSnapshot()
                synchronized(lock) {
                    if(latest == null) latest = restored
                }
            }
        }
    }

    fun beginAuton(alliance: Alliance) {
        clear()
        currentAlliance = alliance
        currentPhase = PosePhase.AUTON_RUNNING
    }

    fun beginTeleop(alliance: Alliance?) {
        clear()
        currentAlliance = alliance
        currentPhase = PosePhase.TELEOP_RUNNING
    }

    fun beginAutonTracking(alliance: Alliance) = beginAuton(alliance)

    fun beginTeleopTracking(alliance: Alliance?) = beginTeleop(alliance)

    /**
     * Ends any open session without completing it. The latest snapshot keeps
     * its phase, and nothing records against it until the next begin.
     */
    fun endTracking() {
        currentAlliance = null
        currentPhase = null
    }

    fun record(pose: Pose) {
        if(!pose.x.isFinite() || !pose.y.isFinite() || !pose.theta.isFinite()) return
        synchronized(lock) {
            val phase = currentPhase ?: return
            val elapsed = elapsedNow()
            val epoch = epochNow()
            latest = PoseSnapshot(
                pose = pose,
                alliance = currentAlliance,
                phase = phase,
                savedAtElapsedMs = elapsed,
                savedAtEpochMs = epoch,
                bootEpochMs = epoch - elapsed,
            )
            scheduleWrite()
        }
    }

    fun completeAuton() {
        // only an open auton session can be completed: an auton that never
        // began one must not relabel another opmode's snapshot
        if(currentPhase != PosePhase.AUTON_RUNNING) return
        // the session is over, so nothing may record against it again. a later
        // opmode that never begins its own session would otherwise be attributed
        // to this one and overwrite the pose it just handed off
        currentPhase = null
        val snapshot = latest ?: return
        val elapsed = elapsedNow()
        val epoch = epochNow()
        latest = snapshot.copy(
            phase = PosePhase.AUTON_COMPLETE,
            savedAtElapsedMs = elapsed,
            savedAtEpochMs = epoch,
            bootEpochMs = epoch - elapsed,
        )
        flush()
    }

    fun completeTeleop() {
        // only an open teleop session can be completed: one stopped before PLAY
        // never began one, and marking the auton's handoff complete instead
        // would stop the next teleop restoring it
        if(currentPhase != PosePhase.TELEOP_RUNNING) return
        // the session is over, so nothing may record against it again. a later
        // opmode that never begins its own session would otherwise be attributed
        // to this one and overwrite the pose it just handed off
        currentPhase = null
        val snapshot = latest ?: return
        val elapsed = elapsedNow()
        val epoch = epochNow()
        latest = snapshot.copy(
            phase = PosePhase.TELEOP_COMPLETE,
            savedAtElapsedMs = elapsed,
            savedAtEpochMs = epoch,
            bootEpochMs = epoch - elapsed,
        )
        flush()
    }

    fun completeAutonTracking(finalPose: Pose?) {
        finalPose?.let(::record)
        completeAuton()
    }

    fun completeTeleopTracking(finalPose: Pose?) {
        finalPose?.let(::record)
        completeTeleop()
    }

    fun restoreCandidate(alliance: Alliance?): PoseSnapshot? {
        val snapshot = latest ?: return null
        if(snapshot.alliance != alliance) return null
        val elapsed = elapsedNow()
        val epoch = epochNow()
        if(abs((epoch - elapsed) - snapshot.bootEpochMs) >
            config.bootEpochToleranceMs
        ) return null
        val age = elapsed - snapshot.savedAtElapsedMs
        if(age < 0 || age > config.maxAgeMs) return null
        return snapshot
    }

    fun restoreForTeleop(
        alliance: Alliance?,
        restorePhases: Set<PosePhase> = DEFAULT_TELEOP_RESTORE_PHASES,
    ): PoseSnapshot? = restoreCandidate(alliance)?.takeIf { it.phase in restorePhases }

    fun flush() {
        persistLatest()
    }

    fun clear() {
        synchronized(persistenceLock) {
            synchronized(lock) {
                latest = null
                currentAlliance = null
                currentPhase = null
            }
            runCatching { persistence?.clear() }
                .onFailure { RobotLog.ee(TAG, it, "Unable to clear persisted robot pose") }
        }
    }

    private fun scheduleWrite() {
        synchronized(lock) {
            if(persistence == null || writeScheduled) return
            writeScheduled = true
            val sinceLastWrite = elapsedNow() - lastWriteElapsedMs
            val delay = max(0L, config.writeIntervalMs - sinceLastWrite)
            executor.schedule(::runScheduledWrite, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun runScheduledWrite() {
        val snapshot = persistLatest()
        synchronized(lock) {
            writeScheduled = false
            if(latest != null && latest != snapshot) scheduleWrite()
        }
    }

    private fun persistLatest(): PoseSnapshot? {
        synchronized(persistenceLock) {
            // Choose the snapshot only after taking the storage lock. A flush
            // or clear that finished ahead of this write must not be undone.
            val snapshot = latest ?: return null
            val storage = persistence ?: return snapshot
            runCatching { storage.write(PoseSnapshotCodec.encode(snapshot)) }
                .onSuccess {
                    synchronized(lock) { lastWriteElapsedMs = elapsedNow() }
                }
                .onFailure { RobotLog.ee(TAG, it, "Unable to persist robot pose") }
            return snapshot
        }
    }

    private fun readPersistedSnapshot(): PoseSnapshot? {
        return runCatching {
            persistence?.read()?.let(PoseSnapshotCodec::decode)
        }.onFailure {
            RobotLog.ee(TAG, it, "Ignoring unreadable persisted robot pose")
        }.getOrNull()
    }

    private companion object {
        const val TAG = "PoseRepository"
    }
}

/**
 * Process-wide pose handoff and crash-safe, app-private snapshot.
 *
 * This is deliberately independent of any robot instance so localization,
 * opmodes, and diagnostics can all use the same handoff state directly.
 * Writes are coalesced off the opmode loop; [PoseRepository.flush] provides
 * the normal-stop durability boundary.
 */
object PoseRepository {
    private val engine = PoseRepositoryEngine()

    val latest: PoseSnapshot? get() = engine.latest

    fun configure(config: PoseCacheConfig) = engine.configure(config)

    fun attach(context: Context) = engine.attach(context)

    fun beginAutonTracking(alliance: Alliance) = engine.beginAutonTracking(alliance)

    fun beginTeleopTracking(alliance: Alliance?) = engine.beginTeleopTracking(alliance)

    fun endTracking() = engine.endTracking()

    fun record(pose: Pose) = engine.record(pose)

    fun completeAutonTracking(finalPose: Pose?) = engine.completeAutonTracking(finalPose)

    fun completeTeleopTracking(finalPose: Pose?) = engine.completeTeleopTracking(finalPose)

    fun restoreForTeleop(
        alliance: Alliance?,
        restorePhases: Set<PosePhase> = DEFAULT_TELEOP_RESTORE_PHASES,
    ): PoseSnapshot? = engine.restoreForTeleop(alliance, restorePhases)

    fun flush() = engine.flush()

    fun clear() = engine.clear()
}
