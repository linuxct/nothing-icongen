package space.linuxct.nothing.icongen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Prepared-directory and configuration-dependent palette integration checks run on-device. */
@RunWith(AndroidJUnit4::class)
class PackAssemblyTest {
    @Test
    fun directoryMapsEveryComponentAndPreservesRealDayNightTokensAndNativeMaskPolarity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launcher = NativeLauncherOracle.requireLauncher(context)
        val normal = mask(90f)
        val light = mask(282f)
        val glyphs = LauncherGlyphs(normal, light)
        val root = File(context.cacheDir, "pack_behavior_${System.nanoTime()}").apply { check(mkdirs()) }
        val lightPng = File(root, "light.png")
        val darkPng = File(root, "dark.png")
        PackIconEncoder().use { encoder ->
            for ((dark, file) in listOf(false to lightPng, true to darkPng)) {
                val palette = ThemeColors.resolve(context, dark)
                val artwork = IconArtwork.buildIcon(palette, glyphs.forColor(palette.foreground))
                try {
                    val encoded = encoder.encode(artwork)
                    try { file.outputStream().use { check(encoded.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                    finally { encoded.recycle() }
                } finally { artwork.recycle() }
            }
        }
        val entries = listOf(
            IconPackAssembler.PackEntry("com.example.reference/com.example.reference.First", "First", lightPng, darkPng),
            IconPackAssembler.PackEntry("com.example.reference/com.example.reference.Second", "Second", lightPng, darkPng),
        )
        val host = NativePackRasterizer(context, IconArtwork.ICON_SIZE)
        try {
            // The assembler must use prepared artwork verbatim, not recolor it from this palette.
            val supplied = ThemeColors.Palette(Color.MAGENTA, Color.GREEN, true)
            val resources = IconPackAssembler.prepareResources(context, File(root, "resources"), entries, supplied)
            run {
                assertTrue("missing manifest", File(resources, "AndroidManifest.xml").isFile)
                val xml = File(resources, "res/xml/appfilter.xml").readText()
                val parser = android.util.Xml.newPullParser().apply { setInput(xml.reader()) }
                val packageDefaults = linkedMapOf<String, String>()
                val mappings = linkedMapOf<String, String>()
                val components = mutableSetOf<String>()
                while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "item") {
                        val component = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        requireNotNull(component) { "item without component" }
                        requireNotNull(drawable) { "item without drawable" }
                        assertTrue("duplicate mapping: $component", components.add(component))
                        val flattened = component.removePrefix("ComponentInfo{").removeSuffix("}")
                        val name = android.content.ComponentName(
                            flattened.substringBefore('/'), flattened.substringAfter('/'))
                        if (name.className.isEmpty()) {
                            assertEquals("package default must suppress system replacement", "", drawable)
                            assertTrue("package default follows an activity: $component",
                                mappings.keys.none { it.startsWith("ComponentInfo{${name.packageName}/") })
                            packageDefaults[name.packageName] = drawable
                        } else {
                            assertTrue("activity lacks preceding package default: $component",
                                packageDefaults.containsKey(name.packageName))
                            assertTrue("activity drawable must not be empty: $component", drawable.isNotEmpty())
                            mappings[component] = drawable
                        }
                    }
                    parser.next()
                }
                assertEquals("one empty default per package",
                    entries.map { it.componentName.substringBefore('/') }.toSet(), packageDefaults.keys)
                assertEquals("one mapping per activity", entries.size, mappings.size)
                assertEquals("same-package activities overwrote one another", entries.size, mappings.values.toSet().size)
                for (entry in entries) {
                    val drawable = requireNotNull(mappings["ComponentInfo{${entry.componentName}}"])
                    for (dark in listOf(false, true)) {
                        val palette = ThemeColors.resolve(context, dark)
                        val path = "res/${if (dark) "drawable-night" else "drawable"}/$drawable.png"
                        val prepared = File(resources, path)
                        assertTrue("missing $path", prepared.isFile)
                        val bitmap = requireNotNull(BitmapFactory.decodeFile(prepared.path))
                        val displayed = host.render(bitmap)
                        try {
                            assertEquals(IconArtwork.ICON_SIZE, bitmap.width)
                            assertEquals(IconArtwork.ICON_SIZE, bitmap.height)
                            val platePixel = displayed.getPixel(216, 120)
                            assertDisplayedPixel("$path final background", palette.background, platePixel)
                            assertEquals("$path outside circular plate", 0, Color.alpha(bitmap.getPixel(0, 0)))
                            val pairClass = launcher.classLoader.loadClass("ga.c")
                            val pair = pairClass.getConstructor(Bitmap::class.java, Bitmap::class.java).newInstance(normal, light)
                            val selected = pairClass.getMethod("a", Int::class.javaPrimitiveType).invoke(pair, palette.foreground)
                            val inkX = if (selected === normal) 120 else 312
                            val blankX = if (selected === normal) 312 else 120
                            val swatch = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                            val expectedInk = try {
                                Canvas(swatch).apply {
                                    drawColor(palette.background)
                                    drawColor(palette.foreground)
                                }
                                swatch.getPixel(0, 0)
                            } finally { swatch.recycle() }
                            assertDisplayedPixel("$path foreground/native polarity", expectedInk, displayed.getPixel(inkX, 216))
                            assertDisplayedPixel("$path unselected mask leaked", palette.background, displayed.getPixel(blankX, 216))
                            assertNotEquals("caller placeholder palette leaked", Color.MAGENTA, displayed.getPixel(inkX, 216))
                        } finally { bitmap.recycle(); displayed.recycle() }
                    }
                }
                val names = resources.walkTopDown().filter { it.isFile }
                    .map { it.relativeTo(resources).invariantSeparatorsPath }.toSet()
                val day = names.filter { it.startsWith("res/drawable/") && it.endsWith(".png") }.map { it.substringAfterLast('/') }.toSet()
                val night = names.filter { it.startsWith("res/drawable-night/") && it.endsWith(".png") }.map { it.substringAfterLast('/') }.toSet()
                assertEquals("day/night resource sets", day, night)
                assertTrue("no drawable resources", day.isNotEmpty())
            }
        } finally { host.close(); glyphs.recycle(); root.deleteRecursively() }
    }

    private fun mask(left: Float): Bitmap =
        Bitmap.createBitmap(IconArtwork.ICON_SIZE, IconArtwork.ICON_SIZE, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawRect(left, 180f, left + 60f, 252f, Paint().apply { color = Color.WHITE })
        }
}
