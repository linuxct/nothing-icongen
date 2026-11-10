package space.linuxct.nothing.icongen

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.LauncherActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.UserManager
import android.os.UserHandle
import android.util.Log
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Immediate while resident; persisted identities also detect installs/updates after process death. */
object PackageChangeMonitor {
    const val JOB_ID = 4402
    private const val PREFS = "package_monitor"
    private const val INSTALLED = "installed_fingerprint"
    private const val OBSERVED = "observed_packages"
    private var registeredContext: Context? = null
    private var activationPosted = false

    @Synchronized
    fun register(context: Context) {
        val app = context.applicationContext
        if (registeredContext !== app) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            app.registerReceiver(PackageChangeReceiver(), filter, Context.RECEIVER_EXPORTED)
            registeredContext = app
        }
        if (!OnboardingState.isComplete(app) || activationPosted) return
        ensureScheduled(app)
        activationPosted = true
        // App.onCreate runs before a manifest receiver. Let its authoritative package
        // event consume the snapshot first, otherwise a cold start can request two builds.
        Handler(Looper.getMainLooper()).post {
            synchronized(this) { activationPosted = false }
            if (OnboardingState.isComplete(app)) {
                reconcile(app)
                BuildCoordinator.resumeQueued(app)
            }
        }
    }

    fun ensureScheduled(context: Context) {
        DailyIconCacheCleanup.ensureScheduled(context)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!OnboardingState.isComplete(context) ||
            !context.getSharedPreferences(PackageChangeReceiver.PREFS, Context.MODE_PRIVATE)
                .getBoolean(PackageChangeReceiver.KEY_ENABLED, true)) {
            scheduler.cancel(JOB_ID)
            return
        }
        if (scheduler.getPendingJob(JOB_ID) == null) {
            val result = scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, IconPackBuildJobService::class.java))
                .setPeriodic(15 * 60 * 1000L).setPersisted(true).build())
            if (result != JobScheduler.RESULT_SUCCESS) {
                BuildCoordinator.update { it.copy(phase = BuildCoordinator.Phase.FAILED,
                    error = "Android rejected automatic package monitoring; enable auto-update to retry") }
            }
        }
    }

    /** Package/profile identities deliberately exclude components, theme and permission state. */
    internal fun identities(context: Context): Map<String, String> = scanIdentities(context).records

    private data class IdentityInventory(
        val records: Map<String, String>,
        val unavailable: Set<Long>,
        val currentProfile: Long,
        val profilesUnavailable: Boolean,
    )

    private fun scanIdentities(context: Context): IdentityInventory {
        val records = HashMap<String, String>()
        val unavailable = HashSet<Long>()
        val users = context.getSystemService(UserManager::class.java)
        val currentUser = Process.myUserHandle()
        val currentProfile = users.getSerialNumberForUser(currentUser)
        @Suppress("DEPRECATION")
        val installed = if (Build.VERSION.SDK_INT >= 35) {
            context.packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.MATCH_ARCHIVED_PACKAGES))
        } else context.packageManager.getInstalledPackages(0)
        for (info in installed) {
            if (excluded(context, info.packageName)) continue
            records["$currentProfile:${info.packageName}"] = packageIdentity(info)
        }
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val profiles = try { launcherApps.profiles } catch (_: SecurityException) { null }
        for (user in profiles.orEmpty()) {
            if (user == currentUser) continue
            val profile = users.getSerialNumberForUser(user)
            val activities = try {
                launcherApps.getActivityList(null, user)
            } catch (_: SecurityException) {
                unavailable.add(profile)
                continue
            }
            for (activity in activities) {
                val packageName = activity.componentName.packageName
                if (excluded(context, packageName)) continue
                records["$profile:$packageName"] = launcherIdentity(activity)
            }
        }
        return IdentityInventory(records, unavailable, currentProfile, profiles == null)
    }

    /** An inaccessible profile is unknown, not an authoritative empty installation inventory. */
    private fun observedIdentities(context: Context): Map<String, String> {
        val inventory = scanIdentities(context)
        if (inventory.unavailable.isEmpty() && !inventory.profilesUnavailable) return inventory.records
        val records = inventory.records.toMutableMap()
        val previous = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(OBSERVED, null)?.let(::JSONObject) ?: return records
        for (key in previous.keys()) {
            val profile = key.substringBefore(':').toLongOrNull() ?: continue
            if (profile in inventory.unavailable ||
                (inventory.profilesUnavailable && profile != inventory.currentProfile)) {
                records.putIfAbsent(key, previous.getString(key))
            }
        }
        return records
    }

    /** Revalidates one source without rescanning all visible packages; deliberately no exclusions. */
    internal fun identity(context: Context, user: UserHandle, packageName: String): String? {
        return try {
            if (user == Process.myUserHandle()) {
                @Suppress("DEPRECATION")
                val info = if (Build.VERSION.SDK_INT >= 35) {
                    context.packageManager.getPackageInfo(packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.MATCH_ARCHIVED_PACKAGES))
                } else context.packageManager.getPackageInfo(packageName, 0)
                // Archived icons are retained by LauncherApps after Android removes the APK.
                if (Build.VERSION.SDK_INT < 35 || info.archiveTimeMillis == 0L) {
                    val source = info.applicationInfo?.sourceDir?.let(::File) ?: return null
                    if (!source.isFile || !source.canRead()) return null
                }
                packageIdentity(info)
            } else {
                val activity = context.getSystemService(LauncherApps::class.java)
                    .getActivityList(packageName, user).firstOrNull() ?: return null
                launcherIdentity(activity)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun packageIdentity(info: PackageInfo): String {
        val version = "${info.longVersionCode}:${info.lastUpdateTime}"
        return if (Build.VERSION.SDK_INT >= 35 && info.archiveTimeMillis > 0L) {
            "archived:$version:${info.archiveTimeMillis}"
        } else version
    }

    private fun launcherIdentity(activity: LauncherActivityInfo): String {
        val source = activity.applicationInfo.sourceDir?.let(::File)
        return if (source != null && source.isFile && source.canRead()) {
            "${activity.firstInstallTime}:${source.absolutePath}:${source.lastModified()}:${source.length()}"
        } else {
            // Other profiles expose retained launcher artwork but no public archive timestamp.
            // Keep it renderable without reusing PNGs whose freshness cannot be established.
            "launcher-only:${activity.firstInstallTime}:${source?.absolutePath.orEmpty()}"
        }
    }

    internal fun isCacheableIdentity(identity: String): Boolean = !identity.startsWith("launcher-only:")

    private fun excluded(context: Context, packageName: String) =
        packageName == context.packageName || packageName == PackageChangeReceiver.PACK_PACKAGE

    fun fingerprint(context: Context): String {
        val bytes = identities(context).toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
            .toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun reconcile(context: Context) = reconcileSnapshot(context, observedIdentities(context))

    /** Toggling monitoring consumes the current baseline without manufacturing an app event. */
    @Synchronized
    fun observeCurrent(context: Context) {
        reconcileSnapshot(context, observedIdentities(context), allowBuild = false)
    }

    @Synchronized
    fun packageAdded(context: Context, packageName: String) {
        reconcileSnapshot(context, observedIdentities(context), addedPackage = packageName)
    }

    /** Removal must not consume another package's install/update awaiting its broadcast. */
    @Synchronized
    fun packageRemoved(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(OBSERVED, null)
        val previous = saved?.let(::JSONObject) ?: JSONObject()
        val current = observedIdentities(context)
        val removed = (previous.keys().asSequence().toSet() + DirtyIconQueue.snapshot(context).entries.keys).filter {
            it.substringAfter(':') == packageName && it !in current
        }
        removed.forEach(previous::remove)
        DirtyIconQueue.remove(context, removed)
        if (saved != null) prefs.edit().putString(OBSERVED, previous.toString()).commit()
    }

    /** Dirty identities precede observation and scheduling, so interrupted preparation is recoverable. */
    @Synchronized
    internal fun reconcileSnapshot(context: Context, current: Map<String, String>,
                                   addedPackage: String? = null, allowBuild: Boolean = true) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(OBSERVED, null)?.let(::JSONObject)
        val changed = current.filter { (key, identity) ->
            if (previous == null) {
                // A live install can precede the application's first baseline.
                key.substringAfter(':') == addedPackage
            } else !previous.has(key) || previous.getString(key) != identity
        }
        val enabled = BuildCoordinator.automationEnabled(context)
        if (allowBuild && enabled) DirtyIconQueue.record(context, changed)
        prefs.edit().putString(OBSERVED, JSONObject(current).toString()).commit()
        Log.d("PackageChangeMonitor", "Observed ${current.size} packages installOrUpdate=${changed.size} enabled=$enabled")
        if (changed.isNotEmpty() && allowBuild && enabled) {
            BuildCoordinator.start(context, "package:install-or-update")
        }
        if (allowBuild && enabled) BuildCoordinator.resumePreparation(context)
    }


    /** Only the snapshot taken BEFORE the successful build is acknowledged. */
    fun installed(context: Context, fingerprint: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(INSTALLED, fingerprint).commit()
        Log.d("PackageChangeMonitor", "Installed fingerprint=$fingerprint")
    }
}
