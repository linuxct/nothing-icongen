package space.linuxct.nothing.icongen

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.Context
import android.content.pm.PackageInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AppUpdateCheckerTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("app_update_prefs", Context.MODE_PRIVATE)
    private val scheduler get() = context.getSystemService(JobScheduler::class.java)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val now = 1_000_000_000L

    @Before fun setUp() {
        AppUpdateChecker.setEnabled(context, false)
        prefs.edit().clear().commit()
        context.getSharedPreferences("notification_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("app_available_update", Context.MODE_PRIVATE).edit().clear().commit()
        scheduler.cancelAll()
        notifications.cancelAll()
        notifications.deleteNotificationChannel(AppUpdateChecker.CHANNEL_ID)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(true)
    }

    @After fun tearDown() {
        AppUpdateChecker.setEnabled(context, false)
        notifications.cancelAll()
        scheduler.cancelAll()
    }

    private fun enable() {
        prefs.edit().putBoolean("app_update_enabled", true)
            .putBoolean("app_update_decision", true).commit()
    }

    private fun release(tag: String = "build-1.0.0.9-9.1793917972"): JSONObject = JSONObject()
        .put("tag_name", tag)
        .put("name", "IconGen \"stable\" {release}")
        .put("html_url", "https://github.com/linuxct/nothing-icongen/releases/tag/$tag")
        .put("published_at", "2026-11-05T12:00:00Z")
        .put("draft", false)
        .put("prerelease", false)
        .put("assets", JSONArray().put(JSONObject()
            .put("name", "IconGen.apk")
            .put("size", 1234L)
            .put("digest", "sha256:" + "a".repeat(64))
            .put("browser_download_url", "https://github.com/linuxct/nothing-icongen/releases/download/$tag/IconGen.apk")
            .put("uploader", JSONObject().put("html_url", "https://github.com/uploader"))))
        .put("author", JSONObject().put("name", "Not the release title")
            .put("html_url", "https://github.com/author"))

    private fun available(tag: String = "build-1.0.0.9-9.1793917972") =
        AppUpdateChecker.CheckResult.Available(AppUpdateChecker.parseRelease(release(tag).toString())!!)


    @Test fun detectedReleaseRemainsAvailableUntilItsVersionIsInstalled() {
        fun install(version: String) {
            shadowOf(context.packageManager).installPackage(PackageInfo().apply {
                packageName = context.packageName
                versionName = version
                versionCode = 9
            })
        }
        install("1.0.0.1")
        context.getSharedPreferences("app_available_update", Context.MODE_PRIVATE).edit()
            .putString("release", release().toString()).commit()
        assertEquals("1.0.0.9", AppUpdateChecker.availableRelease(context)?.version)
        install("1.0.0.10")
        assertNull(AppUpdateChecker.availableRelease(context))
        install("1.0.0.1")
        assertNull(AppUpdateChecker.availableRelease(context))
    }

    @Test fun backgroundDetectionNeverStartsInstallationEvenWithAnOldAutomaticInstallPreference() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = context.packageName
            versionName = "1.0.0.1"
            versionCode = 1
        })
        context.getSharedPreferences(AppSelfUpdater.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("automatic_install_enabled", true).commit()
        enable()
        AppUpdateChecker.checkAutomatically(context, now, fetch = { available() })
        assertEquals(AppSelfUpdater.Phase.IDLE, AppSelfUpdater.status(context).phase)
        assertFalse(AppSelfUpdater.isRunning(context))
        assertEquals(1, notifications.activeNotifications.size)
    }

    @Test fun parserKeepsRootMetadataAndUnescapesStrings() {
        val parsed = AppUpdateChecker.parseRelease(release().toString())!!
        assertEquals("build-1.0.0.9-9.1793917972", parsed.tagName)
        assertEquals("1.0.0.9", parsed.version)
        assertEquals("IconGen \"stable\" {release}", parsed.title)
        assertEquals("https://github.com/linuxct/nothing-icongen/releases/tag/${parsed.tagName}", parsed.url)
        assertEquals("2026-11-05T12:00:00Z", parsed.releasedAt)
    }

    @Test fun parserRejectsDraftPrereleaseMissingApkAndMalformedVersion() {
        assertNull(AppUpdateChecker.parseRelease(release().put("draft", true).toString()))
        assertNull(AppUpdateChecker.parseRelease(release().put("prerelease", true).toString()))
        assertNull(AppUpdateChecker.parseRelease(release().put("assets", JSONArray()).toString()))
        assertNull(AppUpdateChecker.parseRelease(release("build-not-a-version-9.1793917972").toString()))
        assertNull(AppUpdateChecker.parseRelease(release("1.0.00.9").toString()))
        assertTrue(AppUpdateChecker.evaluateRelease("not JSON", "1.0.0.1") is AppUpdateChecker.CheckResult.Failed)
    }

    @Test fun invalidArtifactMetadataCannotBecomeAnInstallableUpdate() {
        val invalidSize = release().apply { getJSONArray("assets").getJSONObject(0).put("size", -1) }
        assertTrue(AppUpdateChecker.evaluateRelease(invalidSize.toString(), "1.0.0.8") is AppUpdateChecker.CheckResult.Failed)
        val invalidDigest = release().apply { getJSONArray("assets").getJSONObject(0).put("digest", "sha256:truncated") }
        assertTrue(AppUpdateChecker.evaluateRelease(invalidDigest.toString(), "1.0.0.8") is AppUpdateChecker.CheckResult.Failed)
        val wrongRepository = release().apply {
            getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://github.com/another/app/releases/download/v9/IconGen.apk")
        }
        assertNull(AppUpdateChecker.parseRelease(wrongRepository.toString()))
    }

    @Test fun fourPartVersionsUseNumericComponentsNotLexicalOrderOrMachineIntegerRange() {
        assertTrue(UpdateVersion.parse("1.0.0.10")!! > UpdateVersion.parse("1.0.0.9")!!)
        assertTrue(UpdateVersion.parse("1.0.1.0")!! > UpdateVersion.parse("1.0.0.999")!!)
        assertTrue(UpdateVersion.parse("1.0.0.999999999999999999999")!! > UpdateVersion.parse("1.0.0.10")!!)
        assertEquals(0, UpdateVersion.parse("v1.0.0.9+local")!!.compareTo(UpdateVersion.parse("1.0.0.9+other")!!))
        assertEquals(0, UpdateVersion.parse("1.0.0")!!.compareTo(UpdateVersion.parse("1.0.0.0")!!))
        assertNull(UpdateVersion.parse("1.0.0-01"))
        assertNull(UpdateVersion.parse("1.0.0."))
        assertNull(UpdateVersion.parse("1.0.0+"))
    }

    @Test fun prereleaseIdentifiersFollowSemverPrecedence() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta",
            "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        ordered.zipWithNext().forEach { (older, newer) ->
            assertTrue("$newer must be newer than $older", UpdateVersion.parse(newer)!! > UpdateVersion.parse(older)!!)
        }
        assertEquals("1.0.0.9", UpdateVersion.fromTag("build-v1.0.0.9-900.1999999999")!!.text)
        assertEquals("1.0.0.9-rc.1", UpdateVersion.fromTag("v1.0.0.9-rc.1")!!.text)
    }

    @Test fun offersOnlyStrictlyNewerVersionsNotChangedTagsBuildNumbersOrTimestamps() {
        assertTrue(AppUpdateChecker.evaluateRelease(release().toString(), "1.0.0.8") is AppUpdateChecker.CheckResult.Available)
        assertEquals(AppUpdateChecker.CheckResult.Current("1.0.0.9"),
            AppUpdateChecker.evaluateRelease(release("build-1.0.0.9-999.9999999999").toString(), "1.0.0.9"))
        assertEquals(AppUpdateChecker.CheckResult.Current("1.0.0.10"),
            AppUpdateChecker.evaluateRelease(release().toString(), "1.0.0.10"))
        assertTrue(AppUpdateChecker.evaluateRelease(release("v1.0.0.10").toString(), "v1.0.0.9") is AppUpdateChecker.CheckResult.Available)
        assertTrue(AppUpdateChecker.evaluateRelease(release().toString(), "unknown") is AppUpdateChecker.CheckResult.Failed)
    }

    @Test fun failedChecksStayDueAndAskFiniteJobForBackoff() {
        enable()
        assertTrue(AppUpdateChecker.checkAutomatically(context, now, fetch = { AppUpdateChecker.CheckResult.Failed("HTTP 503") }))
        assertFalse(prefs.contains("app_update_last_success"))
        assertTrue(AppUpdateChecker.isDue(context, now + 1))
        var calls = 0
        assertFalse(AppUpdateChecker.checkAutomatically(context, now + 1, fetch = {
            calls++
            AppUpdateChecker.CheckResult.Current("1.0.0.9")
        }))
        assertEquals(1, calls)
        assertEquals(now + 1, prefs.getLong("app_update_last_success", 0))
        assertFalse(AppUpdateChecker.isDue(context, now + AppUpdateChecker.INTERVAL_MS))
        assertTrue(AppUpdateChecker.isDue(context, now + 1 + AppUpdateChecker.INTERVAL_MS))
    }

    @Test fun blockedNotificationsDoNotTurnSeenReleaseIntoNotifiedOrConsumeInterval() {
        enable()
        prefs.edit().putString("app_update_last_release", available().release.tagName)
            .putLong("app_update_last_check", now).commit()
        NotificationPreferences.setEnabled(context, false)
        assertFalse(AppUpdateChecker.checkAutomatically(context, now, fetch = { available() }))
        assertTrue(notifications.activeNotifications.isEmpty())
        assertFalse(prefs.contains("app_update_notified_version"))
        assertTrue(AppUpdateChecker.isDue(context, now + 1))
        NotificationPreferences.setEnabled(context, true)
        AppUpdateChecker.checkAutomatically(context, now + 1, fetch = { available() })
        val posted = notifications.activeNotifications.single().notification
        assertTrue(posted.contentIntent.isImmutable)
        assertEquals("1.0.0.9", prefs.getString("app_update_notified_version", null))
        assertFalse(AppUpdateChecker.isDue(context, now + 2))
    }

    @Test fun appAndChannelBlocksLeaveReleaseEligibleForNextDueStart() {
        enable()
        shadowOf(notifications).setNotificationsEnabled(false)
        AppUpdateChecker.checkAutomatically(context, now, fetch = { available() })
        assertTrue(AppUpdateChecker.isDue(context, now))
        assertTrue(notifications.activeNotifications.isEmpty())
        shadowOf(notifications).setNotificationsEnabled(true)
        AppUpdateChecker.ensureChannel(context)
        val channel = notifications.getNotificationChannel(AppUpdateChecker.CHANNEL_ID)
        channel.importance = NotificationManager.IMPORTANCE_NONE
        notifications.createNotificationChannel(channel)
        AppUpdateChecker.checkAutomatically(context, now, fetch = { available() })
        assertTrue(AppUpdateChecker.isDue(context, now))
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test fun oldDedupKeysCannotSuppressFirstRealDeliveryAndSameVersionTagDoesNotNotifyAgain() {
        enable()
        prefs.edit().putString("app_update_last_release", available().release.tagName).commit()
        AppUpdateChecker.checkAutomatically(context, now, fetch = { available() })
        assertEquals(1, notifications.activeNotifications.size)
        notifications.cancelAll()
        AppUpdateChecker.checkAutomatically(context, now + AppUpdateChecker.INTERVAL_MS,
            fetch = { available("build-1.0.0.9-999.9999999999") })
        assertTrue(notifications.activeNotifications.isEmpty())
        assertFalse(AppUpdateChecker.isDue(context, now + AppUpdateChecker.INTERVAL_MS + 1))
    }

    @Test fun disablingEitherPreferenceCancelsNotificationAndRejectsLateFetch() {
        enable()
        AppUpdateChecker.checkAutomatically(context, now, fetch = { available() })
        assertEquals(1, notifications.activeNotifications.size)
        NotificationPreferences.setEnabled(context, false)
        assertTrue(notifications.activeNotifications.isEmpty())
        NotificationPreferences.setEnabled(context, true)
        prefs.edit().remove("app_update_notified_version").remove("app_update_last_success").commit()
        AppUpdateChecker.checkAutomatically(context, now, fetch = {
            AppUpdateChecker.setEnabled(context, false)
            available()
        })
        assertTrue(notifications.activeNotifications.isEmpty())
        assertFalse(prefs.contains("app_update_last_success"))
        assertNull(scheduler.getPendingJob(AppUpdateChecker.PERIODIC_JOB_ID))
        assertNull(scheduler.getPendingJob(AppUpdateChecker.DUE_JOB_ID))
    }

    @Test fun stoppedCheckCannotWriteStateOrPostAndDisableReenableRevokesOldGeneration() {
        enable()
        var active = true
        AppUpdateChecker.checkAutomatically(context, now, isActive = { active }, fetch = {
            active = false
            available()
        })
        assertFalse(prefs.contains("app_update_last_success"))
        assertTrue(notifications.activeNotifications.isEmpty())
        val oldGeneration = AppUpdateChecker.automaticGeneration()
        AppUpdateChecker.setEnabled(context, false)
        AppUpdateChecker.setEnabled(context, true)
        assertFalse(AppUpdateChecker.automaticAllowed(context, oldGeneration))
    }

    @Test fun currentAndNoStableResultsConsumeIntervalAndClearStaleNotification() {
        enable()
        AppUpdateChecker.checkAutomatically(context, now, fetch = { available() })
        AppUpdateChecker.checkAutomatically(context, now + AppUpdateChecker.INTERVAL_MS,
            fetch = { AppUpdateChecker.CheckResult.Current("1.0.0.9") })
        assertTrue(notifications.activeNotifications.isEmpty())
        val later = now + 2 * AppUpdateChecker.INTERVAL_MS
        AppUpdateChecker.checkAutomatically(context, later, fetch = { AppUpdateChecker.CheckResult.NoStableRelease })
        assertEquals(later, prefs.getLong("app_update_last_success", 0))
    }

    @Test fun syncKeepsBothPendingSchedulesAndEnqueuesFiniteWorkExactlyWhenDue() {
        enable()
        AppUpdateChecker.sync(context, now)
        val periodic = scheduler.getPendingJob(AppUpdateChecker.PERIODIC_JOB_ID)!!
        val finite = scheduler.getPendingJob(AppUpdateChecker.DUE_JOB_ID)!!
        assertTrue(periodic.isPeriodic)
        assertEquals(AppUpdateChecker.INTERVAL_MS, periodic.intervalMillis)
        assertFalse(finite.isPeriodic)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, finite.networkType)
        assertEquals(JobInfo.BACKOFF_POLICY_EXPONENTIAL, finite.backoffPolicy)
        assertEquals(30_000L, finite.initialBackoffMillis)
        AppUpdateChecker.sync(context, now + 100)
        assertSame(periodic, scheduler.getPendingJob(AppUpdateChecker.PERIODIC_JOB_ID))
        assertSame(finite, scheduler.getPendingJob(AppUpdateChecker.DUE_JOB_ID))
        scheduler.cancel(AppUpdateChecker.DUE_JOB_ID)
        AppUpdateChecker.checkAutomatically(context, now, fetch = { AppUpdateChecker.CheckResult.Current("1.0.0.9") })
        AppUpdateChecker.sync(context, now + AppUpdateChecker.INTERVAL_MS - 1)
        assertNull(scheduler.getPendingJob(AppUpdateChecker.DUE_JOB_ID))
        AppUpdateChecker.sync(context, now + AppUpdateChecker.INTERVAL_MS)
        assertNotNull(scheduler.getPendingJob(AppUpdateChecker.DUE_JOB_ID))
        assertSame(periodic, scheduler.getPendingJob(AppUpdateChecker.PERIODIC_JOB_ID))
    }

    @Test fun periodicWakeupEnqueuesFiniteWorkAndStoppedFiniteJobRetriesOnlyWhileEnabled() {
        enable()
        val controller = Robolectric.buildService(UpdateCheckJob::class.java).create()
        val service = controller.get()
        try {
            fun params(id: Int): JobParameters = Shadow.newInstanceOf(JobParameters::class.java).also {
                ReflectionHelpers.setField(it, "jobId", id)
            }
            assertFalse(service.onStartJob(params(AppUpdateChecker.PERIODIC_JOB_ID)))
            assertNotNull(scheduler.getPendingJob(AppUpdateChecker.DUE_JOB_ID))
            assertTrue(service.onStopJob(params(AppUpdateChecker.DUE_JOB_ID)))
            assertFalse(service.onStopJob(params(AppUpdateChecker.PERIODIC_JOB_ID)))
            AppUpdateChecker.setEnabled(context, false)
            assertFalse(service.onStartJob(params(AppUpdateChecker.DUE_JOB_ID)))
            assertFalse(service.onStopJob(params(AppUpdateChecker.DUE_JOB_ID)))
        } finally {
            controller.destroy()
        }
    }
}
