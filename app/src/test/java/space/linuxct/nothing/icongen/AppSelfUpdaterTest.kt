package space.linuxct.nothing.icongen

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.provider.Settings
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AppSelfUpdaterTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences(AppSelfUpdater.PREFS, Context.MODE_PRIVATE)

    @Before fun clear() {
        prefs.edit().clear().commit()
        context.getSharedPreferences("app_update_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", -1)
        installVersion("1.0.0.1", 1)
    }

    @After fun releaseOperation() {
        ReflectionHelpers.setStaticField(AppSelfUpdater::class.java, "operation", null)
        AppUpdateChecker.setEnabled(context, false)
        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", -1)
    }

    private fun installVersion(version: String, code: Int) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = context.packageName
            versionName = version
            versionCode = code
        })
    }

    private fun pending(version: String = "1.0.0.9", code: Long = 9) {
        prefs.edit().putString("expected_version", version).putLong("expected_version_code", code)
            .putLong("handoff_at", 1234).putString("phase", "INSTALLING").putString("version", version).commit()
    }


    @Test fun recoveryOnlyConfirmsExactInstalledVersionAndAndroidCode() {
        pending()
        installVersion("1.0.0.9", 9)
        AppSelfUpdater.recover(context)
        assertEquals(AppSelfUpdater.Phase.DONE, AppSelfUpdater.status(context).phase)
        assertFalse(prefs.contains("expected_version"))
        assertFalse(prefs.contains("expected_version_code"))
        assertFalse(prefs.contains("handoff_at"))
        assertFalse(AppSelfUpdater.isRunning(context))
    }

    @Test fun submittedInstallerIsNotSuccessAndUnknownOutcomeRetainsExpectedVersion() {
        pending()
        AppSelfUpdater.recover(context)
        assertEquals(AppSelfUpdater.Phase.FAILED, AppSelfUpdater.status(context).phase)
        assertEquals("1.0.0.9", prefs.getString("expected_version", null))
        assertEquals(9L, prefs.getLong("expected_version_code", -1))
        assertTrue(AppSelfUpdater.status(context).message.contains("could not be confirmed"))
        installVersion("1.0.0.9", 9)
        AppSelfUpdater.recover(context)
        assertEquals(AppSelfUpdater.Phase.DONE, AppSelfUpdater.status(context).phase)
    }

    @Test fun matchingNameWithWrongCodeAndUnexpectedNewerVersionNeverConfirm() {
        pending()
        installVersion("1.0.0.9", 8)
        AppSelfUpdater.recover(context)
        assertEquals(AppSelfUpdater.Phase.FAILED, AppSelfUpdater.status(context).phase)
        installVersion("1.0.0.10", 10)
        AppSelfUpdater.recover(context)
        assertEquals(AppSelfUpdater.Phase.FAILED, AppSelfUpdater.status(context).phase)
        assertTrue(prefs.contains("expected_version"))
    }

    @Test fun processDeathBeforeHandoffIsFailureNotAutomaticRetry() {
        for (phase in listOf("DISCOVERING", "DOWNLOADING", "VERIFYING", "INSTALLING")) {
            prefs.edit().putString("phase", phase).commit()
            AppSelfUpdater.recover(context)
            assertEquals(AppSelfUpdater.Phase.FAILED, AppSelfUpdater.status(context).phase)
            assertFalse(AppSelfUpdater.isRunning(context))
        }
    }


    @Test fun cancellingBeforeHandoffClosesTheDownloadAndDoesNotDependOnReleaseChecks() {
        val task = AppSelfUpdater.Operation(release())
        val connection = TrackingConnection()
        task.connection = connection
        ReflectionHelpers.setStaticField(AppSelfUpdater::class.java, "operation", task)
        AppUpdateChecker.setEnabled(context, false)
        assertNull(task.cancelled)
        AppSelfUpdater.cancel(context)
        assertNotNull(task.cancelled)
        assertTrue(connection.disconnected)
        AppSelfUpdater.execute(context, task) {}
        assertEquals(AppSelfUpdater.Phase.FAILED, AppSelfUpdater.status(context).phase)
        assertFalse(AppSelfUpdater.status(context).needsWirelessSetup)
        AppSelfUpdater.finished(context, task)
    }

    @Test fun cancellationCannotInterruptIrreversibleInstallerHandoff() {
        val committed = AppSelfUpdater.Operation(release()).apply { this.committed = true }
        val connection = TrackingConnection()
        committed.connection = connection
        ReflectionHelpers.setStaticField(AppSelfUpdater::class.java, "operation", committed)
        AppSelfUpdater.cancel(context)
        assertNull(committed.cancelled)
        assertFalse(connection.disconnected)
        AppSelfUpdater.finished(context, committed)
    }

    @Test fun explicitWirelessDebuggingOffRejectsBeforeDownloadDespiteSavedIdentityAndSettingsGrants() {
        val identity = File(context.noBackupFilesDir, "wireless-adb-identity.p12")
        val original = identity.takeIf { it.exists() }?.readBytes()
        try {
            writeSavedIdentity(identity)
            val endpoint = WirelessAdbEndpoint.parse("127.0.0.1", "37007")
            WirelessAdbAccess.remember(context, endpoint)
            shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS, Manifest.permission.WRITE_SETTINGS)
            Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
            assertFalse(AppSelfUpdater.start(context, release()))
            assertFalse(AppSelfUpdater.isRunning(context))
            assertEquals(AppSelfUpdater.Phase.FAILED, AppSelfUpdater.status(context).phase)
            assertTrue(AppSelfUpdater.status(context).needsWirelessSetup)
            assertTrue(AppSelfUpdater.status(context).message.contains("Wireless debugging is off"))
            assertTrue(AppSelfUpdater.status(context).message.contains("pair IconGen again"))
            assertFalse(prefs.contains("expected_version"))
            assertTrue(WirelessAdbAccess.hasIdentity(context))
            assertEquals(endpoint, WirelessAdbAccess.lastEndpoint(context))
        } finally {
            context.getSharedPreferences("wireless_adb_access", Context.MODE_PRIVATE).edit().clear().commit()
            if (original == null) identity.delete() else identity.writeBytes(original)
        }
    }

    @Test fun unknownDebuggingFlagDoesNotClaimEnabledAndPositiveFlagNeverAuthorizes() {
        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", -1)
        assertEquals(-1, AppSelfUpdater.wirelessDebuggingState(context))
        AppSelfUpdater.requireWirelessDebuggingNotDisabled(context)
        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
        assertThrows(IllegalStateException::class.java) { AppSelfUpdater.requireWirelessDebuggingNotDisabled(context) }
        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
        val identity = File(context.noBackupFilesDir, "wireless-adb-identity.p12")
        val original = identity.takeIf { it.exists() }?.readBytes()
        try {
            identity.delete()
            assertFalse(AppSelfUpdater.start(context, release()))
            assertTrue(AppSelfUpdater.status(context).message.contains("Pair IconGen"))
            assertTrue(AppSelfUpdater.status(context).needsWirelessSetup)
            assertFalse(AppSelfUpdater.isRunning(context))
            assertFalse(prefs.contains("expected_version"))
        } finally {
            if (original != null) identity.writeBytes(original)
        }
    }

    @Test fun aNonWirelessFailureDoesNotRetainAnEarlierPairingRecoveryAction() {
        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
        assertFalse(AppSelfUpdater.start(context, release()))
        assertTrue(AppSelfUpdater.status(context).needsWirelessSetup)
        installVersion("1.0.0.10", 10)
        assertFalse(AppSelfUpdater.start(context, release()))
        assertEquals(AppSelfUpdater.Phase.FAILED, AppSelfUpdater.status(context).phase)
        assertFalse(AppSelfUpdater.status(context).needsWirelessSetup)
    }

    @Test fun savedIdentityAndCachedPortCannotInstallWhenDaemonIsUnreachable() {
        val identity = File(context.noBackupFilesDir, "wireless-adb-identity.p12")
        val original = identity.takeIf { it.exists() }?.readBytes()
        val apk = File.createTempFile("negative-wireless-update-", ".apk", context.cacheDir).apply {
            writeBytes(ByteArray(2048))
        }
        val client = WirelessAdbClient(context)
        var committed = false
        try {
            writeSavedIdentity(identity)
            // Binding a client socket reserves the port without listening. The real kernel must
            // refuse connects; no fake ADB server, grant command, or trust acknowledgement exists.
            Socket().use { reserved ->
                reserved.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
                val endpoint = WirelessAdbEndpoint.parse("127.0.0.1", reserved.localPort.toString())
                WirelessAdbAccess.remember(context, endpoint)
                assertTrue(WirelessAdbAccess.hasIdentity(context))
                assertEquals(endpoint, WirelessAdbAccess.lastEndpoint(context))
                try {
                    client.install(endpoint, apk) { committed = true }
                    fail("A saved credential and endpoint were treated as live ADB authorization")
                } catch (_: java.io.IOException) {
                    // The endpoint exists only in preferences; actual fresh transport is refused.
                }
                assertFalse(committed)
                assertFalse(prefs.contains("expected_version"))
            }
        } finally {
            client.cancel("Test finished")
            apk.delete()
            context.getSharedPreferences("wireless_adb_access", Context.MODE_PRIVATE).edit().clear().commit()
            if (original == null) identity.delete() else identity.writeBytes(original)
        }
    }

    private fun writeSavedIdentity(file: File) {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val subject = X500Name("CN=Previously paired regression identity")
        val certificate = JcaX509CertificateConverter().getCertificate(JcaX509v3CertificateBuilder(
            subject, BigInteger.ONE, Date(0), Date(4_102_444_800_000L), subject, key.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(key.private)))
        val password = "IconGen wireless ADB".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry("adb", key.private, password, arrayOf(certificate))
        }
        file.parentFile!!.mkdirs()
        file.outputStream().use { store.store(it, password) }
    }

    private class TrackingConnection : HttpURLConnection(URL("https://github.com/")) {
        var disconnected = false
        override fun disconnect() { disconnected = true }
        override fun connect() = Unit
        override fun usingProxy() = false
    }

    @Test fun recoveryDeletesOnlyItsOwnTemporaryDownloads() {
        val own = java.io.File(context.cacheDir, "icongen-self-update-interrupted.apk").apply { writeText("partial") }
        val pack = java.io.File(context.cacheDir, "icon-pack.apk").apply { writeText("keep") }
        AppSelfUpdater.recover(context)
        assertFalse(own.exists())
        assertTrue(pack.exists())
        pack.delete()
    }

    private fun release() = AppUpdateChecker.ReleaseInfo("v1.0.0.9", "Release", "https://github.com/linuxct/nothing-icongen/releases/tag/v1.0.0.9", "2026-11-05", "1.0.0.9",
        AppUpdateChecker.ReleaseAsset("IconGen.apk", "https://github.com/linuxct/nothing-icongen/releases/download/v1.0.0.9/IconGen.apk", 2048, null))
}
