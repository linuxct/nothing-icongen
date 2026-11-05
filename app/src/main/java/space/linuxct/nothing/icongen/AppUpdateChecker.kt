package space.linuxct.nothing.icongen

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** Optional stable-release checks. Installation always remains an explicit user action. */
object AppUpdateChecker {
    private const val TAG = "AppUpdateChecker"
    const val CHANNEL_ID = "icongen_releases"
    private const val PREFS_KEY = "app_update_enabled"
    private const val DECISION_KEY = "app_update_decision"
    // The old last_check/last_release keys recorded failed checks and merely seen releases.
    private const val LAST_SUCCESS_KEY = "app_update_last_success"
    private const val NOTIFIED_VERSION_KEY = "app_update_notified_version"
    internal const val PERIODIC_JOB_ID = 4405
    internal const val DUE_JOB_ID = 4406
    internal const val INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val LATEST_URL = "https://api.github.com/repos/linuxct/nothing-icongen/releases/latest"
    private const val RELEASE_URL_PREFIX = "https://github.com/linuxct/nothing-icongen/releases/tag/"
    private const val DOWNLOAD_URL_PREFIX = "https://github.com/linuxct/nothing-icongen/releases/download/"

    internal val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    // Guarded, like notification posting and preference changes, by NotificationPreferences.
    private var generation = 0L

    private fun prefs(context: Context) =
        context.getSharedPreferences("app_update_prefs", Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREFS_KEY, false) && hasDecision(context)

    fun hasDecision(context: Context): Boolean = prefs(context).contains(DECISION_KEY)

    fun setEnabled(context: Context, enabled: Boolean) = synchronized(NotificationPreferences) {
        prefs(context).edit().putBoolean(PREFS_KEY, enabled).putBoolean(DECISION_KEY, true).apply()
        if (!enabled) generation++
        sync(context)
    }

    /** KEEP existing schedules: repeatedly opening the app must not postpone either job. */
    fun sync(context: Context) = sync(context, System.currentTimeMillis())

