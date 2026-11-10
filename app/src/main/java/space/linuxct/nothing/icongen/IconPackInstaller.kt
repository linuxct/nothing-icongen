package space.linuxct.nothing.icongen

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.CancellationException

/** One durable operation, from install-access gate through the final session result. */
object IconPackInstaller {
    private const val TAG = "IconPackInstaller"
    private const val PREFS = "install_state"
    private const val STAGE = "stage"
    private const val STATUS = "status"
    private const val APK = "apk"
    private const val SESSION = "session"
    private const val EXPECTED_VERSION = "expected_version"
    private const val PREVIOUS_VERSION = "previous_version"
    private const val CONFIRMATION = "confirmation"
    private const val BUILD_REQUEST = "build_request"
    private const val SNAPSHOT = "build_fingerprint"
    private const val REAPPLY_VERSION = "reapply_version"
    private const val REAPPLY_REQUEST = "reapply_request"
    private const val PERMISSION = "permission"
    private const val INSTALLING = "installing"
    private const val CONFIRMING = "confirming"
    private const val SUCCESS = "success"
    private const val FAILED = "failed"
    private const val STOPPED = "stopped"
    private const val CALLBACK_SESSION = "operation_session"
    const val ACTION_INSTALL_STATUS = "space.linuxct.nothing.icongen.INSTALL_STATUS"

    @Volatile var statusListener: ((String) -> Unit)? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun pendingReapplyVersion(context: Context): Long? =
        prefs(context).getLong(REAPPLY_VERSION, -1).takeIf { it >= 0 }

    internal data class ReapplyRequest(val version: Long, val request: Long)

    @Synchronized
    internal fun pendingReapply(context: Context): ReapplyRequest? = pendingReapplyVersion(context)?.let {
        ReapplyRequest(it, prefs(context).getLong(REAPPLY_REQUEST, 0))
    }

    internal fun canReapplyInstalledPack(context: Context): Boolean =
        pendingReapplyVersion(context)?.let { installedVersion(context) == it } == true

    /** Version AND operation identity prevent consuming a newer same-version reinstall. */
    @Synchronized
    internal fun completeReapply(context: Context, request: ReapplyRequest) {
        val prefs = prefs(context)
        if (pendingReapply(context) == request) {
            check(prefs.edit().remove(REAPPLY_VERSION).commit()) { "Could not acknowledge icon pack reapply" }
        }
    }

    @Synchronized
    fun hasPendingOperation(context: Context): Boolean =
        prefs(context).getString(STAGE, null) in setOf(PERMISSION, INSTALLING, CONFIRMING)

    /** A new build invalidates the previous operation's terminal result, including after failure. */
    @Synchronized
    fun beginBuild(context: Context, cancellation: BuildCoordinator.Cancellation = BuildCoordinator.cancellation(context)): Boolean {
        cancellation.checkpoint()
        if (hasPendingOperation(context)) return false
        val prefs = prefs(context)
        val reapplyVersion = prefs.getLong(REAPPLY_VERSION, -1)
        val reapplyRequest = prefs.getLong(REAPPLY_REQUEST, 0)
        prefs.edit().clear().apply {
            if (reapplyVersion >= 0) putLong(REAPPLY_VERSION, reapplyVersion)
            putLong(REAPPLY_REQUEST, reapplyRequest)
        }.commit()
        return true
    }

    /** Android may already own a committed install; abandoning cannot promise rollback. */
    @Synchronized
    fun stopPending(context: Context): String {
        val prefs = prefs(context)
        val stage = prefs.getString(STAGE, null)
        if (stage == STOPPED) return prefs.getString(STATUS, null) ?: "All generation stopped"
        val session = prefs.getInt(SESSION, -1)
        val submitted = stage == INSTALLING || stage == CONFIRMING
        if (session >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(session) }
        val message = if (submitted) {
            "Generation stopped. Installation was already submitted to Android and may still finish."
        } else "All generation stopped"
        prefs.edit().putString(STAGE, STOPPED).putString(STATUS, message)
            .putInt(SESSION, -1).remove(CONFIRMATION).remove(REAPPLY_VERSION).commit()
        statusListener?.invoke(message)
        return message
    }

