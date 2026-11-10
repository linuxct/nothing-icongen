package space.linuxct.nothing.icongen

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

/** Pipeline invoked only by the serialized JobScheduler worker; installation completes asynchronously. */
object IconPackBuildService {
    private const val TAG = "IconPackBuildService"
    private const val CHANNEL = "iconpack_updates_silent"
    private const val CHANNEL_DONE = "iconpack_results_silent"
    private const val NOTIFICATION_ID = 42
    private const val RESULT_ID = 43
    private const val THEME_ACCESS_ID = 44
    private const val RENDER_ERRORS_ID = 45
    private const val NOTIFICATION_TIMEOUT_MS = 60_000L
    private const val BUILD_DIR_PREFIX = "autobuild_"
    private const val PROGRESS_THROTTLE_MS = 500L

    /** Android documents that updates posted many times per second can be dropped; routine updates are coalesced. */
    private var lastProgressPostedAt = 0L
    private var lastProgressPhase: BuildCoordinator.Phase? = null
    private var pendingProgress: Pair<Context, BuildCoordinator.State>? = null
    // The handler is created at schedule time: a process-looper-bound Handler outliving a test's main looper is a hazard.
    private var flushHandler: Handler? = null
    private val flushRunnable = Runnable { flushProgress() }

    enum class ResultKind { SUCCESS, ERROR, ACTION_REQUIRED }

    fun build(context: Context, cancellation: BuildCoordinator.Cancellation): File {
        Log.d(TAG, "Build started")
        ensureChannels(context)
        context.getSystemService(NotificationManager::class.java).apply {
            cancel(RESULT_ID)
            cancel(RENDER_ERRORS_ID)
        }
        cancellation.update {
            BuildCoordinator.State(phase = BuildCoordinator.Phase.SCANNING,
                message = "Scanning installed apps", percent = 5, startedAt = System.currentTimeMillis())
        }
        val observer: (BuildCoordinator.State) -> Unit = { state ->
            updateProgress(context, state)
        }
        BuildCoordinator.addListener(observer)
        deleteStaleBuildFolders(context)
        val outputRoot = context.getExternalFilesDir(null) ?: context.filesDir
        val outputDir = File(outputRoot, "$BUILD_DIR_PREFIX${System.currentTimeMillis()}")
        try {
            val renderFailures = ArrayList<Pair<String, Exception>>()
            val resources = try {
                IconGeneratorEngine.generate(
                    context = context.applicationContext,
                    outputDir = outputDir,
                    maxApps = 2000,
                    cancellation = cancellation,
                    renderFailures = renderFailures,
                )
            } finally {
                // Also when every app failed and generation threw: these are what the author needs to triage.
                postRenderErrors(context, renderFailures)
            }
            cancellation.update {
                it.copy(phase = BuildCoordinator.Phase.COMPILING, message = "Compiling APK", percent = 80)
            }
            cancellation.checkpoint()
            return IconPackAssembler.compileApk(context.applicationContext, resources, cancellation)
        } finally {
            outputDir.deleteRecursively()
            BuildCoordinator.removeListener(observer)
            cancelProgress(context)
        }
    }

    /**
     * Deletes build folders left by crashed runs and by older app versions, which never removed them.
     * Builds are serialized on one worker, so call this only from that worker: any folder found is stale.
     */
    fun deleteStaleBuildFolders(context: Context) {
        // Older builds fell back to filesDir when external storage was missing, so sweep both roots.
        listOfNotNull(context.getExternalFilesDir(null), context.filesDir).forEach { root ->
            root.listFiles { file ->
                file.name.startsWith(BUILD_DIR_PREFIX) || file.name.startsWith(".$BUILD_DIR_PREFIX")
            }?.forEach { it.deleteRecursively() }
        }
        context.cacheDir.listFiles { file -> file.name.startsWith(IconPackAssembler.COMPILE_DIR_PREFIX) }
            ?.forEach { it.deleteRecursively() }
    }

    fun updateProgress(context: Context, state: BuildCoordinator.State) = synchronized(BuildCoordinator) {
        if (state != BuildCoordinator.state) return@synchronized
        if (!state.isRunning || BuildCoordinator.isStopped(context)) {
            cancelProgress(context)
            return@synchronized
        }
        val elapsed = SystemClock.elapsedRealtime() - lastProgressPostedAt
        // Milestones (N/N) and every phase transition publish immediately, even when the percent is
        // unchanged; routine per-icon updates coalesce to ~2/s, retaining only the latest state.
        val counts = state.detail?.split('/')
        val forced = state.phase != lastProgressPhase || (counts?.size == 2 && counts[0] == counts[1])
        lastProgressPhase = state.phase
        flushHandler?.removeCallbacks(flushRunnable)
        flushHandler = null
        if (forced || elapsed >= PROGRESS_THROTTLE_MS) {
            pendingProgress = null
            lastProgressPostedAt = SystemClock.elapsedRealtime()
            postProgress(context, state)
        } else {
            pendingProgress = context to state
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed(flushRunnable, PROGRESS_THROTTLE_MS - elapsed)
            flushHandler = handler
        }
    }

    private fun flushProgress() = synchronized(BuildCoordinator) {
        val pending = pendingProgress ?: return
        val state = BuildCoordinator.state
        // A terminal state arrives through updateProgress, which clears the queue before this runs.
        if (!state.isRunning || BuildCoordinator.isStopped(pending.first)) return
        pendingProgress = null
        lastProgressPostedAt = SystemClock.elapsedRealtime()
        lastProgressPhase = state.phase
        postProgress(pending.first, state)
    }

