package dev.renoo.sdk.collectors

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Decides when the app as a whole enters the foreground or the background, from per-activity
 * lifecycle callbacks. The Android equivalent of iOS `didBecomeActive` / `didEnterBackground`,
 * without AndroidX `ProcessLifecycleOwner`. Pure (activities are opaque keys) so it is unit-tested
 * on the JVM. Main thread only.
 *
 * - Foreground = at least one started activity. Pausing (dialogs, permission prompts, multi-window
 *   focus changes) does not count, like iOS's resign-active.
 * - A stop with `isChangingConfigurations` (rotation, dark mode, locale change, resize) never
 *   backgrounds the app: the recreated instance starts right after it.
 * - Late `configure` (an activity already started before the callbacks were registered): we
 *   never see that activity's start. [assumeForeground] marks the app as in front from an
 *   out-of-band check, and a resume of an activity we never saw start counts as its start.
 *   Stopping an unknown activity when no known one is started backgrounds the app.
 */
internal class ForegroundTracker {
    private val started: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())

    var isForeground: Boolean = false
        private set

    /** True once any activity callback has arrived; after that the callbacks are authoritative. */
    var hasSeenActivity: Boolean = false
        private set

    /** Any activity callback (created, saved state…). Returns nothing; only marks [hasSeenActivity]. */
    fun activitySeen() {
        hasSeenActivity = true
    }

    /** Returns true when this start brings the app to the foreground. */
    fun activityStarted(activity: Any): Boolean {
        hasSeenActivity = true
        started.add(activity)
        if (isForeground) return false
        isForeground = true
        return true
    }

    /** A resume of an activity we never saw start means configure ran after its `onStart`. */
    fun activityResumed(activity: Any): Boolean {
        hasSeenActivity = true
        if (activity in started) return false
        return activityStarted(activity)
    }

    /** Returns true when this stop sends the app to the background. */
    fun activityStopped(activity: Any, isChangingConfigurations: Boolean): Boolean {
        hasSeenActivity = true
        started.remove(activity)
        if (!isForeground || isChangingConfigurations || started.isNotEmpty()) return false
        isForeground = false
        return true
    }

    /** Destroyed without a stop (should not happen once started): treated as a stop. */
    fun activityDestroyed(activity: Any, isChangingConfigurations: Boolean): Boolean {
        hasSeenActivity = true
        if (activity !in started) return false
        return activityStopped(activity, isChangingConfigurations)
    }

    /**
     * The process is known to be showing UI although no activity callback has arrived (late
     * configure). Ignored once callbacks have been seen. Returns true when this is an activation.
     */
    fun assumeForeground(): Boolean {
        if (hasSeenActivity || isForeground) return false
        isForeground = true
        return true
    }
}
