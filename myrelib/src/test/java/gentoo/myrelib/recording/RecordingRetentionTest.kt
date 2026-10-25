package gentoo.myrelib.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FilterOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS

class RecordingRetentionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `overlapping writers enforce file count when the oldest finishes last`() {
        verifyRetention(RecordingConfig(keepFiles = 1, maxBytes = 1024, storageBytes = 4096), newestFirst = true)
    }

    @Test
    fun `overlapping writers enforce file count when the newest finishes last`() {
        verifyRetention(RecordingConfig(keepFiles = 1, maxBytes = 1024, storageBytes = 4096), newestFirst = false)
    }

    @Test
    fun `overlapping writers enforce storage bytes after draining`() {
        verifyRetention(RecordingConfig(keepFiles = 10, maxBytes = 1024, storageBytes = 1024), newestFirst = true)
    }

    private fun verifyRetention(config: RecordingConfig, newestFirst: Boolean) {
        val directory = temporary.newFolder()
        val olderFile = File(directory, "100-aa.jsonl")
        val newerFile = File(directory, "200-bb.jsonl")

        DrainingRecording(olderFile, config).use { older ->
            older.awaitDraining()
            DrainingRecording(newerFile, config).use { newer ->
                newer.awaitDraining()
                assertTrue("Starting another writer must preserve active files", olderFile.isFile)
                assertTrue(newerFile.isFile)

                if(newestFirst) {
                    newer.close()
                    assertTrue("Cleanup must preserve a writer that is still draining", olderFile.isFile)
                    older.close()
                } else {
                    older.close()
                    assertTrue("Cleanup must preserve a writer that is still draining", newerFile.isFile)
                    newer.close()
                }
            }
        }

        val retained = directory.listFiles()!!.toList()
        assertEquals("Keep the newest recording after both writers finish", listOf(newerFile), retained)
        assertTrue(retained.size <= config.keepFiles)
        assertTrue(retained.sumOf { it.length() } <= config.storageBytes)
    }

    /** Hold final close so both files remain active even after their buffers drain. */
    private class DrainingRecording(file: File, config: RecordingConfig) : AutoCloseable {
        private val draining = CountDownLatch(1)
        private val finish = CountDownLatch(1)
        private val writer = RecordingWriter(file, mapOf("type" to "header"), config) {
            object : FilterOutputStream(file.outputStream()) {
                override fun close() {
                    try {
                        draining.countDown()
                        check(finish.await(5, SECONDS))
                    } finally {
                        super.close()
                    }
                }
            }
        }

        init {
            writer.offer(mapOf("type" to "sample", "value" to "x".repeat(600)))
            writer.close()
        }

        fun awaitDraining() {
            assertTrue("Writer did not reach final close", draining.await(5, SECONDS))
        }

        override fun close() {
            finish.countDown()
            writer.awaitClosed()
        }
    }
}
