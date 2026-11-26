package dev.renoo.sdk.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    private val settings = UploadSettings.DEFAULT

    @Test
    fun stepDoublesFrom30sToThe1hCap() {
        val max = (1..12).map { Backoff.delayMillis(it, settings, 1.0) }
        assertEquals(listOf(30_000L, 60_000, 120_000, 240_000, 480_000, 960_000, 1_920_000, 3_600_000, 3_600_000), max.take(9))
        assertTrue(max.all { it <= 3_600_000 })
    }

    @Test
    fun jitterStaysBetweenHalfAndAllOfTheStep() {
        for (failures in 1..20) {
            val step = Backoff.delayMillis(failures, settings, 1.0)
            val min = Backoff.delayMillis(failures, settings, 0.0)
            assertEquals(step / 2, min)
            val mid = Backoff.delayMillis(failures, settings, 0.5)
            assertTrue(mid in min..step)
        }
    }

    @Test
    fun hugeFailureCountsDoNotOverflow() {
        assertEquals(3_600_000L, Backoff.delayMillis(10_000, settings, 1.0))
    }
}
