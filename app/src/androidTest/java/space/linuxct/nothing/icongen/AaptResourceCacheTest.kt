package space.linuxct.nothing.icongen

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.apksig.ApkVerifier
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the shipped executable and consumes its native outputs through Android's resource loader. */
@RunWith(AndroidJUnit4::class)
class AaptResourceCacheTest {
    @Test fun nativeFlatsReuseInvalidateRepairAndExcludeDeletedResourcesFromSignedApk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        App.instance.ensureTools()
        val root = File(context.cacheDir, "aapt_behavior_${System.nanoTime()}").apply { check(mkdirs()) }
        val published = File(context.filesDir, "iconpack.apk")
        val previous = if (published.isFile) File(root, "previous.apk").also { published.copyTo(it) } else null
        val prepared = File(root, "prepared").apply { mkdirs() }
        val resources = File(prepared, "res")
        val kept = File(resources, "drawable/kept.png")
        val removed = File(resources, "drawable/removed.png")
        val strings = File(resources, "values/strings.xml").apply { parentFile!!.mkdirs() }
        strings.writeText("<resources><string name=\"label\">First</string></resources>")
        png(kept, Color.RED)
        png(removed, Color.BLUE)
        File(prepared, "AndroidManifest.xml").writeText("""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="${IconPackAssembler.PACKAGE_NAME}" android:versionCode="1" android:versionName="1">
                <uses-sdk android:minSdkVersion="33" android:targetSdkVersion="37" />
                <application android:label="@string/label" android:icon="@drawable/kept" android:hasCode="false" />
            </manifest>
        """.trimIndent())
        val cacheRoot = File(root, "compiled")
        val cache = AaptResourceCache(cacheRoot)
        val cancellation = BuildCoordinator.cancellation(context)
        var launches = 0
        fun compile(compiler: AaptResourceCache = cache): AaptResourceCache.Result {
            val work = File(root, "work_${System.nanoTime()}").apply { mkdirs() }
            return try {
                compiler.compile(resources, File(App.instance.aapt2Path), work, cancellation) { arguments ->
                    launches++
                    IconPackAssembler.runCommand(cancellation, *arguments.toTypedArray())
                }
            } finally { work.deleteRecursively() }
        }
        try {
            val cold = compile()
            assertEquals(3, cold.misses)
            assertEquals("Cold population must use bulk compile", 1, launches)
            val warm = compile()
            assertEquals(0, warm.misses)
            assertEquals(3, warm.hits)
            assertEquals(cold.files, warm.files)
            assertEquals("Warm population must not launch AAPT2 compile", 1, launches)

            png(kept, Color.GREEN)
            val changed = compile()
            assertEquals(1, changed.misses)
            assertEquals(2, changed.hits)
            assertNotEquals(cold.files.first(), changed.files.first())
            assertEquals(cold.files.drop(1), changed.files.drop(1))

            strings.writeText("<resources><string name=\"label\">Updated</string></resources>")
            assertEquals("XML mutations must invalidate their flat", 1, compile().misses)
            val current = compile()
            current.files.first().writeBytes(byteArrayOf(0, 1, 2, 3))
            assertEquals("Corrupt flat must be recompiled", 1, compile().misses)
            compile().files.last().delete()
            assertEquals("Missing flat must be recompiled", 1, compile().misses)
            assertEquals("Compile flags are part of cache identity", 3,
                compile(AaptResourceCache(cacheRoot, listOf("--no-crunch"))).misses)

            val firstApk = IconPackAssembler.compileApk(context, prepared, cancellation)
            assertTrue(ApkVerifier.Builder(firstApk).build().verify().isVerified)
            val firstResources = archiveResources(firstApk, root)
            assertTrue(firstResources.getIdentifier("removed", "drawable", IconPackAssembler.PACKAGE_NAME) != 0)
            assertTrue(removed.delete())
            val afterRemoval = compile()
            assertEquals(0, afterRemoval.misses)
            assertEquals(2, afterRemoval.files.size)
            // Eviction cannot delete a flat still owned by an active compilation/link inventory.
            cache.evict(afterRemoval.files, maxBytes = 0, maxEntries = 0)
            assertTrue(afterRemoval.files.all { it.isFile })
            assertEquals(2, cacheRoot.listFiles()!!.size)

            val finalApk = IconPackAssembler.compileApk(context, prepared, cancellation)
            assertTrue(ApkVerifier.Builder(finalApk).build().verify().isVerified)
            val packaged = archiveResources(finalApk, root)
            assertEquals("Removed resources must not survive full-inventory link", 0,
                packaged.getIdentifier("removed", "drawable", IconPackAssembler.PACKAGE_NAME))
            val label = packaged.getIdentifier("label", "string", IconPackAssembler.PACKAGE_NAME)
            assertEquals("Updated", packaged.getString(label))
            val image = packaged.getDrawable(packaged.getIdentifier("kept", "drawable", IconPackAssembler.PACKAGE_NAME), null)
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                image.setBounds(0, 0, 8, 8)
                image.draw(android.graphics.Canvas(bitmap))
                assertEquals("Changed PNG must reach APK consumer", Color.GREEN, bitmap.getPixel(4, 4))
            } finally { bitmap.recycle() }
        } finally {
            try {
                if (previous != null) previous.copyTo(published, overwrite = true) else published.delete()
            } finally { root.deleteRecursively() }
        }
    }

    private fun archiveResources(apk: File, root: File): android.content.res.Resources {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = requireNotNull(context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_META_DATA))
        val application = requireNotNull(info.applicationInfo).apply {
            sourceDir = apk.path
            publicSourceDir = apk.path
        }
        // A unique path avoids ResourcesManager retaining an earlier APK at the published path.
        val snapshot = File(root, "resource_snapshot_${System.nanoTime()}.apk")
        apk.copyTo(snapshot)
        application.sourceDir = snapshot.path
        application.publicSourceDir = snapshot.path
        return context.packageManager.getResourcesForApplication(application)
    }

    private fun png(file: File, color: Int) {
        file.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}
