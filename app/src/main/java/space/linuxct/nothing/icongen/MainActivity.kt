package space.linuxct.nothing.icongen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.CancellationException

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var ui: LucentUi
    private lateinit var btnGenerate: Button
    private lateinit var btnStopGeneration: Button
    private lateinit var progress: ProgressBar
    private lateinit var workControls: LinearLayout
    private lateinit var setupSummary: TextView
    private var details = ""
    private var setupDialog: android.app.Dialog? = null
    private var updateConsentDialog: android.app.Dialog? = null
    private var builtApk: File? = null
    private var activityResumed = false
    private var resumingInstall = false
    private val setupPrefs by lazy { getSharedPreferences("permission_setup", MODE_PRIVATE) }

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_NOTIFICATION_ASKED = "notification_asked"
        const val EXTRA_THEME_ACCESS = "show_theme_access"
        private const val KEY_SOURCE_ASKED = "source_asked"
        private const val KEY_FLOW_ACTIVE = "flow_active"
        private const val KEY_AWAITING = "awaiting"
        private const val KEY_ACTION = "action"
        private const val KEY_APK = "built_apk"
        private const val AWAITING_NOTIFICATION = "notification"
        private const val AWAITING_SOURCE = "source"
        private const val ACTION_GENERATE = "generate"
        private const val ACTION_INSTALL = "install"
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        setupPrefs.edit().remove(KEY_AWAITING).apply()
        advancePermissionSetup()
    }

    private val installAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        setupPrefs.edit().remove(KEY_AWAITING).putBoolean(KEY_FLOW_ACTIVE, false).apply()
        refreshPermissionStatus()
        if (InstallAccess.canInstall(this)) {
            resumeRequestedOperation()
        }
    }

    private val notificationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshPermissionStatus() }

    private val backgroundAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        IconPackSelectionJobService.scheduleAfterAccessChange(this)
        refreshPermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OnboardingState.isComplete(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java)
                .putExtra(EXTRA_THEME_ACCESS, intent.getBooleanExtra(EXTRA_THEME_ACCESS, false)))
            finish()
            return
        }
        ui = LucentUi(this)
        createHome()
        // With the legacy target Android owns the notification prompt. Only prepare
        // optional channels here when the user actually chose those notifications.
        if (NotificationPreferences.isEnabled(this)) IconPackBuildService.ensureChannels(this)

        builtApk = setupPrefs.getString(KEY_APK, null)?.let(::File)?.takeIf { it.exists() }
        // ActivityResultRegistry restores in-flight prompts when there is saved state.
        // A genuinely new Activity must not remain stuck waiting for a lost result.
        if (savedInstanceState == null) setupPrefs.edit().remove(KEY_AWAITING).apply()

        renderAutomation()
        btnGenerate.setOnClickListener {
            val phase = BuildCoordinator.state.phase
            requestOperation(if (phase == BuildCoordinator.Phase.WAITING_CONFIRMATION || phase == BuildCoordinator.Phase.WAITING_PERMISSION) ACTION_INSTALL else ACTION_GENERATE)
        }
        btnStopGeneration.setOnClickListener {
            setupPrefs.edit().remove(KEY_ACTION).remove(KEY_AWAITING).putBoolean(KEY_FLOW_ACTIVE, false).apply()
            BuildCoordinator.stopAll(this)
            renderBuildState(BuildCoordinator.state)
        }
        IconPackInstaller.statusListener = { onUi { syncInstallState(); refreshPermissionStatus() } }
    }

    private lateinit var automationCard: LinearLayout

    private fun renderAutomation() {
        automationCard.removeAllViews()
        ui.toggle(automationCard, "App updates", "Rebuild after apps are installed or updated", getSharedPreferences(PackageChangeReceiver.PREFS, MODE_PRIVATE).getBoolean(PackageChangeReceiver.KEY_ENABLED, true), first = true) { checked ->
            getSharedPreferences(PackageChangeReceiver.PREFS, MODE_PRIVATE).edit().putBoolean(PackageChangeReceiver.KEY_ENABLED, checked).apply()
            if (checked) requestOperation("auto_update") else {
                if (setupPrefs.getString(KEY_ACTION, null) == "auto_update") setupPrefs.edit().remove(KEY_ACTION).apply()
                BuildCoordinator.setAutoUpdateEnabled(this, false)
            }
            refreshPermissionStatus()
        }
        ui.toggle(automationCard, "Notifications", "Optional progress, results and update alerts", NotificationPreferences.isEnabled(this)) { enabled ->
            NotificationPreferences.setEnabled(this, enabled)
            refreshPermissionStatus()
        }
        ui.toggle(automationCard, "Theme changes", "Refresh the pack when light or dark mode changes", ThemeRefreshController.isEnabled(this)) { enabled ->
            ThemeRefreshController.setEnabled(this, enabled)
            refreshThemeAccess()
        }
        ui.toggle(automationCard, "Keep theme on app updates", "Apps stay themed after update without rebuild, but are themed system-wide which looks off", IconPackAssembler.keepThemeOnUpdate(this)) { enabled ->
            IconPackAssembler.setKeepThemeOnUpdate(this, enabled)
            requestOperation(ACTION_GENERATE)
        }
        ui.toggle(automationCard, "IconGen updates", "Check GitHub once a day for new IconGen releases", AppUpdateChecker.isEnabled(this), last = true) { enabled ->
            AppUpdateChecker.setEnabled(this, enabled)
        }
    }

    private fun postUpdateConsent() {
        window.decorView.post {
            if (!activityResumed || isFinishing || isDestroyed ||
                !OnboardingState.isComplete(this) || AppUpdateChecker.hasDecision(this) ||
                !window.decorView.hasWindowFocus() || updateConsentDialog?.isShowing == true ||
                setupDialog?.isShowing == true || setupPrefs.getBoolean(KEY_FLOW_ACTIVE, false) ||
                setupPrefs.contains(KEY_AWAITING) || intent.getBooleanExtra(EXTRA_THEME_ACCESS, false)
            ) return@post
            updateConsentDialog = ui.dialog(this, "IconGen updates") { content, dialog ->
                ui.add(content, ui.text("Allow IconGen to contact GitHub in the background once every 24 hours to check for new IconGen releases? Android may delay these checks. No checks run unless you enable this option.", 14f))
                ui.add(content, ui.text("When optional notifications are enabled, a new release can show an alert linking to GitHub. Updates are never installed automatically: installing IconGen requires a PC and the ADB bypass. You can change this choice in Automation.", 13f, muted = true), 12)
                ui.add(content, ui.button("Enable", primary = true) {
                    AppUpdateChecker.setEnabled(this, true)
                    dialog.dismiss()
                }, 20)
                ui.add(content, ui.button("Not now · keep off") {
                    AppUpdateChecker.setEnabled(this, false)
                    dialog.dismiss()
                }, 8)
            }.apply {
                setOnDismissListener {
                    // Back and outside taps are a saved decline, never an opt-in.
                    if (!AppUpdateChecker.hasDecision(this@MainActivity)) {
                        AppUpdateChecker.setEnabled(this@MainActivity, false)
                    }
                    updateConsentDialog = null
                    if (!isFinishing && !isDestroyed) renderAutomation()
                }
            }
        }
    }

    private fun createHome() {
        val root = ui.screen(this)
        ui.header(root, "Nothing IconGen", "Making your apps a bit more Lucent!", menu = ::showMoreOptions)
        val main = ui.card()
        ui.add(main, ui.text("ICON PACK", 11f, muted = true))
        tvStatus = ui.text("Ready to create", 18f)
        tvStatus.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        btnGenerate = ui.button("Create icon pack", primary = true) {}
        ui.add(main, btnGenerate, 16)
        ui.add(main, tvStatus, 12)
        // The primary action is always above conditional status and access information.
        workControls = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(48)
            visibility = View.GONE
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(ui.ink)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(ui.alpha(ui.ink, .12f))
        }
        workControls.addView(progress, LinearLayout.LayoutParams(0, ui.dp(4), 1f))
        btnStopGeneration = ui.button("Cancel") {}
        workControls.addView(btnStopGeneration, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(12) })
        ui.add(main, workControls, 8)
        ui.add(root, main, 22)
        ui.add(root, ui.text("Automation", 14f, muted = true), 22)
        automationCard = ui.column()
        ui.add(root, automationCard, 10)
        val access = ui.card(0)
        val setupRow = ui.row("Setup", "Checking access…", action = ::showSetup)
        setupSummary = ((setupRow.getChildAt(0) as LinearLayout).getChildAt(1) as TextView)
        ui.add(access, setupRow)
        ui.add(root, access, 14)
    }

    private fun showMoreOptions() {
        ui.dialog(this, "Icon pack") { content, dialog ->
            ui.add(content, ui.row("Choose in launcher", "Open Nothing Launcher’s icon pack picker") { dialog.dismiss(); selectInLauncherSettings() })
            if (builtApk?.exists() == true && !BuildCoordinator.state.isRunning && BuildCoordinator.state.phase != BuildCoordinator.Phase.QUEUED) {
                ui.add(content, ui.row("Reinstall icon pack", "Install the last generated pack") { dialog.dismiss(); requestOperation(ACTION_INSTALL) })
            }
            ui.add(content, ui.row("Build details", "Status and diagnostic information") { dialog.dismiss(); showBuildDetails() })
            ui.add(content, ui.button("Done") { dialog.dismiss() }, 12)
        }
    }

    private fun showBuildDetails() {
        ui.dialog(this, "Build details") { content, dialog ->
            val state = BuildCoordinator.state
            val description = buildString {
                append(state.message.ifBlank { "No build is running." })
                state.detail?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
                details.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
            }
            ui.add(content, ui.text(description, 13f).apply { setTextIsSelectable(true) })
            ui.add(content, ui.button("Done") { dialog.dismiss() }, 20)
        }
    }

    private fun showSetup() {
        setupDialog?.dismiss()
        setupDialog = ui.dialog(this, "Setup") { content, dialog ->
            ui.add(content, ui.text("Allow the access IconGen needs. You stay in control of optional notifications and automation.", 14f, muted = true))
            ui.add(content, ui.row("Icon pack installation", if (InstallAccess.canInstall(this)) "Allowed" else "Required to install your generated pack") {
                dialog.dismiss()
                setupPrefs.edit().putBoolean(KEY_FLOW_ACTIVE, true).apply()
                openInstallAccessSettings()
            }, 12)
            ui.divider(content)
            ui.add(content, ui.row("Settings access", if (WirelessGrantStatus.read(this).complete) "Ready · both grants enabled" else "Apply rebuilt icons and refresh theme changes") {
                dialog.dismiss(); showSettingsAccessInstructions()
            })
            ui.divider(content)
            ui.add(content, ui.row("Background icon refresh", if (LauncherRefreshAccess.isGranted(this)) "Allowed · no overlay is drawn" else "Prepare updated icons without interrupting your app") {
                dialog.dismiss(); showBackgroundRefreshAccess()
            })
            ui.divider(content)
            ui.add(content, ui.row("Notification access", if (notificationsAvailable()) "Allowed" else "Blocked · tap to manage") { dialog.dismiss(); requestNotificationAccess() })
            ui.add(content, ui.text("Only enabled Theme changes requires a running theme-monitor service and Android’s silent monitoring notification. App updates work independently. Wireless setup uses a separate temporary pairing notification.", 12f, muted = true), 16)
            ui.add(content, ui.row("Run setup again", "Review your feature choices and guided access setup") {
                dialog.dismiss()
                startActivity(Intent(this, OnboardingActivity::class.java)
                    .putExtra(OnboardingActivity.EXTRA_RESTART, true))
            }, 12)
            ui.add(content, ui.button("Done") { dialog.dismiss() }, 20)
        }
    }

    private fun requestOperation(action: String) {
        setupPrefs.edit()
            .putString(KEY_ACTION, action)
            .putBoolean(KEY_FLOW_ACTIVE, true)
            .apply()
        advancePermissionSetup()
    }

    private fun advancePermissionSetup() {
        refreshPermissionStatus()
        if (!activityResumed || setupPrefs.contains(KEY_AWAITING)) return
        if (!setupPrefs.getBoolean(KEY_FLOW_ACTIVE, false)) {
            if (InstallAccess.canInstall(this)) resumeRequestedOperation()
            return
        }
        if (!setupPrefs.getBoolean(KEY_NOTIFICATION_ASKED, false)) {
            setupPrefs.edit().putBoolean(KEY_NOTIFICATION_ASKED, true).apply()
            // Legacy-target apps cannot explicitly launch POST_NOTIFICATIONS requests.
            if (Build.VERSION.SDK_INT >= 33 && applicationInfo.targetSdkVersion >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                setupPrefs.edit().putString(KEY_AWAITING, AWAITING_NOTIFICATION).apply()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        if (!InstallAccess.canInstall(this)) {
            // Only an initial setup or a deliberate operation opens this screen.
            openInstallAccessSettings()
            return
        }
        setupPrefs.edit()
            .putBoolean(KEY_SOURCE_ASKED, true)
            .putBoolean(KEY_FLOW_ACTIVE, false)
            .apply()
        resumeRequestedOperation()
    }

    private fun openInstallAccessSettings() {
        if (setupPrefs.contains(KEY_AWAITING)) return
        if (InstallAccess.canInstall(this)) {
            setupPrefs.edit().putBoolean(KEY_FLOW_ACTIVE, false).apply()
            resumeRequestedOperation()
            refreshPermissionStatus()
            return
        }
        setupPrefs.edit()
            .putBoolean(KEY_SOURCE_ASKED, true)
            .putString(KEY_AWAITING, AWAITING_SOURCE)
            .apply()
        try {
            installAccessLauncher.launch(InstallAccess.settingsIntent(this))
        } catch (e: android.content.ActivityNotFoundException) {
            setupPrefs.edit().remove(KEY_AWAITING).putBoolean(KEY_FLOW_ACTIVE, false).apply()
            tvStatus.text = "Cannot open install access settings: ${e.message}"
        }
    }

    private fun requestNotificationAccess() {
        if (setupPrefs.contains(KEY_AWAITING)) return
        val runtimeMissing = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (runtimeMissing && applicationInfo.targetSdkVersion >= 33 &&
            (!setupPrefs.getBoolean(KEY_NOTIFICATION_ASKED, false) ||
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        ) {
            setupPrefs.edit()
                .putBoolean(KEY_NOTIFICATION_ASKED, true)
                .putString(KEY_AWAITING, AWAITING_NOTIFICATION)
                .apply()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val intent = NotificationAccessUi.settingsIntent(this)
        notificationSettingsLauncher.launch(intent)
    }

    private fun notificationsAvailable() = NotificationAccessUi.available(this)

    private fun refreshPermissionStatus() {
        val automaticApplyEnabled = ThemeRefreshController.isEnabled(this) ||
            getSharedPreferences(PackageChangeReceiver.PREFS, MODE_PRIVATE).getBoolean(PackageChangeReceiver.KEY_ENABLED, true)
        val missing = buildList {
            if (!InstallAccess.canInstall(this@MainActivity)) add("installation")
            if (automaticApplyEnabled && !WirelessGrantStatus.read(this@MainActivity).complete) add("Settings")
            if (automaticApplyEnabled && !LauncherRefreshAccess.isGranted(this@MainActivity)) add("background refresh")
            if (NotificationPreferences.isEnabled(this@MainActivity) && !notificationsAvailable()) add("notifications")
        }
        setupSummary.text = if (missing.isEmpty()) "All required access is ready" else "Access needed: ${missing.joinToString(", ")}"
    }

    private fun refreshThemeAccess() = refreshPermissionStatus()

    private fun showSettingsAccessInstructions() {
        ui.dialog(this, "Settings access") { content, dialog ->
            ui.add(content, ui.text(SettingsAccessUi.EXPLANATION, 14f, muted = true))
            ui.add(content, ui.text(SettingsAccessUi.status(this), 13f), 12)
            SettingsAccessUi.addChoices(ui, content, openWireless = {
                dialog.dismiss()
                startActivity(Intent(this, WirelessDebuggingActivity::class.java))
            })
            ui.add(content, ui.button("Done") { dialog.dismiss() }, 8)
        }
    }

    private fun showBackgroundRefreshAccess() {
        ui.dialog(this, "Background icon refresh") { content, dialog ->
            ui.add(content, ui.text(SettingsAccessUi.BACKGROUND_EXPLANATION, 14f))
            if (!LauncherRefreshAccess.isGranted(this)) {
                ui.add(content, ui.text("Select IconGen in Android’s app list, allow Display over other apps, then return here.", 13f, muted = true), 12)
            }
            ui.add(content, ui.button("Open access settings", primary = true) {
                dialog.dismiss()
                backgroundAccessLauncher.launch(LauncherRefreshAccess.settingsIntent(this))
            }, 20)
            ui.add(content, ui.button("Done") { dialog.dismiss() }, 8)
        }
    }

    private fun resumeRequestedOperation() {
        if (!activityResumed || !InstallAccess.canInstall(this)) return
        val action = setupPrefs.getString(KEY_ACTION, null)
        // Consume the durable intent before starting work so another onResume cannot replay it.
        setupPrefs.edit().remove(KEY_ACTION).apply()
        when (action) {
            "auto_update" -> BuildCoordinator.setAutoUpdateEnabled(this, true)
        }
        if (BuildCoordinator.state.phase == BuildCoordinator.Phase.STOPPED &&
            action != ACTION_GENERATE && action != ACTION_INSTALL
        ) return
        if (action == ACTION_INSTALL &&
            BuildCoordinator.state.phase == BuildCoordinator.Phase.WAITING_CONFIRMATION
        ) {
            IconPackInstaller.resumePendingInstall(this)
            return
        }
        if (IconPackInstaller.pendingApk(this) != null) {
            if (resumingInstall) return
            val cancellation = BuildCoordinator.cancellation(this)
            resumingInstall = true
            Thread {
                try {
                    IconPackInstaller.resumePendingInstall(
                        applicationContext,
                        cancellation = cancellation,
                    )
                } catch (_: CancellationException) {
                    onUi { renderBuildState(BuildCoordinator.state) }
                } catch (t: Throwable) {
                    Log.e(TAG, "Resuming install failed", t)
                    onUi {
                        if (BuildCoordinator.state.phase == BuildCoordinator.Phase.STOPPED) {
                            renderBuildState(BuildCoordinator.state)
                        } else {
                            tvStatus.text = "Install failed: ${t.message}"
                        }
                    }
                } finally {
                    onUi {
                        resumingInstall = false
                        syncInstallState()
                    }
                }
            }.start()
            return
        }
        when (action) {
            ACTION_GENERATE -> BuildCoordinator.start(this, trigger = "ui")
            ACTION_INSTALL -> installIconPack()
        }
    }

    private val buildListener: (BuildCoordinator.State) -> Unit = {
        // A queued UI callback must not repaint a phase that Stop has superseded.
        runOnUiThread { renderBuildState(BuildCoordinator.state) }
    }

    private fun renderBuildState(state: BuildCoordinator.State) {
        details = state.error.orEmpty()
        state.apkPath?.let {
            builtApk = File(it).takeIf { apk -> apk.exists() }
            setupPrefs.edit().putString(KEY_APK, it).apply()
        }
        val pending = state.phase == BuildCoordinator.Phase.WAITING_CONFIRMATION || state.phase == BuildCoordinator.Phase.WAITING_PERMISSION
        val working = state.isRunning || state.phase == BuildCoordinator.Phase.QUEUED
        val installed = packInstalled()
        btnGenerate.text = when (state.phase) {
            BuildCoordinator.Phase.WAITING_CONFIRMATION -> "Approve installation"
            BuildCoordinator.Phase.WAITING_PERMISSION -> "Finish installation"
            else -> if (installed || builtApk?.exists() == true) "Update icon pack" else "Create icon pack"
        }
        btnGenerate.isEnabled = !working
        workControls.visibility = if (working || pending) View.VISIBLE else View.GONE
        progress.visibility = if (working) View.VISIBLE else View.INVISIBLE
        btnStopGeneration.visibility = if (working || pending) View.VISIBLE else View.INVISIBLE
        progress.isIndeterminate = state.phase == BuildCoordinator.Phase.QUEUED || state.percent <= 0
        progress.progress = state.percent.coerceIn(0, 100)
        tvStatus.text = when (state.phase) {
            BuildCoordinator.Phase.IDLE -> if (installed) "Your icon pack is ready" else "Ready to create"
            BuildCoordinator.Phase.STOPPED -> state.message.ifBlank { "Generation stopped" }
            BuildCoordinator.Phase.FAILED -> "Couldn’t update the pack. See Build details."
            BuildCoordinator.Phase.WAITING_PERMISSION -> "Allow installation to finish your pack"
            BuildCoordinator.Phase.WAITING_CONFIRMATION -> "Your pack is ready for approval"
            else -> state.message.ifBlank { if (working) "Updating your icon pack…" else "Your icon pack is ready" }
        }
        if (pending) refreshPermissionStatus()
    }

    private fun packInstalled(): Boolean = try {
        packageManager.getPackageInfo("com.nothing.icongen.pack", 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun installIconPack() {
        val apk = builtApk
        if (apk == null || !apk.exists()) {
            tvStatus.text = "Create an icon pack before reinstalling"
            details = "No generated APK is available."
            return
        }
        btnGenerate.isEnabled = false
        tvStatus.text = "Installing..."
        val cancellation = BuildCoordinator.cancellation(this)
        Thread {
            try {
                IconPackInstaller.install(this, apk, cancellation = cancellation)
                onUi { syncInstallState() }
            } catch (_: CancellationException) {
                onUi { syncInstallState() }
            } catch (t: Throwable) {
                Log.e(TAG, "Install failed", t)
                onUi {
                    if (BuildCoordinator.state.phase == BuildCoordinator.Phase.STOPPED) {
                        renderBuildState(BuildCoordinator.state)
                    } else {
                        tvStatus.text = "Installation failed. See Build details."
                        details = t.stackTraceToString()
                        btnGenerate.isEnabled = true
                    }
                }
            }
        }.start()
    }

    private fun syncInstallState() {
        val state = BuildCoordinator.state
        if (state.phase != BuildCoordinator.Phase.STOPPED) {
            IconPackInstaller.pendingApk(this)?.let { builtApk = it }
        }
        renderBuildState(state)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!::ui.isInitialized) return
        activityResumed = true
        renderAutomation()
        IconPackInstaller.restoreState(this)
        // Re-attach: the build may have started while this Activity did not exist.
        BuildCoordinator.addListener(buildListener)
        syncInstallState()
        advancePermissionSetup()
        BackgroundMonitorService.sync(this)
        if (intent.getBooleanExtra(EXTRA_THEME_ACCESS, false)) {
            intent.removeExtra(EXTRA_THEME_ACCESS)
            showSettingsAccessInstructions()
        }
        postUpdateConsent()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::ui.isInitialized) postUpdateConsent()
    }

    override fun onPause() {
        activityResumed = false
        BuildCoordinator.removeListener(buildListener)
        super.onPause()
    }

    override fun onDestroy() {
        IconPackInstaller.statusListener = null
        super.onDestroy()
    }

    private fun onUi(block: () -> Unit) = runOnUiThread(block)


    private fun selectInLauncherSettings() {
        val intent = Intent("com.nothing.launcher.icon_pack_picker").apply {
            setPackage("com.nothing.launcher")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            ui.dialog(this, "Choose your icon pack") { content, dialog ->
                ui.add(content, ui.text("The launcher’s icon pack picker could not be opened. Open Nothing Launcher settings and select your generated IconGen pack manually.", 14f))
                ui.add(content, ui.button("Done") { dialog.dismiss() }, 20)
            }
        }
    }
}