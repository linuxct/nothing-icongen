package space.linuxct.nothing.icongen

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Lives only for one finite update; there is no resident ADB connection or retry loop. */
class AppSelfUpdateService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var task: AppSelfUpdater.Operation? = null
    private var finished = false
    private var foregroundAvailable = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "IconGen installation", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                })
        }
        foregroundAvailable = enterForeground()
    }

    private fun foreground() {
        val notification = notification(ongoing = true)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun enterForeground(): Boolean = try {
        foreground()
        true
    } catch (error: Exception) {
        AppSelfUpdater.startupFailed(applicationContext, error)
        stopSelf()
        false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundAvailable) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (finished) {
            finished = false
            task = null
            foregroundAvailable = enterForeground()
            if (!foregroundAvailable) return START_NOT_STICKY
        }
        if (task != null) return START_NOT_STICKY
        val operation = AppSelfUpdater.claim()
        if (operation == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        task = operation
        Thread({
            AppSelfUpdater.execute(applicationContext, operation) {
                main.post {
                    if (!finished && task === operation) {
                        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(ongoing = true)) }
                    }
                }
            }
            main.post {
                finished = true
                AppSelfUpdater.finished(applicationContext, operation)
                stopForeground(STOP_FOREGROUND_REMOVE)
                postTerminalNotification()
                stopSelfResult(startId)
            }
        }, "IconGen-self-update").start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!finished) task?.let(AppSelfUpdater::serviceStopped)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(ongoing: Boolean): Notification {
        val status = AppSelfUpdater.status(this)
        val intent = PendingIntent.getActivity(this, NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (ongoing) "Updating IconGen" else if (status.phase == AppSelfUpdater.Phase.DONE) "IconGen updated" else "IconGen update needs attention")
            .apply {
                val percent = if (ongoing && status.phase == AppSelfUpdater.Phase.DOWNLOADING)
                    " ${status.downloadPercent}%" else ""
                setContentText(status.message + percent)
                setStyle(Notification.BigTextStyle().bigText(status.message + percent))
            }
            .setContentIntent(intent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    private fun postTerminalNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (!NotificationPreferences.isEnabled(this) || !manager.areNotificationsEnabled() ||
            (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ||
            (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE)) return
        runCatching { manager.notify(NOTIFICATION_ID, notification(ongoing = false)) }
    }

    companion object {
        const val NOTIFICATION_ID = 1026
        const val CHANNEL_ID = "icongen_self_update"
    }
}
