package space.linuxct.nothing.icongen

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Setup continues in its temporary service while Android's pairing dialog stays in front. */
class WirelessDebuggingActivity : AppCompatActivity() {
    private lateinit var ui: LucentUi
    private lateinit var primary: Button
    private lateinit var notificationRow: View
    private lateinit var developerRow: View
    private lateinit var pairAgainRow: View
    private lateinit var guidance: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var permissions: TextView
    private lateinit var setupPrefs: SharedPreferences
    private var resumed = false
    private val forAppUpdate: Boolean
        get() = intent.getBooleanExtra(EXTRA_FOR_APP_UPDATE, false)
    private val setupListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { if (resumed) refreshStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        ui = LucentUi(this)
        setupPrefs = getSharedPreferences(WirelessSetupService.PREFS, Context.MODE_PRIVATE)
        val root = ui.screen(this)
        ui.header(root, "Wireless setup", back = { finish() }, menu = ::showHelp)
        ui.add(root, ui.text(if (forAppUpdate) "Pair or reconnect this phone for IconGen updates." else "Settings access and IconGen updates, without a computer.", 14f, muted = true), 8)
        val card = ui.card()
        status = ui.text("Ready to pair", 18f).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        primary = ui.button("Start pairing", primary = true) {
            when {
                WirelessSetupService.isRunning(this) -> WirelessSetupService.stop(this)
                WirelessGrantStatus.read(this).complete && !forAppUpdate -> finish()
                !notificationsAvailable() -> recoverNotifications()
                WirelessSetupService.start(this, pairOnly = forAppUpdate) -> openDeveloperSettings()
            }
            refreshStatus()
        }
        ui.add(card, primary)
        ui.add(card, status, 16)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(ui.ink)
        }
        card.addView(progress, LinearLayout.LayoutParams(-1, ui.dp(4)).apply { topMargin = ui.dp(16) })
        permissions = ui.text("", 12f, muted = true)
        ui.add(card, permissions, 12)
        ui.add(root, card, 24)
        pairAgainRow = ui.row("Pair again for IconGen updates", "Available even when a PC already granted Settings access") {
            intent.putExtra(EXTRA_FOR_APP_UPDATE, true)
            refreshStatus()
        }
        ui.add(root, pairAgainRow, 12)
        if (forAppUpdate) {
            ui.add(root, ui.text("If this IconGen identity is still paired, just enable Wireless debugging in Developer options on trusted Wi-Fi, then return to IconGen and retry the update. Use Start pairing or Pair again only if pairing is missing or revoked. Settings grants alone do not permit installation.", 13f, muted = true), 16)
            ui.add(root, ui.button("Return to IconGen") { finish() }, 12)
        }

