package dev.renoo.sdk.upload

import android.util.Log
import dev.renoo.sdk.LogLevel
import dev.renoo.sdk.core.ClearReason
import dev.renoo.sdk.core.PendingOffers
import dev.renoo.sdk.core.RequestIdentity
import dev.renoo.sdk.core.SdkLogger
import dev.renoo.sdk.upload.FakeTransport.Companion.networkError
import dev.renoo.sdk.upload.FakeTransport.Companion.status
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URL

/** `GET /offers` with a fake transport and clock. */
class OfferFetcherTest {
    private val logs = mutableListOf<Pair<Int, String>>()
    private val logger = SdkLogger(LogLevel.DEBUG) { priority, message -> logs.add(priority to message) }
    private var now = 0L
    private val transport = FakeTransport { now }
    private val mainQueue = ArrayDeque<() -> Unit>()
    private val offers = PendingOffers(logger) { mainQueue.addLast(it) }
    private val seen = mutableListOf<Map<String, Any?>?>()
    private var identity: RequestIdentity? = RequestIdentity(ANON, null)
    private var unauthorized = 0
    private val fetcher = OfferFetcher(
        url = URL("https://api.example.com/offers"),
        apiKey = KEY,
        transport = transport,
        offers = offers,
        logger = logger,
        identity = { identity },
        onUnauthorized = { unauthorized += 1 },
        clock = { now },
    )

    init {
        offers.addObserver { seen.add(it) }
        transport.responder = { offer() }
    }

    @After
    fun tearDown() {
        assertFalse("key logged", logs.any { it.second.contains(KEY) })
        assertFalse("payload logged at info+", logs.any { it.first >= Log.INFO && it.second.contains("20%") })
    }

    private fun runMain() {
        while (mainQueue.isNotEmpty()) mainQueue.removeFirst()()
    }

    private fun offer(body: String = OFFER) = HttpResponse(200, null, body)

    @Test
    fun requestIsAGetWithIdentityInHeadersOnly() {
        fetcher.onStartup()
        val sent = transport.sent.single()
        assertEquals("GET", sent.request.method)
        assertEquals("https://api.example.com/offers", sent.request.url.toString())
        assertNull(sent.request.url.query)
        assertFalse(sent.request.url.toString().contains(ANON))
        assertEquals("Bearer $KEY", sent.headers["Authorization"])
        assertEquals("application/json", sent.headers["Accept"])
        assertEquals(ANON, sent.headers["X-Renoo-Anonymous-Id"])
        assertFalse(sent.headers.containsKey("X-Renoo-User-Id"))
    }

    @Test
    fun userHeaderOnlyWhenIdentified() {
        identity = RequestIdentity(ANON, "user-42")
        fetcher.onStartup()
        val sent = transport.sent.single()
        assertEquals("user-42", sent.headers["X-Renoo-User-Id"])
        assertEquals(ANON, sent.headers["X-Renoo-Anonymous-Id"])
        assertFalse(sent.request.url.toString().contains("user-42"))
    }

