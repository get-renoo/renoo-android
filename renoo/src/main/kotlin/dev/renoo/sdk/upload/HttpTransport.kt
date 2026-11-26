package dev.renoo.sdk.upload

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal class HttpRequest(
    val url: URL,
    val headers: Map<String, String>,
    /** Sent only with `POST`; ignored for `GET`. */
    val body: ByteArray = ByteArray(0),
    /** `POST` (uploads) or `GET` (health check, offers). */
    val method: String = "POST",
) {
    // Never print headers: Authorization carries the SDK key.
    override fun toString(): String = "HttpRequest($method, ${body.size} bytes)"
}

internal class HttpResponse(
    val status: Int,
    /** `Retry-After` header value, if any. */
    val retryAfter: String?,
    /** At most [UrlConnectionTransport.MAX_RESPONSE_BYTES] of the body, UTF-8. */
    val body: String,
)

/**
 * Sends one request, synchronously, on the calling thread (the SDK executor). Throws
 * [IOException] for network errors and timeouts; the uploader treats those like a `5xx`.
 * Tests inject fakes; production uses [UrlConnectionTransport].
 */
internal fun interface HttpTransport {
    @Throws(IOException::class)
    fun send(request: HttpRequest): HttpResponse
}

/**
 * Plain `HttpURLConnection`, no third-party client. Connect timeout and a
 * whole-request deadline: `HttpURLConnection` only has per-read timeouts, so a watchdog on
 * [scheduler] calls `disconnect()` when the deadline passes (the blocked read then throws).
 * No caches, no redirects (a redirect would carry the `Authorization` header elsewhere).
 */
internal class UrlConnectionTransport(
    private val scheduler: Scheduler,
    private val connectTimeoutMillis: Int,
    private val requestTimeoutMillis: Int,
) : HttpTransport {
    override fun send(request: HttpRequest): HttpResponse {
        val connection = request.url.openConnection() as HttpURLConnection
        val watchdog = scheduler.schedule(requestTimeoutMillis.toLong()) {
            try {
                connection.disconnect()
            } catch (ignored: Throwable) {
                // Best effort.
            }
        }
        try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = requestTimeoutMillis
            val isPost = request.method == "POST"
            connection.requestMethod = request.method
            connection.doOutput = isPost
            connection.doInput = true
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            if (isPost) connection.setFixedLengthStreamingMode(request.body.size)
            for ((name, value) in request.headers) connection.setRequestProperty(name, value)
            if (isPost) connection.outputStream.use { it.write(request.body) }
            val status = connection.responseCode
            val retryAfter = connection.getHeaderField("Retry-After")
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use(::readBounded).orEmpty()
            return HttpResponse(status, retryAfter, body)
        } catch (e: IOException) {
            throw e
        } catch (t: Throwable) {
            // Anything odd from the platform (e.g. SecurityException without INTERNET) is a failed attempt.
            throw IOException("request failed: ${t.javaClass.simpleName}", t)
        } finally {
            watchdog.cancel()
            try {
                connection.disconnect()
            } catch (ignored: Throwable) {
                // Best effort.
            }
        }
    }

    private fun readBounded(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() < MAX_RESPONSE_BYTES) {
            val read = stream.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toString("UTF-8")
    }

    companion object {
        const val MAX_RESPONSE_BYTES: Int = 64 * 1024
    }
}
