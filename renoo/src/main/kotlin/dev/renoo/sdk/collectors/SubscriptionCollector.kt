package dev.renoo.sdk.collectors

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import dev.renoo.sdk.core.ClearReason
import dev.renoo.sdk.core.Collector
import dev.renoo.sdk.core.SdkEnvironment
import dev.renoo.sdk.core.StateOwner

/**
 * Subscription state through Play Billing and the tracking gate.
 *
 * - At start and on every return to the foreground: reads `queryPurchasesAsync(SUBS)`, emits
 *   `subscription_state` (trigger `app_open`, one per app open) and opens or closes the gate.
 * - Shortly after Play's `ProxyBillingActivity` closes (an in-app purchase flow just ended, bought or
 *   not): re-reads; new purchases emit `transaction` and then `subscription_state` (trigger
 *   `transaction_update`), a state change with no new transaction emits `subscription_state`
 *   (trigger `status_update`), and nothing is emitted when nothing changed.
 * - Renewals and purchases made while the app wasn't running are caught by the next read.
 * - `is_trial` and `expires_at` are best-effort estimates from Play's product catalogue, cached per
 *   product and listed in `inferred_fields` ([SubscriptionInference]).
 *
 * Foregrounds are detected with its own lightweight `ActivityLifecycleCallbacks` (started-activity
 * count; no AndroidX). All rules live in [SubscriptionTracker] and [SubscriptionMapping]; Billing
 * calls in [PlayBillingSubscriptionSource]. Observe only: never launches a billing flow,
 * acknowledges or consumes.
 */
internal class SubscriptionCollector(
    private val environment: SdkEnvironment,
    source: SubscriptionSource = PlayBillingSubscriptionSource(environment.appContext, environment.logger),
    private val scheduler: Scheduler = MainLooperScheduler(environment.logger),
    uptime: () -> Long = SystemClock::elapsedRealtime,
) : Collector, StateOwner {
    val tracker = SubscriptionTracker(
        pipeline = environment.pipeline,
        prefs = environment.prefs,
        logger = environment.logger,
        executor = environment.executor,
        source = source,
        scheduler = scheduler,
        installMarker = { environment.identity.firstInstallTimeMillis },
        uptime = uptime,
        configuredProductIds = environment.configuration.subscriptionProductIds,
    )

    /** Main-thread only. */
    private val foreground = ForegroundCounter()

    override fun clearState(reason: ClearReason) = tracker.clearState(reason)

    override fun start() {
        val application = environment.appContext as? Application
        if (application == null) {
            environment.logger.error { "No Application context: subscription state is read at launch only" }
        } else {
            application.registerActivityLifecycleCallbacks(callbacks)
        }
        // The pipeline buffers every event until this first read answers.
        tracker.appOpened()
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) = guard {
            if (foreground.onStarted()) tracker.appOpened()
        }

        override fun onActivityStopped(activity: Activity) = guard {
            foreground.onStopped(activity.isChangingConfigurations)
        }

        override fun onActivityDestroyed(activity: Activity) = guard {
            if (isBillingActivity(activity.javaClass.name) && !activity.isChangingConfigurations) {
                // Give Play a moment to settle the purchase before asking for it.
                scheduler.schedule(PURCHASE_FLOW_READ_DELAY_MILLIS) { tracker.purchaseFlowFinished() }
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            environment.logger.error({ "subscription lifecycle callback failed" }, t)
        }
    }

    companion object {
        const val PURCHASE_FLOW_READ_DELAY_MILLIS: Long = 1_000

        /** Play Billing's purchase activities (`ProxyBillingActivity`, `ProxyBillingActivityV2`). Name check only. */
        fun isBillingActivity(className: String): Boolean =
            className.startsWith("com.android.billingclient.api.ProxyBillingActivity")
    }
}

/**
 * Started-activity count → "the app came to the foreground". A configuration change (rotation)
 * stops and restarts an activity without leaving the foreground, so it is not counted.
 * Robust to being registered after the first activity started (the count never goes below zero).
 */
internal class ForegroundCounter {
    private var started = 0
    private var restartingAfterConfigChange = 0

    /** Returns true when this start brings the app to the foreground. */
    fun onStarted(): Boolean {
        if (restartingAfterConfigChange > 0) {
            restartingAfterConfigChange -= 1
            return false
        }
        started += 1
        return started == 1
    }

    fun onStopped(changingConfigurations: Boolean) {
        if (changingConfigurations) {
            restartingAfterConfigChange += 1
            return
        }
        if (started > 0) started -= 1
    }
}
