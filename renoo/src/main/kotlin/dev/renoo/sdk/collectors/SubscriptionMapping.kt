package dev.renoo.sdk.collectors

import dev.renoo.sdk.core.Timestamp
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

// Plain values describing what Play Billing reported on this device, and the pure rules that turn
// them into `subscription_state` and `transaction` events. No Billing classes here, so everything
// is unit-tested on the JVM (the Android counterpart of iOS `SubscriptionMapping.swift`).

/** `Purchase.PurchaseState`. */
internal enum class PurchaseStateValue { PURCHASED, PENDING, UNSPECIFIED }

/**
 * One subscription purchase from `queryPurchasesAsync(SUBS)`, reduced to what the events need.
 * Play only lists subscriptions the user currently has (active, in grace period, cancelled but not
 * yet expired; suspended ones too when asked for). Expired, revoked and refunded ones are absent.
 */
internal data class PurchaseFacts(
    /** Stable for the life of the subscription, across renewals; changes on upgrade / resubscribe. */
    val purchaseToken: String,
    /** Google order ID (`GPA.…`). Renewals on the server get `..0`, `..1`, … suffixes. Null while pending. */
    val orderId: String?,
    /** Usually one product. Several only for multi-line purchases (see [productId]). */
    val productIds: List<String>,
    val purchaseTimeMillis: Long,
    val state: PurchaseStateValue,
    /** False once the user cancelled (strong churn signal); access continues until expiry. */
    val isAutoRenewing: Boolean,
    /** Account hold or paused (Play Billing 8+, `includeSuspendedSubscriptions`). No access. */
    val isSuspended: Boolean = false,
    /** `AccountIdentifiers.obfuscatedAccountId` as the client set it; null when none was set. */
    val accountToken: String? = null,
) {
    /**
     * The product reported in events. A multi-line purchase is reported as its first product, in
     * the order Play returns them (the base subscription comes first); the others are not sent.
     */
    val productId: String? get() = productIds.firstOrNull()
}

/**
 * `subscription_state.status` values Android can produce. `trial`, `grace_period`,
 * `billing_retry` and `revoked` are not knowable on the device.
 */
internal enum class SubscriptionStatus(val wireValue: String, val isTracked: Boolean) {
    ACTIVE("active", true),

    /** A suspended subscription (account hold or paused): no access, Play may still recover it. */
    SUSPENDED("suspended", true),
    EXPIRED("expired", false),
    NONE("none", false),
    ;

    /** Lower is better: picks the subscription to report when there are several. */
    internal val rank: Int get() = ordinal
}

internal enum class SubscriptionTrigger(val wireValue: String) {
    APP_OPEN("app_open"),
    /** The re-read after Play's purchase screen closed found a new transaction. */
    TRANSACTION_UPDATE("transaction_update"),

    /** The re-read after Play's purchase screen closed found only a state change (no new transaction). */
    STATUS_UPDATE("status_update"),
}

/** `transaction.kind` values Android can produce (no `revocation`: a refund only makes the purchase disappear). */
internal enum class TransactionKind(val wireValue: String) {
    PURCHASE("purchase"),
    RENEWAL("renewal"),
    UPGRADE("upgrade"),
}

internal data class DetectedTransaction(val purchase: PurchaseFacts, val kind: TransactionKind)

/** What this install has seen before, persisted between reads (see [SubscriptionTracker]). */
internal data class KnownPurchases(
    /** Hashed purchase token → last order ID seen for it (null if none). Oldest first. */
    val orders: LinkedHashMap<String, String?> = LinkedHashMap(),
    /** Hashed tokens that were entitled in the last successful read. */
    val lastEntitled: Set<String> = emptySet(),
)

internal object SubscriptionMapping {
    const val MAX_KNOWN_TOKENS: Int = 50

    // region Status

    /** The status of one purchase, or `null` when it gives no access (pending / unspecified). */
    fun status(purchase: PurchaseFacts): SubscriptionStatus? = when {
        purchase.state != PurchaseStateValue.PURCHASED -> null
        purchase.isSuspended -> SubscriptionStatus.SUSPENDED
        else -> SubscriptionStatus.ACTIVE
    }

    /** Entitled purchases (purchased, possibly suspended). Pending ones are not active. */
    fun entitled(purchases: List<PurchaseFacts>): List<PurchaseFacts> = purchases.filter { status(it) != null }

