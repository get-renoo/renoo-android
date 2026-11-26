package dev.renoo.sdk.upload

import dev.renoo.sdk.upload.IngestEndpoint.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestEndpointTest {
    private fun url(base: String?, insecure: Boolean = false): String? =
        (IngestEndpoint.resolve(base, insecure) as? Resolution.Enabled)?.events?.toString()

    @Test
    fun appendsTheEventsPath() {
        assertEquals("https://ingest.example.com/events/sdk", url("https://ingest.example.com"))
    }

    @Test
    fun toleratesTrailingSlashesAndWhitespace() {
        assertEquals("https://ingest.example.com/events/sdk", url("https://ingest.example.com/"))
        assertEquals("https://ingest.example.com/events/sdk", url("  https://ingest.example.com//  "))
    }

    @Test
    fun keepsPortAndPath() {
        assertEquals("https://ingest.example.com:8443/api/events/sdk", url("https://ingest.example.com:8443/api/"))
        assertEquals("https://[::1]:8443/events/sdk", url("https://[::1]:8443"))
    }

    @Test
    fun schemeIsCaseInsensitive() {
        assertEquals("https://ingest.example.com/events/sdk", url("HTTPS://ingest.example.com"))
    }

    @Test
    fun healthCheckAndOffersShareTheBase() {
        val enabled = IngestEndpoint.resolve("https://api.example.com/base/", false) as Resolution.Enabled
        assertEquals("https://api.example.com/base/events/sdk", enabled.events.toString())
        assertEquals("https://api.example.com/base/health-check", enabled.healthCheck.toString())
        assertEquals("https://api.example.com/base/offers", enabled.offers.toString())
    }

    @Test
    fun nullOrBlankDisablesUploading() {
        assertEquals(Resolution.Disabled, IngestEndpoint.resolve(null, false))
        assertEquals(Resolution.Disabled, IngestEndpoint.resolve("   ", true))
    }

    @Test
    fun httpIsRejectedUnlessTheDebugFlagIsSet() {
        val rejected = IngestEndpoint.resolve("http://10.0.2.2:8788", allowInsecureHttp = false)
        assertTrue(rejected is Resolution.Rejected)
        assertTrue((rejected as Resolution.Rejected).reason.contains("https"))
        assertEquals("http://10.0.2.2:8788/events/sdk", url("http://10.0.2.2:8788", insecure = true))
    }

    @Test
    fun otherSchemesAreRejectedEvenWithTheDebugFlag() {
        for (base in listOf("ftp://example.com", "ws://example.com", "file:///tmp/x", "example.com", "//example.com")) {
            assertTrue(base, IngestEndpoint.resolve(base, allowInsecureHttp = true) is Resolution.Rejected)
        }
    }

    @Test
    fun userInfoQueryAndFragmentAreRejectedWithoutEchoingTheUrl() {
        for (base in listOf("https://user:secret@example.com", "https://example.com?key=1", "https://example.com#x", "https://", "https://exa mple.com")) {
            val result = IngestEndpoint.resolve(base, allowInsecureHttp = false)
            assertTrue(base, result is Resolution.Rejected)
            assertFalse("reason must not echo credentials", (result as Resolution.Rejected).reason.contains("secret"))
        }
    }
}
