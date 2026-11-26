package dev.renoo.sdk.collectors

import dev.renoo.sdk.LogLevel
import dev.renoo.sdk.core.FakeSharedPreferences
import dev.renoo.sdk.core.SdkLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class InstallIdentityTest {
    private val logger = SdkLogger(LogLevel.NONE) { _, _ -> }

    private fun identity(
        prefs: FakeSharedPreferences = FakeSharedPreferences(),
        androidId: () -> String? = { ANDROID_ID },
        packageName: String = "com.example.app",
        firstInstall: () -> Long? = { 1_000L },
        clock: () -> Long = { 5_000L },
    ) = InstallIdentity(prefs, logger, { packageName }, AndroidIdSource { androidId() }, firstInstall, clock)

    @Test
    fun derivedFromAndroidIdAndPackageAndStableAcrossReinstall() {
        val first = identity().installId
        // A reinstall: prefs wiped, new first-install time, same device and package.
        val reinstalled = identity(prefs = FakeSharedPreferences(), firstInstall = { 9_000L }).installId
        assertEquals(first, reinstalled)
        assertEquals(InstallIdentity.deriveInstallId(ANDROID_ID, "com.example.app"), first)
        assertNotEquals("per app", first, identity(packageName = "com.example.other").installId)
        assertNotEquals("per device", first, identity(androidId = { "0123456789abcdef" }).installId)

        val uuid = UUID.fromString(first)
        assertEquals(first, uuid.toString())
        assertEquals("version 8", 8, uuid.version())
        assertEquals("RFC variant", 2, uuid.variant())
        assertFalse("never the raw ANDROID_ID", first.replace("-", "").contains(ANDROID_ID))
    }

    @Test
    fun derivationIsCaseAndWhitespaceInsensitiveAndPinned() {
        assertEquals(
            InstallIdentity.deriveInstallId(ANDROID_ID, "com.example.app"),
            InstallIdentity.deriveInstallId(" ${ANDROID_ID.uppercase()} ", "com.example.app"),
        )
        // Pinned so an accidental change to the derivation (which would split every user in two) fails a test.
        // (Independently computed: SHA-256 of "dev.renoo.install-id.v1\\0<android id>\\0<package>", v8 bits set.)
        assertEquals("fe5daebc-1477-8982-8bd7-5c6d8bc454ae", InstallIdentity.deriveInstallId(ANDROID_ID, "com.example.app"))
    }

    @Test
    fun randomFallbackWhenAndroidIdIsMissingBlankBrokenOrThrows() {
        val bad: List<() -> String?> = listOf({ null }, { "  " }, { "9774d56d682e549c" }, { throw SecurityException("nope") })
        val ids = bad.map { androidId ->
            val prefs = FakeSharedPreferences()
            val id = identity(prefs = prefs, androidId = androidId).installId
            assertEquals(InstallIdentity.SOURCE_RANDOM, prefs.getString(InstallIdentity.KEY_INSTALL_ID_SOURCE, null))
            assertEquals("persisted", id, prefs.getString(InstallIdentity.KEY_INSTALL_ID, null))
            assertEquals("same launch after launch", id, identity(prefs = prefs, androidId = androidId).installId)
            UUID.fromString(id)
            id
        }
        assertEquals("all random, all different", ids.size, ids.toSet().size)
    }

    @Test
    fun idSourceSaysWhereTheIdCameFrom() {
        assertEquals(InstallIdentity.SOURCE_ANDROID_ID, identity().idSource)
        assertEquals(InstallIdentity.SOURCE_RANDOM, identity(androidId = { null }).idSource)

        // Prefs from an SDK that saved no source: recomputed from the stored ID.
        val derivedPrefs = FakeSharedPreferences()
        identity(prefs = derivedPrefs).installId
        derivedPrefs.edit().remove(InstallIdentity.KEY_INSTALL_ID_SOURCE).apply()
        assertEquals(InstallIdentity.SOURCE_ANDROID_ID, identity(prefs = derivedPrefs).idSource)
        val randomPrefs = FakeSharedPreferences()
        identity(prefs = randomPrefs, androidId = { null }).installId
        randomPrefs.edit().remove(InstallIdentity.KEY_INSTALL_ID_SOURCE).apply()
        assertEquals(InstallIdentity.SOURCE_RANDOM, identity(prefs = randomPrefs).idSource)
    }

    @Test
    fun storedIdWinsForTheRestOfTheInstall() {
        val prefs = FakeSharedPreferences()
        val random = identity(prefs = prefs, androidId = { null }).installId
        assertEquals("ANDROID_ID appearing later does not change the ID", random, identity(prefs = prefs).installId)

        val derivedPrefs = FakeSharedPreferences()
        val derived = identity(prefs = derivedPrefs).installId
        assertEquals(derived, identity(prefs = derivedPrefs, androidId = { null }).installId)
    }

    @Test
    fun prefsRestoredIntoAnotherInstallAreIgnored() {
        val prefs = FakeSharedPreferences()
        val random = identity(prefs = prefs, androidId = { null }).installId
        // Auto Backup restored these prefs on a new device (or into a reinstall): another first-install time.
        val restored = identity(prefs = prefs, firstInstall = { 2_000L }).installId
        assertNotEquals(random, restored)
        assertEquals(InstallIdentity.deriveInstallId(ANDROID_ID, "com.example.app"), restored)
    }

    @Test
    fun firstInstallTimeFromPackageManagerElseFirstSeen() {
        assertEquals(1_000L, identity().firstInstallTimeMillis)
        val prefs = FakeSharedPreferences()
        var clock = 5_000L
        assertEquals(5_000L, identity(prefs = prefs, firstInstall = { throw RuntimeException("PackageManager died") }, clock = { clock }).firstInstallTimeMillis)
        clock = 7_000L
        assertEquals("kept from the first launch", 5_000L, identity(prefs = prefs, firstInstall = { 0L }, clock = { clock }).firstInstallTimeMillis)
    }

    @Test
    fun isReinstallIsAlwaysNullOnAndroid() {
        assertNull(identity().isReinstall)
        assertNull(identity(androidId = { null }).isReinstall)
    }

    @Test
    fun resolvedOnceAndCachedAcrossThreads() {
        val reads = AtomicInteger()
        val identity = identity(androidId = { reads.incrementAndGet(); Thread.sleep(20); ANDROID_ID })
        val start = CountDownLatch(1)
        val ids = Collections.synchronizedList(mutableListOf<String>())
        val threads = List(8) { thread { start.await(); ids.add(identity.installId) } }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, ids.toSet().size)
        assertEquals(1, reads.get())
        assertTrue(identity.firstInstallTimeMillis > 0)
        assertEquals(1, reads.get())
    }

    private companion object {
        const val ANDROID_ID = "3f9a1c2b7d4e5f60"
    }
}