    fun cancelProgress(context: Context) = synchronized(BuildCoordinator) {
        pendingProgress = null
        lastProgressPhase = null
        lastProgressPostedAt = 0L
        flushHandler?.removeCallbacks(flushRunnable)
        flushHandler = null
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    fun cancelNotifications(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
        manager.cancel(RESULT_ID)
        manager.cancel(THEME_ACCESS_ID)
        manager.cancel(RENDER_ERRORS_ID)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun postProgress(context: Context, state: BuildCoordinator.State) = synchronized(NotificationPreferences) {
        if (!canNotify(context)) return@synchronized
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_sync_animated)
            .setLargeIcon(NotificationArtwork.state(context, R.drawable.ic_notification_sync_animated))
            .setContentTitle("Updating icon pack")
            .setContentText(state.message)
            .setContentIntent(openApp(context))
            .setPriority(Notification.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setDefaults(0)
            .setSound(null)
            .setProgress(100, state.percent.coerceIn(0, 100), false)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
            .apply {
                if (Build.VERSION.SDK_INT >= 36) {
                    setStyle(Notification.ProgressStyle()
                        .setProgress(state.percent.coerceIn(0, 100))
                        .setProgressSegments(listOf(
                            Notification.ProgressStyle.Segment(5),
                            Notification.ProgressStyle.Segment(75),
                            Notification.ProgressStyle.Segment(12),
                            Notification.ProgressStyle.Segment(8),
                        ))
                        .setStyledByProgress(true))
                }
            }
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    fun cancelThemeAccessReminder(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(THEME_ACCESS_ID)
    }

    internal fun cancelResult(context: Context) = synchronized(NotificationPreferences) {
        context.getSystemService(NotificationManager::class.java).cancel(RESULT_ID)
    }

    fun postThemeAccessReminder(context: Context, failure: String) = synchronized(ThemeRefreshController) {
        if (!OnboardingState.isComplete(context) || !ThemeRefreshController.isEnabled(context)) return@synchronized
        val action = PendingIntent.getActivity(
            context, THEME_ACCESS_ID,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_THEME_ACCESS, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        postNotice(context, THEME_ACCESS_ID, "Automatic theme refresh needs Settings access",
            "$failure\nRun the PC commands or use IconGen's built-in wireless ADB setup. " +
                "Automatic light/dark icon refresh cannot work until Settings access is available.",
            ResultKind.ACTION_REQUIRED, action)
    }

    fun postResult(
        context: Context,
        title: String,
        message: String,
        kind: ResultKind,
        action: PendingIntent? = null,
    ) = postNotice(context, RESULT_ID, title, message, kind, action)

    private fun postRenderErrors(context: Context, failures: List<Pair<String, Exception>>) {
        if (failures.isEmpty()) return
        val title = if (failures.size == 1) "1 icon could not be generated"
            else "${failures.size} icons could not be generated"
        // No timeout: the user needs time to screenshot it, even after an unattended rebuild.
        postNotice(context, RENDER_ERRORS_ID, title, renderErrorReport(failures),
            ResultKind.ERROR, action = null, expires = false)
    }

    /** Package, exception and its top two stack frames per app, as the author asked for in bug reports. */
    internal fun renderErrorReport(failures: List<Pair<String, Exception>>): String =
        "Take a screenshot of this error and submit it to the author.\n\n" +
            failures.joinToString("\n\n") { (component, e) ->
                (listOf(component, e.toString()) + e.stackTrace.take(2).map { "  at $it" }).joinToString("\n")
            }

    private fun postNotice(
        context: Context,
        id: Int,
        title: String,
        message: String,
        kind: ResultKind,
        action: PendingIntent?,
        expires: Boolean = true,
    ) = synchronized(NotificationPreferences) {
        if (!canNotify(context)) return@synchronized
        ensureChannels(context)
        val icon = when (kind) {
            ResultKind.SUCCESS -> R.drawable.ic_notification_success
            ResultKind.ERROR -> R.drawable.ic_notification_error
            ResultKind.ACTION_REQUIRED -> R.drawable.ic_notification_waiting
        }
        val notification = Notification.Builder(context, CHANNEL_DONE)
            .setSmallIcon(icon)
            .setLargeIcon(NotificationArtwork.state(context, icon))
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(action ?: openApp(context))
            .setPriority(Notification.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setDefaults(0)
            .setSound(null)
            .setAutoCancel(true)
            .apply { if (expires) setTimeoutAfter(NOTIFICATION_TIMEOUT_MS) }
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun canNotify(context: Context): Boolean =
        NotificationPreferences.isEnabled(context) &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        createSilentChannel(manager, CHANNEL, "Icon pack updates",
            "Silent icon generation progress", "iconpack_updates")
        createSilentChannel(manager, CHANNEL_DONE, "Icon pack results",
            "Silent icon pack installation results and access requests", "iconpack_results")
    }

    private fun createSilentChannel(
        manager: NotificationManager,
        id: String,
        name: String,
        description: String,
        legacyId: String,
    ) {
        val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW).apply {
            this.description = description
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        // Existing channel sound settings cannot be changed by recreating that ID.
        // Migrate to the silent channels, preserving an explicit user block.
        if (manager.getNotificationChannel(legacyId)?.importance == NotificationManager.IMPORTANCE_NONE) {
            channel.importance = NotificationManager.IMPORTANCE_NONE
        }
        manager.createNotificationChannel(channel)
        manager.deleteNotificationChannel(legacyId)
    }
}
