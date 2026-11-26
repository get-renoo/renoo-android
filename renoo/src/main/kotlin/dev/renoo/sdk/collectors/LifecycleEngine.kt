package dev.renoo.sdk.collectors

import android.content.SharedPreferences
import dev.renoo.sdk.EventName
import dev.renoo.sdk.core.SessionOverride
import dev.renoo.sdk.core.Timestamp
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max

/** What the lifecycle engine persists between runs (JSON in the SDK prefs, key [LifecycleEngine.STATE_KEY]). */
internal data class LifecycleState(
    /** The open session, or `null` before the first activation. */
    val sessionId: String? = null,
    val sessionStartedAt: Long? = null,
    /**
     * True from an activation until the next move to the background.
     * Still true on the next cold start means the previous run died in the foreground.
     */
    val inForeground: Boolean = false,
    /** When the app last went to the background. `null` while in the foreground. */
    val backgroundedAt: Long? = null,
    /** Best estimate of the last moment the app was alive in the foreground (see [LifecycleEngine.recordActivity]). */
    val lastActiveAt: Long? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("session_id", sessionId ?: JSONObject.NULL)
        put("session_started_at", sessionStartedAt ?: JSONObject.NULL)
        put("in_foreground", inForeground)
        put("backgrounded_at", backgroundedAt ?: JSONObject.NULL)
        put("last_active_at", lastActiveAt ?: JSONObject.NULL)
    }.toString()

    companion object {
        /** `null` for anything that isn't a state written by [toJson]. */
        fun fromJson(value: String?): LifecycleState? {
            if (value == null) return null
            return try {
                val json = JSONObject(value)
                LifecycleState(
                    sessionId = if (json.isNull("session_id")) null else json.getString("session_id"),
                    sessionStartedAt = json.optionalLong("session_started_at"),
                    inForeground = json.getBoolean("in_foreground"),
                    backgroundedAt = json.optionalLong("backgrounded_at"),
                    lastActiveAt = json.optionalLong("last_active_at"),
                )
            } catch (e: Exception) {
                null
            }
        }

        private fun JSONObject.optionalLong(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
    }
}

/** One thing the collector must do, in order. */
internal sealed interface LifecycleStep {
    /** `pipeline.setSessionId(id)`. */
    data class SetSession(val id: String?) : LifecycleStep

    /** `pipeline.track(name, properties, timestampMillis, session)`. */
    data class Emit(
        val name: EventName,
        val properties: Map<String, Any?>,
        val timestampMillis: Long,
        val session: SessionOverride = SessionOverride.Current,
    ) : LifecycleStep
}

/**
 * The app lifecycle and session rules, without any Android framework class except
 * `SharedPreferences`, so they run as plain JVM tests. A port of the iOS `LifecycleEngine`.
 *
 * Not thread-safe: [LifecycleCollector] serialises calls with a lock.
 *
 * Order of steps on an activation (same as iOS):
 * `unclean_exit` → old `session_end` → `setSession(new)` → `session_start` → `app_open`.
 * The session ID is switched before `session_start`, so everything from it on is stamped with
 * the new session; the late `session_end` and `unclean_exit` carry explicit overrides.
 *
 * One engine per process: a new engine on the same prefs is a new process (cold start).
 *
 * @param sessionTimeoutMillis a test seam only: the timeout is fixed at [SESSION_TIMEOUT_MILLIS]
 *   and is not configurable by the host app. A session ends only when the background time is
 *   strictly greater than it.
 */
