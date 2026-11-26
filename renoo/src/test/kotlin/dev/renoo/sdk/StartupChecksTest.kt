package dev.renoo.sdk

import android.app.Application
import android.os.Looper
import dev.renoo.sdk.collectors.InstallIdentity
import dev.renoo.sdk.core.EventPipeline
import dev.renoo.sdk.core.FakeSharedPreferences
import dev.renoo.sdk.core.SdkCore
import dev.renoo.sdk.core.SdkLogger
import dev.renoo.sdk.upload.HttpRequest
import dev.renoo.sdk.upload.HttpResponse
import dev.renoo.sdk.upload.HttpTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.nio.file.Files
import java.util.Collections

/**
 * Start-up network calls end to end on a real [SdkCore] (real executor, Robolectric context) with a
 * fake transport: health check, kill switch, and that nothing runs on the main thread.
 */
@RunWith(RobolectricTestRunner::class)
class StartupChecksTest {
    private class Call(val method: String, val path: String, val headers: Map<String, String>, val url: String, val thread: Thread)

    private val directory = Files.createTempDirectory("startup").toFile()
    private val calls = Collections.synchronizedList(mutableListOf<Call>())

    /** Status per path; a `null` status means a network error. */
    private val statuses = mutableMapOf<String, Int?>("/health-check" to 200, "/events/sdk" to 202, "/offers" to 204)
    private val bodies = mutableMapOf<String, String>("/events/sdk" to """{"accepted": 1, "rejected": []}""")

    private val transport = HttpTransport { request: HttpRequest ->
        calls.add(Call(request.method, request.url.path, request.headers, request.url.toString(), Thread.currentThread()))
        val code = statuses[request.url.path] ?: throw IOException("offline")
        HttpResponse(code, null, bodies[request.url.path] ?: "")
    }

    private lateinit var core: SdkCore

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun start(
        ingestUrl: String? = "https://api.example.com",
        prefs: FakeSharedPreferences = FakeSharedPreferences(),
        beforeStart: (SdkCore) -> Unit = {},
    ): SdkCore {
        core = SdkCore(
            Configuration(
                apiKey = "pk_test_KEY",
                purchaseHandler = { _, onResult -> onResult(PurchaseResult.CANCELLED) },
                trackingScope = TrackingScope.EVERYONE,
                ingestUrl = ingestUrl,
            ),
            RuntimeEnvironment.getApplication() as Application,
            prefs = prefs,
            storeDirectory = directory,
            identity = InstallIdentity(
                prefs = prefs,
                logger = SdkLogger(LogLevel.NONE) { _, _ -> },
                packageName = { "com.example.app" },
                androidIdSource = { "0123456789abcdef" },
                packageFirstInstallTime = { 1L },
            ),
            transport = transport,
            elapsedClock = { 0L },
        )
        beforeStart(core)
        core.start()
        idle()
        return core
    }

