package dev.renoo.sdk.collectors

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import dev.renoo.sdk.core.SdkLogger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Reads `Settings.Secure.ANDROID_ID`. Behind an interface so tests can fake it. */
internal fun interface AndroidIdSource {
    /** The raw value, or `null`. May throw; the caller handles it. Never emitted or stored. */
    fun androidId(): String?
}

/** The real one: a ContentResolver (Binder) read. Needs no permission. */
internal class SystemAndroidIdSource(private val context: Context) : AndroidIdSource {
    @SuppressLint("HardwareIds")
    override fun androidId(): String? = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
}

/**
 * The stable identity of this install:
 *
 * - [installId]: a UUID derived from SHA-256 of `ANDROID_ID` and the package name ([deriveInstallId]),
 *   so it is the same after a reinstall on the same device (Android 8+ scopes `ANDROID_ID` to the
 *   app-signing key, user and device; it changes on factory reset) and needs no permission. The raw
 *   `ANDROID_ID` is never emitted or stored. When `ANDROID_ID` is null, blank, the known-bad
 *   `9774d56d682e549c`, or the read throws: a random UUID.
 * - Once resolved, the ID is kept in the SDK prefs together with the install it belongs to (the
 *   first-install time), so it stays the same for the whole install even if `ANDROID_ID` later
 *   becomes (un)available. If Android's backup restores those prefs into a different install
 *   (reinstall or new device: the first-install time differs), they are ignored and the ID is
 *   resolved again, so two devices never share a derived ID through a backup.
 * - [firstInstallTimeMillis]: `PackageInfo.firstInstallTime` (resets on reinstall). If it can't be
 *   read, the time the SDK first saw this install, from prefs.
 * - [isReinstall]: always `null` on Android. The device can't tell a first install from a
 *   reinstall; the backend derives it from the stable install ID.
 *
 * Resolved once, lazily, under a lock. The first access reads prefs, PackageManager and Settings
 * (Binder), so it is made from the SDK executor (the pipeline's `installIdProvider`, collectors);
 * later ones are cached. Safe from any thread; never throws.
 */
