package dev.renoo.sdk.core

import android.content.Context
import android.content.SharedPreferences
import dev.renoo.sdk.Configuration
import dev.renoo.sdk.collectors.InstallIdentity

/** Everything a collector needs. One instance per configured SDK. */
internal class SdkEnvironment(
    val configuration: Configuration,
    /** Where events go: `pipeline.track(EventName.X, mapOf("key" to value))`. */
    val pipeline: EventPipeline,
    /** The SDK's own SharedPreferences file (`dev.renoo.sdk`). Read and write it on [executor]. */
    val prefs: SharedPreferences,
    val logger: SdkLogger,
    /** The SDK's single background thread. Disk, Binder and Billing work goes here, never on the main thread. */
    val executor: SdkExecutor,
    /** Always the application context. */
    val appContext: Context,
    val identity: InstallIdentity,
)

/**
 * A source of events. [start] is called once, on the main thread, from `SdkCore.start()`.
 * It must return quickly: post anything slow to `environment.executor`. Exceptions thrown by
 * [start] are caught and logged, and never stop the other collectors.
 */
internal interface Collector {
    fun start()
}

/** Why stored state is being cleared. */
internal enum class ClearReason {
    /** `Renoo.optOut()`: delete what we stored about the user; tracking may resume after `optIn`. */
    OPT_OUT,

    /**
     * The SDK prefs were restored by Android's backup into a different install (reinstall or new
     * device). Nothing in them describes this install. Runs before any collector work on the executor.
     */
    RESTORED_FROM_ANOTHER_INSTALL,
}

/**
 * A component that persists state in the SDK prefs. Each owner deletes its own keys, so the
 * pipeline never needs to know them. Called on the SDK executor; must not throw (the caller guards
 * it anyway) and must not write the cleared keys again while the user is opted out.
 */
internal fun interface StateOwner {
    fun clearState(reason: ClearReason)
}
