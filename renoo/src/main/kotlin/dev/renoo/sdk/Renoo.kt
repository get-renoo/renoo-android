package dev.renoo.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.renoo.sdk.core.MainProcess
import dev.renoo.sdk.core.SdkCore
import dev.renoo.sdk.core.SdkLogger

/**
 * The public entry point. Every method is safe to call from any thread, never throws, and does
 * nothing until [configure] has succeeded. Only [queuedEvents] waits (briefly) for the SDK thread.
 */
public object Renoo {
    public const val SDK_VERSION: String = "0.1.0"

    private val lock = Any()

    @Volatile
    private var core: SdkCore? = null

    /**
     * Starts the SDK. Call once, as early as possible (e.g. `Application.onCreate`).
     * A bad configuration disables the SDK instead of crashing; a second call is ignored.
     * The application context is used, never the one passed in.
     *
     * Only the app's main process runs the SDK: in any other process (a `:remote` service, a
     * push or crash-reporter process) this is a logged no-op, so it is safe to call from
     * `Application.onCreate`, which runs in every process. If the process name can't be
     * determined, the SDK starts.
     */
    @JvmStatic
    public fun configure(context: Context, configuration: Configuration) {
        safely("configure") {
            if (!configuration.isValid) {
                SdkLogger(LogLevel.ERROR).error { "configure: empty API key or invalid configuration. The SDK is disabled." }
                return@safely
            }
            val appContext = context.applicationContext ?: context
            if (!MainProcess.isMainProcess(appContext)) {
                SdkLogger(configuration.logLevel).info { "configure: not the app's main process; the SDK stays off here." }
                return@safely
            }
            val created = synchronized(lock) {
                if (core != null) {
                    null
                } else {
                    SdkCore(configuration, appContext).also { core = it }
                }
            }
            if (created == null) {
                SdkLogger(configuration.logLevel).error { "configure called twice; ignoring the second call." }
                return@safely
            }
            val mainLooper = Looper.getMainLooper()
            if (Looper.myLooper() == mainLooper) {
                created.start()
            } else {
                Handler(mainLooper).post { safely("start") { created.start() } }
            }
        }
    }

    @JvmStatic
    public val isConfigured: Boolean
        get() = core != null

    /**
     * Links events to the client's user ID: the same ID they use in their billing SDK. Call it
     * after every login (and on launch when a user is already logged in). Pass an opaque
     * identifier, never an email address or a name. Emits an `identify` event; identifying the
     * user ID that is already set emits nothing. Ignored after [optOut].
     */
    @JvmStatic
    public fun identify(userId: String) {
        safely("identify") {
            if (userId.isEmpty()) return@safely
            core?.environment?.pipeline?.identify(userId)
        }
    }

    /**
     * Call on logout. Clears the user ID and starts a new [anonymousId], which gets its own
     * `identify` event (with a `null` user ID) once the user is tracked. Events already captured
     * keep the old anonymous ID. Clears [pendingOffer] (it belonged to the previous user).
     */
    @JvmStatic
    public fun reset() {
        safely("reset") { core?.environment?.pipeline?.reset() }
    }

    /**
     * This install's current anonymous ID: a random lowercase UUID v4 on every event's
     * `anonymous_id`, made at [configure] and stored in the SDK's preferences (not backed up, so
     * a reinstall or a restored backup gets a new one). [reset] replaces it; [optOut] keeps it.
     *
     * `null` before [configure] and for the few milliseconds until the SDK thread has loaded it.
     * Never blocks.
     */
    @JvmStatic
    public val anonymousId: String?
        get() = safely("anonymousId", null) { core?.environment?.pipeline?.currentAnonymousId }

    /**
     * Stops all tracking for this install and deletes what the SDK stored locally: the queue, the
     * user ID, the [pendingOffer], and the cached subscription, session and device-context state.
     * No `GET /offers` while opted out. The opt-out flag, the install ID and the [anonymousId] are kept.
     */
    @JvmStatic
    public fun optOut() {
        safely("optOut") { core?.environment?.pipeline?.setOptedOut(true) }
    }

