package space.linuxct.nothing.icongen

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.InetAddresses
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.AtomicFile
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.PairingConnectionCtx
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.Closeable
import java.io.DataInputStream
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.util.Date
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/** Only this device's numeric IP addresses are accepted; no DNS or remote-device ADB. */
internal data class WirelessAdbEndpoint(val address: InetAddress, val port: Int) {
    companion object {
        fun parse(host: String, port: String): WirelessAdbEndpoint {
            val numericHost = host.trim().removeSurrounding("[", "]")
            require(InetAddresses.isNumericAddress(numericHost)) {
                "Enter this device's numeric IP address, not a hostname."
            }
            val address = InetAddresses.parseNumericAddress(numericHost)
            val number = port.trim().toIntOrNull()
            require(number != null && number in 1..65535) { "Enter a port from 1 to 65535." }
            val local = address.isLoopbackAddress || NetworkInterface.getNetworkInterfaces()
                .toList().any { it.isUp && it.inetAddresses.toList().contains(address) }
            require(local && !address.isAnyLocalAddress && !address.isMulticastAddress) {
                "Only loopback or an IP currently assigned to this device is allowed."
            }
            return WirelessAdbEndpoint(address, number)
        }
    }
}

internal data class WirelessGrantStatus(
    val secureSettings: Boolean,
    val writeSettings: Boolean,
    val writeSettingsMode: Int,
) {
    val complete: Boolean get() = secureSettings && writeSettings &&
        writeSettingsMode == AppOpsManager.MODE_ALLOWED

    fun describe(): String = "WRITE_SECURE_SETTINGS: ${if (secureSettings) "granted" else "not granted"}\n" +
        "WRITE_SETTINGS: ${if (writeSettings) "allowed" else "not allowed"} " +
        "(AppOps ${if (writeSettingsMode == AppOpsManager.MODE_ALLOWED) "allow" else writeSettingsMode.toString()})"

    companion object {
        fun read(context: Context): WirelessGrantStatus {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            return WirelessGrantStatus(
                context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                    PackageManager.PERMISSION_GRANTED,
                Settings.System.canWrite(context),
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_WRITE_SETTINGS, Process.myUid(), context.packageName,
                ),
            )
        }
    }
}

/** A single short-lived operation; cancellation closes sockets even during library TLS/SPAKE2 calls. */
internal class WirelessAdbClient(private val context: Context) {
    @Volatile private var cancelled: String? = null
    @Volatile private var relay: LocalAdbRelay? = null
    @Volatile private var worker: Thread? = null

    fun cancel(reason: String) {
        cancelled = reason
        relay?.close()
        worker?.interrupt()
    }

    private fun checkCancelled() {
        cancelled?.let { throw CancellationException(it) }
    }

    fun failureMessage(error: Throwable): String = cancelled ?: when (error) {
        is UnsatisfiedLinkError -> "Wireless ADB native libraries could not load on this device. ${error.message.orEmpty()}"
        else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }

    fun pair(endpoint: WirelessAdbEndpoint, code: ByteArray) {
        require(code.size == 6 && code.all { it.toInt() in 48..57 }) {
            "Enter the six-digit pairing code."
        }
        run(endpoint, requireAdbTls = false) { port ->
            val identity = AdbIdentity.load(context)
            val pairing = PairingConnectionCtx(
                "127.0.0.1", port, code, identity.privateKey, identity.certificate, "IconGen",
            )
            try {
                pairing.start()
                checkCancelled()
            } finally {
                // Upstream close() assumes TLS and SPAKE2 initialization succeeded. Preserve the real failure.
                runCatching { pairing.close() }
            }
        }
    }

    fun grant(endpoint: WirelessAdbEndpoint): WirelessGrantStatus = run(endpoint, requireAdbTls = true) { port ->
        check(context.packageName == PACKAGE) { "Unexpected application package; grants were not executed." }
        val connection = connect(port)
        try {
            execute(connection, "pm grant $PACKAGE android.permission.WRITE_SECURE_SETTINGS")
            checkCancelled()
            execute(connection, "appops set $PACKAGE WRITE_SETTINGS allow")
            checkCancelled()
            val status = WirelessGrantStatus.read(context)
            check(status.complete) {
                "ADB commands finished, but Android did not confirm both grants.\n${status.describe()}"
            }
            WirelessAdbAccess.remember(context, endpoint)
            status
        } finally {
            // Close the relay first so upstream's connection-thread join cannot wait on network I/O.
            relay?.close()
            connection.close()
        }
    }

