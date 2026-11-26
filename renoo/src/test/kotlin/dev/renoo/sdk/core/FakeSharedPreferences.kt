package dev.renoo.sdk.core

import android.content.SharedPreferences

/**
 * In-memory, thread-safe SharedPreferences for plain JVM tests. Edits are applied atomically
 * on `apply()` / `commit()`. Listeners are not supported.
 */
internal class FakeSharedPreferences : SharedPreferences {
    private val values = HashMap<String, Any?>()

    @Synchronized override fun getAll(): Map<String, *> = HashMap(values)
    @Synchronized override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    @Synchronized override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        values[key] as? Set<String> ?: defValues
    @Synchronized override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    @Synchronized override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    @Synchronized override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    @Synchronized override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    @Synchronized override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
        throw UnsupportedOperationException()

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
        throw UnsupportedOperationException()

    private inner class Editor : SharedPreferences.Editor {
        private val changes = HashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: Set<String>?) = put(key, values?.toSet())
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String) = apply { removals.add(key) }
        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            synchronized(this@FakeSharedPreferences) {
                if (clear) values.clear()
                removals.forEach { values.remove(it) }
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            }
            return true
        }

        override fun apply() {
            commit()
        }

        private fun put(key: String, value: Any?) = apply { changes[key] = value }
    }
}

/** The anonymous ID [withIdentitySent] stores. */
internal const val TEST_ANONYMOUS_ID: String = "00000000-0000-4000-8000-000000000001"

/**
 * Prefs of an install whose anonymous ID already had its `identify` sent, so tests about other
 * events don't see that one-off event. Anonymous ID tests start from empty prefs instead.
 */
internal fun FakeSharedPreferences.withIdentitySent(): FakeSharedPreferences = apply {
    edit()
        .putString(EventPipeline.KEY_ANONYMOUS_ID, TEST_ANONYMOUS_ID)
        .putString(EventPipeline.KEY_IDENTITY_SENT_FOR, TEST_ANONYMOUS_ID)
        .apply()
}
