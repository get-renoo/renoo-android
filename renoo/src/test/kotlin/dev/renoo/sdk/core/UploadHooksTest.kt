package dev.renoo.sdk.core

import android.app.Application
import dev.renoo.sdk.Configuration
import dev.renoo.sdk.EventName
import dev.renoo.sdk.LogLevel
import dev.renoo.sdk.PurchaseResult
import dev.renoo.sdk.TrackingScope
import dev.renoo.sdk.collectors.InstallIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.nio.file.Files
import java.util.Collections

/** The hooks the uploader hangs on: queue size after each append, and foreground / background order. */
@RunWith(RobolectricTestRunner::class)
class UploadHooksTest {
    private val harness = PipelineHarness()
    private val directory = Files.createTempDirectory("hooks").toFile()

    @After
    fun tearDown() {
        harness.tearDown()
        directory.deleteRecursively()
    }

    @Test
    fun queueSizeListenerSeesTheCountAfterEachAppend() {
        val pipeline = harness.makePipeline()
        val sizes = Collections.synchronizedList(mutableListOf<Int>())
        pipeline.queueSizeListener = { sizes.add(it) }
        repeat(3) { pipeline.track(EventName.SCREEN_VIEW) }
        pipeline.waitUntilIdle()
        assertEquals(listOf(1, 2, 3), sizes.toList())
    }

    @Test
    fun aThrowingQueueListenerDoesNotLoseEvents() {
        val pipeline = harness.makePipeline()
        pipeline.queueSizeListener = { error("boom") }
        pipeline.track(EventName.SCREEN_VIEW)
        assertEquals(1, pipeline.queuedEvents().size)
    }

    @Test
    fun foregroundListenerRunsAfterTheTransitionsEventsAreEnqueued() {
        val prefs = FakeSharedPreferences()
        val core = SdkCore(
            Configuration(apiKey = "test", purchaseHandler = { _, onResult -> onResult(PurchaseResult.CANCELLED) }, trackingScope = TrackingScope.EVERYONE),
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
        )
        val pipeline = core.environment.pipeline
        pipeline.waitUntilIdle()
        val seen = Collections.synchronizedList(mutableListOf<String>())
        // Like the uploader: the listener posts to the executor, so it runs after the events.
        core.lifecycle.foregroundListener = { foreground ->
            core.environment.executor.post {
                seen.add("$foreground:" + pipeline.queueOnExecutor.all().lastOrNull()?.name?.wireValue)
            }
        }
        core.lifecycle.becameActive()
        core.lifecycle.enteredBackground()
        pipeline.waitUntilIdle()
        assertEquals(2, seen.size)
        assertTrue(seen[0], seen[0].startsWith("true:"))
        assertEquals("false:app_background", seen[1])
    }
}
