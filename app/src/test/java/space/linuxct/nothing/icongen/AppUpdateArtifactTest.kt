package space.linuxct.nothing.icongen

import android.app.Application
import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AppUpdateArtifactTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun release() = AppUpdateChecker.ReleaseInfo("v1.0.0.9", "Release", "https://github.com/linuxct/nothing-icongen/releases/tag/v1.0.0.9", "2026-11-05", "1.0.0.9",
        AppUpdateChecker.ReleaseAsset("IconGen.apk", "https://github.com/linuxct/nothing-icongen/releases/download/v1.0.0.9/IconGen.apk", 2048, null))

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Unsafe artifact was accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun onlyNewerOfficialStableReleaseAssetsAreAccepted() {
        val release = release()
        AppUpdateArtifact.validateRelease(release, "1.0.0.8")
        rejects { AppUpdateArtifact.validateRelease(release, "1.0.0.9") }
        rejects { AppUpdateArtifact.validateRelease(release, "1.0.0.10") }
        rejects { AppUpdateArtifact.validateRelease(release.copy(version = "1.0.0.9-rc.1", tagName = "v1.0.0.9-rc.1"), "1.0.0.8") }
        rejects { AppUpdateArtifact.validateRelease(release.copy(url = "https://evil.example/release"), "1.0.0.8") }
        rejects { AppUpdateArtifact.validateRelease(release.copy(apk = release.apk.copy(url = "https://github.com/attacker/repo/releases/download/v1.0.0.9/IconGen.apk")), "1.0.0.8") }
        rejects { AppUpdateArtifact.validateRelease(release.copy(apk = release.apk.copy(url = release.apk.url + "?redirect=evil")), "1.0.0.8") }
        rejects { AppUpdateArtifact.validateRelease(release.copy(apk = release.apk.copy(url = release.apk.url.replace("v1.0.0.9/", "v1.0.0.8/"))), "1.0.0.8") }
        rejects { AppUpdateArtifact.validateRelease(release.copy(apk = release.apk.copy(size = AppUpdateArtifact.MAX_BYTES + 1)), "1.0.0.8") }
        rejects { AppUpdateArtifact.validateRelease(release.copy(apk = release.apk.copy(size = 1)), "1.0.0.8") }
        rejects { AppUpdateArtifact.validateRelease(release.copy(apk = release.apk.copy(sha256 = "not-a-digest")), "1.0.0.8") }
    }

    @Test fun redirectsCannotDowngradeHttpsOrLeaveGithubAssetHosts() {
        assertTrue(AppUpdateArtifact.allowedDownloadUrl(URL("https://release-assets.githubusercontent.com/asset?signature=123")))
        assertTrue(AppUpdateArtifact.allowedDownloadUrl(URL("https://objects.githubusercontent.com/asset")))
        for (url in listOf("http://github.com/asset", "https://github.com.evil.example/asset", "https://evil.example/asset", "https://user@github.com/asset", "https://github.com:444/asset", "https://127.0.0.1/asset")) {
            assertFalse(url, AppUpdateArtifact.allowedDownloadUrl(URL(url)))
        }
    }

    @Test fun streamingChecksExactLengthDigestAndDeletesRejectedDownloads() {
        val bytes = ByteArray(2048) { it.toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = temporary.newFile()
        AppUpdateArtifact.copyVerified(ByteArrayInputStream(bytes), file, 2048, digest) { }
        assertArrayEquals(bytes, file.readBytes())
        rejects { AppUpdateArtifact.copyVerified(ByteArrayInputStream(bytes), file, 2048, "0".repeat(64)) { } }
        assertFalse(file.exists())
        rejects { AppUpdateArtifact.copyVerified(ByteArrayInputStream(bytes), file, 2049, null) { } }
        assertFalse(file.exists())
        rejects { AppUpdateArtifact.copyVerified(ByteArrayInputStream(bytes), file, 2047, null) { } }
        assertFalse(file.exists())
    }

    @Test fun cancellationDuringStreamingDeletesThePartialApk() {
        val file = temporary.newFile()
        var checks = 0
        try {
            AppUpdateArtifact.copyVerified(ByteArrayInputStream(ByteArray(128 * 1024)), file, 128 * 1024L, null) {
                if (++checks == 2) throw CancellationException("cancelled")
            }
            fail("Cancellation was ignored")
        } catch (_: CancellationException) { }
        assertFalse(file.exists())
    }

    @Test fun packageVersionCodeAndEveryCurrentSignerMustMatch() {
        fun validate(pkg: String = "space.linuxct.nothing.icongen", version: String = "1.0.0.9", code: Long = 9,
                     installedSigners: Set<String> = setOf("official"), signers: Set<String> = setOf("official")) =
            AppUpdateArtifact.validatePackage(release(), "space.linuxct.nothing.icongen", "1.0.0.8", 8,
                installedSigners, pkg, version, code, signers)
        validate()
        rejects { validate(pkg = "space.linuxct.nothing.iconpack") }
        rejects { validate(version = "1.0.0.10") }
        rejects { validate(version = "1.0.0.8") }
        rejects { validate(code = 7) }
        rejects { validate(signers = setOf("attacker")) }
        rejects { validate(signers = emptySet()) }
        rejects { validate(installedSigners = emptySet()) }
        rejects { validate(installedSigners = setOf("official", "second")) }
        rejects { validate(signers = setOf("official", "attacker")) }
    }

    @Test fun realApkVerifierAcceptsValidSignatureAndRejectsTamperedAndUnsignedApks() {
        val unsigned = temporary.newFile("unsigned.apk")
        ZipOutputStream(unsigned.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(binaryManifest())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("assets/payload"))
            zip.write(ByteArray(4096) { 0x42 })
            zip.closeEntry()
        }
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val subject = X500Name("CN=IconGen regression fixture")
        val certificate = JcaX509CertificateConverter().getCertificate(JcaX509v3CertificateBuilder(
            subject, BigInteger.ONE, Date(0), Date(4_102_444_800_000L), subject, key.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(key.private)))
        val signed = temporary.newFile("signed.apk")
        ApkSigner.Builder(listOf(ApkSigner.SignerConfig.Builder("fixture", key.private, listOf(certificate)).build()))
            .setInputApk(unsigned).setOutputApk(signed).setMinSdkVersion(22)
            .setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(false).setV4SigningEnabled(false)
            .build().sign()
        val expected = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        assertEquals(setOf(expected), AppUpdateArtifact.verifiedCertificates(signed))
        // Change a byte in the first ZIP member without touching the signing block or central directory.
        val tampered = temporary.newFile("tampered.apk")
        val bytes = signed.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val firstData = 30 + (header.getShort(26).toInt() and 0xffff) + (header.getShort(28).toInt() and 0xffff)
        bytes[firstData + 12] = (bytes[firstData + 12].toInt() xor 1).toByte()
        tampered.writeBytes(bytes)
        assertVerificationRejected(tampered)
        assertVerificationRejected(unsigned)
    }

    private fun assertVerificationRejected(file: File) {
        try { AppUpdateArtifact.verifiedCertificates(file); fail("Unverified APK accepted") } catch (_: Exception) { }
    }

    /** Small real binary XML manifest; generated in memory, with no SDK executable or APK dependency. */
    private fun binaryManifest(): ByteArray {
        val strings = listOf("manifest", "package", "space.linuxct.nothing.icongen", "http://schemas.android.com/apk/res/android",
            "android", "uses-sdk", "minSdkVersion", "targetSdkVersion", "versionCode", "versionName", "1.0.0.9")
        fun ints(vararg values: Int) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putInt(it) } }.array()
        fun chunk(type: Int, header: Int, body: ByteArray) = ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(type.toShort()).putShort(header.toShort()).putInt(8 + body.size).put(body).array()
        val data = ByteArrayOutputStream()
        val offsets = strings.map { text ->
            val offset = data.size()
            val bytes = text.toByteArray(Charsets.UTF_8)
            data.write(text.length); data.write(bytes.size); data.write(bytes); data.write(0)
            offset
        }
        while (data.size() % 4 != 0) data.write(0)
        val pool = chunk(1, 28, ints(strings.size, 0, 0x100, 28 + strings.size * 4, 0) + ints(*offsets.toIntArray()) + data.toByteArray())
        val resources = IntArray(strings.size).apply {
            this[6] = 0x0101020c; this[7] = 0x01010270; this[8] = 0x0101021b; this[9] = 0x0101021c
        }
        val map = chunk(0x180, 8, ints(*resources))
        fun namespace(type: Int) = chunk(type, 16, ints(1, -1, 4, 3))
        fun attribute(namespace: Int, name: Int, value: Int, type: Int): ByteArray =
            ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(namespace).putInt(name)
                .putInt(if (type == 3) value else -1).putShort(8).put(0).put(type.toByte()).putInt(value).array()
        fun start(name: Int, attributes: List<ByteArray>): ByteArray {
            val extension = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(-1).putInt(name)
                .putShort(20).putShort(20).putShort(attributes.size.toShort()).putShort(0).putShort(0).putShort(0).array()
            return chunk(0x102, 16, ints(1, -1) + extension + attributes.fold(byteArrayOf()) { acc, bytes -> acc + bytes })
        }
        fun end(name: Int) = chunk(0x103, 16, ints(1, -1, -1, name))
        val body = pool + map + namespace(0x100) + start(0, listOf(attribute(-1, 1, 2, 3), attribute(3, 8, 9, 0x10), attribute(3, 9, 10, 3))) +
            start(5, listOf(attribute(3, 6, 22, 0x10), attribute(3, 7, 22, 0x10))) + end(5) + end(0) + namespace(0x101)
        return chunk(3, 8, body)
    }
}
