package dev.renoo.sdk.collectors

import dev.renoo.sdk.EventName
import dev.renoo.sdk.TrackingScope
import dev.renoo.sdk.collectors.Fixture.TOKEN_A
import dev.renoo.sdk.collectors.Fixture.TOKEN_B
import dev.renoo.sdk.collectors.Fixture.purchase
import dev.renoo.sdk.core.EventPipeline
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports of the iOS `SubscriptionCollectorTests`, plus Android-specific ones. Plain JVM. */
class SubscriptionTrackerTest {
    private val harness = CollectorHarness()
    private val source = FakeSubscriptionSource()
    private val scheduler = FakeScheduler()
    private var now = 100_000L
    private var installMarker = 42L

    @After
    fun tearDown() = harness.tearDown()

    private fun tracker(pipeline: EventPipeline) = SubscriptionTracker(
        pipeline = pipeline,
        prefs = harness.prefs,
        logger = harness.logger,
        executor = harness.inline,
        source = source,
        scheduler = scheduler,
        installMarker = { installMarker },
        uptime = { now },
    )

    /** A launch: new pipeline (same disk and prefs) and tracker, with the launch read already done. */
    private fun launch(scope: TrackingScope = TrackingScope.SUBSCRIBERS_ONLY): Pair<EventPipeline, SubscriptionTracker> {
        now += 60_000
        val pipeline = harness.makePipeline(scope)
        val tracker = tracker(pipeline)
        tracker.appOpened()
        return pipeline to tracker
    }

    @Test
    fun subscriberOpensTheGateAndBufferedEventsAreKept() {
        val pipeline = harness.makePipeline()
        pipeline.track(EventName.APP_OPEN, mapOf("start_type" to "cold"))
        assertTrue("buffered until the state is known", pipeline.events().isEmpty())

        source.purchases = listOf(purchase())
        tracker(pipeline).appOpened()

        val events = pipeline.events()
        assertEquals(listOf(EventName.APP_OPEN, EventName.SUBSCRIPTION_STATE), events.map { it.name })
        assertEquals("active", events[1].properties["status"])
        assertEquals("app_open", events[1].properties["trigger"])
        assertEquals(true, events[1].properties["auto_renew"])
        assertEquals(TOKEN_A, events[1].properties["purchase_token"])
        assertEquals("existing purchases are history on the first read", 0, events.named(EventName.TRANSACTION).size)
        assertEquals(1, source.releases)
    }

    @Test
    fun nonSubscriberAndPendingPurchaseAreNotTracked() {
        val pipeline = harness.makePipeline()
        pipeline.track(EventName.APP_OPEN)
        source.purchases = listOf(purchase(state = PurchaseStateValue.PENDING, orderId = null))
        tracker(pipeline).appOpened()
        pipeline.track(EventName.SCREEN_VIEW)
        assertTrue(pipeline.events().isEmpty())
    }

    @Test
    fun suspendedSubscriptionIsTrackedAsBillingRetry() {
        source.purchases = listOf(purchase(suspended = true))
        val (pipeline, _) = launch()
        pipeline.track(EventName.SCREEN_VIEW)
        assertEquals(listOf(EventName.SUBSCRIPTION_STATE, EventName.SCREEN_VIEW), pipeline.events().map { it.name })
        assertEquals(listOf("suspended"), pipeline.events().states())
    }

    @Test
    fun cancelledButNotExpiredIsActiveWithAutoRenewOff() {
        source.purchases = listOf(purchase(autoRenewing = false))
        val (pipeline, _) = launch()
        val state = pipeline.events().single()
        assertEquals("active", state.properties["status"])
        assertEquals(false, state.properties["auto_renew"])
    }

    @Test
    fun expiryIsRecordedThenTheGateCloses() {
        source.purchases = listOf(purchase())
        val (pipeline, tracker) = launch()

        // The subscription is gone from Play's list: expired, refunded or on hold.
        source.purchases = emptyList()
        now += 60_000
        tracker.appOpened()
        pipeline.track(EventName.SCREEN_VIEW)

        val events = pipeline.events()
        assertEquals(listOf("active", "expired"), events.states())
        assertEquals(false, events.last().properties["auto_renew"])
        assertEquals("premium_monthly", events.last().properties["product_id"])
        assertEquals(TOKEN_A, events.last().properties["purchase_token"])
        assertTrue("dropped once the user lapsed", events.none { it.name == EventName.SCREEN_VIEW })
    }

