package dev.renoo.sdk.collectors

import dev.renoo.sdk.Event
import dev.renoo.sdk.EventName
import dev.renoo.sdk.LogLevel
import dev.renoo.sdk.TrackingScope
import dev.renoo.sdk.core.Envelope
import dev.renoo.sdk.core.EventPipeline
import dev.renoo.sdk.core.EventStore
import dev.renoo.sdk.core.FakeSharedPreferences
import dev.renoo.sdk.core.withIdentitySent
import dev.renoo.sdk.core.SdkExecutor
import dev.renoo.sdk.core.SdkLogger
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

/**
 * Pipelines on shared disk and prefs (several made from one harness are like app restarts).
 * Collector logic under test runs on [inline], so the test thread drives it directly; the
 * pipeline keeps its own executor, and [events] waits for it to settle.
 */
internal class CollectorHarness {
    val directory: File = Files.createTempDirectory("renoo-collectors").toFile()
    val prefs = FakeSharedPreferences().withIdentitySent()
    val logger = SdkLogger(LogLevel.NONE) { _, _ -> }
    val inline = Executor { it.run() }

    private val pipelines = ArrayList<EventPipeline>()

    /**
     * A new pipeline on the shared disk, like an app restart. Earlier pipelines are drained first:
     * a real restart only begins after the previous process has stopped writing, and an undrained
     * pipeline's background writes could otherwise land after the new one's events.
     */
    fun makePipeline(scope: TrackingScope = TrackingScope.SUBSCRIBERS_ONLY): EventPipeline {
        pipelines.forEach { it.events() }
        return EventPipeline(
            executor = SdkExecutor(logger, threadName = "test-sdk"),
            storeProvider = { EventStore(directory, logger = logger) },
            prefs = prefs,
            logger = logger,
            envelopeProvider = { Envelope(sdkVersion = "0.0.0", appVersion = "1.0", appBuild = "1", osVersion = "16") },
            trackingScope = scope,
            installIdProvider = { "install-1" },
        ).also { pipelines += it }
    }

    fun tearDown() {
        directory.deleteRecursively()
    }
}

/** Stored events once the pipeline is idle. Observers may post follow-up work, hence the repeats. */
internal fun EventPipeline.events(): List<Event> {
    repeat(3) { waitUntilIdle() }
    return queuedEvents()
}

internal fun List<Event>.named(name: EventName): List<Event> = filter { it.name == name }

internal fun List<Event>.states(): List<String?> = named(EventName.SUBSCRIPTION_STATE).map { it.properties["status"] as String? }

/** Scripted Play Billing. Answers immediately from [results] (or [purchases]), or holds reads when [manual]. */
internal class FakeSubscriptionSource : SubscriptionSource {
    var purchases: List<PurchaseFacts> = emptyList()
    val results = ArrayDeque<SubscriptionReadResult>()
    var manual = false
    val held = ArrayList<(SubscriptionReadResult) -> Unit>()
    var reads = 0
    var releases = 0

    override fun read(callback: (SubscriptionReadResult) -> Unit) {
        reads += 1
        if (manual) {
            held.add(callback)
        } else {
            callback(results.removeFirstOrNull() ?: SubscriptionReadResult.Success(purchases))
        }
    }

    /** Offers per product the fake catalogue returns; products not in it are "not found". */
    var catalogue: Map<String, List<OfferFacts>> = emptyMap()
    val detailsResults = ArrayDeque<ProductDetailsResult>()
    var manualDetails = false
    val heldDetails = ArrayList<(ProductDetailsResult) -> Unit>()
    val detailsQueries = ArrayList<List<String>>()

    override fun productDetails(productIds: List<String>, callback: (ProductDetailsResult) -> Unit) {
        detailsQueries.add(productIds)
        if (manualDetails) {
            heldDetails.add(callback)
        } else {
            callback(detailsResults.removeFirstOrNull() ?: ProductDetailsResult.Success(catalogue.filterKeys { it in productIds }))
        }
    }

    override fun release() {
        releases += 1
    }

    fun answerHeld(result: SubscriptionReadResult = SubscriptionReadResult.Success(purchases)) {
        held.removeAt(0)(result)
    }
}

/** Tasks wait until the test runs them. */
internal class FakeScheduler : Scheduler {
    val tasks = ArrayList<Pair<Long, () -> Unit>>()

    override fun schedule(delayMillis: Long, task: () -> Unit) {
        tasks.add(delayMillis to task)
    }

    /** Runs (and removes) every task scheduled with [delayMillis]. */
    fun run(delayMillis: Long) {
        val due = tasks.filter { it.first == delayMillis }
        tasks.removeAll(due)
        due.forEach { it.second() }
    }

    fun delays(): List<Long> = tasks.map { it.first }
}

internal object Fixture {
    const val TOKEN_A = "token-a-0123456789"
    const val TOKEN_B = "token-b-9876543210"
    const val T0 = 1_700_000_000_000L

    fun purchase(
        token: String = TOKEN_A,
        orderId: String? = "GPA.1111-2222-3333-44444",
        product: String = "premium_monthly",
        time: Long = T0,
        state: PurchaseStateValue = PurchaseStateValue.PURCHASED,
        autoRenewing: Boolean = true,
        suspended: Boolean = false,
        accountToken: String? = null,
    ) = PurchaseFacts(token, orderId, listOf(product), time, state, autoRenewing, suspended, accountToken)
}