    private fun idle() {
        repeat(3) {
            core.environment.pipeline.waitUntilIdle()
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun paths() = calls.map { it.path }
    private fun queued() = core.environment.pipeline.queuedEvents()

    @Test
    fun captureOnlyModeMakesNoCalls() {
        start(ingestUrl = null)
        core.screens.trackScreen("Home", ScreenSource.MANUAL)
        idle()
        assertTrue(calls.isEmpty())
        assertTrue(queued().isNotEmpty())
    }

    @Test
    fun health200CarriesOn() {
        start()
        assertEquals("/health-check", paths().first())
        assertEquals("GET", calls.first().method)
        assertEquals("Bearer pk_test_KEY", calls.first().headers["Authorization"])
        assertFalse(core.killSwitch.isEngaged)
        core.screens.trackScreen("Home", ScreenSource.MANUAL)
        idle()
        assertTrue(queued().any { it.name == EventName.SCREEN_VIEW })
    }

    @Test
    fun health401EngagesTheKillSwitch() {
        statuses["/health-check"] = 401
        start()
        assertTrue(core.killSwitch.isEngaged)
        assertEquals("offers skipped", listOf("/health-check"), paths())
        assertTrue("queue dropped", queued().isEmpty())
        core.screens.trackScreen("Home", ScreenSource.MANUAL)
        idle()
        assertTrue("capture stopped", queued().isEmpty())
        assertTrue(core.uploader?.isStopped == true)
    }

    @Test
    fun health5xxAndNetworkErrorCarryOn() {
        for (status in listOf(500, 503, null)) {
            calls.clear()
            statuses["/health-check"] = status
            start()
            assertFalse("$status", core.killSwitch.isEngaged)
            core.screens.trackScreen("Home", ScreenSource.MANUAL)
            idle()
            assertTrue("$status", queued().any { it.name == EventName.SCREEN_VIEW })
            directory.listFiles()?.forEach { it.delete() }
        }
    }

    @Test
    fun upload401EngagesTheKillSwitch() {
        // Health check passes; the cold-start upload (start-up events are queued) gets the 401.
        statuses["/events/sdk"] = 401
        start()
        assertEquals("/health-check", paths().first())
        assertTrue(paths().contains("/events/sdk"))
        assertTrue(core.killSwitch.isEngaged)
        assertTrue(core.uploader?.isStopped == true)
        assertTrue("queue dropped", queued().isEmpty())
        core.screens.trackScreen("Settings", ScreenSource.MANUAL)
        idle()
        assertTrue("capture stopped", queued().isEmpty())
    }

    @Test
    fun networkCallsNeverRunOnTheMainThread() {
        start()
        assertTrue(calls.isNotEmpty())
        val main = Looper.getMainLooper().thread
        assertTrue(calls.all { it.thread !== main && it.thread.name == "Renoo" })
    }

    @Test
    fun offersFollowAHealthyStartUpWithIdentityHeaders() {
        statuses["/offers"] = 200
        bodies["/offers"] = """{"offer_id": "o-1"}"""
        val observed = Collections.synchronizedList(mutableListOf<Pair<Map<String, Any?>?, Thread>>())
        start { core -> core.pendingOffers.addObserver { observed.add(it to Thread.currentThread()) } }
        assertEquals(listOf("/health-check", "/offers"), paths().take(2))
        val request = calls[1]
        assertEquals("GET", request.method)
        assertEquals(core.environment.pipeline.currentAnonymousId, request.headers["X-Renoo-Anonymous-Id"])
        assertFalse(request.headers.containsKey("X-Renoo-User-Id"))
        assertEquals("https://api.example.com/offers", request.url)
        assertEquals("o-1", core.pendingOffers.current?.get("offer_id"))
        assertEquals(1, observed.size)
        assertTrue("observer on the main thread", observed.single().second === Looper.getMainLooper().thread)
    }

    @Test
    fun resetAndOptOutClearThePendingOffer() {
        statuses["/offers"] = 200
        bodies["/offers"] = """{"offer_id": "o-1"}"""
        start()
        assertEquals("o-1", core.pendingOffers.current?.get("offer_id"))
        core.environment.pipeline.reset()
        idle()
        assertEquals(null, core.pendingOffers.current)
        core.lifecycle.foregroundListener?.invoke(true) // throttle restarted by reset: fetches again
        idle()
        assertEquals("o-1", core.pendingOffers.current?.get("offer_id"))
        core.environment.pipeline.setOptedOut(true)
        idle()
        assertEquals(null, core.pendingOffers.current)
        val before = calls.size
        core.lifecycle.foregroundListener?.invoke(true)
        idle()
        assertFalse("no offers call while opted out", paths().drop(before).contains("/offers"))
    }

    @Test
    fun offers401EngagesTheKillSwitch() {
        statuses["/offers"] = 401
        start()
        assertTrue(core.killSwitch.isEngaged)
        assertFalse("no upload after the kill switch", paths().contains("/events/sdk"))
        assertTrue(queued().isEmpty())
    }

    @Test
    fun killSwitchKeepsIdentityAndOptOutAndLastsUntilTheNextStartUp() {
        val prefs = FakeSharedPreferences()
        start(prefs = prefs)
        core.environment.pipeline.identify("user-1")
        core.environment.pipeline.setOptedOut(false) // writes the stored choice
        idle()
        assertTrue(prefs.contains(EventPipeline.KEY_OPTED_OUT))
        val anonymousId = core.environment.pipeline.currentAnonymousId

        statuses["/health-check"] = 401
        start(prefs = prefs)
        assertTrue(core.killSwitch.isEngaged)
        assertEquals("user-1", prefs.getString(EventPipeline.KEY_USER_ID, null))
        assertEquals(anonymousId, prefs.getString(EventPipeline.KEY_ANONYMOUS_ID, null))
        assertFalse(prefs.getBoolean(EventPipeline.KEY_OPTED_OUT, true))

        calls.clear()
        statuses["/health-check"] = 200
        start(prefs = prefs)
        assertFalse("ends at the next start-up", core.killSwitch.isEngaged)
        val offers = calls.single { it.path == "/offers" }
        assertEquals(anonymousId, offers.headers["X-Renoo-Anonymous-Id"])
        assertEquals("user-1", offers.headers["X-Renoo-User-Id"])
    }

    @Test
    fun optedOutStartUpStillRunsTheHealthCheckButNotOffers() {
        val prefs = FakeSharedPreferences()
        start(prefs = prefs)
        core.environment.pipeline.setOptedOut(true)
        idle()
        calls.clear()
        start(prefs = prefs)
        assertEquals("/health-check", paths().first())
        assertFalse(paths().contains("/offers"))
        assertFalse(calls.any { it.headers.containsKey("X-Renoo-Anonymous-Id") })
    }
}
