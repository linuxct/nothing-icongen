package space.linuxct.nothing.icongen

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Xml
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xmlpull.v1.XmlPullParser

/** Exercises consumer lookup semantics, not the installed launcher's rendering pipeline. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class LauncherOnlyAppfilterTest {
    @Test fun packageDefaultsOptOutWhileEveryActivityStillLoadsGeneratedArtwork() {
        val context = RuntimeEnvironment.getApplication()
        val output = File(context.cacheDir, "launcher-only-appfilter")
        val components = listOf(
            "example.multi/example.multi.Main",
            "example.other/example.other.Main",
            "example.multi/example.multi.Host\$Nested",
        )
        val glyphs = components.indices.map { index ->
            val glyph = Bitmap.createBitmap(
                IconArtwork.ICON_SIZE, IconArtwork.ICON_SIZE, Bitmap.Config.ARGB_8888,
            )
            val x = 120f + index * 96f
            Canvas(glyph).drawRect(x - 32f, 184f, x + 32f, 248f, Paint().apply { color = Color.WHITE })
            LauncherGlyphs(glyph)
        }
        try {
            val zip = IconPackAssembler.assembleZip(
                context,
                output,
                components.mapIndexed { index, name -> IconPackAssembler.PackEntry(name, name, glyphs[index]) },
                ThemeColors.Palette(0xFF272B2D.toInt(), 0x66FFFFFF, false),
            )
            try {
                val packageFirst = mutableMapOf<String, String>()
                val activityExact = mutableMapOf<ComponentName, String>()
                File(output, "res/xml/appfilter.xml").reader().use { reader ->
                    val parser = Xml.newPullParser()
                    parser.setInput(reader)
                    while (parser.next() != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType != XmlPullParser.START_TAG || parser.name != "item") continue
                        val encoded = parser.getAttributeValue(null, "component")
                        val flattened = encoded.removePrefix("ComponentInfo{").removeSuffix("}")
                        val component = ComponentName(
                            flattened.substringBefore('/'), flattened.substringAfter('/'),
                        )
                        val drawable = parser.getAttributeValue(null, "drawable")
                        // Package consumers retain the first entry, including an empty default.
                        if (!packageFirst.containsKey(component.packageName)) {
                            packageFirst[component.packageName] = drawable
                        }
                        activityExact[component] = drawable
                    }
                }
                for ((index, flattened) in components.withIndex()) {
                    val component = requireNotNull(ComponentName.unflattenFromString(flattened))
                    assertTrue(packageFirst.containsKey(component.packageName))
                    val systemDrawable = packageFirst[component.packageName]?.takeIf { it.isNotEmpty() }
                    assertNull("Package lookup must fall back to the app icon", systemDrawable)
                    val drawable = requireNotNull(activityExact[component])
                    assertTrue("An exact launcher match must not be blank", drawable.isNotEmpty())
                    for (directory in listOf("drawable", "drawable-night")) {
                        val image = BitmapFactory.decodeFile(File(output, "res/$directory/$drawable.png").path)
                        assertNotNull("Exact launcher match must load generated artwork", image)
                        requireNotNull(image)
                        try {
                            val markerX = 120 + index * 96
                            val otherX = 120 + ((index + 1) % components.size) * 96
                            assertNotEquals(
                                "Exact activity lookup must show its own glyph, not another activity or a blank",
                                image.getPixel(otherX, 216), image.getPixel(markerX, 216),
                            )
                            assertEquals("Circular artwork must not fill the corner", 0, Color.alpha(image.getPixel(0, 0)))
                        } finally { image.recycle() }
                    }
                }
            } finally { zip.delete() }
        } finally {
            glyphs.forEach { it.recycle() }
            output.deleteRecursively()
        }
    }

    @Test fun keepThemeOnUpdateDropsPackageDefaults() {
        val context = RuntimeEnvironment.getApplication()
        val output = File(context.cacheDir, "keep-theme-appfilter")
        val glyph = Bitmap.createBitmap(IconArtwork.ICON_SIZE, IconArtwork.ICON_SIZE, Bitmap.Config.ARGB_8888)
        IconPackAssembler.setKeepThemeOnUpdate(context, true)
        try {
            IconPackAssembler.assembleZip(
                context, output,
                listOf(IconPackAssembler.PackEntry("example.app/example.app.Main", "App", LauncherGlyphs(glyph))),
                ThemeColors.Palette(0xFF272B2D.toInt(), 0x66FFFFFF, false),
            ).delete()
            val appfilter = File(output, "res/xml/appfilter.xml").readText()
            assertTrue(appfilter.contains("ComponentInfo{example.app/example.app.Main}"))
            assertTrue("No package-wide default expected", !appfilter.contains("ComponentInfo{example.app/}"))
        } finally {
            IconPackAssembler.setKeepThemeOnUpdate(context, false)
            glyph.recycle()
            output.deleteRecursively()
        }
    }
}
