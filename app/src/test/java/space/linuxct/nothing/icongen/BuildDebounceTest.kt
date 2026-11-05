package space.linuxct.nothing.icongen

import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
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
class BuildDebounceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val scheduler get() = context.getSystemService(JobScheduler::class.java)
    private val queue get() = context.getSharedPreferences("build_queue", Context.MODE_PRIVATE)

    @Before fun setUp() {
        for (name in listOf("build_queue", "install_state", "auto_update", "onboarding")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        OnboardingState.markComplete(context)
        scheduler.cancelAll()
        BuildCoordinator.reset()
    }

    @After fun tearDown() {
        scheduler.cancelAll()
        BuildCoordinator.reset()
    }

    @Test fun recoveryRearmsRemainingQuietPeriodAfterNonPersistedJobsAreLost() {
        BuildCoordinator.start(context, "package:first")
        BuildCoordinator.start(context, "package:second")
        // Preferences survive reboot; non-persisted jobs do not. Part of the wait has elapsed.
        val deadline = System.currentTimeMillis() + 20_000L
        queue.edit().putLong("defer_until", deadline).commit()
        scheduler.cancelAll()
        BuildCoordinator.reset()

        val before = System.currentTimeMillis()
        BuildCoordinator.resumeQueued(context)
        val after = System.currentTimeMillis()

        val timer = scheduler.getPendingJob(BuildCoordinator.SETTLE_JOB_ID)
        assertNotNull("Recovery must schedule a wakeup for the remaining quiet period", timer)
        assertTrue(timer!!.minLatencyMillis in (deadline - after)..(deadline - before))
        assertNull("Recovery must not dispatch an early build instead", scheduler.getPendingJob(BuildCoordinator.JOB_ID))
        assertEquals(deadline, queue.getLong("defer_until", 0))
        assertEquals(2L, queue.getLong("requested", 0))
        assertEquals(0L, queue.getLong("completed", 0))
    }

    @Test fun earlyWorkerRearmsMissingTimerWithoutConsumingPendingWork() {
        BuildCoordinator.start(context, "package:first")
        BuildCoordinator.start(context, "package:second")
        val deadline = queue.getLong("defer_until", 0)
        scheduler.cancelAll()

        val before = System.currentTimeMillis()
        BuildCoordinator.runPending(context) { false }
        val after = System.currentTimeMillis()

        val timer = scheduler.getPendingJob(BuildCoordinator.SETTLE_JOB_ID)
        assertNotNull("An early worker must leave a future wakeup before completing its work item", timer)
        assertTrue(timer!!.minLatencyMillis in (deadline - after)..(deadline - before))
        assertNull(scheduler.getPendingJob(BuildCoordinator.JOB_ID))
        assertEquals(2L, queue.getLong("requested", 0))
        assertEquals(0L, queue.getLong("completed", 0))
    }

    @Test fun callbackFromOlderTimerCannotReleaseNewerQuietPeriod() {
        BuildCoordinator.start(context, "package:first")
        BuildCoordinator.start(context, "package:second")
        val deadline = queue.getLong("defer_until", 0)
        // An older onStartJob callback can reach the coordinator after the second update.
        val before = System.currentTimeMillis()
        BuildCoordinator.settled(context)
        val after = System.currentTimeMillis()

        assertEquals("The current deadline belongs to the newer update", deadline, queue.getLong("defer_until", 0))
        assertNull("An old callback must not release the build", scheduler.getPendingJob(BuildCoordinator.JOB_ID))
        val timer = scheduler.getPendingJob(BuildCoordinator.SETTLE_JOB_ID)
        assertNotNull(timer)
        assertTrue(timer!!.minLatencyMillis in (deadline - after)..(deadline - before))
        assertEquals(0L, queue.getLong("completed", 0))
    }

    @Test fun expiredTimerReleasesUnacknowledgedRequestForBuild() {
        BuildCoordinator.start(context, "package:first")
        queue.edit().putLong("defer_until", System.currentTimeMillis() - 1L).commit()

        BuildCoordinator.settled(context)

        assertFalse(queue.contains("defer_until"))
        assertNotNull(scheduler.getPendingJob(BuildCoordinator.JOB_ID))
        assertEquals(1L, queue.getLong("requested", 0))
        assertEquals(0L, queue.getLong("completed", 0))
    }

    @Test fun manualRequestBypassesWaitAndItsOldTimerCannotDispatchAgain() {
        BuildCoordinator.start(context, "package:first")
        BuildCoordinator.start(context, "ui")
        assertFalse(queue.contains("defer_until"))
        assertNotNull(scheduler.getPendingJob(BuildCoordinator.JOB_ID))
        assertEquals(2L, queue.getLong("manual", 0))
        // The manual job is now being processed. A late automatic timer is no longer needed.
        scheduler.cancelAll()
        BuildCoordinator.update { BuildCoordinator.State(phase = BuildCoordinator.Phase.COMPILING) }

        BuildCoordinator.settled(context)

        assertNull(scheduler.getPendingJob(BuildCoordinator.JOB_ID))
        assertNull(scheduler.getPendingJob(BuildCoordinator.SETTLE_JOB_ID))
        assertEquals(BuildCoordinator.Phase.COMPILING, BuildCoordinator.state.phase)
        assertEquals(0L, queue.getLong("completed", 0))
    }
}