internal class InstallIdentity(
    private val prefs: SharedPreferences,
    private val logger: SdkLogger,
    private val packageName: () -> String,
    private val androidIdSource: AndroidIdSource,
    private val packageFirstInstallTime: () -> Long?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context, prefs: SharedPreferences, logger: SdkLogger) : this(
        prefs = prefs,
        logger = logger,
        packageName = { context.packageName },
        androidIdSource = SystemAndroidIdSource(context),
        packageFirstInstallTime = {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        },
    )

    private class Resolved(
        val installId: String,
        val firstInstallTimeMillis: Long,
        val isReinstall: Boolean?,
        val idSource: String,
        val restoredFromAnotherInstall: Boolean = false,
    )

    private val lock = Any()
    private var resolved: Resolved? = null

    /** Stable per device and app; lowercase UUID string. */
    val installId: String get() = resolve().installId

    /** When the app was first installed (epoch millis). Also identifies this install. */
    val firstInstallTimeMillis: Long get() = resolve().firstInstallTimeMillis

    /** `install.id_source`: [SOURCE_ANDROID_ID] or [SOURCE_RANDOM]. */
    val idSource: String get() = resolve().idSource

    /** Whether this install follows an earlier one on the same device; `null` when unknown (always, on Android). */
    val isReinstall: Boolean? get() = resolve().isReinstall

    /**
     * True when the SDK prefs held an install ID recorded for a different install: Android's backup
     * restored them into a reinstall or onto another device. Everything else in those prefs belongs
     * to that other install (`SdkCore` deletes it). Resolves on first access (Binder): executor only.
     */
    val restoredFromAnotherInstall: Boolean get() = resolve().restoredFromAnotherInstall

    private fun resolve(): Resolved = synchronized(lock) {
        resolved ?: load().also { resolved = it }
    }

    private fun load(): Resolved {
        val firstInstall = firstInstallTime()
        val storedId = prefs.getString(KEY_INSTALL_ID, null)
        if (storedId != null && prefs.contains(KEY_INSTALL_ID_FOR) && prefs.getLong(KEY_INSTALL_ID_FOR, 0L) == firstInstall) {
            return Resolved(storedId, firstInstall, null, storedSource(storedId))
        }
        val restored = storedId != null
        if (restored) logger.info { "SDK prefs were restored from another install; resolving a new install ID" }
        val androidId = usableAndroidId()
        val (installId, source) = if (androidId != null) {
            deriveInstallId(androidId, safePackageName()) to SOURCE_ANDROID_ID
        } else {
            UUID.randomUUID().toString().lowercase() to SOURCE_RANDOM
        }
        prefs.edit()
            .putString(KEY_INSTALL_ID, installId)
            .putLong(KEY_INSTALL_ID_FOR, firstInstall)
            .putString(KEY_INSTALL_ID_SOURCE, source)
            .apply()
        logger.info { "install $installId (from $source)" }
        return Resolved(installId, firstInstall, null, source, restoredFromAnotherInstall = restored)
    }

    /** The saved source of [storedId]; recomputed (and saved) if missing, e.g. prefs from an older SDK. */
    private fun storedSource(storedId: String): String {
        val saved = prefs.getString(KEY_INSTALL_ID_SOURCE, null)
        if (saved == SOURCE_ANDROID_ID || saved == SOURCE_RANDOM) return saved
        val androidId = usableAndroidId()
        val source = if (androidId != null && deriveInstallId(androidId, safePackageName()) == storedId) SOURCE_ANDROID_ID else SOURCE_RANDOM
        prefs.edit().putString(KEY_INSTALL_ID_SOURCE, source).apply()
        return source
    }

    private fun firstInstallTime(): Long {
        val fromPackage = try {
            packageFirstInstallTime()
        } catch (t: Throwable) {
            logger.error({ "Could not read the first install time" }, t)
            null
        }
        if (fromPackage != null && fromPackage > 0) return fromPackage
        if (prefs.contains(KEY_FIRST_INSTALL_TIME)) return prefs.getLong(KEY_FIRST_INSTALL_TIME, 0L)
        val now = clock()
        prefs.edit().putLong(KEY_FIRST_INSTALL_TIME, now).apply()
        return now
    }

    private fun usableAndroidId(): String? {
        val raw = try {
            androidIdSource.androidId()
        } catch (t: Throwable) {
            logger.error({ "Could not read ANDROID_ID; using a random install ID" }, t)
            null
        }
        val value = raw?.trim()?.lowercase()
        return if (value.isNullOrEmpty() || value in BROKEN_ANDROID_IDS) null else value
    }

    private fun safePackageName(): String = try {
        packageName()
    } catch (t: Throwable) {
        ""
    }

    companion object {
        const val KEY_INSTALL_ID: String = "install_id"

        /** First-install time of the install [KEY_INSTALL_ID] was resolved for. */
        const val KEY_INSTALL_ID_FOR: String = "install_id_for"

        /** `android_id` or `random`: where [KEY_INSTALL_ID] came from (`install.id_source`). */
        const val KEY_INSTALL_ID_SOURCE: String = "install_id_source"

        /** Fallback first-install time when PackageManager can't tell. */
        const val KEY_FIRST_INSTALL_TIME: String = "first_install_time_millis"

        const val SOURCE_ANDROID_ID: String = "android_id"
        const val SOURCE_RANDOM: String = "random"

        /** A value many old devices shared (Android 2.2 bug); not an identifier. */
        private val BROKEN_ANDROID_IDS = setOf("9774d56d682e549c", "0", "0000000000000000")

        /** Domain separation: the ID is not a plain hash of `ANDROID_ID`. */
        private const val NAMESPACE = "dev.renoo.install-id.v1"

        /**
         * `SHA-256(namespace | ANDROID_ID | package)`, first 128 bits, formatted as an RFC 9562
         * version 8 (custom, name-based) UUID, lowercase. Deterministic: same device, app and
         * signing key → same ID.
         */
        fun deriveInstallId(androidId: String, packageName: String): String {
            val input = "$NAMESPACE\u0000${androidId.trim().lowercase()}\u0000$packageName"
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).copyOf(16)
            bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x80).toByte() // version 8
            bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte() // RFC 9562 variant
            val buffer = ByteBuffer.wrap(bytes)
            return UUID(buffer.long, buffer.long).toString().lowercase()
        }
    }
}
