package dev.renoo.sdk.schema

import android.app.Application
import android.os.Looper
import dev.renoo.sdk.Configuration
import dev.renoo.sdk.Event
import dev.renoo.sdk.EventName
import dev.renoo.sdk.PurchaseResult
import dev.renoo.sdk.TrackingScope
import dev.renoo.sdk.collectors.DetectedTransaction
import dev.renoo.sdk.collectors.HomeActivity
import dev.renoo.sdk.collectors.Inference
import dev.renoo.sdk.collectors.InferenceBasis
import dev.renoo.sdk.collectors.LifecycleEngine
import dev.renoo.sdk.collectors.LifecycleStep
import dev.renoo.sdk.collectors.PurchaseFacts
import dev.renoo.sdk.collectors.PurchaseStateValue
import dev.renoo.sdk.collectors.SettingsActivity
import dev.renoo.sdk.collectors.SubscriptionMapping
import dev.renoo.sdk.collectors.SubscriptionStatus
import dev.renoo.sdk.collectors.SubscriptionTrigger
import dev.renoo.sdk.collectors.TransactionKind
import dev.renoo.sdk.core.EventPipeline
import dev.renoo.sdk.core.FakeSharedPreferences
import dev.renoo.sdk.core.SdkCore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.nio.file.Files
import java.util.UUID

/**
 * Every event type the SDK emits, produced by the real SDK code (the core driven by Robolectric
 * activities, the lifecycle engine, the subscription mapping), validated against the strict
 * event schema v1. The schema path comes from the `RENOO_EVENT_SCHEMA` environment variable; the
 * test is skipped when it is unset or the file is missing.
 */
