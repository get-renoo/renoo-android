package dev.renoo.sdk.collectors

import android.app.Application
import dev.renoo.sdk.Configuration
import dev.renoo.sdk.EventName
import dev.renoo.sdk.PurchaseResult
import dev.renoo.sdk.ScreenSource
import dev.renoo.sdk.TrackingScope
import dev.renoo.sdk.core.FakeSharedPreferences
import dev.renoo.sdk.core.withIdentitySent
import dev.renoo.sdk.core.SdkCore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.nio.file.Files

/** Robolectric only for a real application Context; the pipeline itself needs none. */
@RunWith(RobolectricTestRunner::class)
class ScreenCollectorTest {
    private val directory = Files.createTempDirectory("screens").toFile()
    private val core = SdkCore(
        Configuration(apiKey = "test", purchaseHandler = { _, onResult -> onResult(PurchaseResult.CANCELLED) }, trackingScope = TrackingScope.EVERYONE),
        RuntimeEnvironment.getApplication() as Application,
        prefs = FakeSharedPreferences().withIdentitySent(),
        storeDirectory = directory,
    )

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun manualScreensChainPreviousScreenName() {
        core.screens.trackScreen("Home", ScreenSource.MANUAL)
        core.screens.trackScreen("  Paywall \n", ScreenSource.FLUTTER)
        core.screens.trackScreen("   ", ScreenSource.MANUAL)
        val events = core.environment.pipeline.queuedEvents()
        assertEquals(listOf(EventName.SCREEN_VIEW, EventName.SCREEN_VIEW), events.map { it.name })
        assertEquals("Home", events[0].properties["screen_name"])
        assertEquals("manual", events[0].properties["source"])
        assertNull(events[0].properties["previous_screen_name"])
        assertEquals(true, events[0].properties.containsKey("previous_screen_name"))
        assertEquals("Paywall", events[1].properties["screen_name"])
        assertEquals("flutter", events[1].properties["source"])
        assertEquals("Home", events[1].properties["previous_screen_name"])
    }

    @Test
    fun namesAreCappedAt100CharactersWithoutSplittingSurrogates() {
        core.screens.trackScreen("a".repeat(150), ScreenSource.MANUAL)
        assertEquals(100, (core.environment.pipeline.queuedEvents()[0].properties["screen_name"] as String).length)
        val emoji = "a".repeat(99) + "😀" // the emoji straddles the limit
        assertEquals("a".repeat(99), ScreenCollector.truncate(emoji, 100))
    }

    @Test
    fun sourceWireValues() {
        assertEquals(
            listOf("activity", "compose", "manual", "flutter", "react_native"),
            ScreenSource.entries.map { it.wireValue },
        )
    }
}
