package space.linuxct.nothing.icongen

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** User-driven setup: opening Settings is never treated as granting access. */
class OnboardingActivity : AppCompatActivity() {
    private lateinit var ui: LucentUi
    private val prefs by lazy { OnboardingState.preferences(this) }
    private var error: String? = null
    private val accessLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        IconPackSelectionJobService.scheduleAfterAccessChange(this)
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_RESTART, false)) {
            OnboardingState.restart(this)
            intent.removeExtra(EXTRA_RESTART)
        }
        ui = LucentUi(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        render()
    }

    override fun onResume() {
        super.onResume()
        // Also covers PC grants, external revocation and a restored Settings task without a result.
        render()
    }

    private fun go(step: OnboardingState.Step) {
        error = null
        OnboardingState.moveTo(this, step)
        render()
    }

    private fun next() {
        val steps = OnboardingState.steps(OnboardingState.draft(this))
        val index = steps.indexOf(OnboardingState.step(this))
        go(steps[(index + 1).coerceAtMost(steps.lastIndex)])
    }

    private fun back() {
        val steps = OnboardingState.steps(OnboardingState.draft(this))
        val index = steps.indexOf(OnboardingState.step(this))
        if (index > 0) go(steps[index - 1]) else finish()
    }

    private fun render() {
        if (!::ui.isInitialized || isFinishing || isDestroyed) return
        val draft = OnboardingState.draft(this)
        val step = OnboardingState.step(this)
        val steps = OnboardingState.steps(draft)
        val root = ui.screen(this)
        ui.header(root, "Setup", "${steps.indexOf(step) + 1} of ${steps.size} · Your choices are saved", back = ::back)
        error?.let { ui.add(root, ui.text(it, 14f), 16) }
        when (step) {
            OnboardingState.Step.WELCOME -> welcome(root)
            OnboardingState.Step.FEATURES -> features(root, draft)
            OnboardingState.Step.INSTALL -> installation(root)
            OnboardingState.Step.NOTIFICATIONS -> notifications(root)
            OnboardingState.Step.BACKGROUND -> backgroundRefresh(root)
            OnboardingState.Step.SETTINGS -> settings(root)
            OnboardingState.Step.READY -> ready(root, draft)
        }
    }

    private fun section(root: LinearLayout, title: String, description: String): LinearLayout {
        val card = ui.card()
        ui.add(card, ui.text(title, 26f, heading = true))
        ui.add(card, ui.text(description, 14f, muted = true), 12)
        ui.add(root, card, 22)
        return card
    }

    private fun welcome(root: LinearLayout) {
        val card = section(root, "Make it Lucent", "Making your apps a bit more Lucent!")
        ui.add(card, ui.text("Choose what runs automatically, allow installation, then set up only the access your choices need. Nothing is generated during setup.", 14f), 20)
        ui.add(card, ui.text("Android may ask you to review permissions. Follow its prompts, then return to setup. Installation and Settings access are separate steps.", 13f, muted = true), 16)
        ui.add(root, ui.button("Start setup", primary = true, action = ::next), 20)
        ui.add(root, ui.text("You can leave at any time. Setup resumes where you stopped.", 12f, muted = true), 12)
    }

    private fun features(root: LinearLayout, draft: OnboardingState.Draft) {
        section(root, "Choose your features", "Changes take effect when you finish. You can change them later in IconGen.")
        val choices = ui.column()
        ui.add(root, choices, 16)
        fun save(change: (OnboardingState.Draft) -> OnboardingState.Draft) {
            val previous = OnboardingState.draft(this)
            val updated = change(previous)
            OnboardingState.saveDraft(this, updated)
            if (previous.appUpdates != updated.appUpdates || previous.themeChanges != updated.themeChanges) {
                prefs.edit().remove("settings_deferred").remove("background_deferred").commit()
            }
            if (previous.notifications != updated.notifications) prefs.edit().remove("notifications_deferred").commit()
        }
        ui.toggle(choices, "App updates", "Rebuild after apps are installed or updated, then apply the installed pack.", draft.appUpdates, first = true) {
            save { current -> current.copy(appUpdates = it) }
        }
        ui.toggle(choices, "Theme changes", "Refresh selected icons when light or dark mode changes; no rebuild needed.", draft.themeChanges) {
            save { current -> current.copy(themeChanges = it) }
        }
        ui.toggle(choices, "IconGen updates", "Check GitHub for newer stable releases about once a day. Enable notifications for update alerts; manual checks are also available in the menu.", draft.appReleaseUpdates) {
            save { current -> current.copy(appReleaseUpdates = it) }
        }
        ui.toggle(choices, "Notifications", "Optional build progress and results. Not required for app-update detection.", draft.notifications, last = true) {
            save { current -> current.copy(notifications = it) }
        }
        ui.add(root, ui.text("Only Theme changes keeps a background theme-monitor service running, with Android’s required silent notification. With it off, that service and its notification stop. App updates work independently. Wireless setup uses a separate temporary pairing notification.", 13f, muted = true), 16)
        ui.add(root, ui.button("Continue", primary = true, action = ::next), 20)
    }

    private fun installation(root: LinearLayout) {
        val allowed = InstallAccess.canInstall(this)
        val card = section(root, "Install your icon pack", "IconGen creates an APK on this phone. Allow IconGen as an installation source so Android can install that generated icon pack. You still approve the first installation.")
        status(card, if (allowed) "Installation access is allowed" else "Installation access is not allowed")
        if (allowed) {
            ui.add(root, ui.button("Continue", primary = true, action = ::next), 20)
        } else {
            ui.add(card, ui.text("Tap Allow installation, enable Allow from this source in Android Settings, then return here. If your device administrator blocks this access, you can finish setup and resolve it later.", 13f, muted = true), 12)
            ui.add(root, ui.button("Allow installation", primary = true) { openAccess(InstallAccess.settingsIntent(this)) }, 20)
            ui.add(root, ui.button("Set up later") {
                defer("Installation is still blocked", "Your pack cannot be installed or updated until you allow installation. Your selected features will stay selected.", "install_deferred")
            }, 8)
        }
    }

    private fun backgroundRefresh(root: LinearLayout) {
        val allowed = LauncherRefreshAccess.isGranted(this)
        val card = section(root, "Background icon refresh", SettingsAccessUi.BACKGROUND_EXPLANATION)
        status(card, if (allowed) "Background refresh access is allowed" else "Background refresh access is not allowed")
        if (allowed) {
            ui.add(root, ui.button("Continue", primary = true, action = ::next), 20)
        } else {
            ui.add(card, ui.text("Select IconGen in Android’s app list and allow Display over other apps, then return here. This applies rebuilt packs automatically; theme detection does not need it.", 13f, muted = true), 12)
            ui.add(root, ui.button("Allow background refresh", primary = true) {
                openAccess(LauncherRefreshAccess.settingsIntent(this))
            }, 20)
            ui.add(root, ui.button("Set up later") {
                defer("Background refresh is not ready", "Your pack can still be built and installed. To apply rebuilt icons automatically, allow background refresh later from Setup. Your selected features stay selected.", "background_deferred")
            }, 8)
        }
    }

    private fun notifications(root: LinearLayout) {
        // With targetSdk 22 Android, not a runtime permission launcher, owns any initial prompt.
        IconPackBuildService.ensureChannels(this)
        val available = NotificationAccessUi.available(this)
        val card = section(root, "Progress and results", "You chose optional notifications. Allow them to see build progress, installation approval requests, results, and optional update alerts. Declining access does not turn your choice off.")
        status(card, if (available) "Notification access is ready" else "Notifications are blocked")
        if (available) {
            ui.add(root, ui.button("Continue", primary = true, action = ::next), 20)
        } else {
            ui.add(card, ui.text("If Android shows a notification permission prompt, review it. Otherwise open notification settings, allow notifications and the progress/results channels, then return. A blocked channel may require a second visit.", 13f, muted = true), 12)
            ui.add(root, ui.button("Open notification settings", primary = true) { openAccess(NotificationAccessUi.settingsIntent(this)) }, 20)
            ui.add(root, ui.button("Continue without notifications") {
                defer("Notifications are unavailable", "Your notification choice stays enabled, but progress and results cannot appear until Android allows them. Wireless pairing, if you choose it, requires its own notification access.", "notifications_deferred")
            }, 8)
        }
    }

    private fun settings(root: LinearLayout) {
        val complete = WirelessGrantStatus.read(this).complete
        val card = section(root, "Apply icons automatically", SettingsAccessUi.EXPLANATION)
        status(card, SettingsAccessUi.status(this))
        if (complete) {
            ui.add(card, ui.text("Both Settings grants are enabled.", 13f, muted = true), 12)
            ui.add(root, ui.button("Continue", primary = true, action = ::next), 20)
        } else {
            SettingsAccessUi.addChoices(ui, card, prefs.getBoolean("pc", false),
                onChoice = { prefs.edit().putBoolean("pc", it).commit() },
                openWireless = { accessLauncher.launch(Intent(this, WirelessDebuggingActivity::class.java)) })
            ui.add(root, ui.button("Check access") { render() }, 16)
            ui.add(root, ui.button("Continue without Settings access") {
                defer("Automatic refresh is not ready", "Your chosen automatic features stay enabled, but automatic application of rebuilt icons and theme refresh will not work until both grants are enabled. App updates can still rebuild and install a pack; you may need to select it manually. You can finish these grants later from Setup.", "settings_deferred")
            }, 8)
        }
    }

    private fun ready(root: LinearLayout, draft: OnboardingState.Draft) {
        val missing = missingAccess(draft)
        val card = section(root, if (missing.isEmpty()) "Ready for IconGen" else "Ready, with access deferred",
            "Finish setup to save your choices and open IconGen. This does not start a build.")
        ui.add(card, ui.text("App updates: ${if (draft.appUpdates) "on" else "off"}\nTheme changes: ${if (draft.themeChanges) "on" else "off"}\nOptional notifications: ${if (draft.notifications) "on" else "off"}\nIconGen updates: ${if (draft.appReleaseUpdates) "on" else "off"}", 14f), 16)
        if (missing.isNotEmpty()) ui.add(card, ui.text(missing.joinToString("\n") { "• ${it.second}" }, 13f), 16)
        val pending = IconPackInstaller.pendingApk(this) != null
        val installed = try {
            packageManager.getPackageInfo(PackageChangeReceiver.PACK_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }
        val firstAction = when {
            pending -> "Next: use Finish installation or Approve installation in IconGen to finish the existing pack. Then open More options → Choose in launcher."
            installed -> "Your generated pack is already installed. Next: open More options → Choose in launcher and select IconGen if needed. Use Update icon pack when you want a fresh build."
            else -> "Next: tap Create icon pack in IconGen, wait for the build and approve Android’s installation. Then open More options → Choose in launcher and select IconGen."
        }
        ui.add(card, ui.text(firstAction, 14f), 16)
        ui.add(root, ui.button("Finish setup", primary = true) { finishSetup() }, 20)
        ui.add(root, ui.button("Review choices") { go(OnboardingState.Step.FEATURES) }, 8)
    }

    private fun status(parent: LinearLayout, value: String) {
        ui.add(parent, ui.text(value, 15f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }, 16)
    }

    private fun defer(title: String, message: String, key: String) {
        ui.dialog(this, title) { content, dialog ->
            ui.add(content, ui.text(message, 14f))
            ui.add(content, ui.button("I understand · continue", primary = true) {
                prefs.edit().putBoolean(key, true).commit()
                dialog.dismiss()
                next()
            }, 20)
            ui.add(content, ui.button("Go back") { dialog.dismiss() }, 8)
        }
    }

    private fun missingAccess(draft: OnboardingState.Draft): List<Pair<String, String>> = buildList {
        if (!InstallAccess.canInstall(this@OnboardingActivity)) add("install_deferred" to "Installation blocked: generated packs cannot be installed or updated yet.")
        if (draft.automaticRefresh && !LauncherRefreshAccess.isGranted(this@OnboardingActivity)) add("background_deferred" to "Background icon refresh is blocked: rebuilt packs cannot be applied automatically yet.")
        if (draft.notifications && !NotificationAccessUi.available(this@OnboardingActivity)) add("notifications_deferred" to "Optional progress and results are blocked by Android.")
        if (draft.automaticRefresh && !WirelessGrantStatus.read(this@OnboardingActivity).complete) add("settings_deferred" to "Automatic icon application and theme refresh are not ready: finish both Settings grants.")
    }

    private fun finishSetup() {
        val draft = OnboardingState.draft(this)
        // Access may have been revoked while a later page or the system Settings was open.
        val unacknowledged = missingAccess(draft).firstOrNull { !prefs.getBoolean(it.first, false) }
        if (unacknowledged != null) {
            go(when (unacknowledged.first) {
                "install_deferred" -> OnboardingState.Step.INSTALL
                "background_deferred" -> OnboardingState.Step.BACKGROUND
                "notifications_deferred" -> OnboardingState.Step.NOTIFICATIONS
                else -> OnboardingState.Step.SETTINGS
            })
            return
        }
        BuildCoordinator.setAutoUpdateEnabled(this, draft.appUpdates)
        NotificationPreferences.setEnabled(this, draft.notifications)
        AppUpdateChecker.setEnabled(this, draft.appReleaseUpdates)
        // Old operation recovery stays available, but its former first-run permission tour is done.
        getSharedPreferences("permission_setup", MODE_PRIVATE).edit()
            .putBoolean("notification_asked", true).putBoolean("source_asked", true)
            .putBoolean("flow_active", false).remove("awaiting").commit()
        OnboardingState.markComplete(this)
        PackageChangeMonitor.register(applicationContext)
        BackgroundMonitorService.sync(this, refresh = true)
        IconPackSelectionJobService.scheduleAfterAccessChange(this)
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_THEME_ACCESS, intent.getBooleanExtra(MainActivity.EXTRA_THEME_ACCESS, false)))
        finish()
    }

    private fun openAccess(intent: Intent) {
        error = null
        try {
            accessLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                accessLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
                error = "Android could not open this access screen. Open Settings → Apps → IconGen manually, then return here."
                render()
            }
        }
    }

    companion object {
        const val EXTRA_RESTART = "restart_onboarding"
    }
}
