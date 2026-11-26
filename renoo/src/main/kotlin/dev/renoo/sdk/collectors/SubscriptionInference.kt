package dev.renoo.sdk.collectors

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

// Best-effort `is_trial` and `expires_at` for Android, inferred on the device from Play's product
// catalogue (`queryProductDetailsAsync`) and the purchase time. Play's purchase data says nothing
// about either: `Purchase` carries no base plan, offer, price or expiry, and the SDK holds no Play
// service account to ask Google's server. Everything here is pure and unit-tested on the JVM.
//
// Play lists only the offers the user is currently eligible for. The SDK keeps dated snapshots of
// what was listed per product, so it can compare the catalogue before a purchase with the catalogue
// after it (a free trial listed before and gone after was very likely taken). With
// `Configuration.subscriptionProductIds` the "before" snapshot exists; without it the SDK first
// sees a product after it was bought and stays cautious. Rules: [SubscriptionInference.infer].
//
// Known error sources:
// - A trial that disappears after the purchase may also disappear because the user bought the base
//   plan directly while eligible (eligibility is usually "never had this subscription"). Such a user
//   is reported in a trial.
// - Play may keep listing a used trial for a while (eligibility caches) or for good
//   (developer-determined offers): then the SDK stays cautious until a later snapshot.
// - `purchaseTime` is assumed to be the start of the subscription (or of the trial). If Play moves it
//   to the latest renewal, period arithmetic still lands on the right renewal date, but the trial
//   offset is then wrongly added once more.
// - Grace period, account hold, pause, a deferred plan change or a changed renewal date (e.g. after a
//   recovery from hold) shift the real schedule; the estimate does not follow. A price change does
//   not matter. Introductory (paid) phases with a different period than the base plan are ignored.
// - Device clock vs Google's `purchaseTime`: a snapshot counts as "before" only if taken at least
//   [SubscriptionInference.BEFORE_MARGIN_MILLIS] before the purchase.
// - Calendar arithmetic is done in UTC, anchored on the start date (Jan 31 + P1M = Feb 28/29, + P2M =
//   Mar 31), which is how Play describes monthly renewals; Play's own day boundary and time zone are
//   not documented, so a renewal can be off by up to a day.

/** An ISO 8601 duration as Play writes billing periods (`P1W`, `P1M`, `P3M`, `P1Y`, `P3D`). */
internal data class BillingPeriod(val years: Int, val months: Int, val weeks: Int, val days: Int) {
    val iso: String
        get() = buildString {
            append('P')
            if (years > 0) append(years).append('Y')
            if (months > 0) append(months).append('M')
            if (weeks > 0) append(weeks).append('W')
            if (days > 0) append(days).append('D')
        }

    /** Rough length, only to jump close to the right number of periods. */
    internal val approximateMillis: Double
        get() = (years * 365.2425 + months * 30.436875 + weeks * 7.0 + days) * DAY_MILLIS

    companion object {
        private const val DAY_MILLIS = 86_400_000.0
        private val PATTERN = Regex("^P(?:(\\d{1,4})Y)?(?:(\\d{1,4})M)?(?:(\\d{1,4})W)?(?:(\\d{1,4})D)?$")

        /** `null` for anything that is not a positive date-based duration (times like `PT1H` are not billing periods). */
        fun parse(value: String?): BillingPeriod? {
            val match = PATTERN.matchEntire(value?.trim()?.uppercase() ?: return null) ?: return null
            val (y, m, w, d) = match.destructured
            val period = BillingPeriod(y.toIntOrNull() ?: 0, m.toIntOrNull() ?: 0, w.toIntOrNull() ?: 0, d.toIntOrNull() ?: 0)
            return if (period.approximateMillis > 0) period else null
        }
    }
}

internal object PeriodMath {
    private val UTC = TimeZone.getTimeZone("UTC")

    /**
     * [start] plus [times] × [period], in UTC calendar arithmetic, always from [start] (never
     * chained), so month ends don't drift: Jan 31 + 1 × P1M = Feb 28 (or 29), + 2 × P1M = Mar 31.
     * (`java.time` needs API 26 or core-library desugaring; minSdk is 24.)
     */
    fun add(start: Long, period: BillingPeriod, times: Int): Long {
        val calendar = Calendar.getInstance(UTC)
        calendar.timeInMillis = start
        if (period.years != 0) calendar.add(Calendar.YEAR, period.years * times)
        if (period.months != 0) calendar.add(Calendar.MONTH, period.months * times)
        val days = period.weeks * 7 + period.days
        if (days != 0) calendar.add(Calendar.DAY_OF_MONTH, days * times)
        return calendar.timeInMillis
    }

