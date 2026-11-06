package space.linuxct.nothing.icongen

import android.app.Application
import android.content.Context
import android.net.InetAddresses
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [WirelessAdbAccessTest.NumericInetAddresses::class])
class WirelessAdbAccessTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    // Robolectric's default shadow resolves DNS, unlike Android's numeric-only implementation.
    @Implements(InetAddresses::class)
    class NumericInetAddresses {
        companion object {
            @JvmStatic @Implementation
            fun isNumericAddress(address: String): Boolean =
                com.google.common.net.InetAddresses.isInetAddress(address)

            @JvmStatic @Implementation
            fun parseNumericAddress(address: String): InetAddress =
                com.google.common.net.InetAddresses.forString(address)
        }
    }

    @Test fun onlyNumericCurrentlyLocalAddressesAndValidPortsAreAccepted() {
        assertTrue(WirelessAdbEndpoint.parse("127.0.0.1", "37001").address.isLoopbackAddress)
        assertTrue(WirelessAdbEndpoint.parse("[::1]", "37002").address.isLoopbackAddress)
        for (host in listOf("localhost", "example.com", "0.0.0.0", "::", "224.0.0.1", "ff02::1")) {
            assertThrows(IllegalArgumentException::class.java) { WirelessAdbEndpoint.parse(host, "37001") }
        }
        val local = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }.toSet()
        val remote = (1..254).map { InetAddress.getByName("192.0.2.$it") }.first { it !in local }
        assertThrows(IllegalArgumentException::class.java) {
            WirelessAdbEndpoint.parse(remote.hostAddress!!, "37001")
        }
        for (port in listOf("0", "-1", "65536", "abc", "37001;id", "")) {
            assertThrows(IllegalArgumentException::class.java) { WirelessAdbEndpoint.parse("127.0.0.1", port) }
        }
    }

    @Test fun rememberedEndpointIsRevalidatedRatherThanTrustingPreferences() {
        val prefs = context.getSharedPreferences("wireless_adb_access", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertNull(WirelessAdbAccess.lastEndpoint(context))
        val endpoint = WirelessAdbEndpoint.parse("127.0.0.1", "37021")
        WirelessAdbAccess.remember(context, endpoint)
        assertEquals(endpoint, WirelessAdbAccess.lastEndpoint(context))
        for (host in listOf("localhost", "0.0.0.0", "224.0.0.1")) {
            prefs.edit().putString("host", host).commit()
            assertNull(WirelessAdbAccess.lastEndpoint(context))
        }
        prefs.edit().putString("host", "127.0.0.1").putInt("port", 0).commit()
        assertNull(WirelessAdbAccess.lastEndpoint(context))
        prefs.edit().clear().commit()
    }

    @Test fun credentialPresenceDoesNotCreateOrClaimAValidPairing() {
        val file = File(context.noBackupFilesDir, "wireless-adb-identity.p12")
        val original = file.takeIf { it.exists() }?.readBytes()
        try {
            file.delete()
            assertFalse(WirelessAdbAccess.hasIdentity(context))
            file.parentFile!!.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3))
            // Presence is deliberately not a claim that adbd still trusts the key.
            assertTrue(WirelessAdbAccess.hasIdentity(context))
        } finally {
            if (original == null) file.delete() else file.writeBytes(original)
        }
    }

    @Test fun cancellingAnUpdateClientDoesNotCancelAnIndependentGrantClient() {
        val update = WirelessAdbClient(context)
        val grant = WirelessAdbClient(context)
        update.cancel("Update cancelled")
        assertEquals("Update cancelled", update.failureMessage(IOExceptionForTest()))
        assertEquals("grant connection failed", grant.failureMessage(IOExceptionForTest()))
    }

    private class IOExceptionForTest : java.io.IOException("grant connection failed")
}
