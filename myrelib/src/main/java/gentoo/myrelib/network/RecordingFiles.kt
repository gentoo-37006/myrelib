package gentoo.myrelib.network

import java.io.File
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** App-lifetime file access only; never stores hot-swapped state suppliers. */
object RecordingFiles {
    @Volatile private var directory: File? = null
    private val active = mutableSetOf<String>()

    @Synchronized
    fun beginWrite(file: File, keepFiles: Int, previousBytes: Long) {
        active.add(file.absolutePath)
        prune(file, keepFiles - 1, previousBytes)
    }

    @Synchronized
    fun endWrite(file: File, keepFiles: Int, storageBytes: Long) {
        if (active.remove(file.absolutePath)) prune(file, keepFiles, storageBytes)
    }

    // Called under this object's monitor so beginning another write cannot race
    // with deletion. Active files remain exempt until their own writer closes.
    private fun prune(file: File, keepFiles: Int, storageBytes: Long) {
        var bytes = 0L
        var count = 0
        file.parentFile?.listFiles()?.filter { it.isFile && it.name.endsWith(".jsonl") }
            ?.sortedByDescending { timestamp(it.name) }?.forEach {
                if (it.absolutePath !in active) {
                    bytes += it.length()
                    count++
                    if (count > keepFiles || bytes > storageBytes) it.delete()
                }
            }
    }

    fun attach(filesDir: File) { directory = File(filesDir, "match-recordings") }

    fun newFile(): File? = directory?.let {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        File(it, "$stamp-${UUID.randomUUID()}.jsonl")
    }

    fun list(): List<File> = directory?.listFiles()?.filter {
        it.isFile && it.name.endsWith(".jsonl")
    }?.sortedByDescending { timestamp(it.name) } ?: emptyList()

    fun find(name: String): File? {
        return directory?.let { resolve(it, name) }
    }

    internal fun resolve(directory: File, name: String): File? {
        if (!Regex("(?:[0-9]+|[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}-[0-9]{2}-[0-9]{2}\\.[0-9]{3}Z)-[a-fA-F0-9-]+\\.jsonl").matches(name)) return null
        return File(directory, name).takeIf { it.isFile }
    }

    internal fun timestamp(name: String): Long = if(name.contains('T')) {
        runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }.parse(name.take(24))!!.time }.getOrDefault(0L)
    } else name.substringBefore('-').toLongOrNull() ?: 0L
}
