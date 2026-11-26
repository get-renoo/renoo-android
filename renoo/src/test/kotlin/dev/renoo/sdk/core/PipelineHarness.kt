package dev.renoo.sdk.core

import dev.renoo.sdk.LogLevel
import dev.renoo.sdk.TrackingScope
import java.io.File
import java.nio.file.Files

/**
 * A pipeline with no Android dependencies: in-memory prefs, a temp queue directory, a fixed
 * envelope and install ID. Several pipelines made from one harness share disk and prefs,
 * like an app restart. [identitySent] seeds an anonymous ID whose `identify` was already sent.
 */
internal class PipelineHarness(identitySent: Boolean = true) {
    val directory: File = Files.createTempDirectory("renoo-test").toFile()
    val prefs = FakeSharedPreferences().also { if (identitySent) it.withIdentitySent() }
    val logger = SdkLogger(LogLevel.NONE) { _, _ -> }

    fun makePipeline(
        scope: TrackingScope = TrackingScope.EVERYONE,
        executor: SdkExecutor = SdkExecutor(logger, threadName = "test-sdk"),
    ): EventPipeline = EventPipeline(
        executor = executor,
        storeProvider = { EventStore(directory, logger = logger) },
        prefs = prefs,
        logger = logger,
        envelopeProvider = { Envelope(sdkVersion = "0.0.0", appVersion = "1.0", appBuild = "1", osVersion = "16") },
        trackingScope = scope,
        installIdProvider = { "install-1" },
    )

    fun tearDown() {
        directory.deleteRecursively()
    }
}
