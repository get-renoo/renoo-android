package dev.renoo.sdk.collectors

import dev.renoo.sdk.EventName
import dev.renoo.sdk.core.FakeSharedPreferences
import dev.renoo.sdk.core.SessionOverride
import dev.renoo.sdk.core.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session rules, ported from the iOS `LifecycleEngineTests`. Plain JVM. */
class LifecycleEngineTest {
    private val prefs = FakeSharedPreferences()
    private var now = 1_800_000_000_000L
    private var nextId = 0

    /** A new engine on the same prefs = a new process. */
    private fun launch(timeoutMillis: Long = LifecycleEngine.SESSION_TIMEOUT_MILLIS) = LifecycleEngine(
        prefs = prefs,
        sessionTimeoutMillis = timeoutMillis,
        clock = { now },
        makeSessionId = { "s${++nextId}" },
        context = { mapOf("device_model" to "test") },
    )

    private fun advance(millis: Long) {
        now += millis
    }

    private fun names(steps: List<LifecycleStep>): List<String> = steps.map {
        when (it) {
            is LifecycleStep.SetSession -> "set:${it.id}"
            is LifecycleStep.Emit -> it.name.wireValue
        }
    }

    private fun emitted(name: EventName, steps: List<LifecycleStep>): LifecycleStep.Emit? =
        steps.filterIsInstance<LifecycleStep.Emit>().firstOrNull { it.name == name }

    // Cold start

    @Test
    fun firstColdStartOpensSessionWithContextAndNoForeground() {
        val steps = launch().didBecomeActive()
        assertEquals(listOf("set:s1", "session_start", "app_open"), names(steps))
        assertEquals(mapOf("device_model" to "test"), emitted(EventName.SESSION_START, steps)?.properties?.get("context"))
        assertEquals("cold", emitted(EventName.APP_OPEN, steps)?.properties?.get("start_type"))
        assertEquals(SessionOverride.Current, emitted(EventName.SESSION_START, steps)?.session)
    }

    @Test
    fun backgroundBeforeFirstActivationIsIgnored() {
        // Process started for a receiver or service: no activation yet, nothing to report.
        val engine = launch()
        assertTrue(engine.didEnterBackground().isEmpty())
        assertEquals(listOf("set:s1", "session_start", "app_open"), names(engine.didBecomeActive()))
    }

    @Test
    fun repeatedActivationWithoutBackgroundIsNotANewOpen() {
        val engine = launch()
        engine.didBecomeActive()
        advance(5_000)
        assertTrue(engine.didBecomeActive().isEmpty())
    }

    // Warm returns and the timeout

    @Test
    fun resumeWithinTimeoutContinuesSession() {
        val engine = launch()
        engine.didBecomeActive()
        advance(10_000)
        assertEquals(listOf("app_background"), names(engine.didEnterBackground()))
        advance(20_000)
        val steps = engine.didBecomeActive()
        assertEquals(listOf("set:s1", "app_open"), names(steps))
        assertEquals("warm", emitted(EventName.APP_OPEN, steps)?.properties?.get("start_type"))
    }

    @Test
    fun exactlyTimeoutContinuesSession() {
        val engine = launch()
        engine.didBecomeActive()
        engine.didEnterBackground()
        advance(30_000)
        assertEquals(listOf("set:s1", "app_open"), names(engine.didBecomeActive()))
    }

    @Test
    fun justOverTimeoutEndsSessionAtBackgroundTime() {
        val engine = launch()
        engine.didBecomeActive()
        advance(42_500)
        val backgroundedAt = now
        engine.didEnterBackground()
        advance(30_001)
        val steps = engine.didBecomeActive()
        assertEquals(listOf("session_end", "set:s2", "session_start", "app_open"), names(steps))

        val end = emitted(EventName.SESSION_END, steps)
        assertNotNull(end)
        assertEquals(backgroundedAt, end?.timestampMillis)
        assertEquals(SessionOverride.Explicit("s1"), end?.session)
        assertEquals("timeout", end?.properties?.get("reason"))
        assertEquals(42_500L, end?.properties?.get("duration_ms"))
        assertEquals(SessionOverride.Current, emitted(EventName.SESSION_START, steps)?.session)
    }

    @Test
    fun timeoutIsFixedAtThirtySeconds() {
        assertEquals(30_000L, LifecycleEngine.SESSION_TIMEOUT_MILLIS)
    }

    /** The injected value is an internal test seam; nothing public sets it. */
    @Test
    fun customTimeout() {
        val engine = launch(timeoutMillis = 300_000)
        engine.didBecomeActive()
        engine.didEnterBackground()
        advance(299_000)
        assertFalse(names(engine.didBecomeActive()).contains("session_end"))
        engine.didEnterBackground()
        advance(301_000)
        assertTrue(names(engine.didBecomeActive()).contains("session_end"))
    }

    // Cold start after a clean background

    @Test
    fun coldStartAfterBackgroundBeyondTimeoutEndsOldSession() {
        val first = launch()
        first.didBecomeActive()
        advance(60_000)
        val backgroundedAt = now
        first.didEnterBackground()
        advance(3_600_000) // process killed in the background; reopened an hour later

        val steps = launch().didBecomeActive()
        assertEquals(listOf("session_end", "set:s2", "session_start", "app_open"), names(steps))
        val end = emitted(EventName.SESSION_END, steps)
        assertEquals(backgroundedAt, end?.timestampMillis)
        assertEquals(SessionOverride.Explicit("s1"), end?.session)
        assertEquals(60_000L, end?.properties?.get("duration_ms"))
        assertEquals("cold", emitted(EventName.APP_OPEN, steps)?.properties?.get("start_type"))
        assertNull(emitted(EventName.UNCLEAN_EXIT, steps))
    }

