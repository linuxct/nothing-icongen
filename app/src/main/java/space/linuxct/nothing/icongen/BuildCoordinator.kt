package space.linuxct.nothing.icongen

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobWorkItem
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.CancellationException

/** UI state and durable, coalesced requests. Only the job's single worker runs the pipeline. */
object BuildCoordinator {
    enum class Phase { IDLE, QUEUED, SCANNING, GENERATING, COMPILING, INSTALLING, WAITING_PERMISSION, WAITING_CONFIRMATION, DONE, FAILED, STOPPED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val message: String = "",
        val percent: Int = 0,
        val detail: String? = null,
        val error: String? = null,
        val apkPath: String? = null,
        val startedAt: Long = 0L,
    ) {
        val isRunning: Boolean
            get() = phase == Phase.SCANNING || phase == Phase.GENERATING ||
                phase == Phase.COMPILING || phase == Phase.INSTALLING
    }

    @Volatile var state: State = State()
        private set
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(State) -> Unit>()
    private const val PREFS = "build_queue"
    private const val REQUESTED = "requested"
    private const val COMPLETED = "completed"
    private const val MANUAL = "manual"
    private const val CANCELLATION = "cancellation_generation"
    private const val STOPPED_MESSAGE = "stopped_message"
    private const val DEFER_UNTIL = "defer_until"
    private const val FIRST_SETTLE_MS = 5_000L
    private const val BURST_SETTLE_MS = 30_000L
    private var active: Cancellation? = null
    private var activePreparation: Cancellation? = null
    const val JOB_ID = 4401
    const val SETTLE_JOB_ID = 4404
    const val WARM_JOB_ID = 4410

    /** Captured before dispatch, so queued threads cannot escape a subsequent Stop. */
    class Cancellation internal constructor(
        private val context: Context,
        private val generation: Long,
        private val stopped: () -> Boolean,
    ) {
        @Volatile private var cancelled = false
        private var process: Process? = null

        fun checkpoint() {
            if (cancelled || stopped() || context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(CANCELLATION, 0) != generation) {
                cancel()
                throw CancellationException("Icon pack generation stopped")
            }
        }

        @Synchronized fun cancel() {
            cancelled = true
            process?.destroyForcibly()
        }

        @Synchronized fun attach(process: Process) {
            try {
                checkpoint()
                this.process = process
            } catch (failure: CancellationException) {
                process.destroyForcibly()
                throw failure
            }
        }

        @Synchronized fun detach(process: Process) {
            if (this.process === process) this.process = null
        }

        /** Stop and irreversible submission have a single ordering point. */
        @Synchronized fun <T> commit(block: () -> T): T {
            checkpoint()
            return block()
        }

        private var installing = false

        /** Cancelling inside install() leaves the pack waiting for a manual Install tap, so only before it. */
        @Synchronized fun supersede(): Boolean {
            if (!installing) cancel()
            return !installing
        }

        @Synchronized fun beginInstall() {
            checkpoint()
            installing = true
        }

        fun update(block: (State) -> State) {
            synchronized(BuildCoordinator) {
                checkpoint()
                BuildCoordinator.update(block)
            }
        }
    }

    @Synchronized
    fun cancellation(context: Context, stopped: () -> Boolean = { false }): Cancellation =
        Cancellation(context.applicationContext,
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(CANCELLATION, 0), stopped)

    fun isStopped(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(STOPPED_MESSAGE)

    internal fun prepareManualInstall(context: Context, cancellation: Cancellation) {
        cancellation.update {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(STOPPED_MESSAGE).commit()
            State(phase = Phase.INSTALLING, message = "Preparing installation")
        }
    }

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(state)
    }
    fun removeListener(listener: (State) -> Unit) { listeners.remove(listener) }

    @Synchronized fun update(block: (State) -> State) {
        state = block(state)
        listeners.forEach { it(state) }
    }
    fun reset() { update { State() } }

    @Synchronized
    fun start(context: Context, trigger: String) {
        val automatic = trigger.startsWith("package:")
        if (automatic && (!OnboardingState.isComplete(context) || !autoUpdateEnabled(context))) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = prefs.getLong(REQUESTED, 0) + 1
        val now = System.currentTimeMillis()
        // Store updates install apps one by one. A change during the short wait or the build
        // means a burst: drop the build and wait for a longer quiet gap.
        val burst = automatic && (now < prefs.getLong(DEFER_UNTIL, 0) || state.isRunning)
        val settle = if (!automatic) 0L else if (burst) BURST_SETTLE_MS else FIRST_SETTLE_MS
        val editor = prefs.edit().putLong(REQUESTED, next).remove(STOPPED_MESSAGE)
        if (automatic) editor.putLong(DEFER_UNTIL, now + settle) else editor.putLong(MANUAL, next).remove(DEFER_UNTIL)
        editor.commit()
        Log.d("BuildCoordinator", "Requested generation=$next trigger=$trigger settle=$settle")
        if (burst && active?.supersede() == true) {
            Log.d("BuildCoordinator", "Superseded running build; packages are still changing")
            IconPackBuildService.cancelProgress(context)
            update { State(phase = Phase.QUEUED, message = "Waiting for app updates to finish") }
        }
        if (!state.isRunning && state.phase != Phase.WAITING_PERMISSION && state.phase != Phase.WAITING_CONFIRMATION) {
            if (automatic) update { State(phase = Phase.QUEUED, message = "Waiting for app updates to finish") }
            else waitingForScheduler(context)
        }
        schedule(context)
    }

