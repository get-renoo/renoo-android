package dev.renoo.sdk.core

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.os.Build
import dev.renoo.sdk.Renoo
import dev.renoo.sdk.Configuration
import dev.renoo.sdk.collectors.ContextCollector
import dev.renoo.sdk.collectors.InstallCollector
import dev.renoo.sdk.collectors.InstallIdentity
import dev.renoo.sdk.collectors.LifecycleCollector
import dev.renoo.sdk.collectors.ScreenCollector
import dev.renoo.sdk.collectors.SubscriptionCollector
import dev.renoo.sdk.upload.HealthCheck
import dev.renoo.sdk.upload.HealthStatus
import dev.renoo.sdk.upload.HttpTransport
import dev.renoo.sdk.upload.IngestEndpoint
import dev.renoo.sdk.upload.NetworkKind
import dev.renoo.sdk.upload.OfferFetcher
import dev.renoo.sdk.upload.TimerScheduler
import dev.renoo.sdk.upload.UploadSettings
import dev.renoo.sdk.upload.Uploader
import dev.renoo.sdk.upload.UrlConnectionTransport
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

/**
 * Owns the pipeline and the collectors for one configured SDK. Construction does no disk or
 * Binder work (the store, envelope and install ID are resolved lazily on the executor).
 *
 * **Storage and Android backup.** A library can't change the host app's backup rules, and Auto
 * Backup (and device-to-device transfer) copies `files/` and `shared_prefs/` by default. Two layers
 * keep SDK data from following the user to the cloud or to another device:
 * 1. The event queue lives in `noBackupFilesDir/renoo/queue.jsonl`
 *    (`<data dir>/no_backup/`), which Android never backs up.
 * 2. The SharedPreferences file [PREFS_NAME] can't be moved there, so it may be restored. Every
 *    install records its marker (`PackageInfo.firstInstallTime`, see [InstallIdentity]). The first
 *    task on the SDK executor after the pipeline's load ([clearRestoredState]) compares it, and if
 *    the prefs belong to another install it deletes everything restored: queue, `user_id`, `seq`,
 *    the anonymous ID (a new one is made), and each [StateOwner]'s keys (lifecycle, subscription, context). It keeps `opted_out`.
 *    Remaining gap: [ContextCollector.seedFromPrefs] and a lifecycle activation on the main thread
 *    can read restored prefs in the few milliseconds before that task runs (the check needs a
 *    Binder call, which the main thread never makes); the owners' hooks reset their in-memory
 *    copies too, so only a session opened in that window can carry restored values.
 *
 * @param appContext the application context.
 * @param prefs the SDK's SharedPreferences; tests pass their own.
 * @param storeDirectory the queue directory; defaults to `noBackupFilesDir/renoo`.
 * @param identity tests pass one with a fixed install marker.
 * @param transport tests pass a fake; production uses [UrlConnectionTransport]. Used only when
 *   `ingestUrl` is set.
 */
