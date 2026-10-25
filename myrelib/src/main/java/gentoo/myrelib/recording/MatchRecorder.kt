package gentoo.myrelib.recording

import com.google.gson.GsonBuilder
import com.qualcomm.robotcore.util.RobotLog
import gentoo.myrelib.math.Pose
import gentoo.myrelib.field.RecordingFiles
import gentoo.myrelib.field.FieldPath
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class RecordingConfig(
    val enabled: Boolean = true,
    val intervalMs: Long = 50,
    val queueCapacity: Int = 256,
    val maxBytes: Long = 16L * 1024 * 1024,
    val keepFiles: Int = 50,
    val storageBytes: Long = 256L * 1024 * 1024,
) {
    init {
        require(intervalMs > 0 && queueCapacity > 0 && maxBytes > 0)
        require(keepFiles > 0 && storageBytes >= maxBytes)
    }
}

/** Suppliers run on the opmode thread; only detached JSON values reach the writer. */
class MatchRecorder(
    private val config: RecordingConfig = RecordingConfig(),
    private val clock: () -> Long = System::nanoTime,
) {
    private val states = linkedMapOf<String, () -> Any?>()
    private var writer: RecordingWriter? = null
    private var startedAt = 0L
    private var lastSample: Long? = null

    fun addState(name: String, read: () -> Any?) {
        check(writer == null) { "Register recording states before starting" }
        require(name.isNotBlank() && name !in states) { "Recording state names must be unique and nonblank" }
        require(name.length <= 128 && states.size < 128) { "At most 128 states with names up to 128 characters" }
        states[name] = read
    }

    fun start(file: File, opMode: String, alliance: String?, width: Double, length: Double) {
        check(writer == null)
        startedAt = clock()
        lastSample = null
        writer = RecordingWriter(file, linkedMapOf(
            "type" to "header", "version" to 1, "opMode" to opMode,
            "alliance" to alliance, "startedAt" to System.currentTimeMillis(),
            "intervalMs" to config.intervalMs,
            "robot" to mapOf("width" to width, "length" to length),
            "states" to states.keys.toList(),
        ), config)
    }

    fun sample(phase: String = "RUNNING", force: Boolean = false) {
        val sink = writer ?: return
        if (!sink.accepting) return
        val now = clock()
        if (!force && lastSample?.let { (now - it) / 1_000_000 < config.intervalMs } == true) return
        lastSample = now
        val values = linkedMapOf<String, Any?>()
        val errors = linkedMapOf<String, String>()
        var remainingValues = 4096
        var remainingCharacters = 65_536
        states.forEach { (name, read) ->
            val valuesBefore = remainingValues
            val charactersBefore = remainingCharacters
            try {
                values[name] = detach(read(), consume = { count, characters ->
                    remainingValues -= count
                    remainingCharacters -= characters
                    require(remainingValues >= 0 && remainingCharacters >= 0) { "Recording snapshot is too large" }
                })
            } catch (error: Exception) {
                // A rejected state is recorded as null, so give back what it used
                // rather than starving every state registered after it.
                remainingValues = valuesBefore
                remainingCharacters = charactersBefore
                values[name] = null
                errors[name] = error.javaClass.simpleName
            }
        }
        sink.offer(linkedMapOf("type" to "sample", "timeMs" to (now - startedAt) / 1_000_000.0,
            "phase" to phase, "states" to values, "errors" to errors))
    }

    /** Geometry events preserve even paths that start and finish between periodic samples. */
    fun recordPath(path: FieldPath) {
        writer?.offer(linkedMapOf("type" to "path", "timeMs" to (clock() - startedAt) / 1_000_000.0,
            "id" to path.id, "points" to path.points.map { mapOf("x" to it.x, "y" to it.y) }))
    }

    /** Never wait for disk on the FTC stop callback. The writer drains and closes independently. */
    fun stop() {
        writer?.close()
        states.clear()
    }

    internal fun awaitClosed() = writer?.awaitClosed()

    /** Each value counts once; strings, enum names, and map keys also count their characters. */
    private fun detach(value: Any?, depth: Int = 0, consume: (values: Int, characters: Int) -> Unit): Any? {
        consume(1, 0)
        require(depth <= 8) { "Recording state is too deeply nested" }
        return when (value) {
            null, is Boolean -> value
            is String -> value.take(4096).also { consume(0, it.length) }
            is Number -> if (!value.toDouble().isFinite()) null else when (value) {
                is Byte, is Short, is Int, is Long, is Float, is Double -> value
                else -> value.toDouble()
            }
            is Enum<*> -> value.name.also { consume(0, it.length) }
            is Pose -> if (value.x.isFinite() && value.y.isFinite() && value.theta.isFinite())
                mapOf("x" to value.x, "y" to value.y, "heading" to value.theta) else null
            is Map<*, *> -> {
                require(value.size <= 256 && value.keys.all { it is String })
                value.entries.associate {
                    val key = it.key as String
                    consume(0, key.length)
                    key to detach(it.value, depth + 1, consume)
                }
            }
            is Iterable<*> -> value.take(257).also { require(it.size <= 256) }
                .map { detach(it, depth + 1, consume) }
            else -> error("Record scalars, enums, poses, lists, or string-keyed maps")
        }
    }
}

