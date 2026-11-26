package dev.renoo.sdk.collectors

import android.app.Application
import android.content.SharedPreferences
import android.os.Looper
import dev.renoo.sdk.Configuration
import dev.renoo.sdk.LogLevel
import dev.renoo.sdk.PurchaseResult
import dev.renoo.sdk.TrackingScope
import dev.renoo.sdk.core.FakeSharedPreferences
import dev.renoo.sdk.core.SdkCore
import dev.renoo.sdk.core.SdkLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** SDK code running from the host app's looper must never let an exception escape. */
@RunWith(RobolectricTestRunner::class)
class CallbackSafetyTest {
    private val directory = Files.createTempDirectory("safety").toFile()

    /** Prefs whose `edit()` fails on demand, like a full disk or a corrupt prefs file. */
    private class FailingPrefs(private val delegate: SharedPreferences = FakeSharedPreferences()) : SharedPreferences by delegate {
        @Volatile
        var failing = false
        val edits = AtomicInteger()

        override fun edit(): SharedPreferences.Editor {
            if (failing) throw IllegalStateException("simulated prefs failure")
            edits.incrementAndGet()
            return delegate.edit()
        }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun aFailingHeartbeatDoesNotCrashTheMainThreadAndKeepsBeating() {
        val prefs = FailingPrefs()
        val core = SdkCore(
            Configuration(apiKey = "test", purchaseHandler = { _, onResult -> onResult(PurchaseResult.CANCELLED) }, trackingScope = TrackingScope.EVERYONE),
            RuntimeEnvironment.getApplication() as Application,
            prefs = prefs,
            storeDirectory = directory,
        )
        core.lifecycle.foregroundProbe = { true }
        core.start()
        val looper = shadowOf(Looper.getMainLooper())
        repeat(3) {
            core.environment.pipeline.waitUntilIdle()
            looper.idle()
        }

        prefs.failing = true
        looper.idleFor(Duration.ofMillis(LifecycleCollector.HEARTBEAT_INTERVAL_MILLIS)) // throws here without the guard
        prefs.failing = false
        val before = prefs.edits.get()
        looper.idleFor(Duration.ofMillis(LifecycleCollector.HEARTBEAT_INTERVAL_MILLIS))
        assertTrue("the heartbeat is still scheduled after a failure", prefs.edits.get() > before)
    }

    @Test
    fun aFailingScheduledTaskDoesNotCrashTheMainThread() {
        val logs = mutableListOf<String>()
        val scheduler = MainLooperScheduler(SdkLogger(LogLevel.ERROR) { _, message -> logs.add(message) })
        scheduler.schedule(10) { throw IllegalStateException("boom") }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        assertEquals(1, logs.count { it.startsWith("Scheduled SDK task failed") })
    }
}
