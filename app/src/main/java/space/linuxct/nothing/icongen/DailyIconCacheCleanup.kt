package space.linuxct.nothing.icongen

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log

/** Housekeeping is independent of automatic generation; only successful sweeps advance the deadline. */
internal object DailyIconCacheCleanup {
    const val JOB_ID = 4411
    internal const val INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val PREFS = "icon_cache_cleanup"
    private const val NEXT_DUE = "next_due"

    @Synchronized
    fun ensureScheduled(context: Context, now: Long = System.currentTimeMillis(), replace: Boolean = false) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!OnboardingState.isComplete(context)) {
            scheduler.cancel(JOB_ID)
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val due = if (prefs.contains(NEXT_DUE)) prefs.getLong(NEXT_DUE, 0) else (now + INTERVAL_MS).also {
            check(prefs.edit().putLong(NEXT_DUE, it).commit()) { "Could not persist cache cleanup deadline" }
        }
        val existing = scheduler.getPendingJob(JOB_ID)
        val delay = (due - now).coerceAtLeast(0)
        if (!replace && existing?.extras?.getLong(NEXT_DUE) == due &&
            (delay > 0 || existing.minLatencyMillis == 0L)) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, IconPackBuildJobService::class.java))
            .setPersisted(true).setMinimumLatency(delay).setOverrideDeadline(delay)
            .setExtras(PersistableBundle().apply { putLong(NEXT_DUE, due) }).build()
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            Log.e("DailyIconCacheCleanup", "Android rejected daily cleanup; retaining its deadline for recovery")
        }
    }

    /** Runs on the same serialized worker as generation, never from a receiver or the UI thread. */
    fun run(
        context: Context,
        stopped: () -> Boolean,
        now: () -> Long = { System.currentTimeMillis() },
        prune: (BuildCoordinator.Cancellation) -> Unit = { IconGeneratorEngine.pruneCache(context, it) },
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val due = prefs.getLong(NEXT_DUE, Long.MAX_VALUE)
        if (now() < due) return
        val cancellation = BuildCoordinator.cancellation(context, stopped)
        cancellation.checkpoint()
        prune(cancellation)
        cancellation.commit {
            check(prefs.edit().putLong(NEXT_DUE, now() + INTERVAL_MS).commit()) {
                "Could not persist completed cache cleanup"
            }
        }
        Log.d("DailyIconCacheCleanup", "Completed daily icon cache sweep")
    }
}
