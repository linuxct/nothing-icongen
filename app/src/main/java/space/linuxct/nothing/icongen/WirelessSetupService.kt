package space.linuxct.nothing.icongen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import java.util.UUID

/** User-started, bounded setup; all work and inline replies stay outside the Settings activity. */
class WirelessSetupService : Service() {
    companion object {
        const val PREFS = "wireless_setup"
        private const val KEY_STATUS = "status"
        private const val KEY_RUNNING = "running"
        private const val CHANNEL = "wireless_setup_v1"
        private const val NOTIFICATION_ID = 1025
        private const val ACTION_START = "space.linuxct.nothing.icongen.wireless.START"
        private const val ACTION_REPLY = "space.linuxct.nothing.icongen.wireless.REPLY"
        private const val ACTION_REFRESH = "space.linuxct.nothing.icongen.wireless.REFRESH"
        private const val ACTION_STOP = "space.linuxct.nothing.icongen.wireless.STOP"
        private const val CODE_RESULT = "pairing_code"
        private const val EXTRA_PAIR_ONLY = "pair_only"
        private const val OPERATION_TIMEOUT_MS = 45_000L
        private const val SESSION_TIMEOUT_MS = 180_000L
        private const val INITIAL_STATUS = "Start setup, then open Wireless debugging in Android Settings."
        @Volatile private var active = false

        fun start(context: Context, pairOnly: Boolean = false): Boolean {
            val app = context.applicationContext
            val intent = Intent(app, WirelessSetupService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_PAIR_ONLY, pairOnly)
            try {
                app.startForegroundService(intent)
                return true
            } catch (error: RuntimeException) {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_RUNNING, false)
                    .putString(KEY_STATUS, "Could not start wireless setup. Check notification and foreground-service access, then try again.")
                    .apply()
                return false
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context, WirelessSetupService::class.java))
        }

        fun status(context: Context, forAppUpdate: Boolean = false): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val message = if (!active && prefs.getBoolean(KEY_RUNNING, false)) {
                "The previous setup ended. Start setup again and use a fresh pairing-code dialog."
            } else prefs.getString(KEY_STATUS, INITIAL_STATUS) ?: INITIAL_STATUS
            if (forAppUpdate && !active) {
                return if (prefs.getBoolean(EXTRA_PAIR_ONLY, false)) {
                    "Last update setup: $message\nEvery update requires a fresh authenticated connection."
                } else {
                    "Enable Wireless debugging or pair IconGen again for updates. Previous Settings setup does not verify an update connection."
                }
            }
            return message
        }

        fun isRunning(context: Context): Boolean = active &&
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RUNNING, false)
    }

    private enum class Work { PAIR, GRANT, AUTHORIZE }

    private class Operation(
        val kind: Work,
        val client: WirelessAdbClient,
        val code: ByteArray?,
    ) {
        var thread: Thread? = null
        var timeout: Runnable? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val sessionUri = Uri.parse("icongen-wireless://setup/${UUID.randomUUID()}")
    private var foregroundReady = false
    private var started = false
    private var finished = false
    private var paired = false
    private var pairOnly = false
    private var pairingEndpoint: WirelessAdbEndpoint? = null
    private var connectEndpoint: WirelessAdbEndpoint? = null
    private var discovery: WirelessAdbDiscovery? = null
    private var operation: Operation? = null
    private var issue: String? = null
    private val sessionTimeout = Runnable {
        finish(
            "Wireless setup timed out after three minutes. Start setup again with a fresh pairing-code dialog.",
            "Wireless setup timed out",
        )
    }
    private val connectTimeout = Runnable {
        paired = false
        retry("No local Wireless debugging connection port appeared. Check Wi-Fi and Wireless debugging, then open a fresh pairing-code dialog and reply again.")
    }

    override fun onCreate() {
        super.onCreate()
        active = true
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "Wireless debugging setup", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Temporary pairing-code reply, Settings grants and saved local update identity"
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            })
            val notification = notification("Starting temporary Wireless debugging setup…")
            // This mandatory FGS notification is independent of the optional results preference.
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            foregroundReady = true
            record("Starting temporary Wireless debugging setup…", true)
        } catch (_: RuntimeException) {
            finish("Wireless setup could not enter the foreground. Check the setup notification and specialUse service permissions.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady || finished) {
            if (intent?.action == ACTION_REPLY) takeCode(intent)?.fill(0)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> if (!started) {
                pairOnly = intent.getBooleanExtra(EXTRA_PAIR_ONLY, false)
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(EXTRA_PAIR_ONLY, pairOnly).apply()
                started = true
                handler.postDelayed(sessionTimeout, SESSION_TIMEOUT_MS)
                discovery = WirelessAdbDiscovery(
                    this, handler,
                    onEndpoint = { kind, endpoint ->
                        if (!finished) {
                            when (kind) {
                                WirelessAdbDiscovery.Kind.PAIRING -> pairingEndpoint = endpoint
                                WirelessAdbDiscovery.Kind.CONNECT -> connectEndpoint = endpoint
                            }
                            if (paired && operation == null && connectEndpoint != null) completeConnection()
                            else render()
                        }
                    },
                    onFailure = {
                        if (!finished) {
                            issue = "Local discovery could not start. Check Wi-Fi and Wireless debugging, then tap Refresh discovery."
                            render()
                        }
                    },
                )
                discovery?.start()
                render()
            }
            ACTION_REPLY -> {
                val code = takeCode(intent)
                if (!started || intent.data != sessionUri || operation != null || paired) {
                    code?.fill(0)
                    if (!started) finish("This pairing notification expired. Start setup again.")
                } else {
                    val endpoint = pairingEndpoint
                    when {
                        code == null -> {
                            issue = "Enter exactly six digits from the still-open Android pairing-code dialog, using this notification's reply action."
                            render()
                        }
                        endpoint == null -> {
                            code.fill(0)
                            issue = "The pairing dialog is no longer discoverable. Reopen Pair device with pairing code in Settings, then reply with the new code."
                            discovery?.start()
                            render()
                        }
                        else -> begin(Work.PAIR, endpoint, code)
                    }
                }
            }
            ACTION_REFRESH -> if (started && intent.data == sessionUri && operation == null && !paired) {
                issue = null
                discovery?.start()
                render()
            }
            ACTION_STOP -> if (intent.data == sessionUri) finish("Wireless setup cancelled.")
            else -> if (!started) finish("Wireless setup ended. Start setup again to retry.")
        }
        if (!started && !finished) finish("This setup notification expired. Start setup again.")
        return START_NOT_STICKY
    }

    /** Do not turn the reply into a String, retain its Intent, or persist/log any entered code. */
    private fun takeCode(intent: Intent): ByteArray? {
        val results = RemoteInput.getResultsFromIntent(intent)
        val input = results?.getCharSequence(CODE_RESULT)
        val bytes: ByteArray
        try {
            if (input == null || input.length != 6) return null
            bytes = ByteArray(6)
            for (index in bytes.indices) {
                val char = input[index]
                if (char !in '0'..'9') {
                    bytes.fill(0)
                    return null
                }
                bytes[index] = char.code.toByte()
            }
            return bytes
        } finally {
            if (input is Editable) input.clear()
            results?.clear()
            intent.clipData = null
            intent.replaceExtras(null as Bundle?)
        }
    }

    private fun completeConnection() {
        val endpoint = connectEndpoint ?: return
        handler.removeCallbacks(connectTimeout)
        begin(if (pairOnly) Work.AUTHORIZE else Work.GRANT, endpoint, null)
    }

    private fun begin(kind: Work, endpoint: WirelessAdbEndpoint, code: ByteArray?) {
        if (finished || operation != null) {
            code?.fill(0)
            return
        }
        issue = null
        val current = Operation(kind, WirelessAdbClient(applicationContext), code)
        operation = current
        current.timeout = Runnable {
            if (operation === current && !finished) {
                cancelOperation("Wireless setup operation exceeded 45 seconds")
                paired = false
                retry(if (kind == Work.PAIR) {
                    "Pairing timed out after 45 seconds. Leave Settings in front, reopen Pair device with pairing code, then reply here with the new code."
                } else {
                    "Local ADB authorization timed out after 45 seconds. Check Wi-Fi and Wireless debugging, then open a fresh pairing-code dialog and reply again."
                })
            }
        }
        handler.postDelayed(current.timeout!!, OPERATION_TIMEOUT_MS)
        render()
        if (finished) return
        current.thread = Thread({
            var succeeded = false
            var grants: WirelessGrantStatus? = null
            var nativeFailure = false
            var failure: String? = null
            try {
                when (kind) {
                    Work.PAIR -> current.client.pair(endpoint, code!!)
                    Work.GRANT -> grants = current.client.grant(endpoint)
                    Work.AUTHORIZE -> current.client.authorize(endpoint)
                }
                succeeded = true
            } catch (error: Exception) {
                failure = describeFailure(error)
            } catch (error: LinkageError) {
                nativeFailure = true
                failure = describeFailure(error)
            } finally {
                code?.fill(0)
            }
            handler.post { complete(current, succeeded, grants, nativeFailure, failure) }
        }, "IconGen-wireless-setup").apply { start() }
    }

    private fun describeFailure(error: Throwable): String {
        val cause = generateSequence(error) { it.cause }.take(8).last()
        val detail = cause.message.orEmpty().replace(Regex("\\b[0-9]{6}\\b"), "[redacted]").take(240)
        return "${cause.javaClass.simpleName}: $detail"
    }

    private fun complete(
        current: Operation,
        succeeded: Boolean,
        grants: WirelessGrantStatus?,
        nativeFailure: Boolean,
        failure: String?,
    ) {
        if (finished || operation !== current) return
        current.timeout?.let { handler.removeCallbacks(it) }
        current.code?.fill(0)
        operation = null
        if (nativeFailure) {
            finish("Wireless ADB native libraries could not load: $failure. Install a compatible IconGen build before trying again.", "Wireless setup failed")
        } else if (!succeeded) {
            paired = false
            retry("${failure.orEmpty()}\n" + if (current.kind == Work.PAIR) {
                "Pairing failed. Keep Settings in front, close and reopen Pair device with pairing code, then reply here with the new six-digit code. Check that Wi-Fi and Wireless debugging remain on."
            } else if (current.kind == Work.AUTHORIZE) {
                "ADB authorization was not confirmed. Enable Wireless debugging; if Android forgot IconGen, pair again. Settings permissions were not changed."
            } else {
                "Android did not confirm the local permission grants. Check Wi-Fi and Wireless debugging, then open a fresh pairing-code dialog and reply again.\n${WirelessGrantStatus.read(this).describe()}"
            })
        } else if (current.kind == Work.PAIR) {
            paired = true
            handler.postDelayed(connectTimeout, OPERATION_TIMEOUT_MS)
            if (connectEndpoint != null) completeConnection() else render()
        } else if (current.kind == Work.AUTHORIZE) {
            finish(
                "Wireless ADB authorization confirmed for IconGen updates. Settings permissions were not changed. Return to IconGen and retry the update with Wireless debugging enabled.",
                "Wireless update pairing complete", IconPackBuildService.ResultKind.SUCCESS,
            )
        } else {
            // A successful socket exchange is insufficient: both actual Android grants must agree.
            val actual = WirelessGrantStatus.read(this)
            if (grants?.complete == true && actual.complete) {
                BackgroundMonitorService.sync(this, refresh = true)
                finish(
                    "Wireless setup complete.\n${actual.describe()}\nThis IconGen identity is paired for updates. Keep Wireless debugging on while updating; turn it off when not using the updater. Settings grants remain separate from installation access.",
                    "Wireless setup complete", IconPackBuildService.ResultKind.SUCCESS,
                )
            } else {
                paired = false
                retry("Android did not confirm both grants. Open a fresh pairing-code dialog and reply again.\n${actual.describe()}")
            }
        }
    }

    private fun retry(message: String) {
        handler.removeCallbacks(connectTimeout)
        issue = message
        discovery?.start()
        render()
    }

    private fun cancelOperation(reason: String) {
        val current = operation ?: return
        operation = null
        current.timeout?.let { handler.removeCallbacks(it) }
        current.client.cancel(reason)
        current.thread?.interrupt()
        current.code?.fill(0)
    }

    private fun render() {
        if (finished || !foregroundReady) return
        val message = when (operation?.kind) {
            Work.PAIR -> "Pairing securely with this phone. Keep the Android pairing-code dialog open; do not return to IconGen."
            Work.GRANT -> "Connected to this phone. Applying and verifying the two fixed permission grants; keep Settings open."
            Work.AUTHORIZE -> "Checking this phone's ADB authorization for updates; Settings permissions will not be changed."
            null -> when {
                paired -> "Pairing succeeded. Discovering this phone's Wireless debugging connection port; keep Settings open."
                pairingEndpoint != null -> "Keep the Settings pairing-code dialog open. Expand this notification and tap Enter pairing code to reply with its six digits. Do not switch back to IconGen."
                else -> "In Android Settings, open Developer options → Wireless debugging → Pair device with pairing code. Leave that dialog open; this notification will offer a code reply when this phone is discovered."
            }
        }
        val text = issue?.let { "$it\n\n$message" } ?: message
        record(text, true)
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        } catch (_: RuntimeException) {
            finish("The pairing notification is unavailable. Enable notifications for Wireless debugging setup, then start again.")
        }
    }

    private fun notification(message: String): Notification {
        val icon = when {
            operation != null || paired -> R.drawable.ic_notification_sync_animated
            issue != null -> R.drawable.ic_notification_error
            pairingEndpoint != null -> R.drawable.ic_notification_waiting
            else -> R.drawable.ic_notification_sync_animated
        }
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(icon)
            .setLargeIcon(NotificationArtwork.state(this, icon))
            .setContentTitle("Wireless debugging setup")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setCategory(Notification.CATEGORY_STATUS)
            .setPriority(Notification.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setDefaults(0)
            .setSound(null)
        if (started && !paired && operation == null && pairingEndpoint != null) {
            val mutable = PendingIntent.FLAG_MUTABLE
            val reply = PendingIntent.getService(
                this, 1, actionIntent(ACTION_REPLY), PendingIntent.FLAG_UPDATE_CURRENT or mutable,
            )
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_send, "Enter pairing code", reply)
                .addRemoteInput(RemoteInput.Builder(CODE_RESULT)
                    .setLabel("Six-digit pairing code")
                    .setAllowFreeFormInput(true)
                    .build())
                .setAllowGeneratedReplies(false)
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                .build())
        } else if (started && !paired && operation == null) {
            builder.addAction(Notification.Action.Builder(
                android.R.drawable.ic_popup_sync, "Refresh discovery", immutableAction(ACTION_REFRESH, 2),
            ).build())
        }
        builder.addAction(Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel, "Stop setup", immutableAction(ACTION_STOP, 3),
        ).build())
        // No content Activity intent: inline reply and grant must never navigate away from Settings.
        return builder.build()
    }

    private fun actionIntent(action: String): Intent =
        Intent(this, WirelessSetupService::class.java).setAction(action).setData(sessionUri)

    private fun immutableAction(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, actionIntent(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun record(message: String, running: Boolean) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STATUS, message).putBoolean(KEY_RUNNING, running).apply()
    }

    private fun finish(
        message: String,
        resultTitle: String? = null,
        resultKind: IconPackBuildService.ResultKind = IconPackBuildService.ResultKind.ERROR,
    ) {
        if (finished) return
        finished = true
        release()
        record(message, false)
        if (resultTitle != null) IconPackBuildService.postResult(this, resultTitle, message, resultKind)
        stopSelf()
    }

    private fun release() {
        active = false
        handler.removeCallbacksAndMessages(null)
        cancelOperation("Wireless setup stopped")
        discovery?.stop()
        discovery = null
        pairingEndpoint = null
        connectEndpoint = null
        if (foregroundReady) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundReady = false
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    override fun onDestroy() {
        if (!finished) {
            finished = true
            release()
            record("Wireless setup stopped. Start again with a fresh pairing-code dialog to retry.", false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
