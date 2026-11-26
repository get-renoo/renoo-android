package dev.renoo.sdk.collectors

import android.app.Application
import android.content.res.Configuration as AndroidConfiguration
import dev.renoo.sdk.Configuration
import dev.renoo.sdk.EventName
import dev.renoo.sdk.PurchaseResult
import dev.renoo.sdk.TrackingScope
import dev.renoo.sdk.core.FakeSharedPreferences
import dev.renoo.sdk.core.withIdentitySent
import dev.renoo.sdk.core.SdkCore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ContextCollectorTest {
    private val directory = Files.createTempDirectory("context").toFile()
    private val prefs = FakeSharedPreferences().withIdentitySent()

    private fun makeCore() = SdkCore(
        Configuration(apiKey = "test", purchaseHandler = { _, onResult -> onResult(PurchaseResult.CANCELLED) }, trackingScope = TrackingScope.EVERYONE),
        RuntimeEnvironment.getApplication() as Application,
        prefs = prefs,
        storeDirectory = directory,
    )

    /**
     * Executor reads post follow-up writes (e.g. the network type is persisted in a second task),
     * so one waitUntilIdle() can return before they run. Drain a few times.
     */
    private fun SdkCore.settle() = repeat(3) { environment.pipeline.waitUntilIdle() }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun pushTrackerIsSilentOnFirstReadAndReportsChanges() {
        val tracker = PushPermissionTracker(prefs)
        assertNull(tracker.observe("denied"))
        assertNull(tracker.observe("denied"))
        assertEquals(PushPermissionTracker.Change("authorized", "denied"), tracker.observe("authorized"))
        assertNull(tracker.observe("authorized"))
        // Survives a new process.
        assertEquals("authorized", PushPermissionTracker(prefs).observe("denied")?.previous)
    }

    @Test
    fun pushPermissionChangedEvent() {
        val core = makeCore()
        core.context.pushStatusRead("denied")
        core.context.pushStatusRead("denied")
        core.context.pushStatusRead("authorized")
        val events = core.environment.pipeline.queuedEvents()
        assertEquals(listOf(EventName.PUSH_PERMISSION_CHANGED), events.map { it.name })
        assertEquals(mapOf("status" to "authorized", "previous_status" to "denied"), events[0].properties)
        assertEquals("authorized", core.context.snapshot()["push_permission"])
    }

    @Test
    fun snapshotHasEveryKeyAfterStart() {
        val core = makeCore()
        core.context.start()
        core.settle()
        val snapshot = core.context.snapshot()
        assertEquals(
            listOf("device_model", "locale", "timezone", "network_type", "low_power_mode", "dark_mode", "push_permission", "days_since_install", "is_reinstall"),
            snapshot.keys.toList(),
        )
        assertTrue((snapshot["device_model"] as String).isNotEmpty())
        assertTrue((snapshot["locale"] as String).contains("-") || (snapshot["locale"] as String).isNotEmpty())
        assertTrue(snapshot["network_type"] in setOf("wifi", "cellular", "wired", "none", "unknown"))
        assertEquals("authorized", snapshot["push_permission"]) // Robolectric: notifications enabled
        assertEquals(0L, snapshot["days_since_install"])
        assertNull(snapshot["is_reinstall"]) // always null on Android
        assertTrue(core.environment.pipeline.queuedEvents().isEmpty()) // first push read is silent
    }

    @Test
    fun coldStartSessionStartCarriesLastKnownValuesBeforeAnyAsyncRead() {
        // First process: the executor reads land and are persisted.
        val first = makeCore()
        first.context.start()
        first.settle()
        val known = first.context.snapshot()
        assertEquals("authorized", known["push_permission"])
        assertTrue(known["low_power_mode"] is Boolean)
        assertTrue(known["days_since_install"] is Long)
        val network = prefs.getString(ContextCollector.KEY_NETWORK_TYPE, null)
        assertTrue("network type persisted: $network", network in setOf("wifi", "cellular", "wired", "none"))

        // Second process (force-stop + relaunch): the first activity starts while the SDK thread
        // has not run anything yet, so the engine snapshots before every async read.
        val second = makeCore()
        val release = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        second.environment.executor.post {
            blocked.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        assertTrue(blocked.await(5, TimeUnit.SECONDS))
        second.context.start()
        second.lifecycle.becameActive()
        release.countDown()

        val sessionStart = second.environment.pipeline.queuedEvents().first { it.name == EventName.SESSION_START }
        @Suppress("UNCHECKED_CAST")
        val context = sessionStart.properties["context"] as Map<String, Any?>
        assertEquals("authorized", context["push_permission"])
        assertEquals(known["low_power_mode"], context["low_power_mode"])
        assertEquals(network, context["network_type"])
        assertEquals(0L, (context["days_since_install"] as Number).toLong()) // Int after the JSON round trip
    }

    @Test
    fun seedRecomputesDaysSinceInstallFromThePersistedTime() {
        val core = makeCore()
        val tenDaysAgo = System.currentTimeMillis() - 10 * 86_400_000L - 1_000
        prefs.edit()
            .putLong(ContextCollector.KEY_FIRST_INSTALL_TIME, tenDaysAgo)
            .putBoolean(ContextCollector.KEY_LOW_POWER_MODE, true)
            .putString(ContextCollector.KEY_NETWORK_TYPE, "cellular")
            .putString(PushPermissionTracker.KEY, "denied")
            .apply()
        core.context.seedFromPrefs()
        val snapshot = core.context.snapshot()
        assertEquals(10L, snapshot["days_since_install"])
        assertEquals(true, snapshot["low_power_mode"])
        assertEquals("cellular", snapshot["network_type"])
        assertEquals("denied", snapshot["push_permission"])
    }

    @Test
    fun firstLaunchOfAnInstallSeedsNothing() {
        val core = makeCore()
        core.context.seedFromPrefs()
        val snapshot = core.context.snapshot()
        assertNull(snapshot["days_since_install"])
        assertNull(snapshot["low_power_mode"])
        assertNull(snapshot["push_permission"])
        assertEquals("unknown", snapshot["network_type"])
    }

    @Test
    fun localeIsBcp47() {
        assertEquals("en-US", ContextCollector.bcp47Locale(java.util.Locale.US))
        assertEquals("pt-BR", ContextCollector.bcp47Locale(java.util.Locale("pt", "BR")))
        assertEquals("zh-Hans-CN", ContextCollector.bcp47Locale(java.util.Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals("und", ContextCollector.bcp47Locale(java.util.Locale.ROOT))
    }

    @Test
    fun daysSinceInstall() {
        val installed = 1_800_000_000_000L
        assertEquals(0L, ContextCollector.daysSinceInstall(installed, installed))
        assertEquals(0L, ContextCollector.daysSinceInstall(installed, installed + 86_399_999))
        assertEquals(3L, ContextCollector.daysSinceInstall(installed, installed + 86_400_000L * 3 + 5))
        assertEquals(0L, ContextCollector.daysSinceInstall(installed, installed - 500))
    }

    @Test
    fun networkTypeMapping() {
        assertEquals("wifi", ContextCollector.networkType(wifi = true, cellular = true, ethernet = false))
        assertEquals("cellular", ContextCollector.networkType(wifi = false, cellular = true, ethernet = false))
        assertEquals("wired", ContextCollector.networkType(wifi = false, cellular = false, ethernet = true))
        assertEquals("unknown", ContextCollector.networkType(wifi = false, cellular = false, ethernet = false))
    }

    @Test
    fun darkModeAndPushStatusMapping() {
        assertEquals(true, ContextCollector.isDarkMode(AndroidConfiguration.UI_MODE_NIGHT_YES or AndroidConfiguration.UI_MODE_TYPE_NORMAL))
        assertEquals(false, ContextCollector.isDarkMode(AndroidConfiguration.UI_MODE_NIGHT_NO))
        assertNull(ContextCollector.isDarkMode(AndroidConfiguration.UI_MODE_NIGHT_UNDEFINED))
        assertEquals("authorized", ContextCollector.pushStatus(true))
        assertEquals("denied", ContextCollector.pushStatus(false))
    }

    @Test
    fun optOutDeletesTheStoredContextAndNothingIsStoredWhileOptedOut() {
        val core = makeCore()
        core.context.start()
        core.context.pushStatusRead("denied")
        core.settle()
        assertTrue(prefs.contains(PushPermissionTracker.KEY))
        assertTrue(prefs.contains(ContextCollector.KEY_FIRST_INSTALL_TIME))
        // Wired as in SdkCore.
        core.environment.pipeline.setOptedOut(true)
        core.settle()
        val keys = listOf(
            PushPermissionTracker.KEY,
            ContextCollector.KEY_FIRST_INSTALL_TIME,
            ContextCollector.KEY_LOW_POWER_MODE,
            ContextCollector.KEY_NETWORK_TYPE,
        )
        keys.forEach { assertTrue("$it deleted", !prefs.contains(it)) }

        core.context.refresh()
        core.context.pushStatusRead("authorized")
        core.settle()
        keys.forEach { assertTrue("$it not written while opted out", !prefs.contains(it)) }
        assertEquals("the in-memory context stays for after opt-in", "authorized", core.context.snapshot()["push_permission"])

        core.environment.pipeline.setOptedOut(false)
        core.context.pushStatusRead("denied")
        core.settle()
        assertEquals("denied", prefs.getString(PushPermissionTracker.KEY, null))
        assertEquals(
            "first status after opt-in is stored silently (only the identify re-sent after opt-in is queued)",
            listOf(EventName.IDENTIFY),
            core.environment.pipeline.queuedEvents().map { it.name },
        )
    }
}
