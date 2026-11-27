package gentoo.myrelib.network

import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldWebHandlersTest {
    @Test
    fun `the viewer route serves HTML for navigation and JSON for live polling`() {
        val page = "<!doctype html><title>Viewer</title>"
        val response = FieldWebHandlers.visualizerPage(
            request(NanoHTTPD.Method.GET, "text/html,application/xhtml+xml"),
        ) { page.byteInputStream() }
        assertEquals("text/html; charset=utf-8", response.mimeType)
        assertEquals(page, response.data.bufferedReader().use { it.readText() })
        assertEquals("Accept", response.getHeader("Vary"))

        val feed = FieldWebHandlers.visualizerPage(request(NanoHTTPD.Method.GET, "application/json")) {
            error("A live data request should not open the HTML asset")
        }
        assertEquals("application/json", feed.mimeType)
        val snapshot = feed.data.bufferedReader().use { JsonParser().parse(it).asJsonObject }
        assertTrue(snapshot.has("states"))
        assertEquals("no-store", feed.getHeader("Cache-Control"))

        val rejected = FieldWebHandlers.visualizerPage(request(NanoHTTPD.Method.POST, "application/json")) {
            error("The viewer route is read-only")
        }
        assertEquals(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, rejected.status)
    }

    private fun request(method: NanoHTTPD.Method, accept: String): NanoHTTPD.IHTTPSession =
        Proxy.newProxyInstance(
            NanoHTTPD.IHTTPSession::class.java.classLoader,
            arrayOf(NanoHTTPD.IHTTPSession::class.java),
        ) { _, member, _ ->
            when(member.name) {
                "getMethod" -> method
                "getHeaders" -> mapOf("accept" to accept)
                else -> error("Unexpected request access: ${member.name}")
            }
        } as NanoHTTPD.IHTTPSession
}