    /** The first instant `start + k × period` (k ≥ 0) strictly after [now]. */
    fun nextBoundaryAfter(start: Long, period: BillingPeriod, now: Long): Long? {
        if (start > now) return start
        val estimate = ((now - start) / period.approximateMillis).toLong()
        var k = (estimate - 2).coerceAtLeast(0L).coerceAtMost(MAX_PERIODS.toLong()).toInt()
        while (k <= MAX_PERIODS) {
            val boundary = add(start, period, k)
            if (boundary > now) return boundary
            k += 1
        }
        return null
    }

    /** About 100 years of weekly renewals; anything beyond is nonsense input. */
    private const val MAX_PERIODS = 5_300
}

/** One subscription offer from `ProductDetails.getSubscriptionOfferDetails()`, as plain values. */
internal data class OfferFacts(
    val basePlanId: String,
    /** `null` for the base plan itself. */
    val offerId: String?,
    val phases: List<PhaseFacts>,
)

/** `ProductDetails.PricingPhase`. */
internal data class PhaseFacts(
    val billingPeriod: String,
    val priceAmountMicros: Long,
    val billingCycleCount: Int,
    /** `ProductDetails.RecurrenceMode`: 1 infinite, 2 finite, 3 non-recurring. */
    val recurrenceMode: Int,
) {
    companion object {
        const val INFINITE_RECURRING = 1
        const val FINITE_RECURRING = 2
        const val NON_RECURRING = 3
    }
}

/** A free-trial phase: its period and how many times it repeats. */
internal data class TrialFacts(val period: String, val cycles: Int)

/**
 * What Play's catalogue listed for one product, unchanged from [firstAtMillis] to [lastAtMillis]
 * (device clock): the base plans and the offers with a free trial (`basePlanId/offerId`).
 */
internal data class CatalogueSnapshot(
    val firstAtMillis: Long,
    val lastAtMillis: Long,
    val basePlans: Set<String>,
    val trials: Set<String>,
)

/**
 * What the SDK has learned about one subscription product, merged across catalogue queries so a
 * trial offer that stopped being listed (the user is no longer eligible) is not forgotten.
 */
internal data class ProductKnowledge(
    /** Base plan ID → its recurring billing period, or `""` when it doesn't renew (prepaid / unknown). */
    val basePlans: Map<String, String> = emptyMap(),
    /** `basePlanId/offerId` → the offer's free-trial phase, for every trial ever listed. */
    val trials: Map<String, TrialFacts> = emptyMap(),
    /** Distinct catalogue states over time, oldest first (bounded). */
    val snapshots: List<CatalogueSnapshot> = emptyList(),
    /** Last query that returned this product; `null` if never. */
    val lastFetchedAtMillis: Long? = null,
    /** Last query that answered (returned the product or said it doesn't exist / has no eligible offer). */
    val lastAnsweredAtMillis: Long? = null,
    /** Last query attempt, successful or not (rate limiting). */
    val lastAttemptAtMillis: Long? = null,
)

/**
 * `subscription_state.inference_basis`: which row of the inference table ([SubscriptionInference.infer])
 * produced `is_trial` / `expires_at`. A label only; it never changes the values. The wire values
 * are part of the event contract and can't be renamed once shipped.
 */
internal enum class InferenceBasis(val wireValue: String) {
    /** B lists no free trial. */
    NO_TRIAL_OFFERED("no_trial_offered"),

    /** Trials in B are gone from A, with a single trial end. */
    TRIAL_CONSUMED("trial_consumed"),

    /** A trial is listed but B or A is missing, or it is still listed in A. */
    TRIAL_UNCERTAIN("trial_uncertain"),

    /** No B, and no trial listed after the purchase (typical without `subscriptionProductIds`). */
    NO_SNAPSHOT_BEFORE("no_snapshot_before"),

    /** Several consumed trial lengths. */
    MULTIPLE_TRIALS("multiple_trials"),
}

/**
 * `is_trial` and `expires_at` (epoch millis), each `null` when it can't be inferred, and the table
 * row they come from ([basis]; `null` when nothing was inferred at all, see [NONE]).
 */
