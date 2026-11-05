package space.linuxct.nothing.icongen

import android.content.Context

/** Controls optional notifications, never Android's required foreground-service notification. */
object NotificationPreferences {
    private const val PREFS = "notification_preferences"
    private const val ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    // Posting shares this monitor so an in-flight update cannot reappear after disabling.
    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, enabled).apply()
        if (!enabled) {
            IconPackBuildService.cancelNotifications(context)
            AppUpdateChecker.cancelNotification(context)
        }
    }
}