internal class SdkCore(
    configuration: Configuration,
    appContext: Context,
    prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    storeDirectory: File? = null,
    identity: InstallIdentity? = null,
    transport: HttpTransport? = null,
    elapsedClock: () -> Long = SystemClock::elapsedRealtime,
) {
    val environment: SdkEnvironment
    val install: InstallCollector
    val subscription: SubscriptionCollector
    val context: ContextCollector
    val lifecycle: LifecycleCollector
    val screens: ScreenCollector

    /** `null` when `ingestUrl` is not set or was rejected: capture only, no network code runs. */
    val uploader: Uploader?

    /** `GET /health-check` on start-up; `null` exactly when [uploader] is. */
    val healthCheck: HealthCheck?

    /** Engaged by any `401`; in memory only. */
    val killSwitch: KillSwitch

    /** `GET /offers`; `null` exactly when [uploader] is. */
    val offerFetcher: OfferFetcher?

    /** The public `Renoo.pendingOffer`; always present (stays `null` in capture-only mode). */
    val pendingOffers: PendingOffers

    init {
        val logger = SdkLogger(configuration.logLevel)
        val executor = SdkExecutor(logger)
        val identity = identity ?: InstallIdentity(appContext, prefs, logger)
        val pipeline = EventPipeline(
            executor = executor,
            storeProvider = {
                EventStore(storeDirectory ?: File(appContext.noBackupFilesDir, EventStore.DIRECTORY_NAME), logger = logger)
            },
            prefs = prefs,
            logger = logger,
            envelopeProvider = { envelope(appContext, logger) },
            trackingScope = configuration.trackingScope,
            installIdProvider = { identity.installId },
        )
        environment = SdkEnvironment(
            configuration = configuration,
            pipeline = pipeline,
            prefs = prefs,
            logger = logger,
            executor = executor,
            appContext = appContext,
            identity = identity,
        )
        install = InstallCollector(environment)
        subscription = SubscriptionCollector(environment)
        context = ContextCollector(environment)
        lifecycle = LifecycleCollector(environment, context)
        screens = ScreenCollector(environment)

        killSwitch = KillSwitch(logger)
        val mainHandler by lazy { Handler(Looper.getMainLooper()) }
        pendingOffers = PendingOffers(logger) { block -> mainHandler.post(block) }
        val network = makeNetwork(configuration, pipeline, context, executor, logger, killSwitch, transport, pendingOffers, elapsedClock)
        uploader = network?.uploader
        healthCheck = network?.healthCheck
        offerFetcher = network?.offers
        offerFetcher?.let { offers ->
            killSwitch.addListener(offers::stop)
            pipeline.identityResetListener = offers::onIdentityReset
            pipeline.trackingGateListener = offers::onTrackingGateChanged
        }
        uploader?.let { uploader ->
            killSwitch.addListener(uploader::stop)
            pipeline.queueSizeListener = uploader::onQueueSize
            lifecycle.foregroundListener = { foreground ->
                executor.post {
                    uploader.onForegroundChanged(foreground)
                    if (foreground) offerFetcher?.onForeground()
                }
            }
            context.networkListener = { type -> executor.post { uploader.onNetworkChanged(NetworkKind.fromNetworkType(type)) } }
        }
        killSwitch.addListener(pipeline::stopCapture)

        listOfNotNull<StateOwner>(subscription, context, lifecycle, uploader, offerFetcher).forEach(pipeline::addStateOwner)
        // Posted before `configure` publishes this core and before `start()`, so it runs right after
        // the pipeline's load and before any collector or API call touches the executor.
        executor.post {
            if (storeDirectory == null) deleteLegacyQueue(appContext, logger)
            clearRestoredState(identity, pipeline, logger)
            // With TrackingScope.EVERYONE the gate is already open; otherwise this waits for it.
            pipeline.sendIdentityIfNeeded()
        }
    }

    /** Main thread. Order matters: install and subscription state first, then lifecycle. */
    fun start() {
        listOf(install, subscription, context, lifecycle, screens).forEach { collector ->
            try {
                collector.start()
            } catch (t: Throwable) {
                environment.logger.error({ "${collector.javaClass.simpleName}.start failed" }, t)
            }
        }
        if (healthCheck != null) environment.executor.post(::runStartupChecks)
        environment.logger.info { "configured (sdk ${Renoo.SDK_VERSION})" }
    }

    /**
     * Executor, once per start-up. Capture is already running; this never delays it.
     * `401` → kill switch. Anything else (`200`, `5xx`, network error) → carry on: `GET /offers`
     * (only if the user is tracked; otherwise it waits for the tracking gate to open), then the
     * cold-start upload (a blocked key would get its `401` there too).
     */
    private fun runStartupChecks() {
        val check = healthCheck ?: return
        if (check.run() == HealthStatus.UNAUTHORIZED) {
            killSwitch.engage("health check")
            return
        }
        if (killSwitch.isEngaged) return
        offerFetcher?.onStartup()
        if (killSwitch.isEngaged) return
        uploader?.onColdStart()
    }

    /** The network side; exists only when `ingestUrl` is set and valid. */
    private class Network(val uploader: Uploader, val healthCheck: HealthCheck, val offers: OfferFetcher)

    companion object {
        /** The SDK's own SharedPreferences file. Android may back it up and restore it: see the class KDoc. */
        const val PREFS_NAME: String = "dev.renoo.sdk"

        /**
         * Main thread, no I/O: URL parsing only. The timer thread and connections are created
         * lazily, on first use, and never when `ingestUrl` is null.
         */
        private fun makeNetwork(
            configuration: Configuration,
            pipeline: EventPipeline,
            context: ContextCollector,
            executor: SdkExecutor,
            logger: SdkLogger,
            killSwitch: KillSwitch,
            transport: HttpTransport?,
            pendingOffers: PendingOffers,
            elapsedClock: () -> Long,
        ): Network? = when (val endpoint = IngestEndpoint.resolve(configuration.ingestUrl, configuration.allowInsecureHttp)) {
            IngestEndpoint.Resolution.Disabled -> {
                logger.info { "no ingestUrl: events are captured and queued, nothing is sent" }
                null
            }
            is IngestEndpoint.Resolution.Rejected -> {
                logger.error { "${endpoint.reason}. Uploading is disabled; events are still captured and queued." }
                null
            }
            is IngestEndpoint.Resolution.Enabled -> {
                val settings = UploadSettings.DEFAULT
                val scheduler = TimerScheduler(logger)
                if (endpoint.events.protocol == "http") {
                    logger.error { "allowInsecureHttp is on: events are sent without TLS. Debug builds only." }
                }
                // One transport for all three calls: connect 10 s, whole request 30 s.
                val http = transport ?: UrlConnectionTransport(scheduler, settings.connectTimeoutMillis, settings.requestTimeoutMillis)
                val uploader = Uploader(
                    endpoint = endpoint.events,
                    apiKey = configuration.apiKey,
                    settings = settings,
                    store = { pipeline.queueOnExecutor },
                    isOptedOut = { pipeline.isOptedOut },
                    network = { NetworkKind.fromNetworkType(context.networkType) },
                    transport = http,
                    scheduler = scheduler,
                    postToExecutor = executor::post,
                    logger = logger,
                    onUnauthorized = { killSwitch.engage("upload") },
                )
                val offers = OfferFetcher(
                    url = endpoint.offers,
                    apiKey = configuration.apiKey,
                    transport = http,
                    offers = pendingOffers,
                    logger = logger,
                    identity = pipeline::identityForRequests,
                    onUnauthorized = { killSwitch.engage("offers") },
                    clock = elapsedClock,
                )
                Network(uploader, HealthCheck(endpoint.healthCheck, configuration.apiKey, http, logger), offers)
            }
        }

        /** Executor. Wipes state restored from another install (layer 2 in the class KDoc). Reads PackageManager (Binder). */
        internal fun clearRestoredState(identity: InstallIdentity, pipeline: EventPipeline, logger: SdkLogger) {
            try {
                if (identity.restoredFromAnotherInstall) pipeline.clearRestoredState()
            } catch (t: Throwable) {
                logger.error({ "Could not check for restored state" }, t)
            }
        }

        /** Executor. Early builds kept the queue in `filesDir`, which is backed up; nothing has shipped, so it is deleted, not migrated. */
        private fun deleteLegacyQueue(context: Context, logger: SdkLogger) {
            try {
                val directory = File(context.filesDir, EventStore.DIRECTORY_NAME)
                if (!directory.exists()) return
                File(directory, EventStore.FILE_NAME).delete()
                File(directory, EventStore.FILE_NAME + ".tmp").delete()
                if (directory.list()?.isEmpty() == true) directory.delete()
            } catch (t: Throwable) {
                logger.error({ "Could not delete the old queue" }, t)
            }
        }

        /** Runs on the executor: PackageManager is a Binder call. */
        private fun envelope(context: Context, logger: SdkLogger): Envelope {
            var appVersion = "unknown"
            var appBuild = "unknown"
            try {
                val info: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                appVersion = info.versionName ?: "unknown"
                appBuild = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode.toString()
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toString()
                }
            } catch (t: Throwable) {
                logger.error({ "Could not read the app version" }, t)
            }
            return Envelope(
                sdkVersion = Renoo.SDK_VERSION,
                appVersion = appVersion,
                appBuild = appBuild,
                osVersion = Build.VERSION.RELEASE ?: "unknown",
            )
        }
    }
}
