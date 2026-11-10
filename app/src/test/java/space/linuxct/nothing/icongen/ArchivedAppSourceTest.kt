package space.linuxct.nothing.icongen

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.os.Process
import android.os.UserHandle
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [ArchivedAppSourceTest.Activities::class, ArchivedAppSourceTest.Activity::class])
class ArchivedAppSourceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var root: File
    private val user get() = Process.myUserHandle()

    @Implements(LauncherActivityInfo::class)
    class Activity {
        lateinit var info: ApplicationInfo
        @Implementation fun getComponentName() = ComponentName(info.packageName, "${info.packageName}.Main")
        @Implementation fun getApplicationInfo() = info
        @Implementation fun getFirstInstallTime() = 123L
        @Implementation fun getLabel(): CharSequence = "Retained archived activity"
    }

    @Implements(LauncherApps::class)
    class Activities {
        @Implementation fun getProfiles(): List<UserHandle> = listOf(Process.myUserHandle())
        @Implementation fun getActivityList(packageName: String?, user: UserHandle): List<LauncherActivityInfo> =
            activities.filter { packageName == null || it.componentName.packageName == packageName }
    }

    companion object { private var activities = emptyList<LauncherActivityInfo>() }

    @Before fun setUp() {
        root = File(context.cacheDir, "archived-source-test").apply { deleteRecursively(); mkdirs() }
        install(context.packageName, File(root, "own.apk").apply { writeText("own APK") })
    }
    @After fun tearDown() { activities = emptyList(); root.deleteRecursively() }

    private fun install(name: String, source: File? = null, archivedAt: Long = 0, version: Long = 7): PackageInfo {
        val info = PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply { packageName = name; sourceDir = source?.path }
            setLongVersionCode(version)
            lastUpdateTime = version * 1000
        }
        ReflectionHelpers.setField(info, "mArchiveTimeMillis", archivedAt)
        shadowOf(context.packageManager).installPackage(info)
        return info
    }

    private fun launcher(info: ApplicationInfo) {
        val activity = Shadow.newInstanceOf(LauncherActivityInfo::class.java)
        Shadow.extract<Activity>(activity).info = info
        activities = listOf(activity)
    }

    @Test fun archivedLauncherRemainsInInventoryWithoutAnApkAndCanBeRevalidated() {
        val info = install("example.archived", archivedAt = 9000)
        launcher(info.applicationInfo!!)
        val inventory = AppScanner.inventory(context)
        val app = inventory.apps.single { it.sourcePackage == info.packageName }
        assertEquals("example.archived/example.archived.Main", app.componentName)
        assertEquals(PackageChangeMonitor.identity(context, user, info.packageName), app.sourceIdentity)
        assertTrue(PackageChangeMonitor.isCacheableIdentity(app.sourceIdentity))
        assertTrue(PackageChangeMonitor.identities(context).values.contains(app.sourceIdentity))
        assertNull(PackageChangeMonitor.identity(context, user, "example.uninstalled"))
    }

    @Test fun rearchivingAndRestorationInvalidateTheRetainedIconIdentity() {
        val name = "example.archived"
        install(name, archivedAt = 9000)
        val first = PackageChangeMonitor.identity(context, user, name)
        install(name, archivedAt = 10000)
        val rearchived = PackageChangeMonitor.identity(context, user, name)
        assertNotNull(first)
        assertNotNull(rearchived)
        assertNotEquals(first, rearchived)
        val apk = File(root, "restored.apk").apply { writeText("restored APK") }
        install(name, apk, version = 8)
        val restored = PackageChangeMonitor.identity(context, user, name)
        assertNotNull(restored)
        assertNotEquals(rearchived, restored)
        apk.delete()
        assertNull("Missing APK without archive metadata must not masquerade as archived", PackageChangeMonitor.identity(context, user, name))
    }

    @Test fun otherProfileRetainedArtworkStaysRenderableWithoutPretendingItsMetadataIsCacheable() {
        val info = install("example.work", archivedAt = 9000)
        launcher(info.applicationInfo!!)
        val work = UserHandle.getUserHandleForUid(1_000_000)
        val identity = PackageChangeMonitor.identity(context, work, info.packageName)
        assertNotNull(identity)
        assertFalse(PackageChangeMonitor.isCacheableIdentity(identity!!))
        val apk = File(root, "work.apk").apply { writeText("work source") }
        info.applicationInfo!!.sourceDir = apk.path
        val installed = PackageChangeMonitor.identity(context, work, info.packageName)
        assertNotNull(installed)
        assertTrue(PackageChangeMonitor.isCacheableIdentity(installed!!))
        assertNotEquals(identity, installed)
        apk.delete()
        assertNotEquals(installed, PackageChangeMonitor.identity(context, work, info.packageName))
    }
}
