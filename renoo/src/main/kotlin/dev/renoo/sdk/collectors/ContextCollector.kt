package dev.renoo.sdk.collectors

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import dev.renoo.sdk.EventName
import dev.renoo.sdk.core.ClearReason
import dev.renoo.sdk.core.Collector
import dev.renoo.sdk.core.SdkEnvironment
import dev.renoo.sdk.core.StateOwner
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

/**
 * Device and app context, attached to `session_start` as its `context` property.
 * Also emits `push_permission_changed`.
 *
 * [snapshot] only reads a lock-protected cache, so it is cheap and safe from any thread (the
 * lifecycle engine calls it on the main thread). The cache is refreshed at [start] and on every
 * return to the foreground ([refresh], called by [LifecycleCollector] just before the engine
 * decides whether to open a session):
 * - main thread, no Binder: locale, timezone, dark mode (`Resources` configuration);
 * - SDK executor (Binder calls): power save mode, push permission
 *   (`NotificationManager.areNotificationsEnabled`, read only, never requested), the current
 *   network, and once per process the install age and reinstall flag from `environment.identity`;
 * - live: network type from a default-network callback (`ConnectivityManager`, needs only the
 *   install-time `ACCESS_NETWORK_STATE`; if the host app strips it, `network_type` is `unknown`).
 *
 * **Cold starts.** The lifecycle engine snapshots synchronously on the main thread, often before
 * the executor reads land (a fast relaunch starts the first activity within a few ms of
 * `configure`). So the last known executor-read values (power save mode, push status, network
 * type, first-install time) are persisted in the SDK prefs whenever they change, and [start]
 * seeds the cache from prefs **synchronously** (no Binder; the prefs file is already loaded or
 * loading since `configure`, and it is one small file read at most). The asynchronous refresh
 * still runs and corrects them within milliseconds. `days_since_install` is recomputed from the
 * persisted first-install time at each snapshot, so it is never stale. Only the very first launch
 * of an install can carry `null`s (and `network_type` `unknown`) if its first activity starts
 * before the executor reads finish.
 *
 * Power save mode and notification status are deliberately **not** read synchronously on the main
 * thread even once: they are Binder calls, which the SDK never makes on the main thread (CLAUDE.md
 * rule 2; a busy system_server can stall them), and the prefs seed already covers every launch
 * but the first. A persisted `network_type` from the previous run may be briefly out of date
 * until the live network callback fires; it is a better estimate than `unknown`.
 *
 * Opt-out: [clearState] deletes the persisted values, and nothing is persisted (and no
 * `push_permission_changed` tracked) while opted out. The in-memory cache is kept, so the first
 * session after `optIn` still has a context. After a restore from another install the seeded
 * values are dropped from memory too.
 */
internal class ContextCollector(private val environment: SdkEnvironment) : Collector, StateOwner {
    private data class Cache(
        val deviceModel: String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "unknown",
        val locale: String? = null,
        val timezone: String? = null,
        val networkType: String = NETWORK_UNKNOWN,
        val lowPowerMode: Boolean? = null,
        val darkMode: Boolean? = null,
        val pushPermission: String? = null,
        val firstInstallTimeMillis: Long? = null,
        val isReinstall: Boolean? = null,
    )

    private val lock = Any()
    private var cache = Cache()
    private val pushTracker = PushPermissionTracker(environment.prefs)

    /**
     * Called (on any thread: network callbacks arrive on a framework thread) when the network type
     * changes. The uploader uses it to retry when the network comes back. Set before [start].
     */
    @Volatile
    internal var networkListener: ((String) -> Unit)? = null

    /** The last known `network_type` (`wifi`, `cellular`, `wired`, `none`, `unknown`). Any thread. */
    val networkType: String
        get() = synchronized(lock) { cache.networkType }

    /** Executor only. */
    private var networkCallbackRegistered = false
    private var identityLoaded = false

    override fun start() {
        seedFromPrefs()
        refresh()
    }

    /**
     * Main thread, synchronous: the last values seen by any earlier run. Prefs only, no Binder.
     * Internal so tests can check it.
     */
    internal fun seedFromPrefs() {
        try {
            val prefs = environment.prefs
            val push = pushTracker.lastStatus
            val lowPower = if (prefs.contains(KEY_LOW_POWER_MODE)) prefs.getBoolean(KEY_LOW_POWER_MODE, false) else null
            val network = prefs.getString(KEY_NETWORK_TYPE, null)
            val firstInstall = if (prefs.contains(KEY_FIRST_INSTALL_TIME)) prefs.getLong(KEY_FIRST_INSTALL_TIME, 0L) else null
            update {
                it.copy(
                    pushPermission = it.pushPermission ?: push,
                    lowPowerMode = it.lowPowerMode ?: lowPower,
                    networkType = if (it.networkType == NETWORK_UNKNOWN && network != null) network else it.networkType,
                    firstInstallTimeMillis = it.firstInstallTimeMillis ?: firstInstall,
                )
            }
        } catch (t: Throwable) {
            environment.logger.error({ "Could not read the cached context" }, t)
        }
    }

