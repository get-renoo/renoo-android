package dev.renoo.sdk.upload

import java.net.URI
import java.net.URL

/**
 * Turns `Configuration.ingestUrl` into the SDK's three endpoints on the same base URL:
 * `POST <base>/events/sdk` (uploads), `GET <base>/health-check` and
 * `GET <base>/offers`. Pure (no DNS, no I/O), so it is safe on the main thread inside `configure`.
 *
 * Rules: `null` or blank → uploading off (capture only). Scheme must be `https`; `http` only when
 * `allowInsecureHttp` is set (debug, local API). No other scheme, no user info (credentials in a
 * URL would end up in logs), no query or fragment. A path is kept (`https://h/api` → `https://h/api/events/sdk`)
 * and trailing slashes are ignored.
 */
internal object IngestEndpoint {
    const val EVENTS_PATH: String = "/events/sdk"
    const val HEALTH_CHECK_PATH: String = "/health-check"
    const val OFFERS_PATH: String = "/offers"

    sealed interface Resolution {
        /** No URL configured: capture only, no network code runs. */
        data object Disabled : Resolution

        /** A URL was configured but is not acceptable. [reason] never contains the URL itself. */
        data class Rejected(val reason: String) : Resolution

        /** [events] is where uploads go; the other two are the start-up reads. All share one base. */
        data class Enabled(val events: URL, val healthCheck: URL, val offers: URL) : Resolution
    }

    fun resolve(baseUrl: String?, allowInsecureHttp: Boolean): Resolution {
        val trimmed = baseUrl?.trim()
        if (trimmed.isNullOrEmpty()) return Resolution.Disabled
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase()
            when {
                scheme == null -> Resolution.Rejected("ingestUrl has no scheme; use https://")
                scheme == "http" && !allowInsecureHttp ->
                    Resolution.Rejected("ingestUrl uses http://; only https:// is allowed (allowInsecureHttp is a debug-only setting)")
                scheme != "https" && scheme != "http" -> Resolution.Rejected("ingestUrl scheme '$scheme' is not supported; use https://")
                uri.host.isNullOrEmpty() -> Resolution.Rejected("ingestUrl has no host")
                uri.rawUserInfo != null -> Resolution.Rejected("ingestUrl must not contain user info")
                uri.rawQuery != null || uri.rawFragment != null -> Resolution.Rejected("ingestUrl must not contain a query or fragment")
                else -> {
                    val base = "$scheme://${uri.rawAuthority}" + (uri.rawPath ?: "").trimEnd('/')
                    Resolution.Enabled(
                        events = URL(base + EVENTS_PATH),
                        healthCheck = URL(base + HEALTH_CHECK_PATH),
                        offers = URL(base + OFFERS_PATH),
                    )
                }
            }
        } catch (t: Throwable) {
            Resolution.Rejected("ingestUrl is not a valid URL")
        }
    }
}
