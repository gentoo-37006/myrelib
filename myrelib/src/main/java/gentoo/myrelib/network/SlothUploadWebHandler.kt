package gentoo.myrelib.network

import android.content.Context
import com.google.gson.Gson
import com.qualcomm.robotcore.util.WebHandlerManager
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.firstinspires.ftc.ftccommon.external.WebHandlerRegistrar
import org.firstinspires.ftc.robotcore.internal.system.AppUtil
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Makes paired uploads available in apps with Sloth. */
object SlothUploadWebHandler {
    @JvmStatic
    @WebHandlerRegistrar
    fun register(context: Context, manager: WebHandlerManager) {
        try {
            Class.forName("dev.frozenmilk.sinister.SlothTeamCodeLoader", false, javaClass.classLoader)
        } catch (_: ClassNotFoundException) {
            return
        }

        val keyFile = File(context.filesDir, "yb-sloth.key")
        val directory = File(AppUtil.FIRST_FOLDER, "dairy/sloth")
        var receiver: SlothUploadReceiver? = null
        manager.register("/yb/sloth") { session ->
            // A full push may pair the key just after the app's server starts.
            val paired = synchronized(keyFile) {
                receiver ?: readKey(keyFile)?.let { key -> SlothUploadReceiver(key, directory).also { receiver = it } }
            }
            paired?.respond(session) ?: NanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "HTTP hot loads are not paired.")
        }
    }

    private fun readKey(file: File): String? = try {
        file.readText().trim().takeIf(HEX_DIGEST::matches)
    } catch (_: IOException) {
        null
    }
}

private val HEX_DIGEST = Regex("[0-9a-f]{64}")

internal class SlothUploadReceiver(key: String, private val directory: File) {
    private val signingKey = SecretKeySpec(key.toByteArray(Charsets.US_ASCII), "HmacSHA256")
    private val keyId = sha256(signingKey.encoded)
    private val gson = Gson()
    private var challenge = ""
    private var issuedAt = 0L

    @Synchronized
    fun respond(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = when (session.method) {
        NanoHTTPD.Method.GET -> {
            if (challenge.isEmpty() || expired()) {
                challenge = UUID.randomUUID().toString()
                issuedAt = System.nanoTime()
            }
            json(Status.OK, mapOf(
                "endpoint" to "yb sloth upload", "version" to 3, "keyId" to keyId, "challenge" to challenge,
            ))
        }
        NanoHTTPD.Method.PUT -> receive(session)
        else -> reject(Status.METHOD_NOT_ALLOWED, "Use GET to obtain a challenge or PUT to upload.").apply {
            addHeader("Allow", "GET, PUT")
        }
    }

    private fun expired() = System.nanoTime() - issuedAt >= 60_000_000_000L

    private fun receive(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val digest = session.parameters["sha256"]?.singleOrNull()
        val signature = session.headers["x-yb-signature"]
        if (digest == null || !HEX_DIGEST.matches(digest) || signature == null ||
            !HEX_DIGEST.matches(signature) || challenge.isEmpty() || expired()
        ) return reject(Status.UNAUTHORIZED, "Obtain a fresh challenge and sign the upload with the paired key.")

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(signingKey)
        val expected = hex(mac.doFinal("yb-sloth-v3\n$challenge\n$digest".toByteArray(Charsets.US_ASCII)))
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), signature.toByteArray(Charsets.US_ASCII))) {
            return reject(Status.UNAUTHORIZED, "The upload signature is invalid.")
        }
        challenge = ""

        val length = session.headers["content-length"]?.toIntOrNull()
        if (length == null || length !in 1..MAX_BYTES) {
            return reject(Status.BAD_REQUEST, "Send a Content-Length between 1 and $MAX_BYTES bytes.")
        }
        val payload = ByteArray(length)
        try {
            DataInputStream(session.inputStream).readFully(payload)
        } catch (_: IOException) {
            return reject(Status.BAD_REQUEST, "The upload ended before the whole payload arrived.")
        }
        if (sha256(payload) != digest || !containsDex(payload)) {
            return reject(Status.BAD_REQUEST, "The upload must match its SHA-256 and contain classes.dex.")
        }
        if (!directory.isDirectory && !directory.mkdirs()) {
            return reject(Status.INTERNAL_ERROR, "Could not create the Sloth directory.")
        }
        if (File(directory, "sloth.lock").exists()) {
            return reject(Status.CONFLICT, "Sloth is already staging a load.")
        }

        // Sloth 0.3.2 watches file creation and retries opening the new jar while it is written.
        val file = File(directory, "yb-${UUID.randomUUID()}.jar")
        return try {
            file.writeBytes(payload)
            json(Status.ACCEPTED, mapOf("ok" to true, "sha256" to digest))
        } catch (_: IOException) {
            file.delete()
            reject(Status.INTERNAL_ERROR, "Could not write the Sloth payload.")
        }
    }

    private fun containsDex(payload: ByteArray): Boolean = try {
        ZipInputStream(ByteArrayInputStream(payload)).use { zip ->
            val dex = generateSequence { zip.nextEntry }.firstOrNull { it.name == "classes.dex" && !it.isDirectory }
            dex != null && DataInputStream(zip).readInt() == 0x6465780a // dex\n
        }
    } catch (_: IOException) {
        false
    }

    private fun reject(status: Status, reason: String) = json(status, mapOf("ok" to false, "error" to reason))

    private fun json(status: Status, values: Map<String, Any>) =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", gson.toJson(values)).apply {
            addHeader("Cache-Control", "no-store")
        }

    private fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
    }
}
