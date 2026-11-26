package dev.renoo.sdk.core

import dev.renoo.sdk.EventName
import dev.renoo.sdk.EventObserver
import dev.renoo.sdk.LogLevel
import dev.renoo.sdk.TrackingScope
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections

/** Ports of the iOS `EventPipelineTests`, plus Android-specific checks. Plain JVM, no Robolectric. */
class EventPipelineTest {
    private val harness = PipelineHarness()

    @After
    fun tearDown() = harness.tearDown()

    @Test
    fun eventsArePersistedInOrderWithIncreasingSeq() {
        val pipeline = harness.makePipeline()
        pipeline.setSessionId("s1")
        pipeline.track(EventName.APP_OPEN, mapOf("start_type" to "cold"))
        pipeline.track(EventName.SCREEN_VIEW, mapOf("screen_name" to "Home"))
        val events = pipeline.queuedEvents()
        assertEquals(listOf(EventName.APP_OPEN, EventName.SCREEN_VIEW), events.map { it.name })
        assertEquals(listOf(1L, 2L), events.map { it.seq })
        assertEquals(listOf("s1", "s1"), events.map { it.sessionId })
        assertEquals("install-1", events[0].installId)
        assertEquals("android", events[0].platform)
        assertEquals("cold", events[0].properties["start_type"])

        // A new pipeline on the same disk and prefs continues the queue and the sequence.
        val second = harness.makePipeline()
        second.track(EventName.APP_BACKGROUND)
        assertEquals(listOf(1L, 2L, 3L), second.queuedEvents().map { it.seq })
    }

    @Test
    fun eventsWaitUntilTheUserIsKnownToBeTracked() {
        val pipeline = harness.makePipeline(TrackingScope.SUBSCRIBERS_ONLY)
        pipeline.track(EventName.APP_OPEN)
        assertTrue(pipeline.queuedEvents().isEmpty())
        pipeline.setTrackingState(TrackingState.TRACKED)
        pipeline.track(EventName.SCREEN_VIEW)
        assertEquals(listOf(EventName.APP_OPEN, EventName.SCREEN_VIEW), pipeline.queuedEvents().map { it.name })
        assertEquals(listOf(1L, 2L), pipeline.queuedEvents().map { it.seq })
    }

    @Test
    fun nothingIsStoredForNonSubscribers() {
        val pipeline = harness.makePipeline(TrackingScope.SUBSCRIBERS_ONLY)
        pipeline.track(EventName.APP_OPEN)
        pipeline.setTrackingState(TrackingState.NOT_TRACKED)
        pipeline.track(EventName.SCREEN_VIEW)
        assertTrue(pipeline.queuedEvents().isEmpty())
        // Becoming tracked later does not resurrect dropped events.
        pipeline.setTrackingState(TrackingState.TRACKED)
        assertTrue(pipeline.queuedEvents().isEmpty())
    }

    /** Reads [EventPipeline.identityForRequests] on the executor, where it must run. */
    private fun EventPipeline.requestIdentity(executor: SdkExecutor): RequestIdentity? {
        var result: RequestIdentity? = null
        executor.post { result = identityForRequests() }
        waitUntilIdle()
        return result
    }

    @Test
    fun offerRequestsNeedTheUserToBeKnownToBeTracked() {
        val executor = SdkExecutor(harness.logger, threadName = "test-sdk")
        val pipeline = harness.makePipeline(TrackingScope.SUBSCRIBERS_ONLY, executor = executor)
        val gate = mutableListOf<Boolean>()
        pipeline.trackingGateListener = { gate.add(it) }
        assertNull("unknown state", pipeline.requestIdentity(executor))
        pipeline.setTrackingState(TrackingState.NOT_TRACKED)
        assertNull("not tracked", pipeline.requestIdentity(executor))
        assertEquals(listOf(false), gate)
        pipeline.setTrackingState(TrackingState.TRACKED)
        assertEquals(pipeline.currentAnonymousId, pipeline.requestIdentity(executor)?.anonymousId)
        assertEquals(listOf(false, true), gate)
        pipeline.setTrackingState(TrackingState.NOT_TRACKED)
        assertNull("no longer tracked", pipeline.requestIdentity(executor))
        assertEquals(listOf(false, true, false), gate)
    }

