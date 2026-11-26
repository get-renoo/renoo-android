package dev.renoo.sdk.upload

import dev.renoo.sdk.Event
import dev.renoo.sdk.EventName
import dev.renoo.sdk.LogLevel
import dev.renoo.sdk.core.EventStore
import dev.renoo.sdk.core.SdkLogger
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.util.UUID
import java.util.zip.GZIPInputStream

/** Delayed tasks driven by a fake clock. */
internal class FakeScheduler(private val clock: () -> Long) : Scheduler {
    class Task(val dueAt: Long, val task: () -> Unit) {
        var cancelled = false
    }

    val tasks = mutableListOf<Task>()

    override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
        val entry = Task(clock() + delayMillis, task)
        tasks.add(entry)
        return Cancellable { entry.cancelled = true }
    }

    /** The earliest live task due at or before [time], removed from the list. */
    fun takeDue(time: Long): Task? {
        val next = tasks.filter { !it.cancelled && it.dueAt <= time }.minByOrNull { it.dueAt } ?: return null
        tasks.remove(next)
        return next
    }

    val liveTasks: List<Task> get() = tasks.filter { !it.cancelled }
}

/** Records every request; answers with [responder] (default `202`). */
internal class FakeTransport(private val clock: () -> Long) : HttpTransport {
    class Sent(val request: HttpRequest, val at: Long) {
        val headers: Map<String, String> get() = request.headers
        val path: String get() = request.url.path
        val ndjson: String get() = GZIPInputStream(request.body.inputStream()).readBytes().toString(Charsets.UTF_8)
        val lines: List<String> get() = ndjson.split("\n").filter { it.isNotEmpty() }
        val eventIds: List<String> get() = lines.map { JSONObject(it).getString("event_id") }
        val batchId: String get() = headers.getValue("X-Renoo-Batch-Id")
        val sentAt: String get() = headers.getValue("X-Renoo-Sent-At")
    }

    val sent = mutableListOf<Sent>()
    var responder: (Sent) -> HttpResponse = { accepted(it.lines.size) }

    override fun send(request: HttpRequest): HttpResponse {
        val entry = Sent(request, clock())
        sent.add(entry)
        return responder(entry)
    }

    companion object {
        fun accepted(count: Int, rejected: String = "[]") = HttpResponse(202, null, """{"accepted": $count, "rejected": $rejected}""")
        fun status(code: Int, retryAfter: String? = null) = HttpResponse(code, retryAfter, """{"error": "x", "message": "y"}""")
        fun networkError(): Nothing = throw IOException("connection refused")
    }
}

/**
 * An [Uploader] over a real on-disk [EventStore], with a fake clock, scheduler, transport and
 * executor ([posted] tasks run on [drain], in order, like the SDK's single thread).
 */
internal class UploaderHarness(
    val settings: UploadSettings = UploadSettings.DEFAULT,
    val apiKey: String = "pk_test_SECRET_KEY_123",
) {
    val directory = Files.createTempDirectory("renoo-upload-test").toFile()
    val logs = mutableListOf<Pair<Int, String>>()
    val logger = SdkLogger(LogLevel.DEBUG) { priority, message -> logs.add(priority to message) }
    val store = EventStore(directory, logger = logger)
    var now = 0L
    val scheduler = FakeScheduler { now }
    val transport = FakeTransport { now }
    var network = NetworkKind.OTHER
    var optedOut = false
    var random = 0.5
    var unauthorizedCalls = 0
    val posted = ArrayDeque<() -> Unit>()

    val uploader: Uploader = makeUploader()

    fun makeUploader(): Uploader = Uploader(
        endpoint = URL("https://ingest.example.com/events/sdk"),
        apiKey = apiKey,
        settings = settings,
        store = { store },
        isOptedOut = { optedOut },
        network = { network },
        transport = transport,
        scheduler = scheduler,
        postToExecutor = { posted.addLast(it) },
        logger = logger,
        monotonicClock = { now },
        wallClock = { WALL_START + now },
        random = { random },
        onUnauthorized = { unauthorizedCalls += 1 },
    )

    /** Runs every posted task, including ones posted while draining. */
    fun drain() {
        while (posted.isNotEmpty()) posted.removeFirst()()
    }

    /** Moves the clock forward, firing due timers (and draining) in time order. */
    fun advance(millis: Long) {
        val target = now + millis
        while (true) {
            val task = scheduler.takeDue(target) ?: break
            now = maxOf(now, task.dueAt)
            task.task()
            drain()
        }
        now = target
        drain()
    }

    fun append(count: Int, propertyBytes: Int = 0): List<String> = (1..count).map {
        val id = UUID.randomUUID().toString()
        store.append(event(id, propertyBytes))
        id
    }

    fun queuedIds(): List<String> = store.all().map { it.eventId }

    fun tearDown() {
        directory.deleteRecursively()
    }

    companion object {
        private val rng = java.util.Random(42)

        fun randomText(length: Int): String {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            return String(CharArray(length) { chars[rng.nextInt(chars.length)] })
        }

        const val WALL_START: Long = 1_794_000_000_000L

        fun event(id: String, propertyBytes: Int = 0) = Event(
            eventId = id,
            name = EventName.SCREEN_VIEW,
            timestamp = "2026-11-16T12:00:00.000Z",
            seq = 1,
            installId = "install-1",
            anonymousId = "00000000-0000-4000-8000-000000000001",
            userId = null,
            sessionId = "s1",
            schemaVersion = 0,
            sdkVersion = "0.1.0",
            platform = "android",
            appVersion = "1.0",
            appBuild = "1",
            osVersion = "16",
            // Random padding, so gzip can't shrink it away.
            properties = if (propertyBytes > 0) mapOf("padding" to randomText(propertyBytes)) else mapOf("screen_name" to "Home"),
        )
    }
}