    /** A dispatched timer may be older than the latest package change; honor the saved deadline. */
    @Synchronized fun settled(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(DEFER_UNTIL) || prefs.getLong(REQUESTED, 0) <= prefs.getLong(COMPLETED, 0)) return
        if (System.currentTimeMillis() >= prefs.getLong(DEFER_UNTIL, 0)) {
            prefs.edit().remove(DEFER_UNTIL).commit()
        }
        schedule(context)
    }

    fun stopAll(context: Context) {
        // Do not acquire the installer lock while holding the coordinator lock: installer
        // callbacks acknowledge requests in the opposite direction.
        synchronized(this) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putLong(CANCELLATION, prefs.getLong(CANCELLATION, 0) + 1)
                .putLong(COMPLETED, prefs.getLong(REQUESTED, 0))
                .putString(STOPPED_MESSAGE, "All generation stopped").commit()
            active?.cancel()
            activePreparation?.cancel()
            DirtyIconQueue.discard(context)
            update { State(phase = Phase.STOPPED, message = "All generation stopped") }
            context.getSystemService(JobScheduler::class.java).run {
                cancel(JOB_ID); cancel(SETTLE_JOB_ID); cancel(WARM_JOB_ID)
            }
        }
        val message = IconPackInstaller.stopPending(context)
        synchronized(this) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.contains(STOPPED_MESSAGE)) {
                prefs.edit().putString(STOPPED_MESSAGE, message).commit()
                update { State(phase = Phase.STOPPED, message = message) }
            }
        }
        IconPackBuildService.cancelNotifications(context)
    }

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        // A warm lease is independent of manual generation's cancellation generation.
        context.getSharedPreferences(PackageChangeReceiver.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PackageChangeReceiver.KEY_ENABLED, enabled).commit()
        synchronized(this) {
            activePreparation?.cancel()
            DirtyIconQueue.discard(context)
            context.getSystemService(JobScheduler::class.java).cancel(WARM_JOB_ID)
        }
        // Enabling is not an installation event. Consume changes made while disabled.
        PackageChangeMonitor.observeCurrent(context)
        if (enabled) PackageChangeMonitor.ensureScheduled(context)
        else context.getSystemService(JobScheduler::class.java).cancel(PackageChangeMonitor.JOB_ID)
    }

    @Synchronized fun completeRequest(context: Context, request: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (request > prefs.getLong(COMPLETED, 0)) prefs.edit().putLong(COMPLETED, request).commit()
        Log.d("BuildCoordinator", "Acknowledged generation=$request")
    }

    /** Always schedule from the durable deadline, including recovery and early worker delivery.
     *  Enqueue immediate work rather than replacing a running build; delayed work uses its own job. */
    private fun schedule(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val settleMs = (prefs.getLong(DEFER_UNTIL, 0) - System.currentTimeMillis()).coerceAtLeast(0L)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val component = ComponentName(context, IconPackBuildJobService::class.java)
        val result = if (settleMs > 0) scheduler.schedule(JobInfo.Builder(SETTLE_JOB_ID, component)
            .setMinimumLatency(settleMs).setOverrideDeadline(settleMs).build())
        else scheduler.enqueue(JobInfo.Builder(JOB_ID, component).setOverrideDeadline(0).build(), JobWorkItem(Intent()))
        if (result != JobScheduler.RESULT_SUCCESS) update {
            it.copy(phase = Phase.FAILED, message = "Could not schedule icon pack update",
                error = "Android rejected the build job; open the app to retry")
        }
    }

    @Synchronized fun resumeQueued(context: Context) {
        if (!OnboardingState.isComplete(context)) return
        resumePreparation(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(REQUESTED, 0) > prefs.getLong(COMPLETED, 0)) {
            if (!state.isRunning && state.phase != Phase.WAITING_PERMISSION && state.phase != Phase.WAITING_CONFIRMATION) {
                waitingForScheduler(context)
            }
            schedule(context)
        }
        else prefs.getString(STOPPED_MESSAGE, null)?.let { message ->
            update { State(phase = Phase.STOPPED, message = message) }
        }
    }

    @Synchronized
    fun waitingForScheduler(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(STOPPED_MESSAGE) ||
            prefs.getLong(REQUESTED, 0) <= prefs.getLong(COMPLETED, 0)) return
        update { State(phase = Phase.QUEUED, message = "Queued — waiting for Android to permit generation") }
    }

    /** The installer acknowledges a snapshot only after its recoverable operation is persisted. */
    fun runPending(context: Context, stopped: () -> Boolean) {
        if (stopped()) return
        IconPackInstaller.restoreState(context)
        if (!OnboardingState.isComplete(context)) return
        while (!stopped() && !IconPackInstaller.hasPendingOperation(context)) {
            val target: Long
            val cancellation: Cancellation
            synchronized(this) {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                target = prefs.getLong(REQUESTED, 0)
                if (target <= prefs.getLong(COMPLETED, 0)) return
                // An early/redelivered worker must leave a future wakeup before completing its item.
                if (System.currentTimeMillis() < prefs.getLong(DEFER_UNTIL, 0)) {
                    schedule(context)
                    return
                }
                if (prefs.getLong(MANUAL, 0) <= prefs.getLong(COMPLETED, 0) && !autoUpdateEnabled(context)) {
                    prefs.edit().putLong(COMPLETED, target).commit()
                    return
                }
                cancellation = BuildCoordinator.cancellation(context, stopped)
                active = cancellation
            }
            try {
                cancellation.checkpoint()
                if (!IconPackInstaller.beginBuild(context, cancellation)) return
                val fingerprint = PackageChangeMonitor.fingerprint(context)
                cancellation.checkpoint()
                Log.d("BuildCoordinator", "Building generation=$target fingerprint=$fingerprint")
                val apk = IconPackBuildService.build(context, cancellation)
                cancellation.beginInstall()
                IconPackInstaller.install(context, apk, target, fingerprint, cancellation)
            } catch (_: CancellationException) {
                // Explicit Stop consumed the queue. Framework interruption leaves it durable
                // for redelivery, but neither path may publish stale failure/success state.
                return
            } catch (failure: Exception) {
                try {
                    cancellation.update {
                        completeRequest(context, target)
                        it.copy(phase = Phase.FAILED, message = "Icon pack update failed", error = failure.stackTraceToString())
                    }
                    cancellation.commit {
                        Log.e("BuildCoordinator", "Generation $target failed", failure)
                        IconPackBuildService.postResult(context, "Icon pack update failed",
                            failure.message ?: failure.javaClass.simpleName, IconPackBuildService.ResultKind.ERROR)
                    }
                } catch (_: CancellationException) { return }
            } finally {
                synchronized(this) { if (active === cancellation) active = null }
            }
        }
    }

    internal fun automationEnabled(context: Context): Boolean =
        OnboardingState.isComplete(context) && autoUpdateEnabled(context)

    /** An independent finite job can render during the build's persisted quiet period. */
    @Synchronized internal fun resumePreparation(context: Context) {
        if (!automationEnabled(context) || isStopped(context)) return
        val snapshot = DirtyIconQueue.snapshot(context)
        if (snapshot.entries.isEmpty()) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.enqueue(JobInfo.Builder(WARM_JOB_ID,
            ComponentName(context, IconPackBuildJobService::class.java)).setOverrideDeadline(0).build(),
            JobWorkItem(Intent()))
        // Failure is recoverable: reconciliation/startup will see the same durable lease.
    }

    /** Called only by the process-wide worker; no rendering under monitor/UI locks. */
    internal fun runPreparation(
        context: Context,
        stopped: () -> Boolean,
        inventory: () -> Map<String, String> = { PackageChangeMonitor.identities(context) },
        prepare: (Set<String>, Cancellation) -> Unit = { packages, cancellation ->
            IconGeneratorEngine.warmPackages(context, packages, cancellation)
        },
    ) {
        val snapshot: DirtyIconQueue.Snapshot
        val cancellation: Cancellation
        synchronized(this) {
            if (stopped() || !automationEnabled(context) || isStopped(context)) return
            snapshot = DirtyIconQueue.snapshot(context)
            cancellation = BuildCoordinator.cancellation(context) {
                stopped() || !automationEnabled(context) || isStopped(context) ||
                    !DirtyIconQueue.isCurrent(context, snapshot)
            }
            activePreparation = cancellation
        }
        try {
            cancellation.checkpoint()
            for ((packageName, entries) in snapshot.entries.entries.groupBy { it.key.substringAfter(':') }) {
                cancellation.checkpoint()
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                // Once quiet has expired the final build fills misses; do not delay it with warming.
                if (System.currentTimeMillis() >= prefs.getLong(DEFER_UNTIL, 0)) break
                val before = inventory()
                val valid = entries.filter { before[it.key] == it.value.identity }.associate { it.toPair() }
                if (valid.isEmpty()) continue // Inaccessible profiles and missed updates stay durable.
                prepare(setOf(packageName), cancellation)
                cancellation.checkpoint()
                val after = inventory()
                cancellation.checkpoint()
                DirtyIconQueue.acknowledge(context, snapshot.copy(entries = valid), after)
            }
        } catch (_: CancellationException) {
            // Framework interruption retains leases; explicit Stop/off discards them itself.
        } finally {
            synchronized(this) {
                if (activePreparation === cancellation) activePreparation = null
            }
        }
    }

    private fun autoUpdateEnabled(context: Context): Boolean =
        context.getSharedPreferences(PackageChangeReceiver.PREFS, Context.MODE_PRIVATE)
            .getBoolean(PackageChangeReceiver.KEY_ENABLED, true)
}
