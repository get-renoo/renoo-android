package dev.renoo.sdk.collectors

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dev.renoo.sdk.EventName
import dev.renoo.sdk.ScreenSource
import dev.renoo.sdk.core.Collector
import dev.renoo.sdk.core.SdkEnvironment
import java.lang.ref.WeakReference

/**
 * Screen views.
 *
 * - Manual: [trackScreen], used by `Renoo.trackScreen` and the Flutter / React Native
 *   wrappers.
 * - Automatic, when `configuration.automaticScreenTracking` is on: one `screen_view` with source
 *   `activity` and the Activity's simple class name each time an Activity resumes, except
 *   activities [ScreenFilter] rejects (Flutter / React Native host activities, system and library
 *   activities such as Play Billing's proxy or Google sign-in).
 *
 * Automatic capture works at Activity granularity only. **Fragments, Navigation destinations and
 * Jetpack Compose destinations are not captured**: a single-activity app gets one screen name.
 * Report those screens with `Renoo.trackScreen(name)` (source `manual`, or `compose`).
 *
 * Re-emission rules, matching what iOS `viewDidAppear` does:
 * - the same Activity instance resuming again (after a dialog or permission prompt, a
 *   multi-window focus change, or a return from the background) is not a new screen view;
 * - a recreation for a configuration change (rotation, dark mode, locale) is not either;
 * - coming back to an Activity from another app Activity is (like popping a navigation controller).
 *
 * All sources go through [trackScreen] so `previous_screen_name` chains across them.
 * Activity callbacks run on the main thread, after [LifecycleCollector]'s (registered first),
 * so a cold start writes `session_start` and `app_open` before the first `screen_view`.
 */
internal class ScreenCollector(private val environment: SdkEnvironment) : Collector {
    private val lock = Any()
    private var lastScreenName: String? = null

    // Main thread only (activity callbacks).
    private var lastAutomaticActivity: WeakReference<Activity>? = null
    private var recreatingClass: Class<*>? = null
    private val namesByClass = HashMap<Class<*>, String?>()

    override fun start() {
        if (!environment.configuration.automaticScreenTracking) return
        val app = environment.appContext as? Application
        if (app == null) {
            environment.logger.error { "Automatic screen capture disabled: the context is not an Application" }
            return
        }
        app.registerActivityLifecycleCallbacks(callbacks)
    }

    /** Trims [name], caps it at [MAX_NAME_LENGTH] characters, ignores it if empty. Any thread. */
    fun trackScreen(name: String, source: ScreenSource) {
        val trimmed = truncate(name.trim(), MAX_NAME_LENGTH)
        if (trimmed.isEmpty()) return
        val previous = synchronized(lock) {
            val previous = lastScreenName
            lastScreenName = trimmed
            previous
        }
        environment.pipeline.track(
            EventName.SCREEN_VIEW,
            mapOf(
                "screen_name" to trimmed,
                "source" to source.wireValue,
                "previous_screen_name" to previous,
            ),
        )
    }

    // region Automatic Activity capture

    /** Main thread. */
    internal fun activityResumed(activity: Activity) {
        val activityClass = activity.javaClass
        if (lastAutomaticActivity?.get() === activity) return
        if (recreatingClass != null) {
            val recreated = recreatingClass == activityClass
            recreatingClass = null
            if (recreated) {
                lastAutomaticActivity = WeakReference(activity)
                return
            }
        }
        val name = screenName(activityClass) ?: return
        lastAutomaticActivity = WeakReference(activity)
        trackScreen(name, ScreenSource.ACTIVITY)
    }

    /** Main thread. Remembers that the reported Activity is being recreated for a configuration change. */
    internal fun activityGoingAway(activity: Activity) {
        if (activity.isChangingConfigurations && lastAutomaticActivity?.get() === activity) {
            recreatingClass = activity.javaClass
        }
    }

    private fun screenName(activityClass: Class<*>): String? {
        if (namesByClass.containsKey(activityClass)) return namesByClass[activityClass]
        val supers = ArrayList<String>()
        var current: Class<*>? = activityClass.superclass
        while (current != null && current != Activity::class.java && supers.size < MAX_SUPERCLASS_DEPTH) {
            supers += current.name
            current = current.superclass
        }
        return ScreenFilter.screenName(activityClass.name, supers).also { namesByClass[activityClass] = it }
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) = safely { activityResumed(activity) }
        override fun onActivityStopped(activity: Activity) = safely { activityGoingAway(activity) }
        override fun onActivityDestroyed(activity: Activity) = safely { activityGoingAway(activity) }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            environment.logger.error({ "Screen capture failed" }, t)
        }
    }

    // endregion

    companion object {
        private const val MAX_SUPERCLASS_DEPTH = 32
        const val MAX_NAME_LENGTH: Int = 100

        /** The first [max] UTF-16 units, without splitting a surrogate pair. */
        internal fun truncate(value: String, max: Int): String {
            if (value.length <= max) return value
            val end = if (Character.isHighSurrogate(value[max - 1])) max - 1 else max
            return value.substring(0, end)
        }
    }
}
