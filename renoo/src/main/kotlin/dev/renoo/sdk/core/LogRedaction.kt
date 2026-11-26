package dev.renoo.sdk.core

import dev.renoo.sdk.Event

/**
 * Masks secrets in log output only. Events on disk and those passed to observers keep the full
 * values. A `purchase_token` is a bearer credential for the Play Developer API: Logcat is readable by
 * anyone with ADB and ends up in bug reports, so only a recognisable prefix is logged.
 */
internal object LogRedaction {
    private val SECRET_KEYS = setOf("purchase_token")
    private const val VISIBLE_PREFIX = 6

    /** The event's JSON, with every [SECRET_KEYS] value (at any depth in `properties`) masked. */
    fun forLog(event: Event): String = event.copy(properties = redactMap(event.properties)).toJsonString()

    fun mask(value: String): String = if (value.length <= VISIBLE_PREFIX) "…" else value.take(VISIBLE_PREFIX) + "…"

    private fun redactMap(map: Map<String, Any?>): Map<String, Any?> = map.mapValues { (key, value) ->
        if (key in SECRET_KEYS && value is String) mask(value) else redact(value)
    }

    private fun redact(value: Any?): Any? = when (value) {
        is Map<*, *> -> redactMap(value.entries.associate { (k, v) -> k.toString() to v })
        is List<*> -> value.map(::redact)
        else -> value
    }
}
