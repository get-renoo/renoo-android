package dev.renoo.sdk.collectors

import android.content.SharedPreferences
import dev.renoo.sdk.EventName
import dev.renoo.sdk.core.ClearReason
import dev.renoo.sdk.core.EventPipeline
import dev.renoo.sdk.core.SdkLogger
import dev.renoo.sdk.core.TrackingState
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The subscription rules, without Android: reads through a [SubscriptionSource], emits
 * `transaction` and `subscription_state`, and drives the tracking gate. Ported from the iOS
 * `SubscriptionTracker`, with the lessons learned there:
 *
 * - **One read at a time.** A request that arrives during a read is queued (at most one app-open
 *   and one transaction-update read) and runs when the current read ends.
 * - **One `subscription_state` per app open.** An app-open request within [APP_OPEN_COALESCING_MILLIS]
 *   of the previous app-open read (or while one is running) is skipped, so the launch read and the
 *   first activity start count once.
 * - **State after its transactions, and only when it changed** (for re-reads after a purchase
 *   flow: `transaction_update` with new transactions, `status_update` without; app-open reads always emit).
 * - **Transactions deduped across launches** by hashed purchase token + order ID, in a bounded prefs
 *   entry. The first successful read of an install records existing purchases without emitting them.
 * - **Gate order:** opened before a subscriber's events, closed after a lapsed user's final state.
 * - **Failures** never flip a gate already decided in this process. With no answer yet: the last
 *   state a successful read saw on this install is used if there is one; otherwise events keep
 *   waiting in memory while bounded retries run, and the gate closes when they give up (see
 *   [onFailure]).
 *
 * - **Inferred `is_trial` / `expires_at`** ([SubscriptionInference]) come only from what is already
 *   cached in prefs, so the state is never held back by a catalogue query. After the state is
 *   published, the configured and owned products that are unknown, just bought or due for a
 *   refresh are queried (`queryProductDetailsAsync`, at most once a day per product plus once
 *   right after a purchase, an hour after a failure) on the same connection; the answer only updates the cache, used from the next read on. That query
 *   counts as part of the read (one Billing operation at a time) and has its own timeout. A change
 *   in an inferred value or in `inference_basis` alone never emits a `status_update` state ([SubscriptionMapping.comparable]).
 *
 * Every method hops to [executor]; all state below is executor-only.
 *
 * @param installMarker identifies this install (first-install time): seeding is redone after a
 *   reinstall even if Android's backup restored the SDK prefs.
 * @param uptime monotonic milliseconds, for coalescing.
 * @param clock wall-clock milliseconds, for the inference and catalogue refresh times.
 * @param configuredProductIds `Configuration.subscriptionProductIds`: read from the catalogue at
 *   app opens even without a purchase, so a "before" snapshot exists when the user buys.
 */