internal data class Inference(val isTrial: Boolean?, val expiresAtMillis: Long?, val basis: InferenceBasis? = null) {
    /** Names of the fields that carry an inferred (non-null) value, for `inferred_fields`. */
    val inferredFields: List<String>
        get() = listOfNotNull(if (expiresAtMillis != null) "expires_at" else null, if (isTrial != null) "is_trial" else null)

    companion object {
        val NONE = Inference(null, null)
    }
}

internal object SubscriptionInference {
    /** Cached products beyond the pinned ones (configured or currently owned). */
    const val MAX_UNPINNED_PRODUCTS: Int = 10
    const val MAX_PRODUCTS_PER_QUERY: Int = 30
    const val MAX_ENTRIES_PER_PRODUCT: Int = 10
    const val MAX_SNAPSHOTS: Int = 8

    /** Answered products are refreshed at most once a day; a failed query is retried after an hour. */
    const val REFRESH_AFTER_SUCCESS_MILLIS: Long = 24 * 3_600_000L
    const val RETRY_AFTER_FAILURE_MILLIS: Long = 3_600_000L

    /** A snapshot is "before" a purchase only if taken at least this long before `purchaseTime` (clock skew). */
    const val BEFORE_MARGIN_MILLIS: Long = 30_000L

    // region Learning

    /**
     * Merges one catalogue answer into [previous]. Base plans and trials seen now replace earlier
     * entries with the same ID; entries not listed any more are kept in the dictionaries (a used
     * trial is no longer offered, but its length is exactly what we need). The listing itself is
     * recorded as a snapshot: extended if unchanged, appended otherwise.
     */
    fun learn(previous: ProductKnowledge?, offers: List<OfferFacts>, now: Long): ProductKnowledge {
        val basePlans = LinkedHashMap(previous?.basePlans ?: emptyMap())
        val trials = LinkedHashMap(previous?.trials ?: emptyMap())
        val listedPlans = LinkedHashSet<String>()
        val listedTrials = LinkedHashSet<String>()
        for (offer in offers) {
            if (offer.basePlanId.isEmpty()) continue
            listedPlans.add(offer.basePlanId)
            val recurring = offer.phases.lastOrNull { it.recurrenceMode == PhaseFacts.INFINITE_RECURRING }
            val period = BillingPeriod.parse(recurring?.billingPeriod)?.iso ?: ""
            // The base plan's own entry (no offer ID) is authoritative. A developer offer ends in the
            // same recurring phase, so it fills in a base plan that isn't listed (or not yet known).
            val authoritative = offer.offerId == null
            if (authoritative || (period.isNotEmpty() && basePlans[offer.basePlanId].isNullOrEmpty())) {
                basePlans.remove(offer.basePlanId)
                basePlans[offer.basePlanId] = period
            }
            val first = offer.phases.firstOrNull()
            val trialPeriod = BillingPeriod.parse(first?.billingPeriod)
            if (offer.offerId != null && first != null && first.priceAmountMicros == 0L && trialPeriod != null) {
                val key = "${offer.basePlanId}/${offer.offerId}"
                trials.remove(key)
                trials[key] = TrialFacts(trialPeriod.iso, first.billingCycleCount.coerceAtLeast(1))
                listedTrials.add(key)
            }
        }
        val snapshots = (previous?.snapshots ?: emptyList()).toMutableList()
        val last = snapshots.lastOrNull()
        if (last != null && last.basePlans == listedPlans && last.trials == listedTrials && now >= last.lastAtMillis) {
            snapshots[snapshots.lastIndex] = last.copy(lastAtMillis = now)
        } else {
            snapshots.add(CatalogueSnapshot(now, now, listedPlans, listedTrials))
        }
        return ProductKnowledge(
            basePlans = basePlans.takeLastEntries(MAX_ENTRIES_PER_PRODUCT),
            trials = trials.takeLastEntries(MAX_ENTRIES_PER_PRODUCT),
            snapshots = snapshots.takeLast(MAX_SNAPSHOTS),
            lastFetchedAtMillis = now,
            lastAnsweredAtMillis = now,
            lastAttemptAtMillis = now,
        )
    }

    /**
     * Play answered but didn't return the product (unknown ID, or no offer the user is eligible
     * for). Not a snapshot: an empty answer can't be told apart from a misconfigured ID. Throttled
     * like a success, so a wrong configured ID is asked about once a day, not every launch.
     */
    fun notReturned(previous: ProductKnowledge?, now: Long): ProductKnowledge =
        (previous ?: ProductKnowledge()).copy(lastAnsweredAtMillis = now, lastAttemptAtMillis = now)

