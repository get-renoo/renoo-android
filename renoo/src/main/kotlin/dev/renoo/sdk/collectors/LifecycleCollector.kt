package dev.renoo.sdk.collectors

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import dev.renoo.sdk.core.ClearReason
import dev.renoo.sdk.core.Collector
import dev.renoo.sdk.core.SdkEnvironment
import dev.renoo.sdk.core.StateOwner

/**
 * App opens, foreground / background, sessions and unclean exits.
 *
 * The rules live in [LifecycleEngine] (framework-free, unit-tested) and app-level foreground
 * detection in [ForegroundTracker]. This class turns `Application.ActivityLifecycleCallbacks`
 * into engine calls and forwards the resulting steps to the pipeline. No AndroidX lifecycle.
 *
 * **Threading: the engine runs synchronously on the main thread**, under a lock, exactly like
 * iOS. Activity callbacks arrive on the main thread, and `screen_view` for the same activity is
 * tracked from `onActivityResumed`, also on the main thread, right after the `onActivityStarted`
 * that activates the app. `pipeline.setSessionId` and `pipeline.track` only enqueue on the SDK's
 * single executor, so calling them from the main thread in callback order keeps that order on
 * disk: `session_start` / `app_open` and the new session ID always land before the first
 * `screen_view`. If the engine ran on the executor instead, its `track` calls would be enqueued
 * one hop later, behind a `screen_view` posted from `onActivityResumed` in the meantime, which
 * would then be stamped with the old (or no) session: the race iOS hit. The engine only reads a
 * cached context snapshot and writes one small prefs value with `apply()` (in-memory now, disk
 * later on a framework thread), so it is cheap. The engine and its first prefs read are warmed
 * up on the executor in [start], so the main thread normally never waits for the prefs file.
 *
 * Cold `app_open` is emitted on the first activity start of the process, not in [start]: a
 * process started for a broadcast receiver, a service, a job or a content provider runs
 * `Application.onCreate` (and so `configure`) without any UI, and must not report an open.
 *
 * Late `configure` (after an activity is already started, e.g. from a Flutter platform channel):
 * the activity's `onStart` was missed. [start] asks the system, on the executor (it is a Binder
 * call), whether this process is the top, user-facing one; if so, and no activity callback has
 * arrived in the meantime, the app is treated as already in the foreground. A resume or stop of
 * an activity we never saw start is also handled (see [ForegroundTracker]).
 *
 * `last_active_at` for unclean exits: we can't see a crash, so the engine persists a "last
 * alive in the foreground" time on every transition, on every activity pause, and from a slow
 * heartbeat (every [HEARTBEAT_INTERVAL_MILLIS], main-thread handler, only while in the
 * foreground). The estimate is therefore at most ~60 s early.
 *
 * Android has no reliable "will terminate" callback. Leaving the app (Home, Back from the last
 * activity, swiping from Recents) stops the activities first, so it is a clean background; a
 * process killed while in the foreground (crash, ANR, `System.exit`, force stop) is an unclean exit.
 *
 * Opt-out: [clearState] deletes the persisted state, and while opted out the engine is not run
 * (nothing is written back). After `optIn` the next return to the foreground opens a new session.
 */
