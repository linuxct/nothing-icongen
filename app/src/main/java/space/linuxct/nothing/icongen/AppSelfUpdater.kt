package space.linuxct.nothing.icongen

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.CancellationException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Consent and durable outcome state are deliberately separate from icon-pack installation. */
internal object AppSelfUpdater {
    const val PREFS = "app_self_update"
    enum class Phase { IDLE, DISCOVERING, DOWNLOADING, VERIFYING, INSTALLING, DONE, FAILED }
    data class Status(val phase: Phase, val message: String, val version: String?, val needsWirelessSetup: Boolean, val downloadPercent: Int)
    private const val EXPECTED = "expected_version"
    private const val EXPECTED_CODE = "expected_version_code"
    private const val HANDOFF_AT = "handoff_at"
    private const val MAX_WORK_MS = 10L * 60 * 1000
    private const val PAIR_GUIDANCE = "Enable Wireless debugging on this phone. If authorization was revoked, pair IconGen again in Wireless ADB setup."
    private val main = Handler(Looper.getMainLooper())

    internal class Operation(val release: AppUpdateChecker.ReleaseInfo) {
        @Volatile var cancelled: String? = null
        @Volatile var committed = false
        @Volatile var started = false
        @Volatile var connection: HttpURLConnection? = null
        @Volatile var client: WirelessAdbClient? = null
        @Volatile var worker: Thread? = null
        val deadline = SystemClock.elapsedRealtime() + MAX_WORK_MS
        var timeout: Runnable? = null
    }
    private var operation: Operation? = null
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized fun status(context: Context): Status {
        val prefs = prefs(context)
        return Status(runCatching { Phase.valueOf(prefs.getString("phase", "IDLE")!!) }.getOrDefault(Phase.IDLE),
            prefs.getString("message", "Updates install only after you choose Update now.").orEmpty(),
            prefs.getString("version", null), prefs.getBoolean("wireless_setup_required", false),
            prefs.getInt("download_percent", 0))
    }

    @Synchronized fun isRunning(context: Context): Boolean = operation != null

    /** Unknown is not enabled or authorized; only an explicit off state can fast-fail discovery. */
    internal fun wirelessDebuggingState(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", -1)
    } catch (_: RuntimeException) {
        -1
    }

    internal fun requireWirelessDebuggingNotDisabled(context: Context) {
        check(wirelessDebuggingState(context) != 0) {
            "Wireless debugging is off. Enable Wireless debugging on this phone and try again. If Android removed IconGen's authorization, pair IconGen again in Wireless ADB setup."
        }
    }

    @Synchronized fun start(context: Context, release: AppUpdateChecker.ReleaseInfo): Boolean {
        val app = context.applicationContext
        operation?.let { return it.release == release && it.cancelled == null }
        var needsWirelessSetup = false
        try {
            require(Build.VERSION.SDK_INT >= 34) { "This phone's Wireless ADB update discovery requires Android 14 or later." }
            val installed = app.packageManager.getPackageInfo(app.packageName, 0)
            AppUpdateArtifact.validateRelease(release, installed.versionName.orEmpty())
            needsWirelessSetup = true
            requireWirelessDebuggingNotDisabled(app)
            check(WirelessAdbAccess.hasIdentity(app)) { "Pair IconGen once in Wireless ADB setup before installing updates. Settings grants alone do not allow app installation." }
            needsWirelessSetup = false
            val lastHandoff = prefs(app).getLong(HANDOFF_AT, 0)
            check(!prefs(app).contains(EXPECTED) || System.currentTimeMillis() - lastHandoff >= 180_000L) {
                "A previous installer handoff is still unresolved. Wait a few minutes, reopen IconGen, and check the installed version before retrying."
            }
            val task = Operation(release)
            operation = task
            update(app, Phase.DISCOVERING, "Preparing the verified update. $PAIR_GUIDANCE", release.version)
            val timeout = Runnable {
                abort(task, "The update timed out. $PAIR_GUIDANCE", evenAfterCommit = true)
                synchronized(this) {
                    if (operation === task && !task.started) {
                        operation = null
                        update(app, Phase.FAILED, "Android did not start the update service. Open IconGen and try again.", release.version)
                    }
                }
            }
            task.timeout = timeout
            main.postDelayed(timeout, MAX_WORK_MS)
            try {
                val intent = Intent(app, AppSelfUpdateService::class.java)
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
            } catch (error: Exception) {
                operation = null
                main.removeCallbacks(timeout)
                throw error
            }
            return true
        } catch (error: Exception) {
            update(app, Phase.FAILED, error.message ?: "Android could not start the update service. Open IconGen and try again.", release.version, needsWirelessSetup)
            return false
        }
    }

    @Synchronized fun cancel(context: Context) {
        operation?.let { if (!it.committed) abort(it, "Update cancelled.") }
    }


