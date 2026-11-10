package space.linuxct.nothing.icongen

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class ArchivedAppGenerationTest {
    @Test fun archivedLauncherArtworkProducesBothVariantsAndSurvivesCacheSweeps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packages = context.packageManager.getInstalledPackages(
            PackageManager.PackageInfoFlags.of(PackageManager.MATCH_ARCHIVED_PACKAGES))
        val archived = packages.filter { it.archiveTimeMillis > 0 }.mapTo(HashSet()) { it.packageName }
        val inventory = AppScanner.inventory(context)
        val requested = InstrumentationRegistry.getArguments().getString("archivedFixture")
        val apps = inventory.apps.filter { it.sourcePackage in archived && (requested == null || it.sourcePackage == requested) }
        if (requested == null) assumeTrue("Requires an archived launcher app", archived.isNotEmpty())
        assertTrue("Archived launcher apps must not be dropped because their APK is absent", apps.isNotEmpty())
        val root = File(context.cacheDir, "archived-render-test-${System.nanoTime()}")
        val cancellation = BuildCoordinator.cancellation(context)
        ThemeColors.invalidate()
        try {
            IconPipeline(context).use { pipeline ->
                val cache = PreparedIconCache(context, root)
                for (app in apps) {
                    assertEquals(app.sourceIdentity, PackageChangeMonitor.identity(context, app.userHandle, app.sourcePackage))
                    val entry = cache.prepare(app, pipeline, cancellation)
                    fun verify(palette: ThemeColors.Palette, file: File) {
                        val expected = pipeline.icon(app.icon, palette)
                        val encoded = ByteArrayOutputStream()
                        val actual = requireNotNull(BitmapFactory.decodeFile(file.path))
                        try {
                            check(expected.compress(Bitmap.CompressFormat.PNG, 90, encoded))
                            val bytes = encoded.toByteArray()
                            val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                            try { assertTrue("Archived artwork must match fresh post-processing", actual.sameAs(decoded)) }
                            finally { decoded.recycle() }
                            assertEquals(0, android.graphics.Color.alpha(actual.getPixel(0, 0)))
                        } finally { expected.recycle(); actual.recycle(); encoded.close() }
                    }
                    verify(cache.lightPalette, entry.lightPng)
                    verify(cache.darkPalette, entry.darkPng)
                    cache.prune(inventory)
                    assertTrue(entry.lightPng.isFile)
                    assertTrue(entry.darkPng.isFile)
                }
                val warm = PreparedIconCache(context, root)
                apps.forEach { warm.prepare(it, pipeline, cancellation) }
                assertEquals(apps.size, warm.reused)
                assertEquals(0, warm.rendered)
                assertEquals(0, warm.encoded)
            }
        } finally { root.deleteRecursively(); ThemeColors.invalidate() }
    }
}
