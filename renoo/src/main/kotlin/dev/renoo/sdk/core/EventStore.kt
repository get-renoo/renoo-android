package dev.renoo.sdk.core

import dev.renoo.sdk.Event
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * The persistent on-disk queue: one JSON event per line in `<directory>/queue.jsonl`
 * (in production `noBackupFilesDir/renoo/queue.jsonl`, see `SdkCore`). Only ever used
 * from the SDK executor. The uploader reads the oldest lines with [oldest] / [linesFor]
 * and deletes them with [remove] only when the upload response says the events were stored or permanently rejected.
 *
 * When the file grows past [maxEvents] the oldest 10% are dropped. Lines that fail to parse are
 * skipped on read. A last line cut short by a crash mid-write is closed with a newline before the
 * next append, so it can't merge with (and corrupt) the next event.
 */
internal class EventStore(
    directory: File,
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
    private val logger: SdkLogger,
) {
    private val file = File(directory, FILE_NAME)

    /** Lines currently in the file (valid or not). */
    var count: Int = 0
        private set

    init {
        try {
            if (!directory.isDirectory && !directory.mkdirs()) {
                logger.error { "Could not create the queue directory" }
            }
        } catch (t: Throwable) {
            logger.error({ "Could not create the queue directory" }, t)
        }
        count = lines().size
    }

    fun append(event: Event) {
        try {
            val prefix = if (endsWithoutNewline()) "\n" else ""
            FileOutputStream(file, true).use { it.write((prefix + event.toJsonString() + "\n").toByteArray(Charsets.UTF_8)) }
            count += 1
            if (count > maxEvents) trim()
        } catch (t: Throwable) {
            logger.error({ "Could not persist an event" }, t)
        }
    }

    /** Every queued event, oldest first. Lines that fail to parse are skipped. */
    fun all(): List<Event> = lines().mapNotNull { Event.fromJsonString(it) }

    /**
     * The oldest valid lines, as stored, up to [maxEvents] and [maxBytes] of UTF-8 (always at least
     * one line, so a single huge event still goes out and gets its `413`). Unparseable lines are skipped.
     */
    fun oldest(maxEvents: Int, maxBytes: Int = Int.MAX_VALUE): List<QueuedLine> {
        val result = ArrayList<QueuedLine>()
        var bytes = 0L
        for (line in lines()) {
            if (result.size >= maxEvents) break
            val id = eventIdOf(line) ?: continue
            val size = line.toByteArray(Charsets.UTF_8).size + 1L
            if (result.isNotEmpty() && bytes + size > maxBytes) break
            result.add(QueuedLine(id, line))
            bytes += size
        }
        return result
    }

    /** The queued lines whose event ID is in [ids], in queue order (some may be gone, e.g. trimmed). */
    fun linesFor(ids: Collection<String>): List<QueuedLine> {
        val wanted = ids.toHashSet()
        return lines().mapNotNull { line -> eventIdOf(line)?.takeIf { it in wanted }?.let { QueuedLine(it, line) } }
    }

    /**
     * Deletes the events with these IDs (written to a temp file and renamed over the queue, so a
     * kill mid-way leaves either the old or the new file). Unparseable lines are dropped too: they
     * could never be delivered. Returns how many lines were removed.
     */
    fun remove(ids: Set<String>): Int {
        return try {
            val all = lines()
            val kept = all.filter { line -> eventIdOf(line)?.let { it !in ids } ?: false }
            if (kept.size == all.size) return 0
            if (kept.isEmpty()) {
                clear()
            } else {
                replaceWith(kept)
            }
            all.size - kept.size
        } catch (t: Throwable) {
            logger.error({ "Could not remove sent events from the queue" }, t)
            0
        }
    }

    fun clear() {
        try {
            if (file.exists() && !file.delete()) logger.error { "Could not delete the queue" }
        } catch (t: Throwable) {
            logger.error({ "Could not delete the queue" }, t)
        }
        count = 0
    }

    /** True when the file is non-empty and its last byte is not `\n` (an interrupted write). */
    private fun endsWithoutNewline(): Boolean = try {
        if (!file.exists() || file.length() == 0L) {
            false
        } else {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(raf.length() - 1)
                raf.read() != '\n'.code
            }
        }
    } catch (t: Throwable) {
        logger.error({ "Could not check the end of the queue" }, t)
        true // An extra blank line is skipped on read; a merged line would lose two events.
    }

    private fun lines(): List<String> = try {
        if (file.exists()) file.readLines(Charsets.UTF_8).filter { it.isNotBlank() } else emptyList()
    } catch (t: Throwable) {
        logger.error({ "Could not read the queue" }, t)
        emptyList()
    }

    /** Keeps the newest 90%, written to a temp file and renamed over the queue. */
    private fun trim() {
        try {
            replaceWith(lines().takeLast(maxEvents - maxEvents / 10))
        } catch (t: Throwable) {
            logger.error({ "Could not trim the queue" }, t)
        }
    }

    private fun replaceWith(kept: List<String>) {
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(kept.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) logger.error { "Could not replace the queue file" }
        }
        count = kept.size
    }

    private fun eventIdOf(line: String): String? = Event.fromJsonString(line)?.eventId?.takeIf { it.isNotEmpty() }

    /** One queued event: its ID and the exact JSON line stored on disk (sent as is). */
    class QueuedLine(val eventId: String, val line: String)

    companion object {
        const val DEFAULT_MAX_EVENTS: Int = 5_000
        const val FILE_NAME: String = "queue.jsonl"
        const val DIRECTORY_NAME: String = "renoo"
    }
}