@RunWith(RobolectricTestRunner::class)
class EventSchemaV1Test {
    private val directory = Files.createTempDirectory("schema").toFile()
    private val app = RuntimeEnvironment.getApplication() as Application

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun everyEventTheSdkEmitsMatchesSchemaV1() {
        val file = JsonSchemaValidator.findEventSchemaV1()
        assumeTrue("RENOO_EVENT_SCHEMA not set; skipping schema validation", file != null)
        val validator = JsonSchemaValidator.load(requireNotNull(file))

        val core = SdkCore(
            Configuration(
                apiKey = "test",
                purchaseHandler = { _, onResult -> onResult(PurchaseResult.CANCELLED) },
                trackingScope = TrackingScope.EVERYONE,
            ),
            app,
            prefs = FakeSharedPreferences(),
            storeDirectory = directory,
        )
        core.lifecycle.foregroundProbe = { false }
        core.start()
        settle(core)
        val pipeline = core.environment.pipeline

        // Real activities: install, identify (null), session_start, app_open cold, screen_view,
        // app_background, app_open warm.
        val home = Robolectric.buildActivity(HomeActivity::class.java).create().start().resume()
        settle(core)
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        home.pause().stop()
        settings.pause().stop()
        settle(core)
        home.restart().start().resume()
        settle(core)

        // Push permission changes, identify with a user, reset.
        core.context.pushStatusRead("not_determined")
        core.context.pushStatusRead("authorized")
        pipeline.identify("user-42")
        pipeline.reset()
        settle(core)

        // Session timeout and unclean exit, from the lifecycle engine with the real context.
        val lifecyclePrefs = FakeSharedPreferences()
        var now = System.currentTimeMillis() - 3_600_000L
        fun engine() = LifecycleEngine(
            prefs = lifecyclePrefs,
            sessionTimeoutMillis = LifecycleEngine.SESSION_TIMEOUT_MILLIS,
            clock = { now },
            makeSessionId = { UUID.randomUUID().toString() },
            context = { core.context.snapshot() },
        )
        val first = engine()
        apply(pipeline, first.didBecomeActive())
        apply(pipeline, first.didEnterBackground())
        now += LifecycleEngine.SESSION_TIMEOUT_MILLIS + 1_000
        apply(pipeline, engine().didBecomeActive()) // new process after a timeout
        now += 5_000
        apply(pipeline, engine().didBecomeActive()) // new process, the last one died in the foreground

        // Subscription events from the mapping the tracker uses.
        val bought = PurchaseFacts(
            purchaseToken = "token-a",
            orderId = "GPA.1111-2222-3333-44444",
            productIds = listOf("premium_monthly"),
            purchaseTimeMillis = now - 86_400_000L,
            state = PurchaseStateValue.PURCHASED,
            isAutoRenewing = true,
            accountToken = "acct-hash-1",
        )
        val trial = Inference(isTrial = true, expiresAtMillis = now + 86_400_000L, basis = InferenceBasis.TRIAL_CONSUMED)
        val states = listOf(
            SubscriptionMapping.stateProperties(SubscriptionStatus.ACTIVE, bought, SubscriptionTrigger.APP_OPEN, trial),
            SubscriptionMapping.stateProperties(SubscriptionStatus.ACTIVE, bought.copy(accountToken = null), SubscriptionTrigger.TRANSACTION_UPDATE),
            SubscriptionMapping.stateProperties(SubscriptionStatus.ACTIVE, bought.copy(isAutoRenewing = false), SubscriptionTrigger.STATUS_UPDATE),
            SubscriptionMapping.stateProperties(SubscriptionStatus.SUSPENDED, bought.copy(isSuspended = true), SubscriptionTrigger.APP_OPEN),
            SubscriptionMapping.stateProperties(SubscriptionStatus.EXPIRED, bought.copy(isAutoRenewing = false), SubscriptionTrigger.APP_OPEN),
            SubscriptionMapping.stateProperties(SubscriptionStatus.NONE, null, SubscriptionTrigger.APP_OPEN),
        )
        states.forEach { pipeline.track(EventName.SUBSCRIPTION_STATE, it) }
        for (kind in TransactionKind.entries) {
            pipeline.track(EventName.TRANSACTION, SubscriptionMapping.transactionProperties(DetectedTransaction(bought, kind)))
        }
        settle(core)

        val events = pipeline.queuedEvents()
        val failures = events.flatMap { event -> validator.validate(event.toJson()).map { "${event.name.wireValue}: $it" } }
        assertEquals("schema errors:\n" + failures.joinToString("\n"), emptyList<String>(), failures)

        assertEquals(
            "every event type is covered",
            EventName.entries.map { it.wireValue }.toSet(),
            events.map { it.name.wireValue }.toSet(),
        )
        assertEquals(setOf("cold", "warm"), values(events, EventName.APP_OPEN, "start_type"))
        assertTrue("a previous screen", values(events, EventName.SCREEN_VIEW, "previous_screen_name").any { it != null })
        assertEquals(setOf("timeout", "unclean_exit"), values(events, EventName.SESSION_END, "reason"))
        assertEquals(setOf(null, "user-42"), values(events, EventName.IDENTIFY, "user_id"))
        assertEquals(setOf("active", "suspended", "expired", "none"), values(events, EventName.SUBSCRIPTION_STATE, "status"))
        assertEquals(setOf("app_open", "transaction_update", "status_update"), values(events, EventName.SUBSCRIPTION_STATE, "trigger"))
        val install = events.single { it.name == EventName.INSTALL }
        assertTrue(install.properties["id_source"] in setOf("android_id", "random"))
    }

    private fun values(events: List<Event>, name: EventName, key: String): Set<Any?> =
        events.filter { it.name == name }.map { it.properties[key] }.toSet()

    private fun apply(pipeline: EventPipeline, steps: List<LifecycleStep>) {
        for (step in steps) {
            when (step) {
                is LifecycleStep.SetSession -> pipeline.setSessionId(step.id)
                is LifecycleStep.Emit -> pipeline.track(step.name, step.properties, step.timestampMillis, step.session)
            }
        }
    }

    private fun settle(core: SdkCore) {
        repeat(3) {
            core.environment.pipeline.waitUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