    /** The current context object. Cheap and non-blocking: returns cached values. Any thread. */
    fun snapshot(): Map<String, Any?> = snapshot(System.currentTimeMillis())

    fun snapshot(nowMillis: Long): Map<String, Any?> {
        val cache = synchronized(lock) { cache }
        return linkedMapOf(
            "device_model" to cache.deviceModel,
            "locale" to cache.locale,
            "timezone" to cache.timezone,
            "network_type" to cache.networkType,
            "low_power_mode" to cache.lowPowerMode,
            "dark_mode" to cache.darkMode,
            "push_permission" to cache.pushPermission,
            "days_since_install" to cache.firstInstallTimeMillis?.let { daysSinceInstall(it, nowMillis) },
            "is_reinstall" to cache.isReinstall,
        )
    }

    /** Main thread. Reads the cheap values now and schedules the rest on the executor. */
    fun refresh() {
        val locale = try {
            bcp47Locale(Locale.getDefault())
        } catch (t: Throwable) {
            null
        }
        val timezone = try {
            TimeZone.getDefault().id
        } catch (t: Throwable) {
            null
        }
        val dark = try {
            isDarkMode(environment.appContext.resources.configuration.uiMode)
        } catch (t: Throwable) {
            null
        }
        update { it.copy(locale = locale, timezone = timezone, darkMode = dark) }
        environment.executor.post(::refreshOnExecutor)
    }

    private fun refreshOnExecutor() {
        if (!networkCallbackRegistered) {
            networkCallbackRegistered = true
            registerNetworkCallback()
        }
        if (!identityLoaded) {
            identityLoaded = true
            pushTracker.lastStatus?.let { status -> update { it.copy(pushPermission = status) } }
            try {
                val identity = environment.identity
                val firstInstall = identity.firstInstallTimeMillis
                val reinstall = identity.isReinstall
                update { it.copy(firstInstallTimeMillis = firstInstall, isReinstall = reinstall) }
                persist(KEY_FIRST_INSTALL_TIME, firstInstall)
            } catch (t: Throwable) {
                environment.logger.error({ "Could not read the install identity" }, t)
            }
        }
        val app = environment.appContext
        try {
            val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (power != null) {
                val lowPower = power.isPowerSaveMode
                update { it.copy(lowPowerMode = lowPower) }
                persist(KEY_LOW_POWER_MODE, lowPower)
            }
        } catch (t: Throwable) {
            environment.logger.error({ "Could not read power save mode" }, t)
        }
        readCurrentNetwork()
        try {
            val notifications = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (notifications != null) pushStatusRead(pushStatus(notifications.areNotificationsEnabled()))
        } catch (t: Throwable) {
            environment.logger.error({ "Could not read the notification status" }, t)
        }
    }

    // region Push permission

    /** Executor. Internal so tests can feed statuses. */
    internal fun pushStatusRead(status: String) {
        update { it.copy(pushPermission = status) }
        if (environment.pipeline.isOptedOut) return
        val change = pushTracker.observe(status) ?: return
        environment.pipeline.track(
            EventName.PUSH_PERMISSION_CHANGED,
            mapOf("status" to change.status, "previous_status" to change.previous),
        )
    }

    // endregion

    // region Network

