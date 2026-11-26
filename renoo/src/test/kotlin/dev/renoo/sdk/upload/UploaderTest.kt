package dev.renoo.sdk.upload

import android.util.Log
import dev.renoo.sdk.core.ClearReason
import dev.renoo.sdk.core.EventStore
import dev.renoo.sdk.core.Timestamp
import dev.renoo.sdk.upload.FakeTransport.Companion.accepted
import dev.renoo.sdk.upload.FakeTransport.Companion.networkError
import dev.renoo.sdk.upload.FakeTransport.Companion.status
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Uploader rules (batching, backoff, flush triggers) against every upload response status, with a fake clock, scheduler and transport. */
class UploaderTest {
    private val h = UploaderHarness()
    private val sent get() = h.transport.sent

    @After
    fun tearDown() {
        // LogRedaction: the key never reaches the logs, at any level.
        assertFalse("API key leaked into logs", h.logs.any { it.second.contains(h.apiKey) })
        // Payloads never at info level or above.
        assertFalse("payload logged at info+", h.logs.any { it.first >= Log.INFO && it.second.contains("\"event_id\"") })
        h.tearDown()
    }

    private fun errors() = h.logs.filter { it.first == Log.ERROR }.map { it.second }

    // region Request shape

    @Test
    fun requestCarriesAuthContentTypeGzipSentAtAndBatchId() {
        h.append(3)
        h.now = 12_345
        h.uploader.flush(FlushTrigger.BACKGROUND)
        val request = sent.single()
        assertEquals("https://ingest.example.com/events/sdk", request.request.url.toString())
        assertEquals("Bearer ${h.apiKey}", request.headers["Authorization"])
        assertEquals("application/x-ndjson", request.headers["Content-Type"])
        assertEquals("gzip", request.headers["Content-Encoding"])
        assertEquals(Timestamp.format(UploaderHarness.WALL_START + 12_345), request.sentAt)
        assertTrue(request.sentAt.matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z""")))
        assertTrue(request.batchId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        assertFalse("toString must not expose headers", request.request.toString().contains("Bearer"))
    }

    @Test
    fun bodyIsTheQueuedLinesAsGzippedNdjson() {
        h.append(4)
        val onDisk = File(h.directory, EventStore.FILE_NAME).readText()
        h.uploader.flush(FlushTrigger.BACKGROUND)
        // Byte for byte what is on disk: one event per line, each ending in "\n".
        assertEquals(onDisk, sent.single().ndjson)
        assertEquals(4, sent.single().lines.size)
        assertEquals(0x1f, sent.single().request.body[0].toInt() and 0xff) // gzip magic
        assertEquals(0x8b, sent.single().request.body[1].toInt() and 0xff)
    }

    // endregion

    // region Batches

    @Test
    fun batchesAreCutAt500OldestFirstAndDrainedOneRequestAtATime() {
        val ids = h.append(1_200)
        h.uploader.onColdStart()
        h.drain()
        assertEquals(listOf(500, 500, 200), sent.map { it.lines.size })
        assertEquals(ids, sent.flatMap { it.eventIds })
        assertEquals(3, sent.map { it.batchId }.toSet().size)
        assertTrue(h.queuedIds().isEmpty())
    }

    @Test
    fun batchesRespectTheDecompressedLimit() {
        val small = UploaderHarness(UploadSettings(maxDecompressedBytes = 10_000))
        try {
            small.append(5, propertyBytes = 3_000)
            small.uploader.onColdStart()
            small.drain()
            assertTrue(small.transport.sent.all { it.ndjson.toByteArray().size <= 10_000 })
            assertEquals(5, small.transport.sent.sumOf { it.lines.size })
        } finally {
            small.tearDown()
        }
    }

    @Test
    fun gzipBodyOverTheLimitIsSplitBeforeSending() {
        val small = UploaderHarness(UploadSettings(maxCompressedBytes = 4_000))
        try {
            small.append(8, propertyBytes = 1_000)
            small.uploader.onColdStart()
            small.drain()
            assertTrue(small.transport.sent.all { it.request.body.size <= 4_000 })
            assertEquals(8, small.transport.sent.sumOf { it.lines.size })
            assertTrue(small.queuedIds().isEmpty())
        } finally {
            small.tearDown()
        }
    }

    @Test
    fun retryReusesTheBatchIdAndRefreshesSentAt() {
        val ids = h.append(5)
        h.transport.responder = { if (sent.size == 1) status(500) else accepted(it.lines.size) }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        h.append(2) // Appended while backing off: not part of the retried batch.
        h.advance(60 * 60_000L)
        assertEquals(listOf(5, 5, 2), sent.map { it.lines.size }.take(3))
        assertEquals(sent[0].batchId, sent[1].batchId)
        assertEquals(ids, sent[1].eventIds)
        assertNotEquals(sent[0].sentAt, sent[1].sentAt)
        assertNotEquals(sent[1].batchId, sent[2].batchId)
        assertTrue(h.queuedIds().isEmpty())
    }

    @Test
    fun onlyOneRequestIsInFlight() {
        h.append(3)
        h.transport.responder = {
            // Triggers arriving during the request (re-entrantly) must not start a second one.
            h.uploader.flush(FlushTrigger.BACKGROUND)
            h.uploader.onColdStart()
            accepted(it.lines.size)
        }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        h.drain()
        assertEquals(1, sent.size)
    }

    @Test
    fun eventsAppendedDuringARequestWaitForTheNextFlush() {
        h.append(3)
        var late = emptyList<String>()
        h.transport.responder = {
            if (sent.size == 1) late = h.append(1)
            accepted(it.lines.size)
        }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        h.drain()
        assertEquals(1, sent.size)
        assertEquals(late, h.queuedIds())
        h.uploader.flush(FlushTrigger.BACKGROUND)
        assertEquals(late, sent[1].eventIds)
    }

    // endregion

    // region Response statuses

    @Test
    fun accepted202DeletesTheSentEvents() {
        h.append(3)
        h.uploader.flush(FlushTrigger.BACKGROUND)
        assertTrue(h.queuedIds().isEmpty())
        assertTrue(errors().isEmpty())
    }

    @Test
    fun accepted202WithRejectedDeletesAcceptedAndRejectedAlikeAndLogs() {
        val ids = h.append(3)
        h.transport.responder = { accepted(2, """[{"event_id": "${ids[1]}", "reason": "invalid_envelope"}]""") }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        assertTrue(h.queuedIds().isEmpty())
        assertTrue(errors().any { it.contains("rejected 1") && it.contains("invalid_envelope") })
        // Never resent.
        h.uploader.flush(FlushTrigger.BACKGROUND)
        assertEquals(1, sent.size)
    }

    @Test
    fun accepted202WithAnUnreadableBodyStillDeletes() {
        h.append(2)
        h.transport.responder = { HttpResponse(202, null, "not json") }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        assertTrue(h.queuedIds().isEmpty())
    }

    @Test
    fun badRequest400DeletesTheBatchLogsAndDoesNotRetry() {
        h.append(3)
        h.transport.responder = { status(400) }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        assertTrue(h.queuedIds().isEmpty())
        assertTrue(errors().any { it.contains("400") })
        assertTrue(h.scheduler.liveTasks.isEmpty())
    }

    @Test
    fun unauthorized401EngagesTheKillSwitchAndStopsUntilTheNextColdStart() {
        h.append(3)
        h.transport.responder = { status(401) }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        assertTrue(h.uploader.isStopped)
        assertEquals(1, h.unauthorizedCalls)
        assertEquals(0, h.uploader.pendingBatchCount)
        // Every trigger is ignored, and no timer is left behind.
        h.append(200)
        h.uploader.onQueueSize(203)
        h.uploader.onForegroundChanged(true)
        h.uploader.onForegroundChanged(false)
        h.uploader.onNetworkChanged(NetworkKind.OTHER)
        h.advance(24 * 60 * 60_000L)
        assertEquals(1, sent.size)
        assertTrue(h.scheduler.liveTasks.isEmpty())
        // Next cold start: a new uploader sends again.
        h.transport.responder = { accepted(it.lines.size) }
        val next = h.makeUploader()
        next.onColdStart()
        h.drain()
        assertEquals(2, sent.size)
        assertTrue(h.queuedIds().isEmpty())
    }

    @Test
    fun stopFromTheKillSwitchCancelsTimersAndIgnoresTriggers() {
        h.append(3)
        h.transport.responder = { status(503) }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        h.uploader.onForegroundChanged(true)
        assertTrue(h.scheduler.liveTasks.isNotEmpty())
        h.uploader.stop()
        assertTrue(h.scheduler.liveTasks.isEmpty())
        h.uploader.flush(FlushTrigger.BACKGROUND)
        assertEquals(1, sent.size)
        assertEquals(0, h.unauthorizedCalls)
    }

    @Test
    fun forbidden403IsAnOrdinaryFailure() {
        // The API never returns 403: it backs off and retries like any unexpected status.
        val ids = h.append(3)
        h.transport.responder = { status(403) }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        assertFalse(h.uploader.isStopped)
        assertEquals(1, h.uploader.failures)
        assertEquals(ids, h.queuedIds())
        assertEquals(1, h.scheduler.liveTasks.size)
        h.transport.responder = { accepted(it.lines.size) }
        h.advance(60 * 60_000L)
        assertTrue(h.queuedIds().isEmpty())
    }

    @Test
    fun payloadTooLarge413SplitsTheBatchWithNewBatchIds() {
        h.append(4)
        h.transport.responder = { if (it.lines.size > 2) status(413) else accepted(it.lines.size) }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        h.drain()
        assertEquals(listOf(4, 2, 2), sent.map { it.lines.size })
        assertEquals(3, sent.map { it.batchId }.toSet().size)
        assertTrue(h.queuedIds().isEmpty())
    }

    @Test
    fun aSingle40KbEventThatStillGets413IsDroppedAndLogged() {
        // An oversized event is rejected, not retried forever. The server's 32 KB per-line limit is simulated by the fake.
        val before = h.append(1)
        val big = h.append(1, propertyBytes = 40 * 1024)
        val after = h.append(1)
        h.transport.responder = { req ->
            if (req.lines.any { it.toByteArray().size > 32 * 1024 }) status(413) else accepted(req.lines.size)
        }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        h.drain()
        assertTrue(h.queuedIds().isEmpty())
        val delivered = sent.filter { it.lines.size == 1 && !it.eventIds.containsAll(big) }.flatMap { it.eventIds }
        assertEquals((before + after).toSet(), delivered.toSet())
        assertTrue(errors().any { it.contains("413") && it.contains(big.single()) })
    }

    @Test
    fun rateLimited429WaitsRetryAfter() {
        // Retry-After 120 delays the next attempt by 120 s.
        h.append(3)
        h.transport.responder = { if (sent.size == 1) status(429, retryAfter = "120") else accepted(it.lines.size) }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        h.uploader.flush(FlushTrigger.BACKGROUND) // other triggers inside the window are ignored
        h.advance(119_000)
        assertEquals(1, sent.size)
        h.advance(1_000)
        assertEquals(2, sent.size)
        assertEquals(120_000, sent[1].at - sent[0].at)
        assertEquals(sent[0].batchId, sent[1].batchId)
        assertTrue(h.queuedIds().isEmpty())
    }

    @Test
    fun retryAfterAcceptsDecimalsIsClampedToTheCapAndIgnoresDates() {
        h.append(1)
        var retryAfter = "1.5"
        h.transport.responder = { if (sent.size < 3) status(429, retryAfter) else accepted(it.lines.size) }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        retryAfter = "999999"
        h.advance(1_500)
        assertEquals(2, sent.size)
        assertEquals(1_500, sent[1].at - sent[0].at)
        h.advance(3_600_000)
        assertEquals("clamped to the 1 h cap", 3, sent.size)
        assertEquals(3_600_000, sent[2].at - sent[1].at)
        // An HTTP-date is not understood: normal backoff, counted as a failure.
        val dated = UploaderHarness()
        try {
            dated.append(1)
            dated.transport.responder = { status(429, "Wed, 21 Oct 2026 07:28:00 GMT") }
            dated.uploader.flush(FlushTrigger.BACKGROUND)
            assertEquals(1, dated.uploader.failures)
        } finally {
            dated.tearDown()
        }
    }

    @Test
    fun otherSuccessCodesAreNotTreatedAs202() {
        val ids = h.append(1)
        h.transport.responder = { HttpResponse(200, null, "{}") }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        assertEquals(ids, h.queuedIds())
        assertEquals(1, h.uploader.failures)
    }

    @Test
    fun rateLimited429WithoutRetryAfterBacksOff() {
        h.append(1)
        h.random = 0.0
        h.transport.responder = { if (sent.size == 1) status(429) else accepted(it.lines.size) }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        assertEquals(1, h.uploader.failures)
        h.advance(15_000) // base 30 s, equal jitter floor 15 s
        assertEquals(2, sent.size)
    }

    @Test
    fun serverError5xxKeepsTheBatchAndBacksOffExponentiallyToTheCap() {
        h.append(3)
        h.random = 1.0 // longest delays
        h.transport.responder = { status(503) }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        h.advance(6 * 60 * 60_000L)
        val gaps = sent.zipWithNext { a, b -> b.at - a.at }
        assertEquals(
            listOf(30_000L, 60_000, 120_000, 240_000, 480_000, 960_000, 1_920_000, 3_600_000, 3_600_000),
            gaps.take(9),
        )
        assertTrue(gaps.all { it <= 3_600_000 })
        assertEquals(3, h.queuedIds().size)
        assertEquals(1, sent.map { it.batchId }.toSet().size)
    }

    @Test
    fun backoffNeverBusyLoops_atMost12AttemptsInTheFirstHour() {
        // At most 12 attempts in the first hour, worst case: every random draw at its minimum.
        for (random in listOf(0.0, 0.5, 0.999)) {
            val run = UploaderHarness()
            try {
                run.random = random
                run.append(1)
                run.transport.responder = { status(500) }
                run.uploader.flush(FlushTrigger.BACKGROUND)
                run.advance(60 * 60_000L)
                val attempts = run.transport.sent.count { it.at <= 60 * 60_000L }
                assertTrue("random=$random: $attempts attempts", attempts in 2..12)
            } finally {
                run.tearDown()
            }
        }
    }

    @Test
    fun backoffResetsAfterA202() {
        h.append(1)
        h.random = 0.0
        var fail = true
        h.transport.responder = { if (fail) status(500) else accepted(it.lines.size) }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        h.advance(15_000)
        h.advance(30_000)
        assertEquals(3, h.uploader.failures)
        fail = false
        h.advance(60_000)
        assertEquals(0, h.uploader.failures)
        assertTrue(h.scheduler.liveTasks.isEmpty())
        // The next failure starts from the base again.
        fail = true
        h.append(1)
        h.uploader.flush(FlushTrigger.BACKGROUND)
        assertEquals(1, h.uploader.failures)
    }

    @Test
    fun networkErrorsAndTimeoutsBackOffAndKeepTheQueue() {
        val ids = h.append(2)
        h.transport.responder = { networkError() }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        assertEquals(1, h.uploader.failures)
        assertEquals(ids, h.queuedIds())
        assertEquals(1, h.scheduler.liveTasks.size)
    }

    @Test
    fun unexpectedStatusesAreTreatedAsTransient() {
        val ids = h.append(2)
        h.transport.responder = { status(404) }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        assertEquals(ids, h.queuedIds())
        assertEquals(1, h.uploader.failures)
    }

    @Test
    fun aKillMidRequestLosesNothing() {
        // No event lost when the process dies mid-request, at unit level: the request never completes, the process dies, the next one resends.
        val ids = h.append(3)
        h.transport.responder = { networkError() }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        h.transport.responder = { accepted(it.lines.size) }
        val nextProcess = h.makeUploader()
        nextProcess.onColdStart()
        h.drain()
        assertEquals(ids, sent.last().eventIds)
        assertNotEquals(sent.first().batchId, sent.last().batchId)
        assertTrue(h.queuedIds().isEmpty())
    }

    // endregion

    // region Network, opt-out

    @Test
    fun noNetworkMeansNoAttemptAndTheFlushRunsWhenItReturns() {
        h.network = NetworkKind.NONE
        h.append(3)
        h.uploader.flush(FlushTrigger.BACKGROUND)
        h.uploader.onColdStart()
        assertTrue(sent.isEmpty())
        h.network = NetworkKind.OTHER
        h.uploader.onNetworkChanged(NetworkKind.OTHER)
        assertEquals(1, sent.size)
        assertTrue(h.queuedIds().isEmpty())
    }

    @Test
    fun noRetriesWhileOffline() {
        h.append(1)
        h.transport.responder = { status(500) }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        h.network = NetworkKind.NONE
        h.advance(3 * 60 * 60_000L)
        assertEquals(1, sent.size)
        h.transport.responder = { accepted(it.lines.size) }
        h.network = NetworkKind.CELLULAR
        h.uploader.onNetworkChanged(NetworkKind.CELLULAR)
        assertEquals(2, sent.size)
        assertTrue(h.queuedIds().isEmpty())
    }

    @Test
    fun optedOutSendsNothing() {
        h.append(150)
        h.optedOut = true
        h.uploader.onColdStart()
        h.uploader.onQueueSize(150)
        h.uploader.onForegroundChanged(true)
        h.advance(10 * 60_000L)
        h.uploader.onForegroundChanged(false)
        h.uploader.onNetworkChanged(NetworkKind.OTHER)
        h.drain()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun optOutForgetsThePendingBatchAndBackoff() {
        h.append(2)
        h.transport.responder = { status(500) }
        h.uploader.flush(FlushTrigger.BACKGROUND)
        assertEquals(1, h.uploader.pendingBatchCount)
        h.optedOut = true
        h.uploader.clearState(ClearReason.OPT_OUT)
        h.store.clear() // what the pipeline does on opt-out
        assertEquals(0, h.uploader.pendingBatchCount)
        assertEquals(0, h.uploader.failures)
        h.advance(2 * 60 * 60_000L)
        assertEquals(1, sent.size)
    }

    // endregion

    // region Flush triggers and the cellular rule

    @Test
    fun coldStartFlushesOnlyANonEmptyQueue() {
        h.uploader.onColdStart()
        assertTrue(sent.isEmpty())
        h.append(1)
        h.uploader.onColdStart()
        assertEquals(1, sent.size)
    }

    @Test
    fun goingToTheBackgroundFlushes() {
        h.append(2)
        h.uploader.onForegroundChanged(true)
        assertTrue(sent.isEmpty())
        h.uploader.onForegroundChanged(false)
        assertEquals(1, sent.size)
    }

    @Test
    fun reaching100EventsFlushesOnce() {
        h.append(99)
        h.uploader.onQueueSize(99)
        h.drain()
        assertTrue(sent.isEmpty())
        h.append(1)
        h.uploader.onQueueSize(100)
        h.uploader.onQueueSize(100) // a second commit before the posted flush ran
        h.drain()
        assertEquals(listOf(100), sent.map { it.lines.size })
    }

    @Test
    fun foregroundTimerFlushesEvery5MinutesWhileQueueIsNonEmpty() {
        h.uploader.onForegroundChanged(true)
        h.advance(5 * 60_000L)
        assertTrue("empty queue: no request", sent.isEmpty())
        h.append(3)
        h.advance(4 * 60_000L)
        assertTrue(sent.isEmpty())
        h.advance(60_000L)
        assertEquals(1, sent.size)
        h.append(1)
        h.advance(5 * 60_000L)
        assertEquals(2, sent.size)
        // Background: flush, then the timer stops.
        h.uploader.onForegroundChanged(false)
        h.append(1)
        h.advance(30 * 60_000L)
        assertEquals(2, sent.size)
    }

    @Test
    fun onCellularTheTimerFlushNeeds100EventsButBackgroundAndColdStartAlwaysRun() {
        h.network = NetworkKind.CELLULAR
        h.uploader.onForegroundChanged(true)
        h.append(5)
        h.advance(5 * 60_000L)
        assertTrue(sent.isEmpty())
        h.append(95)
        h.advance(5 * 60_000L)
        assertEquals(listOf(100), sent.map { it.lines.size })

        h.append(1)
        h.uploader.onForegroundChanged(false)
        assertEquals(2, sent.size)

        h.append(1)
        h.makeUploader().onColdStart()
        assertEquals(3, sent.size)
    }

    // endregion
}