    /** Records a failed query (transport error, timeout). */
    fun attempted(previous: ProductKnowledge?, now: Long): ProductKnowledge =
        (previous ?: ProductKnowledge()).copy(lastAttemptAtMillis = now)

    /**
     * Products worth asking Play about now, in order: never asked; owned with a purchase newer than
     * the last attempt (one read right after a purchase, to see what changed, whatever the
     * throttle); due for the daily refresh; failed more than an hour ago.
     *
     * @param purchaseTimes latest purchase time per owned product.
     */
    fun productsToQuery(
        productIds: Collection<String>,
        cache: Map<String, ProductKnowledge>,
        now: Long,
        purchaseTimes: Map<String, Long> = emptyMap(),
    ): List<String> = productIds.distinct().filter { id ->
        val known = cache[id] ?: return@filter true
        val attempt = known.lastAttemptAtMillis ?: return@filter true
        val purchased = purchaseTimes[id]
        // Not when the purchase is in the device's future (clock behind Google's): that would ask on every read.
        if (purchased != null && purchased >= attempt && purchased <= now) return@filter true
        val answered = known.lastAnsweredAtMillis
        val wait = if (answered != null && answered >= attempt) REFRESH_AFTER_SUCCESS_MILLIS else RETRY_AFTER_FAILURE_MILLIS
        now < attempt || now - attempt >= wait
    }.take(MAX_PRODUCTS_PER_QUERY)

    // endregion

    // region Inference

    /** How the trial question was settled for one purchase. */
    internal enum class TrialBasis {
        /** Catalogue seen before the purchase listed no free trial: none was offered. */
        NO_TRIAL_OFFERED,

        /** Free trials listed before the purchase and gone after it: one was very likely taken. */
        TRIAL_CONSUMED,

        /** A trial was listed, but nothing shows whether it was taken (still listed, or no "after" yet, or no "before"). */
        AMBIGUOUS,

        /** No catalogue before the purchase and no trial listed after it: no trial, or a used one. */
        UNKNOWN,
    }

    /**
     * `is_trial` and `expires_at` for an active (entitled, not suspended) purchase. With
     * B = the latest snapshot first seen at least [BEFORE_MARGIN_MILLIS] before `purchaseTime` and
     * A = the latest snapshot, if seen after `purchaseTime`:
     *
     * | Situation | `is_trial` | period starts at | [InferenceBasis] |
     * | --- | --- | --- | --- |
     * | nothing learned about the product, or not `active` | `null` | (no `expires_at`) | `null` |
     * | B lists no trial | `false` | purchase | `no_trial_offered` |
     * | trials in B are missing from A (consumed) | `true` before purchase + L, then `false` | purchase + L | `trial_consumed` |
     * | trial listed but B or A missing, or still listed in A | `false` after the longest L, else `null` | unknown: no `expires_at` | `trial_uncertain` |
     * | no B, A lists no trial | `null` | purchase (assumes no trial; a hint) | `no_snapshot_before` |
     *
     * Several consumed trial lengths (`multiple_trials`): `true` before the shortest, `false` after
     * the longest, `null` in between, and no `expires_at` until the start is clear.
     *
     * The basis only labels the branch taken ([basisOf]); see there for the two places where the
     * branches are wider than the rows.
     *
     * **Billing period:** the base plans listed (in B, else A) if they all have the same recurring
     * period; otherwise the base plan(s) of the consumed trials; otherwise the base plans listed in B
     * but gone from A (Play may hide the plan just bought); if still ambiguous, `null`.
     *
     * **`expires_at`:** during a trial, the trial end; otherwise the first `start + k × period`
     * (k ≥ 1) after now.
     */
    fun infer(purchase: PurchaseFacts, knowledge: ProductKnowledge?, now: Long): Inference {
        if (knowledge?.lastFetchedAtMillis == null) return Inference.NONE
        if (SubscriptionMapping.status(purchase) != SubscriptionStatus.ACTIVE) return Inference.NONE
        val purchaseTime = purchase.purchaseTimeMillis

        val before = knowledge.snapshots.lastOrNull { it.firstAtMillis <= purchaseTime - BEFORE_MARGIN_MILLIS }
        val after = knowledge.snapshots.lastOrNull()?.takeIf { it.lastAtMillis > purchaseTime }
        val (basis, trialKeys) = trialBasis(before, after, knowledge)

        fun trialEnds(keys: Set<String>): List<Long> = keys.mapNotNull { key ->
            val trial = knowledge.trials[key] ?: return@mapNotNull null
            BillingPeriod.parse(trial.period)?.let { PeriodMath.add(purchaseTime, it, trial.cycles) }
        }.distinct()

        val ends = trialEnds(trialKeys)
        val (isTrial, start) = when (basis) {
            TrialBasis.NO_TRIAL_OFFERED -> false to purchaseTime
            TrialBasis.UNKNOWN -> null to purchaseTime
            TrialBasis.TRIAL_CONSUMED -> when {
                ends.isEmpty() -> null to null
                now < ends.min() -> true to (if (ends.size == 1) ends.single() else null)
                now >= ends.max() -> false to (if (ends.size == 1) ends.single() else null)
                else -> null to null
            }
            TrialBasis.AMBIGUOUS -> (if (ends.isNotEmpty() && now >= ends.max()) false else null) to null
        }

        val period = billingPeriod(before, after, knowledge, if (basis == TrialBasis.TRIAL_CONSUMED) trialKeys else emptySet())
        val expiresAt = when {
            start == null -> null
            isTrial == true -> start // the trial end
            period == null -> null
            else -> PeriodMath.nextBoundaryAfter(start, period, now)
        }
        return Inference(isTrial, expiresAt, basisOf(basis, ends.size))
    }