internal class SubscriptionTracker(
    private val pipeline: EventPipeline,
    private val prefs: SharedPreferences,
    private val logger: SdkLogger,
    private val executor: Executor,
    private val source: SubscriptionSource,
    private val scheduler: Scheduler,
    private val installMarker: () -> Long,
    private val uptime: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val configuredProductIds: List<String> = emptyList(),
) {
    private var inFlight: SubscriptionTrigger? = null
    private var pendingAppOpen = false
    private var pendingUpdate = false
    private var retryScheduled = false
    private var retriesLeft = 0
    private var lastAppOpenRead: Long? = null
    private var gateDecided = false
    private var detailsInFlight = false

    /** Bumped by [clearState] so a catalogue answer that arrives after an opt-out is dropped. */
    private val generation = AtomicInteger(0)

    private val busy: Boolean get() = inFlight != null || detailsInFlight

    /** Properties of the last `subscription_state` emitted in this process, without `trigger`. */
    private var lastState: Map<String, Any?>? = null

    /** Launch or return to the foreground: read and emit the state (trigger `app_open`), coalesced. */
    fun appOpened() = executor.execute { request(SubscriptionTrigger.APP_OPEN, isRetry = false) }

    /** Something may have bought or changed a subscription (Play's purchase activity closed). */
    fun purchaseFlowFinished() = executor.execute { request(SubscriptionTrigger.TRANSACTION_UPDATE, isRetry = false) }

    /**
     * Executor (the pipeline's opt-out / restore task). Deletes the stored subscription state,
     * including the raw purchase token in [KEY_LAST_SUBSCRIPTION], and forgets this process's last
     * state so the next read (after `optIn`, on the next foreground) emits again and re-seeds known
     * purchases without reporting them as new. A read in flight finishes without writing (see
     * [onSuccess]); with only a retry pending, the billing connection is released now.
     */
    fun clearState(reason: ClearReason) {
        prefs.edit()
            .remove(KEY_KNOWN)
            .remove(KEY_LAST_SUBSCRIPTION)
            .remove(KEY_SEEDED_FOR)
            .remove(KEY_LAST_TRACKED)
            .remove(KEY_PRODUCT_DETAILS)
            .apply()
        generation.incrementAndGet()
        lastState = null
        pendingAppOpen = false
        pendingUpdate = false
        if (reason == ClearReason.OPT_OUT) releaseIfIdle()
    }

    // region Scheduling

    private fun request(trigger: SubscriptionTrigger, isRetry: Boolean) {
        if (pipeline.isOptedOut) {
            logger.debug { "opted out: subscription read skipped" }
            if (isRetry) releaseIfIdle()
            return
        }
        if (trigger == SubscriptionTrigger.APP_OPEN && !isRetry) {
            if (inFlight == SubscriptionTrigger.APP_OPEN) {
                logger.debug { "app-open subscription read already running; skipped" }
                return
            }
            val last = lastAppOpenRead
            val now = uptime()
            if (last != null && now >= last && now - last < APP_OPEN_COALESCING_MILLIS) {
                logger.debug { "app-open subscription read done ${now - last} ms ago; skipped" }
                return
            }
        }
        if (busy) {
            if (trigger == SubscriptionTrigger.APP_OPEN) pendingAppOpen = true else pendingUpdate = true
            return
        }
        if (!isRetry) retriesLeft = MAX_RETRIES
        start(trigger)
    }

    private fun start(trigger: SubscriptionTrigger) {
        inFlight = trigger
        if (trigger == SubscriptionTrigger.APP_OPEN) lastAppOpenRead = uptime()
        val delivered = AtomicBoolean(false)
        val deliver: (SubscriptionReadResult) -> Unit = { result ->
            if (delivered.compareAndSet(false, true)) executor.execute { finish(trigger, result) }
        }
        scheduler.schedule(READ_TIMEOUT_MILLIS) {
            deliver(SubscriptionReadResult.Failure("timed out after $READ_TIMEOUT_MILLIS ms", retryable = true))
        }
        try {
            source.read(deliver)
        } catch (t: Throwable) {
            deliver(SubscriptionReadResult.Failure("exception: ${t.javaClass.simpleName}", retryable = false))
        }
    }

    private fun finish(trigger: SubscriptionTrigger, result: SubscriptionReadResult) {
        inFlight = null
        // When a catalogue query starts, its completion runs the pending work instead (see finishDetails).
        var detailsStarted = false
        try {
            when (result) {
                is SubscriptionReadResult.Success -> {
                    onSuccess(trigger, result.purchases)
                    detailsStarted = queryProductDetails(result.purchases)
                }
                is SubscriptionReadResult.Failure -> onFailure(trigger, result)
            }
        } catch (t: Throwable) {
            logger.error({ "subscription read handling failed" }, t)
        }
        if (!detailsStarted) runPending()
    }

    private fun runPending() {
        when {
            pendingAppOpen -> {
                pendingAppOpen = false
                request(SubscriptionTrigger.APP_OPEN, isRetry = false)
            }
            pendingUpdate -> {
                pendingUpdate = false
                request(SubscriptionTrigger.TRANSACTION_UPDATE, isRetry = false)
            }
        }
        // The pending app-open may have been coalesced away; check again before releasing.
        if (!busy && pendingUpdate) {
            pendingUpdate = false
            request(SubscriptionTrigger.TRANSACTION_UPDATE, isRetry = false)
        }
        releaseIfIdle()
    }

    /** Drops the billing connection when no read is running or planned. */
    private fun releaseIfIdle() {
        if (busy || retryScheduled) return
        try {
            source.release()
        } catch (t: Throwable) {
            logger.error({ "Could not release the billing connection" }, t)
        }
    }

    // endregion

    // region Results

    private fun onSuccess(trigger: SubscriptionTrigger, purchases: List<PurchaseFacts>) {
        retriesLeft = 0
        // The user opted out while this read was running: write nothing back (the opt-out cleared it).
        if (pipeline.isOptedOut) {
            logger.debug { "opted out during a subscription read; result discarded" }
            return
        }
        val marker = installMarker()
        val known = SubscriptionMapping.decodeKnown(prefs.getString(KEY_KNOWN, null))
        val seeded = prefs.contains(KEY_SEEDED_FOR) && prefs.getLong(KEY_SEEDED_FOR, 0L) == marker
        // First read of this install: existing purchases are history, not new events.
        val transactions = if (seeded) SubscriptionMapping.detectTransactions(purchases, known) else emptyList()
        val updated = SubscriptionMapping.remember(purchases, if (seeded) known else KnownPurchases())

        val (status, facts) = SubscriptionMapping.resolve(purchases, remembered())
        // Only a live subscription gets estimates; an inferred `expired` keeps them null.
        val inference = facts?.productId?.takeIf { status == SubscriptionStatus.ACTIVE }?.let { productId ->
            SubscriptionInference.infer(facts, productCache()[productId], clock())
        } ?: Inference.NONE
        val editor = prefs.edit()
            .putString(KEY_KNOWN, SubscriptionMapping.encode(updated))
            .putLong(KEY_SEEDED_FOR, marker)
            .putBoolean(KEY_LAST_TRACKED, status.isTracked)
        SubscriptionMapping.primary(purchases)?.let { editor.putString(KEY_LAST_SUBSCRIPTION, SubscriptionMapping.encode(it)) }
        editor.apply()

        publish(status, facts, trigger, transactions, inference)
    }

    private fun publish(
        status: SubscriptionStatus,
        facts: PurchaseFacts?,
        trigger: SubscriptionTrigger,
        transactions: List<DetectedTransaction>,
        inference: Inference,
    ) {
        // A re-read after the purchase screen that finds no new transaction reports a status change.
        val effective = if (trigger == SubscriptionTrigger.TRANSACTION_UPDATE && transactions.isEmpty()) {
            SubscriptionTrigger.STATUS_UPDATE
        } else {
            trigger
        }
        val properties = SubscriptionMapping.stateProperties(status, facts, effective, inference)
        val withoutTrigger = SubscriptionMapping.comparable(properties)
        if (effective == SubscriptionTrigger.STATUS_UPDATE && withoutTrigger == lastState) {
            logger.debug { "subscription re-read: no change" }
            setGate(status.isTracked)
            return
        }
        lastState = withoutTrigger
        // Open the gate before a subscriber's events and close it after a lapsed user's last one,
        // so a new purchase and the final `expired` state are both recorded.
        if (status.isTracked) setGate(true)
        for (transaction in transactions) {
            pipeline.track(EventName.TRANSACTION, SubscriptionMapping.transactionProperties(transaction))
        }
        pipeline.track(EventName.SUBSCRIPTION_STATE, properties)
        if (!status.isTracked) setGate(false)
        logger.info { "subscription ${status.wireValue} (${effective.wireValue}), ${transactions.size} new transaction(s)" }
    }

    /**
     * No state is emitted (nothing is known). A gate decided earlier in this process stays as it
     * is. Otherwise:
     * - a previous successful read on this install decided it: use that (stale, but right far more
     *   often than either default, and it stops buffering);
     * - never known (first run): keep buffering while a retry is planned, then close the gate.
     *   Closing is the least harmful default: storing events of someone who may not be a subscriber
     *   breaks the rule that only subscribers are tracked, while waiting forever holds up to 500 events in memory for nothing. A user
     *   who subscribes later is picked up by the next read (purchase flow closed or next foreground),
     *   and only the events of this process before that read are lost.
     */
    private fun onFailure(trigger: SubscriptionTrigger, failure: SubscriptionReadResult.Failure) {
        val willRetry = failure.retryable && retriesLeft > 0
        logger.info { "subscription read failed (${failure.reason})${if (willRetry) "; retrying" else ""}" }
        if (willRetry) {
            val delay = RETRY_DELAYS_MILLIS[(MAX_RETRIES - retriesLeft).coerceIn(0, RETRY_DELAYS_MILLIS.lastIndex)]
            retriesLeft -= 1
            retryScheduled = true
            scheduler.schedule(delay) {
                executor.execute {
                    retryScheduled = false
                    request(trigger, isRetry = true)
                }
            }
        }
        if (gateDecided) return
        if (prefs.contains(KEY_LAST_TRACKED)) {
            val tracked = prefs.getBoolean(KEY_LAST_TRACKED, false)
            logger.info { "subscription state unknown; using the last known (tracked: $tracked)" }
            setGate(tracked)
        } else if (!willRetry) {
            logger.info { "subscription state never known; not tracking until a read succeeds" }
            setGate(false)
        }
    }

    // endregion

    // region Product catalogue (for the inference)

    /**
     * Asks Play about the configured products ([configuredProductIds], for subscribers and
     * non-subscribers alike: a "before the purchase" snapshot) and the owned ones, when unknown,
     * just bought, or due for a refresh. Never delays or changes the state already published, emits
     * nothing, and doesn't touch the gate; a failure or timeout only records the attempt.
     */
    private fun queryProductDetails(purchases: List<PurchaseFacts>): Boolean {
        if (pipeline.isOptedOut) return false
        val now = clock()
        val owned = SubscriptionMapping.entitled(purchases)
        val purchaseTimes = HashMap<String, Long>()
        for (purchase in owned) {
            val id = purchase.productId ?: continue
            purchaseTimes[id] = maxOf(purchaseTimes[id] ?: Long.MIN_VALUE, purchase.purchaseTimeMillis)
        }
        val products = owned.mapNotNull { it.productId } + configuredProductIds
        val due = SubscriptionInference.productsToQuery(products, productCache(), now, purchaseTimes)
        if (due.isEmpty()) return false
        detailsInFlight = true
        val startedGeneration = generation.get()
        val delivered = AtomicBoolean(false)
        val deliver: (ProductDetailsResult) -> Unit = { result ->
            if (delivered.compareAndSet(false, true)) {
                executor.execute { finishDetails(due, result, startedGeneration, owned.mapNotNull { it.productId }.toSet()) }
            }
        }
        scheduler.schedule(DETAILS_TIMEOUT_MILLIS) { deliver(ProductDetailsResult.Failure("timed out")) }
        try {
            source.productDetails(due, deliver)
        } catch (t: Throwable) {
            deliver(ProductDetailsResult.Failure("exception: ${t.javaClass.simpleName}"))
        }
        return true
    }

    private fun finishDetails(requested: List<String>, result: ProductDetailsResult, startedGeneration: Int, owned: Set<String>) {
        detailsInFlight = false
        try {
            if (generation.get() != startedGeneration || pipeline.isOptedOut) {
                logger.debug { "product details discarded (state cleared meanwhile)" }
            } else {
                val now = clock()
                val cache = LinkedHashMap(productCache())
                val answered = result is ProductDetailsResult.Success
                val found = (result as? ProductDetailsResult.Success)?.offers.orEmpty()
                for (productId in requested) {
                    val offers = found[productId]
                    cache[productId] = when {
                        offers != null -> SubscriptionInference.learn(cache[productId], offers, now)
                        answered -> SubscriptionInference.notReturned(cache[productId], now)
                        else -> SubscriptionInference.attempted(cache[productId], now)
                    }
                }
                val pinned = configuredProductIds.toSet() + owned
                prefs.edit().putString(KEY_PRODUCT_DETAILS, SubscriptionInference.encode(SubscriptionInference.bound(cache, pinned))).apply()
                when (result) {
                    is ProductDetailsResult.Failure -> logger.info { "product details unavailable (${result.reason})" }
                    is ProductDetailsResult.Success -> {
                        val missing = requested.count { it !in found }
                        if (missing > 0) logger.debug { "product details: $missing of ${requested.size} product(s) not returned by Play" }
                    }
                }
            }
        } catch (t: Throwable) {
            logger.error({ "product details handling failed" }, t)
        }
        runPending()
    }

    private fun productCache(): Map<String, ProductKnowledge> =
        SubscriptionInference.decode(prefs.getString(KEY_PRODUCT_DETAILS, null))

    // endregion

    private fun setGate(tracked: Boolean) {
        gateDecided = true
        pipeline.setTrackingState(if (tracked) TrackingState.TRACKED else TrackingState.NOT_TRACKED)
    }

    private fun remembered(): PurchaseFacts? = SubscriptionMapping.decodePurchase(prefs.getString(KEY_LAST_SUBSCRIPTION, null))

    companion object {
        /** JSON [KnownPurchases]: hashed tokens with their last order ID, and the last entitled set. */
        const val KEY_KNOWN: String = "subscription.known_purchases"

        /** JSON [PurchaseFacts] of the last subscription seen, to report `expired` later. */
        const val KEY_LAST_SUBSCRIPTION: String = "subscription.last"

        /** Install marker of the install whose first read was done (history recorded, not emitted). */
        const val KEY_SEEDED_FOR: String = "subscription.seeded_for"

        /** Gate decision of the last successful read, used when a later read fails. */
        const val KEY_LAST_TRACKED: String = "subscription.last_tracked"

        /** JSON [ProductKnowledge] per product ID: base plan periods and free trials seen in Play's catalogue. */
        const val KEY_PRODUCT_DETAILS: String = "subscription.product_details"

        const val APP_OPEN_COALESCING_MILLIS: Long = 2_000
        const val READ_TIMEOUT_MILLIS: Long = 20_000
        const val DETAILS_TIMEOUT_MILLIS: Long = 15_000
        const val MAX_RETRIES: Int = 2
        val RETRY_DELAYS_MILLIS: LongArray = longArrayOf(2_000, 10_000)
    }
}
