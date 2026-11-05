package space.linuxct.nothing.icongen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.ParcelFileDescriptor
import android.content.pm.PackageInstaller
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object IconPackAssembler {

    private const val TAG = "IconPackAssembler"
    /**
     * Derived from the build timestamp so every regenerated pack is a strictly higher
     * versionCode. A constant here was why re-installing never replaced the old icons.
     */
    private val MANIFEST_CODE: Int
        get() = (System.currentTimeMillis() / 1000L % 2_000_000_000L).toInt()

    private val MANIFEST_VERSION: String
        get() = (MANIFEST_CODE / 1000L).toString()
    private const val MIN_SDK = 33
    private const val TARGET_SDK = 37
    internal const val PACKAGE_NAME = "com.nothing.icongen.pack"
    const val COMPILE_DIR_PREFIX = "apk_compile_"
    private const val PREFS = "pack_options"
    private const val KEY_KEEP_THEME_ON_UPDATE = "keep_theme_on_update"

    fun keepThemeOnUpdate(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_KEEP_THEME_ON_UPDATE, false)

    fun setKeepThemeOnUpdate(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_KEEP_THEME_ON_UPDATE, enabled).apply()
    }

    data class PackEntry(
        val componentName: String,
        val label: String,
        /** Native normal/light alpha masks, retained until both resource variants are written. */
        val glyphs: LauncherGlyphs,
    )

    fun assembleZip(
        context: Context,
        outputDir: File,
        entries: List<PackEntry>,
        palette: ThemeColors.Palette,
        cancellation: BuildCoordinator.Cancellation = BuildCoordinator.cancellation(context),
    ): File {
        Log.d(TAG, "Assembling icon pack resources for ${entries.size} apps")
        cancellation.checkpoint()

        outputDir.mkdirs()

        try {
            generateManifest(context, outputDir, palette)
            generateAppfilter(context, outputDir, entries)
            generateResources(context, outputDir, entries, palette, cancellation)
            generateResourceFiles(context, outputDir, entries)
            cancellation.checkpoint()

            val zipFile = File(outputDir.parentFile, "iconpack_resources.zip")
            zipFile.delete()

            ZipOutputStream(FileOutputStream(zipFile)).use { zout ->
                zipDir(zout, outputDir, outputDir, cancellation)
            }

            Log.d(TAG, "Icon pack resources ZIP created at ${zipFile.absolutePath} (${zipFile.length()} bytes)")
            return zipFile
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw RuntimeException("Resource ZIP assembly failed: ${e.message}", e)
        }
    }

    private fun zipDir(zout: ZipOutputStream, baseDir: File, dir: File, cancellation: BuildCoordinator.Cancellation) {
        dir.listFiles()?.forEach { file ->
            cancellation.checkpoint()
            val entryPath = file.relativeTo(baseDir).path
            if (file.isDirectory) {
                zout.putNextEntry(ZipEntry(entryPath + "/"))
                zout.closeEntry()
                zipDir(zout, baseDir, file, cancellation)
            } else {
                zout.putNextEntry(ZipEntry(entryPath))
                FileInputStream(file).use { fin ->
                    fin.copyTo(zout)
                }
                zout.closeEntry()
            }
        }
    }

    fun assemble(
        context: Context,
        entries: List<PackEntry>,
        palette: ThemeColors.Palette,
    ): File {
        Log.d(TAG, "Assembling icon pack (ZIP output) for ${entries.size} apps")
        val outputDir = File(context.cacheDir, "iconpack_${System.currentTimeMillis()}")
        return assembleZip(context, outputDir, entries, palette)
    }

    fun compileApk(context: Context, zipFile: File,
                   cancellation: BuildCoordinator.Cancellation = BuildCoordinator.cancellation(context)): File {
        cancellation.checkpoint()
        Log.d(TAG, "Compiling APK from ZIP: ${zipFile.absolutePath}")

        val outputDir = File(context.cacheDir, "$COMPILE_DIR_PREFIX${System.currentTimeMillis()}")
        try {
            return compileApkIn(outputDir, context, zipFile, cancellation)
        } finally {
            outputDir.deleteRecursively()
        }
    }

    private fun compileApkIn(outputDir: File, context: Context, zipFile: File,
                             cancellation: BuildCoordinator.Cancellation): File {
        outputDir.mkdirs()
        val resDir = File(outputDir, "res")
        resDir.mkdirs()

        // Unzip resources
        try {
            ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    cancellation.checkpoint()
                    val outFile = File(outputDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(outFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw RuntimeException("Failed to unzip resources: ${e.message}", e)
        }

        // Toolchain: executables in nativeLibraryDir (app-private, executable), android.jar in filesDir.
        App.instance.ensureTools()
        cancellation.checkpoint()
        val aapt2Path = App.instance.aapt2Path
        val androidJarPath = App.instance.androidJarPath

        val manifestFile = File(outputDir, "AndroidManifest.xml")
        if (!manifestFile.exists()) {
            throw RuntimeException("AndroidManifest.xml missing from resource ZIP at ${manifestFile.absolutePath}")
        }

        val compiledRes = File(outputDir, "resources.zip")
        val apkFile = File(context.filesDir, "iconpack.apk")

        // Compile resources: aapt2 compile res -o resources.zip --dir res
        try {
            runCommand(cancellation, aapt2Path, "compile", "-o", compiledRes.absolutePath, "--dir", resDir.absolutePath)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw RuntimeException("aapt2 compile failed: ${e.message}", e)
        }

        // Link APK: aapt2 link -o apk -I android.jar --manifest
        try {
            runCommand(
                cancellation, aapt2Path, "link",
                "-o", apkFile.absolutePath,
                "-I", androidJarPath,
                "--manifest", manifestFile.absolutePath,
                "--target-sdk-version", "37",
                compiledRes.absolutePath
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw RuntimeException("aapt2 link failed: ${e.message}", e)
        }

        // Every install must be signed; reuse the persistent on-device key so upgrades work.
        cancellation.checkpoint()
        ApkSignerTool.signApk(context, apkFile)
        cancellation.checkpoint()

        Log.d(TAG, "APK compiled and signed: ${apkFile.absolutePath} (${apkFile.length()} bytes)")
        return apkFile
    }

    private fun runCommand(cancellation: BuildCoordinator.Cancellation, vararg args: String) {
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

    private fun generateManifest(context: Context, outputDir: File, palette: ThemeColors.Palette) {
        val manifestFile = File(outputDir, "AndroidManifest.xml")
        val resDir = File(outputDir, "res")
        resDir.mkdirs()

        val manifestXml = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$PACKAGE_NAME"
    android:versionCode="$MANIFEST_CODE"
    android:versionName="$MANIFEST_VERSION">

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
        context: Context,
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
        val launcherOnly = !keepThemeOnUpdate(context)
        for (entry in entries) {
            val packageName = entry.componentName.substringBefore('/')
            if (launcherOnly && packages.add(packageName)) {
                // Empty package defaults opt out of package-wide theming; exact activity
                // overrides below still resolve to their generated launcher drawables.
                // The launcher also hits them when an app updates, dropping its theme until a rebuild.
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
    private fun packDrawableName(componentName: String): String =
        "icon_" + componentName.lowercase().replace(Regex("[^a-z0-9]"), "_")

    private fun generateResources(
        context: Context,
        outputDir: File,
        entries: List<PackEntry>,
        palette: ThemeColors.Palette,
        cancellation: BuildCoordinator.Cancellation,
    ) {
        // Qualifiers select the right variant on a fresh drawable lookup. The launcher
        // caches rasterized icons, so a uiMode change alone does not guarantee a refresh.
        val drawableDir = File(outputDir, "res/drawable")
        val drawableNightDir = File(outputDir, "res/drawable-night")
        drawableDir.mkdirs()
        drawableNightDir.mkdirs()

        val mipmapDir = File(outputDir, "res/mipmap-nodpi")
        mipmapDir.mkdirs()
        val launcherIcon = Bitmap.createBitmap(IconArtwork.ICON_SIZE, IconArtwork.ICON_SIZE, Bitmap.Config.ARGB_8888)
        try {
            val artwork = requireNotNull(context.getDrawable(R.drawable.ic_icon_pack))
            artwork.setBounds(0, 0, launcherIcon.width, launcherIcon.height)
            artwork.draw(Canvas(launcherIcon))
            saveBitmapAsPng(launcherIcon, File(mipmapDir, "ic_launcher.png"))
        } finally { launcherIcon.recycle() }

        // Resolve each configuration independently; copying isDark would retain the wrong ink.
        val lightPalette = ThemeColors.resolve(context, dark = false)
        val darkPalette = ThemeColors.resolve(context, dark = true)

        PackIconEncoder().use { encoder ->
            fun saveForLauncher(artwork: Bitmap, file: File) {
                try {
                    val encoded = encoder.encode(artwork)
                    try { saveBitmapAsPng(encoded, file) } finally { encoded.recycle() }
                } finally { artwork.recycle() }
            }
            saveForLauncher(IconArtwork.buildPlate(lightPalette), File(drawableDir, "icon_background.png"))
            saveForLauncher(IconArtwork.buildPlate(darkPalette), File(drawableNightDir, "icon_background.png"))

            for (entry in entries) {
                cancellation.checkpoint()
                // Native artwork stays unchanged as the target. Encode the complete RGBA
                // composite to counter the host's source/shadows/source rasterization.
                val name = "${packDrawableName(entry.componentName)}.png"
                saveForLauncher(
                    IconArtwork.buildIcon(lightPalette, entry.glyphs.forColor(lightPalette.foreground)),
                    File(drawableDir, name),
                )
                cancellation.checkpoint()
                saveForLauncher(
                    IconArtwork.buildIcon(darkPalette, entry.glyphs.forColor(darkPalette.foreground)),
                    File(drawableNightDir, name),
                )
            }
        }
    }

    private fun generateResourceFiles(context: Context, outputDir: File, entries: List<PackEntry>) {
        val valuesDir = File(outputDir, "res/values")
        valuesDir.mkdirs()

        val appfilterRes = File(valuesDir, "appfilter.xml")
        appfilterRes.writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string-array name="appfilter">
        <item>@xml/appfilter</item>
    </string-array>
    <string name="app_label">${context.getString(R.string.icon_pack_name)}</string>
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
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            out.flush()
        }
    }

    private fun String.ifNegative(transform: (String) -> String): String {
        return if (startsWith("-")) transform(this.substring(1)) else this
    }
}