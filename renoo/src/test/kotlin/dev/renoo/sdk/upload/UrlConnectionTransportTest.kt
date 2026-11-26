package dev.renoo.sdk.upload

import dev.renoo.sdk.LogLevel
import dev.renoo.sdk.core.SdkLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * The real `HttpURLConnection` transport over a real socket. `com.sun.net.httpserver` is not on the
 * Android unit-test classpath, so this is a minimal one-request HTTP/1.1 server on localhost.
 */
class UrlConnectionTransportTest {
    private class Received(val method: String, val headers: Map<String, String>, val body: ByteArray)

    private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val scheduler = TimerScheduler(SdkLogger(LogLevel.NONE) { _, _ -> })
    private val url get() = URL("http://127.0.0.1:${socket.localPort}/events/sdk")

    @After
    fun tearDown() = socket.close()

    /** Serves one request on a background thread with [reply] (status line + headers + body). */
    private fun serveOnce(delayMillis: Long = 0, reply: (Received) -> String): AtomicReference<Received> {
        val seen = AtomicReference<Received>()
        thread(isDaemon = true) {
            try {
                socket.accept().use { client ->
                    val input = client.getInputStream()
                    val head = readHead(input).split("\r\n")
                    val headers = head.drop(1).filter { it.contains(':') }
                        .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                    val length = headers["content-length"]?.toInt() ?: 0
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) read += input.read(body, read, length - read).also { if (it < 0) return@thread }
                    val received = Received(head[0].substringBefore(' '), headers, body)
                    seen.set(received)
                    if (delayMillis > 0) Thread.sleep(delayMillis)
                    client.getOutputStream().apply {
                        write(reply(received).toByteArray())
                        flush()
                    }
                }
            } catch (ignored: Throwable) {
                // Socket closed by the test.
            }
        }
        return seen
    }

    private fun readHead(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (!out.toString("ISO-8859-1").endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
        }
        return out.toString("ISO-8859-1")
    }

    private fun response(status: String, body: String, extra: String = "") =
        "HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n${extra}Connection: close\r\n\r\n$body"

    @Test
    fun postsHeadersAndGzipBodyAndReadsTheResponse() {
        val seen = serveOnce { response("202 Accepted", """{"accepted": 1, "rejected": []}""") }
        val result = UrlConnectionTransport(scheduler, 2_000, 5_000).send(
            HttpRequest(
                url,
                mapOf(
                    "Authorization" to "Bearer pk_test",
                    "Content-Type" to "application/x-ndjson",
                    "Content-Encoding" to "gzip",
                    "X-Renoo-Batch-Id" to "b-1",
                ),
                BatchEncoder.gzip(BatchEncoder.ndjson(listOf("""{"event_id":"e1"}"""))),
            ),
        )
        assertEquals(202, result.status)
        assertTrue(result.body.contains("\"accepted\": 1"))
        val received = seen.get()
        assertEquals("POST", received.method)
        assertEquals("Bearer pk_test", received.headers["authorization"])
        assertEquals("application/x-ndjson", received.headers["content-type"])
        assertEquals("gzip", received.headers["content-encoding"])
        assertEquals("b-1", received.headers["x-renoo-batch-id"])
        assertEquals("{\"event_id\":\"e1\"}\n", GZIPInputStream(received.body.inputStream()).readBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun readsErrorStatusAndRetryAfter() {
        serveOnce { response("429 Too Many Requests", """{"error": "rate_limited"}""", extra = "Retry-After: 120\r\n") }
        val result = UrlConnectionTransport(scheduler, 2_000, 5_000).send(HttpRequest(url, emptyMap(), byteArrayOf(1, 2, 3)))
        assertEquals(429, result.status)
        assertEquals("120", result.retryAfter)
        assertTrue(result.body.contains("rate_limited"))
    }

    @Test
    fun redirectsAreNotFollowed() {
        serveOnce { response("307 Temporary Redirect", "", extra = "Location: http://127.0.0.1:1/elsewhere\r\n") }
        val result = UrlConnectionTransport(scheduler, 2_000, 5_000).send(HttpRequest(url, mapOf("Authorization" to "Bearer k"), byteArrayOf(1)))
        assertEquals(307, result.status)
    }

    @Test
    fun aStuckServerHitsTheRequestDeadline() {
        serveOnce(delayMillis = 5_000) { response("202 Accepted", "{}") }
        val started = System.nanoTime()
        try {
            UrlConnectionTransport(scheduler, 1_000, 700).send(HttpRequest(url, emptyMap(), byteArrayOf(1)))
            fail("expected a timeout")
        } catch (e: IOException) {
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertTrue("took $elapsed ms", elapsed < 3_000)
        }
    }

    @Test
    fun connectionRefusedIsAnIOException() {
        val port = socket.localPort
        socket.close()
        try {
            UrlConnectionTransport(scheduler, 1_000, 1_000).send(HttpRequest(URL("http://127.0.0.1:$port/events/sdk"), emptyMap(), byteArrayOf(1)))
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
    }
}