    /**
     * Resumes tracking after [optOut], with the same [anonymousId]; its `identify` is sent again
     * once the user is tracked. The next return to the foreground reads the subscription again and starts
     * a new session.
     */
    @JvmStatic
    public fun optIn() {
        safely("optIn") { core?.environment?.pipeline?.setOptedOut(false) }
    }

    @JvmStatic
    public val isOptedOut: Boolean
        get() = safely("isOptedOut", false) { core?.environment?.pipeline?.isOptedOut ?: false }

    /**
     * Records a screen view by name. Use it where automatic capture can't see the screen;
     * wrappers (Flutter, React Native) pass their own [source].
     * Use a static screen identifier (at most 100 characters), never text with user content.
     */
    @JvmStatic
    @JvmOverloads
    public fun trackScreen(name: String, source: ScreenSource = ScreenSource.MANUAL) {
        safely("trackScreen") { core?.screens?.trackScreen(name, source) }
    }

    /**
     * Calls [observer] for every captured event, on the SDK's background thread. Keep it quick.
     * For debugging and test apps. Returns `null` when the SDK is not configured.
     */
    @JvmStatic
    public fun addEventObserver(observer: EventObserver): EventObserverToken? =
        safely("addEventObserver", null) { core?.environment?.pipeline?.addObserver(observer) }

    @JvmStatic
    public fun removeEventObserver(token: EventObserverToken) {
        safely("removeEventObserver") { core?.environment?.pipeline?.removeObserver(token) }
    }

    /**
     * The pending offer for this user from `GET /offers`, or `null` when there is none (or the SDK
     * is not configured, has no `ingestUrl`, or the user opted out). Never blocks.
     *
     * An immutable `Map<String, Any?>` made from the server's JSON object: values are `String`,
     * `Boolean`, `Int` / `Long`, `Double`, `null`, and nested immutable `Map<String, Any?>` /
     * `List<Any?>`. Its shape is free-form until the offer contract is fixed. The SDK renders
     * nothing. Fetched on start-up and when the app returns to the foreground (at most every 6 h);
     * cleared by [reset], [optOut] and the kill switch. Use [addPendingOfferObserver] to hear about changes.
     */
    @JvmStatic
    public val pendingOffer: Map<String, Any?>?
        get() = safely("pendingOffer", null) { core?.pendingOffers?.current }

    /**
     * Calls [observer] on the **main thread** each time [pendingOffer] changes (a new offer, or
     * `null` when it goes away). Not called on registration: read [pendingOffer] for the current
     * value. Returns `null` when the SDK is not configured.
     */
    @JvmStatic
    public fun addPendingOfferObserver(observer: PendingOfferObserver): PendingOfferObserverToken? =
        safely("addPendingOfferObserver", null) { core?.pendingOffers?.addObserver(observer) }

    @JvmStatic
    public fun removePendingOfferObserver(token: PendingOfferObserverToken) {
        safely("removePendingOfferObserver") { core?.pendingOffers?.removeObserver(token) }
    }

    /**
     * **Debugging API; blocks.** Every event waiting in the on-disk queue, oldest first.
     * The calling thread waits for the SDK thread to finish earlier work and read the file, up to
     * 5 seconds, then gets an empty list. Test apps may call it from the main thread (e.g. through
     * a Flutter method channel); production code should not, since a long wait can cause an ANR.
     * If the caller is interrupted while waiting, it gets an empty list and its interrupt flag stays set.
     */
    @JvmStatic
    public fun queuedEvents(): List<Event> =
        safely("queuedEvents", emptyList()) { core?.environment?.pipeline?.queuedEvents() ?: emptyList() }

    /** Test hook: replaces the running SDK. Not part of the public API. */
    internal fun setCoreForTesting(value: SdkCore?) {
        synchronized(lock) { core = value }
    }

    internal val coreForTesting: SdkCore?
        get() = core

    private inline fun safely(name: String, block: () -> Unit) {
        safely(name, Unit, block)
    }

    private inline fun <T> safely(name: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        try {
            Log.e(SdkLogger.TAG, "$name failed: $t")
        } catch (ignored: Throwable) {
            // Never let logging crash the host app either.
        }
        fallback
    }
}
