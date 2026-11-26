package dev.renoo.sdk.upload

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Upload body format: NDJSON (one queued line per event, each ending in `\n`), gzip-compressed. */
internal object BatchEncoder {
    fun ndjson(lines: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        for (line in lines) {
            out.write(line.toByteArray(Charsets.UTF_8))
            out.write('\n'.code)
        }
        return out.toByteArray()
    }

    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}
