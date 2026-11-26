package dev.renoo.sdk.upload

import dev.renoo.sdk.core.SdkLogger
import java.net.URL

/** What `GET /health-check` said. */
internal enum class HealthStatus {
    /** `200`: the app is enabled. */
    OK,

    /** `401`: unknown or blocked key, the kill switch. */
    UNAUTHORIZED,

    /** Network error, timeout, `5xx` or any other status: carry on; uploads will find out. */
    UNKNOWN,
}

/**
 * `GET <ingestUrl>/health-check`, once per start-up, after `configure`. It carries only the API
 * key. Synchronous: **call it on the SDK executor** only. It never throws.
 * Capture does not wait for it.
 */
internal class HealthCheck(
    private val url: URL,
    private val apiKey: String,
    private val transport: HttpTransport,
    private val logger: SdkLogger,
) {
    fun run(): HealthStatus {
        val request = HttpRequest(
            url = url,
            headers = linkedMapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json"),
            method = "GET",
        )
        val status = try {
            transport.send(request).status
        } catch (t: Throwable) {
            logger.debug { "health check: request failed: ${t.javaClass.simpleName}; carrying on" }
            return HealthStatus.UNKNOWN
        }
        logger.debug { "health check: status=$status" }
        return when (status) {
            200 -> HealthStatus.OK
            401 -> HealthStatus.UNAUTHORIZED
            else -> {
                logger.info { "health check: unexpected HTTP $status; carrying on" }
                HealthStatus.UNKNOWN
            }
        }
    }
}