    internal fun sync(context: Context, now: Long) = synchronized(NotificationPreferences) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!isEnabled(context)) {
            scheduler.cancel(PERIODIC_JOB_ID)
            scheduler.cancel(DUE_JOB_ID)
            cancelNotification(context)
            return@synchronized
        }
        try {
            if (scheduler.getPendingJob(PERIODIC_JOB_ID) == null) {
                scheduler.schedule(jobBuilder(context, PERIODIC_JOB_ID).setPeriodic(INTERVAL_MS).build())
            }
            if (isDue(context, now) && scheduler.getPendingJob(DUE_JOB_ID) == null) {
                scheduler.schedule(jobBuilder(context, DUE_JOB_ID)
                    .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Scheduling failed", e)
        }
    }

    private fun jobBuilder(context: Context, id: Int) =
        JobInfo.Builder(id, ComponentName(context, UpdateCheckJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)

    fun isScheduled(context: Context): Boolean =
        context.getSystemService(JobScheduler::class.java).getPendingJob(PERIODIC_JOB_ID) != null

    internal fun isDue(context: Context, now: Long): Boolean {
        val last = prefs(context).getLong(LAST_SUCCESS_KEY, 0)
        return last <= 0 || now < last || now - last >= INTERVAL_MS
    }

    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "IconGen updates", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    fun cancelNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(PERIODIC_JOB_ID)
    }

    data class ReleaseInfo(
        val tagName: String,
        val title: String,
        val url: String,
        val releasedAt: String,
        val version: String
    )

    sealed interface CheckResult {
        data class Available(val release: ReleaseInfo) : CheckResult
        data class Current(val installedVersion: String) : CheckResult
        data object NoStableRelease : CheckResult
        data class Failed(val reason: String) : CheckResult
    }

    /** Manual checks never consult or change automatic-check/notification preferences or throttles. */
    fun checkNow(context: Context, callback: (CheckResult) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val result = fetchAndCompare(app)
            main.post { callback(result) }
        }
    }

    private fun fetchAndCompare(context: Context): CheckResult = try {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            ?: error("The installed app has no version name")
        if (UpdateVersion.parse(installed) == null) {
            CheckResult.Failed("The installed version could not be read: $installed")
        } else {
            fetchLatestRelease(installed)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Update check failed", e)
        CheckResult.Failed(e.message ?: "Could not check GitHub for updates")
    }

    private fun fetchLatestRelease(installed: String): CheckResult {
        val connection = URL(LATEST_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "IconGen-Android")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            return when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.bufferedReader().use {
                    evaluateRelease(it.readText(), installed)
                }
                HttpURLConnection.HTTP_NOT_FOUND -> CheckResult.NoStableRelease
                else -> CheckResult.Failed("GitHub returned HTTP $status. Please try again later.")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Read only root release metadata; asset names/uploader URLs cannot overwrite it. */
    internal fun parseRelease(json: String): ReleaseInfo? {
        val root = JSONObject(json)
        if (root.getBoolean("draft") || root.getBoolean("prerelease")) return null
        val tag = root.getString("tag_name")
        val version = UpdateVersion.fromTag(tag) ?: return null
        val assets = root.getJSONArray("assets")
        val hasApk = (0 until assets.length()).any { index ->
            val asset = assets.getJSONObject(index)
            asset.optString("name").endsWith(".apk", ignoreCase = true) &&
                asset.optString("browser_download_url").startsWith(DOWNLOAD_URL_PREFIX)
        }
        if (!hasApk) return null
        val url = root.getString("html_url")
        require(url.startsWith(RELEASE_URL_PREFIX)) { "GitHub returned an invalid release link" }
        return ReleaseInfo(tag, root.optString("name").ifBlank { tag }, url,
            root.getString("published_at"), version.text)
    }

    internal fun evaluateRelease(json: String, installedVersion: String): CheckResult = try {
        val installed = UpdateVersion.parse(installedVersion)
            ?: error("The installed version could not be read: $installedVersion")
        val release = parseRelease(json)
        when {
            release == null -> CheckResult.NoStableRelease
            UpdateVersion.parse(release.version)!! > installed -> CheckResult.Available(release)
            else -> CheckResult.Current(installedVersion)
        }
    } catch (e: Exception) {
        CheckResult.Failed(e.message ?: "GitHub returned an invalid release")
    }

    internal fun automaticGeneration(): Long = synchronized(NotificationPreferences) { generation }

    internal fun automaticAllowed(context: Context, expectedGeneration: Long): Boolean =
        synchronized(NotificationPreferences) { isEnabled(context) && generation == expectedGeneration }

    /** Returns whether a finite automatic job needs scheduler backoff; failures consume no interval. */
    internal fun checkAutomatically(
        context: Context,
        now: Long = System.currentTimeMillis(),
        isActive: () -> Boolean = { true },
        fetch: () -> CheckResult = { fetchAndCompare(context) }
    ): Boolean {
        synchronized(NotificationPreferences) {
            if (!isActive() || !isEnabled(context) || !isDue(context, now)) return false
        }
        val result = try {
            fetch()
        } catch (e: Exception) {
            CheckResult.Failed(e.message ?: "Update check failed")
        }
        return synchronized(NotificationPreferences) {
            if (!isActive() || !isEnabled(context)) return@synchronized false
            when (result) {
                is CheckResult.Failed -> true
                is CheckResult.Available -> {
                    val previouslyNotified = prefs(context).getString(NOTIFIED_VERSION_KEY, null)
                        ?.let(UpdateVersion::parse)
                    val version = UpdateVersion.parse(result.release.version)!!
                    val delivered = previouslyNotified?.compareTo(version) == 0 ||
                        postReleaseNotification(context, result.release)
                    if (delivered) {
                        prefs(context).edit().putLong(LAST_SUCCESS_KEY, now)
                            .putString(NOTIFIED_VERSION_KEY, result.release.version).apply()
                    }
                    // A blocked notification stays due for the next start/permission recovery.
                    false
                }
                else -> {
                    cancelNotification(context)
                    prefs(context).edit().putLong(LAST_SUCCESS_KEY, now).apply()
                    false
                }
            }
        }
    }

    /** Caller holds NotificationPreferences so opting out cannot race with a late notification. */
    private fun postReleaseNotification(context: Context, release: ReleaseInfo): Boolean {
        if (!NotificationPreferences.isEnabled(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(context)
        if (!manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE ||
            (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        ) return false
        return try {
            val intent = PendingIntent.getActivity(context, PERIODIC_JOB_ID,
                Intent(Intent.ACTION_VIEW, Uri.parse(release.url)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("IconGen update available")
                .setContentText(release.title)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentIntent(intent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            manager.notify(PERIODIC_JOB_ID, notification)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not post release notification", e)
            false
        }
    }
}

/** Numeric core components (including IconGen's fourth component) plus SemVer suffix precedence. */
internal class UpdateVersion private constructor(
    val text: String,
    private val core: List<String>,
    private val prerelease: List<String>
) : Comparable<UpdateVersion> {
    override fun compareTo(other: UpdateVersion): Int {
        for (index in 0 until maxOf(core.size, other.core.size)) {
            val difference = compareNumeric(core.getOrElse(index) { "0" }, other.core.getOrElse(index) { "0" })
            if (difference != 0) return difference
        }
        if (prerelease.isEmpty()) return if (other.prerelease.isEmpty()) 0 else 1
        if (other.prerelease.isEmpty()) return -1
        for (index in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val left = prerelease[index]
            val right = other.prerelease[index]
            val leftNumeric = left.all(Char::isDigit)
            val rightNumeric = right.all(Char::isDigit)
            val difference = when {
                leftNumeric && rightNumeric -> compareNumeric(left, right)
                leftNumeric -> -1
                rightNumeric -> 1
                else -> left.compareTo(right)
            }
            if (difference != 0) return difference
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val syntax = Regex("^(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*)){2,}(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?(\\+[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?$")
        private val buildTag = Regex("^build-(.+)-[0-9]+\\.[0-9]+$")

        fun fromTag(tag: String): UpdateVersion? {
            val version = if (tag.startsWith("build-")) {
                buildTag.matchEntire(tag)?.groupValues?.get(1) ?: return null
            } else tag
            return parse(version)
        }

        fun parse(value: String): UpdateVersion? {
            val text = value.removePrefix("v")
            if (!syntax.matches(text)) return null
            val withoutMetadata = text.substringBefore('+')
            val core = withoutMetadata.substringBefore('-').split('.')
            val prerelease = withoutMetadata.substringAfter('-', "").takeIf { it.isNotEmpty() }
                ?.split('.') ?: emptyList()
            if (prerelease.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
            return UpdateVersion(text, core, prerelease)
        }

        private fun compareNumeric(left: String, right: String): Int {
            val length = left.length.compareTo(right.length)
            return if (length != 0) length else left.compareTo(right)
        }
    }
}

/** Periodic wakeups enqueue finite work; only finite failures request scheduler backoff. */
class UpdateCheckJob : JobService() {
    private class Work(val generation: Long) {
        val active = AtomicBoolean(true)
        var future: Future<*>? = null
    }

    private val work = mutableMapOf<Int, Work>()
    private val main = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        if (!AppUpdateChecker.isEnabled(this)) return false
        if (params.jobId == AppUpdateChecker.PERIODIC_JOB_ID) {
            AppUpdateChecker.sync(this)
            return false
        }
        if (params.jobId != AppUpdateChecker.DUE_JOB_ID) return false
        val task = Work(AppUpdateChecker.automaticGeneration())
        work.put(params.jobId, task)?.let(::revoke)
        task.future = AppUpdateChecker.executor.submit {
            val retry = try {
                AppUpdateChecker.checkAutomatically(this, isActive = {
                    task.active.get() && AppUpdateChecker.automaticAllowed(this, task.generation)
                })
            } catch (e: Exception) {
                Log.w("UpdateCheckJob", "Background update check failed", e)
                true
            }
            main.post {
                if (work[params.jobId] === task) {
                    work.remove(params.jobId)
                    if (task.active.getAndSet(false) &&
                        AppUpdateChecker.automaticAllowed(this, task.generation)) {
                        jobFinished(params, retry)
                    }
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work.remove(params.jobId)?.let(::revoke)
        return params.jobId == AppUpdateChecker.DUE_JOB_ID && AppUpdateChecker.isEnabled(this)
    }

    override fun onDestroy() {
        work.values.forEach(::revoke)
        work.clear()
        super.onDestroy()
    }

    private fun revoke(task: Work) = synchronized(NotificationPreferences) {
        task.active.set(false)
        task.future?.cancel(true)
        Unit
    }
}
