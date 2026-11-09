package space.linuxct.nothing.icongen

import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Looper
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Durable operation fixtures exercise production transitions without compiling/installing APKs. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AutomaticPipelineTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Implements(android.content.pm.LauncherApps::class)
    class UnavailableProfileLauncherApps {
        @Implementation
        fun getProfiles(): List<android.os.UserHandle> =
            listOf(android.os.Process.myUserHandle(), android.os.UserHandle.getUserHandleForUid(1_000_000))

        @Implementation
        fun getActivityList(packageName: String?, user: android.os.UserHandle):
            List<android.content.pm.LauncherActivityInfo> {
            if (user != android.os.Process.myUserHandle()) throw SecurityException("Profile locked")
            return emptyList()
        }
    }

    @Test
    @Config(shadows = [UnavailableProfileLauncherApps::class])
    fun unavailableProfileDoesNotBlockAccessibleUpdatesOrEraseItsObservedIdentities() {
        val users = context.getSystemService(android.os.UserManager::class.java)
        val serial = users.getSerialNumberForUser(android.os.UserHandle.getUserHandleForUid(1_000_000))
        val hiddenKey = "$serial:example.work"
        val baseline = PackageChangeMonitor.identities(context) + (hiddenKey to "work-source")
        PackageChangeMonitor.reconcileSnapshot(context, baseline)
        DirtyIconQueue.record(context, mapOf(hiddenKey to "work-source"))
        installedPackage("example.new")

        PackageChangeMonitor.reconcile(context)

        assertEquals(1L, prefs("build_queue").getLong("requested", 0))
        assertEquals("work-source", org.json.JSONObject(prefs("package_monitor")
            .getString("observed_packages", "{}")!!).getString(hiddenKey))
        assertEquals(setOf("example.work", "example.new"), DirtyIconQueue.snapshot(context)
            .entries.keys.map { it.substringAfter(':') }.toSet())
        assertNotNull(context.getSystemService(JobScheduler::class.java)
            .getPendingJob(BuildCoordinator.WARM_JOB_ID))
    }

    @Before fun setUp() {
        for (name in listOf("build_queue", "icon_preparation", "install_state", "auto_update", "package_monitor", "onboarding", "theme_refresh")) {
            prefs(name).edit().clear().commit()
        }
        OnboardingState.markComplete(context)
        context.getSystemService(JobScheduler::class.java).cancelAll()
        BuildCoordinator.reset()
        IconPackInstaller.statusListener = null
    }

    @After fun tearDown() {
        context.getSystemService(JobScheduler::class.java).cancelAll()
        IconPackInstaller.statusListener = null
        BuildCoordinator.reset()
    }

    private fun change(action: String, pkg: String = "example.probe", replacing: Boolean = false) {
        PackageChangeReceiver().onReceive(context, Intent(action, Uri.parse("package:$pkg"))
            .putExtra(Intent.EXTRA_REPLACING, replacing))
    }


    private fun installedPackage(name: String = "example.probe", version: Long = 1, source: File? = null) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply {
                packageName = name
                sourceDir = source?.absolutePath
            }
            setLongVersionCode(version)
            lastUpdateTime = version * 1000
        })
    }

    @Test fun directIdentityMatchesInventoryAndDoesNotExcludeBundledArtworkSources() {
        val source = File(context.cacheDir, "identity-fixture.apk").apply { writeText("source") }
        val user = android.os.Process.myUserHandle()
        try {
            installedPackage(source = source, version = 7)
            val key = PackageChangeMonitor.identities(context).keys.single { it.endsWith(":example.probe") }
            assertEquals(PackageChangeMonitor.identities(context).getValue(key),
                PackageChangeMonitor.identity(context, user, "example.probe"))
            installedPackage(PackageChangeReceiver.PACK_PACKAGE, version = 8, source = source)
            assertEquals("8:8000", PackageChangeMonitor.identity(context, user, PackageChangeReceiver.PACK_PACKAGE))
            assertFalse(PackageChangeMonitor.identities(context).keys.any { it.endsWith(":${PackageChangeReceiver.PACK_PACKAGE}") })
            source.delete()
            assertNull(PackageChangeMonitor.identity(context, user, "example.probe"))
            assertNull(PackageChangeMonitor.identity(context, user, "example.absent"))
        } finally { source.delete() }
    }
    @Test fun replacementBroadcastsProduceOneRequestAndOwnPackagesProduceNone() {
        PackageChangeMonitor.reconcile(context)
        installedPackage()
        change(Intent.ACTION_PACKAGE_REMOVED, replacing = true)
        change(Intent.ACTION_PACKAGE_ADDED, replacing = true)
        change(Intent.ACTION_PACKAGE_REPLACED)
        change(Intent.ACTION_PACKAGE_ADDED, context.packageName)
        change(Intent.ACTION_PACKAGE_ADDED, PackageChangeReceiver.PACK_PACKAGE)
        assertEquals(1L, prefs("build_queue").getLong("requested", 0))
    }

    @Test fun disablingAutomaticUpdatesDoesNotDisableManualGeneration() {
        prefs("auto_update").edit().putBoolean("enabled", false).commit()
        installedPackage()
        change(Intent.ACTION_PACKAGE_ADDED)
        assertEquals(0L, prefs("build_queue").getLong("requested", 0))
        BuildCoordinator.start(context, "ui")
        assertEquals(1L, prefs("build_queue").getLong("requested", 0))
        assertEquals(1L, prefs("build_queue").getLong("manual", 0))
        assertNotNull(context.getSystemService(JobScheduler::class.java).getPendingJob(BuildCoordinator.JOB_ID))
    }

    @Test fun requestDuringBuildSurvivesAcknowledgingEarlierSnapshot() {
        BuildCoordinator.start(context, "ui") // Worker takes generation 1.
        installedPackage()
        change(Intent.ACTION_PACKAGE_ADDED) // Generation 2 arrives while generation 1 is building.
        BuildCoordinator.completeRequest(context, 1)
        assertEquals(2L, prefs("build_queue").getLong("requested", 0))
        assertEquals(1L, prefs("build_queue").getLong("completed", 0))
        BuildCoordinator.completeRequest(context, 1) // Restoration cannot consume generation 2 either.
        BuildCoordinator.resumeQueued(context)
        assertEquals(1L, prefs("build_queue").getLong("completed", 0))
        assertNotNull(context.getSystemService(JobScheduler::class.java).getPendingJob(BuildCoordinator.JOB_ID))
    }

    @Test fun onlyUnsubmittedApkIsReturnedForPermissionRecovery() {
        val apk = File(context.filesDir, "permission-fixture.apk").apply { writeBytes(byteArrayOf(1)) }
        try {
            prefs("install_state").edit().putString("stage", "permission")
                .putString("apk", apk.absolutePath).putString("status", "Install access needed").commit()
            IconPackInstaller.restoreState(context)
            assertEquals(BuildCoordinator.Phase.WAITING_PERMISSION, BuildCoordinator.state.phase)
            assertEquals(apk, IconPackInstaller.pendingApk(context))
            prefs("install_state").edit().putString("stage", "installing").commit()
            assertNull(IconPackInstaller.pendingApk(context))
        } finally { apk.delete() }
    }

    private fun operation(session: Int = 71) {
        prefs("install_state").edit().putString("stage", "installing")
            .putInt("session", session).putLong("expected_version", 43)
            .putLong("previous_version", 42).putString("build_fingerprint", "build-start-snapshot").commit()
        BuildCoordinator.update { it.copy(phase = BuildCoordinator.Phase.INSTALLING) }
    }

    private fun callback(status: Int, session: Int = 71, confirmation: Intent? = null) {
        val intent = Intent(IconPackInstaller.ACTION_INSTALL_STATUS)
            .putExtra("operation_session", session).putExtra(PackageInstaller.EXTRA_STATUS, status)
        if (confirmation != null) intent.putExtra(Intent.EXTRA_INTENT, confirmation)
        IconPackInstaller.InstallationReceiver().onReceive(context, intent)
    }

    @Test fun committedOperationIsNotDoneUntilItsOwnSuccessCallback() {
        operation()
        installedPackage(PackageChangeReceiver.PACK_PACKAGE, 43)
        callback(PackageInstaller.STATUS_SUCCESS, session = 70)
        assertEquals(BuildCoordinator.Phase.INSTALLING, BuildCoordinator.state.phase)
        assertNull(prefs("package_monitor").getString("installed_fingerprint", null))
        callback(PackageInstaller.STATUS_SUCCESS)
        assertEquals(BuildCoordinator.Phase.DONE, BuildCoordinator.state.phase)
        assertEquals("build-start-snapshot", prefs("package_monitor").getString("installed_fingerprint", null))
    }

    @Test fun ownSuccessCannotApplyAnOlderInstalledPack() {
        operation()
        installedPackage(PackageChangeReceiver.PACK_PACKAGE, 42)
        callback(PackageInstaller.STATUS_SUCCESS)
        assertEquals(BuildCoordinator.Phase.FAILED, BuildCoordinator.state.phase)
        assertNull(IconPackInstaller.pendingReapplyVersion(context))
        assertNull(prefs("package_monitor").getString("installed_fingerprint", null))
    }

    @Test fun confirmedSameVersionReinstallCanCompleteAndReapply() {
        operation()
        installedPackage(PackageChangeReceiver.PACK_PACKAGE, 43)
        prefs("install_state").edit().putLong("previous_version", 43).commit()
        callback(PackageInstaller.STATUS_SUCCESS)
        assertEquals(BuildCoordinator.Phase.DONE, BuildCoordinator.state.phase)
        assertEquals(43L, IconPackInstaller.pendingReapplyVersion(context))
        assertTrue(IconPackInstaller.canReapplyInstalledPack(context))
    }

    @Test fun sameVersionRecoveryWaitsForOwnSessionCallbackWithoutInferringSuccess() {
        operation()
        installedPackage(PackageChangeReceiver.PACK_PACKAGE, 43)
        prefs("install_state").edit().putLong("previous_version", 43).commit()
        IconPackInstaller.restoreState(context)
        assertEquals(BuildCoordinator.Phase.INSTALLING, BuildCoordinator.state.phase)
        assertTrue(IconPackInstaller.hasPendingOperation(context))
        assertNull(IconPackInstaller.pendingReapply(context))
        callback(PackageInstaller.STATUS_SUCCESS)
        assertEquals(BuildCoordinator.Phase.DONE, BuildCoordinator.state.phase)
        assertEquals(43L, IconPackInstaller.pendingReapplyVersion(context))
    }

    @Test fun confirmedInstallUsesIndependentFiniteJobWithBothFeaturesOff() {
        prefs("auto_update").edit().putBoolean("enabled", false).commit()
        prefs("theme_refresh").edit().putBoolean("enabled", false).commit()
        operation()
        installedPackage(PackageChangeReceiver.PACK_PACKAGE, 43)
        callback(PackageInstaller.STATUS_SUCCESS)
        assertEquals(43L, IconPackInstaller.pendingReapplyVersion(context))
        assertNotNull(context.getSystemService(JobScheduler::class.java).getPendingJob(IconPackSelectionJobService.JOB_ID))
        assertFalse(BackgroundMonitorService.isNeeded(context))
        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test fun olderSelectorAcknowledgementCannotConsumeNewerSameVersionReinstall() {
        operation()
        installedPackage(PackageChangeReceiver.PACK_PACKAGE, 43)
        callback(PackageInstaller.STATUS_SUCCESS)
        val first = IconPackInstaller.pendingReapply(context)!!
        operation(session = 72)
        callback(PackageInstaller.STATUS_SUCCESS, session = 72)
        val newer = IconPackInstaller.pendingReapply(context)!!
        assertEquals(first.version, newer.version)
        assertNotEquals(first.request, newer.request)
        IconPackInstaller.completeReapply(context, first)
        assertEquals(newer, IconPackInstaller.pendingReapply(context))
    }

    @Test fun userConfirmationIsPersistedWithoutBackgroundActivityLaunch() {
        operation()
        callback(PackageInstaller.STATUS_PENDING_USER_ACTION,
            confirmation = Intent("example.CONFIRM").putExtra("session_id", 71))
        assertEquals(BuildCoordinator.Phase.WAITING_CONFIRMATION, BuildCoordinator.state.phase)
        assertNotNull(prefs("install_state").getString("confirmation", null))
        assertNull(shadowOf(context as Application).nextStartedActivity)
        assertNull(IconPackInstaller.pendingApk(context))
        assertNull(prefs("package_monitor").getString("installed_fingerprint", null))
        callback(PackageInstaller.STATUS_FAILURE_ABORTED)
        assertEquals(BuildCoordinator.Phase.FAILED, BuildCoordinator.state.phase)
        assertNull(prefs("package_monitor").getString("installed_fingerprint", null))
    }

    @Test fun anOlderInstalledPackCannotCompletePendingUpdateAfterRecreation() {
        val oldPack = PackageInfo().apply {
            packageName = PackageChangeReceiver.PACK_PACKAGE
            setLongVersionCode(42)
        }
        shadowOf(context.packageManager).installPackage(oldPack)
        operation()
        IconPackInstaller.restoreState(context) // No session remains and only version 42 exists.
        assertEquals(BuildCoordinator.Phase.FAILED, BuildCoordinator.state.phase)
        assertNull(prefs("package_monitor").getString("installed_fingerprint", null))
    }

    @Test fun previousSuccessCannotOverwriteFailureOfANewerBuild() {
        operation()
        installedPackage(PackageChangeReceiver.PACK_PACKAGE, 43)
        callback(PackageInstaller.STATUS_SUCCESS)
        assertTrue(IconPackInstaller.beginBuild(context))
        BuildCoordinator.update { it.copy(phase = BuildCoordinator.Phase.FAILED, error = "Compilation failed") }
        IconPackInstaller.restoreState(context)
        assertEquals(BuildCoordinator.Phase.FAILED, BuildCoordinator.state.phase)
        assertNull(IconPackInstaller.lastStatus(context))
    }

    @Test fun stoppedWorkDoesNotAcknowledgeBuildOrInstalledFingerprint() {
        BuildCoordinator.start(context, "ui")
        BuildCoordinator.runPending(context) { true }
        assertEquals(0L, prefs("build_queue").getLong("completed", 0))
        assertNull(prefs("package_monitor").getString("installed_fingerprint", null))
    }

    @Test fun firstBaselineAndUnchangedPeriodicScansNeverBuild() {
        installedPackage()
        PackageChangeMonitor.reconcile(context)
        PackageChangeMonitor.reconcile(context)
        assertEquals(0L, prefs("build_queue").getLong("requested", 0))
        assertNotNull(prefs("package_monitor").getString("observed_packages", null))
        assertTrue(DirtyIconQueue.snapshot(context).entries.isEmpty())
    }

    @Test fun periodicReconciliationDetectsRealInstallAndUpdateAfterProcessStateReset() {
        PackageChangeMonitor.reconcile(context)
        installedPackage()
        BuildCoordinator.reset() // No in-memory package baseline is involved in detection.
        PackageChangeMonitor.reconcile(context)
        assertEquals(1L, prefs("build_queue").getLong("requested", 0))
        PackageChangeMonitor.reconcile(context)
        assertEquals(1L, prefs("build_queue").getLong("requested", 0))
        installedPackage(version = 2)
        BuildCoordinator.reset()
        PackageChangeMonitor.reconcile(context)
        assertEquals(2L, prefs("build_queue").getLong("requested", 0))
    }

    @Test fun coldStartRegistrationCannotConsumeOrDuplicateAuthoritativeInstall() {
        prefs("theme_refresh").edit().putBoolean("enabled", false).commit()
        installedPackage()
        PackageChangeMonitor.register(context)
        PackageChangeMonitor.register(context)
        change(Intent.ACTION_PACKAGE_ADDED)
        shadowOf(Looper.getMainLooper()).idle()
        change(Intent.ACTION_PACKAGE_ADDED) // Manifest and resident deliveries can overlap.
        assertEquals(1L, prefs("build_queue").getLong("requested", 0))
        assertFalse(BackgroundMonitorService.isNeeded(context))
    }

    @Test fun incompleteOnboardingConsumesEventsWithoutStartingAutomation() {
        prefs("onboarding").edit().clear().commit()
        PackageChangeMonitor.register(context)
        installedPackage()
        change(Intent.ACTION_PACKAGE_ADDED)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0L, prefs("build_queue").getLong("requested", 0))
        assertNull(context.getSystemService(JobScheduler::class.java).getPendingJob(PackageChangeMonitor.JOB_ID))
        assertFalse(BackgroundMonitorService.isNeeded(context))
        OnboardingState.markComplete(context)
        PackageChangeMonitor.register(context)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(context.getSystemService(JobScheduler::class.java).getPendingJob(PackageChangeMonitor.JOB_ID))
        assertEquals(0L, prefs("build_queue").getLong("requested", 0))
        installedPackage(version = 2)
        change(Intent.ACTION_PACKAGE_ADDED)
        assertEquals(1L, prefs("build_queue").getLong("requested", 0))
    }

    @Test fun removalCannotConsumeAnotherPackagesUnobservedInstall() {
        installedPackage(name = "example.removed")
        PackageChangeMonitor.reconcile(context)
        installedPackage(name = "example.new")
        shadowOf(context.packageManager).removePackage("example.removed")
        change(Intent.ACTION_PACKAGE_REMOVED, "example.removed")
        assertEquals(0L, prefs("build_queue").getLong("requested", 0))
        change(Intent.ACTION_PACKAGE_ADDED, "example.new")
        assertEquals(1L, prefs("build_queue").getLong("requested", 0))
        assertEquals(setOf("example.new"), DirtyIconQueue.snapshot(context).entries.keys
            .map { it.substringAfter(':') }.toSet())
    }

    @Test fun removalsAndConfigurationChangesOnlyConsumeBaseline() {
        installedPackage()
        PackageChangeMonitor.reconcile(context)
        ThemeColors.invalidate()
        PackageChangeMonitor.reconcile(context)
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        PackageChangeMonitor.reconcile(context)
        assertEquals(0L, prefs("build_queue").getLong("requested", 0))
        shadowOf(context.packageManager).removePackage("example.probe")
        change(Intent.ACTION_PACKAGE_REMOVED)
        PackageChangeMonitor.reconcile(context)
        assertEquals(0L, prefs("build_queue").getLong("requested", 0))
    }

    @Test fun enablingAutomaticUpdatesSeedsBaselineWithoutGenerating() {
        BuildCoordinator.setAutoUpdateEnabled(context, false)
        installedPackage()
        BuildCoordinator.setAutoUpdateEnabled(context, true)
        PackageChangeMonitor.reconcile(context)
        assertEquals(0L, prefs("build_queue").getLong("requested", 0))
        installedPackage(version = 2)
        PackageChangeMonitor.reconcile(context)
        assertEquals(1L, prefs("build_queue").getLong("requested", 0))
    }

    @Test fun stoppedQueueIsConsumedAndUnchangedPackagesCannotReplayIt() {
        PackageChangeMonitor.reconcile(context)
        installedPackage()
        change(Intent.ACTION_PACKAGE_ADDED)
        BuildCoordinator.start(context, "ui")
        val stale = BuildCoordinator.cancellation(context)
        BuildCoordinator.stopAll(context)
        assertEquals(2L, prefs("build_queue").getLong("completed", 0))
        assertEquals(BuildCoordinator.Phase.STOPPED, BuildCoordinator.state.phase)
        assertThrows(CancellationException::class.java) {
            stale.update { it.copy(phase = BuildCoordinator.Phase.DONE) }
        }
        BuildCoordinator.reset()
        BuildCoordinator.resumeQueued(context)
        PackageChangeMonitor.reconcile(context)
        change(Intent.ACTION_PACKAGE_ADDED) // Duplicate observation is already consumed.
        assertEquals(BuildCoordinator.Phase.STOPPED, BuildCoordinator.state.phase)
        assertEquals(2L, prefs("build_queue").getLong("requested", 0))
        assertNull(context.getSystemService(JobScheduler::class.java).getPendingJob(BuildCoordinator.JOB_ID))
        installedPackage(version = 2)
        PackageChangeMonitor.reconcile(context)
        assertEquals(3L, prefs("build_queue").getLong("requested", 0))
    }

    @Test fun authoritativeFirstInstallDoesNotWarmEveryPreexistingPackage() {
        installedPackage("example.existing")
        installedPackage("example.new")
        change(Intent.ACTION_PACKAGE_ADDED, "example.new")
        assertEquals(setOf("example.new"), DirtyIconQueue.snapshot(context).entries.keys
            .map { it.substringAfter(':') }.toSet())
        assertNotNull(context.getSystemService(JobScheduler::class.java).getPendingJob(BuildCoordinator.WARM_JOB_ID))
        assertNull(context.getSystemService(JobScheduler::class.java).getPendingJob(BuildCoordinator.JOB_ID))
        assertEquals(1L, prefs("build_queue").getLong("requested", 0))
    }

    @Test fun removalBeforeFirstBaselineDoesNotTurnExistingPackagesIntoInstalls() {
        installedPackage("example.existing")
        change(Intent.ACTION_PACKAGE_REMOVED, "example.absent")
        PackageChangeMonitor.reconcile(context)
        assertEquals(0L, prefs("build_queue").getLong("requested", 0))
        assertTrue(DirtyIconQueue.snapshot(context).entries.isEmpty())
        installedPackage("example.new")
        change(Intent.ACTION_PACKAGE_ADDED, "example.new")
        assertEquals(setOf("example.new"), DirtyIconQueue.snapshot(context).entries.keys
            .map { it.substringAfter(':') }.toSet())
        assertEquals(1L, prefs("build_queue").getLong("requested", 0))
    }

    @Test fun stopDiscardsPermissionRecoveryAndLateInstallerCallbacks() {
        val apk = File(context.filesDir, "stopped-fixture.apk").apply { writeBytes(byteArrayOf(1)) }
        try {
            prefs("install_state").edit().putString("stage", "permission")
                .putString("apk", apk.absolutePath).commit()
            BuildCoordinator.stopAll(context)
            assertNull(IconPackInstaller.pendingApk(context))
            assertFalse(IconPackInstaller.hasPendingOperation(context))
            IconPackInstaller.resumePendingInstall(context)
            IconPackInstaller.restoreState(context)
            assertEquals(BuildCoordinator.Phase.STOPPED, BuildCoordinator.state.phase)
        } finally { apk.delete() }

        operation()
        installedPackage(PackageChangeReceiver.PACK_PACKAGE, 43)
        BuildCoordinator.stopAll(context)
        callback(PackageInstaller.STATUS_SUCCESS)
        assertEquals(BuildCoordinator.Phase.STOPPED, BuildCoordinator.state.phase)
        assertTrue(BuildCoordinator.state.message.contains("may still finish"))
        assertNull(prefs("package_monitor").getString("installed_fingerprint", null))
    }

    @Test fun cancellationKillsAttachedCompilerAndPreventsInstallSubmission() {
        val cancellation = BuildCoordinator.cancellation(context)
        val child = ProcessBuilder("sh", "-c", "exec sleep 60").start()
        try {
            cancellation.attach(child)
            BuildCoordinator.stopAll(context)
            assertThrows(CancellationException::class.java) { cancellation.checkpoint() }
            assertTrue(child.waitFor(5, TimeUnit.SECONDS))
            assertThrows(CancellationException::class.java) {
                IconPackInstaller.install(context, File(context.filesDir, "never.apk"), cancellation = cancellation)
            }
            assertEquals(BuildCoordinator.Phase.STOPPED, BuildCoordinator.state.phase)
        } finally {
            child.destroyForcibly()
            child.waitFor()
            cancellation.detach(child)
        }
    }

    @Test fun previousSuccessCannotHideAQueuedUpdateAwaitingAndroid() {
        operation()
        installedPackage(PackageChangeReceiver.PACK_PACKAGE, 43)
        callback(PackageInstaller.STATUS_SUCCESS)
        BuildCoordinator.start(context, "ui")
        IconPackInstaller.restoreState(context)
        assertEquals(BuildCoordinator.Phase.QUEUED, BuildCoordinator.state.phase)
        assertEquals(1L, prefs("build_queue").getLong("requested", 0))
        assertEquals(0L, prefs("build_queue").getLong("completed", 0))

        BuildCoordinator.update { it.copy(phase = BuildCoordinator.Phase.COMPILING) }
        BuildCoordinator.waitingForScheduler(context)
        assertEquals(BuildCoordinator.Phase.QUEUED, BuildCoordinator.state.phase)
    }
}
