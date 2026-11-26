package dev.renoo.sdk.core

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The kill switch: the backend blocks the app's API key, and any
 * `401` (from `GET /health-check`, `POST /events/sdk` or `GET /offers`) engages it. Engaged, the
 * SDK stops capturing, drops the on-disk queue and the in-memory buffer, skips `GET /offers`,
 * clears the pending offer and sends nothing.
 *
 * **In memory only.** Nothing is persisted: the next start-up (process restart) runs the health
 * check again and, if the key is unblocked, everything works as before.
 *
 * Local state keeps working while engaged (`identify`, `reset`, `optOut` / `optIn` still update
 * the stored user ID, anonymous ID and opt-out flag), so the next start-up begins with the right
 * identity; only events are dropped.
 */
internal class KillSwitch(private val logger: SdkLogger) {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Readable from any thread. */
    @Volatile
    var isEngaged: Boolean = false
        private set

    /** Called once, on the SDK executor, when the switch is engaged. Register before `start()`. */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    /** **SDK executor only.** Idempotent. [source] names the call that got the `401`, for the log. */
    fun engage(source: String) {
        if (isEngaged) return
        isEngaged = true
        logger.error {
            "$source: the API key was refused (401). Unknown or blocked key: capture stopped and the queue " +
                "deleted until the next start-up. Check the API key."
        }
        for (listener in listeners) {
            try {
                listener()
            } catch (t: Throwable) {
                logger.error({ "Kill switch listener failed" }, t)
            }
        }
    }
}
