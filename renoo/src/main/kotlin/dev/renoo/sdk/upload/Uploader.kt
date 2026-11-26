package dev.renoo.sdk.upload

import dev.renoo.sdk.core.ClearReason
import dev.renoo.sdk.core.EventStore
import dev.renoo.sdk.core.SdkLogger
import dev.renoo.sdk.core.StateOwner
import dev.renoo.sdk.core.Timestamp
import org.json.JSONObject
import java.net.URL
import java.util.UUID

/** Why a flush was attempted (a flush trigger or a retry). Logged at debug level. */
internal enum class FlushTrigger(val label: String) {
    BACKGROUND("background"),
    QUEUE_SIZE("queue_size"),
    COLD_START("cold_start"),
    FOREGROUND_TIMER("foreground_timer"),
    RETRY("retry"),
    NETWORK_RESTORED("network_restored"),
}

/** What the uploader needs to know about the device's default network. */
internal enum class NetworkKind {
    NONE,
    CELLULAR,

    /** Wi-Fi, wired, or unknown (unknown is treated as "try"). */
    OTHER,
    ;

    companion object {
        /** From `ContextCollector`'s `network_type` values. */
        fun fromNetworkType(type: String?): NetworkKind = when (type) {
            "none" -> NONE
            "cellular" -> CELLULAR
            else -> OTHER
        }
    }
}

/**
 * Sends the on-disk queue to `POST <ingestUrl>/events/sdk`.
 *
 * **Threading: every method runs on the SDK executor** (the caller posts there). The HTTP request
 * is synchronous on that thread, so there is exactly one request in flight per install
 *, and events tracked meanwhile wait in the executor's queue and are appended after
 * the response. Timers fire on [scheduler]'s thread and only post back to the executor.
 *
 * **Queue semantics.** Events are deleted from disk only after
 * a `202` (accepted and rejected alike), a `400`, or a `413` for a single event, or by the kill
 * switch after a `401` (see `KillSwitch`: the whole queue is dropped). Everything else keeps them. A kill mid-request leaves them on disk; they are sent again at the next cold start
 * and the backend dedupes by `event_id`.
 *
 * **Batch identity.** A batch is cut from the oldest queued events and remembered in
 * memory by its event IDs and `X-Renoo-Batch-Id`; a retry re-reads those same lines and reuses the
 * ID, with a fresh `X-Renoo-Sent-At`. A `413` (or a gzip body over the limit, detected before sending)
 * splits it in two new batches. The batch, the backoff state and the 401 stop are in memory
 * only: a cold start begins afresh.
 */
