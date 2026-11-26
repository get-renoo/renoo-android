package dev.renoo.sdk.collectors

import dev.renoo.sdk.Event
import dev.renoo.sdk.EventName
import dev.renoo.sdk.TrackingScope
import dev.renoo.sdk.collectors.Fixture.purchase
import dev.renoo.sdk.core.ClearReason
import dev.renoo.sdk.core.EventPipeline
import dev.renoo.sdk.core.Timestamp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The inferred `is_trial` / `expires_at` inside the tracker: catalogue pre-read, cache, timing, existing rules. Plain JVM. */
class SubscriptionInferenceTrackerTest {
    private val harness = CollectorHarness()
    private val source = FakeSubscriptionSource()
    private val scheduler = FakeScheduler()
    private var uptime = 100_000L
    private var wall = t("2026-01-30T12:00:00.000Z") // pre-read 22 h before the purchase

    private val product = "premium_monthly"
    private val configured = listOf(product, "premium_annual")
    private val purchasedAt = t("2026-01-31T10:00:00.000Z")
    private val subscription = purchase(product = product, time = purchasedAt)
    private val baseOnly = listOf(OfferFacts("monthly", null, listOf(PhaseFacts("P1M", 9_990_000, 0, PhaseFacts.INFINITE_RECURRING))))
    private val withTrial = baseOnly + OfferFacts(
        "monthly",
        "free-week",
        listOf(PhaseFacts("P1W", 0, 1, PhaseFacts.FINITE_RECURRING), PhaseFacts("P1M", 9_990_000, 0, PhaseFacts.INFINITE_RECURRING)),
    )

    @After
    fun tearDown() = harness.tearDown()

    private fun t(iso: String): Long = Timestamp.parse(iso) ?: error(iso)

    private fun tracker(pipeline: EventPipeline, productIds: List<String> = emptyList()) = SubscriptionTracker(
        pipeline = pipeline,
        prefs = harness.prefs,
        logger = harness.logger,
        executor = harness.inline,
        source = source,
        scheduler = scheduler,
        installMarker = { 42L },
        uptime = { uptime },
        clock = { wall },
        configuredProductIds = productIds,
    ).also { pipeline.addStateOwner(it::clearState) }

    private fun launch(
        productIds: List<String> = emptyList(),
        scope: TrackingScope = TrackingScope.EVERYONE,
    ): Pair<EventPipeline, SubscriptionTracker> {
        uptime += 60_000
        val pipeline = harness.makePipeline(scope)
        return pipeline to tracker(pipeline, productIds).also { it.appOpened() }
    }

    private fun EventPipeline.lastState(): Event = events().named(EventName.SUBSCRIPTION_STATE).last()

    /** A day before the purchase the app is opened (pre-read); the user then buys in a later session. */
    private fun buyAfterAPreRead(catalogueAfter: List<OfferFacts>): Pair<EventPipeline, SubscriptionTracker> {
        source.catalogue = mapOf(product to withTrial)
        launch(configured)
        assertEquals(listOf(configured), source.detailsQueries)

        wall = purchasedAt + 1_500 // the purchase flow just closed
        source.purchases = listOf(subscription)
        source.catalogue = mapOf(product to catalogueAfter)
        val (pipeline, tracker) = launch(configured)
        assertEquals("one read right after the purchase, despite the daily throttle", listOf(product), source.detailsQueries.last())
        return pipeline to tracker
    }

