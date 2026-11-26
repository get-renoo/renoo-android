package dev.renoo.sdk.core

import dev.renoo.sdk.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SdkExecutorTest {
    private val executor = SdkExecutor(SdkLogger(LogLevel.NONE) { _, _ -> }, threadName = "test-sdk")

    @Test
    fun runAndWaitKeepsTheCallersInterruptFlag() {
        val release = CountDownLatch(1)
        executor.post { release.await(5, TimeUnit.SECONDS) } // keep the SDK thread busy
        Thread.currentThread().interrupt()
        val result = executor.runAndWait("fallback") { "value" }
        val stillInterrupted = Thread.interrupted() // also clears it for the next test
        release.countDown()
        assertEquals("fallback", result)
        assertTrue("the host app's interrupt must not be swallowed", stillInterrupted)
    }
}
