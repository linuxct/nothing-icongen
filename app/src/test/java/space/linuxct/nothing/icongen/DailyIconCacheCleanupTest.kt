package space.linuxct.nothing.icongen

import android.app.Application
import android.app.job.JobScheduler
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
class DailyIconCacheCleanupTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val scheduler get() = context.getSystemService(JobScheduler::class.java)
    private val prefs get() = context.getSharedPreferences("icon_cache_cleanup", Context.MODE_PRIVATE)
    private lateinit var root: File
    private lateinit var store: PreparedIconStore
    private lateinit var obsolete: File
    private lateinit var live: File
    private var now = 1_000L
    private val day = DailyIconCacheCleanup.INTERVAL_MS

    @Before fun setUp() {
        for (name in listOf("icon_cache_cleanup", "onboarding", "build_queue", "install_state", "auto_update")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        BuildCoordinator.reset()
        OnboardingState.markComplete(context)
        scheduler.cancelAll()
        root = File(context.cacheDir, "daily-cleanup-test").apply { deleteRecursively() }
        store = PreparedIconStore(root)
        fun icon(component: String) = store.publish(store.group(0, component), store.key("source"),
            BuildCoordinator.cancellation(context)) { it.writeText("cached final PNG fixture") }
        obsolete = icon("removed/.Main")
        live = icon("installed/.Main")
        DailyIconCacheCleanup.ensureScheduled(context, now)
    }
    @After fun tearDown() { root.deleteRecursively(); scheduler.cancelAll(); BuildCoordinator.reset() }

    private fun run() = DailyIconCacheCleanup.run(context, { false }, { now }) { token ->
        token.commit { store.prune(setOf(store.group(0, "installed/.Main")), setOf(0)) }
    }

    @Test fun noCleanupBefore24HoursAndOnlySuccessfulSweepStartsTheNextDay() {
        val due = prefs.getLong("next_due", 0)
        val job = scheduler.getPendingJob(DailyIconCacheCleanup.JOB_ID)!!
        assertFalse(job.isPeriodic)
        assertTrue(job.isPersisted)
        assertEquals(day, job.minLatencyMillis)
        assertEquals(day, job.maxExecutionDelayMillis)
        now = due - 1
        run()
        assertTrue(obsolete.exists())
        assertEquals(due, prefs.getLong("next_due", 0))
        now = due
        run()
        assertFalse(obsolete.exists())
        assertTrue(live.exists())
        assertEquals(now + day, prefs.getLong("next_due", 0))
        DailyIconCacheCleanup.ensureScheduled(context, now, replace = true)
        assertEquals(day, scheduler.getPendingJob(DailyIconCacheCleanup.JOB_ID)!!.minLatencyMillis)
    }

    @Test fun restartsAndRepeatedMonitoringCannotPostponeAnOverdueSweep() {
        val due = prefs.getLong("next_due", 0)
        now += day / 2
        DailyIconCacheCleanup.ensureScheduled(context, now)
        assertEquals(due, prefs.getLong("next_due", 0))
        scheduler.cancelAll() // Simulate loss of the scheduled job while the saved deadline survives.
        now = due + day * 2
        DailyIconCacheCleanup.ensureScheduled(context, now)
        val recovered = scheduler.getPendingJob(DailyIconCacheCleanup.JOB_ID)!!
        assertEquals(0L, recovered.minLatencyMillis)
        assertEquals(0L, recovered.maxExecutionDelayMillis)
        assertEquals(due, prefs.getLong("next_due", 0))
        run()
        assertFalse(obsolete.exists())
        assertEquals(now + day, prefs.getLong("next_due", 0))
    }

    @Test fun forwardClockChangeMakesAnExistingFutureJobImmediatelyDue() {
        now += day * 2
        DailyIconCacheCleanup.ensureScheduled(context, now)
        assertEquals(0L, scheduler.getPendingJob(DailyIconCacheCleanup.JOB_ID)!!.minLatencyMillis)
        run()
        assertFalse(obsolete.exists())
    }

    @Test fun failureAndInterruptionKeepTheOriginalDeadlineForRetry() {
        val due = prefs.getLong("next_due", 0)
        now = due
        assertThrows(IOException::class.java) {
            DailyIconCacheCleanup.run(context, { false }, { now }) { throw IOException("Inventory unavailable") }
        }
        assertTrue(obsolete.exists())
        assertEquals(due, prefs.getLong("next_due", 0))
        assertThrows(CancellationException::class.java) {
            DailyIconCacheCleanup.run(context, { false }, { now }) { token -> token.cancel() }
        }
        assertEquals(due, prefs.getLong("next_due", 0))
        run()
        assertFalse(obsolete.exists())
        assertEquals(now + day, prefs.getLong("next_due", 0))
    }

    @Test fun housekeepingDoesNotRequireAutomaticGenerationOrUninstallEvents() {
        context.getSharedPreferences("auto_update", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).commit()
        now += day
        run()
        assertFalse(obsolete.exists())
        assertTrue(live.exists())
        assertFalse(context.getSharedPreferences("build_queue", Context.MODE_PRIVATE).contains("requested"))
    }
}
