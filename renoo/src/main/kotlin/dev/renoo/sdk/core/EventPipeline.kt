package dev.renoo.sdk.core

import android.content.SharedPreferences
import dev.renoo.sdk.Event
import dev.renoo.sdk.EventName
import dev.renoo.sdk.EventObserver
import dev.renoo.sdk.EventObserverToken
import dev.renoo.sdk.TrackingScope
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Whether the current user is in tracking scope (active subscription or trial). */
internal enum class TrackingState {
    /** Subscription state not known yet. Events wait in memory (up to [EventPipeline.MAX_BUFFERED_EVENTS]). */
    UNKNOWN,
    TRACKED,

    /** Not a subscriber. Events are dropped and nothing is stored. */
    NOT_TRACKED,
}

/** Which session an event is stamped with. */
internal sealed interface SessionOverride {
    /** The pipeline's current session (the last [EventPipeline.setSessionId]). The default. */
    data object Current : SessionOverride

    /** This session instead, for events that belong to an older one. `null` means "no session". */
    data class Explicit(val sessionId: String?) : SessionOverride
}

/** Fields of the envelope that are the same for every event of this process. */
internal data class Envelope(
    val sdkVersion: String,
    val appVersion: String,
    val appBuild: String,
    val osVersion: String,
)

/**
 * The single path every event takes: gate → sequence number → disk → log → observers.
 * All state lives on the SDK executor, so collectors can call any method from any thread;
 * nothing here blocks the caller except [queuedEvents] and [waitUntilIdle].
 *
 * [storeProvider], [envelopeProvider] and [installIdProvider] are called lazily on the executor,
 * so creating the pipeline (in `configure`, on the main thread) does no disk or Binder work.
 */
