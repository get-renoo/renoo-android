package dev.renoo.sdk.collectors

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import dev.renoo.sdk.core.SdkLogger
import java.util.concurrent.atomic.AtomicBoolean

/** The outcome of one read of the user's subscriptions. */
internal sealed class SubscriptionReadResult {
    class Success(val purchases: List<PurchaseFacts>) : SubscriptionReadResult()

    /**
     * @param retryable a transient problem (service disconnected, timeout, network); worth a
     *   bounded retry. `BILLING_UNAVAILABLE` and similar are not.
     */
    class Failure(val reason: String, val retryable: Boolean) : SubscriptionReadResult()
}

/** The outcome of one product catalogue query. */
internal sealed class ProductDetailsResult {
    /** Offers per product ID. Products Play didn't return (not found, no eligible offer) are absent. */
    class Success(val offers: Map<String, List<OfferFacts>>) : ProductDetailsResult()

    class Failure(val reason: String) : ProductDetailsResult()
}

/** Where the subscription tracker gets its facts. The real one is Play Billing; tests use a fake. */
internal interface SubscriptionSource {
    /**
     * Reads the user's current subscriptions. Called on the SDK executor, one read at a time.
     * [callback] may run on any thread; extra calls after the first are ignored by the caller.
     */
    fun read(callback: (SubscriptionReadResult) -> Unit)

    /**
     * The subscription offers Play lists for [productIds] (`queryProductDetailsAsync`, SUBS): only
     * offers this user is currently eligible for. Called on the SDK executor right after a
     * successful [read], before [release]; same callback rules as [read].
     */
    fun productDetails(productIds: List<String>, callback: (ProductDetailsResult) -> Unit)

    /** No read is planned soon: drop the connection. Called on the SDK executor. */
    fun release()
}

/** Delayed work. The real one uses the main looper; tests run tasks by hand. */
internal fun interface Scheduler {
    fun schedule(delayMillis: Long, task: () -> Unit)
}

/** Runs tasks on the main thread; a task that throws is logged, never propagated into the host app's looper. */
internal class MainLooperScheduler(private val logger: SdkLogger) : Scheduler {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun schedule(delayMillis: Long, task: () -> Unit) {
        handler.postDelayed({
            try {
                task()
            } catch (t: Throwable) {
                logger.error({ "Scheduled SDK task failed" }, t)
            }
        }, delayMillis)
    }
}

/**
 * Play Billing, observe only: `queryPurchasesAsync(SUBS)` and, for the inferred `is_trial` /
 * `expires_at`, `queryProductDetailsAsync(SUBS)` (read the catalogue; no flow is launched). Never
 * launches a billing flow, acknowledges or consumes.
 *
 * The SDK's own `BillingClient` is created lazily on the first read (off the main thread),
 * connected for the read and ended by [release] once the tracker has nothing else to read, so
 * nothing stays bound to the Play Store between app opens. Its `PurchasesUpdatedListener` is a
 * no-op: Play only calls it for flows launched by the same client, so the app's own purchases never
 * arrive there (the collector re-reads when Play's purchase activity closes instead).
 */
