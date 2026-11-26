package dev.renoo.sdk.collectors

import android.app.Activity
import android.app.Application
import android.content.res.Configuration as AndroidConfiguration
import android.os.Looper
import dev.renoo.sdk.Configuration
import dev.renoo.sdk.Event
import dev.renoo.sdk.PurchaseResult
import dev.renoo.sdk.TrackingScope
import dev.renoo.sdk.core.FakeSharedPreferences
import dev.renoo.sdk.core.withIdentitySent
import dev.renoo.sdk.core.SdkCore
import io.flutter.embedding.android.FlutterActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.nio.file.Files

class HomeActivity : Activity()
class SettingsActivity : Activity()
class MainFlutterActivity : FlutterActivity()

/**
 * Lifecycle + screen capture end to end: real `ActivityLifecycleCallbacks` driven by Robolectric
 * activity controllers, through the real pipeline to the on-disk queue.
 */
@RunWith(RobolectricTestRunner::class)
class ActivityCaptureTest {
    private val directory = Files.createTempDirectory("capture").toFile()
    private val prefs = FakeSharedPreferences().withIdentitySent()
    private val app = RuntimeEnvironment.getApplication() as Application

    private fun makeCore(automaticScreens: Boolean = true) = SdkCore(
        Configuration(
            apiKey = "test",
            purchaseHandler = { _, onResult -> onResult(PurchaseResult.CANCELLED) },
            trackingScope = TrackingScope.EVERYONE,
            automaticScreenTracking = automaticScreens,
        ),
        app,
        prefs = prefs,
        storeDirectory = directory,
    )

    /** Starts the core like `configure` does, with a fixed answer to "is the process in front?". */
    private fun startCore(inForeground: Boolean = false): SdkCore = makeCore().also {
        it.lifecycle.foregroundProbe = { inForeground }
        it.start()
        settle(it)
    }

    /** Drains the SDK executor and the main looper until both are quiet. */
    private fun settle(core: SdkCore) {
        repeat(3) {
            core.environment.pipeline.waitUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun events(core: SdkCore): List<Event> {
        settle(core)
        // Install and subscription events come from other collectors and are tested there.
        return core.environment.pipeline.queuedEvents()
            .filter { it.name != dev.renoo.sdk.EventName.INSTALL && it.name != dev.renoo.sdk.EventName.SUBSCRIPTION_STATE }
    }

    private fun names(events: List<Event>) = events.map { it.name.wireValue }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun coldStartRotationBackgroundAndWarmReturn() {
        val core = startCore()
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup()

        var events = events(core)
        assertEquals(listOf("session_start", "app_open", "screen_view"), names(events))
        val session = events[0].sessionId
        assertNotNull(session)
        assertTrue(events.all { it.sessionId == session })
        assertEquals("cold", events[1].properties["start_type"])
        assertEquals("HomeActivity", events[2].properties["screen_name"])
        assertEquals("activity", events[2].properties["source"])
        @Suppress("UNCHECKED_CAST")
        val context = events[0].properties["context"] as Map<String, Any?>
        for (key in listOf("device_model", "locale", "timezone", "network_type", "low_power_mode", "dark_mode", "push_permission", "days_since_install", "is_reinstall")) {
            assertTrue(key, context.containsKey(key))
        }

        // Rotation recreates the activity: no background / foreground, no second screen view.
        val landscape = AndroidConfiguration(app.resources.configuration).apply { orientation = AndroidConfiguration.ORIENTATION_LANDSCAPE }
        home.configurationChange(landscape)
        assertEquals(3, events(core).size)

        // A dialog over the activity (pause / resume only) changes nothing.
        home.pause().resume()
        assertEquals(3, events(core).size)

        // Home button, then back within the timeout: same session, no new screen view.
        home.pause().stop()
        home.restart().resume()
        events = events(core)
        assertEquals(listOf("session_start", "app_open", "screen_view", "app_background", "app_open"), names(events))
        assertEquals("warm", events[4].properties["start_type"])
        assertTrue(events.all { it.sessionId == session })
    }

    @Test
    fun navigationBetweenActivitiesChainsScreensWithoutBackgrounds() {
        val core = startCore()
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup()
        home.pause()
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        home.stop()
        // Back.
        settings.pause()
        home.restart().resume()
        settings.stop().destroy()

        val events = events(core)
        assertEquals(listOf("session_start", "app_open", "screen_view", "screen_view", "screen_view"), names(events))
        assertEquals(listOf("HomeActivity", "SettingsActivity", "HomeActivity"), events.drop(2).map { it.properties["screen_name"] })
        assertEquals(listOf(null, "HomeActivity", "SettingsActivity"), events.drop(2).map { it.properties["previous_screen_name"] })
    }

    @Test
    fun flutterHostActivityIsNotAScreenButStillOpensTheApp() {
        val core = startCore()
        Robolectric.buildActivity(MainFlutterActivity::class.java).setup()
        assertEquals(listOf("session_start", "app_open"), names(events(core)))
    }

    @Test
    fun automaticScreenTrackingOff() {
        val core = makeCore(automaticScreens = false).also {
            it.lifecycle.foregroundProbe = { false }
            it.start()
        }
        Robolectric.buildActivity(HomeActivity::class.java).setup()
        assertEquals(listOf("session_start", "app_open"), names(events(core)))
    }

    @Test
    fun processWithoutUiDoesNotOpenUntilAnActivityStarts() {
        // configure from Application.onCreate in a process started for a receiver or a service.
        val core = startCore(inForeground = false)
        assertTrue(events(core).isEmpty())

        Robolectric.buildActivity(HomeActivity::class.java).setup()
        val events = events(core)
        assertEquals(listOf("session_start", "app_open", "screen_view"), names(events))
        assertEquals("cold", events[1].properties["start_type"])
    }

    @Test
    fun lateConfigureWithAnActivityAlreadyInFront() {
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup() // before configure
        val core = startCore(inForeground = true)
        var events = events(core)
        assertEquals(listOf("session_start", "app_open"), names(events))
        assertEquals("cold", events[1].properties["start_type"])

        // Leaving from the activity we never saw start is still a background.
        home.pause().stop()
        events = events(core)
        assertEquals("app_background", events.last().name.wireValue)
    }

    @Test
    fun uncleanExitAcrossProcesses() {
        // Drive the entry points directly: a second core on the same prefs and disk is a new process.
        val first = makeCore()
        first.lifecycle.becameActive()
        val firstSession = events(first).first().sessionId

        val second = makeCore()
        second.lifecycle.becameActive()
        val events = events(second).drop(2)
        assertEquals(listOf("unclean_exit", "session_end", "session_start", "app_open"), names(events))
        assertNull(events[0].sessionId)
        assertEquals(firstSession, events[0].properties["previous_session_id"])
        assertEquals(firstSession, events[1].sessionId)
        assertEquals("unclean_exit", events[1].properties["reason"])
        assertTrue(events[2].sessionId != firstSession)
        assertEquals(events[2].sessionId, events[3].sessionId)
    }
}
