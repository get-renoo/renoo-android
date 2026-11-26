package dev.renoo.sdk.core

import android.util.Log
import dev.renoo.sdk.LogLevel

/**
 * Logging that never throws. Messages are built lazily, only when the level is enabled.
 * [sink] defaults to Logcat; tests pass their own (the JVM test classpath has no working `Log`).
 */
internal class SdkLogger(
    private val level: LogLevel,
    private val sink: (priority: Int, message: String) -> Unit = ::logcat,
) {
    fun error(message: () -> String) = emit(LogLevel.ERROR, Log.ERROR, message)

    fun error(message: () -> String, throwable: Throwable) =
        emit(LogLevel.ERROR, Log.ERROR) { "${message()}: $throwable" }

    fun info(message: () -> String) = emit(LogLevel.INFO, Log.INFO, message)

    fun debug(message: () -> String) = emit(LogLevel.DEBUG, Log.DEBUG, message)

    private inline fun emit(required: LogLevel, priority: Int, message: () -> String) {
        if (level == LogLevel.NONE || level.ordinal < required.ordinal) return
        try {
            sink(priority, message())
        } catch (t: Throwable) {
            // Logging must never take the host app down.
        }
    }

    companion object {
        const val TAG: String = "Renoo"

        private fun logcat(priority: Int, message: String) {
            Log.println(priority, TAG, message)
        }
    }
}
