package dev.renoo.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationTest {
    private val handler = PurchaseHandler { _, onResult -> onResult(PurchaseResult.CANCELLED) }

    @Test
    fun subscriptionProductIdsDefaultToEmpty() {
        assertTrue(Configuration(apiKey = "k", purchaseHandler = handler).subscriptionProductIds.isEmpty())
        // Positional callers: automaticScreenTracking follows trackingScope (no session timeout parameter).
        val positional = Configuration("k", handler, LogLevel.DEBUG, TrackingScope.EVERYONE, false)
        assertTrue(positional.subscriptionProductIds.isEmpty())
        assertEquals(false, positional.automaticScreenTracking)
    }

    @Test
    fun uploadingIsOffAndHttpsOnlyByDefault() {
        val configuration = Configuration(apiKey = "k", purchaseHandler = handler)
        assertEquals(null, configuration.ingestUrl)
        assertEquals(false, configuration.allowInsecureHttp)
        val named = Configuration(apiKey = "k", purchaseHandler = handler, ingestUrl = "https://ingest.example.com", allowInsecureHttp = true)
        assertEquals("https://ingest.example.com", named.ingestUrl)
        assertTrue(named.allowInsecureHttp)
    }

    @Test
    fun subscriptionProductIdsAreSanitised() {
        val configuration = Configuration(
            apiKey = "k",
            purchaseHandler = handler,
            subscriptionProductIds = listOf("  monthly ", "", "   ", "annual", "monthly", "x".repeat(Configuration.MAX_PRODUCT_ID_LENGTH + 1)),
        )
        assertEquals(listOf("monthly", "annual"), configuration.subscriptionProductIds)
    }

    @Test
    fun subscriptionProductIdsAreCapped() {
        val many = (1..50).map { "product_$it" }
        val ids = Configuration(apiKey = "k", purchaseHandler = handler, subscriptionProductIds = many).subscriptionProductIds
        assertEquals(Configuration.MAX_SUBSCRIPTION_PRODUCT_IDS, ids.size)
        assertEquals(many.take(Configuration.MAX_SUBSCRIPTION_PRODUCT_IDS), ids)
    }

    @Test
    fun nullEntriesFromJavaAreDropped() {
        @Suppress("UNCHECKED_CAST")
        val fromJava = java.util.Arrays.asList("monthly", null, "annual") as List<String>
        assertEquals(listOf("monthly", "annual"), Configuration(apiKey = "k", purchaseHandler = handler, subscriptionProductIds = fromJava).subscriptionProductIds)
    }
}