    private fun abort(task: Operation, message: String, evenAfterCommit: Boolean = false) {
        synchronized(this) {
            if (task.committed && !evenAfterCommit) return
            task.cancelled = message
        }
        task.connection?.disconnect()
        task.client?.cancel(message)
        task.worker?.interrupt()
    }

    @Synchronized internal fun claim(): Operation? = operation?.takeIf { !it.started }?.also { it.started = true }

    @Synchronized internal fun startupFailed(context: Context, error: Exception) {
        val task = operation?.takeIf { !it.started } ?: return
        task.timeout?.let(main::removeCallbacks)
        operation = null
        update(context, Phase.FAILED, "Android could not run the temporary update service: ${error.message.orEmpty()}. Open IconGen and try again.", task.release.version)
    }

    internal fun serviceStopped(task: Operation) {
        if (!task.committed) abort(task, "Android stopped the update before installation. Reopen IconGen and try again.")
    }

    @Synchronized internal fun finished(context: Context, task: Operation) {
        if (operation === task) {
            operation = null
            // Wake the status UI after resource cleanup, not just when the terminal phase is set.
            prefs(context).edit().putBoolean("running", false).apply()
        }
    }

    private fun checkActive(task: Operation) {
        task.cancelled?.let { throw CancellationException(it) }
        if (SystemClock.elapsedRealtime() >= task.deadline) throw CancellationException("The update timed out. $PAIR_GUIDANCE")
    }

    internal fun execute(context: Context, task: Operation, changed: () -> Unit) {
        task.worker = Thread.currentThread()
        var apk: File? = null
        var wirelessStage = false
        try {
            checkActive(task)
            apk = File.createTempFile("icongen-self-update-", ".apk", context.cacheDir)
            update(context, Phase.DOWNLOADING, "Downloading IconGen ${task.release.version} from the official release.", task.release.version)
            changed()
            var lastPercent = 0
            var lastReportAt = 0L
            AppUpdateArtifact.download(task.release, apk, { checkActive(task) }, { connection ->
                task.connection = connection
                if (task.cancelled != null) connection?.disconnect()
            }) { copied, total ->
                val percent = (copied * 100 / total).toInt()
                // Coalesce to whole percents at ~2/s: Android drops notification updates posted many times per second.
                if (percent > lastPercent && SystemClock.elapsedRealtime() - lastReportAt >= 500L) {
                    lastPercent = percent
                    lastReportAt = SystemClock.elapsedRealtime()
                    setDownloadPercent(context, percent)
                    changed()
                }
            }
            checkActive(task)
            update(context, Phase.VERIFYING, "Checking the APK signature, package, version and installed signing certificate.", task.release.version)
            changed()
            val verified = AppUpdateArtifact.verify(context, task.release, apk)
            checkActive(task)
            wirelessStage = true
            update(context, Phase.DISCOVERING, "Finding this phone's paired Wireless ADB connection. $PAIR_GUIDANCE", task.release.version)
            changed()
            val candidates = discover(context, task)
            var lastFailure: Throwable? = null
            for (endpoint in candidates) {
                checkActive(task)
                val client = WirelessAdbClient(context)
                task.client = client
                try {
                    checkActive(task)
                    wirelessStage = true
                    client.install(endpoint, apk) {
                        synchronized(this) {
                            checkActive(task)
                            requireWirelessDebuggingNotDisabled(context)
                            wirelessStage = false
                            AppUpdateArtifact.recheckInstalled(context, task.release, verified)
                            // Persist before handoff: a successful in-app update kills this process.
                            check(prefs(context).edit().putString(EXPECTED, task.release.version)
                                .putLong(EXPECTED_CODE, verified.versionCode).putLong(HANDOFF_AT, System.currentTimeMillis())
                                .putString("phase", Phase.INSTALLING.name).putString("version", task.release.version)
                                .putString("message", "Verified update handed to Android. Installation is confirmed only after the installed version changes.")
                                .commit()) { "Could not persist the expected update version; installation was not started." }
                            task.committed = true
                        }
                        changed()
                    }
                    lastFailure = null
                    break
                } catch (error: Exception) {
                    lastFailure = error
                    // Retrying after handoff could start two installers; never do that.
                    if (task.committed) throw error
                } finally {
                    task.client = null
                }
            }
            lastFailure?.let { throw it }
            check(task.committed) { "No authorized Wireless ADB connection was available. $PAIR_GUIDANCE" }
            // pm exit 0 is not enough. Package replacement usually terminates us before this point.
            if (!confirmInstalled(context)) {
                update(context, Phase.FAILED, "Android accepted the installer, but the installed version has not been confirmed. Reopen IconGen to verify the outcome before retrying.", task.release.version)
            }
        } catch (error: Throwable) {
            if (error !is Exception && error !is LinkageError) throw error
            val message = if (error is WirelessAdbInstallRejectedException) {
                prefs(context).edit().remove(EXPECTED).remove(EXPECTED_CODE).remove(HANDOFF_AT).commit()
                "Android rejected the verified update: ${error.message.orEmpty()}. Check for updates again after resolving the installer error."
            } else if (task.committed) {
                "Installation outcome is not confirmed: ${error.message.orEmpty()}. Reopen IconGen to verify the installed version before retrying."
            } else if (error is WirelessAdbUnavailableException) {
                error.message.orEmpty()
            } else {
                "${task.cancelled ?: error.message ?: "Update failed"} ${if (task.client != null || status(context).phase == Phase.DISCOVERING) PAIR_GUIDANCE else ""}".trim()
            }
            update(context, Phase.FAILED, message, task.release.version,
                needsWirelessSetup = wirelessStage && !task.committed &&
                    task.cancelled == null && error !is CancellationException)
        } finally {
            apk?.delete()
            task.connection?.disconnect()
            task.connection = null
            task.client = null
            task.worker = null
            synchronized(this) {
                task.timeout?.let(main::removeCallbacks)
            }
            changed()
        }
    }