    /**
     * The purchase to report when the user has several subscriptions: the best status, then one
     * that still renews, then the most recent purchase (token as a final tie-break).
     */
    fun primary(purchases: List<PurchaseFacts>): PurchaseFacts? = entitled(purchases).minWithOrNull(
        compareBy<PurchaseFacts> { status(it)?.rank ?: Int.MAX_VALUE }
            .thenBy { if (it.isAutoRenewing) 0 else 1 }
            .thenByDescending { it.purchaseTimeMillis }
            .thenBy { it.purchaseToken },
    )

    /**
     * The state to report. When Play lists nothing but a subscription was seen before, the user is
     * reported as `expired` with the remembered details and `auto_renew` false: on the device an
     * expiry, a refund, a revocation, a pending purchase and account hold or pause (where Play
     * doesn't return suspended purchases) all look the same, so `expired` is always an inference.
     */
    fun resolve(current: List<PurchaseFacts>, remembered: PurchaseFacts?): Pair<SubscriptionStatus, PurchaseFacts?> {
        val primary = primary(current)
        if (primary != null) return (status(primary) ?: SubscriptionStatus.ACTIVE) to primary
        if (remembered == null) return SubscriptionStatus.NONE to null
        return SubscriptionStatus.EXPIRED to remembered.copy(isAutoRenewing = false, isSuspended = false)
    }

    /**
     * `subscription_state` properties. `is_trial` and `expires_at` are best-effort inferences on
     * Android ([SubscriptionInference]), listed in `inferred_fields` when not `null`;
     * `inference_basis` (always present, string or `null`) names the rule behind them. An
     * `expired` status is itself inferred, so `inferred_fields` lists `status` then. Fields the
     * device can't know are `null`: `original_transaction_id`, `offer_type`, `environment`.
     */
    fun stateProperties(
        status: SubscriptionStatus,
        purchase: PurchaseFacts?,
        trigger: SubscriptionTrigger,
        inference: Inference = Inference.NONE,
    ): Map<String, Any?> {
        val facts = if (status == SubscriptionStatus.NONE) null else purchase
        val inferred = if (facts == null) Inference.NONE else inference
        return linkedMapOf(
            "status" to status.wireValue,
            "product_id" to facts?.productId,
            "auto_renew" to facts?.isAutoRenewing,
            "expires_at" to inferred.expiresAtMillis?.let(Timestamp::format),
            "original_transaction_id" to null,
            "transaction_id" to facts?.orderId,
            "purchase_token" to facts?.purchaseToken,
            "account_token" to facts?.accountToken,
            "is_trial" to inferred.isTrial,
            "offer_type" to null,
            "environment" to null,
            "inferred_fields" to inferred.inferredFields + listOfNotNull(if (status == SubscriptionStatus.EXPIRED) "status" else null),
            "inference_basis" to inferred.basis?.wireValue,
            "trigger" to trigger.wireValue,
        )
    }

    /**
     * What "the state changed" compares: everything but `trigger`, the inferred values and
     * `inference_basis` (`status` stays, even when inferred). An
     * estimate moving on by itself (`expires_at` rolling to the next period, a trial window
     * passing) is not a change worth an extra `status_update` state; the next app open
     * reports it anyway.
     */
    fun comparable(properties: Map<String, Any?>): Map<String, Any?> =
        properties - "trigger" - "inferred_fields" - INFERABLE_FIELDS

    /**
     * Fields Android may infer, and the label of the rule behind them; excluded from change
     * detection whether or not inferred this time.
     */
    private val INFERABLE_FIELDS = setOf("expires_at", "is_trial", "inference_basis")

    // endregion

    // region Transactions

    /**
     * New transactions in [current], given what was [known] before this read:
     * - a token never seen → `purchase`, or `upgrade` when a token entitled in the last read is gone
     *   in this one (a replacement: upgrade, downgrade or crossgrade all get a new token);
     * - a known token with a different, non-null order ID → `renewal` (Play's `GPA.x..0` → `..1`).
     * Pending purchases are skipped until they complete. Oldest first.
     */
    fun detectTransactions(current: List<PurchaseFacts>, known: KnownPurchases): List<DetectedTransaction> {
        val entitled = entitled(current)
        val currentKeys = entitled.map { tokenKey(it.purchaseToken) }.toSet()
        val replaced = (known.lastEntitled - currentKeys).isNotEmpty()
        return entitled.sortedBy { it.purchaseTimeMillis }.mapNotNull { purchase ->
            val key = tokenKey(purchase.purchaseToken)
            when {
                !known.orders.containsKey(key) ->
                    DetectedTransaction(purchase, if (replaced) TransactionKind.UPGRADE else TransactionKind.PURCHASE)
                purchase.orderId != null && known.orders[key] != null && known.orders[key] != purchase.orderId ->
                    DetectedTransaction(purchase, TransactionKind.RENEWAL)
                else -> null
            }
        }
    }