internal class RecordingWriter(
    private val file: File,
    private val header: Map<String, Any?>,
    private val config: RecordingConfig,
    private val openOutput: () -> OutputStream = { file.outputStream() },
) {
    private val queue = ArrayBlockingQueue<Map<String, Any?>>(config.queueCapacity)
    private val dropped = AtomicLong()
    @Volatile private var closing = false
    @Volatile var accepting = true
        private set
    private val thread = Thread({ write() }, "match-recorder").apply { isDaemon = true; start() }

    fun offer(sample: Map<String, Any?>) {
        if (accepting && !queue.offer(sample)) dropped.incrementAndGet()
    }

    fun close() { accepting = false; closing = true }
    fun awaitClosed() { thread.join(5_000); check(!thread.isAlive) }

    private fun write() {
        try {
            val gson = GsonBuilder().serializeNulls().create()
            fun encode(value: Any) = (gson.toJson(value) + "\n").toByteArray(Charsets.UTF_8)
            fun footer(droppedSamples: Long, sizeLimitReached: Boolean) = encode(mapOf(
                "type" to "end", "droppedSamples" to droppedSamples, "sizeLimitReached" to sizeLimitReached,
            ))
            // Reserve the largest footer before admitting any samples. False
            // is longer than true, and the drop count can grow while writing.
            val contentLimit = config.maxBytes - footer(Long.MAX_VALUE, false).size
            val encodedHeader = encode(header)
            require(encodedHeader.size <= contentLimit) { "Recording byte cap cannot fit its header and footer" }

            // Invalid metadata must not evict older recordings or create an
            // unusable file before the writer discovers that it cannot fit.
            file.parentFile?.mkdirs()
            RecordingFiles.beginWrite(file, config.keepFiles, config.storageBytes - config.maxBytes)
            openOutput().buffered().use { output ->
                var bytes = encodedHeader.size.toLong()
                output.write(encodedHeader)
                output.flush()
                var flushedAt = System.nanoTime()
                var sizeLimitReached = false
                while (!closing || queue.isNotEmpty()) {
                    val sample = queue.poll(100, TimeUnit.MILLISECONDS)
                    if (sample != null) {
                        val encoded = encode(sample)
                        if (encoded.size > contentLimit - bytes) {
                            sizeLimitReached = true
                            accepting = false
                            dropped.addAndGet(queue.size.toLong() + 1)
                            queue.clear()
                            break
                        }
                        output.write(encoded)
                        bytes += encoded.size
                    }
                    if (System.nanoTime() - flushedAt >= 1_000_000_000) {
                        output.flush()
                        flushedAt = System.nanoTime()
                    }
                }
                output.write(footer(dropped.get(), sizeLimitReached))
            }
        } catch (error: Exception) {
            RobotLog.ee("MatchRecorder", error, "Recording stopped: %s", file.name)
        } finally {
            accepting = false
            RecordingFiles.endWrite(file, config.keepFiles, config.storageBytes)
        }
    }
}
