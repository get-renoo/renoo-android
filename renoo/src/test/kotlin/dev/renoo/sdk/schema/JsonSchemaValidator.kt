package dev.renoo.sdk.schema

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger

/**
 * A minimal JSON Schema (2020-12) validator for the event schema, so tests need no new dependency.
 * It supports exactly the keywords that schema uses: `type`, `enum`, `const`, `pattern`,
 * `minLength`, `minimum`, `required`, `properties`, `additionalProperties`, `$ref` (`#` and
 * `#/$defs/...`, applied alongside sibling keywords), `allOf`, `anyOf`, `not`, `if` / `then` /
 * `else`, `items`, `contains` and `uniqueItems`. `format` is an annotation only (the schema's
 * patterns cover it). Any other keyword fails loudly, so a schema change can't be ignored silently.
 */
internal class JsonSchemaValidator(private val root: JSONObject) {

    /** Error messages (JSON pointer of the value, then the reason); empty when [value] is valid. */
    fun validate(value: Any?): List<String> = validate(root, normalize(value), "")

    private fun validate(schema: Any?, value: Any?, path: String): List<String> {
        if (schema == true) return emptyList()
        if (schema == false) return listOf("$path: not allowed")
        val s = schema as? JSONObject ?: error("schema at $path is not an object or boolean")
        val errors = mutableListOf<String>()
        for (keyword in s.keyList()) {
            if (keyword !in KNOWN_KEYWORDS) error("unsupported keyword '$keyword' (at $path)")
        }

        s.opt("\$ref")?.let { errors += validate(resolve(it as String), value, path) }
        s.opt("type")?.let { type ->
            val types = if (type is JSONArray) List(type.length()) { type.getString(it) } else listOf(type as String)
            if (types.none { matchesType(it, value) }) errors += "$path: expected $types, got ${describe(value)}"
        }
        s.opt("enum")?.let { options ->
            options as JSONArray
            if ((0 until options.length()).none { jsonEquals(normalize(options.get(it)), value) }) {
                errors += "$path: ${describe(value)} not in $options"
            }
        }
        if (s.has("const") && !jsonEquals(normalize(s.get("const")), value)) {
            errors += "$path: ${describe(value)} is not ${s.get("const")}"
        }
        if (value is String) {
            s.opt("pattern")?.let { if (!Regex(it as String).containsMatchIn(value)) errors += "$path: '$value' does not match $it" }
            s.opt("minLength")?.let { if (value.codePointCount(0, value.length) < (it as Number).toInt()) errors += "$path: shorter than $it" }
        }
        if (value is Number && s.has("minimum")) {
            if (toDecimal(value) < toDecimal(s.get("minimum") as Number)) errors += "$path: $value is below ${s.get("minimum")}"
        }
        if (value is JSONObject) errors += validateObject(s, value, path)
        if (value is JSONArray) errors += validateArray(s, value, path)

        s.optJSONArray("allOf")?.let { all -> for (i in 0 until all.length()) errors += validate(all.get(i), value, path) }
        s.optJSONArray("anyOf")?.let { any ->
            if ((0 until any.length()).none { validate(any.get(it), value, path).isEmpty() }) errors += "$path: matches no anyOf branch"
        }
        if (s.has("not") && validate(s.get("not"), value, path).isEmpty()) errors += "$path: must not match ${s.get("not")}"
        if (s.has("if")) {
            val branch = if (validate(s.get("if"), value, path).isEmpty()) "then" else "else"
            if (s.has(branch)) errors += validate(s.get(branch), value, path)
        }
        return errors
    }

    private fun validateObject(s: JSONObject, value: JSONObject, path: String): List<String> {
        val errors = mutableListOf<String>()
        s.optJSONArray("required")?.let { required ->
            for (i in 0 until required.length()) {
                val key = required.getString(i)
                if (!value.has(key)) errors += "$path: missing required '$key'"
            }
        }
        val properties = s.optJSONObject("properties")
        properties?.keyList()?.forEach { key ->
            if (value.has(key)) errors += validate(properties.get(key), normalize(value.get(key)), "$path/$key")
        }
        if (s.has("additionalProperties")) {
            val additional = s.get("additionalProperties")
            for (key in value.keyList()) {
                if (properties?.has(key) == true) continue
                errors += validate(additional, normalize(value.get(key)), "$path/$key").let { found ->
                    if (additional == false) listOf("$path: unexpected property '$key'") else found
                }
            }
        }
        return errors
    }