    /** Callbacks arrive on the system's ConnectivityThread: nothing may escape them. */
    internal val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = guarded {
            val type = try {
                connectivity()?.getNetworkCapabilities(network)?.let(::networkType)
            } catch (t: Throwable) {
                NETWORK_UNKNOWN
            }
            if (type != null) setNetworkType(type)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = guarded {
            setNetworkType(networkType(networkCapabilities))
        }

        override fun onLost(network: Network) = guarded {
            setNetworkType(NETWORK_NONE)
        }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            environment.logger.error({ "Network callback failed" }, t)
        }
    }

    /** Executor, once. A missing `ACCESS_NETWORK_STATE` throws `SecurityException`: caught. */
    private fun registerNetworkCallback() {
        try {
            connectivity()?.registerDefaultNetworkCallback(networkCallback)
        } catch (t: Throwable) {
            environment.logger.error({ "Could not watch the network (is ACCESS_NETWORK_STATE missing?)" }, t)
        }
    }

    /** Executor. */
    private fun readCurrentNetwork() {
        val type = try {
            val manager = connectivity()
            if (manager == null) {
                NETWORK_UNKNOWN
            } else {
                val network = manager.activeNetwork
                if (network == null) NETWORK_NONE else networkType(manager.getNetworkCapabilities(network))
            }
        } catch (t: Throwable) {
            NETWORK_UNKNOWN
        }
        setNetworkType(type)
    }

    private fun connectivity(): ConnectivityManager? =
        environment.appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** Any thread (network callbacks arrive on a framework thread). Persists real values on the executor. */
    private fun setNetworkType(type: String) {
        val previous = synchronized(lock) { cache.networkType.also { cache = cache.copy(networkType = type) } }
        if (previous != type) {
            try {
                networkListener?.invoke(type)
            } catch (t: Throwable) {
                environment.logger.error({ "Network listener failed" }, t)
            }
        }
        if (type != NETWORK_UNKNOWN) environment.executor.post { persist(KEY_NETWORK_TYPE, type) }
    }

    private fun networkType(caps: NetworkCapabilities?): String {
        if (caps == null) return NETWORK_UNKNOWN
        return networkType(
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        )
    }

    // endregion

    private inline fun update(change: (Cache) -> Cache) {
        synchronized(lock) { cache = change(cache) }
    }

    /**
     * Executor (pipeline opt-out / restore task). Deletes the persisted context values. After a
     * restore the values seeded from those prefs are also dropped from memory and the identity is
     * read again; on opt-out the in-memory cache stays (it is never stored while opted out).
     */
    override fun clearState(reason: ClearReason) {
        environment.prefs.edit()
            .remove(KEY_LOW_POWER_MODE)
            .remove(KEY_NETWORK_TYPE)
            .remove(KEY_FIRST_INSTALL_TIME)
            .remove(PushPermissionTracker.KEY)
            .apply()
        if (reason == ClearReason.RESTORED_FROM_ANOTHER_INSTALL) {
            identityLoaded = false
            update {
                it.copy(
                    networkType = NETWORK_UNKNOWN,
                    lowPowerMode = null,
                    pushPermission = null,
                    firstInstallTimeMillis = null,
                    isReinstall = null,
                )
            }
        }
    }

    /** Executor. Writes only when the value changed, and nothing while opted out. */
    private fun persist(key: String, value: Any) {
        if (environment.pipeline.isOptedOut) return
        val prefs = environment.prefs
        if (prefs.contains(key) && prefs.all[key] == value) return
        val editor = prefs.edit()
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Long -> editor.putLong(key, value)
            is String -> editor.putString(key, value)
            else -> return
        }
        editor.apply()
    }

    companion object {
        const val NETWORK_WIFI: String = "wifi"
        const val NETWORK_CELLULAR: String = "cellular"
        const val NETWORK_WIRED: String = "wired"
        const val NETWORK_NONE: String = "none"
        const val NETWORK_UNKNOWN: String = "unknown"

        /** Last known values, for the synchronous seed at [start]. Push status lives in [PushPermissionTracker.KEY]. */
        const val KEY_LOW_POWER_MODE: String = "context_low_power_mode"
        const val KEY_NETWORK_TYPE: String = "context_network_type"
        const val KEY_FIRST_INSTALL_TIME: String = "context_first_install_time_millis"

        const val PUSH_AUTHORIZED: String = "authorized"
        const val PUSH_DENIED: String = "denied"

        private const val DAY_MILLIS = 86_400_000L

        /** Whole 24-hour periods since the first install, never negative. */
        private val LOCALE_PATTERN = Regex("^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$")

        /** BCP 47 tag (`en-US`), or `null` for an empty or malformed one (`und` stays: it is well-formed). */
        fun bcp47Locale(locale: Locale): String? = locale.toLanguageTag().takeIf { LOCALE_PATTERN.matches(it) }

        fun daysSinceInstall(firstInstallMillis: Long, nowMillis: Long): Long =
            max(0L, (nowMillis - firstInstallMillis) / DAY_MILLIS)

        /**
         * The default network's transport. Wi-Fi wins over cellular (a VPN or a Wi-Fi network
         * with cellular fallback may report both); anything else (VPN only, Bluetooth, USB) is `unknown`.
         */
        fun networkType(wifi: Boolean, cellular: Boolean, ethernet: Boolean): String = when {
            wifi -> NETWORK_WIFI
            ethernet -> NETWORK_WIRED
            cellular -> NETWORK_CELLULAR
            else -> NETWORK_UNKNOWN
        }

        /**
         * Android only says whether notifications are enabled. On Android 13+ an app that never
         * asked for `POST_NOTIFICATIONS` reads as `denied` (iOS would say `not_determined`).
         */
        fun pushStatus(enabled: Boolean): String = if (enabled) PUSH_AUTHORIZED else PUSH_DENIED

        /** `null` when the night mode is undefined. */
        fun isDarkMode(uiMode: Int): Boolean? = when (uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            Configuration.UI_MODE_NIGHT_NO -> false
            else -> null
        }
    }
}

/**
 * Remembers the last push permission status across runs (SDK prefs) and reports changes.
 * The first status ever seen is stored silently: there is nothing to compare it with.
 * Executor only.
 */
internal class PushPermissionTracker(private val prefs: SharedPreferences) {
    data class Change(val status: String, val previous: String)

    val lastStatus: String? get() = prefs.getString(KEY, null)

    /** Stores [status]; returns the change when it differs from the previously stored one. */
    fun observe(status: String): Change? {
        val previous = lastStatus
        if (previous == status) return null
        prefs.edit().putString(KEY, status).apply()
        return previous?.let { Change(status, it) }
    }

    companion object {
        const val KEY: String = "push_permission_status"
    }
}