    @Test
    fun preReadWithoutPurchasesEmitsNothingExtraAndLeavesTheGateAlone() {
        source.catalogue = mapOf(product to withTrial)
        val (everyone, _) = launch(configured)
        assertEquals("only the usual state", listOf(EventName.SUBSCRIPTION_STATE), everyone.events().map { it.name })
        assertEquals("none", everyone.lastState().properties["status"])
        assertEquals(listOf(configured), source.detailsQueries)
        assertTrue(harness.prefs.contains(SubscriptionTracker.KEY_PRODUCT_DETAILS))

        wall += SubscriptionInference.REFRESH_AFTER_SUCCESS_MILLIS
        val (subscribersOnly, _) = launch(configured, TrackingScope.SUBSCRIBERS_ONLY)
        subscribersOnly.track(EventName.SCREEN_VIEW)
        assertTrue("a non-subscriber stores nothing", subscribersOnly.events().none { it.timestamp >= Timestamp.format(wall) && it.name == EventName.SCREEN_VIEW })
        assertEquals(2, source.detailsQueries.size)
    }

    @Test
    fun trialListedBeforeAndGoneAfterIsATrial() {
        val (pipeline, _) = buyAfterAPreRead(catalogueAfter = baseOnly)
        val first = pipeline.lastState()
        assertNull("the read right after the purchase only had the 'before' snapshot", first.properties["is_trial"])
        assertEquals("trial_uncertain", first.properties["inference_basis"])

        wall = purchasedAt + 3_600_000
        val (next, _) = launch(configured)
        val state = next.lastState()
        assertEquals(true, state.properties["is_trial"])
        assertEquals("2026-02-07T10:00:00.000Z", state.properties["expires_at"])
        assertEquals(listOf("expires_at", "is_trial"), state.properties["inferred_fields"])
        assertEquals("trial_consumed", state.properties["inference_basis"])

        wall = t("2026-02-20T00:00:00.000Z")
        val (after, _) = launch(configured)
        assertEquals(false, after.lastState().properties["is_trial"])
        assertEquals("2026-03-07T10:00:00.000Z", after.lastState().properties["expires_at"])
    }

    @Test
    fun trialStillListedAfterThePurchaseIsCautious() {
        buyAfterAPreRead(catalogueAfter = withTrial)
        wall = purchasedAt + 3_600_000
        val (next, _) = launch(configured)
        assertNull(next.lastState().properties["is_trial"])
        assertNull(next.lastState().properties["expires_at"])
        assertEquals(emptyList<String>(), next.lastState().properties["inferred_fields"])
        assertEquals("trial_uncertain", next.lastState().properties["inference_basis"])
    }

    @Test
    fun noTrialListedBeforeThePurchaseIsNotATrial() {
        source.catalogue = mapOf(product to baseOnly)
        launch(configured)
        wall = purchasedAt + 1_500
        source.purchases = listOf(subscription)
        launch(configured)
        wall = t("2026-02-03T00:00:00.000Z")
        val (pipeline, _) = launch(configured)
        assertEquals(false, pipeline.lastState().properties["is_trial"])
        assertEquals("2026-02-28T10:00:00.000Z", pipeline.lastState().properties["expires_at"])
        assertEquals("no_trial_offered", pipeline.lastState().properties["inference_basis"])
    }

    @Test
    fun withoutConfiguredIdsTheFirstSnapshotIsAfterThePurchase() {
        wall = t("2026-02-01T00:00:00.000Z")
        source.purchases = listOf(subscription)
        source.catalogue = mapOf(product to withTrial)
        val (first, _) = launch()
        val state = first.lastState()
        assertNull("nothing cached yet: not held back", state.properties["is_trial"])
        assertEquals(emptyList<String>(), state.properties["inferred_fields"])
        assertTrue(state.properties.containsKey("inference_basis"))
        assertNull("nothing learned about the product yet", state.properties["inference_basis"])
        assertEquals(listOf(listOf(product)), source.detailsQueries)
        assertEquals("released once, after the catalogue query", 1, source.releases)

        val (second, _) = launch()
        assertNull("trial listed only after the purchase: cautious", second.lastState().properties["is_trial"])
        assertEquals("cached for a day: no second query", 1, source.detailsQueries.size)
    }