    private fun validateArray(s: JSONObject, value: JSONArray, path: String): List<String> {
        val errors = mutableListOf<String>()
        val items = List(value.length()) { normalize(value.get(it)) }
        if (s.has("items")) items.forEachIndexed { i, item -> errors += validate(s.get("items"), item, "$path/$i") }
        if (s.has("contains") && items.none { validate(s.get("contains"), it, path).isEmpty() }) {
            errors += "$path: no item matches contains ${s.get("contains")}"
        }
        if (s.optBoolean("uniqueItems", false)) {
            for (i in items.indices) for (j in i + 1 until items.size) {
                if (jsonEquals(items[i], items[j])) errors += "$path: items $i and $j are equal"
            }
        }
        return errors
    }

    private fun resolve(ref: String): Any {
        require(ref == "#" || ref.startsWith("#/")) { "only local refs are supported: $ref" }
        var node: Any = root
        for (token in ref.removePrefix("#").split("/").filter { it.isNotEmpty() }) {
            val key = token.replace("~1", "/").replace("~0", "~")
            node = (node as JSONObject).get(key)
        }
        return node
    }

    companion object {
        private val KNOWN_KEYWORDS = setOf(
            "\$schema", "\$id", "\$comment", "\$defs", "title", "description", "format",
            "\$ref", "type", "enum", "const", "pattern", "minLength", "minimum",
            "required", "properties", "additionalProperties", "allOf", "anyOf", "not", "if", "then", "else",
            "items", "contains", "uniqueItems",
        )

        /** Environment variable with the absolute path to `event-v1.schema.json`. */
        const val SCHEMA_ENV: String = "RENOO_EVENT_SCHEMA"

        /** The event schema named by [SCHEMA_ENV], or `null` when the variable is unset or the file is missing. */
        fun findEventSchemaV1(): File? {
            val path = System.getenv(SCHEMA_ENV)?.takeIf { it.isNotBlank() } ?: return null
            return File(path).takeIf { it.isFile }
        }

        fun load(file: File): JsonSchemaValidator = JsonSchemaValidator(JSONObject(file.readText()))

        /** `JSONObject.NULL` and Kotlin `null` are the same JSON value. */
        private fun normalize(value: Any?): Any? = if (value == null || value == JSONObject.NULL) null else value

        private fun matchesType(type: String, value: Any?): Boolean = when (type) {
            "null" -> value == null
            "boolean" -> value is Boolean
            "string" -> value is String
            "object" -> value is JSONObject
            "array" -> value is JSONArray
            "number" -> value is Number
            "integer" -> value is Number && isInteger(value)
            else -> error("unknown type $type")
        }

        private fun isInteger(value: Number): Boolean = when (value) {
            is Int, is Long, is Short, is Byte, is BigInteger -> true
            else -> toDecimal(value).stripTrailingZeros().scale() <= 0
        }

        private fun toDecimal(value: Number): BigDecimal = when (value) {
            is BigDecimal -> value
            is BigInteger -> BigDecimal(value)
            is Int, is Long, is Short, is Byte -> BigDecimal.valueOf(value.toLong())
            else -> BigDecimal(value.toString())
        }

        fun jsonEquals(a: Any?, b: Any?): Boolean {
            val x = normalize(a)
            val y = normalize(b)
            return when {
                x == null || y == null -> x == null && y == null
                x is Number && y is Number -> toDecimal(x).compareTo(toDecimal(y)) == 0
                x is JSONArray && y is JSONArray ->
                    x.length() == y.length() && (0 until x.length()).all { jsonEquals(x.get(it), y.get(it)) }
                x is JSONObject && y is JSONObject ->
                    x.keyList().toSet() == y.keyList().toSet() && x.keyList().all { jsonEquals(x.get(it), y.get(it)) }
                else -> x == y
            }
        }

        /** Keys of a JSON object (Android's `org.json` API has no `keySet()`). */
        private fun JSONObject.keyList(): List<String> = keys().asSequence().toList()

        private fun describe(value: Any?): String = when (value) {
            null -> "null"
            is String -> "\"$value\""
            else -> value.toString()
        }
    }
}
