// SPDX-License-Identifier: CC0-1.0
package org.getflourish.beeswax

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import java.util.Calendar

// Updates keep an app's first install time, so they pass the lock and new installs don't.
object Lock {
    private const val LOCKED = "locked"
    private const val BLOCKED = "blocked"
    private const val APPROVED = "approved"
    private const val ALLOWED = "allowed"
    private const val QUARANTINED = "quarantined"
    private const val SCHEDULED = "scheduled"
    private const val FROM = "from"
    private const val UNTIL = "until"
    private const val DAYS = "days"
    private const val SCHEDULE_ON = "schedule_on"
    private const val RECOVERY_MINUTES = "recovery_minutes"

    fun isLocked(context: Context) = prefs(context).getBoolean(LOCKED, false)

    fun blocked(context: Context) = set(context, BLOCKED)

    fun quarantined(context: Context) = set(context, QUARANTINED)

    // Android turns system backups off for a device or profile owner, after the broadcast Receiver gets, so the
    // screen runs this on every refresh. Debugging is blocked from activation; once locked, Allow developer
    // options decides. Returns whether backups are on. Not synchronized, so the screen never waits for Lock.
    fun activate(context: Context): Boolean {
        if (!isLocked(context)) dpm(context).addUserRestriction(admin(context), UserManager.DISALLOW_DEBUGGING_FEATURES)
        return runCatching { dpm(context).setBackupServiceEnabled(admin(context), true) }.isSuccess
    }

    // Synchronized, with the checks inside, so two Lock dialogs confirmed at once lock only once, and a user added
    // in Settings while a dialog was open still stops it. Other users have their own browsers and app stores.
    // Returns false when other users stopped it.
    @Synchronized
    fun lock(context: Context, code: String, plan: Map<String, Boolean>): Boolean {
        if (isLocked(context)) return true
        val deviceOwner = dpm(context).isDeviceOwnerApp(context.packageName)
        if (deviceOwner && dpm(context).getSecondaryUsers(admin(context)).isNotEmpty()) return false
        Code.save(context, code)
        lock(context, plan)
        return true
    }

    // A user made by Create user locks at its first start as a copy of the Owner profile at the time: the same
    // code, schedule and Lost code recovery, with Owner's choices as the plan. Apps are copied by name, so one
    // blocked in Owner is blocked here, and one allowed in Owner but new to this user still waits for the code.
    @Synchronized
    fun lockUser(context: Context, copy: Intent) {
        if (isLocked(context)) return
        val salt = copy.getStringExtra(Code.SALT) ?: return
        val hash = copy.getStringExtra(Code.HASH) ?: return
        Code.paste(context, salt, hash)
        prefs(context).edit()
            .putStringSet(SCHEDULED, copy.getStringArrayExtra(SCHEDULED)?.toSet() ?: emptySet())
            .putBoolean(SCHEDULE_ON, copy.getBooleanExtra(SCHEDULE_ON, true))
            .putInt(FROM, copy.getIntExtra(FROM, from(context)))
            .putInt(UNTIL, copy.getIntExtra(UNTIL, until(context)))
            .putInt(DAYS, copy.getIntExtra(DAYS, days(context)))
            .putInt(RECOVERY_MINUTES, copy.getIntExtra(RECOVERY_MINUTES, 0))
            .commit()
        val allowed = copy.getStringArrayExtra(ALLOWED).orEmpty().map { it to true }
        val blocked = copy.getStringArrayExtra(BLOCKED).orEmpty().map { it to false }
        lock(context, (allowed + blocked).toMap())
    }

    // Every built-in app, like a user added in Settings, so Camera and the rest are there; the new user then
    // blocks what Owner blocks. Android lets a device owner create a user even though Lock blocks new users.
    @Synchronized
    fun createUser(context: Context, name: String) {
        if (!isLocked(context)) return
        val off = blocked(context) + quarantined(context)
        val copy = Code.copy(context).apply {
            putStringArray(BLOCKED, off.toTypedArray())
            putStringArray(ALLOWED, (listed(context) - off).toTypedArray())
            putStringArray(SCHEDULED, set(context, SCHEDULED).toTypedArray())
            putBoolean(SCHEDULE_ON, isScheduleOn(context))
            putInt(FROM, from(context))
            putInt(UNTIL, until(context))
            putInt(DAYS, days(context))
            putInt(RECOVERY_MINUTES, recoveryMinutes(context))
        }
        dpm(context).createAndManageUser(admin(context), name, admin(context), copy, DevicePolicyManager.LEAVE_ALL_SYSTEM_APPS_ENABLED)
    }