    /**
     * The `inference_basis` label for a [TrialBasis] branch. One-to-one except:
     * - consumed trials are split by the number of distinct trial ends: one is `trial_consumed`,
     *   several are `multiple_trials`, and none (the consumed offers' lengths are no longer in the
     *   cache, so neither field can be inferred) is labelled `trial_uncertain`, the row whose
     *   values (`null`, `null`) it has;
     * - [TrialBasis.UNKNOWN] is `no_snapshot_before` also when there is no "after" snapshot either
     *   (the only snapshot was taken less than [BEFORE_MARGIN_MILLIS] before the purchase, or an
     *   older cache has no snapshots) and no trial is known.
     */
    internal fun basisOf(basis: TrialBasis, distinctTrialEnds: Int): InferenceBasis = when (basis) {
        TrialBasis.NO_TRIAL_OFFERED -> InferenceBasis.NO_TRIAL_OFFERED
        TrialBasis.TRIAL_CONSUMED -> when {
            distinctTrialEnds == 1 -> InferenceBasis.TRIAL_CONSUMED
            distinctTrialEnds > 1 -> InferenceBasis.MULTIPLE_TRIALS
            else -> InferenceBasis.TRIAL_UNCERTAIN
        }
        TrialBasis.AMBIGUOUS -> InferenceBasis.TRIAL_UNCERTAIN
        TrialBasis.UNKNOWN -> InferenceBasis.NO_SNAPSHOT_BEFORE
    }

    internal fun trialBasis(
        before: CatalogueSnapshot?,
        after: CatalogueSnapshot?,
        knowledge: ProductKnowledge,
    ): Pair<TrialBasis, Set<String>> = when {
        before != null && before.trials.isEmpty() -> TrialBasis.NO_TRIAL_OFFERED to emptySet()
        before != null && after != null && (before.trials - after.trials).isNotEmpty() ->
            TrialBasis.TRIAL_CONSUMED to (before.trials - after.trials)
        before != null -> TrialBasis.AMBIGUOUS to before.trials
        // No "before" (first seen after the purchase, or a cache from an older SDK without snapshots).
        else -> {
            val listed = after?.trials ?: knowledge.snapshots.lastOrNull()?.trials
                ?: if (knowledge.snapshots.isEmpty()) knowledge.trials.keys else emptySet()
            if (listed.isNotEmpty()) TrialBasis.AMBIGUOUS to listed else TrialBasis.UNKNOWN to emptySet()
        }
    }

    private fun billingPeriod(
        before: CatalogueSnapshot?,
        after: CatalogueSnapshot?,
        knowledge: ProductKnowledge,
        consumedTrials: Set<String>,
    ): BillingPeriod? {
        fun agreed(plans: Collection<String>): BillingPeriod? {
            val periods = plans.mapNotNull { knowledge.basePlans[it] }.toSet()
            return if (periods.size == 1 && "" !in periods) BillingPeriod.parse(periods.single()) else null
        }
        val listed = (before ?: after ?: knowledge.snapshots.lastOrNull())?.basePlans?.takeIf { it.isNotEmpty() }
            ?: knowledge.basePlans.keys
        agreed(listed)?.let { return it }
        if (consumedTrials.isNotEmpty()) agreed(consumedTrials.map { it.substringBefore('/') })?.let { return it }
        if (before != null && after != null) {
            val vanished = before.basePlans - after.basePlans
            if (vanished.isNotEmpty()) agreed(vanished)?.let { return it }
        }
        return null
    }