    /** Verify a fresh ADB authorization without restoring any Android Settings permissions. */
    fun authorize(endpoint: WirelessAdbEndpoint) = run(endpoint, requireAdbTls = true) { port ->
        check(context.packageName == PACKAGE) { "Unexpected application package; authorization was not checked." }
        val connection = connect(port)
        try {
            checkCancelled()
            WirelessAdbAccess.remember(context, endpoint)
        } finally {
            relay?.close()
            connection.close()
        }
    }

    /**
     * The uploaded APK has already been authenticated by the updater. This transport never accepts
     * a caller-supplied shell command, remote filename, or package installer argument.
     */
    fun install(endpoint: WirelessAdbEndpoint, apk: File, beforeCommit: () -> Unit) {
        require(apk.isFile && apk.length() in 1..AdbSyncTransfer.MAX_FILE_SIZE) {
            "The verified update APK is missing or too large."
        }
        var authenticated = false
        val deadline = Timer("IconGen-adb-install-deadline", true)
        deadline.schedule(object : TimerTask() {
            override fun run() = cancel("Wireless ADB installation timed out; check the installed version before retrying.")
        }, 180_000)
        try {
            run(endpoint, requireAdbTls = true) { port ->
                check(context.packageName == PACKAGE) { "Unexpected application package; update was not installed." }
                check(WirelessAdbAccess.hasIdentity(context)) {
                    "Pair this app in Wireless debugging before installing updates."
                }
                val connection = connect(port)
                authenticated = true
                val staging = AdbInstallStaging(UUID.randomUUID().toString().replace("-", ""))
                var handedOff = false
                try {
                    // A detached lease also cleans partial uploads if cancellation closes the connection.
                    execute(connection, staging.prepareCommand())
                    apk.inputStream().use {
                        push(connection, it, apk.length(), staging.apk, 0x180)
                    }
                    val script = staging.script().toByteArray(Charsets.UTF_8)
                    push(connection, ByteArrayInputStream(script), script.size.toLong(), staging.scriptPath, 0x1c0)
                    checkCancelled()
                    beforeCommit()
                    // From here any lost transport is an UNKNOWN outcome, never proof of success.
                    handedOff = true
                    execute(connection, staging.launchCommand())
                    while (true) {
                        checkCancelled()
                        val outcome = AdbInstallOutcome.parse(execute(connection, staging.pollCommand()))
                        if (outcome != null) {
                            runCatching { execute(connection, staging.cleanupCommand()) }
                            if (outcome.exitCode != 0) {
                                if (outcome.exitCode == 124 || outcome.exitCode == 137 || outcome.exitCode == 143) {
                                    throw IOException("Android installer stopped or timed out; its outcome is unknown. " +
                                        outcome.output.take(8192))
                                }
                                throw WirelessAdbInstallRejectedException(
                                    "Android rejected the update (exit ${outcome.exitCode}). ${outcome.output.take(8192)}",
                                )
                            }
                            break
                        }
                        Thread.sleep(500)
                    }
                } finally {
                    // Never remove a committed APK while the detached package manager still uses it.
                    if (!handedOff && cancelled == null) {
                        runCatching { execute(connection, staging.cleanupCommand()) }
                    }
                    relay?.close()
                    runCatching { connection.close() }
                }
            }
        } catch (error: Exception) {
            if (!authenticated && error !is CancellationException && cancelled == null) {
                throw WirelessAdbUnavailableException(error)
            }
            throw error
        } finally {
            deadline.cancel()
        }
    }

    private fun connect(port: Int): AdbConnection {
        val identity = AdbIdentity.load(context)
        val connection = AdbConnection.Builder("127.0.0.1", port)
            .setApi(Build.VERSION.SDK_INT)
            .setPrivateKey(identity.privateKey)
            .setCertificate(identity.certificate)
            .setDeviceName("IconGen")
            .build()
        try {
            check(connection.connect(15, TimeUnit.SECONDS, true)) {
                "ADB connection timed out. Check the main Wireless debugging port, not the pairing port."
            }
            checkCancelled()
            return connection
        } catch (error: Throwable) {
            relay?.close()
            runCatching { connection.close() }
            throw error
        }
    }

