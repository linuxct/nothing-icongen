package space.linuxct.nothing.icongen

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Android drops notification updates posted many times per second; progress posting must coalesce safely. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BuildProgressNotificationTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before fun setUp() {
        context.getSharedPreferences("notification_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("build_queue", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
        BuildCoordinator.reset()
        IconPackBuildService.cancelProgress(context)
    }

    @After fun tearDown() {
        IconPackBuildService.cancelProgress(context)
        BuildCoordinator.reset()
        manager.cancelAll()
    }

    private fun post(phase: BuildCoordinator.Phase, message: String, percent: Int, detail: String? = null) {
        BuildCoordinator.update { it.copy(phase = phase, message = message, percent = percent, detail = detail) }
        IconPackBuildService.updateProgress(context, BuildCoordinator.state)
    }

    private fun generating(completed: Int, total: Int) =
        post(BuildCoordinator.Phase.GENERATING, "Generating icons ($completed/$total)", 10 + 70 * completed / total, "$completed/$total")

    private fun activeText() = manager.activeNotifications.singleOrNull { it.id == 42 }
        ?.let { shadowOf(it.notification).contentText.toString() }

    @Test fun rapidIconUpdatesCoalesceToTheLatestState() {
        generating(1, 10)
        for (i in 2..5) generating(i, 10)
        assertEquals("Generating icons (1/10)", activeText())
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertEquals("Generating icons (5/10)", activeText())
    }

    @Test fun theFinalCountPublishesImmediatelyAndTheThrottleCannotRegressIt() {
        generating(1, 10)
        generating(2, 10) // Held behind the throttle; the milestone must not be stranded behind it.
        generating(10, 10)
        assertEquals("Generating icons (10/10)", activeText())
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        assertEquals("Generating icons (10/10)", activeText())
    }

    @Test fun aPhaseChangePublishesImmediatelyAtAnUnchangedPercent() {
        post(BuildCoordinator.Phase.GENERATING, "Preparing resources (3/3)", 80, "3/3")
        post(BuildCoordinator.Phase.COMPILING, "Compiling APK", 80)
        assertEquals("Compiling APK", activeText())
    }

    @Test fun stoppingClearsAQueuedUpdateWithoutResurrectingTheProgressNotification() {
        generating(1, 10)
        generating(2, 10)
        BuildCoordinator.stopAll(context)
        IconPackBuildService.updateProgress(context, BuildCoordinator.state)
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        assertNull(activeText())
    }

    @Test fun aTerminalStateClearsTheQueuedUpdateWithoutResurrectingTheProgressNotification() {
        generating(1, 10)
        generating(2, 10)
        BuildCoordinator.update { it.copy(phase = BuildCoordinator.Phase.DONE, message = "Icon pack installed", percent = 100) }
        IconPackBuildService.updateProgress(context, BuildCoordinator.state)
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        assertNull(activeText())
    }

    @Test fun aSingleIconBuildPublishesItsOnlyCountImmediately() {
        generating(1, 1)
        assertEquals("Generating icons (1/1)", activeText())
    }

    @Test fun aSupersededBuildCannotPostItsPendingProgress() {
        generating(1, 10)
        generating(2, 10) // Queued for the throttle window.
        IconPackBuildService.cancelProgress(context) // The superseded worker's cleanup path.
        BuildCoordinator.reset() // New generation: the queued pair is no longer the coordinator state.
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        assertNull(activeText())
    }
}
