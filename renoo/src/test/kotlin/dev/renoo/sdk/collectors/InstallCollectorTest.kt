package dev.renoo.sdk.collectors

import dev.renoo.sdk.EventName
import dev.renoo.sdk.TrackingScope
import dev.renoo.sdk.core.EventPipeline
import dev.renoo.sdk.core.Timestamp
import dev.renoo.sdk.core.TrackingState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallCollectorTest {
    private val harness = CollectorHarness()
    private var marker = 1_000L
    private var clock = Fixture.T0

    @After
    fun tearDown() = harness.tearDown()

    private fun start(pipeline: EventPipeline): InstallEventTracker = InstallEventTracker(
        pipeline = pipeline,
        prefs = harness.prefs,
        logger = harness.logger,
        executor = harness.inline,
        installMarker = { marker },
        isReinstall = { null },
        idSource = { InstallIdentity.SOURCE_ANDROID_ID },
        clock = { clock },
    ).also { it.start() }

    private fun installs(pipeline: EventPipeline) = pipeline.events().named(EventName.INSTALL)

    @Test
    fun oncePerInstall() {
        val pipeline = harness.makePipeline(TrackingScope.EVERYONE)
        start(pipeline)
        pipeline.events()
        start(harness.makePipeline(TrackingScope.EVERYONE)) // a second launch of the same install
        val installs = installs(pipeline)
        assertEquals(1, installs.size)
        assertTrue(installs[0].properties.containsKey("is_reinstall"))
        assertEquals(null, installs[0].properties["is_reinstall"])
        assertEquals("android_id", installs[0].properties["id_source"])
        assertEquals(Timestamp.format(Fixture.T0), installs[0].timestamp)
    }

    @Test
    fun droppedByTheGateIsRetriedOnTheNextLaunchWithTheOriginalTimestamp() {
        val first = harness.makePipeline()
        start(first)
        first.setTrackingState(TrackingState.NOT_TRACKED)
        assertTrue(first.events().isEmpty())
        assertFalse("not marked done", harness.prefs.contains(InstallEventTracker.KEY_TRACKED_FOR))

        clock += 86_400_000 // the user subscribes a day later
        val second = harness.makePipeline()
        start(second)
        second.setTrackingState(TrackingState.TRACKED)
        val installs = installs(second)
        assertEquals(1, installs.size)
        assertEquals("when the install happened, not now", Timestamp.format(Fixture.T0), installs[0].timestamp)

        val third = harness.makePipeline(TrackingScope.EVERYONE)
        start(third)
        assertEquals("done for good", 1, installs(third).size)
    }

    @Test
    fun retriedInTheSameProcessWhenTheGateOpensLater() {
        val pipeline = harness.makePipeline()
        start(pipeline)
        pipeline.setTrackingState(TrackingState.NOT_TRACKED)
        pipeline.track(EventName.SCREEN_VIEW)

        // The user subscribes mid-session: the subscription collector opens the gate.
        pipeline.setTrackingState(TrackingState.TRACKED)
        pipeline.track(EventName.SUBSCRIPTION_STATE, mapOf("status" to "active"))
        pipeline.track(EventName.SCREEN_VIEW)

        val events = pipeline.events()
        assertEquals(1, events.named(EventName.INSTALL).size)
        assertEquals(EventName.SUBSCRIPTION_STATE, events.first().name)
        assertEquals(Timestamp.format(Fixture.T0), events.named(EventName.INSTALL).single().timestamp)
        assertTrue(harness.prefs.contains(InstallEventTracker.KEY_TRACKED_FOR))
    }

    @Test
    fun retriedWhenPushedOutOfTheMemoryBuffer() {
        val pipeline = harness.makePipeline()
        start(pipeline)
        repeat(EventPipeline.MAX_BUFFERED_EVENTS) { pipeline.track(EventName.SCREEN_VIEW) }
        pipeline.setTrackingState(TrackingState.TRACKED)
        val events = pipeline.events()
        assertEquals(1, events.named(EventName.INSTALL).size)
        assertEquals(EventName.INSTALL, events.last().name)
    }

    @Test
    fun bufferedThenCommittedWhenTheFirstReadSaysSubscriber() {
        val pipeline = harness.makePipeline()
        start(pipeline)
        pipeline.track(EventName.APP_OPEN)
        pipeline.setTrackingState(TrackingState.TRACKED)
        assertEquals(listOf(EventName.INSTALL, EventName.APP_OPEN), pipeline.events().map { it.name })
    }

    @Test
    fun aNewInstallEmitsAgainEvenWithRestoredPrefs() {
        val pipeline = harness.makePipeline(TrackingScope.EVERYONE)
        start(pipeline)
        marker = 2_000L // reinstall (or a new device) with the SDK prefs restored from backup
        clock += 1_000
        start(pipeline)
        val installs = installs(pipeline)
        assertEquals(2, installs.size)
        assertEquals(Timestamp.format(Fixture.T0 + 1_000), installs[1].timestamp)
    }
}
