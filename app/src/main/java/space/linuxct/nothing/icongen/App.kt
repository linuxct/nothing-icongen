package space.linuxct.nothing.icongen

import android.app.Application
import android.util.Log
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream

class App : Application() {

    companion object {
        @Volatile lateinit var instance: App
            private set

        const val AAPT2_LIB = "libaapt2.so"
        const val AAPT_LIB = "libaapt.so"
        const val ANDROID_JAR = "android.jar"
        private const val TAG = "App"
    }

    /** Non-null when the toolchain could not be prepared. */
    @Volatile
    var toolsError: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        PackageChangeMonitor.register(this)
        IconPackBuildJobService.deleteStaleBuildFolders(this)
        BackgroundMonitorService.sync(this, refresh = true)
        // A manifest install callback must run before disappearance-of-session recovery;
        // it can authoritatively confirm a same-version reinstall.
        Handler(Looper.getMainLooper()).post {
            AppSelfUpdater.recover(this)
            IconPackInstaller.restoreState(this)
            IconPackSelectionJobService.schedule(this)
            AppUpdateChecker.sync(this)
        }
    }

    /**
     * The executables are packaged as native libraries so the OS extracts them into
     * nativeLibraryDir at install time; that directory is executable, unlike app data
     * storage (W^X blocks exec from filesDir on API 29+). android.jar is copied out of
     * assets on first use because it is only read, never executed.
     */
    fun ensureTools(): Unit {
        val aapt2 = File(nativeLibraryDir, AAPT2_LIB)
        if (!aapt2.exists()) {
            val msg = "aapt2 missing at ${aapt2.absolutePath}. " +
                "Native libs must be extracted (android:extractNativeLibs / useLegacyPackaging)."
            toolsError = msg
            throw IllegalStateException(msg)
        }
        val androidJar = File(filesDir, ANDROID_JAR)
        if (!androidJar.exists()) {
            try {
                assets.open(ANDROID_JAR).use { input ->
                    FileOutputStream(androidJar).use { output -> input.copyTo(output, 256 * 1024) }
                }
            } catch (t: Throwable) {
                val msg = "Failed to copy $ANDROID_JAR from assets: ${t.stackTraceToString()}"
                toolsError = msg
                throw RuntimeException(msg, t)
            }
        }
        toolsError = null
    }

    /** Directory that holds libaapt2.so / libaapt.so and their shared-library deps. */
    val nativeLibraryDir: String get() = applicationInfo.nativeLibraryDir

    val aapt2Path: String get() = File(nativeLibraryDir, AAPT2_LIB).absolutePath

    val androidJarPath: String get() = File(filesDir, ANDROID_JAR).absolutePath
}