    @Test
    fun rememberedSubscriptionSurvivesRelaunch() {
        source.purchases = listOf(purchase())
        launch()
        source.purchases = emptyList()
        val (pipeline, _) = launch(TrackingScope.EVERYONE)
        assertEquals("expired", pipeline.events().states().last())
    }

    @Test
    fun purchaseFlowEmitsTransactionThenStateAndOpensTheGate() {
        val (pipeline, tracker) = launch()
        pipeline.track(EventName.SCREEN_VIEW)
        assertTrue("not a subscriber yet", pipeline.events().isEmpty())

        source.purchases = listOf(purchase())
        tracker.purchaseFlowFinished()
        pipeline.track(EventName.SCREEN_VIEW)

        val events = pipeline.events()
        assertEquals(listOf(EventName.TRANSACTION, EventName.SUBSCRIPTION_STATE, EventName.SCREEN_VIEW), events.map { it.name })
        assertEquals("purchase", events[0].properties["kind"])
        assertEquals("GPA.1111-2222-3333-44444", events[0].properties["transaction_id"])
        assertEquals(TOKEN_A, events[0].properties["purchase_token"])
        assertEquals("transaction_update", events[1].properties["trigger"])
        assertEquals("active", events[1].properties["status"])
    }

    @Test
    fun aStateChangeWithoutTransactionIsAStatusUpdate() {
        source.purchases = listOf(purchase())
        val (pipeline, tracker) = launch()
        source.purchases = listOf(purchase(autoRenewing = false))
        tracker.purchaseFlowFinished()
        val events = pipeline.events()
        assertEquals(listOf(EventName.SUBSCRIPTION_STATE, EventName.SUBSCRIPTION_STATE), events.map { it.name })
        assertEquals("status_update", events[1].properties["trigger"])
        assertEquals(false, events[1].properties["auto_renew"])
    }

    @Test
    fun cancelledPurchaseFlowEmitsNothing() {
        source.purchases = listOf(purchase())
        val (pipeline, tracker) = launch()
        tracker.purchaseFlowFinished()
        assertEquals(listOf("active"), pipeline.events().states())
        assertEquals(2, source.reads)
    }

    @Test
    fun transactionsAreNotEmittedTwiceAcrossLaunches() {
        launch() // seeds with nothing
        source.purchases = listOf(purchase())
        val (first, _) = launch()
        assertEquals(1, first.events().named(EventName.TRANSACTION).size)

        val (second, _) = launch()
        assertEquals("same purchase, next launch", 1, second.events().named(EventName.TRANSACTION).size)
        assertEquals(listOf("active", "active"), second.events().states())
    }

    @Test
    fun renewalIsANewOrderIdOnAKnownToken() {
        source.purchases = listOf(purchase())
        launch()
        source.purchases = listOf(purchase(orderId = "GPA.1111-2222-3333-44444..0"))
        val (pipeline, _) = launch()
        val transactions = pipeline.events().named(EventName.TRANSACTION)
        assertEquals(listOf("renewal"), transactions.map { it.properties["kind"] })
        assertEquals("GPA.1111-2222-3333-44444..0", pipeline.events().last().properties["transaction_id"])
    }

    @Test
    fun replacementTokenIsAnUpgrade() {
        source.purchases = listOf(purchase())
        launch()
        source.purchases = listOf(purchase(token = TOKEN_B, orderId = "GPA.9", product = "premium_yearly", time = Fixture.T0 + 1))
        val (pipeline, _) = launch()
        val transaction = pipeline.events().named(EventName.TRANSACTION).single()
        assertEquals("upgrade", transaction.properties["kind"])
        assertEquals("premium_yearly", transaction.properties["product_id"])
    }

    @Test
    fun pendingPurchaseBecomesATransactionWhenItCompletes() {
        launch()
        source.purchases = listOf(purchase(state = PurchaseStateValue.PENDING, orderId = null))
        val (pending, tracker) = launch()
        assertTrue(pending.events().isEmpty())
        source.purchases = listOf(purchase())
        now += 60_000
        tracker.appOpened()
        assertEquals(listOf(EventName.TRANSACTION, EventName.SUBSCRIPTION_STATE), pending.events().map { it.name })
    }

    @Test
    fun reinstallSeedsAgainEvenWithRestoredPrefs() {
        source.purchases = listOf(purchase())
        launch()
        // Backup restored the prefs into a reinstall: a different install marker.
        installMarker = 43L
        source.purchases = listOf(purchase(), purchase(token = TOKEN_B, time = Fixture.T0 + 1))
        val (pipeline, _) = launch(TrackingScope.EVERYONE)
        assertTrue(pipeline.events().named(EventName.TRANSACTION).isEmpty())
    }

