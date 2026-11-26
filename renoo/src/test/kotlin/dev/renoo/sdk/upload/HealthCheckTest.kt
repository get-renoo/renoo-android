package dev.renoo.sdk.upload

import dev.renoo.sdk.LogLevel
import dev.renoo.sdk.core.SdkLogger
import dev.renoo.sdk.upload.FakeTransport.Companion.networkError
import dev.renoo.sdk.upload.FakeTransport.Companion.status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.URL

/** `GET /health-check`: request shape and the status table. */
class HealthCheckTest {
    private val logs = mutableListOf<String>()
    private val transport = FakeTransport { 0L }
    private val check = HealthCheck(
        URL("https://api.example.com/health-check"),
        "pk_test_SECRET",
        transport,
        SdkLogger(LogLevel.DEBUG) { _, message -> logs.add(message) },
    )

    @Test
    fun sendsAnAuthenticatedGetWithNoBody() {
        transport.responder = { status(200) }
        check.run()
        val request = transport.sent.single().request
        assertEquals("GET", request.method)
        assertEquals("https://api.example.com/health-check", request.url.toString())
        assertEquals("Bearer pk_test_SECRET", request.headers["Authorization"])
        assertEquals(0, request.body.size)
        assertFalse(logs.any { it.contains("pk_test_SECRET") })
    }

    @Test
    fun ok200() {
        transport.responder = { status(200) }
        assertEquals(HealthStatus.OK, check.run())
    }

    @Test
    fun unauthorized401() {
        transport.responder = { status(401) }
        assertEquals(HealthStatus.UNAUTHORIZED, check.run())
    }

    @Test
    fun serverErrorsAnd403AreUnknown() {
        for (code in listOf(500, 502, 503, 403, 404)) {
            transport.responder = { status(code) }
            assertEquals("$code", HealthStatus.UNKNOWN, check.run())
        }
    }

    @Test
    fun networkErrorIsUnknown() {
        transport.responder = { networkError() }
        assertEquals(HealthStatus.UNKNOWN, check.run())
    }
}