    @Test
    fun memoryBufferKeepsTheNewest500() {
        val pipeline = harness.makePipeline(TrackingScope.SUBSCRIBERS_ONLY)
        repeat(EventPipeline.MAX_BUFFERED_EVENTS + 20) { pipeline.track(EventName.SCREEN_VIEW, mapOf("i" to it)) }
        pipeline.setTrackingState(TrackingState.TRACKED)
        val events = pipeline.queuedEvents()
        assertEquals(EventPipeline.MAX_BUFFERED_EVENTS, events.size)
        assertEquals(20, events.first().properties["i"])
        assertEquals(EventPipeline.MAX_BUFFERED_EVENTS + 19, events.last().properties["i"])
    }

    @Test
    fun trackingStateIsIgnoredWhenTrackingEveryone() {
        val pipeline = harness.makePipeline(TrackingScope.EVERYONE)
        pipeline.setTrackingState(TrackingState.NOT_TRACKED)
        pipeline.track(EventName.APP_OPEN)
        assertEquals(1, pipeline.queuedEvents().size)
    }

    @Test
    fun sessionOverrideAndPastTimestamp() {
        val pipeline = harness.makePipeline()
        pipeline.setSessionId("current")
        pipeline.track(EventName.SESSION_END, timestampMillis = 1_700_000_000_000L, sessionId = SessionOverride.Explicit("old"))
        pipeline.track(EventName.APP_OPEN, sessionId = SessionOverride.Explicit(null))
        pipeline.track(EventName.APP_BACKGROUND)
        val events = pipeline.queuedEvents()
        assertEquals("old", events[0].sessionId)
        assertEquals("2023-11-14T22:13:20.000Z", events[0].timestamp)
        assertNull(events[1].sessionId)
        assertEquals("current", events[2].sessionId)
    }

    @Test
    fun identifyResetAndOptOut() {
        val pipeline = harness.makePipeline()
        pipeline.identify("user-1")
        pipeline.track(EventName.APP_OPEN)
        pipeline.reset()
        pipeline.track(EventName.APP_OPEN)
        assertEquals(listOf("user-1", null), pipeline.queuedEvents().filter { it.name == EventName.APP_OPEN }.map { it.userId })

        pipeline.setOptedOut(true)
        pipeline.track(EventName.APP_OPEN)
        assertTrue(pipeline.queuedEvents().isEmpty())
        assertTrue(pipeline.isOptedOut)

        pipeline.setOptedOut(false)
        pipeline.track(EventName.APP_OPEN)
        assertEquals(1, pipeline.queuedEvents().count { it.name == EventName.APP_OPEN })
        assertFalse(pipeline.isOptedOut)
    }

    @Test
    fun userIdAndOptOutSurviveARestart() {
        val first = harness.makePipeline()
        first.identify("user-1")
        first.waitUntilIdle()
        val second = harness.makePipeline()
        second.track(EventName.APP_OPEN)
        assertEquals(listOf("user-1"), second.queuedEvents().filter { it.name == EventName.APP_OPEN }.map { it.userId })

        second.setOptedOut(true)
        second.waitUntilIdle()
        val third = harness.makePipeline()
        assertTrue(third.isOptedOut)
        third.track(EventName.APP_OPEN)
        assertTrue(third.queuedEvents().isEmpty())
    }

    @Test
    fun identifyAfterOptOutStoresNothing() {
        val pipeline = harness.makePipeline()
        pipeline.setOptedOut(true)
        pipeline.identify("user-1")
        pipeline.waitUntilIdle()
        assertNull(harness.prefs.getString(EventPipeline.KEY_USER_ID, null))
        pipeline.setOptedOut(false)
        pipeline.track(EventName.APP_OPEN)
        assertEquals(listOf<String?>(null), pipeline.queuedEvents().filter { it.name == EventName.APP_OPEN }.map { it.userId })
    }

