package space.linuxct.nothing.icongen

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkVerifier
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/** No downloaded bytes reach ADB until both cryptographic and Android package checks pass. */
internal object AppUpdateArtifact {
    const val MIN_BYTES = 1_024L
    const val MAX_BYTES = 200L * 1024 * 1024
    private const val DOWNLOAD_PREFIX = "https://github.com/linuxct/nothing-icongen/releases/download/"
    private const val RELEASE_PREFIX = "https://github.com/linuxct/nothing-icongen/releases/tag/"

    fun validateRelease(release: AppUpdateChecker.ReleaseInfo, installedVersion: String) {
        val version = UpdateVersion.parse(release.version) ?: error("Invalid release version.")
        require(!version.text.substringBefore('+').contains('-')) { "Only stable releases can be installed." }
        require(UpdateVersion.fromTag(release.tagName)?.compareTo(version) == 0) { "Release tag and version do not match." }
        require(version > (UpdateVersion.parse(installedVersion) ?: error("Cannot read the installed version."))) {
            "This release is not newer than the installed app."
        }
        val encodedTag = URI(null, null, "/${release.tagName}", null).rawPath.removePrefix("/")
        require(release.url == RELEASE_PREFIX + encodedTag) { "The release is not from the official repository." }
        val asset = release.apk
        val uri = URI(asset.url)
        val encodedName = URI(null, null, "/${asset.name}", null).rawPath.removePrefix("/")
        require(asset.name.endsWith(".apk", true) && asset.name.none { it == '/' || it == '\\' } &&
            asset.url == DOWNLOAD_PREFIX + encodedTag + "/" + encodedName &&
            uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null &&
            uri.port == -1 && uri.query == null && uri.fragment == null &&
            uri.rawPath.substringAfterLast('/') == encodedName) {
            "The APK is not an official asset of this release."
        }
        require(asset.size in MIN_BYTES..MAX_BYTES) { "The release APK size is outside the safe download limit." }
        require(asset.sha256 == null || Regex("[0-9a-f]{64}").matches(asset.sha256)) { "Invalid release SHA-256 digest." }
    }

    /** GitHub's signed asset redirects may use these HTTPS endpoints, never an arbitrary host. */
    internal fun allowedDownloadUrl(url: URL): Boolean =
        url.protocol == "https" && url.userInfo == null && (url.port == -1 || url.port == 443) &&
            url.ref == null && url.host in setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    fun download(
        release: AppUpdateChecker.ReleaseInfo,
        file: File,
        checkActive: () -> Unit,
        connectionChanged: (HttpURLConnection?) -> Unit,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        var url = URL(release.apk.url)
        try {
            for (redirect in 0..5) {
                checkActive()
                require(allowedDownloadUrl(url)) { "GitHub redirected the APK to an untrusted endpoint." }
                val connection = url.openConnection() as HttpURLConnection
                connectionChanged(connection)
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("User-Agent", "IconGen-Android")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    when (val code = connection.responseCode) {
                        301, 302, 303, 307, 308 -> {
                            require(redirect < 5) { "Too many APK download redirects." }
                            val location = connection.getHeaderField("Location") ?: error("Missing APK redirect location.")
                            url = URL(url, location)
                        }
                        HttpURLConnection.HTTP_OK -> {
                            val length = connection.contentLengthLong
                            require(length == -1L || length == release.apk.size) { "The APK download size differs from GitHub metadata." }
                            val encoding = connection.contentEncoding
                            require(encoding == null || encoding.equals("identity", true)) { "Unexpected compressed APK response." }
                            connection.inputStream.use { input ->
                                copyVerified(input, file, release.apk.size, release.apk.sha256, checkActive, onProgress)
                            }
                            return
                        }
                        else -> error("APK download failed: HTTP $code. Try checking for updates again later.")
                    }
                } finally {
                    connection.disconnect()
                    connectionChanged(null)
                }
            }
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        error("APK download did not complete.")
    }

