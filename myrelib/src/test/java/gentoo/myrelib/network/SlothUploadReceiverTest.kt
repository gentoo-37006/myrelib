package gentoo.myrelib.network

import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method.GET
import fi.iki.elonen.NanoHTTPD.Method.PUT
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SlothUploadReceiverTest {
    @get:Rule val temporary = TemporaryFolder()

    private val key = "ab".repeat(32)
    private val payload = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write("dex\n".toByteArray())
        }
    }.toByteArray()

    @Test
    fun `only a paired signature can stage a payload and it cannot be replayed`() {
        val directory = temporary.newFolder()
        val receiver = SlothUploadReceiver(key, directory)
        val digest = sha256(payload)
        val signature = signature(challenge(receiver), digest)

        assertEquals(Status.UNAUTHORIZED, receiver.respond(upload(digest, "00".repeat(32))).status)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertEquals(Status.ACCEPTED, receiver.respond(upload(digest, signature, payload)).status)
        assertArrayEquals(payload, directory.listFiles()!!.single().readBytes())

        challenge(receiver)
        assertEquals(Status.UNAUTHORIZED, receiver.respond(upload(digest, signature)).status)
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test
    fun `an authenticated payload must arrive intact before Sloth can see it`() {
        val directory = temporary.newFolder()
        val receiver = SlothUploadReceiver(key, directory)
        val digest = sha256(payload)
        assertEquals(Status.BAD_REQUEST, receiver.respond(upload(digest, signature(challenge(receiver), digest),
            payload.copyOf().apply { this[0] = 0 })).status)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    private fun challenge(receiver: SlothUploadReceiver): String =
        receiver.respond(request(GET)).data.bufferedReader().use { JsonParser().parse(it).asJsonObject["challenge"].asString }

    private fun upload(digest: String, signature: String, body: ByteArray? = null) = request(
        PUT, mapOf("content-length" to payload.size.toString(), "x-yb-signature" to signature),
        mapOf("sha256" to listOf(digest)), body,
    )

    private fun request(
        method: NanoHTTPD.Method,
        headers: Map<String, String> = emptyMap(),
        params: Map<String, List<String>> = emptyMap(),
        body: ByteArray? = null,
    ): NanoHTTPD.IHTTPSession = Proxy.newProxyInstance(
        NanoHTTPD.IHTTPSession::class.java.classLoader, arrayOf(NanoHTTPD.IHTTPSession::class.java),
    ) { _, member, _ ->
        when (member.name) {
            "getMethod" -> method
            "getHeaders" -> headers
            "getParameters" -> params
            "getInputStream" -> checkNotNull(body) { "An unauthorized request must not read the body" }.inputStream()
            else -> error("Unexpected request access: ${member.name}")
        }
    } as NanoHTTPD.IHTTPSession

    private fun signature(challenge: String, digest: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        return mac.doFinal("yb-sloth-v3\n$challenge\n$digest".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
