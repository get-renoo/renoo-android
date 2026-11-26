package dev.renoo.sdk.collectors

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** App-level foreground detection from per-activity callbacks. Plain JVM; activities are opaque keys. */
class ForegroundTrackerTest {
    private val tracker = ForegroundTracker()
    private val a = Any()
    private val b = Any()

    @Test
    fun firstStartActivatesAndLastStopBackgrounds() {
        assertTrue(tracker.activityStarted(a))
        assertFalse(tracker.activityResumed(a))
        assertTrue(tracker.activityStopped(a, isChangingConfigurations = false))
        assertFalse(tracker.isForeground)
        assertTrue(tracker.activityStarted(a))
    }

    @Test
    fun navigatingBetweenActivitiesStaysInForeground() {
        tracker.activityStarted(a)
        assertFalse(tracker.activityStarted(b)) // B starts before A stops
        assertFalse(tracker.activityStopped(a, isChangingConfigurations = false))
        assertFalse(tracker.activityStarted(a)) // back
        assertFalse(tracker.activityStopped(b, isChangingConfigurations = false))
        assertFalse(tracker.activityDestroyed(b, isChangingConfigurations = false))
        assertTrue(tracker.isForeground)
    }

    @Test
    fun rotationIsNotABackgroundAndForeground() {
        val recreated = Any()
        tracker.activityStarted(a)
        assertFalse(tracker.activityStopped(a, isChangingConfigurations = true))
        assertFalse(tracker.activityDestroyed(a, isChangingConfigurations = true))
        assertTrue(tracker.isForeground)
        assertFalse(tracker.activityStarted(recreated))
        assertFalse(tracker.activityResumed(recreated))
        assertTrue(tracker.activityStopped(recreated, isChangingConfigurations = false))
    }

    @Test
    fun noActivityMeansNoForeground() {
        // Receiver or service process: nothing ever starts.
        tracker.activitySeen()
        assertFalse(tracker.isForeground)
        assertFalse(tracker.activityStopped(a, isChangingConfigurations = false))
    }

    @Test
    fun lateConfigureAssumesForegroundAndUnknownStopBackgrounds() {
        assertTrue(tracker.assumeForeground())
        assertFalse(tracker.assumeForeground())
        // The already-started activity we never saw: navigating away from it to a known one…
        assertFalse(tracker.activityStarted(b))
        assertFalse(tracker.activityStopped(a, isChangingConfigurations = false))
        // …and leaving the app from the known one.
        assertTrue(tracker.activityStopped(b, isChangingConfigurations = false))
    }

    @Test
    fun lateConfigureHomeFromTheUnknownActivity() {
        tracker.assumeForeground()
        assertTrue(tracker.activityStopped(a, isChangingConfigurations = false))
    }

    @Test
    fun probeIsIgnoredOnceCallbacksArrived() {
        tracker.activityStarted(a)
        tracker.activityStopped(a, isChangingConfigurations = false)
        assertFalse(tracker.assumeForeground()) // stale "foreground" answer after the user left
        assertFalse(tracker.isForeground)
    }

    @Test
    fun resumeOfAnUnseenActivityActivates() {
        // configure ran between the activity's onStart and onResume (or while it was paused).
        tracker.activitySeen()
        assertTrue(tracker.activityResumed(a))
        assertTrue(tracker.activityStopped(a, isChangingConfigurations = false))
    }
}
