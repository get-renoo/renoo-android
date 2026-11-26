package dev.renoo.sdk.collectors

import dev.renoo.sdk.collectors.Fixture.TOKEN_A
import dev.renoo.sdk.collectors.Fixture.TOKEN_B
import dev.renoo.sdk.collectors.Fixture.purchase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionMappingTest {
    @Test
    fun statusOfAPurchase() {
        assertEquals(SubscriptionStatus.ACTIVE, SubscriptionMapping.status(purchase()))
        assertEquals(SubscriptionStatus.ACTIVE, SubscriptionMapping.status(purchase(autoRenewing = false)))
        assertEquals(SubscriptionStatus.SUSPENDED, SubscriptionMapping.status(purchase(suspended = true)))
        assertNull("pending is not active", SubscriptionMapping.status(purchase(state = PurchaseStateValue.PENDING)))
        assertNull(SubscriptionMapping.status(purchase(state = PurchaseStateValue.UNSPECIFIED)))
    }

    @Test
    fun trackedStatuses() {
        assertEquals(
            listOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.SUSPENDED),
            SubscriptionStatus.entries.filter { it.isTracked },
        )
        assertEquals(listOf("active", "suspended", "expired", "none"), SubscriptionStatus.entries.map { it.wireValue })
    }

    @Test
    fun primaryPrefersActiveThenRenewingThenLatest() {
        val suspended = purchase(token = "s", suspended = true, time = Fixture.T0 + 9)
        val cancelled = purchase(token = "c", autoRenewing = false, time = Fixture.T0 + 5)
        val older = purchase(token = "o", time = Fixture.T0)
        val newer = purchase(token = "n", time = Fixture.T0 + 1)
        val pending = purchase(token = "p", state = PurchaseStateValue.PENDING, time = Fixture.T0 + 99)
        assertEquals("n", SubscriptionMapping.primary(listOf(suspended, cancelled, older, newer, pending))?.purchaseToken)
        assertEquals("c", SubscriptionMapping.primary(listOf(suspended, cancelled))?.purchaseToken)
        assertEquals("s", SubscriptionMapping.primary(listOf(suspended, pending))?.purchaseToken)
        assertNull(SubscriptionMapping.primary(listOf(pending)))
    }

    @Test
    fun resolveInfersExpiryFromARememberedSubscription() {
        assertEquals(SubscriptionStatus.NONE to null, SubscriptionMapping.resolve(emptyList(), null))
        val remembered = purchase(autoRenewing = true)
        val (status, facts) = SubscriptionMapping.resolve(emptyList(), remembered)
        assertEquals(SubscriptionStatus.EXPIRED, status)
        assertEquals(false, facts?.isAutoRenewing)
        assertEquals(TOKEN_A, facts?.purchaseToken)
        // A pending purchase alone is not an entitlement either.
        assertEquals(
            SubscriptionStatus.EXPIRED,
            SubscriptionMapping.resolve(listOf(purchase(token = TOKEN_B, state = PurchaseStateValue.PENDING)), remembered).first,
        )
        assertEquals(SubscriptionStatus.ACTIVE, SubscriptionMapping.resolve(listOf(purchase(token = TOKEN_B)), remembered).first)
    }

    @Test
    fun statePropertiesMatchTheSchemaPlusPurchaseToken() {
        val properties = SubscriptionMapping.stateProperties(
            SubscriptionStatus.ACTIVE,
            purchase(autoRenewing = false),
            SubscriptionTrigger.APP_OPEN,
        )
        assertEquals(
            setOf(
                "status", "product_id", "auto_renew", "expires_at", "original_transaction_id", "transaction_id",
                "purchase_token", "account_token", "is_trial", "offer_type", "environment", "inferred_fields", "inference_basis", "trigger",
            ),
            properties.keys,
        )
        assertEquals("nothing inferred without catalogue knowledge", emptyList<String>(), properties["inferred_fields"])
        assertEquals("active", properties["status"])
        assertEquals("premium_monthly", properties["product_id"])
        assertEquals(false, properties["auto_renew"])
        assertEquals("GPA.1111-2222-3333-44444", properties["transaction_id"])
        assertEquals(TOKEN_A, properties["purchase_token"])
        assertEquals("app_open", properties["trigger"])
        for (unknowable in listOf("expires_at", "original_transaction_id", "is_trial", "offer_type", "environment", "inference_basis")) {
            assertNull(unknowable, properties[unknowable])
        }
    }

    @Test
    fun inferenceBasisIsAlwaysPresentAsStringOrNull() {
        for (basis in InferenceBasis.values()) {
            val properties = SubscriptionMapping.stateProperties(
                SubscriptionStatus.ACTIVE, purchase(), SubscriptionTrigger.APP_OPEN, Inference(null, null, basis),
            )
            assertEquals(basis.wireValue, properties["inference_basis"])
        }
        val inference = Inference(false, 1_800_000_000_000L, InferenceBasis.NO_TRIAL_OFFERED)
        val none = SubscriptionMapping.stateProperties(SubscriptionStatus.NONE, purchase(), SubscriptionTrigger.APP_OPEN, inference)
        assertTrue("present", none.containsKey("inference_basis"))
        assertNull("nothing is inferred for `none`", none["inference_basis"])
    }

    @Test
    fun inferenceBasisAloneIsNotAStateChange() {
        fun state(basis: InferenceBasis?) = SubscriptionMapping.stateProperties(
            SubscriptionStatus.ACTIVE, purchase(), SubscriptionTrigger.TRANSACTION_UPDATE, Inference(null, null, basis),
        )
        val uncertain = state(InferenceBasis.TRIAL_UNCERTAIN)
        val several = state(InferenceBasis.MULTIPLE_TRIALS)
        assertEquals("only the basis differs", setOf("inference_basis"), uncertain.keys.filter { uncertain[it] != several[it] }.toSet())
        assertEquals(SubscriptionMapping.comparable(uncertain), SubscriptionMapping.comparable(several))
        assertEquals(SubscriptionMapping.comparable(uncertain), SubscriptionMapping.comparable(state(null)))
        assertFalse(SubscriptionMapping.comparable(uncertain).containsKey("inference_basis"))
        val cancelled = SubscriptionMapping.stateProperties(
            SubscriptionStatus.ACTIVE, purchase(autoRenewing = false), SubscriptionTrigger.TRANSACTION_UPDATE, Inference(null, null, InferenceBasis.TRIAL_UNCERTAIN),
        )
        assertTrue("a real change still counts", SubscriptionMapping.comparable(uncertain) != SubscriptionMapping.comparable(cancelled))
    }

    @Test
    fun noneHasEveryFieldNull() {
        val properties = SubscriptionMapping.stateProperties(SubscriptionStatus.NONE, purchase(), SubscriptionTrigger.TRANSACTION_UPDATE)
        assertEquals("none", properties["status"])
        assertEquals("transaction_update", properties["trigger"])
        assertTrue((properties - "status" - "trigger" - "inferred_fields").values.all { it == null })
        assertTrue(properties.containsKey("inference_basis"))
        assertEquals(emptyList<String>(), properties["inferred_fields"])
    }

    @Test
    fun multiLinePurchaseReportsItsFirstProduct() {
        val multi = purchase().copy(productIds = listOf("base_plan_sub", "addon_sub"))
        assertEquals("base_plan_sub", SubscriptionMapping.stateProperties(SubscriptionStatus.ACTIVE, multi, SubscriptionTrigger.APP_OPEN)["product_id"])
    }

    @Test
    fun detectsPurchasesRenewalsAndUpgrades() {
        val first = purchase()
        val nothingKnown = KnownPurchases()
        assertEquals(
            listOf(TransactionKind.PURCHASE),
            SubscriptionMapping.detectTransactions(listOf(first), nothingKnown).map { it.kind },
        )
        val known = SubscriptionMapping.remember(listOf(first), nothingKnown)

        assertTrue("same order: nothing new", SubscriptionMapping.detectTransactions(listOf(first), known).isEmpty())
        assertTrue(
            "pending purchases wait",
            SubscriptionMapping.detectTransactions(listOf(first, purchase(token = TOKEN_B, orderId = null, state = PurchaseStateValue.PENDING)), known).isEmpty(),
        )
        assertTrue("a missing order ID is not a renewal", SubscriptionMapping.detectTransactions(listOf(first.copy(orderId = null)), known).isEmpty())

        val renewed = first.copy(orderId = "GPA.1111-2222-3333-44444..0")
        assertEquals(listOf(TransactionKind.RENEWAL), SubscriptionMapping.detectTransactions(listOf(renewed), known).map { it.kind })

        val replacement = purchase(token = TOKEN_B, orderId = "GPA.5", product = "premium_yearly", time = Fixture.T0 + 10)
        assertEquals(listOf(TransactionKind.UPGRADE), SubscriptionMapping.detectTransactions(listOf(replacement), known).map { it.kind })
        assertEquals(
            "both still there: a second subscription, not a replacement",
            listOf(TransactionKind.PURCHASE),
            SubscriptionMapping.detectTransactions(listOf(first, replacement), known).map { it.kind },
        )
    }

    @Test
    fun transactionProperties() {
        val properties = SubscriptionMapping.transactionProperties(
            DetectedTransaction(purchase(accountToken = "acct-hash-1"), TransactionKind.RENEWAL),
        )
        assertEquals(
            mapOf(
                "product_id" to "premium_monthly",
                "transaction_id" to "GPA.1111-2222-3333-44444",
                "original_transaction_id" to null,
                "purchase_token" to TOKEN_A,
                "account_token" to "acct-hash-1",
                "purchase_date" to "2023-11-14T22:13:20.000Z",
                "kind" to "renewal",
                "offer_type" to null,
                "environment" to null,
            ),
            properties,
        )
    }

    @Test
    fun accountTokenIsReportedAndRemembered() {
        val bought = purchase(accountToken = "acct-hash-1")
        assertEquals("acct-hash-1", SubscriptionMapping.stateProperties(SubscriptionStatus.ACTIVE, bought, SubscriptionTrigger.APP_OPEN)["account_token"])
        assertEquals(bought, SubscriptionMapping.decodePurchase(SubscriptionMapping.encode(bought)))
        assertEquals(purchase(), SubscriptionMapping.decodePurchase(SubscriptionMapping.encode(purchase())))
        val expired = SubscriptionMapping.stateProperties(SubscriptionStatus.EXPIRED, bought, SubscriptionTrigger.APP_OPEN)
        assertEquals("acct-hash-1", expired["account_token"])
        assertEquals(listOf("status"), expired["inferred_fields"])
        val none = SubscriptionMapping.stateProperties(SubscriptionStatus.NONE, null, SubscriptionTrigger.APP_OPEN)
        assertTrue(none.containsKey("account_token"))
        assertNull(none["account_token"])
        assertEquals(emptyList<String>(), none["inferred_fields"])
    }

    @Test
    fun rememberedTokensAreBoundedButEntitledOnesAreKept() {
        var known = SubscriptionMapping.remember(listOf(purchase(token = "keep", time = 0)), KnownPurchases())
        val keepKey = SubscriptionMapping.tokenKey("keep")
        repeat(SubscriptionMapping.MAX_KNOWN_TOKENS + 10) { i ->
            known = SubscriptionMapping.remember(
                listOf(purchase(token = "keep", time = 0), purchase(token = "t$i", time = i + 1L)),
                known,
            )
        }
        assertEquals(SubscriptionMapping.MAX_KNOWN_TOKENS, known.orders.size)
        assertTrue(known.orders.containsKey(keepKey))
        assertTrue(known.orders.containsKey(SubscriptionMapping.tokenKey("t${SubscriptionMapping.MAX_KNOWN_TOKENS + 9}")))
        assertEquals(setOf(keepKey, SubscriptionMapping.tokenKey("t${SubscriptionMapping.MAX_KNOWN_TOKENS + 9}")), known.lastEntitled)
    }

    @Test
    fun persistenceRoundTripsAndToleratesGarbage() {
        val facts = purchase(orderId = null, suspended = true).copy(productIds = listOf("a", "b"))
        assertEquals(facts, SubscriptionMapping.decodePurchase(SubscriptionMapping.encode(facts)))
        assertNull(SubscriptionMapping.decodePurchase("not json"))
        assertNull(SubscriptionMapping.decodePurchase(null))

        val known = SubscriptionMapping.remember(listOf(purchase(), purchase(token = TOKEN_B, orderId = null)), KnownPurchases())
        assertEquals(known, SubscriptionMapping.decodeKnown(SubscriptionMapping.encode(known)))
        assertEquals(KnownPurchases(), SubscriptionMapping.decodeKnown("{]"))
    }

    @Test
    fun tokenKeyIsAShortStableHash() {
        val key = SubscriptionMapping.tokenKey(TOKEN_A)
        assertEquals(32, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(key, SubscriptionMapping.tokenKey(TOKEN_A))
        assertTrue(key != SubscriptionMapping.tokenKey(TOKEN_B))
    }

    @Test
    fun billingActivityNames() {
        assertTrue(SubscriptionCollector.isBillingActivity("com.android.billingclient.api.ProxyBillingActivity"))
        assertTrue(SubscriptionCollector.isBillingActivity("com.android.billingclient.api.ProxyBillingActivityV2"))
        assertTrue(!SubscriptionCollector.isBillingActivity("com.example.MainActivity"))
    }

    @Test
    fun foregroundCounter() {
        val counter = ForegroundCounter()
        assertTrue(counter.onStarted())
        assertTrue("second activity", !counter.onStarted())
        counter.onStopped(changingConfigurations = false)
        counter.onStopped(changingConfigurations = true) // rotation
        assertTrue("restarted after rotation: still foreground", !counter.onStarted())
        counter.onStopped(changingConfigurations = false)
        assertTrue("back from background", counter.onStarted())

        val late = ForegroundCounter() // registered after the first activity started
        late.onStopped(changingConfigurations = false)
        assertTrue(late.onStarted())
    }
}