internal class EventPipeline(
    private val executor: SdkExecutor,
    storeProvider: () -> EventStore,
    private val prefs: SharedPreferences,
    private val logger: SdkLogger,
    envelopeProvider: () -> Envelope,
    private val trackingScope: TrackingScope,
    installIdProvider: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Pending(
        val name: EventName,
        val properties: Map<String, Any?>,
        val timestampMillis: Long,
        val sessionId: String?,
        val anonymousId: String,
        val userId: String?,
    )

    // Executor-only state.
    private val store by lazy(LazyThreadSafetyMode.NONE, storeProvider)
    private val envelope by lazy(LazyThreadSafetyMode.NONE, envelopeProvider)
    private val installId by lazy(LazyThreadSafetyMode.NONE, installIdProvider)
    private var trackingState = if (trackingScope == TrackingScope.EVERYONE) TrackingState.TRACKED else TrackingState.UNKNOWN
    private val buffer = ArrayDeque<Pending>()
    private val observers = LinkedHashMap<EventObserverToken, EventObserver>()
    private val stateOwners = CopyOnWriteArrayList<StateOwner>()
    private var sessionId: String? = null
    private var userId: String? = null
    private var optedOut = false

    /** Set by the kill switch ([stopCapture]); never cleared in this process. */
    private var captureStopped = false

    /** Set by the load task (see [ensureAnonymousId]); never `null` afterwards. */
    private var anonymousId: String? = null

    /** Readable from any thread: the public `Renoo.anonymousId`. */
    @Volatile
    private var anonymousIdMirror: String? = null

    /**
     * Called on the executor after each event is appended to disk, with the queue's line count.
     * The uploader uses it for the queue-size flush trigger. Set before `start()`.
     */
    @Volatile
    var queueSizeListener: ((Int) -> Unit)? = null

    /** Called on the executor after [reset] has made the new anonymous ID. Set before `start()`. */
    @Volatile
    var identityResetListener: (() -> Unit)? = null

    /**
     * Called on the executor when the tracking gate opens (`true`, state became TRACKED) or closes
     * (`false`, NOT_TRACKED). Never called for [TrackingScope.EVERYONE], whose gate is open from
     * the start. Set before `start()`.
     */
    @Volatile
    var trackingGateListener: ((Boolean) -> Unit)? = null

    /** Readable from any thread without touching the executor. `null` until known (reads then fall back to prefs). */
    @Volatile
    private var optedOutMirror: Boolean? = null
    private val mirrorLock = Any()

    init {
        // First task on the executor: load persisted state off the caller's thread.
        executor.post {
            userId = prefs.getString(KEY_USER_ID, null)
            optedOut = synchronized(mirrorLock) {
                optedOutMirror ?: prefs.getBoolean(KEY_OPTED_OUT, false).also { optedOutMirror = it }
            }
            ensureAnonymousId()
        }
    }

    // region Tracking

    /**
     * Records an event. Safe from any thread; never blocks.
     *
     * @param properties JSON-shaped values (see [JsonValues]); copied before this returns.
     * @param timestampMillis when it happened (epoch millis). Pass a past time for events
     *   discovered late, e.g. a `session_end` found on the next launch.
     * @param sessionId [SessionOverride.Explicit] for events that belong to another (or no) session.
     */
    fun track(
        name: EventName,
        properties: Map<String, Any?> = emptyMap(),
        timestampMillis: Long = clock(),
        sessionId: SessionOverride = SessionOverride.Current,
    ) {
        val snapshot = try {
            JsonValues.normalize(properties)
        } catch (t: Throwable) {
            logger.error({ "Invalid properties for ${name.wireValue}" }, t)
            return
        }
        executor.post { record(name, snapshot, timestampMillis, sessionId) }
    }

    /** Executor. Stamps the event with the current identity and passes it through the gate. */
    private fun record(name: EventName, properties: Map<String, Any?>, timestampMillis: Long, sessionId: SessionOverride) {
        if (optedOut || captureStopped) return
        val anonymousId = anonymousId ?: return
        val pending = Pending(
            name = name,
            properties = properties,
            timestampMillis = timestampMillis,
            sessionId = when (sessionId) {
                SessionOverride.Current -> this.sessionId
                is SessionOverride.Explicit -> sessionId.sessionId
            },
            anonymousId = anonymousId,
            userId = userId,
        )
        when (trackingState) {
            TrackingState.TRACKED -> commit(pending)
            TrackingState.UNKNOWN -> {
                buffer.addLast(pending)
                if (buffer.size > MAX_BUFFERED_EVENTS) buffer.removeFirst()
            }
            TrackingState.NOT_TRACKED ->
                logger.debug { "dropped ${name.wireValue}: user is not in tracking scope" }
        }
    }

    /** Sets the session later events are stamped with. `null` means no session is open. */
    fun setSessionId(id: String?) {
        executor.post { sessionId = id }
    }

    /**
     * Called by the subscription collector whenever it learns whether the user is in scope.
     * [TrackingState.TRACKED] commits the buffered events in order; [TrackingState.NOT_TRACKED]
     * drops them. Ignored when the SDK is configured with [TrackingScope.EVERYONE].
     */
    fun setTrackingState(state: TrackingState) {
        if (trackingScope != TrackingScope.SUBSCRIBERS_ONLY) return
        executor.post {
            if (state == trackingState) return@post
            trackingState = state
            logger.info { "tracking state: $state" }
            when (state) {
                TrackingState.TRACKED -> {
                    val waiting = buffer.toList()
                    buffer.clear()
                    waiting.forEach(::commit)
                    sendIdentityIfNeeded()
                }
                TrackingState.NOT_TRACKED -> buffer.clear()
                TrackingState.UNKNOWN -> Unit
            }
            if (state != TrackingState.UNKNOWN) {
                trackingGateListener?.let { listener ->
                    try {
                        listener(state == TrackingState.TRACKED)
                    } catch (t: Throwable) {
                        logger.error({ "Tracking gate listener failed" }, t)
                    }
                }
            }
        }
    }

    // endregion

    // region Identity

    /** The current anonymous ID, from any thread. `null` until the SDK thread has loaded it. */
    val currentAnonymousId: String?
        get() = anonymousIdMirror

    /**
     * `Renoo.identify`: sets the user ID and emits `identify`. Identifying the user ID that is
     * already set emits nothing. Ignored while opted out or for an empty ID.
     */
    fun identify(id: String) {
        executor.post {
            if (optedOut || id.isEmpty() || id == userId) return@post
            userId = id
            prefs.edit().putString(KEY_USER_ID, id).apply()
            trackIdentify()
        }
    }

    /**
     * `Renoo.reset` (logout): clears the user ID and starts a new anonymous ID, which gets its own
     * `identify` once the user is tracked. Events already queued or buffered keep the old one.
     */
    fun reset() {
        executor.post {
            userId = null
            val id = newAnonymousId()
            prefs.edit().remove(KEY_USER_ID).remove(KEY_IDENTITY_SENT_FOR).putString(KEY_ANONYMOUS_ID, id).apply()
            anonymousId = id
            anonymousIdMirror = id
            logger.info { "reset: new anonymous ID" }
            sendIdentityIfNeeded()
            identityResetListener?.let { listener ->
                try {
                    listener()
                } catch (t: Throwable) {
                    logger.error({ "Identity reset listener failed" }, t)
                }
            }
        }
    }

    /**
     * **SDK executor only.** Emits `identify` (with the current user ID, `null` if nobody has
     * logged in) once per anonymous ID, when the user is tracked. Called after the restore check
     * at start-up (see `SdkCore`), when the gate opens (after the buffered events), after [reset]
     * and after opt-in. The flag is written only when an `identify` reaches the disk queue, so one
     * from [identify] counts as sent.
     */
    fun sendIdentityIfNeeded() {
        val id = anonymousId ?: return
        if (optedOut || trackingState != TrackingState.TRACKED) return
        if (prefs.getString(KEY_IDENTITY_SENT_FOR, null) == id) return
        trackIdentify()
    }

    /** Executor, synchronously, so a second check can't queue a duplicate. The gate applies; [commit] records it as sent. */
    private fun trackIdentify() {
        record(EventName.IDENTIFY, mapOf("user_id" to userId), clock(), SessionOverride.Current)
    }

    /** Executor. Loads the stored anonymous ID, or makes and stores a new one. */
    private fun ensureAnonymousId() {
        val stored = prefs.getString(KEY_ANONYMOUS_ID, null)
        val id = stored ?: newAnonymousId().also { prefs.edit().putString(KEY_ANONYMOUS_ID, it).remove(KEY_IDENTITY_SENT_FOR).apply() }
        anonymousId = id
        anonymousIdMirror = id
    }

    private fun newAnonymousId(): String = UUID.randomUUID().toString().lowercase()

    /**
     * Opting out deletes the queue, the user ID, the "identity sent" flag and every collector's
     * stored state (through the [StateOwner]s), and closes the current session; `opted_out`, the
     * install ID, the anonymous ID and `seq` stay. Opting in sends `identify` again (same anonymous
     * ID) once the user is tracked.
     * The mirror flips first, so collectors checking [isOptedOut] (e.g. a billing read already in
     * flight) stop writing before the clearing task runs.
     */
    fun setOptedOut(value: Boolean) {
        synchronized(mirrorLock) { optedOutMirror = value }
        executor.post {
            optedOut = value
            val editor = prefs.edit().putBoolean(KEY_OPTED_OUT, value)
            if (value) {
                buffer.clear()
                store.clear()
                userId = null
                sessionId = null
                editor.remove(KEY_USER_ID).remove(KEY_IDENTITY_SENT_FOR)
            }
            editor.apply()
            if (value) {
                clearOwners(ClearReason.OPT_OUT)
                logger.info { "opted out: queue, user ID, identify flag and collector state cleared" }
            } else {
                sendIdentityIfNeeded()
            }
        }
    }

    /**
     * **SDK executor only.** The identity `GET /offers` asks about, or `null` when it should not
     * ask: opted out, user not known to be in tracking scope (not tracked, or not known yet),
     * capture stopped, or not loaded yet. Nothing about a non-subscriber reaches the server.
     */
    fun identityForRequests(): RequestIdentity? {
        if (optedOut || captureStopped || trackingState != TrackingState.TRACKED) return null
        val id = anonymousId ?: return null
        return RequestIdentity(id, userId)
    }

    /**
     * **SDK executor only.** The kill switch: drops the in-memory buffer and the on-disk queue, and
     * every later event, until the process ends. Identity and opt-out state still update (they are
     * local), so the next start-up begins with the right IDs; a skipped `identify` is sent then.
     */
    fun stopCapture() {
        captureStopped = true
        buffer.clear()
        store.clear()
    }

    /** Registers a component whose stored state is cleared on opt-out and after a restore. Any thread. */
    fun addStateOwner(owner: StateOwner) {
        stateOwners.add(owner)
    }

    /**
     * **SDK executor only**, synchronously (the caller is the first task after the pipeline's own
     * load, see `SdkCore`). The prefs came from another install through Android's backup: deletes
     * the queue, `user_id`, `seq`, the anonymous ID (a new one is made) and its identify flag, the
     * in-memory buffer and session, and every owner's state.
     * Keeps `opted_out` (the user's choice travels with their backup) and the install ID keys
     * (already re-resolved for this install).
     */
    fun clearRestoredState() {
        buffer.clear()
        store.clear()
        userId = null
        sessionId = null
        prefs.edit().remove(KEY_USER_ID).remove(KEY_SEQ).remove(KEY_ANONYMOUS_ID).remove(KEY_IDENTITY_SENT_FOR).apply()
        ensureAnonymousId()
        clearOwners(ClearReason.RESTORED_FROM_ANOTHER_INSTALL)
        logger.info { "SDK state restored from another install was deleted" }
    }

    private fun clearOwners(reason: ClearReason) {
        for (owner in stateOwners) {
            try {
                owner.clearState(reason)
            } catch (t: Throwable) {
                logger.error({ "Could not clear ${owner.javaClass.simpleName} state" }, t)
            }
        }
    }

    /** Safe from any thread, including inside an observer. */
    val isOptedOut: Boolean
        get() = optedOutMirror ?: prefs.getBoolean(KEY_OPTED_OUT, false)

    // endregion

    // region Observers and debugging

    /** Observers are called on the SDK executor, once per committed event. */
    fun addObserver(observer: EventObserver): EventObserverToken {
        val token = EventObserverToken()
        executor.post { observers[token] = observer }
        return token
    }

    fun removeObserver(token: EventObserverToken) {
        executor.post { observers.remove(token) }
    }

    /**
     * Every event in the on-disk queue, oldest first. **Blocks** the caller until earlier `track`
     * calls are done (at most [SdkExecutor.DEFAULT_WAIT_MILLIS]); safe inside an observer.
     */
    fun queuedEvents(): List<Event> = executor.runAndWait(emptyList()) { store.all() }

    /** **SDK executor only.** The on-disk queue, for the uploader. */
    val queueOnExecutor: EventStore
        get() = store

    /** Waits for everything submitted so far. For tests. */
    fun waitUntilIdle(timeoutMillis: Long = SdkExecutor.DEFAULT_WAIT_MILLIS) {
        executor.runAndWait(Unit, timeoutMillis) {}
    }

    // endregion

    private fun commit(pending: Pending) {
        val seq = prefs.getLong(KEY_SEQ, 0L) + 1
        prefs.edit().putLong(KEY_SEQ, seq).apply()
        val envelope = envelope
        val event = Event(
            eventId = UUID.randomUUID().toString().lowercase(),
            name = pending.name,
            timestamp = Timestamp.format(pending.timestampMillis),
            seq = seq,
            installId = installId,
            anonymousId = pending.anonymousId,
            userId = pending.userId,
            sessionId = pending.sessionId,
            schemaVersion = Event.SCHEMA_VERSION,
            sdkVersion = envelope.sdkVersion,
            platform = Event.PLATFORM,
            appVersion = envelope.appVersion,
            appBuild = envelope.appBuild,
            osVersion = envelope.osVersion,
            properties = pending.properties,
        )
        store.append(event)
        if (event.name == EventName.IDENTIFY) {
            prefs.edit().putString(KEY_IDENTITY_SENT_FOR, event.anonymousId).apply()
        }
        logger.debug { "event ${LogRedaction.forLog(event)}" }
        queueSizeListener?.let { listener ->
            try {
                listener(store.count)
            } catch (t: Throwable) {
                logger.error({ "Queue listener failed" }, t)
            }
        }
        for (observer in observers.values.toList()) {
            try {
                observer.onEvent(event)
            } catch (t: Throwable) {
                logger.error({ "An event observer threw" }, t)
            }
        }
    }

    companion object {
        const val MAX_BUFFERED_EVENTS: Int = 500
        const val KEY_SEQ: String = "seq"
        const val KEY_USER_ID: String = "user_id"
        const val KEY_OPTED_OUT: String = "opted_out"
        const val KEY_ANONYMOUS_ID: String = "anonymous_id"

        /** The anonymous ID whose `identify` has been committed to the queue. */
        const val KEY_IDENTITY_SENT_FOR: String = "identify_sent_for_anonymous_id"
    }
}
