package dev.renoo.sdk

import android.app.Application
import dev.renoo.sdk.core.MainProcess
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** `Configuration.ingestUrl` / `allowInsecureHttp` decide whether an uploader exists at all. */
@RunWith(RobolectricTestRunner::class)
class UploaderWiringTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val handler = PurchaseHandler { _, onResult -> onResult(PurchaseResult.CANCELLED) }
    private val defaultProcessName = MainProcess.currentProcessName

    @Before
    fun setUp() = Renoo.setCoreForTesting(null)

    @After
    fun tearDown() {
        Renoo.setCoreForTesting(null)
        MainProcess.currentProcessName = defaultProcessName
    }

    private fun configure(url: String?, insecure: Boolean = false) {
        Renoo.configure(
            app,
            Configuration(apiKey = "k", purchaseHandler = handler, trackingScope = TrackingScope.EVERYONE, ingestUrl = url, allowInsecureHttp = insecure),
        )
    }

    @Test
    fun noUrlMeansNoUploaderAndCaptureStillWorks() {
        configure(null)
        val core = Renoo.coreForTesting
        assertNotNull(core)
        assertNull(core?.uploader)
        Renoo.trackScreen("Home")
        assertTrue(Renoo.queuedEvents().any { it.name == EventName.SCREEN_VIEW })
    }

    @Test
    fun httpUrlIsRejectedButCaptureContinues() {
        configure("http://10.0.2.2:8788")
        assertNull(Renoo.coreForTesting?.uploader)
        Renoo.trackScreen("Home")
        assertTrue(Renoo.queuedEvents().any { it.name == EventName.SCREEN_VIEW })
    }

    @Test
    fun httpUrlIsAcceptedWithTheDebugFlag() {
        // Port 1 on localhost: nothing listens, so any attempt fails fast without leaving the machine.
        configure("http://127.0.0.1:1", insecure = true)
        assertNotNull(Renoo.coreForTesting?.uploader)
    }

    @Test
    fun httpsUrlEnablesTheUploader() {
        configure("https://127.0.0.1:1/")
        assertNotNull(Renoo.coreForTesting?.uploader)
    }

    @Test
    fun anInvalidUrlNeverThrows() {
        configure("::not a url::")
        assertNotNull(Renoo.coreForTesting)
        assertNull(Renoo.coreForTesting?.uploader)
    }
}
