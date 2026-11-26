package dev.renoo.sdk

import android.app.Application
import android.content.Context
import dev.renoo.sdk.collectors.ContextCollector
import dev.renoo.sdk.collectors.InstallIdentity
import dev.renoo.sdk.collectors.LifecycleEngine
import dev.renoo.sdk.collectors.SubscriptionTracker
import dev.renoo.sdk.core.MainProcess
import dev.renoo.sdk.core.SdkCore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.Collections

/** The public facade end to end, on Robolectric (real Context, SharedPreferences and noBackupFilesDir). */
@RunWith(RobolectricTestRunner::class)
class RenooTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val handler = PurchaseHandler { _, onResult -> onResult(PurchaseResult.CANCELLED) }

    @Before
    fun setUp() {
        Renoo.setCoreForTesting(null)
        // Run as the main process. The real lookup reads /proc/self/cmdline below API 28, which on a
        // Linux CI runner names the JVM, not the app, and would keep the SDK off.
        MainProcess.currentProcessName = { app.packageName }
    }

    private val defaultProcessName = MainProcess.currentProcessName

    @After
    fun tearDown() {
        Renoo.setCoreForTesting(null)
        MainProcess.currentProcessName = defaultProcessName
    }

    private fun configure(scope: TrackingScope = TrackingScope.EVERYONE) =
        Renoo.configure(app, Configuration(apiKey = "test-key", purchaseHandler = handler, trackingScope = scope))

    @Test
    fun everythingIsANoOpBeforeConfigure() {
        assertFalse(Renoo.isConfigured)
        Renoo.identify("user-1")
        Renoo.reset()
        Renoo.optOut()
        Renoo.optIn()
        Renoo.trackScreen("Home")
        assertNull(Renoo.addEventObserver { })
        assertNull(Renoo.addPendingOfferObserver { })
        assertNull(Renoo.pendingOffer)
        assertFalse(Renoo.isOptedOut)
        assertTrue(Renoo.queuedEvents().isEmpty())
        assertNull(Renoo.anonymousId)
    }

    @Test
    fun anonymousIdIsReadableAfterConfigureAndStoredInTheSdkPrefs() {
        configure()
        Renoo.coreForTesting?.environment?.pipeline?.waitUntilIdle()
        val id = Renoo.anonymousId
        assertNotNull(id)
        assertEquals(id, id?.lowercase())
        val prefs = app.getSharedPreferences(SdkCore.PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals(id, prefs.getString("anonymous_id", null))
        Renoo.reset()
        Renoo.coreForTesting?.environment?.pipeline?.waitUntilIdle()
        assertNotEquals(id, Renoo.anonymousId)
    }

    @Test
    fun blankApiKeyDisablesTheSdk() {
        Renoo.configure(app, Configuration(apiKey = "  ", purchaseHandler = handler))
        assertFalse(Renoo.isConfigured)
    }

    @Test
    fun secondConfigureIsIgnored() {
        configure()
        val first = Renoo.coreForTesting
        configure(TrackingScope.SUBSCRIBERS_ONLY)
        assertSame(first, Renoo.coreForTesting)
    }

    @Test
    fun usesTheApplicationContextAndStoresTheQueueOutsideBackups() {
        // A queue left in the backed-up filesDir by an early build.
        val legacy = File(app.filesDir, "renoo/queue.jsonl").apply {
            parentFile?.mkdirs()
            writeText("{}\n")
        }
        configure()
        val core = Renoo.coreForTesting
        assertNotNull(core)
        assertSame(app, core?.environment?.appContext)
        Renoo.trackScreen("Home")
        Renoo.queuedEvents()
        assertEquals("no_backup", app.noBackupFilesDir.name)
        assertTrue(File(app.noBackupFilesDir, "renoo/queue.jsonl").isFile)
        assertFalse("the old queue is deleted", legacy.exists())
        assertFalse(File(app.filesDir, "renoo").exists())
        val prefs = app.getSharedPreferences(SdkCore.PREFS_NAME, Context.MODE_PRIVATE)
        assertTrue(prefs.getLong("seq", 0) >= 1L) // the screen view, plus `install` once it lands
    }

    @Test
    fun screensIdentifyAndOptOutEndToEnd() {
        configure()
        val seen = Collections.synchronizedList(mutableListOf<String>())
        // `install` is committed asynchronously at some point after start; ignored here.
        val token = Renoo.addEventObserver { if (it.name != EventName.INSTALL) seen.add(it.name.wireValue) }
        assertNotNull(token)

        Renoo.trackScreen("Home")
        Renoo.identify("user-1")
        Renoo.trackScreen("Settings", ScreenSource.REACT_NATIVE)
        val events = Renoo.queuedEvents().filter { it.name != EventName.INSTALL }
        // The first identify is the anonymous one, sent once at start-up (the gate is open: EVERYONE).
        assertEquals(listOf("identify", "screen_view", "identify", "screen_view"), events.map { it.name.wireValue })
        assertEquals(listOf(null, null, "user-1", "user-1"), events.map { it.userId })
        assertTrue(events[0].properties.containsKey("user_id"))
        assertNull(events[0].properties["user_id"])
        assertEquals("user-1", events[2].properties["user_id"])
        assertEquals("Home", events[3].properties["previous_screen_name"])
        assertEquals("react_native", events[3].properties["source"])
        assertEquals(setOf(Renoo.anonymousId), events.map { it.anonymousId }.toSet())
        events.forEach {
            assertEquals("android", it.platform)
            assertEquals(Renoo.SDK_VERSION, it.sdkVersion)
            assertTrue(it.installId.isNotEmpty())
        }
        assertEquals(1, events.map { it.installId }.toSet().size)
        assertEquals(listOf("screen_view", "identify", "screen_view"), seen.toList().takeLast(3))

        Renoo.optOut()
        assertTrue(Renoo.isOptedOut)
        Renoo.identify("user-2")
        Renoo.trackScreen("Hidden")
        assertTrue(Renoo.queuedEvents().isEmpty())

        assertEquals("opting out keeps the anonymous ID", events[0].anonymousId, Renoo.anonymousId)

        Renoo.optIn()
        Renoo.trackScreen("Back")
        val afterOptIn = Renoo.queuedEvents().filter { it.name != EventName.INSTALL }
        assertEquals(listOf("identify", "screen_view"), afterOptIn.map { it.name.wireValue })
        assertEquals(listOf<String?>(null, null), afterOptIn.map { it.userId })
        assertEquals("opting in keeps the anonymous ID and sends identify again", events[0].anonymousId, afterOptIn[0].anonymousId)
        assertEquals(setOf(Renoo.anonymousId), afterOptIn.map { it.anonymousId }.toSet())
    }

    @Test
    fun configureIsANoOpOutsideTheMainProcess() {
        MainProcess.currentProcessName = { "${app.packageName}:remote" }
        configure()
        assertFalse(Renoo.isConfigured)

        MainProcess.currentProcessName = { app.packageName }
        configure()
        assertTrue(Renoo.isConfigured)
    }

    @Test
    fun configureRunsWhenTheProcessNameIsUnknown() {
        MainProcess.currentProcessName = { throw SecurityException("no /proc") }
        configure()
        assertTrue(Renoo.isConfigured)
    }

    @Test
    fun optOutDeletesCollectorStateButKeepsTheFlagAndInstallId() {
        configure()
        Renoo.queuedEvents() // let the start-up work settle
        val prefs = app.getSharedPreferences(SdkCore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(SubscriptionTracker.KEY_LAST_SUBSCRIPTION, "{\"purchase_token\":\"secret\"}")
            .putString(SubscriptionTracker.KEY_KNOWN, "{}")
            .putString(LifecycleEngine.STATE_KEY, "{}")
            .putString(ContextCollector.KEY_NETWORK_TYPE, "wifi")
            .commit()
        Renoo.identify("user-1")

        Renoo.optOut()
        Renoo.queuedEvents()
        for (key in listOf(SubscriptionTracker.KEY_LAST_SUBSCRIPTION, SubscriptionTracker.KEY_KNOWN, LifecycleEngine.STATE_KEY, ContextCollector.KEY_NETWORK_TYPE, "user_id")) {
            assertFalse("$key deleted", prefs.contains(key))
        }
        assertTrue(prefs.getBoolean("opted_out", false))
        assertTrue(prefs.contains(InstallIdentity.KEY_INSTALL_ID))
    }

    @Test
    fun subscribersOnlyBuffersUntilTheStubbedSubscriptionCollectorDecides() {
        configure(TrackingScope.SUBSCRIBERS_ONLY)
        Renoo.trackScreen("Home")
        assertTrue(Renoo.queuedEvents().isEmpty())
    }
}
