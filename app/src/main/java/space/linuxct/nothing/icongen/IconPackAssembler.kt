package space.linuxct.nothing.icongen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

object IconPackAssembler {

    private const val TAG = "IconPackAssembler"
    internal data class PackVersion(val code: Int, val name: String)

    /** Persist before use: failed/cancelled builds may leave gaps, never duplicate versions. */
    @Synchronized
    internal fun reserveVersion(context: Context, nowMillis: Long = System.currentTimeMillis()): PackVersion {
        val preferences = context.getSharedPreferences("iconpack_versions", Context.MODE_PRIVATE)
        val installed = try {
            context.packageManager.getPackageInfo(PACKAGE_NAME, 0).longVersionCode
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { 0L }
        val next = maxOf(nowMillis / 1000L, preferences.getLong("last_code", 0) + 1L, installed + 1L)
        check(next in 1..Int.MAX_VALUE.toLong()) { "Icon pack version code exhausted" }
        check(preferences.edit().putLong("last_code", next).commit()) { "Cannot persist icon pack version" }
        return PackVersion(next.toInt(), next.toString())
    }
    private const val MIN_SDK = 33
    private const val TARGET_SDK = 37
    internal const val PACKAGE_NAME = "com.nothing.icongen.pack"
    const val COMPILE_DIR_PREFIX = "apk_compile_"

    data class PackEntry(
        val componentName: String,
        val label: String,
        val lightPng: File,
        val darkPng: File,
    )

    @Synchronized
    fun prepareResources(
        context: Context,
        outputDir: File,
        entries: List<PackEntry>,
        palette: ThemeColors.Palette,
        cancellation: BuildCoordinator.Cancellation = BuildCoordinator.cancellation(context),
    ): File {
        val started = SystemClock.elapsedRealtime()
        cancellation.checkpoint()
        val ordered = entries.sortedBy { it.componentName }
        require(ordered.map { it.componentName }.distinct().size == ordered.size) { "Duplicate launcher component" }
        require(ordered.map { packDrawableName(it.componentName) }.distinct().size == ordered.size) { "Colliding drawable names" }
        outputDir.parentFile?.mkdirs()
        val staged = File(outputDir.parentFile, ".${outputDir.name}_${System.nanoTime()}").apply { check(mkdirs()) }
        val previous = File(outputDir.parentFile, ".${outputDir.name}_previous_${System.nanoTime()}")
        try {
            generateManifest(staged, reserveVersion(context))
            generateAppfilter(staged, ordered)
            generateResources(context, staged, ordered, cancellation)
            generateResourceFiles(context, staged)
            cancellation.checkpoint()
            if (outputDir.exists()) check(outputDir.renameTo(previous)) { "Cannot move previous resource inventory" }
            if (!staged.renameTo(outputDir)) {
                previous.renameTo(outputDir)
                error("Cannot publish resource inventory")
            }
            Log.d(TAG, "Prepared resources: entries=${ordered.size} ms=${SystemClock.elapsedRealtime() - started}")
            return outputDir
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw RuntimeException("Resource assembly failed: ${e.message}", e)
        } finally {
            staged.deleteRecursively()
            previous.deleteRecursively()
        }
    }

    /** One serialized owner protects cache entries through link/sign and atomic APK publication. */
    @Synchronized
    fun compileApk(
        context: Context,
        resourceDir: File,
        cancellation: BuildCoordinator.Cancellation = BuildCoordinator.cancellation(context),
    ): File {
        cancellation.checkpoint()
        val outputDir = File(context.cacheDir, "$COMPILE_DIR_PREFIX${System.nanoTime()}").apply { check(mkdirs()) }
        val cache = AaptResourceCache(File(context.cacheDir, "aapt2_flat_v1"))
        try {
            App.instance.ensureTools()
            cancellation.checkpoint()
            val manifest = File(resourceDir, "AndroidManifest.xml")
            check(manifest.isFile) { "AndroidManifest.xml missing from prepared resources at ${manifest.absolutePath}" }
            val resources = File(resourceDir, "res")
            check(resources.isDirectory) { "Prepared resource directory missing at ${resources.absolutePath}" }
            val compiled = try {
                cache.compile(resources, File(App.instance.aapt2Path), outputDir, cancellation) {
                    runCommand(cancellation, *it.toTypedArray())
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                throw RuntimeException("aapt2 compile failed: ${e.message}", e)
            }
            val active = compiled.files
            // A response file keeps full inventories below Android's process argument limit.
            val inventory = File(outputDir, "resources.list")
            inventory.bufferedWriter().use { writer ->
                for (flat in active) {
                    // AAPT2 treats quotes literally here; these app-private hash paths contain no whitespace.
                    writer.append(flat.absolutePath).append('\n')
                }
            }
            val apk = File(outputDir, "iconpack.apk")
            val linkStarted = SystemClock.elapsedRealtime()
            try {
                runCommand(cancellation, App.instance.aapt2Path, "link",
                    "-o", apk.absolutePath, "-I", App.instance.androidJarPath,
                    "--manifest", manifest.absolutePath,
                    "--target-sdk-version", TARGET_SDK.toString(), "@${inventory.absolutePath}")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                throw RuntimeException("aapt2 link failed: ${e.message}", e)
            }
            Log.d(TAG, "AAPT2 link: resources=${active.size} ms=${SystemClock.elapsedRealtime() - linkStarted}")
            cancellation.checkpoint()
            val signStarted = SystemClock.elapsedRealtime()
            ApkSignerTool.signApk(context, apk)
            cancellation.checkpoint()
            // filesDir/cacheDir can be separate filesystems: stage beside the final APK.
            val published = File(context.filesDir, "iconpack.apk")
            val stagingApk = File(context.filesDir, ".iconpack_${System.nanoTime()}.apk")
            try {
                try {
                    android.system.Os.link(apk.absolutePath, stagingApk.absolutePath)
                } catch (_: android.system.ErrnoException) {
                    apk.copyTo(stagingApk)
                }
                cancellation.checkpoint()
                check(stagingApk.renameTo(published)) { "Cannot publish signed icon pack" }
            } finally { stagingApk.delete() }
            Log.d(TAG, "APK signed: bytes=${published.length()} ms=${SystemClock.elapsedRealtime() - signStarted}")
            return published
        } finally {
            outputDir.deleteRecursively()
            cache.evict(emptyList())
        }
    }

    internal fun runCommand(cancellation: BuildCoordinator.Cancellation, vararg args: String) {
        cancellation.checkpoint()
        val outputFile = File.createTempFile("aapt2_", ".log", App.instance.cacheDir)
        try {
            // A file avoids a blocked pipe reader hiding cancellation or a full child pipe.
            val builder = ProcessBuilder(*args).redirectErrorStream(true).redirectOutput(outputFile)
            val env = builder.environment()
            env["LD_LIBRARY_PATH"] = App.instance.nativeLibraryDir
            env["TMPDIR"] = File(App.instance.filesDir, "tmp").apply { mkdirs() }.absolutePath
            val process = builder.start()
            try {
                cancellation.attach(process)
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) cancellation.checkpoint()
                cancellation.checkpoint()
                if (process.exitValue() != 0) {
                    throw RuntimeException("Command failed with exit code ${process.exitValue()}: ${args.joinToString(" ")}\nOutput: ${outputFile.readText()}")
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.waitFor()
                cancellation.detach(process)
            }
        } finally {
            outputFile.delete()
        }
    }

    private fun generateManifest(outputDir: File, version: PackVersion) {
        val manifestFile = File(outputDir, "AndroidManifest.xml")

        val manifestXml = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$PACKAGE_NAME"
    android:versionCode="${version.code}"
    android:versionName="${version.name}">

    <uses-sdk android:minSdkVersion="$MIN_SDK" android:targetSdkVersion="$TARGET_SDK" />

    <uses-feature android:name="com.nothing.feature.OS.V2_0" android:required="true" />

    <application
        android:label="@string/app_label"
        android:icon="@mipmap/ic_launcher"
        android:hasCode="false"
        android:supportsRtl="true">
        <!-- These theme intents make the pack available in the icon pack picker. -->
        <activity
            android:name=".IconPackThemeActivity"
            android:exported="true"
            android:label="@string/app_label">
            <intent-filter>
                <action android:name="org.adw.launcher.THEMES" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
            <intent-filter>
                <action android:name="com.gau.go.launcherex.theme" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>
        <meta-data
            android:name="com.android.vending.splits"
            android:resource="@xml/splits" />
        <meta-data
            android:name="com.android.vending.derived.apk.id"
            android:value="1" />
    </application>
</manifest>
"""
        manifestFile.writeText(manifestXml)
    }

    private fun generateAppfilter(
        outputDir: File,
        entries: List<PackEntry>,
    ) {
        val xmlDir = File(outputDir, "res/xml")
        xmlDir.mkdirs()

        // Drawable references use plain resource names, without a "drawable/" prefix.
        val appfilterXml = StringBuilder()
        appfilterXml.append("""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- The background plate also covers icons without an explicit mapping. -->
    <iconback img1="icon_background" />
""")
        val packages = HashSet<String>()
        for (entry in entries) {
            val packageName = entry.componentName.substringBefore('/')
            if (packages.add(packageName)) {
                // Empty package defaults opt out of package-wide theming; exact activity
                // overrides below still resolve to their generated launcher drawables.
                appfilterXml.append(
                    "    <item component=\"ComponentInfo{$packageName/}\" drawable=\"\" />\n",
                )
            }
            val drawableName = packDrawableName(entry.componentName)
            appfilterXml.append(
                "    <item component=\"ComponentInfo{${entry.componentName}}\" drawable=\"$drawableName\" />\n",
            )
        }
        appfilterXml.append("</resources>\n")
        File(xmlDir, "appfilter.xml").writeText(appfilterXml.toString())
    }

    /** Must match the drawable names written into appfilter.xml and the produced PNGs. */
    internal fun packDrawableName(componentName: String): String =
        "icon_" + componentName.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]"), "_")

    private fun generateResources(
        context: Context,
        outputDir: File,
        entries: List<PackEntry>,
        cancellation: BuildCoordinator.Cancellation,
    ) {
        val drawableDir = File(outputDir, "res/drawable").apply { mkdirs() }
        val drawableNightDir = File(outputDir, "res/drawable-night").apply { mkdirs() }
        val artworkCache = File(context.cacheDir, "pack_artwork_v1").apply { mkdirs() }
        artworkCache.listFiles { file -> file.name.startsWith('.') }?.forEach { it.delete() }
        val lightPalette = ThemeColors.resolve(context, dark = false)
        val darkPalette = ThemeColors.resolve(context, dark = true)
        val appRevision = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

        fun cachedArtwork(key: String, destination: File, create: () -> Bitmap) {
            cancellation.checkpoint()
            val id = AaptResourceCache.digest(key.toByteArray())
            val source = File(artworkCache, "$id.png")
            val checksum = File(artworkCache, "$id.sha256")
            val valid = try {
                source.isFile && checksum.readText() == AaptResourceCache.digest(source)
            } catch (_: java.io.IOException) { false }
            if (!valid) {
                val temporary = File(artworkCache, ".$id.${System.nanoTime()}")
                val bitmap = create()
                try {
                    saveBitmapAsPng(bitmap, temporary)
                    check(temporary.renameTo(source)) { "Cannot publish cached artwork" }
                    checksum.writeText(AaptResourceCache.digest(source))
                } finally { bitmap.recycle(); temporary.delete() }
            }
            source.setLastModified(System.currentTimeMillis())
            reusePng(source, destination)
        }

        cachedArtwork("launcher-v1:$appRevision:${context.resources.configuration}",
            File(outputDir, "res/mipmap-nodpi/ic_launcher.png")) {
            Bitmap.createBitmap(IconArtwork.ICON_SIZE, IconArtwork.ICON_SIZE, Bitmap.Config.ARGB_8888).also { bitmap ->
                val artwork = requireNotNull(context.getDrawable(R.drawable.ic_icon_pack))
                artwork.setBounds(0, 0, bitmap.width, bitmap.height)
                artwork.draw(Canvas(bitmap))
            }
        }
        // Allocate encoder bitmap buffers only on a cache miss.
        for ((colors, directory) in listOf(lightPalette to drawableDir, darkPalette to drawableNightDir)) {
            cachedArtwork("plate-v1:$appRevision:${colors.foreground}:${colors.background}:${colors.isDark}",
                File(directory, "icon_background.png")) {
                PackIconEncoder().use { encoder ->
                    val plate = IconArtwork.buildPlate(colors)
                    try { encoder.encode(plate) } finally { plate.recycle() }
                }
            }
        }
        for (entry in entries) {
            cancellation.checkpoint()
            val name = "${packDrawableName(entry.componentName)}.png"
            reusePng(entry.lightPng, File(drawableDir, name))
            reusePng(entry.darkPng, File(drawableNightDir, name))
        }
        // The active build now owns links/copies, so old cached artwork can be bounded safely.
        artworkCache.listFiles { file -> file.extension == "png" }?.sortedByDescending { it.lastModified() }
            ?.drop(24)?.forEach { it.delete(); File(artworkCache, "${it.nameWithoutExtension}.sha256").delete() }
    }

    private fun generateResourceFiles(context: Context, outputDir: File) {
        val valuesDir = File(outputDir, "res/values")
        valuesDir.mkdirs()

        val appfilterRes = File(valuesDir, "appfilter.xml")
        appfilterRes.writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string-array name="appfilter">
        <item>@xml/appfilter</item>
    </string-array>
    <string name="app_label">${xmlEscape(context.getString(R.string.icon_pack_name))}</string>
</resources>
""",
        )

        val xmlDir = File(outputDir, "res/xml")
        xmlDir.mkdirs()
        val splitsXml = File(xmlDir, "splits.xml")
        splitsXml.writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<split-meta xmlns:android="http://schemas.android.com/apk/res/android">
    <density-splits mode="base__density" />
</split-meta>
""",
        )
    }

    private fun saveBitmapAsPng(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)) { "PNG encoding failed: $file" }
            out.flush()
        }
    }

    private fun reusePng(source: File, destination: File) {
        check(source.isFile && source.length() > 0) { "Prepared PNG missing: $source" }
        destination.parentFile?.mkdirs()
        try {
            android.system.Os.link(source.absolutePath, destination.absolutePath)
        } catch (_: android.system.ErrnoException) {
            source.copyTo(destination)
        }
    }

    private fun xmlEscape(value: String): String = value.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}