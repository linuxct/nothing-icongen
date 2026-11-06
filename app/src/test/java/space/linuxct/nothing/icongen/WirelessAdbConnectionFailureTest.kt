package space.linuxct.nothing.icongen

import android.app.Application
import android.content.Context
import android.provider.Settings
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.math.BigInteger
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
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
import org.robolectric.annotation.Config

/** Real loopback connection/TLS failures, not a mocked successful ADB conversation. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WirelessAdbConnectionFailureTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val identity: File get() = File(context.noBackupFilesDir, "wireless-adb-identity.p12")
    private lateinit var store: KeyStore
    private lateinit var apk: File
    private var previousIdentity: ByteArray? = null
    private val password = "IconGen wireless ADB".toCharArray()

    @Before fun setUp() {
        previousIdentity = identity.takeIf { it.isFile }?.readBytes()
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val subject = X500Name("CN=Transport regression identity")
        val certificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(subject, BigInteger.ONE, Date(0),
                Date(System.currentTimeMillis() + 86_400_000), subject, key.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(key.private)),
        )
        store = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry("adb", key.private, password, arrayOf(certificate))
        }
        identity.parentFile!!.mkdirs()
        identity.outputStream().use { store.store(it, password) }
        apk = File.createTempFile("wireless-negative-", ".apk", context.cacheDir).apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        context.getSharedPreferences("wireless_adb_access", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        previousIdentity?.let { identity.writeBytes(it) } ?: identity.delete()
        apk.delete()
        password.fill('\u0000')
        context.getSharedPreferences("wireless_adb_access", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun savedIdentityAndEndpointCannotInstallWhenWirelessDebuggingIsOff() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val endpoint = WirelessAdbEndpoint.parse("127.0.0.1", port.toString())
        WirelessAdbAccess.remember(context, endpoint)
        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
        val oldGrant = WirelessGrantStatus.read(context)
        var committed = false
        val failure = assertThrows(WirelessAdbUnavailableException::class.java) {
            WirelessAdbClient(context).install(WirelessAdbAccess.lastEndpoint(context)!!, apk) {
                committed = true
            }
        }
        assertFalse(committed)
        assertTrue(WirelessAdbAccess.hasIdentity(context))
        assertTrue(failure.message!!.contains("Enable Wireless debugging"))
        assertTrue(failure.message!!.contains("port changed"))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0))
        assertEquals(oldGrant, WirelessGrantStatus.read(context))
    }

    @Test fun revokedClientCertificateFailsRealTlsBeforeAnyUploadOrInstallerCommit() {
        val rejected = AtomicBoolean(false)
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, password)
        }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> =
                arrayOf(store.getCertificate("adb") as X509Certificate)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                rejected.set(true)
                throw CertificateException("This previously paired identity has been revoked")
            }
        }
        val tls = SSLContext.getInstance("TLSv1.3").apply { init(keys.keyManagers, arrayOf(trust), null) }
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10_000
            val daemon = CompletableFuture.runAsync {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = DataInputStream(socket.getInputStream())
                    assertEquals("CNXN", readPacket(input))
                    val stls = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(0x534c5453).putInt(0x01000000).putInt(0).putInt(0).putInt(0)
                        .putInt(0x534c5453.inv()).array()
                    socket.getOutputStream().apply { write(stls); flush() }
                    assertEquals("STLS", readPacket(input))
                    (tls.socketFactory.createSocket(socket, "127.0.0.1", server.localPort, false) as SSLSocket).use { secure ->
                        secure.useClientMode = false
                        secure.needClientAuth = true
                        secure.soTimeout = 10_000
                        assertThrows(SSLException::class.java) { secure.startHandshake() }
                    }
                }
            }
            val endpoint = WirelessAdbEndpoint.parse("127.0.0.1", server.localPort.toString())
            WirelessAdbAccess.remember(context, endpoint)
            var committed = false
            val oldGrant = WirelessGrantStatus.read(context)
            val failure = assertThrows(WirelessAdbUnavailableException::class.java) {
                WirelessAdbClient(context).install(endpoint, apk) { committed = true }
            }
            daemon.get(15, TimeUnit.SECONDS)
            assertTrue("The server must actually reject the client's certificate", rejected.get())
            assertFalse(committed)
            assertTrue(failure.message!!.contains("authorization was revoked"))
            assertTrue(failure.message!!.contains("pair it again"))
            assertEquals(oldGrant, WirelessGrantStatus.read(context))
            assertTrue(WirelessAdbAccess.hasIdentity(context))
        }
    }

    private fun readPacket(input: DataInputStream): String {
        val header = ByteArray(24).also { input.readFully(it) }
        val length = (header[12].toInt() and 255) or ((header[13].toInt() and 255) shl 8) or
            ((header[14].toInt() and 255) shl 16) or ((header[15].toInt() and 255) shl 24)
        assertTrue(length in 0..65536)
        input.readFully(ByteArray(length))
        return String(header, 0, 4, Charsets.US_ASCII)
    }
}