    // region Coalescing and serialisation

    @Test
    fun oneStatePerAppOpen() {
        source.purchases = listOf(purchase())
        val (pipeline, tracker) = launch()
        now += 500 // the first activity starts right after the launch read
        tracker.appOpened()
        assertEquals(1, source.reads)
        now += 1_600 // 2.1 s after the launch read: a real return to the foreground
        tracker.appOpened()
        assertEquals(2, source.reads)
        assertEquals(listOf("app_open", "app_open"), pipeline.events().named(EventName.SUBSCRIPTION_STATE).map { it.properties["trigger"] })
    }

    @Test
    fun oneReadAtATimeAndRequestsDuringAReadAreQueued() {
        source.manual = true
        source.purchases = listOf(purchase())
        val pipeline = harness.makePipeline()
        val tracker = tracker(pipeline)
        tracker.appOpened()
        tracker.appOpened() // same launch, still reading
        tracker.purchaseFlowFinished()
        tracker.purchaseFlowFinished()
        assertEquals(1, source.reads)

        source.answerHeld(SubscriptionReadResult.Success(emptyList()))
        assertEquals("the queued update read starts once the first ends", 2, source.reads)
        assertEquals(0, source.releases)
        source.answerHeld()
        assertEquals(2, source.reads)
        assertEquals(1, source.releases)

        val events = pipeline.events()
        assertEquals(listOf(EventName.TRANSACTION, EventName.SUBSCRIPTION_STATE), events.map { it.name })
        assertEquals("transaction_update", events.last().properties["trigger"])
    }

    @Test
    fun timedOutReadIsAFailureAndALateAnswerIsIgnored() {
        source.manual = true
        val pipeline = harness.makePipeline(TrackingScope.EVERYONE)
        val tracker = tracker(pipeline)
        tracker.appOpened()
        scheduler.run(SubscriptionTracker.READ_TIMEOUT_MILLIS)
        assertEquals("a retry is planned", listOf(SubscriptionTracker.RETRY_DELAYS_MILLIS[0]), scheduler.delays())
        source.answerHeld(SubscriptionReadResult.Success(listOf(purchase())))
        assertTrue("the late answer is dropped", pipeline.events().isEmpty())
    }

    // endregion

    // region Failures and the gate

    @Test
    fun billingUnavailableOnFirstRunClosesTheGateWithoutRetrying() {
        val pipeline = harness.makePipeline()
        pipeline.track(EventName.APP_OPEN)
        source.results.add(SubscriptionReadResult.Failure("setup: response code 3", retryable = false))
        tracker(pipeline).appOpened()
        pipeline.track(EventName.SCREEN_VIEW)

        assertTrue("no state, nothing stored", pipeline.events().isEmpty())
        assertEquals(1, source.reads)
        assertTrue("no retry", scheduler.delays().none { it in SubscriptionTracker.RETRY_DELAYS_MILLIS.toList() })
        assertEquals(1, source.releases)
    }

    @Test
    fun transientFailuresRetryABoundedNumberOfTimesWhileEventsWait() {
        val pipeline = harness.makePipeline()
        pipeline.track(EventName.APP_OPEN)
        repeat(3) { source.results.add(SubscriptionReadResult.Failure("service disconnected", retryable = true)) }
        val tracker = tracker(pipeline)
        tracker.appOpened()
        assertEquals(0, source.releases)

        scheduler.run(SubscriptionTracker.RETRY_DELAYS_MILLIS[0])
        scheduler.run(SubscriptionTracker.RETRY_DELAYS_MILLIS[1])
        assertEquals("first read + 2 retries", 3, source.reads)
        assertTrue("no more retries", scheduler.delays().none { it in SubscriptionTracker.RETRY_DELAYS_MILLIS.toList() })
        assertEquals(1, source.releases)

        // Gave up: the gate is closed, so the buffered app_open is gone. A later read can still open
        // it (as the first successful read of the install it records the purchase as history).
        source.purchases = listOf(purchase())
        tracker.purchaseFlowFinished()
        assertEquals(listOf(EventName.SUBSCRIPTION_STATE), pipeline.events().map { it.name })
    }

    @Test
    fun retrySucceedsAndBufferedEventsAreKept() {
        val pipeline = harness.makePipeline()
        pipeline.track(EventName.APP_OPEN)
        source.results.add(SubscriptionReadResult.Failure("network", retryable = true))
        source.purchases = listOf(purchase())
        tracker(pipeline).appOpened()
        assertTrue(pipeline.events().isEmpty())
        scheduler.run(SubscriptionTracker.RETRY_DELAYS_MILLIS[0])
        assertEquals(listOf(EventName.APP_OPEN, EventName.SUBSCRIPTION_STATE), pipeline.events().map { it.name })
        assertEquals("app_open", pipeline.events().last().properties["trigger"])
    }