internal class LifecycleCollector(
    private val environment: SdkEnvironment,
    private val context: ContextCollector,
) : Collector, StateOwner {
    /** Guards [engine]. */
    private val lock = Any()
    private var engine: LifecycleEngine? = null

    /** Main thread only. */
    private val tracker = ForegroundTracker()
    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private var heartbeatRunning = false
    private val heartbeat = object : Runnable {
        /** Main thread, from the host app's looper: nothing may escape. */
        override fun run() {
            safely {
                if (!heartbeatRunning) return
                try {
                    withEngine { it.recordActivity() }
                } finally {
                    mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MILLIS)
                }
            }
        }
    }

    /**
     * Whether this process is the top, user-facing one. Runs on the executor. Tests replace it
     * before `start()`.
     */
    internal var foregroundProbe: () -> Boolean = ::isProcessInForeground

    /**
     * Called on the main thread after each app-level foreground (`true`) / background (`false`)
     * transition, after that transition's events were enqueued (so a background flush includes
     * `app_background`). The uploader posts from it to the executor. Set before [start].
     */
    @Volatile
    internal var foregroundListener: ((Boolean) -> Unit)? = null

    override fun start() {
        val app = environment.appContext as? Application
        if (app == null) {
            environment.logger.error { "Lifecycle capture disabled: the context is not an Application" }
            return
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        environment.executor.post {
            // Load the persisted state off the main thread so the first activation doesn't wait for it.
            withEngine { }
            val inForeground = try {
                foregroundProbe()
            } catch (t: Throwable) {
                environment.logger.error({ "Could not read the process importance" }, t)
                false
            }
            if (inForeground) mainHandler.post { safely { foregroundProbeSaysActive() } }
        }
    }

    // region Entry points (main thread; internal so tests can drive them directly)

    internal fun becameActive() {
        if (environment.pipeline.isOptedOut) return
        context.refresh()
        withEngine { apply(it.didBecomeActive()) }
        startHeartbeat()
        foregroundListener?.invoke(true)
    }

    internal fun enteredBackground() {
        withEngine { apply(it.didEnterBackground()) }
        stopHeartbeat()
        foregroundListener?.invoke(false)
    }

    internal fun foregroundProbeSaysActive() {
        if (tracker.assumeForeground()) {
            environment.logger.debug { "configure ran with the app already in the foreground" }
            becameActive()
        }
    }

    // endregion

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = safely { tracker.activitySeen() }

        override fun onActivityStarted(activity: Activity) = safely {
            if (tracker.activityStarted(activity)) becameActive()
        }

        override fun onActivityResumed(activity: Activity) = safely {
            if (tracker.activityResumed(activity)) becameActive()
        }

        override fun onActivityPaused(activity: Activity) = safely {
            tracker.activitySeen()
            withEngine { it.recordActivity() }
        }

        override fun onActivityStopped(activity: Activity) = safely {
            if (tracker.activityStopped(activity, activity.isChangingConfigurations)) enteredBackground()
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = safely { tracker.activitySeen() }

        override fun onActivityDestroyed(activity: Activity) = safely {
            if (tracker.activityDestroyed(activity, activity.isChangingConfigurations)) enteredBackground()
        }
    }

    /**
     * Executor (pipeline opt-out / restore task). Forgets the session and the foreground marks,
     * on disk and in the engine, keeping only "this process already had its cold open".
     */
    override fun clearState(reason: ClearReason) {
        synchronized(lock) {
            engine?.reset()
            environment.prefs.edit().remove(LifecycleEngine.STATE_KEY).apply()
        }
    }

    /** Skipped while opted out, checked under [lock] so it can't write after [clearState]. */
    private fun withEngine(work: (LifecycleEngine) -> Unit) {
        synchronized(lock) {
            if (environment.pipeline.isOptedOut) return
            val current = engine ?: LifecycleEngine(
                prefs = environment.prefs,
                context = { context.snapshot() },
            ).also { engine = it }
            work(current)
        }
    }

    /** Called with [lock] held. `track` and `setSessionId` only enqueue, so order is preserved. */
    private fun apply(steps: List<LifecycleStep>) {
        val pipeline = environment.pipeline
        for (step in steps) {
            when (step) {
                is LifecycleStep.SetSession -> pipeline.setSessionId(step.id)
                is LifecycleStep.Emit -> pipeline.track(step.name, step.properties, step.timestampMillis, step.session)
            }
        }
    }

    private fun startHeartbeat() {
        if (heartbeatRunning) return
        heartbeatRunning = true
        mainHandler.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MILLIS)
    }

    private fun stopHeartbeat() {
        heartbeatRunning = false
        mainHandler.removeCallbacks(heartbeat)
    }

    /** Activity callbacks run inside the host app's lifecycle: nothing may escape them. */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            environment.logger.error({ "Lifecycle callback failed" }, t)
        }
    }

    companion object {
        const val HEARTBEAT_INTERVAL_MILLIS: Long = 60_000

        /**
         * True when this process holds the top activity. Binder call: executor only. A process
         * that is merely bound by a foreground app (also "foreground" importance) is excluded by
         * the reason code; receivers, services and jobs have lower importance.
         */
        private fun isProcessInForeground(): Boolean {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                info.importanceReasonCode == ActivityManager.RunningAppProcessInfo.REASON_UNKNOWN
        }
    }
}