    private fun push(connection: AdbConnection, input: InputStream, size: Long, path: String, mode: Int) {
        connection.open("sync:").use { stream ->
            // libadb 3.1.1 does not await READY between the chunks of an oversized write().
            // Keep every call within the negotiated payload to preserve ADB flow control.
            val limit = connection.maxData.also { require(it > 0) }
            val output = object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()))
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    var position = offset
                    var remaining = length
                    while (remaining > 0) {
                        checkCancelled()
                        val count = minOf(remaining, limit)
                        stream.write(bytes, position, count)
                        stream.flush()
                        position += count
                        remaining -= count
                    }
                }
            }
            AdbSyncTransfer.send(input, size, path, mode, stream.openInputStream(), output, ::checkCancelled)
        }
    }

    private fun execute(connection: AdbConnection, command: String): String {
        checkCancelled()
        connection.open("shell:$command; result=\$?; printf '\\nICONGEN_EXIT=%s\\n' \"\$result\"").use { stream ->
            val output = StringBuilder()
            val bytes = ByteArray(1024)
            val input = stream.openInputStream()
            while (true) {
                checkCancelled()
                val count = try {
                    input.read(bytes)
                } catch (error: IOException) {
                    // This library reports normal stream closure as IOException, not always EOF.
                    if (stream.isClosed) break else throw error
                }
                if (count < 0) break
                require(output.length + count <= 16_384) { "Unexpectedly large ADB command response." }
                output.append(String(bytes, 0, count, Charsets.UTF_8))
                // Do not await EOF: upstream can leave readers waiting after a peer's pending close.
                if (EXIT_MARKER.containsMatchIn(output)) break
            }
            val text = output.toString()
            val result = EXIT_MARKER.find(text)
                ?: throw IOException("ADB command ended without an exit status. ${text.take(1000)}")
            val response = text.substring(0, result.range.first).trim()
            if (result.groupValues[1] != "0") {
                throw IOException("ADB command was rejected (exit ${result.groupValues[1]}). " + response.take(1000))
            }
            return response
        }
    }

    private fun <T> run(endpoint: WirelessAdbEndpoint, requireAdbTls: Boolean, action: (Int) -> T): T {
        worker = Thread.currentThread()
        checkCancelled()
        // Recheck immediately before opening sockets in case a network changed after form validation.
        val current = WirelessAdbEndpoint.parse(endpoint.address.hostAddress!!, endpoint.port.toString())
        val transport = LocalAdbRelay(current, requireAdbTls)
        relay = transport
        try {
            checkCancelled()
            val result = action(transport.port)
            checkCancelled()
            return result
        } catch (error: Exception) {
            checkCancelled()
            throw transport.failure ?: error
        } finally {
            transport.close()
            relay = null
            worker = null
        }
    }

    companion object {
        private const val PACKAGE = "space.linuxct.nothing.icongen"
        private val EXIT_MARKER = Regex("(?:^|\\n)ICONGEN_EXIT=(\\d+)\\r?\\n")
    }
}

/** Saved credentials are only a hint; every install must authenticate to the currently running adbd. */
internal class WirelessAdbUnavailableException(cause: Exception) : IOException(
    "Wireless ADB is unavailable or IconGen is no longer authorized. " +
        "Enable Wireless debugging in Developer options and keep this phone on Wi-Fi. " +
        "If its port changed, reopen Wireless ADB setup to discover the current port. " +
        "If IconGen was forgotten or authorization was revoked, pair it again in Wireless ADB setup. " +
        "No debugging setting or settings grant was changed. ${cause.message.orEmpty()}",
    cause,
)

/** A completed installer response, distinct from an unknown outcome after a transport failure. */
internal class WirelessAdbInstallRejectedException(message: String) : IOException(message)

/** ADB sync v1 framing. Only exact, bounded responses can acknowledge a real streamed upload. */
internal object AdbSyncTransfer {
    const val MAX_FILE_SIZE = 512L * 1024 * 1024
    private const val MAX_DATA = 64 * 1024
    private const val MAX_FAILURE = 4096
    private val STAGING_PATH = Regex("/data/local/tmp/icongen-update-[a-f0-9]{32}/(?:update\\.apk|install\\.sh)")

