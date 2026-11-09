package space.linuxct.nothing.icongen

import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import java.io.File
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
class DirtyIconQueueTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val scheduler get() = context.getSystemService(JobScheduler::class.java)
    private val queue get() = context.getSharedPreferences("build_queue", Context.MODE_PRIVATE)
    private lateinit var artifacts: File
    private var current = emptyMap<String, String>()

    @Before fun setUp() {
        for (name in listOf("build_queue", "icon_preparation", "package_monitor", "auto_update", "onboarding", "install_state")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        OnboardingState.markComplete(context)
        BuildCoordinator.reset()
        scheduler.cancelAll()
        artifacts = File(context.cacheDir, "preparation-fixture").apply { deleteRecursively(); mkdirs() }
        current = emptyMap()
        PackageChangeMonitor.reconcileSnapshot(context, current)
    }

    @After fun tearDown() {
        artifacts.deleteRecursively()
        scheduler.cancelAll()
        BuildCoordinator.reset()
    }

    private fun observe(vararg identities: Pair<String, String>) {
        current = mapOf(*identities)
        PackageChangeMonitor.reconcileSnapshot(context, current)
    }

    /** Real durable side effects exercise the lease's commit/ack ordering, not mocked call counts. */
    private fun prepare(packages: Set<String>, cancellation: BuildCoordinator.Cancellation) {
        for ((key, identity) in current) {
            if (key.substringAfter(':') !in packages) continue
            cancellation.commit {
                File(artifacts, key).appendText("$identity\n")
            }
        }
    }

    private fun run(stopped: () -> Boolean = { false }) = BuildCoordinator.runPreparation(
        context, stopped, inventory = { current }, prepare = ::prepare)

    @Test fun burstPreparesOnlyChangedIdentitiesWithoutReleasingFinalBuild() {
        observe("0:example.a" to "1")
        val firstDeadline = queue.getLong("defer_until", 0)
        assertTrue(firstDeadline - System.currentTimeMillis() in 1..5_000L)
        assertNotNull(scheduler.getPendingJob(BuildCoordinator.WARM_JOB_ID))
        assertNull(scheduler.getPendingJob(BuildCoordinator.JOB_ID))
        run()
        assertEquals("1\n", File(artifacts, "0:example.a").readText())
        assertTrue(DirtyIconQueue.snapshot(context).entries.isEmpty())

        observe("0:example.a" to "1", "0:example.b" to "2")
        val burstDeadline = queue.getLong("defer_until", 0)
        assertTrue(burstDeadline - System.currentTimeMillis() in 25_000L..30_000L)
        run()
        run() // Duplicate scheduler deliveries cannot repeat completed preparation.
        assertEquals("1\n", File(artifacts, "0:example.a").readText())
        assertEquals("2\n", File(artifacts, "0:example.b").readText())
        assertEquals(burstDeadline, queue.getLong("defer_until", 0))
        assertNull(scheduler.getPendingJob(BuildCoordinator.JOB_ID))
        assertEquals(2L, queue.getLong("requested", 0))
        assertEquals(0L, queue.getLong("completed", 0))
        assertNull(context.getSharedPreferences("package_monitor", Context.MODE_PRIVATE)
            .getString("installed_fingerprint", null))
    }

    @Test fun updateDuringPreparationCannotBeAcknowledgedByOlderResult() {
        observe("0:example.a" to "1")
        BuildCoordinator.runPreparation(context, { false }, { current }, prepare = { packages, token ->
            prepare(packages, token)
            observe("0:example.a" to "2")
        })
        assertEquals("2", DirtyIconQueue.snapshot(context).entries.getValue("0:example.a").identity)
        run()
        assertEquals("1\n2\n", File(artifacts, "0:example.a").readText())
        assertTrue(DirtyIconQueue.snapshot(context).entries.isEmpty())
    }

    @Test fun unrelatedEventKeepsCompletedPreparationAndItsNewLease() {
        observe("0:example.a" to "1")
        BuildCoordinator.runPreparation(context, { false }, { current }, prepare = { packages, token ->
            prepare(packages, token)
            observe("0:example.a" to "1", "10:example.b" to "2")
            token.checkpoint() // The unrelated event must not cancel this valid result.
        })
        assertEquals(setOf("10:example.b"), DirtyIconQueue.snapshot(context).entries.keys)
        run()
        assertEquals("1\n", File(artifacts, "0:example.a").readText())
        assertEquals("2\n", File(artifacts, "10:example.b").readText())
    }

    @Test fun sameIdentityAfterReplacementCannotReuseOlderAcknowledgement() {
        DirtyIconQueue.record(context, mapOf("0:example.a" to "1"))
        val old = DirtyIconQueue.snapshot(context)
        DirtyIconQueue.record(context, mapOf("0:example.a" to "2"))
        DirtyIconQueue.record(context, mapOf("0:example.a" to "1"))
        DirtyIconQueue.acknowledge(context, old, mapOf("0:example.a" to "1"))
        val newer = DirtyIconQueue.snapshot(context)
        assertNotEquals(old.entries.getValue("0:example.a").revision, newer.entries.getValue("0:example.a").revision)
        assertEquals("1", newer.entries.getValue("0:example.a").identity)
    }

    @Test fun lostSchedulerAndInterruptedWorkerRecoverFromDurableSnapshot() {
        observe("0:example.a" to "1")
        val deadline = queue.getLong("defer_until", 0)
        var stopped = false
        BuildCoordinator.runPreparation(context, { stopped }, { current }, prepare = { packages, token ->
            stopped = true // Framework revokes this execution before publication.
            prepare(packages, token)
        })
        assertFalse(File(artifacts, "0:example.a").exists())
        assertEquals(setOf("0:example.a"), DirtyIconQueue.snapshot(context).entries.keys)
        scheduler.cancelAll() // Process/reboot loses all non-persisted jobs, but not preferences.
        BuildCoordinator.reset()
        BuildCoordinator.resumeQueued(context)
        assertNotNull(scheduler.getPendingJob(BuildCoordinator.WARM_JOB_ID))
        assertNotNull(scheduler.getPendingJob(BuildCoordinator.SETTLE_JOB_ID))
        assertNull(scheduler.getPendingJob(BuildCoordinator.JOB_ID))
        assertEquals(deadline, queue.getLong("defer_until", 0))
        run()
        assertEquals("1\n", File(artifacts, "0:example.a").readText())
        assertTrue(DirtyIconQueue.snapshot(context).entries.isEmpty())
        assertEquals(0L, queue.getLong("completed", 0))
    }

    @Test fun stopInvalidatesRunningPublicationAndCannotReplayUnchangedInventory() {
        observe("0:example.a" to "1")
        val before = DirtyIconQueue.snapshot(context)
        BuildCoordinator.runPreparation(context, { false }, { current }, prepare = { packages, token ->
            BuildCoordinator.stopAll(context)
            prepare(packages, token)
        })
        assertFalse(File(artifacts, "0:example.a").exists())
        assertTrue(DirtyIconQueue.snapshot(context).entries.isEmpty())
        assertNotEquals(before.epoch, DirtyIconQueue.snapshot(context).epoch)
        assertNull(scheduler.getPendingJob(BuildCoordinator.WARM_JOB_ID))
        BuildCoordinator.reset()
        PackageChangeMonitor.reconcileSnapshot(context, current)
        BuildCoordinator.resumeQueued(context)
        run()
        assertFalse(File(artifacts, "0:example.a").exists())
        assertEquals(1L, queue.getLong("requested", 0))
        assertEquals(1L, queue.getLong("completed", 0))
        assertNull(scheduler.getPendingJob(BuildCoordinator.WARM_JOB_ID))
    }

    @Test fun disablingAutomationCancelsOnlyWarmLeaseAndStillAllowsManualRequests() {
        observe("0:example.a" to "1")
        val manualToken = BuildCoordinator.cancellation(context)
        BuildCoordinator.runPreparation(context, { false }, { current }, prepare = { packages, token ->
            BuildCoordinator.setAutoUpdateEnabled(context, false)
            prepare(packages, token)
        })
        assertFalse(File(artifacts, "0:example.a").exists())
        assertTrue(DirtyIconQueue.snapshot(context).entries.isEmpty())
        assertNull(scheduler.getPendingJob(BuildCoordinator.WARM_JOB_ID))
        manualToken.checkpoint()
        observe("0:example.a" to "2")
        run()
        assertTrue(DirtyIconQueue.snapshot(context).entries.isEmpty())
        BuildCoordinator.start(context, "ui")
        assertEquals(2L, queue.getLong("manual", 0))
        assertNotNull(scheduler.getPendingJob(BuildCoordinator.JOB_ID))
    }

    @Test fun failedInventoryNeverPreparesOrAcknowledgesAndRemainsRecoverable() {
        observe("0:example.a" to "1")
        val marker = File(artifacts, "keep").apply { writeText("cached") }
        assertThrows(IllegalStateException::class.java) {
            BuildCoordinator.runPreparation(context, { false },
                inventory = { throw IllegalStateException("profile unavailable") },
                prepare = ::prepare)
        }
        assertEquals("cached", marker.readText())
        assertEquals(setOf("0:example.a"), DirtyIconQueue.snapshot(context).entries.keys)
        run()
        assertTrue(DirtyIconQueue.snapshot(context).entries.isEmpty())
    }

    @Test fun inaccessibleProfileLeaseIsNotConsumedByAnotherProfilesSuccess() {
        observe("0:example.a" to "1", "10:example.a" to "2")
        current = mapOf("0:example.a" to "1")
        run()
        assertEquals(setOf("10:example.a"), DirtyIconQueue.snapshot(context).entries.keys)
        assertEquals("1\n", File(artifacts, "0:example.a").readText())
        assertFalse(File(artifacts, "10:example.a").exists())
    }

    @Test fun quietExpiryLeavesMissesForFinalBuildWithoutPrematureAcknowledgement() {
        observe("0:example.a" to "1")
        queue.edit().putLong("defer_until", System.currentTimeMillis() - 1).commit()
        run()
        assertFalse(File(artifacts, "0:example.a").exists())
        assertEquals(setOf("0:example.a"), DirtyIconQueue.snapshot(context).entries.keys)
        assertEquals(0L, queue.getLong("completed", 0))
        BuildCoordinator.settled(context)
        assertNotNull(scheduler.getPendingJob(BuildCoordinator.JOB_ID))
    }


    @Test fun finalPreparationAfterQuietExpiryConsumesOnlyMatchingSuccessfulSnapshotEntries() {
        observe("0:example.ready" to "1", "0:example.updated" to "1",
            "0:example.failed" to "1", "10:example.hidden" to "1")
        val snapshot = DirtyIconQueue.snapshot(context)
        queue.edit().putLong("defer_until", System.currentTimeMillis() - 1).commit()
        // The final generator prepared ready/updated. Another update arrives before acknowledgement.
        current = current + ("0:example.updated" to "2")
        PackageChangeMonitor.reconcileSnapshot(context, current)
        val visible = current - "10:example.hidden"
        DirtyIconQueue.acknowledgePrepared(context, snapshot,
            mapOf("0:example.ready" to "1", "0:example.updated" to "1", "10:example.hidden" to "1"), visible)
        assertEquals(setOf("0:example.updated", "0:example.failed", "10:example.hidden"),
            DirtyIconQueue.snapshot(context).entries.keys)
        assertEquals("2", DirtyIconQueue.snapshot(context).entries.getValue("0:example.updated").identity)
        assertEquals(0L, queue.getLong("completed", 0))
        assertNull(context.getSharedPreferences("package_monitor", Context.MODE_PRIVATE)
            .getString("installed_fingerprint", null))
    }

    @Test fun finalPreparationCannotAcknowledgeAPostStopLeaseWithTheSameIdentity() {
        observe("0:example.a" to "1")
        val snapshot = DirtyIconQueue.snapshot(context)
        DirtyIconQueue.discard(context)
        DirtyIconQueue.record(context, mapOf("0:example.a" to "1"))
        DirtyIconQueue.acknowledgePrepared(context, snapshot, current, current)
        assertEquals(setOf("0:example.a"), DirtyIconQueue.snapshot(context).entries.keys)
    }
}
