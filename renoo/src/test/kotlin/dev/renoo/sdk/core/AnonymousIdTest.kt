package dev.renoo.sdk.core

import dev.renoo.sdk.Event
import dev.renoo.sdk.EventName
import dev.renoo.sdk.TrackingScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** The anonymous ID: generation, persistence, the envelope field and the once-per-ID `identify`. */
class AnonymousIdTest {
    private val harness = PipelineHarness(identitySent = false)

    @After
    fun tearDown() = harness.tearDown()

    /** A launch with the subscription gate closed until [open] (the default SDK scope). */
    private fun launch(): EventPipeline = harness.makePipeline(scope = TrackingScope.SUBSCRIBERS_ONLY)

    private fun EventPipeline.open(): EventPipeline = apply { setTrackingState(TrackingState.TRACKED) }

    private fun EventPipeline.anonymousId(): String {
        waitUntilIdle()
        return assertNotNull(currentAnonymousId).let { currentAnonymousId!! }
    }

    private fun List<Event>.identifies() = filter { it.name == EventName.IDENTIFY }

    @Test
    fun generatedAtStartAndPersistedAcrossARestart() {
        val first = launch()
        val id = first.anonymousId()
        assertEquals(id, harness.prefs.getString(EventPipeline.KEY_ANONYMOUS_ID, null))

        val second = launch().open()
        assertEquals("a restart reuses the stored ID", id, second.anonymousId())
        second.track(EventName.APP_OPEN)
        assertEquals(id, second.queuedEvents().single { it.name == EventName.APP_OPEN }.anonymousId)
    }

    @Test
    fun isALowercaseUuidV4() {
        val id = launch().anonymousId()
        assertTrue(id, Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(id))
        assertEquals(4, UUID.fromString(id).version())
        assertNotEquals("random per new install", id, PipelineHarness(identitySent = false).makePipeline().anonymousId())
    }

    @Test
    fun presentOnEveryEventIncludingBufferedOnes() {
        val pipeline = launch()
        val id = pipeline.anonymousId()
        pipeline.track(EventName.APP_OPEN)
        pipeline.track(EventName.SCREEN_VIEW, mapOf("screen_name" to "Home"))
        pipeline.open()
        pipeline.track(EventName.APP_BACKGROUND)

        val events = pipeline.queuedEvents()
        assertEquals(
            listOf(EventName.APP_OPEN, EventName.SCREEN_VIEW, EventName.IDENTIFY, EventName.APP_BACKGROUND),
            events.map { it.name },
        )
        events.forEach {
            assertEquals(id, it.anonymousId)
            assertEquals(id, it.toJson().getString("anonymous_id"))
        }
    }

    @Test
    fun identifyIsSentOnceWhenTheGateOpensAndNotAgainAfterARestart() {
        val first = launch()
        first.track(EventName.APP_OPEN)
        first.waitUntilIdle()
        assertTrue("nothing is stored before the gate opens", first.queuedEvents().isEmpty())
        first.open()

        val identify = first.queuedEvents().identifies().single()
        assertEquals(first.anonymousId(), identify.anonymousId)
        assertNull(identify.userId)
        assertTrue(identify.properties.containsKey("user_id"))
        assertNull(identify.properties["user_id"])
        assertEquals(identify.anonymousId, harness.prefs.getString(EventPipeline.KEY_IDENTITY_SENT_FOR, null))

        first.setTrackingState(TrackingState.UNKNOWN)
        first.open()
        val second = launch().open()
        second.track(EventName.APP_OPEN)
        // SdkCore also checks right after start-up.
        second.waitUntilIdle()
        assertEquals(1, second.queuedEvents().identifies().size)
    }

    @Test
    fun identifyUserIdSetsItAndSkipsARepeat() {
        val pipeline = launch().open()
        pipeline.identify("user-1")
        pipeline.identify("user-1")
        pipeline.track(EventName.APP_OPEN)
        pipeline.identify("user-2")

        val events = pipeline.queuedEvents()
        assertEquals(listOf(null, "user-1", "user-2"), events.identifies().map { it.properties["user_id"] })
        assertEquals("user-1", events.single { it.name == EventName.APP_OPEN }.userId)

        val restarted = launch().open()
        restarted.identify("user-2")
        assertEquals("the stored user ID counts as already identified", 3, restarted.queuedEvents().identifies().size)
    }

    @Test
    fun anIdentifyFromTheAppCountsAsSent() {
        val pipeline = launch()
        pipeline.identify("user-1")
        pipeline.open()
        val identifies = pipeline.queuedEvents().identifies()
        assertEquals("the buffered identify is the one for this anonymous ID", listOf<Any?>("user-1"), identifies.map { it.properties["user_id"] })
    }

