package gentoo.myrelib.recording

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import gentoo.myrelib.math.Pose
import gentoo.myrelib.field.RecordingFiles
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MatchRecorderTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun records(file: java.io.File): List<JsonObject> = file.readLines().map {
        JsonParser().parse(it).asJsonObject
    }
    // RobotCore bundles a Gson that predates JsonObject.keySet()
    private fun JsonObject.names() = entrySet().map { it.key }.toSet()

    @Test fun `path events survive between samples and detach geometry before writing`() {
        var now = 0L
        val recorder = MatchRecorder(clock = { now })
        val file = temporary.newFile()
        recorder.start(file, "Auto", "BLUE", 16.0, 16.0)
        recorder.sample("INIT")
        val points = mutableListOf(gentoo.myrelib.field.FieldPath.Point(1.0, 2.0))
        now = 1_000_000
        recorder.recordPath(gentoo.myrelib.field.FieldPath(1, points))
        points.clear()
        now = 2_000_000
        recorder.recordPath(gentoo.myrelib.field.FieldPath(2, listOf(gentoo.myrelib.field.FieldPath.Point(3.0, 4.0))))
        recorder.stop(); recorder.awaitClosed()
        val paths = records(file).filter { it["type"].asString == "path" }
        assertEquals(2, paths.size)
        assertEquals(1.0, paths[0]["timeMs"].asDouble, 0.0)
        assertEquals(1.0, paths[0]["points"].asJsonArray[0].asJsonObject["x"].asDouble, 0.0)
        assertEquals(2, paths[1]["id"].asInt)
    }

    @Test fun `samples cached suppliers on caller thread at configured interval`() {
        var now = 0L
        var reads = 0
        val caller = Thread.currentThread()
        val recorder = MatchRecorder(clock = { now })
        recorder.addState("pose") { assertSame(caller, Thread.currentThread()); reads++; Pose(1.0, 2.0, 90.0) }
        val file = temporary.newFile()
        recorder.start(file, "Teleop", "BLUE", 16.0, 16.0)
        recorder.sample("INIT")
        now = 49_000_000; recorder.sample()
        now = 50_000_000; recorder.sample()
        recorder.sample("STOP", force = true)
        recorder.stop(); recorder.awaitClosed(); recorder.sample()
        val lines = records(file)
        assertEquals(3, reads)
        assertEquals("pose", lines.first()["states"].asJsonArray[0].asString)
        assertEquals("BLUE", lines.first()["alliance"].asString)
        assertEquals(90.0, lines[1]["states"].asJsonObject["pose"].asJsonObject["heading"].asDouble, 0.0)
        assertEquals("STOP", lines[3]["phase"].asString)
        assertEquals(50.0, lines[2]["timeMs"].asDouble, 0.0)
        assertEquals("end", lines.last()["type"].asString)
        assertFalse(lines.last()["sizeLimitReached"].asBoolean)
    }

    @Test fun `values are detached and supplier failures do not stop other states`() {
        val mutable = mutableMapOf("value" to 7)
        val recorder = MatchRecorder()
        recorder.addState("map") { mutable }
        recorder.addState("missing") { null }
        recorder.addState("bad") { error("sensor unavailable") }
        recorder.addState("nan") { Double.NaN }
        recorder.addState("pose") { Pose(Double.NaN, 0.0, 0.0) }
        val file = temporary.newFile()
        recorder.start(file, "Test", null, 16.0, 16.0)
        recorder.sample(); mutable["value"] = 9
        recorder.stop(); recorder.awaitClosed()
        val sample = records(file)[1]
        val states = sample["states"].asJsonObject
        assertEquals(7, states["map"].asJsonObject["value"].asInt)
        for (name in listOf("missing", "bad", "nan", "pose")) assertTrue(states[name].isJsonNull)
        assertEquals("IllegalStateException", sample["errors"].asJsonObject["bad"].asString)
    }

    @Test fun `rejects duplicate registrations and changes after start`() {
        val recorder = MatchRecorder()
        recorder.addState("pose") { null }
        assertThrows(IllegalArgumentException::class.java) { recorder.addState("pose") { null } }
        assertThrows(IllegalArgumentException::class.java) { recorder.addState("") { null } }
        recorder.start(temporary.newFile(), "Test", null, 16.0, 16.0)
        assertThrows(IllegalStateException::class.java) { recorder.addState("other") { true } }
        recorder.stop(); recorder.awaitClosed()
    }

    @Test fun `large state structures are isolated and strings are bounded`() {
        val recorder = MatchRecorder()
        recorder.addState("string") { "x".repeat(5000) }
        recorder.addState("oversized") { (0..256).toList() }
        recorder.addState("healthy") { true }
        val file = temporary.newFile()
        recorder.start(file, "Test", null, 16.0, 16.0)
        recorder.sample(); recorder.stop(); recorder.awaitClosed()
        val sample = records(file)[1]
        assertEquals(4096, sample["states"].asJsonObject["string"].asString.length)
        assertTrue(sample["states"].asJsonObject["oversized"].isJsonNull)
        assertTrue(sample["states"].asJsonObject["healthy"].asBoolean)
        assertEquals("IllegalArgumentException", sample["errors"].asJsonObject["oversized"].asString)
    }

    @Test fun `a state over the snapshot budget leaves the budget for later states`() {
        // 4,353 values: the outer list, 256 inner lists, and 4,096 numbers
        val recorder = MatchRecorder()
        recorder.addState("wide") { List(256) { List(16) { it } } }
        recorder.addState("healthy") { true }
        val file = temporary.newFile()
        recorder.start(file, "Test", null, 16.0, 16.0)
        recorder.sample(); recorder.stop(); recorder.awaitClosed()
        val sample = records(file)[1]
        assertEquals(setOf("wide"), sample["errors"].asJsonObject.names())
        assertEquals("IllegalArgumentException", sample["errors"].asJsonObject["wide"].asString)
        assertTrue(sample["states"].asJsonObject["wide"].isJsonNull)
        assertTrue(sample["states"].asJsonObject["healthy"].asBoolean)
    }

    @Test fun `each string counts once against the value budget`() {
        // 2,570 values, which fit the 4,096 budget only if a string is one value
        val recorder = MatchRecorder()
        repeat(10) { index -> recorder.addState("names$index") { List(256) { "a" } } }
        val file = temporary.newFile()
        recorder.start(file, "Test", null, 16.0, 16.0)
        recorder.sample(); recorder.stop(); recorder.awaitClosed()
        val sample = records(file)[1]
        assertEquals(emptySet<String>(), sample["errors"].asJsonObject.names())
        repeat(10) { assertEquals(256, sample["states"].asJsonObject["names$it"].asJsonArray.size()) }
    }

    @Test fun `a snapshot holds exactly 4096 values and 65536 characters`() {
        // 16 lists of 255 numbers are 4,096 values; 16 strings of 4,096 are 65,536 characters
        val recorder = MatchRecorder()
        repeat(16) { index -> recorder.addState("numbers$index") { List(255) { it } } }
        recorder.addState("value") { true }
        val file = temporary.newFile()
        recorder.start(file, "Test", null, 16.0, 16.0)
        recorder.sample(); recorder.stop(); recorder.awaitClosed()
        val values = records(file)[1]
        repeat(16) { assertEquals(255, values["states"].asJsonObject["numbers$it"].asJsonArray.size()) }
        assertEquals("IllegalArgumentException", values["errors"].asJsonObject["value"].asString)

        val text = MatchRecorder()
        repeat(16) { index -> text.addState("text$index") { "x".repeat(4096) } }
        text.addState("character") { "x" }
        val textFile = temporary.newFile()
        text.start(textFile, "Test", null, 16.0, 16.0)
        text.sample(); text.stop(); text.awaitClosed()
        val characters = records(textFile)[1]
        repeat(16) { assertEquals(4096, characters["states"].asJsonObject["text$it"].asString.length) }
        assertEquals("IllegalArgumentException", characters["errors"].asJsonObject["character"].asString)
    }

    @Test fun `size cap terminates recording with explicit footer`() {
        val file = temporary.newFile()
        val recorder = MatchRecorder(RecordingConfig(maxBytes = 512))
        recorder.addState("large") { "x".repeat(4096) }
        recorder.start(file, "Test", null, 16.0, 16.0)
        recorder.sample(); recorder.stop(); recorder.awaitClosed()
        val lines = records(file)
        assertEquals(2, lines.size)
        assertTrue(lines.last()["sizeLimitReached"].asBoolean)
        assertEquals(1, lines.last()["droppedSamples"].asInt)
        assertTrue(file.length() < 512)
    }

    @Test fun `size cap includes the footer and retains samples before the limit`() {
        val header = mapOf("type" to "header")
        val first = mapOf("type" to "sample", "value" to 7)
        val large = mapOf("type" to "sample", "value" to "\u00e9".repeat(200))
        // Without reserving the footer, the first two samples exactly fill the
        // cap. Use multibyte text so the boundary measures UTF-8 bytes.
        val cap = encodedSize(header) + encodedSize(first) + encodedSize(large)
        val opened = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val file = temporary.newFile()
        val writer = RecordingWriter(file, header, RecordingConfig(queueCapacity = 3, maxBytes = cap)) {
            opened.countDown()
            check(unblock.await(5, TimeUnit.SECONDS))
            file.outputStream()
        }
        try {
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            writer.offer(first)
            writer.offer(large)
            writer.offer(first)
            repeat(10) { writer.offer(first) }
            writer.close()
        } finally { unblock.countDown() }
        writer.awaitClosed()

        assertTrue("Recording exceeded its byte cap", file.length() <= cap)
        val lines = records(file)
        assertEquals(3, lines.size)
        assertEquals(7, lines[1]["value"].asInt)
        assertEquals("end", lines.last()["type"].asString)
        assertTrue(lines.last()["sizeLimitReached"].asBoolean)
        assertEquals(12, lines.last()["droppedSamples"].asInt)
    }

    @Test fun `a header that leaves no footer room is rejected before pruning recordings`() {
        val header = mapOf("type" to "header")
        assertHeaderRejected(header, encodedSize(header))
    }

    @Test fun `an oversized header is rejected before creating a file or pruning recordings`() {
        val header = mapOf("type" to "header", "opMode" to "\u00e9".repeat(300))
        assertHeaderRejected(header, 512)
    }

    private fun encodedSize(value: Any): Long =
        (GsonBuilder().serializeNulls().create().toJson(value) + "\n").toByteArray(Charsets.UTF_8).size.toLong()

    private fun assertHeaderRejected(header: Map<String, Any?>, cap: Long) {
        val dir = temporary.newFolder()
        val previous = java.io.File(dir, "100-a.jsonl").apply { writeText("previous") }
        val next = java.io.File(dir, "200-b.jsonl")
        val writer = RecordingWriter(next, header, RecordingConfig(maxBytes = cap, keepFiles = 1))
        writer.close()
        writer.awaitClosed()

        assertFalse(writer.accepting)
        assertFalse("An unusable recording should not be created", next.exists())
        assertTrue("Rejected metadata must not prune previous recordings", previous.exists())
        assertEquals("previous", previous.readText())
    }

    @Test fun `retention removes oldest recordings but not active writers`() {
        val dir = temporary.newFolder()
        val oldest = java.io.File(dir, "100-a.jsonl").apply { writeText("old") }
        val active = java.io.File(dir, "200-b.jsonl").apply { writeText("active") }
        val newest = java.io.File(dir, "300-c.jsonl").apply { writeText("new") }
        val next = java.io.File(dir, "400-d.jsonl")
        RecordingFiles.beginWrite(active, 50, 1000)
        try {
            RecordingFiles.beginWrite(next, 2, 1000)
            assertFalse(oldest.exists()); assertTrue(active.exists()); assertTrue(newest.exists())
        } finally {
            RecordingFiles.endWrite(active, 50, 1000)
            RecordingFiles.endWrite(next, 2, 1000)
        }
    }

    @Test fun `a saturated queue drops snapshots without waiting for disk`() {
        val opened = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val file = temporary.newFile()
        val writer = RecordingWriter(file, mapOf("type" to "header"), RecordingConfig(queueCapacity = 1)) {
            opened.countDown()
            check(unblock.await(5, TimeUnit.SECONDS))
            file.outputStream()
        }
        try {
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            writer.offer(mapOf("type" to "sample"))
            writer.offer(mapOf("type" to "sample"))
            writer.close()
        } finally { unblock.countDown() }
        writer.awaitClosed()
        val lines = records(file)
        assertEquals(3, lines.size)
        assertEquals(1, lines.last()["droppedSamples"].asInt)
    }

    @Test fun `downloads can only resolve recording filenames inside their directory`() {
        val dir = temporary.newFolder()
        val file = java.io.File(dir, "1234-abcdef.jsonl").apply { writeText("sample") }
        assertEquals(file, RecordingFiles.resolve(dir, file.name))
        for (name in listOf("../1234-abcdef.jsonl", file.absolutePath, "1234-abcdef.jsonl/other", "1234-abcdef.txt", "missing.jsonl")) {
            assertNull(RecordingFiles.resolve(dir, name))
        }
    }
}
