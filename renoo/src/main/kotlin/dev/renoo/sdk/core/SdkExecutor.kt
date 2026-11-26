package dev.renoo.sdk.core

import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The SDK's single background thread. Every task runs in order, one at a time, and every
 * exception a task throws is caught and logged: an uncaught exception on this thread would
 * crash the host app.
 *
 * Do disk, Binder (PackageManager, ContentResolver) and Billing work here, never on the main thread.
 */
internal class SdkExecutor(
    private val logger: SdkLogger,
    threadName: String = "Renoo",
) : Executor {
    @Volatile
    private var thread: Thread? = null

    private val service: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName).also {
            it.isDaemon = true
            it.priority = Thread.NORM_PRIORITY - 1
            thread = it
        }
    }

    /** True when called from this executor's thread (e.g. inside an event observer). */
    val isCurrentThread: Boolean
        get() = Thread.currentThread() === thread

    override fun execute(command: Runnable) {
        try {
            service.execute {
                try {
                    command.run()
                } catch (t: Throwable) {
                    logger.error({ "SDK task failed" }, t)
                }
            }
        } catch (t: Throwable) {
            logger.error({ "Could not schedule SDK work" }, t)
        }
    }

    /** Kotlin-friendly [execute]. */
    fun post(block: () -> Unit) = execute(Runnable(block))

    /**
     * Runs [block] on this executor and waits for its result, or runs it inline when already on
     * the executor (so observers can call blocking getters without deadlocking).
     * Returns [fallback] if it fails or takes longer than [timeoutMillis]. **Blocks the caller**:
     * debugging and test paths only. If the caller is interrupted while waiting, its interrupt
     * flag is restored (the SDK must not swallow the host app's interrupts).
     */
    fun <T> runAndWait(fallback: T, timeoutMillis: Long = DEFAULT_WAIT_MILLIS, block: () -> T): T {
        if (isCurrentThread) {
            return try {
                block()
            } catch (t: Throwable) {
                logger.error({ "SDK task failed" }, t)
                fallback
            }
        }
        return try {
            service.submit(Callable { block() }).get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.error({ "Interrupted while waiting for the SDK thread" }, e)
            fallback
        } catch (t: Throwable) {
            logger.error({ "SDK task failed or timed out" }, t)
            fallback
        }
    }

    companion object {
        const val DEFAULT_WAIT_MILLIS: Long = 5_000
    }
}
