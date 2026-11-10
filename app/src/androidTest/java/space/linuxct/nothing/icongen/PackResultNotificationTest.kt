package space.linuxct.nothing.icongen

import android.Manifest
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackResultNotificationTest {
    @Test fun successfulPackCallbackIsSilentButFailureAndConfirmationStillNotify() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        val version = try { context.packageManager.getPackageInfo(PackageChangeReceiver.PACK_PACKAGE, 0).longVersionCode }
            catch (_: PackageManager.NameNotFoundException) { assumeTrue("Requires an installed pack", false); return }
        val scheduler = context.getSystemService(JobScheduler::class.java)
        assumeTrue(!BuildCoordinator.state.isRunning && !IconPackInstaller.hasPendingOperation(context))
        assumeTrue(scheduler.getPendingJob(IconPackSelectionJobService.JOB_ID) == null)
        val manager = context.getSystemService(NotificationManager::class.java)
        assumeTrue("Notifications are blocked by the device", manager.areNotificationsEnabled())
        assumeTrue("Result channel is blocked by the user",
            manager.getNotificationChannel("iconpack_results_silent")?.importance != NotificationManager.IMPORTANCE_NONE)
        fun awaitIcon(icon: Int): android.app.Notification {
            val deadline = android.os.SystemClock.uptimeMillis() + 5_000
            while (true) {
                manager.activeNotifications.firstOrNull { it.id == 43 && it.notification.smallIcon.resId == icon }
                    ?.let { return it.notification }
                check(android.os.SystemClock.uptimeMillis() < deadline) { "Android did not publish result icon $icon" }
                android.os.SystemClock.sleep(25)
            }
        }
        val names = listOf("install_state", "build_queue", "onboarding", "notification_preferences", "package_monitor")
        val saved = names.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
        val state = BuildCoordinator.state
        fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        fun callback(status: Int, session: Int) {
            prefs("install_state").edit().clear().putString("stage", "installing").putInt("session", session)
                .putLong("expected_version", version).putLong("previous_version", version - 1).commit()
            BuildCoordinator.update { it.copy(phase = BuildCoordinator.Phase.INSTALLING) }
            val intent = Intent(IconPackInstaller.ACTION_INSTALL_STATUS)
                .putExtra("operation_session", session).putExtra(PackageInstaller.EXTRA_STATUS, status)
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                intent.putExtra(Intent.EXTRA_INTENT, Intent(context, MainActivity::class.java))
            }
            IconPackInstaller.InstallationReceiver().onReceive(context, intent)
        }
        fun awaitCleared() {
            val deadline = android.os.SystemClock.uptimeMillis() + 5_000
            while (manager.activeNotifications.any { it.id == 43 } && android.os.SystemClock.uptimeMillis() < deadline) {
                android.os.SystemClock.sleep(25)
            }
            assertTrue("Successful completion must clear obsolete install notices", manager.activeNotifications.none { it.id == 43 })
        }
        manager.cancel(43)
        try {
            prefs("build_queue").edit().clear().commit()
            prefs("notification_preferences").edit().putBoolean("enabled", true).commit()
            OnboardingState.markComplete(context)
            callback(PackageInstaller.STATUS_SUCCESS, 1471)
            // The production selector has a five-second settle delay; never apply test callbacks.
            scheduler.cancel(IconPackSelectionJobService.JOB_ID)
            assertEquals(BuildCoordinator.Phase.DONE, BuildCoordinator.state.phase)
            assertTrue("Verified pack success must not leave a result notification", manager.activeNotifications.none { it.id == 43 })
            callback(PackageInstaller.STATUS_FAILURE, 1472)
            assertEquals(BuildCoordinator.Phase.FAILED, BuildCoordinator.state.phase)
            awaitIcon(R.drawable.ic_notification_error)
            callback(PackageInstaller.STATUS_SUCCESS, 1473)
            scheduler.cancel(IconPackSelectionJobService.JOB_ID)
            awaitCleared()
            callback(PackageInstaller.STATUS_PENDING_USER_ACTION, 1474)
            assertEquals(BuildCoordinator.Phase.WAITING_CONFIRMATION, BuildCoordinator.state.phase)
            assertNotNull(awaitIcon(R.drawable.ic_notification_waiting).contentIntent)
            callback(PackageInstaller.STATUS_SUCCESS, 1474)
            scheduler.cancel(IconPackSelectionJobService.JOB_ID)
            awaitCleared()
        } finally {
            manager.cancel(43)
            scheduler.cancel(IconPackSelectionJobService.JOB_ID)
            saved.forEach { (name, values) -> restore(prefs(name), values) }
            BuildCoordinator.update { state }
        }
    }

    private fun restore(preferences: SharedPreferences, values: Map<String, *>) {
        preferences.edit().clear().apply {
            for ((key, value) in values) when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }.commit()
    }
}
