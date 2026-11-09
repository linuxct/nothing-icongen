package space.linuxct.nothing.icongen

import android.app.Application
import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PreparedIconStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var root: File
    private lateinit var store: PreparedIconStore
    private val pixels = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3, 4)

    @Before fun setUp() {
        context.getSharedPreferences("build_queue", Context.MODE_PRIVATE).edit().clear().commit()
        root = File(context.cacheDir, "prepared-store-test").apply { deleteRecursively(); mkdirs() }
        store = PreparedIconStore(root)
    }
    @After fun tearDown() { root.deleteRecursively() }

    private fun publish(profile: Long, component: String, identity: String = "version-1"): File =
        store.publish(store.group(profile, component), store.key(identity), BuildCoordinator.cancellation(context)) {
            it.writeBytes(pixels)
        }

    @Test fun profilesAndComponentsCannotReuseEachOthersPixels() {
        val first = publish(0, "pkg/.One")
        val work = publish(10, "pkg/.One")
        val second = publish(0, "pkg/.Two")
        first.writeBytes(byteArrayOf(9))
        assertNull(store.find(store.group(0, "pkg/.One"), store.key("version-1")))
        assertArrayEquals(pixels, store.find(store.group(10, "pkg/.One"), store.key("version-1"))!!.readBytes())
        assertArrayEquals(pixels, store.find(store.group(0, "pkg/.Two"), store.key("version-1"))!!.readBytes())
        assertNotEquals(work, second)
    }

    @Test fun changedInputsNeverFallBackToOldPixelsAndCorruptionIsRepaired() {
        val old = publish(0, "pkg/.One")
        val group = store.group(0, "pkg/.One")
        assertNull(store.find(group, store.key("version-2")))
        val current = publish(0, "pkg/.One", "version-2")
        current.writeBytes(byteArrayOf(0, 0, 0))
        assertNull(store.find(group, store.key("version-2")))
        assertArrayEquals(pixels, old.readBytes())
        assertArrayEquals(pixels, publish(0, "pkg/.One", "version-2").readBytes())
        File(current.parentFile, "sha256").delete()
        assertNull(store.find(group, store.key("version-2")))
    }

    @Test fun failedProducerAndStopCannotPublishIncompleteEntries() {
        val group = store.group(0, "pkg/.One")
        val key = store.key("version-1")
        try {
            store.publish(group, key, BuildCoordinator.cancellation(context)) {
                it.writeBytes(pixels)
                throw IOException("Encoder failed")
            }
            fail("Producer failure must propagate")
        } catch (_: IOException) { }
        assertNull(store.find(group, key))
        val cancellation = BuildCoordinator.cancellation(context)
        try {
            store.publish(group, key, cancellation) {
                it.writeBytes(pixels)
                cancellation.cancel()
            }
            fail("Stopped preparation must not publish")
        } catch (_: CancellationException) { }
        assertNull(store.find(group, key))
        assertFalse(root.walkTopDown().any { it.name.startsWith(".pending-") })
        assertArrayEquals(pixels, publish(0, "pkg/.One").readBytes())
    }

    @Test fun changedSourceBeforeCommitCannotPoisonTheCurrentEntry() {
        val group = store.group(0, "pkg/.One")
        val key = store.key("version-2")
        publish(0, "pkg/.One", "version-1")
        try {
            store.publish(group, key, BuildCoordinator.cancellation(context), validate = {
                throw IOException("Package changed during rendering")
            }) { it.writeBytes(pixels) }
            fail("Stale work must be rejected")
        } catch (_: IOException) { }
        assertNull(store.find(group, key))
        assertArrayEquals(pixels, store.find(group, store.key("version-1"))!!.readBytes())
    }

    @Test fun authoritativeSweepPreservesUnavailableProfilesAndOtherLiveComponents() {
        val removed = publish(0, "gone/.Main")
        val kept = publish(0, "live/.Main")
        val locked = publish(10, "work/.Main")
        store.prune(setOf(store.group(0, "live/.Main")), setOf(0))
        assertFalse(removed.exists())
        assertTrue(kept.exists())
        assertTrue(locked.exists())
        store.prune(setOf(store.group(0, "live/.Main")), setOf(0, 10))
        assertFalse(locked.exists())
    }

    @Test fun evictionBoundsStorageWithoutRemovingActiveInputs() {
        val oldest = publish(0, "old/.Main")
        val active = publish(0, "active/.Main")
        oldest.parentFile!!.setLastModified(1)
        PreparedIconStore(root, maxBytes = 0).trim(setOf(active.parentFile!!))
        assertFalse(oldest.exists())
        assertArrayEquals(pixels, active.readBytes())
        PreparedIconStore(root, maxBytes = 0).trim()
        assertFalse(active.exists())
    }

    @Test fun animatedSnapshotsAreAvailableToThisBuildButNeverReused() {
        val group = store.group(0, "animated/.Main")
        val key = store.key("version-1")
        val snapshot = store.publish(group, key, BuildCoordinator.cancellation(context), reusable = false) {
            it.writeBytes(pixels)
        }
        assertArrayEquals(pixels, snapshot.readBytes())
        assertNull(store.find(group, key))
    }
}
