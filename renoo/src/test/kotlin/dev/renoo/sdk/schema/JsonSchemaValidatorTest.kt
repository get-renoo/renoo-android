package dev.renoo.sdk.schema

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Proves the test-only validator rejects what the event schema forbids. */
class JsonSchemaValidatorTest {
    private lateinit var validator: JsonSchemaValidator

    @Before
    fun setUp() {
        val file = JsonSchemaValidator.findEventSchemaV1()
        assumeTrue("RENOO_EVENT_SCHEMA not set; skipping schema validation", file != null)
        validator = JsonSchemaValidator.load(requireNotNull(file))
    }

    private fun appOpen(): JSONObject = JSONObject()
        .put("event_id", "0b6f8a52-3c1e-4d7a-9e55-1f2a3b4c5d6e")
        .put("name", "app_open")
        .put("timestamp", "2026-11-26T10:00:00.000Z")
        .put("seq", 1)
        .put("install_id", "install-1")
        .put("anonymous_id", "5f0c2d1e-8a7b-4c3d-9e1f-0a1b2c3d4e5f")
        .put("user_id", JSONObject.NULL)
        .put("session_id", JSONObject.NULL)
        .put("schema_version", 1)
        .put("sdk_version", "0.1.0")
        .put("platform", "android")
        .put("app_version", "1.0")
        .put("app_build", "1")
        .put("os_version", "16")
        .put("properties", JSONObject().put("start_type", "cold"))

    private fun expiredState(inferred: JSONArray): JSONObject = appOpen()
        .put("name", "subscription_state")
        .put(
            "properties",
            JSONObject()
                .put("status", "expired")
                .put("product_id", "premium")
                .put("auto_renew", false)
                .put("expires_at", JSONObject.NULL)
                .put("is_trial", JSONObject.NULL)
                .put("offer_type", JSONObject.NULL)
                .put("environment", JSONObject.NULL)
                .put("original_transaction_id", JSONObject.NULL)
                .put("transaction_id", "GPA.1")
                .put("purchase_token", "token")
                .put("account_token", JSONObject.NULL)
                .put("inferred_fields", inferred)
                .put("inference_basis", JSONObject.NULL)
                .put("trigger", "app_open"),
        )

    private fun assertRejected(event: JSONObject) {
        assertTrue("expected a schema error for $event", validator.validate(event).isNotEmpty())
    }

    @Test
    fun acceptsAValidEvent() {
        assertEquals(emptyList<String>(), validator.validate(appOpen()))
        assertEquals(emptyList<String>(), validator.validate(expiredState(JSONArray().put("status"))))
    }

    @Test
    fun rejectsAMissingRequiredField() {
        assertRejected(appOpen().apply { remove("seq") })
        assertRejected(appOpen().apply { remove("user_id") })
        assertRejected(appOpen().put("properties", JSONObject()))
    }

    @Test
    fun rejectsV0NamesAndVersions() {
        assertRejected(appOpen().put("name", "app_foreground").put("properties", JSONObject()))
        assertRejected(appOpen().put("schema_version", 0))
    }

    @Test
    fun rejectsExtraPropertiesAtEveryLevel() {
        assertRejected(appOpen().put("tenant_id", "t"))
        assertRejected(appOpen().put("properties", JSONObject().put("start_type", "cold").put("extra", 1)))
    }

    @Test
    fun rejectsWrongTypesPatternsAndPlatformRules() {
        assertRejected(appOpen().put("seq", 1.5))
        assertRejected(appOpen().put("seq", 0))
        assertRejected(appOpen().put("anonymous_id", "5F0C2D1E-8A7B-4C3D-9E1F-0A1B2C3D4E5F"))
        assertRejected(appOpen().put("timestamp", "2026-11-26T10:00:00Z"))
        assertRejected(appOpen().put("properties", JSONObject().put("start_type", "hot")))
        assertRejected(expiredState(JSONArray()))
        assertRejected(expiredState(JSONArray().put("status").put("status")))
        assertRejected(expiredState(JSONArray().put("status")).also { it.getJSONObject("properties").put("offer_type", "promotional") })
    }
}
