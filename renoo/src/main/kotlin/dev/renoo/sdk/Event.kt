package dev.renoo.sdk

import dev.renoo.sdk.core.JsonValues
import org.json.JSONObject

/** Names of every event the SDK emits (event schema v1). */
public enum class EventName(public val wireValue: String) {
    INSTALL("install"),
    APP_OPEN("app_open"),
    APP_BACKGROUND("app_background"),
    SESSION_START("session_start"),
    SESSION_END("session_end"),
    UNCLEAN_EXIT("unclean_exit"),
    SCREEN_VIEW("screen_view"),
    SUBSCRIPTION_STATE("subscription_state"),
    TRANSACTION("transaction"),
    PUSH_PERMISSION_CHANGED("push_permission_changed"),
    IDENTIFY("identify"),
    ;

    public companion object {
        /** The event name for a wire value, or `null` when unknown. */
        @JvmStatic
        public fun fromWireValue(value: String): EventName? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * One captured event: a fixed envelope plus event-specific [properties].
 *
 * [properties] is a JSON-shaped map: values are `String`, `Boolean`, `Int`/`Long`/`Double`,
 * `null`, nested `Map<String, Any?>` or `List<Any?>`. Optional values are explicit `null`.
 */
@ConsistentCopyVisibility
public data class Event internal constructor(
    public val eventId: String,
    public val name: EventName,
    /** ISO 8601 UTC with milliseconds: when the event happened on the device. */
    public val timestamp: String,
    /** Increases by one per committed event, per install. */
    public val seq: Long,
    public val installId: String,
    /**
     * Random lowercase UUID v4 for this install's current anonymous identity. Always set. A new one
     * is made on [Renoo.reset] and when the SDK's storage is new (reinstall, cleared data, restored
     * backup).
     */
    public val anonymousId: String,
    public val userId: String?,
    public val sessionId: String?,
    public val schemaVersion: Int,
    public val sdkVersion: String,
    public val platform: String,
    public val appVersion: String,
    public val appBuild: String,
    public val osVersion: String,
    public val properties: Map<String, Any?>,
) {
    /**
     * The event as a map with snake_case keys and explicit `null`s, nested maps and lists for
     * `properties`. Only types Flutter's StandardMessageCodec understands; send it over a
     * platform channel as is.
     */
    public fun toMap(): Map<String, Any?> = linkedMapOf(
        "event_id" to eventId,
        "name" to name.wireValue,
        "timestamp" to timestamp,
        "seq" to seq,
        "install_id" to installId,
        "anonymous_id" to anonymousId,
        "user_id" to userId,
        "session_id" to sessionId,
        "schema_version" to schemaVersion,
        "sdk_version" to sdkVersion,
        "platform" to platform,
        "app_version" to appVersion,
        "app_build" to appBuild,
        "os_version" to osVersion,
        "properties" to properties,
    )

    /** Compact JSON, keys sorted, optional values written as `null`. One line of the on-disk queue. */
    public fun toJsonString(): String = JsonValues.write(toMap())

    /** The event as an `org.json` object (`null`s are `JSONObject.NULL`). */
    public fun toJson(): JSONObject = JSONObject(toJsonString())

    public companion object {
        public const val SCHEMA_VERSION: Int = 1
        public const val PLATFORM: String = "android"

        /** Parses one queue line. Returns `null` for anything that isn't a valid event. */
        internal fun fromJsonString(line: String): Event? = try {
            val json = JSONObject(line)
            val name = EventName.fromWireValue(json.getString("name"))
            if (name == null) {
                null
            } else {
                Event(
                    eventId = json.getString("event_id"),
                    name = name,
                    timestamp = json.getString("timestamp"),
                    seq = json.getLong("seq"),
                    installId = json.getString("install_id"),
                    anonymousId = json.getString("anonymous_id"),
                    userId = json.optionalString("user_id"),
                    sessionId = json.optionalString("session_id"),
                    schemaVersion = json.getInt("schema_version"),
                    sdkVersion = json.getString("sdk_version"),
                    platform = json.getString("platform"),
                    appVersion = json.getString("app_version"),
                    appBuild = json.getString("app_build"),
                    osVersion = json.getString("os_version"),
                    properties = JsonValues.fromJsonObject(json.getJSONObject("properties")),
                )
            }
        } catch (e: Exception) {
            null
        }

        private fun JSONObject.optionalString(key: String): String? =
            if (!has(key) || isNull(key)) null else getString(key)
    }
}
