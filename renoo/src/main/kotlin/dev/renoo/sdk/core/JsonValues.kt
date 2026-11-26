package dev.renoo.sdk.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Event properties are plain Kotlin values: `String`, `Boolean`, `Int`/`Long`/`Double` (any
 * `Number`), `null`, `Map<String, Any?>`, `List<Any?>` (any `Collection` or `Array`), nested freely.
 * `JSONObject` / `JSONArray` / `JSONObject.NULL` are accepted too. Anything else is stored as its
 * `toString()`; non-finite numbers become `null`.
 *
 * [normalize] turns any of that into an immutable canonical copy (maps, lists, String, Boolean,
 * Long or Int, Double, null). [write] produces compact JSON with sorted keys and explicit nulls,
 * without depending on the platform's `org.json` formatting.
 */
internal object JsonValues {

    fun normalize(properties: Map<String, Any?>): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>(properties.size)
        for ((key, value) in properties) result[key] = normalizeValue(value)
        return result
    }

    fun normalizeValue(value: Any?): Any? = when (value) {
        null -> null
        JSONObject.NULL -> null
        is String -> value
        is Boolean -> value
        is Int, is Long -> value
        is Short -> value.toInt()
        is Byte -> value.toInt()
        is Float -> if (value.isFinite()) value.toDouble() else null
        is Double -> if (value.isFinite()) value else null
        is Number -> {
            val asDouble = value.toDouble()
            if (asDouble.isFinite()) asDouble else null
        }
        is Map<*, *> -> {
            val map = LinkedHashMap<String, Any?>(value.size)
            for ((k, v) in value) map[k.toString()] = normalizeValue(v)
            map
        }
        is JSONObject -> {
            val map = LinkedHashMap<String, Any?>()
            for (key in value.keys()) map[key] = normalizeValue(value.opt(key))
            map
        }
        is JSONArray -> List(value.length()) { normalizeValue(value.opt(it)) }
        is Collection<*> -> value.map { normalizeValue(it) }
        is Array<*> -> value.map { normalizeValue(it) }
        is Enum<*> -> value.name
        else -> value.toString()
    }

    /** Compact JSON for a normalized value (see [normalize]). Keys are sorted. */
    fun write(value: Any?): String = StringBuilder().also { append(it, normalizeValue(value)) }.toString()

    /** Converts a parsed `JSONObject` back into a normalized map (`JSONObject.NULL` → `null`). */
    fun fromJsonObject(json: JSONObject): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return normalizeValue(json) as Map<String, Any?>
    }

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> appendString(out, value)
            is Boolean, is Int, is Long, is Double -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                value.keys.map { it.toString() }.sorted().forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    appendString(out, key)
                    out.append(':')
                    append(out, value[key])
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    append(out, item)
                }
                out.append(']')
            }
            else -> appendString(out, value.toString())
        }
    }

    private fun appendString(out: StringBuilder, value: String) {
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                ' ' -> out.append("\\u2028")
                ' ' -> out.append("\\u2029")
                else -> if (c < ' ') out.append(String.format(java.util.Locale.US, "\\u%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
    }
}