    @Test
    fun resetMakesANewIdAndSendsItsIdentify() {
        val pipeline = launch().open()
        val old = pipeline.anonymousId()
        pipeline.identify("user-1")
        pipeline.track(EventName.APP_OPEN)
        pipeline.reset()
        val new = pipeline.anonymousId()
        pipeline.track(EventName.SCREEN_VIEW)

        assertNotEquals(old, new)
        assertEquals(new, harness.prefs.getString(EventPipeline.KEY_ANONYMOUS_ID, null))
        assertNull(harness.prefs.getString(EventPipeline.KEY_USER_ID, null))
        val events = pipeline.queuedEvents()
        assertEquals(
            listOf(EventName.IDENTIFY, EventName.IDENTIFY, EventName.APP_OPEN, EventName.IDENTIFY, EventName.SCREEN_VIEW),
            events.map { it.name },
        )
        assertEquals(listOf(old, old, old, new, new), events.map { it.anonymousId })
        assertEquals(listOf(null, "user-1", "user-1", null, null), events.map { it.userId })
        assertEquals(new, harness.prefs.getString(EventPipeline.KEY_IDENTITY_SENT_FOR, null))
    }

    @Test
    fun bufferedEventsKeepTheAnonymousIdTheyWereCapturedWith() {
        val pipeline = launch()
        val old = pipeline.anonymousId()
        pipeline.track(EventName.APP_OPEN)
        pipeline.reset()
        val new = pipeline.anonymousId()
        pipeline.track(EventName.SCREEN_VIEW)
        pipeline.open()

        val events = pipeline.queuedEvents()
        assertEquals(listOf(EventName.APP_OPEN, EventName.SCREEN_VIEW, EventName.IDENTIFY), events.map { it.name })
        assertEquals(listOf(old, new, new), events.map { it.anonymousId })
    }

    @Test
    fun theGateHoldsNonSubscribers() {
        val pipeline = launch()
        pipeline.track(EventName.APP_OPEN)
        pipeline.setTrackingState(TrackingState.NOT_TRACKED)
        pipeline.identify("user-1")
        pipeline.reset()
        pipeline.track(EventName.SCREEN_VIEW)
        pipeline.waitUntilIdle()

        assertTrue(pipeline.queuedEvents().isEmpty())
        assertFalse("no identify was sent", harness.prefs.contains(EventPipeline.KEY_IDENTITY_SENT_FOR))
        assertFalse(harness.prefs.contains(EventPipeline.KEY_SEQ))
    }

    @Test
    fun restoredStateWipeMakesANewIdAndForgetsItsIdentify() {
        val executor = SdkExecutor(harness.logger, threadName = "test-sdk")
        harness.prefs.edit()
            .putString(EventPipeline.KEY_ANONYMOUS_ID, "11111111-1111-4111-8111-111111111111")
            .putString(EventPipeline.KEY_IDENTITY_SENT_FOR, "11111111-1111-4111-8111-111111111111")
            .apply()
        val pipeline = harness.makePipeline(executor = executor)
        assertEquals("11111111-1111-4111-8111-111111111111", pipeline.anonymousId())

        // SdkCore runs these from one task on the executor, right after the pipeline's load.
        executor.runAndWait(Unit) {
            pipeline.clearRestoredState()
            pipeline.sendIdentityIfNeeded()
        }

        val new = pipeline.anonymousId()
        assertNotEquals("11111111-1111-4111-8111-111111111111", new)
        assertEquals(new, harness.prefs.getString(EventPipeline.KEY_ANONYMOUS_ID, null))
        val identify = pipeline.queuedEvents().single()
        assertEquals(EventName.IDENTIFY, identify.name)
        assertEquals(new, identify.anonymousId)
    }

    @Test
    fun optOutKeepsTheIdAndOptInSendsIdentifyAgain() {
        val pipeline = launch().open()
        val id = pipeline.anonymousId()
        assertEquals(1, pipeline.queuedEvents().identifies().size)
        pipeline.setOptedOut(true)
        pipeline.waitUntilIdle()
        assertEquals(id, pipeline.currentAnonymousId)
        assertEquals(id, harness.prefs.getString(EventPipeline.KEY_ANONYMOUS_ID, null))
        assertFalse(harness.prefs.contains(EventPipeline.KEY_IDENTITY_SENT_FOR))

        val restarted = launch()
        assertEquals("kept across a restart while opted out", id, restarted.anonymousId())
        restarted.setOptedOut(false)
        restarted.open()
        val identify = restarted.queuedEvents().single()
        assertEquals(EventName.IDENTIFY, identify.name)
        assertEquals(id, identify.anonymousId)
        assertEquals(id, harness.prefs.getString(EventPipeline.KEY_IDENTITY_SENT_FOR, null))
    }

    @Test
    fun queueLinesWithoutAnAnonymousIdAreSkipped() {
        val pipeline = launch().open()
        pipeline.track(EventName.APP_OPEN)
        pipeline.waitUntilIdle()
        val file = java.io.File(harness.directory, EventStore.FILE_NAME)
        val line = file.readLines().first { it.contains("\"app_open\"") }
        val legacy = org.json.JSONObject(line).apply { remove("anonymous_id") }.toString()
        file.appendText(legacy + "\n")
        assertEquals(1, launch().queuedEvents().count { it.name == EventName.APP_OPEN })
    }
}
