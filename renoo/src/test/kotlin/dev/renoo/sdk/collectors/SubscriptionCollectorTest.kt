package dev.renoo.sdk.collectors

import android.app.Activity
import android.app.Application
import dev.renoo.sdk.Configuration
import dev.renoo.sdk.EventName
import dev.renoo.sdk.PurchaseResult
import dev.renoo.sdk.TrackingScope
import dev.renoo.sdk.core.FakeSharedPreferences
import dev.renoo.sdk.core.SdkCore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper
import java.nio.file.Files

/** The Android wiring: activity callbacks → reads, and the real Play source on a device without Play. */
@RunWith(RobolectricTestRunner::class)
class SubscriptionCollectorTest {
    private val directory = Files.createTempDirectory("subscription").toFile()
    private val app = RuntimeEnvironment.getApplication() as Application
    private val core = SdkCore(
        Configuration(apiKey = "test", purchaseHandler = { _, onResult -> onResult(PurchaseResult.CANCELLED) }, trackingScope = TrackingScope.SUBSCRIBERS_ONLY),
        app,
        prefs = FakeSharedPreferences(),
        storeDirectory = directory,
    )
    private val source = FakeSubscriptionSource().apply { purchases = listOf(Fixture.purchase()) }
    private var uptime = 1_000L
    private val collector = SubscriptionCollector(core.environment, source, FakeScheduler()) { uptime }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun launchReadThenOneReadPerReturnToTheForeground() {
        collector.start()
        val activity = Robolectric.buildActivity(Activity::class.java).setup() // first start: same app open
        assertEquals(1, reads())

        activity.pause().stop()
        uptime += 60_000
        activity.start()
        assertEquals("back from the background", 2, reads())

        activity.pause().stop()
        uptime += 500 // a quick bounce is still coalesced
        activity.start()
        assertEquals(2, reads())

        val states = core.environment.pipeline.events().named(EventName.SUBSCRIPTION_STATE)
        assertEquals(listOf("app_open", "app_open"), states.map { it.properties["trigger"] })
    }

    @Test
    fun rotationIsNotAForeground() {
        collector.start()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        uptime += 60_000
        controller.configurationChange(android.content.res.Configuration(app.resources.configuration).apply { orientation = 2 })
        assertEquals(1, reads())
    }

    @Test
    fun realPlaySourceReportsBillingUnavailableQuietly() {
        val play = PlayBillingSubscriptionSource(app, core.environment.logger)
        var result: SubscriptionReadResult? = null
        play.read { result = it }
        repeat(20) {
            ShadowLooper.idleMainLooper()
            if (result == null) Thread.sleep(25)
        }
        play.release()
        val failure = result as? SubscriptionReadResult.Failure
        assertTrue("got $result", failure != null && !failure.retryable)
    }

    @Test
    fun configuredProductIdsReachTheCataloguePreRead() {
        val configured = SdkCore(
            Configuration(
                apiKey = "test",
                purchaseHandler = { _, onResult -> onResult(PurchaseResult.CANCELLED) },
                subscriptionProductIds = listOf(" com.example.monthly ", "com.example.annual", "com.example.monthly", ""),
            ),
            app,
            prefs = FakeSharedPreferences(),
            storeDirectory = directory,
        )
        val noPurchases = FakeSubscriptionSource()
        SubscriptionCollector(configured.environment, noPurchases, FakeScheduler()) { uptime }.start()
        configured.environment.pipeline.events()
        assertEquals(listOf(listOf("com.example.monthly", "com.example.annual")), noPurchases.detailsQueries)
    }

    private fun reads(): Int {
        core.environment.pipeline.events()
        return source.reads
    }
}
