package space.linuxct.nothing.icongen

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreparedIconCacheTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File

    @Before fun setUp() {
        NativeLauncherOracle.requireLauncher(context)
        ThemeColors.invalidate()
        root = File(context.cacheDir, "prepared-native-test-${System.nanoTime()}")
    }
    @After fun tearDown() { root.deleteRecursively(); ThemeColors.invalidate() }

    private fun sample(context: Context): AppScanner.AppIcon =
        AppScanner.scanInstalledApps(context).first { it.componentName.substringBefore('/') == "com.android.vending" }

    private fun assertDecodedEquals(expected: Bitmap, file: File) {
        val bytes = ByteArrayOutputStream().use { output ->
            check(expected.compress(Bitmap.CompressFormat.PNG, 90, output))
            output.toByteArray()
        }
        val roundTrip = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        val actual = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
        try { assertTrue("Cached PNG must preserve final encoded pixels", roundTrip.sameAs(actual)) }
        finally { roundTrip.recycle(); actual.recycle(); expected.recycle() }
    }

    @Test fun warmHitAndMissingVariantPreserveFinalEncodedPixelsWithoutReencodingTheOtherVariant() {
        val app = sample(context)
        val cancellation = BuildCoordinator.cancellation(context)
        IconPipeline(context).use { pipeline ->
            val cold = PreparedIconCache(context, root)
            val entry = cold.prepare(app, pipeline, cancellation)
            assertEquals(1, cold.rendered)
            assertEquals(2, cold.encoded)
            assertDecodedEquals(pipeline.icon(app.icon, cold.lightPalette), entry.lightPng)
            assertDecodedEquals(pipeline.icon(app.icon, cold.darkPalette), entry.darkPng)
            val light = entry.lightPng.readBytes()
            val dark = entry.darkPng.readBytes()
            entry.lightPng.setLastModified(1)
            val warm = PreparedIconCache(context, root)
            val reused = warm.prepare(app, pipeline, cancellation)
            assertEquals(1, warm.reused)
            assertEquals(0, warm.rendered)
            assertEquals(0, warm.encoded)
            assertEquals(1L, reused.lightPng.lastModified())
            assertArrayEquals(light, reused.lightPng.readBytes())
            assertArrayEquals(dark, reused.darkPng.readBytes())
            assertTrue(reused.darkPng.delete())
            val repaired = PreparedIconCache(context, root)
            val restored = repaired.prepare(app, pipeline, cancellation)
            assertEquals(1, repaired.rendered)
            assertEquals(1, repaired.encoded)
            assertEquals(1L, restored.lightPng.lastModified())
            assertArrayEquals(light, restored.lightPng.readBytes())
            assertArrayEquals(dark, restored.darkPng.readBytes())
        }
    }

    @Test fun densityConfigurationInvalidatesFinalPixelsAndMatchesFreshRendering() {
        val cancellation = BuildCoordinator.cancellation(context)
        IconPipeline(context).use { pipeline ->
            PreparedIconCache(context, root).prepare(sample(context), pipeline, cancellation)
        }
        val configuration = Configuration(context.resources.configuration).apply { densityDpi += 80 }
        val changed = context.createConfigurationContext(configuration)
        val app = sample(changed)
        val cache = PreparedIconCache(changed, root)
        IconPipeline(changed).use { pipeline ->
            val entry = cache.prepare(app, pipeline, cancellation)
            assertEquals(0, cache.reused)
            assertEquals(1, cache.rendered)
            assertEquals(2, cache.encoded)
            assertDecodedEquals(pipeline.icon(app.icon, cache.lightPalette), entry.lightPng)
            assertDecodedEquals(pipeline.icon(app.icon, cache.darkPalette), entry.darkPng)
        }
    }
}