internal class LifecycleEngine(
    private val prefs: SharedPreferences,
    sessionTimeoutMillis: Long = SESSION_TIMEOUT_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val makeSessionId: () -> String = { UUID.randomUUID().toString().lowercase() },
    private val context: () -> Map<String, Any?>,
) {
    private val sessionTimeoutMillis = max(0L, sessionTimeoutMillis)

    var state: LifecycleState = LifecycleState.fromJson(prefs.getString(STATE_KEY, null)) ?: LifecycleState()
        private set

    /** False until the first activation of this process: that one is the cold `app_open`. */
    var hasActivatedThisProcess: Boolean = false
        private set

    /** The app came to the foreground (first started activity, or already in front at `configure`). */
    fun didBecomeActive(): List<LifecycleStep> {
        val now = clock()
        val steps = ArrayList<LifecycleStep>(6)

        val isCold = !hasActivatedThisProcess
        hasActivatedThisProcess = true

        if (!isCold && state.inForeground) {
            // Activated twice without a background in between. The collector's foreground tracker
            // normally filters this out; treat it as activity, not as a new open.
            state = state.copy(lastActiveAt = now)
            save()
            return emptyList()
        }

        var needsNewSession = state.sessionId == null
        val previous = state.sessionId
        val backgroundedAt = state.backgroundedAt

        if (isCold && state.inForeground && previous != null) {
            // The last run never reached the background: it crashed, was killed (ANR, OOM in the
            // foreground, force stop) or the device died. We only know roughly when it was last alive.
            val lastActive = state.lastActiveAt ?: state.sessionStartedAt ?: now
            steps += LifecycleStep.Emit(
                EventName.UNCLEAN_EXIT,
                mapOf(
                    "previous_session_id" to previous,
                    "last_active_at" to Timestamp.format(lastActive),
                ),
                now,
                SessionOverride.Explicit(null),
            )
            steps += sessionEnd(previous, endedAt = lastActive, reason = "unclean_exit")
            needsNewSession = true
        } else if (previous != null && backgroundedAt != null) {
            // Returning from the background, in this process (warm) or a new one (cold).
            // "More than the timeout" ends the session; exactly the timeout still continues it.
            if (now - backgroundedAt > sessionTimeoutMillis) {
                steps += sessionEnd(previous, endedAt = backgroundedAt, reason = "timeout")
                needsNewSession = true
            }
        } else if (previous != null) {
            // A session with neither a foreground nor a background mark: corrupt or partial state.
            needsNewSession = true
        }

        if (needsNewSession) {
            val id = makeSessionId()
            state = state.copy(sessionId = id, sessionStartedAt = now)
            steps += LifecycleStep.SetSession(id)
            steps += LifecycleStep.Emit(EventName.SESSION_START, mapOf("context" to context()), now)
        } else {
            steps += LifecycleStep.SetSession(state.sessionId)
        }

        steps += LifecycleStep.Emit(EventName.APP_OPEN, mapOf("start_type" to if (isCold) "cold" else "warm"), now)

        state = state.copy(inForeground = true, backgroundedAt = null, lastActiveAt = now)
        save()
        return steps
    }

    /** The app moved to the background (last started activity stopped, not for a configuration change). */
    fun didEnterBackground(): List<LifecycleStep> {
        if (!state.inForeground || !hasActivatedThisProcess) return emptyList()
        val now = clock()
        state = state.copy(inForeground = false, backgroundedAt = now, lastActiveAt = now)
        save()
        return listOf(LifecycleStep.Emit(EventName.APP_BACKGROUND, emptyMap(), now))
    }

    /**
     * Marks the app as alive in the foreground right now. Called from a slow heartbeat and on
     * activity pause; the last value becomes `last_active_at` if this run ends uncleanly.
     */
    fun recordActivity() {
        if (!state.inForeground || !hasActivatedThisProcess) return
        state = state.copy(lastActiveAt = clock())
        save()
    }

    /**
     * Forgets the persisted state (opt-out, or state restored from another install). Keeps
     * [hasActivatedThisProcess], so the next activation of this process is a warm open with a new
     * session, and no `session_end` or `unclean_exit` refers to the forgotten session.
     */
    fun reset() {
        state = LifecycleState()
        prefs.edit().remove(STATE_KEY).apply()
    }

    private fun sessionEnd(id: String, endedAt: Long, reason: String): LifecycleStep {
        val started = state.sessionStartedAt ?: endedAt
        return LifecycleStep.Emit(
            EventName.SESSION_END,
            mapOf("duration_ms" to max(0L, endedAt - started), "reason" to reason),
            endedAt,
            SessionOverride.Explicit(id),
        )
    }

    /** `apply()`: the in-memory value changes now, the disk write happens on a framework thread. */
    private fun save() {
        prefs.edit().putString(STATE_KEY, state.toJson()).apply()
    }

    companion object {
        const val STATE_KEY: String = "lifecycle_state"

        /** Background time after which a session ends. Fixed (team decision, 2026-11-14); same on iOS. */
        const val SESSION_TIMEOUT_MILLIS: Long = 30_000L
    }
}