    internal fun copyVerified(input: InputStream, file: File, expectedSize: Long, sha256: String?,
                              checkActive: () -> Unit = { }, onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> }) {
        require(expectedSize in MIN_BYTES..MAX_BYTES) { "Unsafe APK size." }
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        try {
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkActive()
                    val size = input.read(buffer)
                    if (size < 0) break
                    count += size
                    require(count <= expectedSize && count <= MAX_BYTES) { "The APK exceeds the advertised download size." }
                    digest.update(buffer, 0, size)
                    output.write(buffer, 0, size)
                    onProgress(count, expectedSize)
                }
                checkActive()
                require(count == expectedSize) { "The APK download was incomplete." }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(sha256 == null || actual == sha256) { "The APK SHA-256 digest does not match GitHub metadata." }
            }
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    internal fun verifiedCertificates(file: File): Set<String> {
        val result = ApkVerifier.Builder(file)
            .setMinCheckedPlatformVersion(Build.VERSION.SDK_INT)
            .setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT)
            .build().verify()
        require(result.isVerified && result.signerCertificates.isNotEmpty()) { "The downloaded APK has an invalid or missing signature." }
        return result.signerCertificates.map { fingerprint(it.encoded) }.toSet()
    }

    data class VerifiedApk(val versionCode: Long, val signers: Set<String>)

    fun verify(context: Context, release: AppUpdateChecker.ReleaseInfo, file: File): VerifiedApk {
        val certificates = verifiedCertificates(file)
        val pm = context.packageManager
        val installed = pm.getPackageInfo(context.packageName, signingFlags())
        val archive = pm.getPackageArchiveInfo(file.absolutePath, signingFlags())
            ?: error("Android could not read the downloaded APK.")
        validatePackage(release, context.packageName, installed.versionName.orEmpty(), versionCode(installed),
            signingCertificates(installed), archive.packageName, archive.versionName.orEmpty(), versionCode(archive), certificates)
        return VerifiedApk(versionCode(archive), certificates)
    }

    /** A package may have changed while downloading or reconnecting; recheck without rehashing the APK. */
    fun recheckInstalled(context: Context, release: AppUpdateChecker.ReleaseInfo, apk: VerifiedApk) {
        val installed = context.packageManager.getPackageInfo(context.packageName, signingFlags())
        validateRelease(release, installed.versionName.orEmpty())
        require(apk.versionCode >= versionCode(installed)) { "The APK would downgrade the Android version code." }
        require(apk.signers == signingCertificates(installed)) { "The installed app's signing certificate changed before installation." }
    }

    internal fun validatePackage(
        release: AppUpdateChecker.ReleaseInfo,
        installedPackage: String,
        installedVersion: String,
        installedCode: Long,
        installedSigners: Set<String>,
        archivePackage: String,
        archiveVersion: String,
        archiveCode: Long,
        verifiedSigners: Set<String>,
    ) {
        validateRelease(release, installedVersion)
        require(installedPackage == "space.linuxct.nothing.icongen" && archivePackage == installedPackage) {
            "The APK belongs to a different application."
        }
        require(installedSigners.isNotEmpty() && verifiedSigners == installedSigners) {
            "The APK signing certificate does not match this installed app. Install the matching official build; debug or differently signed builds cannot self-update."
        }
        val actual = UpdateVersion.parse(archiveVersion) ?: error("The APK has an invalid version name.")
        require(actual.compareTo(UpdateVersion.parse(release.version)!!) == 0) { "The APK version does not match the release." }
        require(actual > UpdateVersion.parse(installedVersion)!!) { "The APK is not newer than this app." }
        require(archiveCode >= installedCode) { "The APK would downgrade the Android version code." }
    }

    @Suppress("DEPRECATION")
    private fun signingCertificates(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { fingerprint(it.toByteArray()) }?.toSet().orEmpty()

    @Suppress("DEPRECATION")
    private fun signingFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun fingerprint(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
