package space.linuxct.nothing.icongen

import android.content.ComponentName
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.DexClassLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.lang.reflect.Proxy
import java.util.Locale

/** Executes the installed pack's compiled XML with both real Nothing icon consumers. */
@RunWith(AndroidJUnit4::class)
class LauncherOnlyConsumerTest {
    @Test
    fun systemHelperDoesNotReplaceAnyDeclaredPackageOrActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pack = installedPack(context)
        val classpath = listOf("/system/framework/nt-services.jar", "/system/framework/services.jar")
        val helperClass = try {
            classpath.forEach { path ->
                check(File(path).canRead()) { "System helper classpath is not readable: $path" }
            }
            // Load the read-only device implementation, never a copied/decompiled substitute.
            DexClassLoader(classpath.joinToString(File.pathSeparator), context.codeCacheDir.absolutePath,
                null, context.classLoader).loadClass("com.nothing.server.ex.NtIconPackHelper")
        } catch (failure: Throwable) {
            throw AssertionError("Cannot load actual NtIconPackHelper from ${classpath.joinToString()}", failure)
        }
        val helper = try {
            helperClass.getConstructor(Context::class.java).newInstance(context).also { instance ->
                helperClass.getDeclaredField("d").apply { isAccessible = true }.set(instance, pack.resources)
                helperClass.getDeclaredField("g").apply { isAccessible = true }.set(instance, PACK_PACKAGE)
                val parse = helperClass.getDeclaredMethod("i", XmlPullParser::class.java).apply {
                    isAccessible = true
                }
                pack.resources.getXml(pack.appfilterId).use { parse.invoke(instance, it) }
            }
        } catch (failure: Throwable) {
            throw AssertionError("Cannot initialize/parse with actual NtIconPackHelper; classpath=" +
                classpath.joinToString(), failure)
        }
        // Its parser catches exceptions internally: prove it consumed every compiled item.
        val componentMap = helperClass.getDeclaredField("e").apply { isAccessible = true }.get(helper)
        assertEquals("system helper did not consume the compiled component map", pack.items, componentMap)
        val packageMap = helperClass.getDeclaredField("f").apply { isAccessible = true }.get(helper)
        assertEquals("system helper must retain the first, empty package drawable",
            pack.packages.associate { it.lowercase(Locale.getDefault()) to "" }, packageMap)
        val getIcon = helperClass.getDeclaredMethod("c", ComponentName::class.java).apply {
            isAccessible = true
        }
        for (packageName in pack.packages) {
            assertNull("system replaced package $packageName",
                getIcon.invoke(helper, ComponentName(packageName, "")))
        }
        for (component in pack.activities.keys) {
            assertNull("system replaced activity $component", getIcon.invoke(helper, component))
        }
        println("LAUNCHER_ONLY_SYSTEM packages=${pack.packages.size} activities=${pack.activities.size}")
    }

    @Test
    fun launcherResolvesEveryDeclaredActivityWithoutUsingApkFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launcher = NativeLauncherTestRuntime.application(context)
        val pack = installedPack(launcher)
        val loader = launcher.classLoader
        val entityClass = loader.loadClass("ea.b")
        val entity = entityClass.getConstructor(String::class.java, String::class.java)
            .newInstance(PACK_PACKAGE, "Generated pack consumer regression")
        val supplierClass = loader.loadClass("ze.a")
        var fallbackCalls = 0
        val fallback = Proxy.newProxyInstance(loader, arrayOf(supplierClass)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    fallbackCalls++
                    throw AssertionError("Launcher requested defaultIconFromApk for an explicitly mapped activity")
                }
                "toString" -> "Forbidden defaultIconFromApk"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw AssertionError("Unexpected defaultIconFromApk method: $method")
            }
        }
        val resolve = entityClass.getMethod("f", Context::class.java, ComponentName::class.java,
            Int::class.javaPrimitiveType, supplierClass)
        for ((component, drawableName) in pack.activities) {
            val drawableId = pack.resources.getIdentifier(drawableName, "drawable", PACK_PACKAGE)
            assertTrue("missing generated drawable for $component", drawableId != 0)
            val expected = raster(pack.resources.getDrawable(drawableId, null))
            try {
                val resolved = resolve.invoke(entity, launcher, component, IconArtwork.ICON_SIZE, fallback)
                assertTrue("launcher did not resolve $component", resolved is Drawable)
                val actual = raster(resolved as Drawable)
                try {
                    assertTrue("launcher returned a different drawable for $component ($drawableName)",
                        expected.sameAs(actual))
                } finally { actual.recycle() }
            } finally { expected.recycle() }
        }
        assertEquals("launcher used APK fallback", 0, fallbackCalls)
        // Nested classes are checked through the same exact ComponentName path, when installed.
        val nested = pack.activities.keys.count { '$' in it.className }
        println("LAUNCHER_ONLY_LAUNCHER activities=${pack.activities.size} nested=$nested fallbackCalls=$fallbackCalls")
    }

    private fun installedPack(context: Context): InstalledPack {
        // Read the currently installed, compiled resource, not the prepared source XML.
        val resources = context.packageManager.getResourcesForApplication(PACK_PACKAGE)
        val appfilterId = resources.getIdentifier("appfilter", "xml", PACK_PACKAGE)
        assertTrue("Install a freshly generated $PACK_PACKAGE before running this regression", appfilterId != 0)
        val items = linkedMapOf<String, String>()
        val packages = linkedSetOf<String>()
        val activities = linkedMapOf<ComponentName, String>()
        resources.getXml(appfilterId).use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "item") {
                    val component = requireNotNull(parser.getAttributeValue(null, "component"))
                    val drawable = requireNotNull(parser.getAttributeValue(null, "drawable"))
                    assertTrue("malformed component: $component",
                        component.startsWith("ComponentInfo{") && component.endsWith("}"))
                    assertTrue("duplicate compiled mapping: $component", !items.containsKey(component))
                    items[component] = drawable
                    val flattened = component.removePrefix("ComponentInfo{").removeSuffix("}")
                    assertTrue("missing component separator: $component", '/' in flattened)
                    val packageName = flattened.substringBefore('/')
                    val className = flattened.substringAfter('/')
                    assertTrue("empty component package: $component", packageName.isNotEmpty())
                    if (className.isEmpty()) {
                        assertEquals("package default must suppress system replacement", "", drawable)
                        assertTrue("duplicate package default: $packageName", packages.add(packageName))
                        assertTrue("package default follows an activity: $component",
                            activities.keys.none { it.packageName == packageName })
                    } else {
                        assertTrue("activity lacks a preceding empty package default: $component",
                            packageName in packages)
                        assertTrue("empty activity drawable: $component", drawable.isNotEmpty())
                        val name = requireNotNull(ComponentName.unflattenFromString(flattened))
                        assertEquals("component is not a canonical full name", component, name.toString())
                        assertNull("duplicate activity: $component", activities.put(name, drawable))
                    }
                }
                parser.next()
            }
        }
        assertTrue("installed pack has no generated activities", activities.isNotEmpty())
        assertEquals("package defaults and activities must cover the same packages",
            activities.keys.map { it.packageName }.toSet(), packages)
        assertEquals("activity drawables must be unique", activities.size, activities.values.toSet().size)
        return InstalledPack(resources, appfilterId, items, packages, activities)
    }

    private fun raster(drawable: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(IconArtwork.ICON_SIZE, IconArtwork.ICON_SIZE, Bitmap.Config.ARGB_8888)
        val oldBounds = Rect(drawable.bounds)
        try {
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
        } finally { drawable.bounds = oldBounds }
        return bitmap
    }

    private data class InstalledPack(
        val resources: Resources,
        val appfilterId: Int,
        val items: Map<String, String>,
        val packages: Set<String>,
        val activities: Map<ComponentName, String>,
    )

    private companion object {
        const val PACK_PACKAGE = "com.nothing.icongen.pack"
    }
}