internal class Uploader(
    private val endpoint: URL,
    private val apiKey: String,
    private val settings: UploadSettings,
    private val store: () -> EventStore,
    private val isOptedOut: () -> Boolean,
    private val network: () -> NetworkKind,
    private val transport: HttpTransport,
    private val scheduler: Scheduler,
    /** Runs a block on the SDK executor (tests run it inline). */
    private val postToExecutor: (() -> Unit) -> Unit,
    private val logger: SdkLogger,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** Uniform in [0, 1). */
    private val random: () -> Double = Math::random,
    private val newBatchId: () -> String = { UUID.randomUUID().toString().lowercase() },
    /** A `401`: engages the kill switch (which calls [stop] and drops the queue). Executor. */
    private val onUnauthorized: () -> Unit = {},
) : StateOwner {
    private class Batch(val id: String, val eventIds: List<String>)

    private val pending = ArrayDeque<Batch>()
    private var stoppedUntilColdStart = false
    private var consecutiveFailures = 0
    private var nextAttemptAt = 0L
    private var retryTimer: Cancellable? = null
    private var foregroundTimer: Cancellable? = null
    private var inForeground = false
    private var deferredForNetwork = false
    private var inFlight = false
    private var queueFlushPosted = false

    // region Triggers (executor)

    /** Flush trigger: cold start with a non-empty queue. */
    fun onColdStart() {
        if (store().count > 0) flush(FlushTrigger.COLD_START)
    }

    /** Flush trigger: going to the background; the 5-minute timer runs only while in the foreground. */
    fun onForegroundChanged(foreground: Boolean) {
        if (foreground == inForeground) return
        inForeground = foreground
        if (foreground) {
            scheduleForegroundTimer()
        } else {
            foregroundTimer?.cancel()
            foregroundTimer = null
            flush(FlushTrigger.BACKGROUND)
        }
    }

    /**
     * Flush trigger: called after an event is appended, with the queue's size. The flush is posted
     * (at most one outstanding), so no request runs in the middle of the pipeline's commit.
     */
    fun onQueueSize(count: Int) {
        if (count < settings.flushAtQueueSize || queueFlushPosted) return
        queueFlushPosted = true
        postToExecutor {
            queueFlushPosted = false
            flush(FlushTrigger.QUEUE_SIZE)
        }
    }

    /** A flush skipped for lack of network runs when the network comes back. */
    fun onNetworkChanged(kind: NetworkKind) {
        if (kind != NetworkKind.NONE && deferredForNetwork) {
            deferredForNetwork = false
            flush(FlushTrigger.NETWORK_RESTORED)
        }
    }

    private fun scheduleForegroundTimer() {
        foregroundTimer?.cancel()
        foregroundTimer = scheduler.schedule(settings.foregroundFlushIntervalMillis) {
            postToExecutor { onForegroundTimer() }
        }
    }

    private fun onForegroundTimer() {
        foregroundTimer = null
        if (!inForeground) return
        scheduleForegroundTimer()
        if (store().count > 0) flush(FlushTrigger.FOREGROUND_TIMER)
    }

    // endregion

    /** One attempt: at most one request. Continues (posted) while a backlog remains. */
    fun flush(trigger: FlushTrigger) {
        if (inFlight) return
        if (stoppedUntilColdStart) {
            logger.debug { "upload: skipped (${trigger.label}): stopped by the kill switch (401) until the next cold start" }
            return
        }
        if (isOptedOut()) {
            pending.clear()
            return
        }
        if (monotonicClock() + CLOCK_TOLERANCE_MILLIS < nextAttemptAt) {
            logger.debug { "upload: skipped (${trigger.label}): backing off" }
            return
        }
        val queue = store()
        if (queue.count == 0 && pending.isEmpty()) return
        val kind = network()
        if (kind == NetworkKind.NONE) {
            deferredForNetwork = true
            logger.debug { "upload: skipped (${trigger.label}): no network" }
            return
        }
        if (trigger == FlushTrigger.FOREGROUND_TIMER && kind == NetworkKind.CELLULAR && queue.count < settings.flushAtQueueSize) {
            logger.debug { "upload: skipped (${trigger.label}): cellular and fewer than ${settings.flushAtQueueSize} events" }
            return
        }
        val (batch, lines) = nextBatch(queue) ?: return
        send(batch, lines, queue, trigger)
    }

    private fun nextBatch(queue: EventStore): Pair<Batch, List<EventStore.QueuedLine>>? {
        while (pending.isNotEmpty()) {
            val batch = pending.first()
            val lines = queue.linesFor(batch.eventIds)
            if (lines.isNotEmpty()) return batch to lines
            pending.removeFirst() // Its events are gone (e.g. trimmed when the queue was full).
        }
        val lines = queue.oldest(settings.maxBatchEvents, settings.maxDecompressedBytes)
        if (lines.isEmpty()) {
            if (queue.count > 0) queue.remove(emptySet()) // Only unreadable lines left: drop them.
            return null
        }
        val batch = Batch(newBatchId(), lines.map { it.eventId })
        pending.addLast(batch)
        return batch to lines
    }

    private fun send(batch: Batch, lines: List<EventStore.QueuedLine>, queue: EventStore, trigger: FlushTrigger) {
        val raw = BatchEncoder.ndjson(lines.map { it.line })
        val body = BatchEncoder.gzip(raw)
        if (body.size > settings.maxCompressedBytes && lines.size > 1) {
            logger.debug { "upload: batch of ${lines.size} is ${body.size} bytes gzipped; splitting before sending" }
            split(lines)
            postToExecutor { flush(trigger) }
            return
        }
        // Events present now but not in this batch: a backlog to drain right after a success.
        // Events appended during the request are not counted (they wait for the next flush).
        val backlog = queue.count > lines.size
        val request = HttpRequest(
            url = endpoint,
            headers = linkedMapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/x-ndjson",
                "Content-Encoding" to "gzip",
                "X-Renoo-Sent-At" to Timestamp.format(wallClock()),
                "X-Renoo-Batch-Id" to batch.id,
            ),
            body = body,
        )
        val started = monotonicClock()
        inFlight = true
        val response = try {
            transport.send(request)
        } catch (t: Throwable) {
            logger.debug { "upload: request failed: ${t.javaClass.simpleName}" }
            null
        } finally {
            inFlight = false
        }
        val latency = monotonicClock() - started
        logger.debug {
            "upload: trigger=${trigger.label} events=${lines.size} bytes=${raw.size} gzip=${body.size} " +
                "status=${response?.status ?: "error"} latency=${latency}ms batch=${batch.id.take(8)}"
        }
        if (response == null) {
            failed(trigger, "network error or timeout")
            return
        }
        val ids = lines.mapTo(HashSet()) { it.eventId }
        when (val status = response.status) {
            202 -> {
                logRejected(response.body)
                queue.remove(ids)
                pending.remove(batch)
                resetBackoff()
                if (backlog || pending.isNotEmpty()) postToExecutor { flush(trigger) }
            }
            400 -> {
                logger.error { "upload: the server could not read a batch (400); its ${lines.size} events were deleted" }
                queue.remove(ids)
                pending.remove(batch)
                if (backlog || pending.isNotEmpty()) postToExecutor { flush(trigger) }
            }
            401 -> {
                // The kill switch: stop here, then let it drop the queue and stop capture and offers.
                stop()
                onUnauthorized()
            }
            413 -> {
                if (lines.size == 1) {
                    logger.error { "upload: an event of ${raw.size} bytes is over the server's limits (413) and was deleted (event_id ${lines[0].eventId})" }
                    queue.remove(ids)
                    pending.remove(batch)
                } else {
                    logger.debug { "upload: 413 for ${lines.size} events; splitting the batch" }
                    split(lines)
                }
                if (backlog || pending.isNotEmpty()) postToExecutor { flush(trigger) }
            }
            429 -> {
                // Seconds, integer or decimal; an HTTP-date or anything else falls back to backoff.
                val seconds = response.retryAfter?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                if (seconds != null) {
                    val delay = (seconds * 1000).toLong().coerceIn(1000, settings.backoffCapMillis)
                    logger.debug { "upload: rate limited (429); retrying in ${delay / 1000} s" }
                    scheduleRetry(delay)
                } else {
                    failed(trigger, "rate limited (429) without Retry-After")
                }
            }
            // Any other status, including 403: the API never returns 403 (a blocked app gets
            // 401), so a 403 is just an unexpected answer: keep the batch and back off.
            else -> failed(trigger, "HTTP $status")
        }
    }

    /** Replaces the front batch with its two halves, each with a new batch ID. */
    private fun split(lines: List<EventStore.QueuedLine>) {
        if (pending.isNotEmpty()) pending.removeFirst()
        val half = (lines.size + 1) / 2
        pending.addFirst(Batch(newBatchId(), lines.drop(half).map { it.eventId }))
        pending.addFirst(Batch(newBatchId(), lines.take(half).map { it.eventId }))
    }

    private fun failed(trigger: FlushTrigger, reason: String) {
        consecutiveFailures += 1
        val delay = Backoff.delayMillis(consecutiveFailures, settings, random())
        logger.info { "upload failed ($reason, trigger ${trigger.label}); retry #$consecutiveFailures in ${delay / 1000} s" }
        scheduleRetry(delay)
    }

    private fun scheduleRetry(delayMillis: Long) {
        nextAttemptAt = monotonicClock() + delayMillis
        retryTimer?.cancel()
        retryTimer = scheduler.schedule(delayMillis) {
            postToExecutor {
                retryTimer = null
                flush(FlushTrigger.RETRY)
            }
        }
    }

    private fun resetBackoff() {
        consecutiveFailures = 0
        nextAttemptAt = 0
        retryTimer?.cancel()
        retryTimer = null
    }

    private fun cancelTimers() {
        retryTimer?.cancel()
        retryTimer = null
        foregroundTimer?.cancel()
        foregroundTimer = null
    }

    /** Rejected events are permanently invalid; they are deleted with the rest and logged. */
    private fun logRejected(body: String) {
        try {
            val rejected = JSONObject(body).optJSONArray("rejected") ?: return
            if (rejected.length() == 0) return
            val reasons = (0 until rejected.length())
                .mapNotNull { rejected.optJSONObject(it)?.optString("reason") }
                .groupingBy { it }
                .eachCount()
            logger.error { "upload: the server rejected ${rejected.length()} events as invalid (${reasons.entries.joinToString { "${it.key}: ${it.value}" }}); they were deleted" }
            logger.debug {
                "upload: rejected event_ids: " + (0 until rejected.length()).mapNotNull { rejected.optJSONObject(it)?.optString("event_id") }.joinToString()
            }
        } catch (t: Throwable) {
            // A 202 whose body can't be read still means "stored".
        }
    }

    /**
     * Executor. The kill switch: forget batches and timers and ignore every trigger until the next
     * cold start (a new process makes a new uploader). The queue itself is deleted by the pipeline.
     */
    fun stop() {
        stoppedUntilColdStart = true
        pending.clear()
        cancelTimers()
        deferredForNetwork = false
    }

    /** Executor (opt-out or restore). Forgets in-memory batches and backoff; the queue is cleared by the pipeline. */
    override fun clearState(reason: ClearReason) {
        pending.clear()
        resetBackoff()
        deferredForNetwork = false
    }

    // region Test inspection

    internal val pendingBatchCount: Int get() = pending.size
    internal val isStopped: Boolean get() = stoppedUntilColdStart
    internal val failures: Int get() = consecutiveFailures

    // endregion

    companion object {
        /** Timers and the clock may disagree by a millisecond or two. */
        const val CLOCK_TOLERANCE_MILLIS: Long = 10
    }
}