    /**
     * [known] updated with this read: every entitled token with its current order ID (newest last),
     * bounded to [MAX_KNOWN_TOKENS] by dropping the oldest tokens that are no longer entitled.
     */
    fun remember(current: List<PurchaseFacts>, known: KnownPurchases): KnownPurchases {
        val orders = LinkedHashMap(known.orders)
        val entitledKeys = LinkedHashSet<String>()
        for (purchase in entitled(current).sortedBy { it.purchaseTimeMillis }) {
            val key = tokenKey(purchase.purchaseToken)
            entitledKeys.add(key)
            val orderId = purchase.orderId ?: orders[key]
            orders.remove(key)
            orders[key] = orderId
        }
        val iterator = orders.keys.iterator()
        var excess = orders.size - MAX_KNOWN_TOKENS
        while (excess > 0 && iterator.hasNext()) {
            if (iterator.next() !in entitledKeys) {
                iterator.remove()
                excess -= 1
            }
        }
        return KnownPurchases(orders, entitledKeys)
    }

    fun transactionProperties(transaction: DetectedTransaction): Map<String, Any?> {
        val purchase = transaction.purchase
        return linkedMapOf(
            "product_id" to purchase.productId,
            "transaction_id" to purchase.orderId,
            "original_transaction_id" to null,
            "purchase_token" to purchase.purchaseToken,
            "account_token" to purchase.accountToken,
            "purchase_date" to Timestamp.format(purchase.purchaseTimeMillis),
            "kind" to transaction.kind.wireValue,
            "offer_type" to null,
            "environment" to null,
        )
    }

    /**
     * Dedupe key for a purchase token in the SDK's prefs: the first 128 bits of its SHA-256, hex.
     * Tokens are long (~150 characters) and are only needed for comparison.
     */
    fun tokenKey(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.copyOf(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    // endregion

    // region Persistence encoding (org.json is part of Android)

    fun encode(purchase: PurchaseFacts): String = JSONObject()
        .put("token", purchase.purchaseToken)
        .put("order_id", purchase.orderId ?: JSONObject.NULL)
        .put("products", JSONArray(purchase.productIds))
        .put("time", purchase.purchaseTimeMillis)
        .put("auto_renew", purchase.isAutoRenewing)
        .put("suspended", purchase.isSuspended)
        .put("account_token", purchase.accountToken ?: JSONObject.NULL)
        .toString()

    fun decodePurchase(value: String?): PurchaseFacts? {
        if (value == null) return null
        return try {
            val json = JSONObject(value)
            val products = json.getJSONArray("products")
            PurchaseFacts(
                purchaseToken = json.getString("token"),
                orderId = if (json.isNull("order_id")) null else json.getString("order_id"),
                productIds = List(products.length()) { products.getString(it) },
                purchaseTimeMillis = json.getLong("time"),
                state = PurchaseStateValue.PURCHASED,
                isAutoRenewing = json.getBoolean("auto_renew"),
                isSuspended = json.optBoolean("suspended", false),
                accountToken = if (json.isNull("account_token")) null else json.getString("account_token"),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun encode(known: KnownPurchases): String {
        val orders = JSONArray()
        known.orders.forEach { (key, orderId) -> orders.put(JSONArray().put(key).put(orderId ?: "")) }
        return JSONObject()
            .put("orders", orders)
            .put("entitled", JSONArray(known.lastEntitled.toList()))
            .toString()
    }

    fun decodeKnown(value: String?): KnownPurchases {
        if (value == null) return KnownPurchases()
        return try {
            val json = JSONObject(value)
            val ordersJson = json.getJSONArray("orders")
            val orders = LinkedHashMap<String, String?>()
            for (i in 0 until ordersJson.length()) {
                val pair = ordersJson.getJSONArray(i)
                orders[pair.getString(0)] = pair.getString(1).ifEmpty { null }
            }
            val entitledJson = json.getJSONArray("entitled")
            KnownPurchases(orders, List(entitledJson.length()) { entitledJson.getString(it) }.toSet())
        } catch (e: Exception) {
            KnownPurchases()
        }
    }

    // endregion
}
