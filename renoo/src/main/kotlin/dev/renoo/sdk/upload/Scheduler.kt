package dev.renoo.sdk.upload

import dev.renoo.sdk.core.SdkLogger
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A cancellable delayed task. */
internal fun interface Cancellable {
    fun cancel()
}

/** Delayed tasks for the uploader (foreground timer, retries, request watchdog). Tests use a fake clock. */
internal interface Scheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): Cancellable
}

/**
 * One daemon timer thread, created on first use (only when uploading is enabled). Its tasks are
 * tiny: they post work to the SDK executor or abort a stuck connection. Every task is guarded.
 * Delays use a monotonic clock that pauses in deep sleep, so timers never wake the device.
 */
internal class TimerScheduler(private val logger: SdkLogger) : Scheduler {
    private val service by lazy {
        ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "Renoo-timer").also {
                it.isDaemon = true
                it.priority = Thread.NORM_PRIORITY - 1
            }
        }.apply { removeOnCancelPolicy = true }
    }

    override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable = try {
        val future: ScheduledFuture<*> = service.schedule({
            try {
                task()
            } catch (t: Throwable) {
                logger.error({ "SDK timer task failed" }, t)
            }
        }, delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        Cancellable { future.cancel(false) }
    } catch (t: Throwable) {
        logger.error({ "Could not schedule an SDK timer" }, t)
        Cancellable { }
    }
}