    fun send(
        source: InputStream,
        size: Long,
        path: String,
        mode: Int,
        response: InputStream,
        destination: OutputStream,
        checkCancelled: () -> Unit,
    ) {
        require(size in 1..MAX_FILE_SIZE) { "Invalid ADB upload size." }
        require(STAGING_PATH.matches(path)) { "Invalid ADB staging path." }
        require(mode == 0x180 || mode == 0x1c0) { "Invalid ADB staging permissions." }
        val request = "$path,${0x8000 or mode}".toByteArray(Charsets.UTF_8)
        require(request.size <= 1024) { "ADB staging path is too long." }
        val header = ByteArray(8)
        fun frame(id: String, value: Int) {
            checkCancelled()
            for (index in 0..3) {
                header[index] = id[index].code.toByte()
                header[index + 4] = (value ushr (8 * index)).toByte()
            }
            destination.write(header)
        }
        frame("SEND", request.size)
        destination.write(request)
        val buffer = ByteArray(MAX_DATA)
        var remaining = size
        while (remaining > 0) {
            checkCancelled()
            val count = source.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (count < 0) throw EOFException("The verified APK was truncated during ADB upload.")
            if (count == 0) throw IOException("ADB upload source made no progress.")
            frame("DATA", count)
            destination.write(buffer, 0, count)
            remaining -= count
        }
        checkCancelled()
        if (source.read() != -1) throw IOException("The verified APK changed size during ADB upload.")
        frame("DONE", (System.currentTimeMillis() / 1000).toInt())
        destination.flush()
        readExactly(response, header, checkCancelled)
        val id = String(header, 0, 4, Charsets.US_ASCII)
        val length = (header[4].toInt() and 255) or
            ((header[5].toInt() and 255) shl 8) or
            ((header[6].toInt() and 255) shl 16) or
            ((header[7].toInt() and 255) shl 24)
        when (id) {
            "OKAY" -> if (length != 0) throw IOException("Malformed ADB sync acknowledgement.")
            "FAIL" -> {
                if (length !in 0..MAX_FAILURE) throw IOException("Invalid ADB sync failure length.")
                val message = ByteArray(length)
                readExactly(response, message, checkCancelled)
                throw IOException("ADB upload rejected: ${String(message, Charsets.UTF_8)}")
            }
            else -> throw IOException("Unexpected ADB sync response: $id")
        }
        checkCancelled()
    }

    private fun readExactly(input: InputStream, bytes: ByteArray, checkCancelled: () -> Unit) {
        var offset = 0
        while (offset < bytes.size) {
            checkCancelled()
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) throw EOFException("Truncated ADB sync response.")
            if (count == 0) throw IOException("ADB sync response made no progress.")
            offset += count
        }
    }
}

/** All remote names and shell arguments are generated here, never from release metadata. */
internal class AdbInstallStaging(token: String) {
    init {
        require(TOKEN.matches(token)) { "Invalid ADB install identifier." }
    }

    private val directory = "/data/local/tmp/icongen-update-$token"
    val apk = "$directory/update.apk"
    val scriptPath = "$directory/install.sh"
    private val log = "$directory/output"
    private val status = "$directory/result"

    fun prepareCommand(): String =
        "if [ ! -x /system/bin/nohup ] || [ ! -x /system/bin/setsid ] || " +
            "[ ! -x /system/bin/timeout ]; then " +
            "printf 'This Android build lacks the detached installer tools (nohup, setsid, timeout).\\n'; false; " +
            "else umask 077 && mkdir -m 700 $directory && " +
            "{ /system/bin/nohup /system/bin/setsid /system/bin/sh -c " +
            "'sleep 600; rm -rf $directory' </dev/null >/dev/null 2>&1 & }; fi"

    fun script(): String = """
        #!/system/bin/sh
        umask 077
        /system/bin/timeout 120 /system/bin/pm install -r --bypass-low-target-sdk-block $apk
        result=${'$'}?
        rm -f $apk $scriptPath
        printf 'ICONGEN_PM_EXIT=%s\n' "${'$'}result" >$status.tmp
        mv $status.tmp $status
        exit "${'$'}result"
    """.trimIndent() + "\n"

    fun launchCommand(): String =
        "{ /system/bin/nohup /system/bin/setsid /system/bin/sh $scriptPath " +
            "</dev/null >$log 2>&1 & }"

    fun pollCommand(): String =
        "if [ -f $status ]; then cat $status; dd if=$log bs=8192 count=1 2>/dev/null; " +
            "else printf 'ICONGEN_PM_WAIT\\n'; fi"

    fun cleanupCommand(): String = "rm -rf $directory"

    companion object {
        private val TOKEN = Regex("[a-f0-9]{32}")
    }
}

internal data class AdbInstallOutcome(val exitCode: Int, val output: String) {
    companion object {
        private val RESULT = Regex("ICONGEN_PM_EXIT=([0-9]{1,3})")

        fun parse(response: String): AdbInstallOutcome? {
            if (response == "ICONGEN_PM_WAIT") return null
            val first = response.substringBefore('\n').removeSuffix("\r")
            val code = RESULT.matchEntire(first)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 0..255 }
                ?: throw IOException("Malformed detached Android installer result.")
            return AdbInstallOutcome(code, response.substringAfter('\n', "").trim())
        }
    }
}

