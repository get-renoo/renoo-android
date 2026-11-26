package dev.renoo.sdk.collectors

import android.content.SharedPreferences
import dev.renoo.sdk.Event
import dev.renoo.sdk.EventName
import dev.renoo.sdk.EventObserver
import dev.renoo.sdk.EventObserverToken
import dev.renoo.sdk.core.Collector
import dev.renoo.sdk.core.EventPipeline
import dev.renoo.sdk.core.SdkEnvironment
import dev.renoo.sdk.core.SdkLogger
import java.util.concurrent.Executor

/**
 * Emits `install` once per install (`is_reinstall` is always `null` on Android; `id_source` says
 * whether the install ID comes from `ANDROID_ID` or is random).
 * The rules are in [InstallEventTracker].
 */
internal class InstallCollector(private val environment: SdkEnvironment) : Collector {
    val tracker = InstallEventTracker(
        pipeline = environment.pipeline,
        prefs = environment.prefs,
        logger = environment.logger,
        executor = environment.executor,
        installMarker = { environment.identity.firstInstallTimeMillis },
        isReinstall = { environment.identity.isReinstall },
        idSource = { environment.identity.idSource },
    )

    override fun start() = tracker.start()
}

/**
 * `install`, once per install, and only counted once it is really **committed** (stored).
 *
 * With `SUBSCRIBERS_ONLY` most first launches are not subscribers, so the event is dropped by the
 * tracking gate. iOS set its "done" flag when tracking, so a user who subscribed later never got
 * an `install`. Here an [EventObserver] on the pipeline sets the flag only when it sees the
 * `install` event committed. Until then:
 * - a later launch tracks it again;
 * - within this process, when any other event is committed while `install` hasn't been (it was
 *   dropped by the gate or pushed out of the memory buffer, and the gate is now open, e.g. the user
 *   just subscribed), it is tracked again once.
 * Every attempt carries the timestamp of the first launch of this install, so a late `install`
 * still says when the install happened.
 *
 * "This install" is the first-install time ([InstallIdentity.firstInstallTimeMillis]), so if
 * Android's backup restores the SDK prefs into a reinstall or onto a new device, `install` is
 * still emitted there.
 */
internal class InstallEventTracker(
    private val pipeline: EventPipeline,
    private val prefs: SharedPreferences,
    private val logger: SdkLogger,
    private val executor: Executor,
    private val installMarker: () -> Long,
    private val isReinstall: () -> Boolean?,
    private val idSource: () -> String = { InstallIdentity.SOURCE_RANDOM },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var token: EventObserverToken? = null
    private var marker = 0L
    private var firstSeenMillis = 0L
    private var committed = false
    private var retried = false

    fun start() = executor.execute {
        val marker = installMarker()
        if (prefs.contains(KEY_TRACKED_FOR) && prefs.getLong(KEY_TRACKED_FOR, 0L) == marker) return@execute
        val firstSeen = if (prefs.contains(KEY_FIRST_SEEN_FOR) && prefs.getLong(KEY_FIRST_SEEN_FOR, 0L) == marker) {
            prefs.getLong(KEY_FIRST_SEEN, clock())
        } else {
            clock().also { prefs.edit().putLong(KEY_FIRST_SEEN, it).putLong(KEY_FIRST_SEEN_FOR, marker).apply() }
        }
        synchronized(lock) {
            if (token != null) return@execute // started twice
            this.marker = marker
            firstSeenMillis = firstSeen
            // Registered before tracking, so the observer sees the commit (both run in order on the SDK executor).
            token = pipeline.addObserver(EventObserver(::onCommitted))
        }
        track()
    }

    /** Called on the SDK executor for every committed event. */
    private fun onCommitted(event: Event) {
        val retry = synchronized(lock) {
            when {
                committed -> false
                event.name == EventName.INSTALL -> {
                    committed = true
                    // commit(), not apply(): a lost flag would emit a duplicate `install` next launch.
                    prefs.edit().putLong(KEY_TRACKED_FOR, marker).commit()
                    token?.let(pipeline::removeObserver)
                    token = null
                    false
                }
                retried -> false
                else -> {
                    retried = true
                    true
                }
            }
        }
        if (retry) {
            logger.debug { "install was not recorded earlier; tracking it again now the gate is open" }
            track()
        }
    }

    private fun track() {
        pipeline.track(
            EventName.INSTALL,
            mapOf("is_reinstall" to isReinstall(), "id_source" to idSource()),
            timestampMillis = firstSeenMillis,
        )
    }

    companion object {
        /** First-install time of the install whose `install` event was committed. */
        const val KEY_TRACKED_FOR: String = "install_event_tracked_for"
        const val KEY_FIRST_SEEN: String = "install_first_seen_millis"
        const val KEY_FIRST_SEEN_FOR: String = "install_first_seen_for"
    }
}