        notificationRow = ui.row("Enable pairing notifications", "Required to enter the code without leaving Settings") { recoverNotifications() }
        ui.add(root, notificationRow, 12)
        guidance = ui.card(0)
        ui.add(guidance, step("1", "Open Wireless debugging", "Tap Start pairing, then enable Wireless debugging in Developer options on trusted Wi-Fi."))
        ui.divider(guidance)
        ui.add(guidance, step("2", "Show the pairing code", "Choose Pair device with pairing code. Keep that Settings dialog open."))
        ui.divider(guidance)
        ui.add(guidance, step("3", "Reply from the notification", "Pull down the shade, expand IconGen’s pairing notification and reply with the six-digit code. Don’t switch apps."))
        ui.add(root, guidance, 16)
        developerRow = ui.row("Open Developer options", "Enable Wireless debugging to reconnect; pairing setup keeps running if started") { openDeveloperSettings() }
        ui.add(root, developerRow, 12)
    }

    private fun step(number: String, title: String, description: String): View {
        val row = ui.column(16)
        val heading = LinearLayout(this).apply {
            gravity = android.view.Gravity.TOP
            isBaselineAligned = false
        }
        val numberView = ui.text(number, 22f, heading = true).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
        }
        val titleView = ui.text(title, 15f)
        // Different typefaces have different space above their visible glyphs, even without font padding.
        val bounds = android.graphics.Rect()
        val metrics = android.graphics.Paint.FontMetricsInt()
        numberView.paint.getFontMetricsInt(metrics)
        numberView.paint.getTextBounds(number, 0, number.length, bounds)
        val numberTop = bounds.top - metrics.ascent
        titleView.paint.getFontMetricsInt(metrics)
        titleView.paint.getTextBounds(title, 0, title.length, bounds)
        val titleTop = bounds.top - metrics.ascent
        val top = maxOf(numberTop, titleTop)
        numberView.setPadding(0, top - numberTop, 0, 0)
        titleView.setPadding(0, top - titleTop, 0, 0)
        heading.addView(numberView, LinearLayout.LayoutParams(ui.dp(28), -2))
        heading.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = ui.dp(12)
        })
        ui.add(row, heading)
        ui.add(row, ui.text(description, 13f, muted = true).apply {
            setPadding(ui.dp(40), 0, 0, 0)
        }, 5)
        return row
    }

    private fun showHelp() {
        ui.dialog(this, "Before pairing") { content, dialog ->
            ui.add(content, ui.text(if (forAppUpdate) {
                "Pair only on trusted Wi-Fi and only if you trust IconGen. This update-only setup authorizes this app’s saved ADB identity without restoring or changing Settings permissions you may have revoked."
            } else {
                "Pair only on trusted Wi-Fi and only if you trust IconGen. This setup also grants secure and system Settings access for applying rebuilt icons and theme changes. Those grants alone do not authorize app installation."
            }, 14f))
            ui.add(content, ui.text("If Developer options is hidden, open Settings → About phone and tap Build number seven times. Return here and tap Start pairing.", 14f, muted = true), 16)
            ui.add(content, ui.text("The temporary silent notification is required even if optional progress notifications are off. It disappears when setup finishes, is stopped or times out after three minutes. Keep the pairing-code dialog in front; enter its code only through the notification reply.", 13f, muted = true), 16)
            ui.add(content, ui.text("Update now uses the saved pairing to verify and install a newer official IconGen release; Android restarts IconGen during replacement. Installation starts only after you choose Update now. A saved identity does not guarantee that Android still trusts it or that a connection is live.", 13f, muted = true), 16)
            ui.add(content, ui.text("Turn Wireless debugging off when not using the updater. Enable it again to update on trusted Wi-Fi; Android may change its connection port. If IconGen is no longer paired, pair again here even when both Settings grants are already enabled.", 13f, muted = true), 16)
            ui.add(content, ui.button("Done") { dialog.dismiss() }, 20)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        setupPrefs.registerOnSharedPreferenceChangeListener(setupListener)
        refreshStatus()
    }

    override fun onPause() {
        resumed = false
        setupPrefs.unregisterOnSharedPreferenceChangeListener(setupListener)
        super.onPause()
    }

    private fun refreshStatus() {
        val running = WirelessSetupService.isRunning(this)
        val grants = WirelessGrantStatus.read(this)
        val ready = grants.complete && !running
        val available = notificationsAvailable()
        primary.text = when {
            running -> "Stop setup"
            ready && !forAppUpdate -> "Done"
            WirelessAdbAccess.hasIdentity(this) -> "Pair again"
            else -> "Start pairing"
        }
        primary.isEnabled = running || (ready && !forAppUpdate) || available
        progress.visibility = if (running) View.VISIBLE else View.GONE
        notificationRow.visibility = if ((!ready || forAppUpdate) && !available) View.VISIBLE else View.GONE
        guidance.visibility = if (ready && !forAppUpdate) View.GONE else View.VISIBLE
        developerRow.visibility = if (running || forAppUpdate) View.VISIBLE else View.GONE
        pairAgainRow.visibility = if (ready && !forAppUpdate) View.VISIBLE else View.GONE
        status.text = when {
            ready && !forAppUpdate -> "Settings access is ready"
            running -> WirelessSetupService.status(this).ifBlank { "Waiting for the pairing code…" }
            !available -> "Enable notifications to pair"
            forAppUpdate -> WirelessSetupService.status(this, forAppUpdate = true)
            else -> WirelessSetupService.status(this).ifBlank { "Ready to pair" }
        }
        val secure = if (grants.secureSettings) "enabled" else "not yet enabled"
        val system = if (grants.writeSettings && grants.writeSettingsMode == android.app.AppOpsManager.MODE_ALLOWED) "enabled" else "not yet enabled"
        val identity = if (WirelessAdbAccess.hasIdentity(this)) {
            "Identity saved; pairing and live connection not verified."
        } else {
            "No saved Wireless debugging identity. Pair this IconGen installation first."
        }
        permissions.text = "Secure Settings: $secure\nSystem Settings: $system\n\n$identity"
    }

    private fun notificationsAvailable(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            manager.getNotificationChannel(SETUP_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun recoverNotifications() {
        val manager = getSystemService(NotificationManager::class.java)
        val intent = if (manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(SETUP_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
        ) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, SETUP_CHANNEL)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun openDeveloperSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            status.text = "Open Settings → Developer options → Wireless debugging. The pairing notification remains active."
        }
    }

    companion object {
        const val EXTRA_FOR_APP_UPDATE = "wireless_for_app_update"
        private const val SETUP_CHANNEL = "wireless_setup_v1"
    }
}
