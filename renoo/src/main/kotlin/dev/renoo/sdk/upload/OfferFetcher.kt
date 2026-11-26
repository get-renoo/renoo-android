package dev.renoo.sdk.upload

import dev.renoo.sdk.core.ClearReason
import dev.renoo.sdk.core.PendingOffers
import dev.renoo.sdk.core.RequestIdentity
import dev.renoo.sdk.core.SdkLogger
import dev.renoo.sdk.core.StateOwner
import org.json.JSONObject
import java.net.URL

/**
 * `GET <ingestUrl>/offers`: asks the backend for the user's pending offer. Only for a tracked user
 * (active subscription or trial, i.e. the pipeline's tracking gate is open): nothing about a
 * non-subscriber, not even their anonymous ID, reaches the server.
 *
 * **Triggers**, each at most once every [minIntervalMillis] (6 h) after a success:
 * - start-up, after a non-`401` health check ([onStartup]);
 * - the tracking gate opening later in the run ([onTrackingGateChanged], e.g. the subscription
 *   state resolves to tracked after start-up, or a subscription starts);
 * - the app returning to the foreground ([onForeground]).
 *
 * **Threading: every method runs on the SDK executor**; the request is synchronous there.
 *
 * **Responses.** `200` with a JSON object → the new pending offer. `204` → no offer (cleared).
 * `401` → the kill switch ([onUnauthorized], which calls [stop]). Anything else (`503`, other
 * statuses, a `200` whose body is not a JSON object, network errors) → nothing changes.
 *
 * **Throttle.** Only a `200` or `204` counts: a failed attempt does not start the 6-hour window,
 * so the next trigger tries again. In memory only; [clearState] (opt-out), [onIdentityReset] and
 * the gate closing restart it. Gate and foreground triggers are ignored until the start-up fetch
 * has run, so they never jump ahead of the health check.
 *
 * **Skipped** while [identity] returns `null`: opted out, tracking gate not open (unknown or not
 * tracked), or capture stopped by the kill switch. When the gate closes the offer is cleared:
 * it was meant for a subscriber, and the user no longer is one.
 */
internal class OfferFetcher(
    private val url: URL,
    private val apiKey: String,
    private val transport: HttpTransport,
    private val offers: PendingOffers,
    private val logger: SdkLogger,
    /** Executor. `null` means "don't ask" (see the class KDoc): no request is made at all. */
    private val identity: () -> RequestIdentity?,
    /** A `401`: engages the kill switch. Executor. */
    private val onUnauthorized: () -> Unit,
    /** Monotonic, counting deep sleep (`SystemClock.elapsedRealtime` in production). */
    private val clock: () -> Long,
    private val minIntervalMillis: Long = MIN_INTERVAL_MILLIS,
) : StateOwner {
    private var lastSuccessAt: Long? = null
    private var startupDone = false
    private var stopped = false

    fun onStartup() {
        startupDone = true
        fetch("start_up")
    }

    fun onForeground() = fetchIfDue("foreground")

    /**
     * Executor. The pipeline's tracking gate changed. Open (the user became tracked) → fetch,
     * window permitting. Closed (no longer tracked) → clear the offer and the window; no request.
     */
    fun onTrackingGateChanged(open: Boolean) {
        if (open) {
            fetchIfDue("tracking_started")
        } else {
            lastSuccessAt = null
            offers.set(null)
        }
    }

    private fun fetchIfDue(trigger: String) {
        if (!startupDone) return
        val last = lastSuccessAt
        if (last != null && clock() - last < minIntervalMillis) {
            logger.debug { "offers: skipped ($trigger): fetched less than 6 h ago" }
            return
        }
        fetch(trigger)
    }

    private fun fetch(trigger: String) {
        if (stopped) return
        val who = identity() ?: run {
            logger.debug { "offers: skipped ($trigger): opted out, not tracked or tracking state unknown" }
            return
        }
        val headers = linkedMapOf(
            "Authorization" to "Bearer $apiKey",
            "Accept" to "application/json",
            "X-Renoo-Anonymous-Id" to who.anonymousId,
        )
        who.userId?.let { headers["X-Renoo-User-Id"] = it }
        val response = try {
            transport.send(HttpRequest(url = url, headers = headers, method = "GET"))
        } catch (t: Throwable) {
            logger.debug { "offers: request failed (${t.javaClass.simpleName}); will retry on the next trigger" }
            return
        }
        logger.debug { "offers: trigger=$trigger status=${response.status}" }
        when (response.status) {
            200 -> {
                val offer = try {
                    PendingOffers.freeze(JSONObject(response.body))
                } catch (t: Throwable) {
                    logger.error { "offers: the 200 response is not a JSON object; ignored" }
                    return
                }
                lastSuccessAt = clock()
                offers.set(offer)
            }
            204 -> {
                lastSuccessAt = clock()
                offers.set(null)
            }
            401 -> {
                stop()
                onUnauthorized()
            }
            else -> logger.info { "offers: HTTP ${response.status}; nothing changes, will retry on the next trigger" }
        }
    }

    /** Executor. The kill switch: clear the offer and never fetch again in this process. */
    fun stop() {
        stopped = true
        offers.set(null)
    }

    /** Executor. `Renoo.reset()`: the offer belonged to the previous user; the next foreground asks again. */
    fun onIdentityReset() {
        lastSuccessAt = null
        offers.set(null)
    }

    /** Executor. Opt-out (or restored state): clear the offer and the throttle. */
    override fun clearState(reason: ClearReason) {
        lastSuccessAt = null
        offers.set(null)
    }

    companion object {
        const val MIN_INTERVAL_MILLIS: Long = 6 * 60 * 60_000L
    }
}