    @Test
    fun failureUsesTheLastKnownStateFromAnEarlierLaunch() {
        source.purchases = listOf(purchase())
        launch()

        source.results.add(SubscriptionReadResult.Failure("setup: response code 3", retryable = false))
        val pipeline = harness.makePipeline()
        pipeline.track(EventName.APP_OPEN)
        tracker(pipeline).appOpened()
        assertEquals("no state emitted, but the known subscriber stays tracked", listOf(EventName.SUBSCRIPTION_STATE, EventName.APP_OPEN), pipeline.events().map { it.name })
    }

    @Test
    fun failureNeverFlipsAGateDecidedInThisProcess() {
        source.purchases = listOf(purchase())
        val (pipeline, tracker) = launch()
        source.results.add(SubscriptionReadResult.Failure("setup: response code 3", retryable = false))
        now += 60_000
        tracker.appOpened()
        pipeline.track(EventName.SCREEN_VIEW)
        assertEquals(listOf(EventName.SUBSCRIPTION_STATE, EventName.SCREEN_VIEW), pipeline.events().map { it.name })
    }

    @Test
    fun noReadsWhileOptedOut() {
        val pipeline = harness.makePipeline()
        pipeline.setOptedOut(true)
        tracker(pipeline).appOpened()
        assertEquals(0, source.reads)
    }

    // region Opt-out

    private val subscriptionKeys = listOf(
        SubscriptionTracker.KEY_KNOWN,
        SubscriptionTracker.KEY_LAST_SUBSCRIPTION,
        SubscriptionTracker.KEY_SEEDED_FOR,
        SubscriptionTracker.KEY_LAST_TRACKED,
    )

    /** A tracker wired to the pipeline's opt-out, as SdkCore does. */
    private fun ownedTracker(pipeline: EventPipeline) = tracker(pipeline).also { t -> pipeline.addStateOwner(t::clearState) }

    private fun optOut(pipeline: EventPipeline) {
        pipeline.setOptedOut(true)
        pipeline.waitUntilIdle()
    }

    @Test
    fun optOutDeletesTheStoredSubscriptionAndOptInStartsOver() {
        source.purchases = listOf(purchase())
        val pipeline = harness.makePipeline()
        val tracker = ownedTracker(pipeline)
        tracker.appOpened()
        assertTrue(harness.prefs.getString(SubscriptionTracker.KEY_LAST_SUBSCRIPTION, null)!!.contains(TOKEN_A))

        optOut(pipeline)
        subscriptionKeys.forEach { assertTrue("$it deleted", !harness.prefs.contains(it)) }

        // Opt back in: the next foreground reads again, emits the state and re-seeds without a transaction.
        pipeline.setOptedOut(false)
        now += 60_000
        tracker.appOpened()
        val events = pipeline.events()
        // Opting out cleared the identify flag; it goes out again first (the gate was still open).
        assertEquals(listOf(EventName.IDENTIFY, EventName.SUBSCRIPTION_STATE), events.map { it.name })
        assertTrue(harness.prefs.contains(SubscriptionTracker.KEY_LAST_SUBSCRIPTION))
    }

    @Test
    fun aReadInFlightDuringOptOutWritesNothingBack() {
        source.manual = true
        source.purchases = listOf(purchase())
        val pipeline = harness.makePipeline()
        val tracker = ownedTracker(pipeline)
        tracker.appOpened()
        optOut(pipeline)

        source.answerHeld()
        subscriptionKeys.forEach { assertTrue("$it not written back", !harness.prefs.contains(it)) }
        pipeline.setOptedOut(false)
        assertTrue(pipeline.events().isEmpty())
        assertEquals("the connection is still released", 1, source.releases)
    }

    @Test
    fun optOutDuringAPendingRetryReleasesTheConnection() {
        source.results.add(SubscriptionReadResult.Failure("service disconnected", retryable = true))
        val pipeline = harness.makePipeline()
        val tracker = ownedTracker(pipeline)
        tracker.appOpened()
        assertEquals("kept for the retry", 0, source.releases)

        optOut(pipeline)
        scheduler.run(SubscriptionTracker.RETRY_DELAYS_MILLIS[0])
        assertEquals("no read after opt-out", 1, source.reads)
        assertEquals(1, source.releases)
    }

    // endregion
}