    @Test
    fun gettersAreSafeInsideAnObserver() {
        val pipeline = harness.makePipeline()
        val counts = Collections.synchronizedList(mutableListOf<Int>())
        val optedOut = Collections.synchronizedList(mutableListOf<Boolean>())
        pipeline.addObserver {
            counts.add(pipeline.queuedEvents().size)
            optedOut.add(pipeline.isOptedOut)
            pipeline.waitUntilIdle()
        }
        val started = System.nanoTime()
        pipeline.track(EventName.APP_OPEN)
        pipeline.waitUntilIdle()
        // Inline on the SDK thread: no deadlock, and no waiting for the executor's timeout.
        assertTrue((System.nanoTime() - started) / 1_000_000 < SdkExecutor.DEFAULT_WAIT_MILLIS)
        assertEquals(listOf(1), counts.toList())
        assertEquals(listOf(false), optedOut.toList())
    }

    @Test
    fun observersSeeCommittedEventsOnTheSdkThread() {
        val pipeline = harness.makePipeline()
        val seen = Collections.synchronizedList(mutableListOf<EventName>())
        val threads = Collections.synchronizedList(mutableListOf<String>())
        val token = pipeline.addObserver {
            seen.add(it.name)
            threads.add(Thread.currentThread().name)
        }
        pipeline.track(EventName.APP_OPEN)
        pipeline.waitUntilIdle()
        pipeline.removeObserver(token)
        pipeline.track(EventName.APP_BACKGROUND)
        pipeline.waitUntilIdle()
        assertEquals(listOf(EventName.APP_OPEN), seen.toList())
        assertEquals(listOf("test-sdk"), threads.toList())
    }

    @Test
    fun aThrowingObserverDoesNotBreakThePipeline() {
        val pipeline = harness.makePipeline()
        val seen = Collections.synchronizedList(mutableListOf<EventName>())
        pipeline.addObserver(EventObserver { throw IllegalStateException("boom") })
        pipeline.addObserver { seen.add(it.name) }
        pipeline.track(EventName.APP_OPEN)
        pipeline.track(EventName.APP_BACKGROUND)
        assertEquals(2, pipeline.queuedEvents().size)
        assertEquals(listOf(EventName.APP_OPEN, EventName.APP_BACKGROUND), seen.toList())
    }

    @Test
    fun propertiesAreCopiedWhenTracked() {
        val pipeline = harness.makePipeline()
        val properties = mutableMapOf<String, Any?>("screen_name" to "Home")
        pipeline.track(EventName.SCREEN_VIEW, properties)
        properties["screen_name"] = "Changed"
        assertEquals("Home", pipeline.queuedEvents()[0].properties["screen_name"])
    }

