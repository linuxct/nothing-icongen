package space.linuxct.nothing.icongen

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Runs generation and persisted package reconciliation under Android's job scheduler. */
class IconPackBuildJobService : JobService() {
    private class Run(val params: JobParameters) {
        @Volatile var stopped = false
    }
    private val runs = ConcurrentHashMap<Int, Run>()

    override fun onStartJob(params: JobParameters): Boolean {
        Log.d("IconPackBuildJobService", "Job started id=${params.jobId}")
        if (params.jobId == BuildCoordinator.SETTLE_JOB_ID) {
            BuildCoordinator.settled(applicationContext)
            return false
        }
        val run = Run(params)
        runs[params.jobId] = run
        worker.execute {
            var retry = false
            try {
                if (params.jobId == DailyIconCacheCleanup.JOB_ID) {
                    DailyIconCacheCleanup.run(applicationContext, { run.stopped })
                } else if (params.jobId == PackageChangeMonitor.JOB_ID) {
                    if (!run.stopped) {
                        PackageChangeMonitor.reconcile(applicationContext)
                        DailyIconCacheCleanup.ensureScheduled(applicationContext)
                        BuildCoordinator.resumeQueued(applicationContext)
                    }
                } else {
                    while (!run.stopped) {
                        val work = params.dequeueWork() ?: break
                        if (params.jobId == BuildCoordinator.WARM_JOB_ID) {
                            BuildCoordinator.runPreparation(applicationContext, { run.stopped })
                        } else {
                            BuildCoordinator.runPending(applicationContext) { run.stopped }
                        }
                        if (!run.stopped) params.completeWork(work)
                    }
                }
            } catch (expired: SecurityException) {
                // Android can revoke the job callback before onStopJob reaches our main thread.
                // Durable requests remain queued; do not use that callback or commit more work.
                run.stopped = true
                Log.w("IconPackBuildJobService", "Job callback expired id=${params.jobId}; retaining queued work", expired)
                BuildCoordinator.waitingForScheduler(applicationContext)
            } catch (failure: Exception) {
                // Leave uncompleted items and dirty identities durable for scheduler redelivery.
                retry = true
                Log.e("IconPackBuildJobService", "Job failed id=${params.jobId}; retaining queued work", failure)
            } finally {
                runs.remove(params.jobId, run)
                if (!run.stopped) jobFinished(params, retry)
                if (!run.stopped && !retry && params.jobId == DailyIconCacheCleanup.JOB_ID) {
                    DailyIconCacheCleanup.ensureScheduled(applicationContext, replace = true)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        Log.d("IconPackBuildJobService", "Job stopped id=${params.jobId}; rescheduling unfinished work")
        runs[params.jobId]?.stopped = true
        if (params.jobId == BuildCoordinator.JOB_ID) {
            IconPackBuildService.cancelProgress(applicationContext)
            BuildCoordinator.waitingForScheduler(applicationContext)
        }
        return true
    }

    override fun onDestroy() {
        runs.values.forEach { it.stopped = true }
        super.onDestroy()
    }

    companion object {
        // A stopped job can be redelivered before its CPU-bound generation returns.
        // One process-wide executor serializes both redeliveries and periodic reconciliation.
        private val worker = Executors.newSingleThreadExecutor()

        /** Runs on the build worker so the sweep can never delete a running build's folder. */
        fun deleteStaleBuildFolders(context: Context) =
            worker.execute { IconPackBuildService.deleteStaleBuildFolders(context.applicationContext) }
    }
}