    @Test
    fun unknownConfiguredIdsAreAskedAboutOnceADay() {
        source.catalogue = emptyMap() // Play doesn't know these IDs
        launch(configured)
        launch(configured)
        wall += SubscriptionInference.RETRY_AFTER_FAILURE_MILLIS
        launch(configured)
        assertEquals("not every launch, not hourly", 1, source.detailsQueries.size)
        wall += SubscriptionInference.REFRESH_AFTER_SUCCESS_MILLIS
        launch(configured)
        assertEquals(2, source.detailsQueries.size)
    }

    @Test
    fun billingUnavailableMeansNoCatalogueQuery() {
        source.results.add(SubscriptionReadResult.Failure("setup: response code 3", retryable = false))
        launch(configured)
        assertTrue(source.detailsQueries.isEmpty())
        assertEquals(1, source.releases)
    }

    @Test
    fun slowCatalogueQueryDelaysNothingAndKeepsOneOperationAtATime() {
        wall = purchasedAt + 60_000
        source.purchases = listOf(subscription)
        source.manualDetails = true
        val pipeline = harness.makePipeline(TrackingScope.EVERYONE)
        val tracker = tracker(pipeline, configured)
        tracker.appOpened()
        assertEquals("state emitted before the catalogue answers", 1, pipeline.events().named(EventName.SUBSCRIPTION_STATE).size)

        tracker.purchaseFlowFinished()
        assertEquals("waits for the catalogue query", 1, source.reads)
        assertEquals(0, source.releases)

        source.heldDetails.removeAt(0)(ProductDetailsResult.Success(mapOf(product to baseOnly)))
        assertEquals("the queued read runs afterwards", 2, source.reads)
        assertTrue("released once idle", source.releases > 0)
    }

    @Test
    fun failedOrTimedOutCatalogueLeavesNullsAndIsRetriedLater() {
        wall = t("2026-02-01T00:00:00.000Z")
        source.purchases = listOf(subscription)
        source.detailsResults.add(ProductDetailsResult.Failure("response code 6"))
        val (first, _) = launch()
        assertNull(first.lastState().properties["expires_at"])
        launch()
        assertEquals("not retried within the hour", 1, source.detailsQueries.size)

        wall += SubscriptionInference.RETRY_AFTER_FAILURE_MILLIS
        source.manualDetails = true
        launch()
        assertEquals(2, source.detailsQueries.size)
        scheduler.run(SubscriptionTracker.DETAILS_TIMEOUT_MILLIS)
        assertTrue("released after the timeout", source.releases >= 3)
        source.heldDetails.removeAt(0)(ProductDetailsResult.Success(mapOf(product to baseOnly))) // late: ignored
        val (last, _) = launch()
        assertNull(last.lastState().properties["expires_at"])
    }

    @Test
    fun anEstimateRollingForwardIsNotAChange() {
        source.catalogue = mapOf(product to baseOnly)
        launch(configured)
        wall = purchasedAt + 1_500
        source.purchases = listOf(subscription)
        launch(configured)
        val (pipeline, tracker) = launch(configured)
        assertEquals("2026-02-28T10:00:00.000Z", pipeline.lastState().properties["expires_at"])
        val before = pipeline.events().named(EventName.SUBSCRIPTION_STATE).size
        wall = t("2026-04-15T00:00:00.000Z") // two periods later: expires_at would move on
        tracker.purchaseFlowFinished()
        assertEquals("no status_update state for an estimate alone", before, pipeline.events().named(EventName.SUBSCRIPTION_STATE).size)

        source.purchases = listOf(subscription.copy(isAutoRenewing = false)) // a real change
        tracker.purchaseFlowFinished()
        val state = pipeline.lastState()
        assertEquals("no new transaction: status_update", "status_update", state.properties["trigger"])
        assertEquals("2026-04-30T10:00:00.000Z", state.properties["expires_at"])
    }