    @Test
    fun eventJsonUsesSnakeCaseEnvelopeAndExplicitNulls() {
        val pipeline = harness.makePipeline()
        pipeline.track(
            EventName.SESSION_START,
            mapOf(
                "screen_name" to "Home \"quoted\" / \n",
                "previous_screen_name" to null,
                "context" to mapOf("locale" to "pt-BR", "push_permission" to null, "days_since_install" to 3, "dark_mode" to true),
                "list" to listOf(1, 2.5, null),
            ),
        )
        val event = pipeline.queuedEvents().single()
        val envelopeKeys = listOf(
            "event_id", "name", "timestamp", "seq", "install_id", "anonymous_id", "user_id", "session_id", "schema_version",
            "sdk_version", "platform", "app_version", "app_build", "os_version", "properties",
        )

        val json = event.toJson()
        assertEquals(envelopeKeys.sorted(), json.keys().asSequence().toList().sorted())
        assertTrue(json.isNull("user_id"))
        assertTrue(json.isNull("session_id"))
        assertEquals("session_start", json.getString("name"))
        assertEquals(1, json.getInt("schema_version"))
        assertEquals("android", json.getString("platform"))
        val properties = json.getJSONObject("properties")
        assertEquals("Home \"quoted\" / \n", properties.getString("screen_name"))
        assertTrue(properties.has("previous_screen_name") && properties.isNull("previous_screen_name"))
        val context = properties.getJSONObject("context")
        assertTrue(context.has("push_permission") && context.isNull("push_permission"))
        assertEquals(3, context.getInt("days_since_install"))
        assertTrue(context.getBoolean("dark_mode"))
        assertTrue(properties.getJSONArray("list").isNull(2))

        val string = event.toJsonString()
        assertTrue(string.contains("\"user_id\":null"))
        assertTrue(string.contains("\"previous_screen_name\":null"))
        assertFalse(string.contains("\n"))

        val map = event.toMap()
        assertEquals(envelopeKeys.toSet(), map.keys)
        assertTrue(map.containsKey("user_id") && map["user_id"] == null)
        val mapProperties = map["properties"] as Map<*, *>
        assertTrue(mapProperties.containsKey("previous_screen_name") && mapProperties["previous_screen_name"] == null)
        assertEquals("pt-BR", (mapProperties["context"] as Map<*, *>)["locale"])

        // The on-disk line round-trips.
        assertEquals(event, dev.renoo.sdk.Event.fromJsonString(string))
        assertEquals(event.properties, JsonValues.fromJsonObject(JSONObject(string).getJSONObject("properties")))
    }

    @Test
    fun timestampsAreIsoUtcWithMilliseconds() {
        assertEquals("1970-01-01T00:00:00.000Z", Timestamp.format(0))
        assertEquals("2023-11-14T22:13:20.123Z", Timestamp.format(1_700_000_000_123L))
        assertEquals(1_700_000_000_123L, Timestamp.parse("2023-11-14T22:13:20.123Z"))
    }

    @Test
    fun storeTrimsOldestEvents() {
        val store = EventStore(harness.directory, maxEvents = 10, logger = harness.logger)
        val pipeline = harness.makePipeline()
        pipeline.track(EventName.APP_OPEN)
        val template = pipeline.queuedEvents()[0]
        store.clear()
        repeat(25) { store.append(template.copy(seq = it.toLong() + 1)) }
        assertTrue(store.count <= 10)
        val all = store.all()
        assertEquals(store.count, all.size)
        assertEquals(25L, all.last().seq)
        assertEquals(all.map { it.seq }.sorted(), all.map { it.seq })
    }

    @Test
    fun storeSkipsCorruptLines() {
        val pipeline = harness.makePipeline()
        pipeline.track(EventName.APP_OPEN)
        pipeline.waitUntilIdle()
        File(harness.directory, EventStore.FILE_NAME).appendText("{not json\n{\"name\":\"unknown_event\"}\n")
        pipeline.track(EventName.APP_BACKGROUND)
        assertEquals(listOf(EventName.APP_OPEN, EventName.APP_BACKGROUND), pipeline.queuedEvents().map { it.name })
    }

    @Test
    fun aLineCutShortByACrashDoesNotSwallowTheNextEvent() {
        // A crash mid-write left a partial line with no newline at the end of the file.
        val file = File(harness.directory, EventStore.FILE_NAME)
        file.writeText("{\"event_id\":\"partial")
        val pipeline = harness.makePipeline()
        pipeline.track(EventName.APP_OPEN)
        pipeline.track(EventName.APP_BACKGROUND)
        assertEquals(listOf(EventName.APP_OPEN, EventName.APP_BACKGROUND), pipeline.queuedEvents().map { it.name })
        assertEquals("{\"event_id\":\"partial", file.readLines().first())
    }