    @Test
    fun coldStartWithinTimeoutContinuesSession() {
        val first = launch()
        first.didBecomeActive()
        first.didEnterBackground()
        advance(10_000) // swiped away from Recents and relaunched quickly

        val steps = launch().didBecomeActive()
        assertEquals(listOf("set:s1", "app_open"), names(steps))
        assertEquals("cold", emitted(EventName.APP_OPEN, steps)?.properties?.get("start_type"))
    }

    // Unclean exit

    @Test
    fun coldStartAfterForegroundKillReportsUncleanExit() {
        val first = launch()
        val startedAt = now
        first.didBecomeActive()
        advance(45_000)
        first.recordActivity() // heartbeat or activity pause
        val lastActive = now
        advance(20_000) // killed here, with no background
        advance(600_000)

        val relaunchAt = now
        val steps = launch().didBecomeActive()
        assertEquals(listOf("unclean_exit", "session_end", "set:s2", "session_start", "app_open"), names(steps))

        val unclean = emitted(EventName.UNCLEAN_EXIT, steps)
        assertEquals("s1", unclean?.properties?.get("previous_session_id"))
        assertEquals(Timestamp.format(lastActive), unclean?.properties?.get("last_active_at"))
        assertEquals(relaunchAt, unclean?.timestampMillis)
        assertEquals(SessionOverride.Explicit(null), unclean?.session)

        val end = emitted(EventName.SESSION_END, steps)
        assertEquals("unclean_exit", end?.properties?.get("reason"))
        assertEquals(lastActive, end?.timestampMillis)
        assertEquals(SessionOverride.Explicit("s1"), end?.session)
        assertEquals(lastActive - startedAt, end?.properties?.get("duration_ms"))
    }

    @Test
    fun crashRightAfterWarmReturnIsUnclean() {
        val first = launch()
        first.didBecomeActive()
        first.didEnterBackground()
        advance(5_000)
        first.didBecomeActive()
        advance(5_000) // crash
        assertEquals("unclean_exit", names(launch().didBecomeActive()).first())
    }

    @Test
    fun noUncleanExitAfterCleanBackgroundOrOnFirstRun() {
        assertFalse(names(launch().didBecomeActive()).contains("unclean_exit"))
        val engine = launch() // the previous run never went to the background: unclean
        assertTrue(names(engine.didBecomeActive()).contains("unclean_exit"))
        engine.didEnterBackground()
        advance(1_000)
        assertFalse(names(launch().didBecomeActive()).contains("unclean_exit"))
    }

    @Test
    fun uncleanExitIsReportedOnlyOnce() {
        launch().didBecomeActive()
        val second = launch()
        assertTrue(names(second.didBecomeActive()).contains("unclean_exit"))
        second.didEnterBackground()
        assertFalse(names(launch().didBecomeActive()).contains("unclean_exit"))
    }

    @Test
    fun processWithoutUiLeavesStateUntouched() {
        // A receiver-started process creates an engine but never activates: the next UI launch
        // still sees the earlier clean background, not an unclean exit.
        val first = launch()
        first.didBecomeActive()
        first.didEnterBackground()
        val receiverProcess = launch()
        receiverProcess.recordActivity()
        assertTrue(receiverProcess.didEnterBackground().isEmpty())
        advance(5_000)
        assertEquals(listOf("set:s1", "app_open"), names(launch().didBecomeActive()))
    }

    // Robustness

    @Test
    fun clockGoingBackwardsNeverProducesNegativeDuration() {
        val engine = launch()
        engine.didBecomeActive()
        advance(-100_000)
        engine.didEnterBackground()
        advance(1_000_000)
        assertEquals(0L, emitted(EventName.SESSION_END, engine.didBecomeActive())?.properties?.get("duration_ms"))
    }

    @Test
    fun corruptPersistedStateStartsFresh() {
        prefs.edit().putString(LifecycleEngine.STATE_KEY, "not json").apply()
        assertEquals(listOf("set:s1", "session_start", "app_open"), names(launch().didBecomeActive()))
    }

    @Test
    fun stateRoundTripsThroughJson() {
        val state = LifecycleState("s9", 1L, inForeground = true, backgroundedAt = null, lastActiveAt = 3L)
        assertEquals(state, LifecycleState.fromJson(state.toJson()))
        assertEquals(LifecycleState(), LifecycleState.fromJson(LifecycleState().toJson()))
        assertNull(LifecycleState.fromJson("{}"))
    }

    @Test
    fun resetForgetsTheSessionAndTheNextActivationIsAWarmOpenWithANewSession() {
        val engine = launch()
        engine.didBecomeActive()
        engine.reset() // opt-out while in the foreground
        assertNull(prefs.getString(LifecycleEngine.STATE_KEY, null))
        assertTrue("nothing to report for a forgotten session", engine.didEnterBackground().isEmpty())

        advance(5_000)
        val steps = engine.didBecomeActive()
        assertEquals(listOf("set:s2", "session_start", "app_open"), names(steps))
        assertEquals("warm", emitted(EventName.APP_OPEN, steps)?.properties?.get("start_type"))
        assertNull("no session_end for the forgotten session", emitted(EventName.SESSION_END, steps))
    }
}
