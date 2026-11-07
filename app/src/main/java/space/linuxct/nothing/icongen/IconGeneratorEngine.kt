package space.linuxct.nothing.icongen

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.CancellationException

/** Generates mapped monochrome PNG resources. */
object IconGeneratorEngine {
    private const val TAG = "IconGeneratorEngine"

    fun generate(context: Context, outputDir: File, maxApps: Int = 2000,
                 cancellation: BuildCoordinator.Cancellation = BuildCoordinator.cancellation(context),
                 renderFailures: MutableList<Pair<String, Exception>> = ArrayList()): File {
        cancellation.checkpoint()
        ThemeColors.invalidate()
        val palette = ThemeColors.resolve(context)
        val apps = AppScanner.scanInstalledApps(context).take(maxApps)
        cancellation.checkpoint()
        check(apps.isNotEmpty()) { "No launchable apps found to generate icons for" }
        val entries = ArrayList<IconPackAssembler.PackEntry>(apps.size)
        try {
            IconPipeline(context).use { pipeline ->
                for ((index, app) in apps.withIndex()) {
                    cancellation.checkpoint()
                    Log.d(TAG, "Rendering ${app.componentName}")
                    try {
                        val glyphs = pipeline.glyph(app.icon)
                        entries.add(IconPackAssembler.PackEntry(app.componentName, app.label, glyphs))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // One unrenderable icon must not block the pack; the app stays unmapped (iconback plate).
                        Log.w(TAG, "Skipping ${app.componentName}: Nothing renderer failed", e)
                        renderFailures.add(app.componentName to e)
                    }
                    cancellation.checkpoint()
                    // Report finished work, so a notification dropped at the last icon cannot strand an old count.
                    val completed = index + 1
                    cancellation.update {
                        it.copy(
                            phase = BuildCoordinator.Phase.GENERATING,
                            message = "Generating icons ($completed/${apps.size})",
                            percent = 10 + 70 * completed / apps.size,
                            detail = "$completed/${apps.size}",
                        )
                    }
                }
            }
            check(entries.isNotEmpty()) { "Nothing renderer failed for all ${apps.size} apps" }
            if (entries.size < apps.size) Log.w(TAG, "Skipped ${apps.size - entries.size} of ${apps.size} apps")
            cancellation.checkpoint()
            cancellation.update {
                it.copy(
                    phase = BuildCoordinator.Phase.GENERATING,
                    message = "Preparing resources (${apps.size}/${apps.size})",
                    percent = 80,
                    detail = "${apps.size}/${apps.size}",
                )
            }
            return IconPackAssembler.assembleZip(context, outputDir, entries, palette, cancellation)
        } finally {
            entries.forEach { it.glyphs.recycle() }
        }
    }
}