internal class PlayBillingSubscriptionSource(
    private val context: Context,
    private val logger: SdkLogger,
) : SubscriptionSource {
    // Executor-only.
    private var client: BillingClient? = null
    @Volatile
    private var includeSuspended = true

    override fun read(callback: (SubscriptionReadResult) -> Unit) {
        val once = AtomicBoolean(false)
        val deliver: (SubscriptionReadResult) -> Unit = { result ->
            if (once.compareAndSet(false, true)) {
                try {
                    callback(result)
                } catch (t: Throwable) {
                    logger.error({ "subscription read callback failed" }, t)
                }
            }
        }
        try {
            val existing = client
            if (existing != null && existing.isReady) {
                query(existing, deliver)
                return
            }
            // Not connected (never, disconnected, or still connecting after a timed-out read): start fresh.
            existing?.let { safeEnd(it) }
            val fresh = BillingClient.newBuilder(context)
                .setListener(NoOpPurchasesListener)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .build()
            client = fresh
            // Billing calls these on its own thread: nothing may escape them.
            fresh.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) = guarded(deliver) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        query(fresh, deliver)
                    } else {
                        deliver(failure("setup", result))
                    }
                }

                override fun onBillingServiceDisconnected() = guarded(deliver) {
                    deliver(SubscriptionReadResult.Failure("billing service disconnected", retryable = true))
                }
            })
        } catch (t: Throwable) {
            logger.error({ "Play Billing read failed" }, t)
            deliver(SubscriptionReadResult.Failure("exception: ${t.javaClass.simpleName}", retryable = false))
        }
    }

    override fun productDetails(productIds: List<String>, callback: (ProductDetailsResult) -> Unit) {
        val once = AtomicBoolean(false)
        val deliver: (ProductDetailsResult) -> Unit = { result ->
            if (once.compareAndSet(false, true)) {
                try {
                    callback(result)
                } catch (t: Throwable) {
                    logger.error({ "product details callback failed" }, t)
                }
            }
        }
        try {
            val billing = client
            if (billing == null || !billing.isReady) {
                // Only asked right after a successful read; if the connection dropped since, skip this time.
                deliver(ProductDetailsResult.Failure("not connected"))
                return
            }
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    productIds.map {
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(it)
                            .setProductType(BillingClient.ProductType.SUBS)
                            .build()
                    },
                )
                .build()
            billing.queryProductDetailsAsync(params) { result, details ->
                try {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        val offers = details.productDetailsList.associate { it.productId to offers(it) }
                        details.unfetchedProductList.forEach { logger.debug { "product details: ${it.productId} not returned (status ${it.statusCode})" } }
                        deliver(ProductDetailsResult.Success(offers))
                    } else if (result.responseCode == BillingClient.BillingResponseCode.ITEM_UNAVAILABLE) {
                        // An answer, not a failure: none of these IDs exist for this app (daily throttle, not hourly).
                        deliver(ProductDetailsResult.Success(emptyMap()))
                    } else {
                        deliver(ProductDetailsResult.Failure("response code ${result.responseCode}"))
                    }
                } catch (t: Throwable) {
                    logger.error({ "product details callback failed" }, t)
                    deliver(ProductDetailsResult.Failure("exception: ${t.javaClass.simpleName}"))
                }
            }
        } catch (t: Throwable) {
            logger.error({ "queryProductDetailsAsync failed" }, t)
            deliver(ProductDetailsResult.Failure("exception: ${t.javaClass.simpleName}"))
        }
    }

    override fun release() {
        client?.let { safeEnd(it) }
        client = null
    }

    private fun offers(details: ProductDetails): List<OfferFacts> =
        details.subscriptionOfferDetails.orEmpty().mapNotNull { offer ->
            try {
                OfferFacts(
                    basePlanId = offer.basePlanId,
                    offerId = offer.offerId,
                    phases = offer.pricingPhases.pricingPhaseList.map { phase ->
                        PhaseFacts(
                            billingPeriod = phase.billingPeriod,
                            priceAmountMicros = phase.priceAmountMicros,
                            billingCycleCount = phase.billingCycleCount,
                            recurrenceMode = phase.recurrenceMode,
                        )
                    },
                )
            } catch (t: Throwable) {
                logger.error({ "Could not read a subscription offer" }, t)
                null
            }
        }

    /** Runs on whatever thread Billing calls back on; touches no state except [includeSuspended]. */
    private fun query(billing: BillingClient, deliver: (SubscriptionReadResult) -> Unit, withSuspended: Boolean = includeSuspended) {
        try {
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .apply { if (withSuspended) includeSuspendedSubscriptions(true) }
                .build()
            billing.queryPurchasesAsync(params) { result, purchases ->
                guarded(deliver) { onQueryResult(billing, deliver, withSuspended, result, purchases) }
            }
        } catch (t: Throwable) {
            logger.error({ "queryPurchasesAsync failed" }, t)
            deliver(SubscriptionReadResult.Failure("exception: ${t.javaClass.simpleName}", retryable = false))
        }
    }

    /** Billing's thread. */
    private fun onQueryResult(
        billing: BillingClient,
        deliver: (SubscriptionReadResult) -> Unit,
        withSuspended: Boolean,
        result: BillingResult,
        purchases: List<Purchase>,
    ) {
        val code = result.responseCode
        when {
            code == BillingClient.BillingResponseCode.OK ->
                deliver(SubscriptionReadResult.Success(purchases.mapNotNull(::facts)))
            withSuspended && (code == BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED ||
                code == BillingClient.BillingResponseCode.DEVELOPER_ERROR) -> {
                // An older Play Store that doesn't know suspended subscriptions: ask without them.
                logger.info { "suspended subscriptions not supported ($code); querying without them" }
                includeSuspended = false
                query(billing, deliver, withSuspended = false)
            }
            else -> deliver(failure("query", result))
        }
    }

    /** Wraps a Billing callback: logs anything thrown and reports the read as failed (once; later deliveries are ignored). */
    private inline fun guarded(noinline deliver: (SubscriptionReadResult) -> Unit, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logger.error({ "Play Billing callback failed" }, t)
            try {
                deliver(SubscriptionReadResult.Failure("exception: ${t.javaClass.simpleName}", retryable = false))
            } catch (ignored: Throwable) {
                // deliver already guards the tracker's callback.
            }
        }
    }

    private fun safeEnd(billing: BillingClient) {
        try {
            billing.endConnection()
        } catch (t: Throwable) {
            logger.debug { "endConnection failed: $t" }
        }
    }

    private fun failure(step: String, result: BillingResult): SubscriptionReadResult.Failure {
        val code = result.responseCode
        return SubscriptionReadResult.Failure(
            reason = "$step: response code $code (${result.debugMessage})",
            retryable = code !in NOT_RETRYABLE,
        )
    }

    private fun facts(purchase: Purchase): PurchaseFacts? = try {
        val token: String? = purchase.purchaseToken
        if (token.isNullOrEmpty()) {
            null
        } else {
            val orderId: String? = purchase.orderId
            PurchaseFacts(
                purchaseToken = token,
                orderId = orderId?.takeIf { it.isNotBlank() },
                productIds = purchase.products.toList(),
                purchaseTimeMillis = purchase.purchaseTime,
                state = when (purchase.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> PurchaseStateValue.PURCHASED
                    Purchase.PurchaseState.PENDING -> PurchaseStateValue.PENDING
                    else -> PurchaseStateValue.UNSPECIFIED
                },
                isAutoRenewing = purchase.isAutoRenewing,
                isSuspended = purchase.isSuspended,
                accountToken = purchase.accountIdentifiers?.obfuscatedAccountId?.takeIf { it.isNotEmpty() },
            )
        }
    } catch (t: Throwable) {
        logger.error({ "Could not read a purchase" }, t)
        null
    }

    private object NoOpPurchasesListener : PurchasesUpdatedListener {
        override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) = Unit
    }

    companion object {
        /** Retrying these within seconds won't help: Play Billing is missing, too old or misconfigured. */
        private val NOT_RETRYABLE = setOf(
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
            BillingClient.BillingResponseCode.DEVELOPER_ERROR,
        )
    }
}