    // endregion

    // region Persistence (SDK prefs; org.json is part of Android)

    fun encode(cache: Map<String, ProductKnowledge>): String {
        val root = JSONObject()
        for ((productId, knowledge) in cache) {
            val plans = JSONObject()
            knowledge.basePlans.forEach { (id, period) -> plans.put(id, period) }
            val trials = JSONObject()
            knowledge.trials.forEach { (key, trial) -> trials.put(key, JSONObject().put("period", trial.period).put("cycles", trial.cycles)) }
            val snapshots = JSONArray()
            knowledge.snapshots.forEach {
                snapshots.put(
                    JSONObject()
                        .put("from", it.firstAtMillis)
                        .put("to", it.lastAtMillis)
                        .put("plans", JSONArray(it.basePlans.toList()))
                        .put("trials", JSONArray(it.trials.toList())),
                )
            }
            root.put(
                productId,
                JSONObject()
                    .put("plans", plans)
                    .put("trials", trials)
                    .put("snapshots", snapshots)
                    .put("last", knowledge.lastFetchedAtMillis ?: JSONObject.NULL)
                    .put("answered", knowledge.lastAnsweredAtMillis ?: JSONObject.NULL)
                    .put("attempt", knowledge.lastAttemptAtMillis ?: JSONObject.NULL),
            )
        }
        return root.toString()
    }

    /** Never throws; garbage gives an empty cache, a bad product entry is skipped. */
    fun decode(value: String?): Map<String, ProductKnowledge> {
        if (value == null) return emptyMap()
        val root = try {
            JSONObject(value)
        } catch (e: Exception) {
            return emptyMap()
        }
        val result = LinkedHashMap<String, ProductKnowledge>()
        for (productId in root.keys()) {
            try {
                val json = root.getJSONObject(productId)
                val plansJson = json.getJSONObject("plans")
                val trialsJson = json.getJSONObject("trials")
                val snapshotsJson = json.optJSONArray("snapshots") ?: JSONArray()
                result[productId] = ProductKnowledge(
                    basePlans = plansJson.keys().asSequence().associateWith { plansJson.getString(it) },
                    trials = trialsJson.keys().asSequence().associateWith {
                        val trial = trialsJson.getJSONObject(it)
                        TrialFacts(trial.getString("period"), trial.getInt("cycles"))
                    },
                    snapshots = List(snapshotsJson.length()) { i ->
                        val snapshot = snapshotsJson.getJSONObject(i)
                        CatalogueSnapshot(
                            firstAtMillis = snapshot.getLong("from"),
                            lastAtMillis = snapshot.getLong("to"),
                            basePlans = snapshot.getJSONArray("plans").strings(),
                            trials = snapshot.getJSONArray("trials").strings(),
                        )
                    },
                    lastFetchedAtMillis = json.optLongOrNull("last"),
                    lastAnsweredAtMillis = json.optLongOrNull("answered") ?: json.optLongOrNull("last"),
                    lastAttemptAtMillis = json.optLongOrNull("attempt"),
                )
            } catch (e: Exception) {
                // Skip this product; it will be asked about again.
            }
        }
        return result
    }

    /**
     * Keeps every [pinned] product (configured IDs, at most 20, and the ones the user owns) and the
     * [MAX_UNPINNED_PRODUCTS] most recently asked-about others.
     */
    fun bound(cache: Map<String, ProductKnowledge>, pinned: Set<String> = emptySet()): Map<String, ProductKnowledge> {
        val (kept, others) = cache.entries.partition { it.key in pinned }
        val recent = others.sortedByDescending { it.value.lastAttemptAtMillis ?: 0L }.take(MAX_UNPINNED_PRODUCTS)
        return (kept + recent).associate { it.key to it.value }
    }

    private fun JSONArray.strings(): Set<String> = (0 until length()).map { getString(it) }.toSet()

    private fun JSONObject.optLongOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)

    private fun <K, V> LinkedHashMap<K, V>.takeLastEntries(max: Int): Map<K, V> =
        if (size <= max) this else entries.toList().takeLast(max).associate { it.key to it.value }

    // endregion
}