    @Test
    fun ok200IsParsedExposedImmutableAndObserved() {
        fetcher.onStartup()
        val current = offers.current
        assertEquals("o-1", current?.get("offer_id"))
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf(1, 2), current?.get("ids") as List<Any?>)
        assertEquals(mapOf("title" to "20% off"), current?.get("template"))
        assertTrue(seen.isEmpty()) // Delivered through the main thread only.
        runMain()
        assertEquals(listOf(current), seen)
        try {
            @Suppress("UNCHECKED_CAST")
            (current as MutableMap<String, Any?>)["x"] = 1
            fail("offer must be immutable")
        } catch (expected: UnsupportedOperationException) {
        }
        try {
            @Suppress("UNCHECKED_CAST")
            (current?.get("template") as MutableMap<String, Any?>)["x"] = 1
            fail("nested maps must be immutable")
        } catch (expected: UnsupportedOperationException) {
        }
    }

    @Test
    fun noContent204ClearsAndNotifiesOnlyOnChange() {
        fetcher.onStartup()
        now += OfferFetcher.MIN_INTERVAL_MILLIS
        transport.responder = { HttpResponse(204, null, "") }
        fetcher.onForeground()
        now += OfferFetcher.MIN_INTERVAL_MILLIS
        fetcher.onForeground()
        runMain()
        assertNull(offers.current)
        assertEquals(2, seen.size)
        assertNull(seen.last())
    }

    @Test
    fun unauthorized401EngagesTheKillSwitchAndStops() {
        fetcher.onStartup()
        now += OfferFetcher.MIN_INTERVAL_MILLIS
        transport.responder = { status(401) }
        fetcher.onForeground()
        assertEquals(1, unauthorized)
        assertNull(offers.current)
        now += 10 * OfferFetcher.MIN_INTERVAL_MILLIS
        fetcher.onForeground()
        assertEquals(2, transport.sent.size)
    }

    @Test
    fun serviceUnavailableNetworkErrorAndBadJsonChangeNothingAndRetryOnNextForeground() {
        fetcher.onStartup()
        val before = offers.current
        val failures = listOf<() -> HttpResponse>(
            { status(503) },
            { status(500) },
            { networkError() },
            { offer("not json") },
            { offer("[1, 2]") },
            { offer("") },
        )
        now += OfferFetcher.MIN_INTERVAL_MILLIS
        for (failure in failures) {
            transport.responder = { failure() }
            fetcher.onForeground()
            assertEquals(before, offers.current)
        }
        // Every failure retried on the very next foreground: none of them started the 6-hour window.
        assertEquals(1 + failures.size, transport.sent.size)
        runMain()
        assertEquals(1, seen.size)
        assertEquals(0, unauthorized)
    }

    @Test
    fun foregroundIsThrottledToOnceEverySixHoursAfterASuccess() {
        fetcher.onStartup()
        now += 60 * 60_000L
        fetcher.onForeground()
        now += 4 * 60 * 60_000L
        fetcher.onForeground()
        assertEquals(1, transport.sent.size)
        now = OfferFetcher.MIN_INTERVAL_MILLIS
        fetcher.onForeground()
        assertEquals(2, transport.sent.size)
        fetcher.onForeground()
        assertEquals(2, transport.sent.size)
    }

    @Test
    fun foregroundBeforeTheStartUpFetchIsIgnored() {
        fetcher.onForeground()
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun skippedWithoutIdentityOptedOutOrNotTracked() {
        identity = null
        fetcher.onStartup()
        fetcher.onForeground()
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun fetchesOnceTheUserBecomesTrackedAfterAStartUpWhileUnknown() {
        identity = null // subscription state still unknown at start-up
        fetcher.onStartup()
        assertTrue(transport.sent.isEmpty())
        identity = RequestIdentity(ANON, null)
        fetcher.onTrackingGateChanged(open = true)
        assertEquals(1, transport.sent.size)
        assertEquals(ANON, transport.sent.single().headers["X-Renoo-Anonymous-Id"])
    }

    @Test
    fun becomingTrackedRespectsTheSixHourWindow() {
        fetcher.onStartup()
        now += 60 * 60_000L
        fetcher.onTrackingGateChanged(open = true)
        assertEquals(1, transport.sent.size)
        now = OfferFetcher.MIN_INTERVAL_MILLIS
        fetcher.onTrackingGateChanged(open = true)
        assertEquals(2, transport.sent.size)
    }

    @Test
    fun becomingTrackedBeforeTheStartUpFetchIsIgnored() {
        fetcher.onTrackingGateChanged(open = true)
        assertTrue(transport.sent.isEmpty())
        fetcher.onStartup()
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun gateClosingClearsTheOfferAndTheWindowAndSendsNothing() {
        fetcher.onStartup()
        assertEquals("o-1", offers.current?.get("offer_id"))
        identity = null // no longer tracked
        fetcher.onTrackingGateChanged(open = false)
        assertNull(offers.current)
        fetcher.onForeground()
        assertEquals(1, transport.sent.size)
        identity = RequestIdentity(ANON, null)
        fetcher.onTrackingGateChanged(open = true) // window was cleared: fetches at once
        assertEquals(2, transport.sent.size)
    }

    @Test
    fun optOutAndResetClearTheOfferAndTheThrottle() {
        fetcher.onStartup()
        fetcher.clearState(ClearReason.OPT_OUT)
        assertNull(offers.current)
        fetcher.onForeground()
        assertEquals(2, transport.sent.size)
        fetcher.onIdentityReset()
        assertNull(offers.current)
        fetcher.onForeground()
        assertEquals(3, transport.sent.size)
    }

    @Test
    fun aThrowingObserverDoesNotStopTheOthers() {
        offers.addObserver { error("boom") }
        val later = mutableListOf<Map<String, Any?>?>()
        offers.addObserver { later.add(it) }
        fetcher.onStartup()
        runMain()
        assertEquals(1, later.size)
    }

    @Test
    fun removedObserversAreNotCalled() {
        val calls = mutableListOf<Map<String, Any?>?>()
        val token = offers.addObserver { calls.add(it) }
        offers.removeObserver(token)
        fetcher.onStartup()
        runMain()
        assertTrue(calls.isEmpty())
    }

    companion object {
        const val KEY = "pk_test_SECRET_OFFERS"
        const val ANON = "00000000-0000-4000-8000-0000000000aa"
        const val OFFER = """{"offer_id": "o-1", "ids": [1, 2], "template": {"title": "20% off"}}"""
    }
}
