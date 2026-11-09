package space.linuxct.nothing.icongen

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/** Produces the complete current inventory while rendering only missing/changed final PNGs. */
object IconGeneratorEngine {
    private const val TAG = "IconGeneratorEngine"

    fun generate(context: Context, outputDir: File, maxApps: Int = 2000,
                 cancellation: BuildCoordinator.Cancellation = BuildCoordinator.cancellation(context),
                 renderFailures: MutableList<Pair<String, Exception>> = ArrayList()): File {
        cancellation.checkpoint()
        val started = SystemClock.elapsedRealtime()
        ThemeColors.invalidate()
        val dirtySnapshot = DirtyIconQueue.snapshot(context)
        val sourceSnapshot = PackageChangeMonitor.identities(context)
        val inventory = AppScanner.inventory(context, sourceSnapshot)
        val apps = inventory.apps.take(maxApps)
        cancellation.checkpoint()
        check(apps.isNotEmpty()) { "No launchable apps found to generate icons for" }
        val scannedAt = SystemClock.elapsedRealtime()
        val cache = PreparedIconCache(context)
        val entries = ArrayList<IconPackAssembler.PackEntry>(apps.size)
        try {
            IconPipeline(context).use { pipeline ->
                for ((index, app) in apps.withIndex()) {
                    cancellation.checkpoint()
                    try {
                        entries.add(cache.prepare(app, pipeline, cancellation))
                    } catch (e: Exception) {
                        if (e is CancellationException || e is IOException) throw e
                        // Never substitute an obsolete cached icon for a source that failed to render.
                        Log.w(TAG, "Skipping ${app.componentName}: Nothing renderer failed", e)
                        renderFailures.add(app.componentName to e)
                    }
                    cancellation.checkpoint()
                    val completed = index + 1
                    cancellation.update {
                        it.copy(phase = BuildCoordinator.Phase.GENERATING,
                            message = "Preparing icons ($completed/${apps.size})",
                            percent = 10 + 70 * completed / apps.size, detail = "$completed/${apps.size}")
                    }
                }
            }
            check(entries.isNotEmpty()) { "Nothing renderer failed for all ${apps.size} apps" }
            if (entries.size < apps.size) Log.w(TAG, "Skipped ${apps.size - entries.size} of ${apps.size} apps")
            cancellation.checkpoint()
            cache.checkEnvironment()
            if (sourceSnapshot != PackageChangeMonitor.identities(context)) {
                throw IOException("Installed apps changed while preparing the icon pack")
            }
            cache.prune(inventory)
            cancellation.update {
                it.copy(phase = BuildCoordinator.Phase.GENERATING,
                    message = "Preparing resources (${apps.size}/${apps.size})", percent = 80,
                    detail = "${apps.size}/${apps.size}")
            }
            val preparedAt = SystemClock.elapsedRealtime()
            Log.d(TAG, "Prepared icons: apps=${apps.size} reused=${cache.reused} rendered=${cache.rendered} " +
                "encoded=${cache.encoded} scanMs=${scannedAt - started} prepareMs=${preparedAt - scannedAt}")
            val resources = IconPackAssembler.prepareResources(context, outputDir, entries,
                ThemeColors.resolve(context), cancellation)
            val preparedComponents = entries.mapTo(HashSet()) { it.componentName }
            val preparedSources = inventory.apps.groupBy { "${it.profileSerial}:${it.sourcePackage}" }
                .filterValues { group -> group.first().profileSerial in inventory.completeProfiles &&
                    group.all { it.componentName in preparedComponents } }
                .mapValues { it.value.first().sourceIdentity }
            cache.checkEnvironment()
            cancellation.checkpoint()
            cancellation.commit {
                DirtyIconQueue.acknowledgePrepared(context, dirtySnapshot, preparedSources,
                    PackageChangeMonitor.identities(context))
            }
            return resources
        } finally {
            // Resource assembly owns its links/copies; failures also have no remaining consumers.
            cache.trim(release = true)
        }
    }

    internal fun warmPackages(context: Context, packages: Set<String>, cancellation: BuildCoordinator.Cancellation) {
        cancellation.checkpoint()
        ThemeColors.invalidate()
        val started = SystemClock.elapsedRealtime()
        val inventory = AppScanner.inventory(context)
        val apps = inventory.apps.filter { it.sourcePackage in packages }
        val cache = PreparedIconCache(context)
        try {
            IconPipeline(context).use { pipeline ->
                for (app in apps) {
                    cancellation.checkpoint()
                    cache.prepare(app, pipeline, cancellation)
                }
            }
            cache.checkEnvironment()
            cancellation.checkpoint()
            Log.d(TAG, "Warmed icons: packages=${packages.size} apps=${apps.size} reused=${cache.reused} " +
                "rendered=${cache.rendered} encoded=${cache.encoded} ms=${SystemClock.elapsedRealtime() - started}")
        } finally { cache.trim(release = true) }
    }

    internal fun pruneCache(context: Context, cancellation: BuildCoordinator.Cancellation) {
        cancellation.checkpoint()
        val inventory = AppScanner.inventory(context)
        cancellation.commit {
            PreparedIconCache(context).apply { prune(inventory); trim(release = true) }
        }
    }
}
