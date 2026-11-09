package space.linuxct.nothing.icongen

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.LauncherActivityInfo
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.os.Process
import android.os.UserManager

/** Launchable components plus the resource-only pack's canonical theme-picker component. */
object AppScanner {
    class AppIcon internal constructor(
        val componentName: String,
        val label: String,
        val userHandle: UserHandle,
        val profileSerial: Long,
        val sourcePackage: String,
        val sourceIdentity: String,
        private val context: Context,
        private val activity: LauncherActivityInfo?,
        private val density: Int,
    ) {
        private var loadedIcon: Drawable? = null
        val icon: Drawable
            get() = loadedIcon ?: (activity?.getIcon(density)
                ?: requireNotNull(context.getDrawable(R.drawable.ic_icon_pack))).also { loadedIcon = it }

        internal fun releaseIcon() { loadedIcon = null }
    }

    internal data class Inventory(val apps: List<AppIcon>, val completeProfiles: Set<Long>)

    fun scanInstalledApps(context: Context): List<AppIcon> = inventory(context).apps

    internal fun inventory(
        context: Context,
        identities: Map<String, String> = PackageChangeMonitor.identities(context),
    ): Inventory {
        val appContext = context.applicationContext
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val users = context.getSystemService(UserManager::class.java)
        val density = context.resources.displayMetrics.densityDpi
        val result = ArrayList<AppIcon>()
        val seen = HashSet<String>()
        val complete = HashSet<Long>()
        for (user in launcherApps.profiles) {
            val serial = users.getSerialNumberForUser(user)
            if (serial < 0) continue
            val activities = try {
                launcherApps.getActivityList(null, user)
            } catch (_: SecurityException) {
                // An inaccessible profile is not an authoritative empty inventory.
                continue
            }
            if (users.isUserUnlocked(user) && !users.isQuietModeEnabled(user)) complete.add(serial)
            for (activity in activities) {
                val pkg = activity.componentName.packageName
                if (pkg == IconPackAssembler.PACKAGE_NAME) continue
                val component = activity.componentName.flattenToString()
                if (!seen.add(component)) continue
                val identity = identities["$serial:$pkg"]
                    ?: PackageChangeMonitor.identity(context, user, pkg) ?: continue
                result.add(AppIcon(component, activity.label.toString(), user, serial, pkg, identity,
                    appContext, activity, density))
            }
        }
        // The synthetic resource-only pack entry always comes from bundled, untransformed artwork.
        val user = Process.myUserHandle()
        val ownIdentity = requireNotNull(PackageChangeMonitor.identity(context, user, context.packageName)) {
            "Cannot read IconGen's source identity."
        }
        result.add(AppIcon(
            "${IconPackAssembler.PACKAGE_NAME}/${IconPackAssembler.PACKAGE_NAME}.IconPackThemeActivity",
            context.getString(R.string.icon_pack_name), user, users.getSerialNumberForUser(user),
            context.packageName, ownIdentity, appContext, null, density,
        ))
        return Inventory(result, complete)
    }
}
