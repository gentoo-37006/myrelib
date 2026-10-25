package gentoo.myrelib.util

import gentoo.myrelib.math.Pose
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

private class BlockingPosePersistence : PosePersistence {
    @Volatile var bytes: ByteArray? = null
    val blockNextWrite = AtomicBoolean(false)
    val writeStarted = CountDownLatch(1)
    val allowWrite = CountDownLatch(1)
    val written = LinkedBlockingQueue<PoseSnapshot>()

    override fun read(): ByteArray? = bytes

    override fun write(bytes: ByteArray) {
        if(blockNextWrite.compareAndSet(true, false)) {
            writeStarted.countDown()
            allowWrite.await()
        }
        this.bytes = bytes.copyOf()
        written.add(PoseSnapshotCodec.decode(bytes))
    }

    override fun clear() {
        bytes = null
    }
}

class PosePersistenceConcurrencyTest {
    private val writer = Executors.newSingleThreadScheduledExecutor()
    private val caller = Executors.newSingleThreadExecutor()
    private val allowQueuedWrites = CountDownLatch(1)
    private val persistence = BlockingPosePersistence()
    private val repository = PoseRepositoryEngine(
        config = PoseCacheConfig(writeIntervalMs = 0L),
        elapsedNow = { 1_000L },
        epochNow = { 10_000L },
        executor = writer,
    )

    @Before
    fun initialize() {
        repository.attach(persistence)
        repository.beginAuton(Alliance.BLUE)
    }

    @After
    fun releaseThreads() {
        persistence.allowWrite.countDown()
        allowQueuedWrites.countDown()
        caller.shutdownNow()
        writer.shutdownNow()
        assertTrue("Caller did not stop", caller.awaitTermination(5, SECONDS))
        assertTrue("Writer did not stop", writer.awaitTermination(5, SECONDS))
    }

    @Test
    fun `record updates the pose during a slow write and coalesces pending samples`() {
        val first = Pose(1.0, 2.0, 3.0)
        val newest = Pose(7.0, 8.0, 9.0)
        startBlockedWrite(first)

        caller.submit {
            repository.record(Pose(4.0, 5.0, 6.0))
            repository.record(newest)
        }.get(1, SECONDS)

        assertEquals(newest, repository.latest?.pose)
        persistence.allowWrite.countDown()
        assertEquals(first, persistence.written.poll(5, SECONDS)?.pose)
        assertEquals(newest, persistence.written.poll(5, SECONDS)?.pose)
    }

    @Test
    fun `completion waits for an in-flight write and durably stores the final phase`() {
        val finalPose = Pose(4.0, 5.0, 6.0)
        startBlockedWrite(Pose(1.0, 2.0, 3.0))

        val completion = caller.submit { repository.completeAutonTracking(finalPose) }
        assertThrows(TimeoutException::class.java) { completion.get(1, SECONDS) }
        persistence.allowWrite.countDown()
        completion.get(5, SECONDS)
        drainQueuedWrites()

        val saved = PoseSnapshotCodec.decode(persistence.bytes!!)
        assertEquals(finalPose, saved.pose)
        assertEquals(PosePhase.AUTON_COMPLETE, saved.phase)
    }

    @Test
    fun `clear waits for an in-flight write and removes its snapshot`() {
        startBlockedWrite(Pose(1.0, 2.0, 3.0))

        val clearing = caller.submit { repository.clear() }
        assertThrows(TimeoutException::class.java) { clearing.get(1, SECONDS) }
        persistence.allowWrite.countDown()
        clearing.get(5, SECONDS)
        drainQueuedWrites()

        assertNull(repository.latest)
        assertNull(persistence.bytes)
    }

    @Test
    fun `a queued write cannot overwrite a flushed completion with an older pose`() {
        holdQueuedWrites()
        repository.record(Pose(1.0, 2.0, 3.0))
        repository.completeAutonTracking(Pose(4.0, 5.0, 6.0))
        val completed = repository.latest

        allowQueuedWrites.countDown()
        drainQueuedWrites()

        assertEquals(completed, PoseSnapshotCodec.decode(persistence.bytes!!))
    }

    @Test
    fun `a queued write cannot recreate a cleared snapshot`() {
        holdQueuedWrites()
        repository.record(Pose(1.0, 2.0, 3.0))
        repository.clear()

        allowQueuedWrites.countDown()
        drainQueuedWrites()

        assertNull(repository.latest)
        assertNull(persistence.bytes)
    }

    private fun startBlockedWrite(pose: Pose) {
        persistence.blockNextWrite.set(true)
        repository.record(pose)
        assertTrue("Background write did not start", persistence.writeStarted.await(5, SECONDS))
    }

    private fun holdQueuedWrites() {
        val held = CountDownLatch(1)
        writer.submit {
            held.countDown()
            allowQueuedWrites.await()
        }
        assertTrue("Writer did not reach the queue barrier", held.await(5, SECONDS))
    }

    private fun drainQueuedWrites() {
        // The first barrier waits for the original task; the second also waits
        // for a coalesced follow-up scheduled while that task was finishing.
        repeat(2) { writer.submit {}.get(5, SECONDS) }
    }
}
