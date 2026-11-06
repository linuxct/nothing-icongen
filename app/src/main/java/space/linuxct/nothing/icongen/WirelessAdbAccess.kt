package space.linuxct.nothing.icongen

import android.content.Context
import java.io.File

/** Credentials stay in the existing no-backup PKCS12 store; endpoints are hints, not authorization. */
internal object WirelessAdbAccess {
    private const val PREFS = "wireless_adb_access"

    fun hasIdentity(context: Context): Boolean =
        File(context.noBackupFilesDir, "wireless-adb-identity.p12").isFile

    fun lastEndpoint(context: Context): WirelessAdbEndpoint? {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = preferences.getString("host", null) ?: return null
        val port = preferences.getInt("port", 0)
        // Network addresses and connect ports can change; revalidate on every use and connection.
        return runCatching { WirelessAdbEndpoint.parse(host, port.toString()) }.getOrNull()
    }

    /** Remember only after a fresh authenticated ADB session, never from credential presence alone. */
    fun remember(context: Context, endpoint: WirelessAdbEndpoint) {
        val validated = WirelessAdbEndpoint.parse(endpoint.address.hostAddress!!, endpoint.port.toString())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("host", validated.address.hostAddress)
            .putInt("port", validated.port)
            .apply()
    }
}
