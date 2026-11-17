package gentoo.myrelib.util

import gentoo.myrelib.math.Pose
import gentoo.myrelib.math.assertPoseEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

private class FakePosePersistence(
    var bytes: ByteArray? = null,
) : PosePersistence {
    var clears = 0

    override fun read(): ByteArray? = bytes

    override fun write(bytes: ByteArray) {
        this.bytes = bytes.copyOf()
    }

    override fun clear() {
        clears++
        bytes = null
    }
}

class PoseRepositoryTest {
    @Test
    fun `cache configuration rejects invalid storage policy`() {
        assertThrows(IllegalArgumentException::class.java) {
            PoseCacheConfig(fileName = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PoseCacheConfig(writeIntervalMs = -1L)
        }
    }

    @Test
    fun `snapshot codec preserves pose and recovery metadata`() {
        val snapshot = PoseSnapshot(
            pose = Pose(12.5, -7.25, 135.0),
            alliance = Alliance.RED,
            phase = PosePhase.AUTON_COMPLETE,
            savedAtElapsedMs = 1234,
            savedAtEpochMs = 5678,
            bootEpochMs = 4444,
        )

        val decoded = PoseSnapshotCodec.decode(PoseSnapshotCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `a clean auton handoff survives repository reconstruction`() {
        var elapsed = 1_000L
        val persistence = FakePosePersistence()
        val writer = PoseRepositoryEngine(elapsedNow = { elapsed }, epochNow = { 10_000L })
        writer.attach(persistence)
        writer.beginAutonTracking(Alliance.BLUE)
        writer.record(Pose(4.0, 8.0, 90.0))
        elapsed += 20
        writer.completeAutonTracking(Pose(5.0, 9.0, 100.0))

        val reader = PoseRepositoryEngine(elapsedNow = { elapsed }, epochNow = { 10_020L })
        reader.attach(persistence)
        val restored = reader.restoreForTeleop(Alliance.BLUE)

        assertNotNull(restored)
        assertEquals(PosePhase.AUTON_COMPLETE, restored!!.phase)
        assertPoseEquals(Pose(5.0, 9.0, 100.0), restored.pose)
    }

    @Test
    fun `a finished session stops recording so a later opmode cannot overwrite it`() {
        var elapsed = 1_000L
        val persistence = FakePosePersistence()
        val engine = PoseRepositoryEngine(elapsedNow = { elapsed }, epochNow = { 10_000L })
        engine.attach(persistence)
        engine.beginAutonTracking(Alliance.BLUE)
        engine.record(Pose(4.0, 8.0, 90.0))
        elapsed += 20
        engine.completeAutonTracking(Pose(5.0, 9.0, 100.0))

        // Any opmode with motion enabled ticks the follower, and the follower records every
        // loop. One that never begins its own session - a debug drive opmode, say
        // - must not be attributed to the auton that already finished.
        elapsed += 20
        engine.record(Pose(0.0, 0.0, 0.0))

        val restored = engine.restoreForTeleop(Alliance.BLUE)
        assertNotNull(restored)
        assertEquals(PosePhase.AUTON_COMPLETE, restored!!.phase)
        assertPoseEquals(Pose(5.0, 9.0, 100.0), restored.pose)
    }

    @Test
    fun `recovery rejects the wrong alliance and stale or previous-boot snapshots`() {
        var elapsed = 5_000L
        var epoch = 20_000L
        val config = PoseCacheConfig(maxAgeMs = 600_000L)
        val repository =
            PoseRepositoryEngine(config, elapsedNow = { elapsed }, epochNow = { epoch })
        repository.beginAuton(Alliance.BLUE)
        repository.record(Pose(1.0, 2.0, 3.0))

        assertNull(repository.restoreCandidate(Alliance.RED))

        val staleAdvance = config.maxAgeMs + 1
        elapsed += staleAdvance
        epoch += staleAdvance
        assertNull(repository.restoreCandidate(Alliance.BLUE))

        elapsed = 1L
        epoch += 1
        assertNull(repository.restoreCandidate(Alliance.BLUE))
    }

    @Test
    fun `unclean teleop is recoverable but clean teleop completion is not`() {
        var elapsed = 100L
        val repository = PoseRepositoryEngine(elapsedNow = { elapsed }, epochNow = { 200L })
        repository.beginTeleopTracking(Alliance.RED)
        repository.record(Pose(-2.0, 3.0, -45.0))

        assertEquals(PosePhase.TELEOP_RUNNING, repository.restoreForTeleop(Alliance.RED)?.phase)

        elapsed++
        repository.completeTeleopTracking(Pose(-3.0, 4.0, -50.0))

        assertEquals(Pose(-3.0, 4.0, -50.0), repository.latest?.pose)
        assertNull(repository.restoreForTeleop(Alliance.RED))
        assertEquals(
            PosePhase.TELEOP_COMPLETE,
            repository.restoreForTeleop(
                Alliance.RED,
                restorePhases = setOf(PosePhase.TELEOP_COMPLETE),
            )?.phase,
        )
    }

    @Test
    fun `completing a session that was never begun leaves the handoff alone`() {
        // A teleop stopped during INIT never began its session. Completing it
        // anyway marked the auton's handoff TELEOP_COMPLETE, which the next
        // teleop's default policy refuses, so it lost the robot's position.
        var elapsed = 1_000L
        val repository = PoseRepositoryEngine(elapsedNow = { elapsed }, epochNow = { 10_000L + elapsed })
        repository.attach(FakePosePersistence())
        repository.beginAutonTracking(Alliance.RED)
        elapsed += 20
        repository.completeAutonTracking(Pose(-40.0, 20.0, 90.0))

        elapsed += 20
        repository.completeTeleopTracking(Pose(64.0, -64.0, 0.0))

        val restored = repository.restoreForTeleop(Alliance.RED)
        assertEquals(PosePhase.AUTON_COMPLETE, restored?.phase)
        assertPoseEquals(Pose(-40.0, 20.0, 90.0), restored!!.pose)
    }

    @Test
    fun `an auton that never began its session cannot relabel a finished teleop`() {
        val repository = PoseRepositoryEngine(elapsedNow = { 100L }, epochNow = { 200L })
        repository.beginTeleopTracking(Alliance.RED)
        repository.completeTeleopTracking(Pose(-3.0, 4.0, -50.0))

        repository.completeAutonTracking(null)

        assertEquals(PosePhase.TELEOP_COMPLETE, repository.latest?.phase)
        assertNull(repository.restoreForTeleop(Alliance.RED))
    }

    @Test
    fun `ending tracking keeps the snapshot and stops recording against it`() {
        // A teleop ends any session still open when it initializes, so applying
        // its pose before PLAY cannot overwrite a handoff it did not restore.
        val repository = PoseRepositoryEngine(elapsedNow = { 100L }, epochNow = { 200L })
        repository.beginAutonTracking(Alliance.RED)
        repository.record(Pose(-40.0, 20.0, 90.0))

        repository.endTracking()
        repository.record(Pose(64.0, -64.0, 0.0))

        assertEquals(PosePhase.AUTON_RUNNING, repository.latest?.phase)
        assertPoseEquals(Pose(-40.0, 20.0, 90.0), repository.latest!!.pose)
    }

    @Test
    fun `teleops initialized and stopped before PLAY leave the handoff for the next one`() {
        var elapsed = 1_000L
        val repository = PoseRepositoryEngine(elapsedNow = { elapsed }, epochNow = { 10_000L + elapsed })
        repository.attach(FakePosePersistence())
        repository.beginAutonTracking(Alliance.RED)
        repository.completeAutonTracking(Pose(-40.0, 20.0, 90.0))

        // Teleop's INIT and stop: the wrong alliance's first, then the right one twice
        for(alliance in listOf(Alliance.BLUE, Alliance.RED, Alliance.RED)) {
            elapsed += 20
            val pose = repository.restoreForTeleop(alliance)?.pose ?: Pose(64.0, -64.0, 0.0)
            repository.endTracking()
            repository.record(pose)
            repository.completeTeleopTracking(pose)
        }

        val restored = repository.restoreForTeleop(Alliance.RED)
        assertEquals(PosePhase.AUTON_COMPLETE, restored?.phase)
        assertPoseEquals(Pose(-40.0, 20.0, 90.0), restored!!.pose)
    }

    @Test
    fun `teleop restore phases are configurable and default to recoverable sessions`() {
        val repository = PoseRepositoryEngine(elapsedNow = { 100L }, epochNow = { 200L })
        repository.beginAuton(Alliance.BLUE)
        repository.record(Pose(1.0, 2.0, 3.0))

        assertEquals(
            PosePhase.AUTON_RUNNING,
            repository.restoreForTeleop(Alliance.BLUE)?.phase,
        )
        assertNull(repository.restoreForTeleop(Alliance.BLUE, emptySet()))

        repository.completeAuton()

        assertEquals(
            PosePhase.AUTON_COMPLETE,
            repository.restoreForTeleop(Alliance.BLUE)?.phase,
        )
        assertNull(
            repository.restoreForTeleop(
                Alliance.BLUE,
                restorePhases = setOf(PosePhase.TELEOP_RUNNING),
            ),
        )
    }

    @Test
    fun `starting a new localization session clears the previous snapshot`() {
        val persistence = FakePosePersistence()
        val repository = PoseRepositoryEngine(elapsedNow = { 1_000L }, epochNow = { 2_000L })
        repository.attach(persistence)
        repository.beginAuton(Alliance.BLUE)
        repository.record(Pose(1.0, 2.0, 3.0))

        repository.beginTeleop(Alliance.BLUE)

        assertNull(repository.latest)
        assertNull(persistence.bytes)
    }

    @Test
    fun `configuration can update restore policy after attachment`() {
        var elapsed = 100L
        val repository = PoseRepositoryEngine(
            elapsedNow = { elapsed },
            epochNow = { 1_000L + elapsed }
        )
        repository.attach(FakePosePersistence())
        repository.beginAuton(Alliance.BLUE)
        repository.record(Pose(1.0, 2.0, 3.0))

        repository.configure(PoseCacheConfig(maxAgeMs = 5L))
        elapsed += 6L

        assertNull(repository.restoreCandidate(Alliance.BLUE))
    }

    @Test
    fun `cache filename cannot change after attachment`() {
        val repository = PoseRepositoryEngine()
        repository.attach(FakePosePersistence())

        assertThrows(IllegalStateException::class.java) {
            repository.configure(PoseCacheConfig(fileName = "another-pose.bin"))
        }
    }
}