    @Test
    fun withoutConfiguredIdsAndNoTrialListedTheBasisIsNoSnapshotBefore() {
        wall = t("2026-02-01T00:00:00.000Z")
        source.purchases = listOf(subscription)
        source.catalogue = mapOf(product to baseOnly)
        launch()
        val (second, _) = launch()
        val state = second.lastState()
        assertNull(state.properties["is_trial"])
        assertEquals("2026-02-28T10:00:00.000Z", state.properties["expires_at"])
        assertEquals("no_snapshot_before", state.properties["inference_basis"])
    }

    @Test
    fun aChangedInferenceBasisIsNotAChange() {
        // The read right after the purchase knows only the "before" snapshot; the catalogue query
        // that follows it adds the "after" one, so the same purchase now reads as a consumed trial.
        val (pipeline, tracker) = buyAfterAPreRead(catalogueAfter = baseOnly)
        assertEquals("trial_uncertain", pipeline.lastState().properties["inference_basis"])
        val before = pipeline.events().size
        tracker.purchaseFlowFinished()
        assertEquals("no transaction_update state for a new basis alone", before, pipeline.events().size)

        val (next, _) = launch(configured)
        assertEquals("the next app open reports it", "trial_consumed", next.lastState().properties["inference_basis"])
    }

    @Test
    fun optOutClearsTheCatalogueCacheAndDropsAnAnswerInFlight() {
        source.catalogue = mapOf(product to withTrial)
        val (pipeline, _) = launch(configured)
        assertTrue(harness.prefs.contains(SubscriptionTracker.KEY_PRODUCT_DETAILS))
        pipeline.setOptedOut(true)
        pipeline.waitUntilIdle()
        assertFalse(harness.prefs.contains(SubscriptionTracker.KEY_PRODUCT_DETAILS))

        pipeline.setOptedOut(false)
        pipeline.waitUntilIdle()
        source.manualDetails = true
        uptime += 60_000
        tracker(pipeline, configured).appOpened()
        pipeline.setOptedOut(true)
        pipeline.waitUntilIdle()
        source.heldDetails.removeAt(0)(ProductDetailsResult.Success(mapOf(product to withTrial)))
        assertFalse("answer after opt-out not written", harness.prefs.contains(SubscriptionTracker.KEY_PRODUCT_DETAILS))
    }

    @Test
    fun restoreWipeClearsTheCatalogueCache() {
        source.catalogue = mapOf(product to withTrial)
        val (pipeline, tracker) = launch(configured)
        assertTrue(harness.prefs.contains(SubscriptionTracker.KEY_PRODUCT_DETAILS))
        tracker.clearState(ClearReason.RESTORED_FROM_ANOTHER_INSTALL)
        pipeline.waitUntilIdle()
        assertFalse(harness.prefs.contains(SubscriptionTracker.KEY_PRODUCT_DETAILS))
    }

    @Test
    fun configuredProductsStayCachedBeyondTheUnpinnedCap() {
        val many = (1..15).map { "configured_$it" }
        source.catalogue = many.associateWith { baseOnly }
        launch(many)
        val cache = SubscriptionInference.decode(harness.prefs.getString(SubscriptionTracker.KEY_PRODUCT_DETAILS, null))
        assertEquals(many.toSet(), cache.keys)
    }

    @Test
    fun expiredStateIsNeverInferred() {
        source.catalogue = mapOf(product to baseOnly)
        launch(configured)
        wall = purchasedAt + 1_500
        source.purchases = listOf(subscription)
        launch(configured)
        source.purchases = emptyList()
        val (pipeline, _) = launch(configured)
        val state = pipeline.lastState()
        assertEquals("expired", state.properties["status"])
        assertNull(state.properties["expires_at"])
        assertTrue(state.properties.containsKey("inference_basis"))
        assertNull("only `active` is inferred", state.properties["inference_basis"])
        assertEquals("expired is itself an inference", listOf("status"), state.properties["inferred_fields"])
    }
}
