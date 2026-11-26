package dev.renoo.sdk.core

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** ISO 8601 UTC with milliseconds, e.g. `2023-11-14T22:13:20.000Z`. Thread-safe. (`java.time` needs API 26.) */
internal object Timestamp {
    private val formats = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = newFormat()
    }

    fun format(epochMillis: Long): String = formatter().format(Date(epochMillis))

    /** Epoch millis for a timestamp written by [format], or `null`. */
    fun parse(value: String): Long? = try {
        formatter().parse(value)?.time
    } catch (e: ParseException) {
        null
    }

    private fun formatter(): SimpleDateFormat = formats.get() ?: newFormat()

    private fun newFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
}
