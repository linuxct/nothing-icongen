# Nothing IconGen: Translucent Icon Generator

Create and install Nothing OS 5.0-style translucent icons on your phone. Making your apps a bit more Lucent!


## Screenshots

| IconGen app | Light theme | Dark theme |
|:---:|:---:|:---:|
| <a href="screenshots/app-ui-2.png"><img src="screenshots/app-ui-2.png" alt="IconGen app" width="230"></a> | <a href="screenshots/light-theme.png"><img src="screenshots/light-theme.png" alt="Icon pack in light theme" width="230"></a> | <a href="screenshots/dark-theme.png"><img src="screenshots/dark-theme.png" alt="Icon pack in dark theme" width="230"></a> |

## Install IconGen: two supported methods

**You must use either a PC with ADB or InstallerX Revived on the phone with Shizuku.** IconGen targets SDK 22, so Android blocks installation through the normal system installer. Simply downloading `IconGen.apk` and tapping the stock **Install** button will not work.

### Requirements

- A Nothing phone running **Android 17 (API 37), arm64**.
- **Nothing Launcher 5.0 or newer**.
- For **Method 1**, a PC with `adb`. For **Method 2**, InstallerX Revived and Shizuku on the phone, plus Wi-Fi for wireless debugging.

Download `IconGen.apk` from [Releases](https://github.com/linuxct/nothing-icongen/releases). Each push publishes a signed build.

### Method 1: PC with ADB

Enable USB debugging, connect the phone to your PC, and authorize the PC when Android asks.

Install it with the low-target bypass (the app targets SDK 22):

```sh
adb install -r --bypass-low-target-sdk-block IconGen.apk
```

### Method 2: on the phone with InstallerX Revived and Shizuku

This alternative needs **no PC and no root**, but it still requires privileged installation through Shizuku. Installing InstallerX Revived alone is not enough.

1. Install [Shizuku](https://shizuku.rikka.app/download/) and the [latest stable InstallerX Revived APK](https://github.com/wxxsfxyzm/InstallerX-Revived/releases/latest). Allow installation from your browser/file manager if Android asks when installing these helper apps.
2. Connect to trusted Wi-Fi and enable **Developer options → USB debugging** and **Wireless debugging**.
3. In Shizuku, use **Start via Wireless debugging → Pairing**. Open Android's **Wireless debugging → Pair device with pairing code**, then enter the code through Shizuku's pairing notification. Return to Shizuku and tap **Start**. Confirm that Shizuku is running. See its [wireless-start guide](https://shizuku.rikka.app/guide/setup/#start-via-wireless-debugging) for details.
4. Open InstallerX Revived. On **Home**, select **Shizuku** as the authorizer, and grant InstallerX permission when Shizuku asks. Allow file access if prompted.
5. Under **Profiles**, edit the default profile. Select **Shizuku** as its authorizer (or **Follow global** with Shizuku selected on Home), choose **Dialog** install mode, and enable **Install Options → Bypass Low Target SDK Block**. Save the profile. If your browser/file manager already has a scoped profile, configure that matching profile instead.
6. Open the downloaded `IconGen.apk` **with InstallerX Revived**, not Android's stock installer. Confirm the install in InstallerX. If it reports `INSTALL_FAILED_DEPRECATED_SDK_VERSION`, check that the profile actually used has **Bypass Low Target SDK Block** enabled and that Shizuku is still running and authorized.
7. Open IconGen and complete its setup. For an entirely on-phone setup, follow [Settings access: wireless](#settings-access-wireless) below. **InstallerX/Shizuku installation does not replace IconGen's own Settings-access setup.**

Shizuku's wireless-start service must be started again after a reboot before using this installation method again. IconGen does not need Shizuku to remain running after installation and its own setup are complete. Once finished, turn Wireless debugging off; enable it again when needed.

## Create your pack

1. Follow the guided setup. Choose features, allow installation and **Background icon refresh** if asked, then finish the access steps. Revisit **Setup → Run setup again**.
2. Tap **Create icon pack** (or **Update icon pack**). Approve installation if Android asks.
3. Open **⋮ → Choose in launcher** and select **IconGen Nothing Translucent Icon Pack** once.

After updating IconGen, update the pack once. Generated artwork is mapped to launcher activities; package-level system icons keep their original artwork.

The automation switches control app updates, theme changes, and optional notifications. App updates rebuild, install, and apply the pack. Theme changes refresh it without rebuilding.

Automatic pack updates wait 5 seconds for app installs/updates to settle. Another app change during that wait or generation restarts a 30-second quiet period. Pending waits recover after an app restart or reboot using the remaining delay. Manual generation starts immediately, but a later app update can supersede it before installation begins. Incoming app updates do not cancel an installation that has already started.

Theme changes detected while the screen is off or the phone is locked stay pending until wake/unlock. This includes Battery Saver and Bedtime mode: refreshing Nothing Launcher's selector while it sleeps can leave icons using the previous theme. IconGen reapplies the pack after unlock without rebuilding it.

Applying updated icons and theme refresh need Settings access. Choose PC or wireless setup.

## Settings access: PC

Connect the phone to your authorized PC and run both commands:

```sh
adb shell pm grant space.linuxct.nothing.icongen android.permission.WRITE_SECURE_SETTINGS
adb shell appops set space.linuxct.nothing.icongen WRITE_SETTINGS allow
```

Return to IconGen and check that both grants are confirmed.

## Settings access: wireless

1. Choose **Wireless · on this phone → Wireless setup** during setup. Later, use **Setup → Settings access**. Allow pairing notifications if asked.
2. Tap **Start pairing**. In Settings, enable Wireless debugging on trusted Wi-Fi, then open **Pair device with pairing code**. Enable Developer options if needed.
3. **Keep the pairing dialog open and Settings in front. Do not switch apps or use split screen.**
4. Expand IconGen's notification and reply with the dialog's **six-digit code**. No address or port is needed.
5. Wait for setup to finish, then return to IconGen. Check that **both Settings grants** are confirmed; pairing alone is not enough.
6. Turn Wireless debugging off when done. Use **Stop setup** to cancel if needed.

**Background icon refresh** uses Android's **Display over other apps** permission; IconGen draws no overlay. Only enabled theme monitoring and active wireless setup require silent notifications.

After a force-stop, open IconGen again to resume automation.
