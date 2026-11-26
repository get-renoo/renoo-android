package dev.renoo.sdk.collectors

import dev.renoo.sdk.collectors.Fixture.purchase
import dev.renoo.sdk.core.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionInferenceTest {
    private fun t(iso: String): Long = Timestamp.parse(iso) ?: error("bad timestamp $iso")
    private fun iso(millis: Long?): String? = millis?.let(Timestamp::format)
    private fun period(value: String) = BillingPeriod.parse(value) ?: error("bad period $value")

    private fun basePlan(id: String, period: String) =
        OfferFacts(id, null, listOf(PhaseFacts(period, 9_990_000, 0, PhaseFacts.INFINITE_RECURRING)))

    private fun trialOffer(basePlan: String, offer: String, trial: String, period: String, cycles: Int = 1) = OfferFacts(
        basePlan,
        offer,
        listOf(
            PhaseFacts(trial, 0, cycles, PhaseFacts.FINITE_RECURRING),
            PhaseFacts(period, 9_990_000, 0, PhaseFacts.INFINITE_RECURRING),
        ),
    )

    private val start = "2026-01-31T10:00:00.000Z"

    private fun infer(knowledge: ProductKnowledge?, now: String, purchased: String = start) =
        SubscriptionInference.infer(purchase(time = t(purchased)), knowledge, t(now))

    // region Periods

    @Test
    fun parsesPlayBillingPeriods() {
        assertEquals(BillingPeriod(0, 1, 0, 0), BillingPeriod.parse("P1M"))
        assertEquals(BillingPeriod(1, 0, 0, 0), BillingPeriod.parse("P1Y"))
        assertEquals(BillingPeriod(0, 0, 1, 0), BillingPeriod.parse("P1W"))
        assertEquals(BillingPeriod(0, 0, 0, 3), BillingPeriod.parse("P3D"))
        assertEquals("P6M", BillingPeriod.parse("p6m")?.iso)
        for (bad in listOf(null, "", "P", "P0M", "PT1H", "1M", "P1.5M", "garbage")) assertNull(bad, BillingPeriod.parse(bad))
    }

    @Test
    fun monthEndsAreAnchoredOnTheStartDate() {
        val jan31 = t(start)
        assertEquals("2026-02-28T10:00:00.000Z", iso(PeriodMath.add(jan31, period("P1M"), 1)))
        assertEquals("2026-03-31T10:00:00.000Z", iso(PeriodMath.add(jan31, period("P1M"), 2)))
        assertEquals("leap year", "2028-02-29T10:00:00.000Z", iso(PeriodMath.add(t("2028-01-31T10:00:00.000Z"), period("P1M"), 1)))
        assertEquals("2027-02-28T00:00:00.000Z", iso(PeriodMath.add(t("2024-02-29T00:00:00.000Z"), period("P1Y"), 3)))
        assertEquals("2026-02-14T10:00:00.000Z", iso(PeriodMath.add(jan31, period("P1W"), 2)))
    }

    @Test
    fun nextBoundaryForMonthlyAnnualAndWeekly() {
        val jan31 = t(start)
        assertEquals("2026-02-28T10:00:00.000Z", iso(PeriodMath.nextBoundaryAfter(jan31, period("P1M"), t("2026-02-10T00:00:00.000Z"))))
        assertEquals("exactly on a boundary: the next one", "2026-03-31T10:00:00.000Z", iso(PeriodMath.nextBoundaryAfter(jan31, period("P1M"), t("2026-02-28T10:00:00.000Z"))))
        assertEquals("2027-01-31T10:00:00.000Z", iso(PeriodMath.nextBoundaryAfter(jan31, period("P1Y"), t("2026-12-31T00:00:00.000Z"))))
        assertEquals("2026-02-07T10:00:00.000Z", iso(PeriodMath.nextBoundaryAfter(jan31, period("P1W"), t("2026-02-03T00:00:00.000Z"))))
        assertEquals("start in the future", jan31, PeriodMath.nextBoundaryAfter(jan31, period("P1M"), jan31 - 1))
    }

    @Test
    fun manyRenewalsLater() {
        val jan31 = t(start)
        // 10 years of monthly renewals: still anchored on the 31st, clamped in short months.
        assertEquals("2036-02-29T10:00:00.000Z", iso(PeriodMath.nextBoundaryAfter(jan31, period("P1M"), t("2036-02-15T00:00:00.000Z"))))
        assertEquals("2036-03-31T10:00:00.000Z", iso(PeriodMath.nextBoundaryAfter(jan31, period("P1M"), t("2036-02-29T10:00:00.000Z"))))
        assertEquals("2031-01-25T10:00:00.000Z", iso(PeriodMath.nextBoundaryAfter(jan31, period("P1W"), t("2031-01-20T00:00:00.000Z"))))
    }

    // endregion

    // region Inference

    // Purchase at 2026-01-31T10:00Z. "Before" snapshots are taken a day earlier, "after" ones a day later.
    private val dayBefore = t("2026-01-30T10:00:00.000Z")
    private val dayAfter = t("2026-02-01T10:00:00.000Z")
    private val monthly = basePlan("monthly", "P1M")
    private val freeWeek = trialOffer("monthly", "free-week", "P1W", "P1M")

    /** Knowledge from successive catalogue answers: (time, offers). */
    private fun history(vararg answers: Pair<Long, List<OfferFacts>>): ProductKnowledge =
        answers.fold(null as ProductKnowledge?) { known, (at, offers) -> SubscriptionInference.learn(known, offers, at) }
            ?: error("no answers")

    @Test
    fun nothingKnownGivesNulls() {
        assertEquals(Inference.NONE, infer(null, "2026-02-10T00:00:00.000Z"))
        val failedOnly = SubscriptionInference.attempted(null, dayBefore)
        assertEquals("details unavailable", Inference.NONE, infer(failedOnly, "2026-02-10T00:00:00.000Z"))
        val notReturned = SubscriptionInference.notReturned(null, dayBefore)
        assertEquals("unknown product ID", Inference.NONE, infer(notReturned, "2026-02-10T00:00:00.000Z"))
        assertEquals(emptyList<String>(), Inference.NONE.inferredFields)
    }

    @Test
    fun trialListedBeforeAndGoneAfterWasConsumed() {
        val knowledge = history(dayBefore to listOf(monthly, freeWeek), dayAfter to listOf(monthly))
        val trialEnd = "2026-02-07T10:00:00.000Z"

        val during = infer(knowledge, "2026-02-07T09:59:59.999Z")
        assertEquals(true, during.isTrial)
        assertEquals("during the trial, expires_at is its end", trialEnd, iso(during.expiresAtMillis))
        assertEquals(listOf("expires_at", "is_trial"), during.inferredFields)

        val atEnd = infer(knowledge, trialEnd)
        assertEquals(false, atEnd.isTrial)
        assertEquals("first paid month starts after the trial", "2026-03-07T10:00:00.000Z", iso(atEnd.expiresAtMillis))
        assertEquals("many renewals later", "2027-01-07T10:00:00.000Z", iso(infer(knowledge, "2026-12-20T00:00:00.000Z").expiresAtMillis))
    }

    @Test
    fun repeatedTrialCyclesAndAnnualPlan() {
        val yearly = basePlan("yearly", "P1Y")
        val twoWeeks = trialOffer("yearly", "two-weeks", "P1W", "P1Y", cycles = 2)
        val knowledge = history(dayBefore to listOf(yearly, twoWeeks), dayAfter to listOf(yearly))
        assertEquals(true, infer(knowledge, "2026-02-14T09:59:59.999Z").isTrial)
        val after = infer(knowledge, "2026-02-14T10:00:00.000Z")
        assertEquals(false, after.isTrial)
        assertEquals("2027-02-14T10:00:00.000Z", iso(after.expiresAtMillis))
    }

    @Test
    fun trialStillListedAfterThePurchaseStaysCautious() {
        val knowledge = history(dayBefore to listOf(monthly, freeWeek), dayAfter to listOf(monthly, freeWeek))
        assertEquals("taken or not: unknown", Inference(null, null, InferenceBasis.TRIAL_UNCERTAIN), infer(knowledge, "2026-02-03T00:00:00.000Z"))
        val later = infer(knowledge, "2026-03-10T00:00:00.000Z")
        assertEquals("past any trial", false, later.isTrial)
        assertNull("start unknown by one trial length", later.expiresAtMillis)

        // No snapshot after the purchase yet: same caution.
        assertEquals(Inference(null, null, InferenceBasis.TRIAL_UNCERTAIN), infer(history(dayBefore to listOf(monthly, freeWeek)), "2026-02-03T00:00:00.000Z"))
        // A later snapshot shows it gone (Play's eligibility caught up): consumed.
        val caughtUp = history(dayBefore to listOf(monthly, freeWeek), dayAfter to listOf(monthly, freeWeek), dayAfter + 86_400_000 to listOf(monthly))
        assertEquals(true, infer(caughtUp, "2026-02-03T00:00:00.000Z").isTrial)
    }

    @Test
    fun noTrialListedBeforeThePurchaseIsFalse() {
        val knowledge = history(dayBefore to listOf(monthly), dayAfter to listOf(monthly))
        val result = infer(knowledge, "2026-02-03T00:00:00.000Z")
        assertEquals(false, result.isTrial)
        assertEquals("2026-02-28T10:00:00.000Z", iso(result.expiresAtMillis))
    }

    @Test
    fun aSnapshotTakenAfterThePurchaseIsNotBefore() {
        // First catalogue read after the purchase, no trial listed: used trial or none at all.
        val afterOnly = history(dayAfter to listOf(monthly))
        val result = infer(afterOnly, "2026-02-03T00:00:00.000Z")
        assertNull(result.isTrial)
        assertEquals("estimated as if there was no trial (a hint)", "2026-02-28T10:00:00.000Z", iso(result.expiresAtMillis))
        assertEquals(listOf("expires_at"), result.inferredFields)

        // Within the clock-skew margin before the purchase: not "before" either.
        val justBefore = t(start) - SubscriptionInference.BEFORE_MARGIN_MILLIS + 1
        assertNull(infer(history(justBefore to listOf(monthly)), "2026-02-03T00:00:00.000Z").isTrial)
        val clearlyBefore = t(start) - SubscriptionInference.BEFORE_MARGIN_MILLIS
        assertEquals(false, infer(history(clearlyBefore to listOf(monthly)), "2026-02-03T00:00:00.000Z").isTrial)

        // A trial listed only after the purchase: still-listed caution.
        assertEquals(Inference(null, null, InferenceBasis.TRIAL_UNCERTAIN), infer(history(dayAfter to listOf(monthly, freeWeek)), "2026-02-03T00:00:00.000Z"))
    }

    @Test
    fun severalBasePlansWithDifferentPeriods() {
        val yearly = basePlan("yearly", "P1Y")
        val both = history(dayBefore to listOf(monthly, yearly), dayAfter to listOf(monthly, yearly))
        val result = infer(both, "2026-03-10T00:00:00.000Z")
        assertEquals(false, result.isTrial)
        assertNull("which plan was bought is unknown", result.expiresAtMillis)

        // The consumed trial belongs to the monthly plan: that is the plan bought.
        val consumed = history(dayBefore to listOf(monthly, yearly, freeWeek), dayAfter to listOf(monthly, yearly))
        assertEquals("2026-03-07T10:00:00.000Z", iso(infer(consumed, "2026-02-20T00:00:00.000Z").expiresAtMillis))

        // Play hid the plan just bought.
        val vanished = history(dayBefore to listOf(monthly, yearly), dayAfter to listOf(monthly))
        assertEquals("2027-01-31T10:00:00.000Z", iso(infer(vanished, "2026-03-10T00:00:00.000Z").expiresAtMillis))

        val samePeriod = history(dayBefore to listOf(monthly, basePlan("monthly-promo", "P1M")))
        assertEquals("2026-03-31T10:00:00.000Z", iso(infer(samePeriod, "2026-03-10T00:00:00.000Z").expiresAtMillis))

        val prepaid = history(dayBefore to listOf(monthly, OfferFacts("prepaid", null, listOf(PhaseFacts("P1M", 1, 1, PhaseFacts.NON_RECURRING)))))
        assertNull("a non-renewing base plan makes it ambiguous", infer(prepaid, "2026-03-10T00:00:00.000Z").expiresAtMillis)
    }

    @Test
    fun consumedTrialsOfDifferentLengths() {
        val month = trialOffer("monthly", "month", "P1M", "P1M")
        val knowledge = history(dayBefore to listOf(monthly, freeWeek, month), dayAfter to listOf(monthly))
        assertEquals(Inference(true, null, InferenceBasis.MULTIPLE_TRIALS), infer(knowledge, "2026-02-03T00:00:00.000Z"))
        assertEquals("between the two ends: unknown", Inference(null, null, InferenceBasis.MULTIPLE_TRIALS), infer(knowledge, "2026-02-20T00:00:00.000Z"))
        val after = infer(knowledge, "2026-03-05T00:00:00.000Z")
        assertEquals(false, after.isTrial)
        assertNull("start of the paid period is ambiguous", after.expiresAtMillis)
    }

    @Test
    fun cachedTrialIsKeptWhenPlayStopsListingIt() {
        val knowledge = history(dayBefore to listOf(monthly, freeWeek), dayAfter to listOf(monthly))
        assertEquals(mapOf("monthly/free-week" to TrialFacts("P1W", 1)), knowledge.trials)
        assertEquals(2, knowledge.snapshots.size)
        val decoded = SubscriptionInference.decode(SubscriptionInference.encode(mapOf("premium_monthly" to knowledge)))
        assertEquals(knowledge, decoded["premium_monthly"])
    }

    @Test
    fun unchangedAnswersExtendOneSnapshot() {
        val knowledge = history(dayBefore to listOf(monthly), dayAfter to listOf(monthly), dayAfter + 1 to listOf(monthly))
        assertEquals(listOf(CatalogueSnapshot(dayBefore, dayAfter + 1, setOf("monthly"), emptySet())), knowledge.snapshots)
        val many = (1..20).fold(knowledge) { k, i -> SubscriptionInference.learn(k, if (i % 2 == 0) listOf(monthly) else listOf(monthly, freeWeek), dayAfter + i) }
        assertEquals(SubscriptionInference.MAX_SNAPSHOTS, many.snapshots.size)
    }

    @Test
    fun suspendedOrPendingAreNotInferred() {
        val knowledge = history(dayBefore to listOf(monthly))
        val now = t("2026-02-10T00:00:00.000Z")
        assertEquals(Inference.NONE, SubscriptionInference.infer(purchase(time = t(start), suspended = true), knowledge, now))
        assertEquals(Inference.NONE, SubscriptionInference.infer(purchase(time = t(start), state = PurchaseStateValue.PENDING), knowledge, now))
    }

    @Test
    fun cancelledSubscriptionStillGetsItsPeriodEnd() {
        val knowledge = history(dayBefore to listOf(monthly))
        val result = SubscriptionInference.infer(purchase(time = t(start), autoRenewing = false), knowledge, t("2026-02-10T00:00:00.000Z"))
        assertEquals("2026-02-28T10:00:00.000Z", iso(result.expiresAtMillis))
    }

    @Test
    fun cacheFromAnOlderSdkWithoutSnapshots() {
        val legacy = """{"premium_monthly":{"plans":{"monthly":"P1M"},"trials":{"monthly/free-week":{"period":"P1W","cycles":1}},"first":1,"last":2,"attempt":2}}"""
        val knowledge = SubscriptionInference.decode(legacy)["premium_monthly"]
        assertEquals(2L, knowledge?.lastAnsweredAtMillis)
        assertEquals("trial known, nothing about before/after: cautious", Inference(null, null, InferenceBasis.TRIAL_UNCERTAIN), infer(knowledge, "2026-02-03T00:00:00.000Z"))
    }

    // endregion

    // region inference_basis (labels the rule; the values above are unchanged)

    private fun basis(knowledge: ProductKnowledge?, now: String = "2026-02-03T00:00:00.000Z"): String? =
        infer(knowledge, now).basis?.wireValue

    @Test
    fun basisWireValuesAreTheSchemaEnum() {
        assertEquals(
            listOf("no_trial_offered", "trial_consumed", "trial_uncertain", "no_snapshot_before", "multiple_trials"),
            InferenceBasis.values().map { it.wireValue },
        )
    }

    @Test
    fun basisIsNullWhenNothingIsInferred() {
        assertNull(Inference.NONE.basis)
        assertNull("not read yet", basis(null))
        assertNull("read failed", basis(SubscriptionInference.attempted(null, dayBefore)))
        assertNull("not returned", basis(SubscriptionInference.notReturned(null, dayBefore)))
        val knowledge = history(dayBefore to listOf(monthly))
        val now = t("2026-02-10T00:00:00.000Z")
        assertNull("suspended", SubscriptionInference.infer(purchase(time = t(start), suspended = true), knowledge, now).basis)
        assertNull("pending", SubscriptionInference.infer(purchase(time = t(start), state = PurchaseStateValue.PENDING), knowledge, now).basis)
    }

    @Test
    fun basisNoTrialOffered() {
        assertEquals("no_trial_offered", basis(history(dayBefore to listOf(monthly), dayAfter to listOf(monthly))))
        assertEquals("B alone is enough", "no_trial_offered", basis(history(dayBefore to listOf(monthly))))
        val yearly = basePlan("yearly", "P1Y")
        val ambiguousPeriod = infer(history(dayBefore to listOf(monthly, yearly), dayAfter to listOf(monthly, yearly)), "2026-03-10T00:00:00.000Z")
        assertEquals("the basis stays when the period (so expires_at) is unknown", Inference(false, null, InferenceBasis.NO_TRIAL_OFFERED), ambiguousPeriod)
    }

    @Test
    fun basisTrialConsumed() {
        val knowledge = history(dayBefore to listOf(monthly, freeWeek), dayAfter to listOf(monthly))
        assertEquals("during the trial", "trial_consumed", basis(knowledge, "2026-02-03T00:00:00.000Z"))
        assertEquals("after the trial", "trial_consumed", basis(knowledge, "2026-02-20T00:00:00.000Z"))
        // Two consumed offers with the same trial end are one trial length.
        val sameLength = trialOffer("monthly", "other-week", "P1W", "P1M")
        val two = history(dayBefore to listOf(monthly, freeWeek, sameLength), dayAfter to listOf(monthly))
        assertEquals(Inference(true, t("2026-02-07T10:00:00.000Z"), InferenceBasis.TRIAL_CONSUMED), infer(two, "2026-02-03T00:00:00.000Z"))
    }

    @Test
    fun basisTrialUncertain() {
        assertEquals("still listed in A", "trial_uncertain", basis(history(dayBefore to listOf(monthly, freeWeek), dayAfter to listOf(monthly, freeWeek))))
        assertEquals("no A", "trial_uncertain", basis(history(dayBefore to listOf(monthly, freeWeek))))
        assertEquals("no B", "trial_uncertain", basis(history(dayAfter to listOf(monthly, freeWeek))))
        val past = infer(history(dayBefore to listOf(monthly, freeWeek), dayAfter to listOf(monthly, freeWeek)), "2026-03-10T00:00:00.000Z")
        assertEquals("past the longest listed trial", Inference(false, null, InferenceBasis.TRIAL_UNCERTAIN), past)
    }

    @Test
    fun basisNoSnapshotBefore() {
        val afterOnly = infer(history(dayAfter to listOf(monthly)), "2026-02-03T00:00:00.000Z")
        assertEquals(Inference(null, t("2026-02-28T10:00:00.000Z"), InferenceBasis.NO_SNAPSHOT_BEFORE), afterOnly)
        // Wider than the table row: no "after" either (the only snapshot is inside the clock-skew margin).
        val justBefore = t(start) - SubscriptionInference.BEFORE_MARGIN_MILLIS + 1
        assertEquals("no_snapshot_before", basis(history(justBefore to listOf(monthly))))
    }

    @Test
    fun basisMultipleTrials() {
        val month = trialOffer("monthly", "month", "P1M", "P1M")
        val knowledge = history(dayBefore to listOf(monthly, freeWeek, month), dayAfter to listOf(monthly))
        for (now in listOf("2026-02-03T00:00:00.000Z", "2026-02-20T00:00:00.000Z", "2026-03-05T00:00:00.000Z")) {
            assertEquals(now, "multiple_trials", basis(knowledge, now))
        }
    }

    @Test
    fun consumedTrialOfUnknownLengthIsLabelledUncertain() {
        // The consumed offer's length fell out of the cache: neither field can be inferred.
        val knowledge = history(dayBefore to listOf(monthly, freeWeek), dayAfter to listOf(monthly)).copy(trials = emptyMap())
        assertEquals(Inference(null, null, InferenceBasis.TRIAL_UNCERTAIN), infer(knowledge, "2026-02-03T00:00:00.000Z"))
    }

    @Test
    fun everyBranchHasALabel() {
        assertEquals(InferenceBasis.NO_TRIAL_OFFERED, SubscriptionInference.basisOf(SubscriptionInference.TrialBasis.NO_TRIAL_OFFERED, 0))
        assertEquals(InferenceBasis.TRIAL_CONSUMED, SubscriptionInference.basisOf(SubscriptionInference.TrialBasis.TRIAL_CONSUMED, 1))
        assertEquals(InferenceBasis.MULTIPLE_TRIALS, SubscriptionInference.basisOf(SubscriptionInference.TrialBasis.TRIAL_CONSUMED, 2))
        assertEquals(InferenceBasis.TRIAL_UNCERTAIN, SubscriptionInference.basisOf(SubscriptionInference.TrialBasis.TRIAL_CONSUMED, 0))
        assertEquals(InferenceBasis.TRIAL_UNCERTAIN, SubscriptionInference.basisOf(SubscriptionInference.TrialBasis.AMBIGUOUS, 1))
        assertEquals(InferenceBasis.NO_SNAPSHOT_BEFORE, SubscriptionInference.basisOf(SubscriptionInference.TrialBasis.UNKNOWN, 0))
    }

    // endregion

    // region Cache

    @Test
    fun queryRateLimits() {
        val now = t("2026-02-01T00:00:00.000Z")
        val fetched = history(now to listOf(monthly))
        val failed = SubscriptionInference.attempted(null, now)
        val missing = SubscriptionInference.notReturned(null, now)
        val cache = mapOf("a" to fetched, "b" to failed, "m" to missing)
        assertEquals(listOf("c"), SubscriptionInference.productsToQuery(listOf("a", "b", "m", "c", "c"), cache, now + 1))
        assertEquals("failure retried after an hour; unknown ID only daily", listOf("b"), SubscriptionInference.productsToQuery(listOf("a", "b", "m"), cache, now + SubscriptionInference.RETRY_AFTER_FAILURE_MILLIS))
        assertEquals(listOf("a", "b", "m"), SubscriptionInference.productsToQuery(listOf("a", "b", "m"), cache, now + SubscriptionInference.REFRESH_AFTER_SUCCESS_MILLIS))
        assertEquals("clock went backwards", listOf("a"), SubscriptionInference.productsToQuery(listOf("a"), cache, now - 1))
        assertEquals(
            "just bought: one read right away, whatever the throttle",
            listOf("a"),
            SubscriptionInference.productsToQuery(listOf("a"), cache, now + 5_000, purchaseTimes = mapOf("a" to now + 4_000)),
        )
        assertEquals(
            "purchase older than the last attempt: throttled as usual",
            emptyList<String>(),
            SubscriptionInference.productsToQuery(listOf("a"), cache, now + 5_000, purchaseTimes = mapOf("a" to now - 1)),
        )
        assertEquals(
            "purchase in the device's future (clock behind): no forced read",
            emptyList<String>(),
            SubscriptionInference.productsToQuery(listOf("a"), cache, now + 5_000, purchaseTimes = mapOf("a" to now + 60_000)),
        )
        val many = (1..50).map { "p$it" }
        assertEquals(SubscriptionInference.MAX_PRODUCTS_PER_QUERY, SubscriptionInference.productsToQuery(many, emptyMap(), now).size)
    }

    @Test
    fun cacheBoundKeepsPinnedProducts() {
        val many = (1..30).associate { "p$it" to SubscriptionInference.attempted(null, it.toLong()) }
        val pinned = (1..20).map { "p$it" }.toSet() // the oldest 20 are configured
        val bounded = SubscriptionInference.bound(many, pinned)
        assertEquals(20 + SubscriptionInference.MAX_UNPINNED_PRODUCTS, bounded.size)
        assertTrue(bounded.keys.containsAll(pinned))
        val unpinnedOnly = SubscriptionInference.bound(many)
        assertEquals(SubscriptionInference.MAX_UNPINNED_PRODUCTS, unpinnedOnly.size)
        assertTrue("keeps the most recent", "p30" in unpinnedOnly && "p1" !in unpinnedOnly)
    }

    @Test
    fun cacheToleratesGarbage() {
        val offers = (1..15).map { trialOffer("plan", "o$it", "P3D", "P1M") }
        assertEquals(SubscriptionInference.MAX_ENTRIES_PER_PRODUCT, SubscriptionInference.learn(null, offers, 0).trials.size)
        assertEquals(emptyMap<String, ProductKnowledge>(), SubscriptionInference.decode("{nope"))
        assertEquals(emptyMap<String, ProductKnowledge>(), SubscriptionInference.decode(null))
        assertEquals("a bad entry is skipped", setOf("ok"), SubscriptionInference.decode("""{"bad":{"plans":3},"ok":{"plans":{},"trials":{}}}""").keys)
    }

    @Test
    fun developerOfferFillsInAnUnlistedBasePlan() {
        val knowledge = SubscriptionInference.learn(null, listOf(trialOffer("monthly", "promo", "P3D", "P1M")), 0)
        assertEquals(mapOf("monthly" to "P1M"), knowledge.basePlans)
        val updated = SubscriptionInference.learn(knowledge, listOf(basePlan("monthly", "P3M")), 1)
        assertEquals(mapOf("monthly" to "P3M"), updated.basePlans)
    }

    // endregion
}
