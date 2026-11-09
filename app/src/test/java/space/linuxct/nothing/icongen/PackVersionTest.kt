package space.linuxct.nothing.icongen

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PackVersionTest {
    @Test fun sameSecondAndRolledBackClockReservePersistedStrictlyIncreasingVersions() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("iconpack_versions", Context.MODE_PRIVATE).edit().clear().commit()
        val first = IconPackAssembler.reserveVersion(context, 1_000_000L)
        val second = IconPackAssembler.reserveVersion(context, 1_000_000L)
        assertEquals(first.code + 1, second.code)
        // A new Context must read durable state; deleting caches cannot reset version allocation.
        context.cacheDir.deleteRecursively()
        val recreated = context.createConfigurationContext(Configuration(context.resources.configuration))
        val third = IconPackAssembler.reserveVersion(recreated, 0L)
        assertEquals(second.code + 1, third.code)
        assertEquals(third.code.toLong(), recreated.getSharedPreferences("iconpack_versions", Context.MODE_PRIVATE)
            .getLong("last_code", 0))
        assertEquals("Version name and code use one reservation", third.code.toString(), third.name)
    }

    @Test fun installedPackVersionRemainsTheFloorAfterGeneratorDataReset() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("iconpack_versions", Context.MODE_PRIVATE).edit().clear().commit()
        Shadows.shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = IconPackAssembler.PACKAGE_NAME
            setLongVersionCode(1_900_000_000L)
        })
        val next = IconPackAssembler.reserveVersion(context, 1_000L)
        assertEquals(1_900_000_001, next.code)
        assertTrue(IconPackAssembler.reserveVersion(context, 1_000L).code > next.code)
    }
}