    /** Only an APK not yet submitted to PackageInstaller is resumable as an APK. */
    @Synchronized
    fun pendingApk(context: Context): File? {
        val prefs = prefs(context)
        if (prefs.getString(STAGE, null) != PERMISSION) return null
        return prefs.getString(APK, null)?.let(::File)?.takeIf { it.isFile }
    }

    @Synchronized
    fun install(context: Context, apkFile: File, buildRequest: Long = -1, fingerprint: String? = null,
                cancellation: BuildCoordinator.Cancellation = BuildCoordinator.cancellation(context)) {
        cancellation.checkpoint()
        val context = context.applicationContext
        val prefs = prefs(context)
        if (buildRequest < 0) BuildCoordinator.prepareManualInstall(context, cancellation)
        if (prefs.getString(STAGE, null) in setOf(INSTALLING, CONFIRMING)) {
            restoreState(context)
            return
        }
        check(apkFile.isFile) { "APK does not exist: ${apkFile.absolutePath}" }
        val savedApk = File(context.filesDir, "pending_iconpack.apk")
        if (apkFile.absolutePath != savedApk.absolutePath) {
            apkFile.inputStream().use { input ->
                savedApk.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        cancellation.checkpoint()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(savedApk.absolutePath, 0)
            ?: error("Generated APK cannot be inspected")
        check(archive.packageName == PackageChangeReceiver.PACK_PACKAGE) { "Unexpected generated package ${archive.packageName}" }
        cancellation.checkpoint()
        prefs.edit().putString(APK, savedApk.absolutePath)
            .putLong(EXPECTED_VERSION, archive.longVersionCode)
            .putLong(PREVIOUS_VERSION, installedVersion(context))
            .putLong(BUILD_REQUEST, buildRequest).putString(SNAPSHOT, fingerprint)
            .putInt(SESSION, -1).remove(CONFIRMATION)
            .putString(STAGE, PERMISSION)
            .putString(STATUS, "Allow this app to install icon packs, then resume installation").commit()
        if (buildRequest >= 0) BuildCoordinator.completeRequest(context, buildRequest)
        if (!InstallAccess.canInstall(context)) {
            publish(context, cancellation)
            cancellation.commit {
                IconPackBuildService.postResult(context, "Install access needed",
                    "Open the generator to allow icon pack installation", IconPackBuildService.ResultKind.ACTION_REQUIRED)
            }
            return
        }

        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(PackageChangeReceiver.PACK_PACKAGE)
                setSize(savedApk.length())
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                setInstallerPackageName(context.packageName)
                setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER)
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
                if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
            }
            sessionId = installer.createSession(params)
            cancellation.checkpoint()
            installer.openSession(sessionId).use { session ->
                session.openWrite("iconpack.apk", 0, savedApk.length()).use { output ->
                    savedApk.inputStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            cancellation.checkpoint()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                    session.fsync(output)
                }
                cancellation.checkpoint()
                // Persist BEFORE commit: a fast callback must never be overwritten by submitted state.
                prefs.edit().putInt(SESSION, sessionId).putString(STAGE, INSTALLING)
                    .putString(STATUS, "Install submitted; waiting for Android").commit()
                publish(context, cancellation)
                cancellation.commit { session.commit(statusPendingIntent(context, sessionId).intentSender) }
            }
            Log.d(TAG, "Committed session $sessionId version ${archive.longVersionCode}")
        } catch (failure: Exception) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            if (failure is CancellationException) {
                prefs.edit().putInt(SESSION, -1).putString(STAGE, PERMISSION)
                    .putString(STATUS, "Installation interrupted; tap Install to retry").commit()
                throw failure
            }
            finish(context, false, "Install failed: ${failure.message}")
            throw failure
        }
    }

    private fun statusPendingIntent(context: Context, sessionId: Int): PendingIntent =
        PendingIntent.getBroadcast(context, sessionId,
            Intent(context, InstallationReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
                .putExtra(CALLBACK_SESSION, sessionId),
            // A session sends PENDING_USER_ACTION and then a final callback; this is not one-shot.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    @Synchronized
    fun resumePendingInstall(context: Context,
                             cancellation: BuildCoordinator.Cancellation = BuildCoordinator.cancellation(context)) {
        cancellation.checkpoint()
        restoreState(context)
        val prefs = prefs(context)
        if (prefs.getString(STAGE, null) == CONFIRMING) {
            val confirmation = confirmationIntent(context) ?: return
            cancellation.commit { context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } else {
            val apk = pendingApk(context) ?: return
            if (InstallAccess.canInstall(context))
                install(context, apk, prefs.getLong(BUILD_REQUEST, -1), prefs.getString(SNAPSHOT, null), cancellation)
        }
    }

    /** Restore only this operation; mere existence of an older installed pack is never success. */
    @Synchronized
    fun restoreState(context: Context) {
        val prefs = prefs(context)
        val stage = prefs.getString(STAGE, null) ?: return
        if (BuildCoordinator.isStopped(context) && stage != STOPPED) {
            stopPending(context)
            publish(context)
            return
        }
        val buildRequest = prefs.getLong(BUILD_REQUEST, -1)
        if (buildRequest >= 0) BuildCoordinator.completeRequest(context, buildRequest)
        if (stage == INSTALLING || stage == CONFIRMING) {
            val sessionId = prefs.getInt(SESSION, -1)
            val session = context.packageManager.packageInstaller.getSessionInfo(sessionId)
            if (session == null) {
                val expected = prefs.getLong(EXPECTED_VERSION, -1)
                val previous = prefs.getLong(PREVIOUS_VERSION, -1)
                val installed = installedVersion(context)
                if (expected > previous && installed == expected) {
                    finish(context, true, "Icon pack installed")
                } else if (expected >= 0 && expected == previous && installed == expected) {
                    // Existence cannot prove a reinstall, but session disappearance must not
                    // discard an authoritative success callback still queued by Android.
                    prefs.edit().putString(STATUS, "Waiting for Android to confirm the same-version installation").commit()
                    publish(context)
                } else {
                    finish(context, false, "Install ended without success; generate or install again")
                }
                return
            }
            if (!session.isSealed) {
                // Process stopped before commit. Keep the APK, but do not submit a second live session.
                context.packageManager.packageInstaller.abandonSession(sessionId)
                prefs.edit().putString(STAGE, PERMISSION).putInt(SESSION, -1)
                    .putString(STATUS, "Installation interrupted; tap Install to retry").commit()
            }
        }
        publish(context)
    }

    fun lastStatus(context: Context, pendingOnly: Boolean = false): String? {
        restoreState(context)
        if (pendingOnly && !hasPendingOperation(context)) return null
        return prefs(context).getString(STATUS, null)
    }

    private fun installedVersion(context: Context): Long = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(PackageChangeReceiver.PACK_PACKAGE, 0).longVersionCode
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) { -1L }

    private fun confirmationIntent(context: Context): Intent? =
        prefs(context).getString(CONFIRMATION, null)?.let { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }

    private fun publish(context: Context, cancellation: BuildCoordinator.Cancellation? = null) {
        val prefs = prefs(context)
        val stage = prefs.getString(STAGE, null) ?: return
        if (BuildCoordinator.state.phase == BuildCoordinator.Phase.STOPPED && stage != STOPPED) return
        if (BuildCoordinator.state.phase == BuildCoordinator.Phase.QUEUED &&
            (stage == SUCCESS || stage == FAILED || stage == STOPPED)) return
        if (BuildCoordinator.state.phase in setOf(BuildCoordinator.Phase.SCANNING,
                BuildCoordinator.Phase.GENERATING, BuildCoordinator.Phase.COMPILING) &&
            stage in setOf(SUCCESS, FAILED)) return
        val message = prefs.getString(STATUS, "") ?: ""
        val phase = when (stage) {
            PERMISSION -> BuildCoordinator.Phase.WAITING_PERMISSION
            INSTALLING -> BuildCoordinator.Phase.INSTALLING
            CONFIRMING -> BuildCoordinator.Phase.WAITING_CONFIRMATION
            SUCCESS -> BuildCoordinator.Phase.DONE
            STOPPED -> BuildCoordinator.Phase.STOPPED
            else -> BuildCoordinator.Phase.FAILED
        }
        val update: (BuildCoordinator.State) -> BuildCoordinator.State = {
            if (BuildCoordinator.isStopped(context) && stage != STOPPED) it else {
                it.copy(phase = phase, message = message, percent = when (stage) {
                    SUCCESS -> 100
                    STOPPED -> 0
                    else -> 92
                }, apkPath = if (stage == STOPPED) null else prefs.getString(APK, null),
                    error = if (stage == FAILED) message else null)
            }
        }
        if (cancellation != null) cancellation.update(update) else BuildCoordinator.update(update)
        IconPackBuildService.updateProgress(context, BuildCoordinator.state)
        statusListener?.invoke(message)
    }

    private fun finish(context: Context, success: Boolean, message: String) = synchronized(BuildCoordinator) {
        if (BuildCoordinator.isStopped(context)) {
            stopPending(context)
            return@synchronized
        }
        val prefs = prefs(context)
        val expected = prefs.getLong(EXPECTED_VERSION, -1)
        val verified = success && expected >= 0 && installedVersion(context) == expected
        val resultMessage = if (success && !verified) {
            "Install ended without the expected updated icon pack; generate or install again"
        } else message
        Log.d(TAG, "Install result success=$verified session=${prefs.getInt(SESSION, -1)} expectedVersion=$expected message=$resultMessage")
        // The refresh request and success acknowledgement must survive process death together.
        check(prefs.edit().putString(STAGE, if (verified) SUCCESS else FAILED)
            .putString(STATUS, resultMessage).remove(CONFIRMATION).apply {
                if (verified) {
                    putLong(REAPPLY_VERSION, expected)
                    putLong(REAPPLY_REQUEST, prefs.getLong(REAPPLY_REQUEST, 0) + 1)
                }
            }.commit()) { "Could not persist installation result" }
        if (verified) {
            IconPackSelectionJobService.scheduleConfirmedInstall(context)
            prefs.getString(SNAPSHOT, null)?.let { PackageChangeMonitor.installed(context, it) }
        }
        publish(context)
        if (verified) {
            IconPackBuildService.cancelResult(context)
        } else {
            IconPackBuildService.postResult(context, "Icon pack install failed",
                resultMessage, IconPackBuildService.ResultKind.ERROR)
        }
        BuildCoordinator.resumeQueued(context)
    }

    class InstallationReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            synchronized(IconPackInstaller) {
                val prefs = prefs(context)
                val sessionId = intent.getIntExtra(CALLBACK_SESSION, -1)
                if (sessionId < 0 || sessionId != prefs.getInt(SESSION, -1)) return
                if (prefs.getString(STAGE, null) !in setOf(INSTALLING, CONFIRMING)) return
                if (BuildCoordinator.isStopped(context)) {
                    stopPending(context)
                    return
                }
                Log.d(TAG, "Session callback session=$sessionId status=${intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)}")
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirm == null) {
                            finish(context, false, "Android supplied no installation confirmation")
                            return
                        }
                        prefs.edit().putString(STAGE, CONFIRMING)
                            .putString(STATUS, "Waiting for install confirmation")
                            .putString(CONFIRMATION, confirm.toUri(Intent.URI_INTENT_SCHEME)).commit()
                        publish(context)
                        val action = PendingIntent.getActivity(context, sessionId,
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        // A direct activity PendingIntent is user-initiated, not a background launch.
                        IconPackBuildService.postResult(context, "Confirm icon pack installation",
                            "Tap to approve the Android installer, or open the generator",
                            IconPackBuildService.ResultKind.ACTION_REQUIRED, action)
                    }
                    PackageInstaller.STATUS_SUCCESS -> finish(context, true, "Icon pack installed")
                    else -> finish(context, false,
                        "Install failed (status $status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android rejected installation"}")
                }
            }
        }
    }
}