/**
 * libadb 3.1.1 creates its own sockets without connect/read timeouts or a cancel handle during pairing.
 * A one-use loopback relay owns both sockets, bounding I/O and allowing cancellation without reflection.
 * For ADB connections it also requires the daemon's first packet to be STLS, rejecting legacy TCP ADB.
 * Pairing TLS, TLS exporter binding, and SPAKE2 are implemented entirely by libadb/Conscrypt.
 */
private class LocalAdbRelay(endpoint: WirelessAdbEndpoint, private val requireAdbTls: Boolean) : Closeable {
    private val lock = Any()
    private var closed = false
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private var client: Socket? = null
    private var remote: Socket? = null
    @Volatile var failure: IOException? = null
        private set
    val port: Int get() = server.localPort

    init {
        Thread({
            try {
                server.soTimeout = 15_000
                val accepted = server.accept()
                synchronized(lock) {
                    if (closed) { accepted.close(); return@Thread }
                    client = accepted
                }
                server.close()
                val destination = Socket()
                synchronized(lock) {
                    if (closed) { destination.close(); return@Thread }
                    remote = destination
                }
                destination.connect(InetSocketAddress(endpoint.address, endpoint.port), 10_000)
                destination.soTimeout = 20_000
                accepted.soTimeout = 20_000
                destination.tcpNoDelay = true
                accepted.tcpNoDelay = true
                Thread({ pump(accepted, destination, false) }, "IconGen-adb-send").start()
                pump(destination, accepted, requireAdbTls)
            } catch (error: IOException) {
                fail(error)
            }
        }, "IconGen-adb-relay").start()
    }

    private fun pump(from: Socket, to: Socket, checkTls: Boolean) {
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            if (checkTls) {
                val header = ByteArray(24)
                DataInputStream(input).readFully(header)
                if (header[0] != 83.toByte() || header[1] != 84.toByte() ||
                    header[2] != 76.toByte() || header[3] != 83.toByte()) {
                    throw IOException("Not a Wireless debugging TLS port")
                }
                output.write(header)
                output.flush()
            }
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (error: IOException) {
            fail(error)
        } finally {
            close()
        }
    }

    private fun fail(error: IOException) {
        synchronized(lock) {
            if (!closed && failure == null) failure = error
        }
        close()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { server.close() }
            runCatching { client?.close() }
            runCatching { remote?.close() }
        }
    }
}

private data class AdbIdentity(val privateKey: PrivateKey, val certificate: Certificate) {
    companion object {
        private val lock = Any()

        fun load(context: Context): AdbIdentity = synchronized(lock) {
            // The app-private, no-backup directory provides confidentiality; this password is only PKCS12 framing.
            val password = "IconGen wireless ADB".toCharArray()
            val file = AtomicFile(File(context.noBackupFilesDir, "wireless-adb-identity.p12"))
            val store = KeyStore.getInstance("PKCS12")
            try {
                if (file.baseFile.exists()) {
                    file.openRead().use { store.load(it, password) }
                } else {
                    store.load(null, password)
                    val key = KeyPairGenerator.getInstance("RSA").apply {
                        initialize(2048, SecureRandom())
                    }.generateKeyPair()
                    val now = System.currentTimeMillis()
                    val subject = X500Name("CN=IconGen Wireless ADB")
                    val builder = JcaX509v3CertificateBuilder(
                        subject, BigInteger(128, SecureRandom()).setBit(127),
                        Date(now - 86_400_000L), Date(now + 10L * 365 * 86_400_000L), subject, key.public,
                    )
                    val signer = JcaContentSignerBuilder("SHA256withRSA").build(key.private)
                    val certificate = JcaX509CertificateConverter()
                        .getCertificate(builder.build(signer))
                    store.setKeyEntry("adb", key.private, password, arrayOf(certificate))
                    val output = file.startWrite()
                    try {
                        store.store(output, password)
                        file.finishWrite(output)
                    } catch (error: Throwable) {
                        file.failWrite(output)
                        throw error
                    }
                }
                AdbIdentity(store.getKey("adb", password) as PrivateKey, store.getCertificate("adb"))
            } finally {
                password.fill('\u0000')
            }
        }
    }
}
