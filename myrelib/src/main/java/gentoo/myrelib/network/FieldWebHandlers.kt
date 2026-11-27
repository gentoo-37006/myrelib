package gentoo.myrelib.network

import android.content.Context
import com.qualcomm.robotcore.util.WebHandlerManager
import fi.iki.elonen.NanoHTTPD
import org.firstinspires.ftc.ftccommon.external.WebHandlerRegistrar
import com.google.gson.Gson
import java.io.InputStream

/** These myrelib handlers and their bridge stay outside Sloth's TeamCode package. */
object FieldWebHandlers {
    @JvmStatic
    @WebHandlerRegistrar
    fun register(context: Context, manager: WebHandlerManager) {
        RecordingFiles.attach(context.filesDir)
        manager.register("/visualizer/recordings") { session ->
            if (!isRead(session)) methodNotAllowed()
            else noCache(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json",
                Gson().toJson(RecordingFiles.list().map { mapOf("name" to it.name, "bytes" to it.length()) })))
        }
        manager.register("/visualizer/recording") { session ->
            val file = session.parameters["name"]?.firstOrNull()?.let(RecordingFiles::find)
            if (!isRead(session)) methodNotAllowed()
            else if (file == null) noCache(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Recording not found"))
            else noCache(NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "application/x-ndjson", file.inputStream())).apply {
                addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
            }
        }
        fun registerAsset(route: String, asset: String) {
            manager.register(route) { session ->
                if (!isRead(session)) methodNotAllowed()
                else noCache(NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, mimeType(asset), context.assets.open(asset)))
            }
        }

        fun registerDirectory(path: String) {
            context.assets.list(path).orEmpty().forEach { name ->
                val asset = "$path/$name"
                if (context.assets.list(asset).orEmpty().isNotEmpty()) registerDirectory(asset)
                else registerAsset("/visualizer/" + asset.removePrefix("field-viewer/"), asset)
            }
        }

        registerDirectory("field-viewer")
        for (route in listOf("/visualizer", "/visualizer/")) {
            manager.register(route) { session ->
                visualizerPage(session) { context.assets.open("field-viewer/index.html") }
            }
        }
    }

    internal fun visualizerPage(session: NanoHTTPD.IHTTPSession, openPage: () -> InputStream): NanoHTTPD.Response {
        if (!isRead(session)) return methodNotAllowed()
        val response = if (session.headers["accept"]?.trim()?.equals("application/json", ignoreCase = true) == true) {
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", RobotTelemetryBridge.json())
        } else {
            NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, mimeType("index.html"), openPage())
        }
        return noCache(response).apply { addHeader("Vary", "Accept") }
    }

    private fun isRead(session: NanoHTTPD.IHTTPSession) = session.method == NanoHTTPD.Method.GET || session.method == NanoHTTPD.Method.HEAD

    private fun methodNotAllowed() = noCache(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Read-only endpoint")).apply {
        addHeader("Allow", "GET, HEAD")
    }

    private fun noCache(response: NanoHTTPD.Response) = response.apply {
        addHeader("Cache-Control", "no-store")
        addHeader("X-Content-Type-Options", "nosniff")
    }

    private fun mimeType(path: String) = when (path.substringAfterLast('.')) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "application/javascript"
        "css" -> "text/css"
        "webp" -> "image/webp"
        "png" -> "image/png"
        else -> "text/plain"
    }
}