    @Test
    fun debugLogMasksThePurchaseTokenButDiskAndObserversKeepIt() {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val pipeline = EventPipeline(
            executor = SdkExecutor(harness.logger, threadName = "test-sdk"),
            storeProvider = { EventStore(harness.directory, logger = harness.logger) },
            prefs = harness.prefs,
            logger = SdkLogger(LogLevel.DEBUG) { _, message -> logs.add(message) },
            envelopeProvider = { Envelope(sdkVersion = "0.0.0", appVersion = "1.0", appBuild = "1", osVersion = "16") },
            trackingScope = TrackingScope.EVERYONE,
            installIdProvider = { "install-1" },
        )
        val token = "abcdefghijklmnopqrstuvwxyz.secret"
        val observed = Collections.synchronizedList(mutableListOf<Any?>())
        pipeline.addObserver { observed.add(it.properties["purchase_token"]) }
        pipeline.track(EventName.SUBSCRIPTION_STATE, mapOf("status" to "active", "purchase_token" to token))
        pipeline.track(EventName.TRANSACTION, mapOf("nested" to mapOf("purchase_token" to token)))

        assertEquals(token, pipeline.queuedEvents()[0].properties["purchase_token"])
        assertEquals(listOf<Any?>(token, null), observed.toList())
        val eventLogs = logs.filter { it.startsWith("event ") }
        assertEquals(2, eventLogs.size)
        eventLogs.forEach {
            assertFalse(it, it.contains(token))
            assertTrue(it, it.contains("abcdef…"))
        }
    }

    @Test
    fun optOutClearsEveryStateOwnerAndTheSession() {
        val pipeline = harness.makePipeline()
        val reasons = Collections.synchronizedList(mutableListOf<ClearReason>())
        pipeline.addStateOwner { reasons.add(it) }
        pipeline.addStateOwner { throw IllegalStateException("a broken owner does not stop the others") }
        pipeline.addStateOwner { reasons.add(it) }
        pipeline.setSessionId("s1")
        pipeline.setOptedOut(true)
        pipeline.waitUntilIdle()
        assertEquals(listOf(ClearReason.OPT_OUT, ClearReason.OPT_OUT), reasons.toList())
        assertTrue(harness.prefs.getBoolean(EventPipeline.KEY_OPTED_OUT, false))

        pipeline.setOptedOut(false)
        pipeline.track(EventName.SCREEN_VIEW)
        assertEquals(listOf<String?>(null), pipeline.queuedEvents().filter { it.name == EventName.SCREEN_VIEW }.map { it.sessionId })
        assertEquals("opting in clears nothing", 2, reasons.size)
    }

    @Test
    fun clearRestoredStateDeletesQueueUserIdAndSeqButKeepsOptOut() {
        val executor = SdkExecutor(harness.logger, threadName = "test-sdk")
        val pipeline = harness.makePipeline(executor = executor)
        val reasons = Collections.synchronizedList(mutableListOf<ClearReason>())
        pipeline.addStateOwner { reasons.add(it) }
        pipeline.identify("user-1")
        pipeline.track(EventName.APP_OPEN)
        pipeline.waitUntilIdle()
        harness.prefs.edit().putBoolean(EventPipeline.KEY_OPTED_OUT, false).apply()

        // SdkCore calls it synchronously from a task on the executor.
        executor.runAndWait(Unit) { pipeline.clearRestoredState() }

        assertTrue(pipeline.queuedEvents().isEmpty())
        assertFalse(harness.prefs.contains(EventPipeline.KEY_USER_ID))
        assertFalse(harness.prefs.contains(EventPipeline.KEY_SEQ))
        assertTrue(harness.prefs.contains(EventPipeline.KEY_OPTED_OUT))
        assertEquals(listOf(ClearReason.RESTORED_FROM_ANOTHER_INSTALL), reasons.toList())
        pipeline.track(EventName.SCREEN_VIEW)
        val events = pipeline.queuedEvents()
        assertEquals(listOf(1L), events.map { it.seq })
        assertEquals(listOf<String?>(null), events.map { it.userId })
    }
}
