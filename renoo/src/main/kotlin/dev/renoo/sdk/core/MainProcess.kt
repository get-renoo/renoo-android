package dev.renoo.sdk.core

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Whether this is the app's main process. The SDK runs only there: a secondary process (`:remote`
 * services, `:push`, a WorkManager or crash-reporter process) that calls `configure` would bind Play
 * Billing, emit its own `subscription_state` and race the main process on the prefs and the queue.
 */
internal object MainProcess {
    /** The current process name, or `null` when unknown. Tests replace it. */
    @Volatile
    var currentProcessName: () -> String? = ::systemProcessName

    /**
     * True in the main process (the application's `android:process`, by default the package name),
     * and when either name can't be determined: better to run than to silently do nothing.
     * No Binder call: `Application.getProcessName()` on API 28+, `/proc/self/cmdline` below that
     * (a kernel pseudo-file, not storage).
     */
    fun isMainProcess(context: Context): Boolean {
        val current = try {
            currentProcessName()
        } catch (t: Throwable) {
            null
        }?.takeIf { it.isNotBlank() } ?: return true
        val main = try {
            context.applicationInfo?.processName ?: context.packageName
        } catch (t: Throwable) {
            null
        }?.takeIf { it.isNotBlank() } ?: return true
        return current == main
    }

    private fun systemProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        return File("/proc/self/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray()
            .toString(Charsets.UTF_8).trim()
    }
}
