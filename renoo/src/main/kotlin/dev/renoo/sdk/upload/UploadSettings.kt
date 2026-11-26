package dev.renoo.sdk.upload

/**
 * Every uploader threshold, with the default values. Remote config is not built
 * yet; when it is, it produces a copy of [DEFAULT] with the server's values and hands it to the
 * uploader. Nothing else in the uploader hard-codes these numbers.
 */
internal data class UploadSettings(
    /** Flush when the queue reaches this many events. Also the threshold for flushing on cellular. */
    val flushAtQueueSize: Int = 100,
    /** Foreground timer flush interval. */
    val foregroundFlushIntervalMillis: Long = 5 * 60_000L,
    /** Events per request. */
    val maxBatchEvents: Int = 500,
    /** Compressed body limit. A bigger body is split before sending. */
    val maxCompressedBytes: Int = 512 * 1024,
    /** Decompressed body limit, respected when a batch is cut. */
    val maxDecompressedBytes: Int = 5 * 1024 * 1024,
    /** Retry backoff: base, factor 2, cap. */
    val backoffBaseMillis: Long = 30_000L,
    val backoffCapMillis: Long = 60 * 60_000L,
    /** Per-request timeouts. */
    val connectTimeoutMillis: Int = 10_000,
    val requestTimeoutMillis: Int = 30_000,
) {
    companion object {
        val DEFAULT: UploadSettings = UploadSettings()
    }
}

/**
 * Retry backoff. `failures` is the number of consecutive failed attempts (1 for the first).
 * The step doubles from [UploadSettings.backoffBaseMillis] up to the cap.
 *
 * **Jitter: "equal jitter", not pure full jitter.** The delay is uniform in `[step / 2, step]`.
 * With pure full jitter (`[0, step]`) the retry bound (at most 12 attempts in the first
 * hour) can't be guaranteed: the random draw can be near zero every time. With this floor the
 * worst case is 8 attempts in the first hour and the delay still reaches the 1 h cap.
 */
internal object Backoff {
    fun delayMillis(failures: Int, settings: UploadSettings, random: Double): Long {
        val n = failures.coerceAtLeast(1)
        var step = settings.backoffBaseMillis
        repeat(n - 1) {
            step = if (step >= settings.backoffCapMillis / 2) settings.backoffCapMillis else step * 2
        }
        step = step.coerceAtMost(settings.backoffCapMillis)
        val r = random.coerceIn(0.0, 1.0)
        return step / 2 + ((step - step / 2) * r).toLong()
    }
}