    private fun lock(context: Context, plan: Map<String, Boolean>) {
        val switchedOn = plan.filterValues { it }.keys
        val switchedOff = plan.filterValues { !it }.keys
        // Browsers uninstalled for this user count too, since an app store could install them again.
        val blocked = detectedBrowsers(context) - switchedOn + switchedOff - essential(context)
        // Before a new user's first start, Owner can add any app it can see with Install available apps.
        val present = userApps(context).filter { !inUser(context) || isSystem(it.applicationInfo) }
        val approved = present.filter { it.packageName !in blocked }.map { fingerprint(it) }.toSet()
        // Saved and blocked first, so if a later step fails, apps stay blocked and Guard keeps them so.
        prefs(context).edit()
            .putBoolean(LOCKED, true)
            .putStringSet(BLOCKED, blocked)
            .putStringSet(APPROVED, approved)
            .putStringSet(QUARANTINED, emptySet())
            .commit()
        // Android starts Guard when its component is enabled, and keeps it running from then on.
        setGuard(context, true)
        enforce(context)
        for (restriction in restrictions(context)) dpm(context).addUserRestriction(admin(context), restriction)
    }

    // Run by Deactivate. The lists are cleared only after every app is usable again.
    @Synchronized
    fun unlock(context: Context) {
        // Unlock first, so the "package added" broadcasts from unhiding don't hide anything again.
        prefs(context).edit().putBoolean(LOCKED, false).commit()
        setGuard(context, false)
        // Allow developer options may have lifted the debugging block. In a user, Android lets Beeswax change
        // only that user's own restrictions, and refuses the device owner's.
        val ownerOnly = listOf(
            UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
            UserManager.DISALLOW_CONFIG_DATE_TIME, UserManager.DISALLOW_DEBUGGING_FEATURES,
        )
        val all = restrictions(context) + if (inUser(context)) emptyList() else ownerOnly
        for (restriction in all) dpm(context).clearUserRestriction(admin(context), restriction)
        // Every app, not only the saved lists, so nothing stays blocked if a list was lost.
        val packages = context.packageManager.getInstalledPackages(
            PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()),
        )
        for (info in packages) {
            val packageName = info.packageName
            if (isHidden(context, packageName)) setHidden(context, packageName, false)
            if (isSuspended(context, packageName)) dpm(context).setPackagesSuspended(admin(context), arrayOf(packageName), false)
            if (dpm(context).isUninstallBlocked(admin(context), packageName)) setUninstallBlocked(context, packageName, false)
        }
        prefs(context).edit().clear().commit()
    }

    @Synchronized
    fun show(context: Context, packageName: String) {
        val info = packageInfo(context, packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES) ?: return
        // Approve before unhiding, because unhiding sends a "package added" broadcast that Guard checks.
        prefs(context).edit()
            .putStringSet(APPROVED, set(context, APPROVED) + fingerprint(info))
            .putStringSet(BLOCKED, blocked(context) - packageName)
            .putStringSet(QUARANTINED, quarantined(context) - packageName)
            .commit()
        setUninstallBlocked(context, packageName, false)
        setHidden(context, packageName, false)
    }

    // Blocked by name, so it's hidden again after a restart or a reinstall.
    @Synchronized
    fun hide(context: Context, packageName: String) {
        if (!isLocked(context) || packageName in essential(context)) return
        prefs(context).edit()
            .putStringSet(BLOCKED, blocked(context) + packageName)
            .putStringSet(SCHEDULED, set(context, SCHEDULED) - packageName)
            .putStringSet(QUARANTINED, quarantined(context) - packageName)
            .commit()
        // Before block(), which blocks uninstalling again for a built-in app.
        setSuspended(context, packageName, false)
        block(context, packageName)
    }

    // Synchronized so it can't run in the middle of Deactivate's unlock.
    @Synchronized
    fun newCode(context: Context, code: String) {
        if (isLocked(context)) Code.save(context, code)
    }

    // Only while locked, so a change confirmed during Deactivate can't outlast it.
    @Synchronized
    fun setRestriction(context: Context, restriction: String, on: Boolean) {
        if (!isLocked(context)) return
        if (on) dpm(context).addUserRestriction(admin(context), restriction) else dpm(context).clearUserRestriction(admin(context), restriction)
    }

    fun hasRestriction(context: Context, restriction: String) = dpm(context).getUserRestrictions(admin(context)).getBoolean(restriction)

    // In minutes, 0 for off.
    fun recoveryMinutes(context: Context) = prefs(context).getInt(RECOVERY_MINUTES, 0)

    // Leaves a finished wait alone: it opened the list, and only a new code should end it.
    @Synchronized
    fun setRecoveryMinutes(context: Context, minutes: Int) {
        if (isLocked(context)) prefs(context).edit().putInt(RECOVERY_MINUTES, minutes).commit()
    }

    // Apps blocked with the code aren't the schedule's to block or allow.
    fun scheduled(context: Context) = set(context, SCHEDULED) - blocked(context) - quarantined(context)

    fun schedulable(context: Context) = listed(context) - blocked(context) - quarantined(context)

    // Minutes after midnight.
    fun from(context: Context) = prefs(context).getInt(FROM, 22 * 60)

    fun until(context: Context) = prefs(context).getInt(UNTIL, 7 * 60)

    // One bit a day, Sunday the lowest.
    fun days(context: Context) = prefs(context).getInt(DAYS, 127)

    @Synchronized
    fun setSchedule(context: Context, from: Int, until: Int, days: Int) {
        if (!isLocked(context)) return
        prefs(context).edit().putInt(FROM, from).putInt(UNTIL, until).putInt(DAYS, days).commit()
        applySchedule(context)
    }

    // On unless turned off, so a schedule set up before the switch existed keeps blocking.
    fun isScheduleOn(context: Context) = prefs(context).getBoolean(SCHEDULE_ON, true)

    @Synchronized
    fun setScheduleOn(context: Context, on: Boolean) {
        if (!isLocked(context)) return
        prefs(context).edit().putBoolean(SCHEDULE_ON, on).commit()
        applySchedule(context)
    }

    @Synchronized
    fun setScheduled(context: Context, packageName: String, on: Boolean) {
        if (!isLocked(context)) return
        val wasScheduled = packageName in scheduled(context)
        val apps = set(context, SCHEDULED)
        prefs(context).edit().putStringSet(SCHEDULED, if (on) apps + packageName else apps - packageName).commit()
        if (!on && wasScheduled) setSuspended(context, packageName, false)
        applySchedule(context)
    }

    // Run by Guard every minute the screen is on, and after each change. The schedule suspends apps rather than
    // hiding them: hiding makes Android treat an app as uninstalled, so the launcher drops its icon and Android
    // deletes its alarms and notification settings. A suspended app stays on the home screen, greyed out.
    @Synchronized
    fun applySchedule(context: Context) {
        if (!isLocked(context)) return
        val apps = scheduled(context)
        if (apps.isEmpty()) return
        val blocking = isScheduleOn(context) && isScheduleBlocking(context)
        for (packageName in apps) {
            // Versions before 1.04 hid scheduled apps.
            if (isHidden(context, packageName)) setHidden(context, packageName, false)
            if (isSuspended(context, packageName) == blocking) continue
            if (blocking && packageName in essential(context)) continue
            setSuspended(context, packageName, blocking)
        }
    }

    // The same from and until means never. A block that runs past midnight belongs to the day it starts,
    // so after midnight it's yesterday that has to be a chosen day.
    private fun isScheduleBlocking(context: Context): Boolean {
        val calendar = Calendar.getInstance()
        val now = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val today = calendar.get(Calendar.DAY_OF_WEEK) - 1
        val from = from(context)
        val until = until(context)
        val overnight = from > until
        val inWindow = if (overnight) now >= from || now < until else now >= from && now < until
        val startDay = if (overnight && now < until) (today + 6) % 7 else today
        val dayChosen = (days(context) shr startDay) and 1 == 1
        return inWindow && dayChosen
    }

    // Installed apps that aren't part of the system, every browser and every app with a home-screen icon
    // (built-in ones too, so they can be blocked) and anything Beeswax hid.
    fun listed(context: Context): List<String> {
        val hidden = PackageManager.MATCH_UNINSTALLED_PACKAGES
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val builtIn = browsers(context, hidden) + activities(context, launcher, hidden).map { it.activityInfo.packageName } +
            blocked(context) + quarantined(context)
        val installed = context.packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(hidden.toLong()))
            .filter { (it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_INSTALLED != 0 }
        return installed.filter { isUserApp(context, it) || it.packageName in builtIn }.map { it.packageName } -
            essential(context)
    }

    fun isHidden(context: Context, packageName: String) = dpm(context).isApplicationHidden(admin(context), packageName)

    // Runs whenever Guard starts, which catches anything installed while it wasn't running. An app blocked
    // before it counted as essential, like Files before 1.04, is shown, since the list no longer offers it.
    @Synchronized
    fun enforce(context: Context) {
        if (!isLocked(context)) return
        val essential = essential(context)
        for (packageName in blocked(context)) if (packageName in essential) show(context, packageName) else block(context, packageName)
        for (info in userApps(context)) check(context, info)
    }

    // Runs for each "package added" broadcast, for new installs and updates alike.
    @Synchronized
    fun check(context: Context, packageName: String) {
        if (!isLocked(context)) return
        if (packageName in blocked(context)) {
            if (packageName !in essential(context)) block(context, packageName)
            return
        }
        // Android hides a reinstalled app again if a device owner hid it before, and it still has to be listed.
        val info = packageInfo(context, packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES) ?: return
        if (isUserApp(context, info)) check(context, info)
    }

    fun label(context: Context, packageName: String): CharSequence {
        val info = packageInfo(context, packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        return info?.applicationInfo?.loadLabel(context.packageManager) ?: packageName
    }

    fun icon(context: Context, packageName: String): Drawable? {
        val info = packageInfo(context, packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        return info?.applicationInfo?.loadIcon(context.packageManager)
    }

    // Hidden or uninstalled for this user too, so the list starts the same browsers switched off that Lock blocks.
    fun detectedBrowsers(context: Context) = browsers(context, PackageManager.MATCH_UNINSTALLED_PACKAGES)

    private fun check(context: Context, info: PackageInfo) {
        if (fingerprint(info) in set(context, APPROVED)) return
        // Built-in apps are only ever new in a user Beeswax created.
        if (isSystem(info.applicationInfo) && info.packageName in essential(context)) return
        prefs(context).edit().putStringSet(QUARANTINED, quarantined(context) + info.packageName).commit()
        setHidden(context, info.packageName, true)
    }

    // Uninstalling an allowed app and installing something else under the same name changes the time, so the
    // new app is quarantined. A built-in app's name can't be taken by another app, so its name is enough.
    private fun fingerprint(info: PackageInfo) =
        "${info.packageName}:${if (isSystem(info.applicationInfo)) 0 else info.firstInstallTime}"

    // Built-in apps that open web links without being browsers, like Carrier Communications for carrier
    // sign-in pages, are left alone.
    private fun browsers(context: Context, extraFlags: Int = 0): Set<String> {
        val main = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val callThemselvesBrowsers = activities(context, main, extraFlags)
        val openAnyLink = activities(context, web, extraFlags).filter { !isSystem(it.activityInfo.applicationInfo) }
        return (callThemselvesBrowsers + openAnyLink).map { it.activityInfo.packageName }.toSet() - context.packageName
    }

    // Disabled apps and components count too, since they could be switched on again in Settings after locking.
    private fun activities(context: Context, intent: Intent, extraFlags: Int): List<ResolveInfo> {
        val flags = PackageManager.MATCH_ALL or PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS or extraFlags
        return context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
    }

    private fun userApps(context: Context) = context.packageManager
        .getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        .filter { isUserApp(context, it) }

    // In the Owner profile built-in apps come only with OS updates, so they're never quarantined; browsers among
    // them are blocked by name. In a user Beeswax created, Owner can add a built-in app with Install available
    // apps, so there the built-in apps with a home-screen icon count too.
    private fun isUserApp(context: Context, info: PackageInfo): Boolean {
        if (info.packageName == context.packageName) return false
        if (!isSystem(info.applicationInfo)) return true
        if (!inUser(context)) return false
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(info.packageName)
        return activities(context, launcher, PackageManager.MATCH_UNINSTALLED_PACKAGES).isNotEmpty()
    }

    private fun inUser(context: Context) = !context.getSystemService(UserManager::class.java).isSystemUser

    private fun isSystem(info: ApplicationInfo?) = info == null || info.flags and ApplicationInfo.FLAG_SYSTEM != 0

    private fun isBuiltIn(context: Context, packageName: String) =
        packageInfo(context, packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)?.let { isSystem(it.applicationInfo) } == true

    private fun packageInfo(context: Context, packageName: String, flags: Int) = try {
        context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    // A new user would have its own browser and app store. In a user Beeswax created, Android allows no new
    // users anyway, and a Private Space (Android 15 and later) would get around the lock the same way.
    private fun restrictions(context: Context) = when {
        inUser(context) -> listOfNotNull(
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            if (Build.VERSION.SDK_INT >= 35) UserManager.DISALLOW_ADD_PRIVATE_PROFILE else null,
        )
        else -> listOf(UserManager.DISALLOW_ADD_USER, UserManager.DISALLOW_DEBUGGING_FEATURES)
    }

    // Never listed or blocked: Beeswax and enabled keyboards, so the code can always be typed, and the built-in
    // phone, SMS and home apps in use, Settings and the file picker other apps open, so calls, texts, the home
    // screen and attaching files keep working and factory reset stays reachable. Found by the job they do, not
    // by name, and read each time, so a newly chosen default counts at once. Only built-in, visible ones count,
    // so an installed app can't be made unblockable by choosing it as a default.
    private fun essential(context: Context): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val settings = Intent(Settings.ACTION_SETTINGS)
        val files = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        val only = { flag: Int -> PackageManager.ResolveInfoFlags.of(flag.toLong()) }
        // A phone without calls or texts may have no default for them, or throw. Guard runs this, so it mustn't.
        val defaults = listOfNotNull(
            runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull(),
            runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull(),
            context.packageManager.resolveActivity(home, only(PackageManager.MATCH_DEFAULT_ONLY))?.activityInfo?.packageName,
        ) + listOf(settings, files).flatMap { intent ->
            context.packageManager.queryIntentActivities(intent, only(PackageManager.MATCH_SYSTEM_ONLY)).map { it.activityInfo.packageName }
        }
        val builtIn = defaults.filter { packageInfo(context, it, 0)?.let { info -> isSystem(info.applicationInfo) } == true }
        val keyboards = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
        return (builtIn + keyboards + context.packageName).toSet()
    }

    // A built-in app's "Uninstall updates" in Settings brings back the copy from the system image, which isn't
    // hidden, so blocked built-in apps can't be uninstalled either.
    private fun block(context: Context, packageName: String) {
        setHidden(context, packageName, true)
        if (isBuiltIn(context, packageName)) setUninstallBlocked(context, packageName, true)
    }

    // The system image's copy isn't suspended either. Uninstall is blocked only once the suspension took:
    // Android refuses to suspend a few apps, such as an active device admin and the home and phone apps in use.
    private fun setSuspended(context: Context, packageName: String, suspended: Boolean) {
        val refused = dpm(context).setPackagesSuspended(admin(context), arrayOf(packageName), suspended)
        if (refused.isEmpty() && isBuiltIn(context, packageName)) setUninstallBlocked(context, packageName, suspended)
    }

    private fun isSuspended(context: Context, packageName: String) =
        runCatching { dpm(context).isPackageSuspended(admin(context), packageName) }.getOrDefault(false)

    private fun setUninstallBlocked(context: Context, packageName: String, blocked: Boolean) {
        dpm(context).setUninstallBlocked(admin(context), packageName, blocked)
    }

    private fun setHidden(context: Context, packageName: String, hidden: Boolean) {
        dpm(context).setApplicationHidden(admin(context), packageName, hidden)
    }

    private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)

    private fun admin(context: Context) = ComponentName(context, Receiver::class.java)

    private fun setGuard(context: Context, on: Boolean) = context.packageManager.setComponentEnabledSetting(
        ComponentName(context, Guard::class.java),
        if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
    )

    // The screen listens to these to refresh as soon as Guard quarantines an app.
    fun prefs(context: Context) = context.getSharedPreferences("lock", Context.MODE_PRIVATE)

    private fun set(context: Context, key: String): Set<String> = prefs(context).getStringSet(key, null)?.toSet() ?: emptySet()
}