    private fun discover(context: Context, task: Operation): List<WirelessAdbEndpoint> {
        val found = LinkedBlockingQueue<WirelessAdbEndpoint>()
        val endpoints = linkedSetOf<WirelessAdbEndpoint>()
        val discovery = WirelessAdbDiscovery(context, main, { kind, endpoint ->
            if (kind == WirelessAdbDiscovery.Kind.CONNECT && endpoint != null) found.offer(endpoint)
        }, { /* A validated cached endpoint can still work when mDNS is unavailable. */ })
        main.post { if (task.cancelled == null) discovery.start() }
        val until = SystemClock.elapsedRealtime() + 12_000L
        try {
            while (SystemClock.elapsedRealtime() < until) {
                checkActive(task)
                found.poll(250, TimeUnit.MILLISECONDS)?.let { endpoints.add(it) }
                if (endpoints.isNotEmpty()) break
            }
        } finally {
            main.post { discovery.stop() }
        }
        WirelessAdbAccess.lastEndpoint(context)?.let(endpoints::add)
        check(endpoints.isNotEmpty()) { "This phone's Wireless ADB connection could not be found. $PAIR_GUIDANCE" }
        return endpoints.toList()
    }

    /** Startup and MY_PACKAGE_REPLACED call this; an old process cannot certify its own handoff. */
    @Synchronized fun recover(context: Context) {
        if (operation != null) return
        if (prefs(context).contains(EXPECTED)) {
            if (!confirmInstalled(context)) {
                update(context, Phase.FAILED, "The previous update could not be confirmed. Check the installed version and Wireless ADB authorization, then check for updates again. $PAIR_GUIDANCE", prefs(context).getString(EXPECTED, null))
            }
        } else if (status(context).phase in setOf(Phase.DISCOVERING, Phase.DOWNLOADING, Phase.VERIFYING, Phase.INSTALLING)) {
            update(context, Phase.FAILED, "Android interrupted the update before a confirmed installation. Check for updates again when ready.", status(context).version)
        }
        // Only our uncommitted private downloads; never generated icon-pack files.
        context.cacheDir.listFiles { _, name -> name.startsWith("icongen-self-update-") && name.endsWith(".apk") }
            ?.forEach { it.delete() }
    }

    private fun confirmInstalled(context: Context): Boolean {
        val prefs = prefs(context)
        val expected = prefs.getString(EXPECTED, null) ?: return false
        val actual = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull() ?: return false
        val actualVersion = actual.versionName?.let(UpdateVersion::parse) ?: return false
        val expectedVersion = UpdateVersion.parse(expected) ?: return false
        if (actualVersion.compareTo(expectedVersion) != 0 || AppUpdateArtifact.versionCode(actual) != prefs.getLong(EXPECTED_CODE, -1L)) return false
        return prefs.edit().remove(EXPECTED).remove(EXPECTED_CODE).remove(HANDOFF_AT)
            .putString("phase", Phase.DONE.name).putString("version", expected)
            .putBoolean("running", operation != null)
            .putBoolean("wireless_setup_required", false)
            .putInt("download_percent", 0)
            .putString("message", "IconGen $expected is installed and verified.").commit()
    }

    private fun update(context: Context, phase: Phase, message: String, version: String?, needsWirelessSetup: Boolean = false) {
        prefs(context).edit().putString("phase", phase.name).putString("message", message)
            .putString("version", version).putBoolean("running", operation != null)
            .putBoolean("wireless_setup_required", needsWirelessSetup)
            .putInt("download_percent", 0).apply()
    }

    /** Download bytes land every few ms; the UI needs whole percents, not a write per chunk. */
    @Synchronized private fun setDownloadPercent(context: Context, percent: Int) {
        prefs(context).edit().putInt("download_percent", percent.coerceIn(0, 100)).apply()
    }
}
