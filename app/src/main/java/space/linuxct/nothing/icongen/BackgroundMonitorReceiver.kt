package space.linuxct.nothing.icongen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Boot and app replacement restore chosen features and independent durable selector work. */
class BackgroundMonitorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AppSelfUpdater.recover(context)
                PackageChangeMonitor.register(context)
                BackgroundMonitorService.sync(context, refresh = true)
                AppUpdateChecker.sync(context)
            }
        }
    }
}