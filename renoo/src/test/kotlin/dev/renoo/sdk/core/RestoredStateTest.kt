package dev.renoo.sdk.core

import android.app.Application
import dev.renoo.sdk.Configuration
import dev.renoo.sdk.EventName
import dev.renoo.sdk.LogLevel
import dev.renoo.sdk.PurchaseResult
import dev.renoo.sdk.TrackingScope
import dev.renoo.sdk.collectors.ContextCollector
import dev.renoo.sdk.collectors.InstallIdentity
import dev.renoo.sdk.collectors.LifecycleEngine
import dev.renoo.sdk.collectors.LifecycleState
import dev.renoo.sdk.collectors.PushPermissionTracker
import dev.renoo.sdk.collectors.SubscriptionTracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.file.Files

/** SDK prefs restored by Android's backup into another install are wiped before anything uses them. */
@RunWith(RobolectricTestRunner::class)
class RestoredStateTest {
    private val directory = Files.createTempDirectory("restore").toFile()
    private val prefs = FakeSharedPreferences()
    private val logger = SdkLogger(LogLevel.NONE) { _, _ -> }

    private val oldInstallKeys = listOf(
        EventPipeline.KEY_USER_ID,
        EventPipeline.KEY_SEQ,
        LifecycleEngine.STATE_KEY,
        SubscriptionTracker.KEY_KNOWN,
        SubscriptionTracker.KEY_LAST_SUBSCRIPTION,
        SubscriptionTracker.KEY_SEEDED_FOR,
        SubscriptionTracker.KEY_LAST_TRACKED,
        ContextCollector.KEY_LOW_POWER_MODE,
        ContextCollector.KEY_NETWORK_TYPE,
        ContextCollector.KEY_FIRST_INSTALL_TIME,
        PushPermissionTracker.KEY,
    )

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    /** Prefs and queue as another install (marker 1) left them, mid-session in the foreground. */
    private fun seedOldInstall() {
        prefs.edit()
            .putString(InstallIdentity.KEY_INSTALL_ID, "old-install-id")
            .putLong(InstallIdentity.KEY_INSTALL_ID_FOR, 1L)
            .putString(EventPipeline.KEY_USER_ID, "old-user")
            .putLong(EventPipeline.KEY_SEQ, 41L)
            .putBoolean(EventPipeline.KEY_OPTED_OUT, false)
            .putString(LifecycleEngine.STATE_KEY, LifecycleState(sessionId = "old-session", sessionStartedAt = 1L, inForeground = true, lastActiveAt = 2L).toJson())
            .putString(SubscriptionTracker.KEY_KNOWN, "{}")
            .putString(SubscriptionTracker.KEY_LAST_SUBSCRIPTION, "{\"purchase_token\":\"old-token\"}")
            .putLong(SubscriptionTracker.KEY_SEEDED_FOR, 1L)
            .putBoolean(SubscriptionTracker.KEY_LAST_TRACKED, true)
            .putBoolean(ContextCollector.KEY_LOW_POWER_MODE, true)
            .putString(ContextCollector.KEY_NETWORK_TYPE, "wifi")
            .putLong(ContextCollector.KEY_FIRST_INSTALL_TIME, 1L)
            .putString(PushPermissionTracker.KEY, "authorized")
            .apply()
        File(directory, EventStore.FILE_NAME).writeText("{\"event_id\":\"from-the-old-install\"}\n")
    }

    private fun makeCore(installMarker: Long) = SdkCore(
        Configuration(apiKey = "test", purchaseHandler = { _, onResult -> onResult(PurchaseResult.CANCELLED) }, trackingScope = TrackingScope.EVERYONE),
        RuntimeEnvironment.getApplication() as Application,
        prefs = prefs,
        storeDirectory = directory,
        identity = InstallIdentity(
            prefs = prefs,
            logger = logger,
            packageName = { "com.example.app" },
            androidIdSource = { "0123456789abcdef" },
            packageFirstInstallTime = { installMarker },
        ),
    )

    @Test
    fun prefsFromAnotherInstallAreWipedExceptOptOut() {
        seedOldInstall()
        prefs.edit().putBoolean(EventPipeline.KEY_OPTED_OUT, true).apply()
        val core = makeCore(installMarker = 2L)
        core.environment.pipeline.waitUntilIdle()

        oldInstallKeys.forEach { assertFalse("$it wiped", prefs.contains(it)) }
        assertTrue("the user's opt-out is kept", prefs.getBoolean(EventPipeline.KEY_OPTED_OUT, false))
        assertNotEquals("old-install-id", prefs.getString(InstallIdentity.KEY_INSTALL_ID, null))
        assertEquals(2L, prefs.getLong(InstallIdentity.KEY_INSTALL_ID_FOR, 0L))
        assertFalse("the restored queue is deleted", File(directory, EventStore.FILE_NAME).exists())
    }

    @Test
    fun aRestoredSessionIsNotReportedAsAnUncleanExit() {
        seedOldInstall()
        val core = makeCore(installMarker = 2L)
        core.environment.pipeline.waitUntilIdle()
        core.lifecycle.becameActive()

        val events = core.environment.pipeline.queuedEvents().filter { it.name != EventName.INSTALL && it.name != EventName.IDENTIFY }
        assertEquals(listOf(EventName.SESSION_START, EventName.APP_OPEN), events.map { it.name })
        assertTrue(events.none { it.sessionId == "old-session" || it.userId == "old-user" })
        assertEquals("seq starts over", 1L, core.environment.pipeline.queuedEvents().minOf { it.seq })
    }

    @Test
    fun theSameInstallKeepsItsState() {
        seedOldInstall()
        val core = makeCore(installMarker = 1L)
        core.environment.pipeline.waitUntilIdle()

        oldInstallKeys.forEach { assertTrue("$it kept", prefs.contains(it)) }
        assertEquals("old-install-id", prefs.getString(InstallIdentity.KEY_INSTALL_ID, null))
        assertTrue(File(directory, EventStore.FILE_NAME).exists())
    }
}
