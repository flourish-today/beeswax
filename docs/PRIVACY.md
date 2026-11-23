# Privacy and security

Beeswax collects and shares no personal data. It has no telemetry, one permission, and no network access. It has reproducible builds so you can verify the source code is what runs on your device.

Beeswax uses its Device Owner capability responsibly by only using these few Device Management APIs it needs to function:

## Device management APIs used

| What it does | Android API | When |
|---|---|---|
| Blocks adding unmanaged users through Settings | `DevicePolicyManager.addUserRestriction` with `DISALLOW_ADD_USER` | When you lock |
| Blocks debugging features, so ADB can't be used to get around the lock | `DevicePolicyManager.addUserRestriction` with `DISALLOW_DEBUGGING_FEATURES` | As soon as Beeswax is activated, when you lock, and when you turn off Allow developer options |
| Allows debugging features while locked | `DevicePolicyManager.clearUserRestriction` with `DISALLOW_DEBUGGING_FEATURES` | When you turn on Allow developer options |
| Blocks factory reset from Settings | `DevicePolicyManager.addUserRestriction` and `clearUserRestriction` with `DISALLOW_FACTORY_RESET` | When you turn Block factory reset on or off |
| Locks private DNS | `DevicePolicyManager.addUserRestriction` and `clearUserRestriction` with `DISALLOW_CONFIG_PRIVATE_DNS` | When you turn Lock private DNS on or off |
| Creates a user managed by Beeswax, with all built-in apps, and a copy of the current lock code's salt and hash, the names of blocked and allowed apps, the schedule and the Lost code recovery wait | `DevicePolicyManager.createAndManageUser` | With the code, from More settings in the Owner profile |
| Turns on automatic time and time zone, and prevents clock changes | `DevicePolicyManager.setAutoTimeEnabled`, `setAutoTimeZoneEnabled`, `addUserRestriction` and `clearUserRestriction` with `DISALLOW_CONFIG_DATE_TIME` | When you change Lock date and time in Schedule in the Owner profile |
| Blocks Private Space in a managed user on Android 15 and newer | `DevicePolicyManager.addUserRestriction` with `DISALLOW_ADD_PRIVATE_PROFILE` | When the user first starts |
| Blocks and allows apps | `DevicePolicyManager.setApplicationHidden` | Blocks the apps switched off in the list (browsers by default) when you lock, an app installed while locked, and an app you switch off in the list. Blocks them again whenever Guard starts, for example after a restart. Allows an app when you switch it on in the list, and every app when you tap Deactivate |
| Pauses scheduled apps, which stay on the home screen greyed out and can't be opened | `DevicePolicyManager.setPackagesSuspended` | At the start of the schedule's window, and lifted at its end, when you take an app off the schedule, and for every app when you tap Deactivate |
| Stops a blocked or paused built-in app from being uninstalled, so Settings' "Uninstall updates" can't bring back an unblocked copy | `DevicePolicyManager.setUninstallBlocked` | When a built-in app is blocked or paused by the schedule. Lifted when you allow it, at the end of the schedule's window, and for every app when you tap Deactivate |
| Turns Guard, Beeswax's background service that watches for new apps, on and off | `PackageManager.setComponentEnabledSetting` (Guard only) | On when you lock, off when you tap Deactivate |
| Allows adding new users again | `DevicePolicyManager.clearUserRestriction` with `DISALLOW_ADD_USER` | When you tap Deactivate |
| Allows debugging features, factory reset, private DNS and clock changes again | `DevicePolicyManager.clearUserRestriction` with `DISALLOW_DEBUGGING_FEATURES`, `DISALLOW_FACTORY_RESET`, `DISALLOW_CONFIG_PRIVATE_DNS` and `DISALLOW_CONFIG_DATE_TIME` | When you tap Deactivate in the Owner profile |
| Allows debugging features and Private Space again in a managed user | `DevicePolicyManager.clearUserRestriction` with `DISALLOW_DEBUGGING_FEATURES` and `DISALLOW_ADD_PRIVATE_PROFILE` | When you tap Deactivate in that user |
| Turns the backup service on, which Android turns off when a device owner is set | `DevicePolicyManager.setBackupServiceEnabled` | When Beeswax is activated, and whenever Beeswax's screen opens or changes |
| Removes itself as device owner | `DevicePolicyManager.clearDeviceOwnerApp` | When you tap Deactivate in the Owner profile |
| Removes itself from a managed user | `DevicePolicyManager.clearProfileOwner` | When you tap Deactivate in that user |

## APIs used to read the phone

These only read, and change nothing. Before you lock, Beeswax uses only these to show its list, and it applies your switches only when you tap Lock.

| What it does | Android API | When |
|---|---|---|
| Lists the apps on your phone | `PackageManager.getInstalledPackages`, `PackageManager.getPackageInfo` | When Beeswax shows its list, when you lock, when you block or allow an app, when an app is installed while locked, when Guard starts, and when you tap Deactivate |
| Finds browsers (apps that call themselves a browser, and apps that can open any web link) and apps with a home screen icon, so built-in ones are listed too | `PackageManager.queryIntentActivities` | When Beeswax shows its list, and when you lock |
| Shows each app's name and icon | `ApplicationInfo.loadLabel`, `ApplicationInfo.loadIcon` | When Beeswax shows its list |
| Finds the keyboards you've turned on, so they're never listed or blocked | `InputMethodManager.getEnabledInputMethodList` | When Beeswax shows its list, when you lock, when you block an app, when a blocked app is installed or updated while locked, and when Guard starts |
| Finds Settings and the built-in phone, messaging and home screen apps in use, so they're never listed or blocked | `TelecomManager.getDefaultDialerPackage`, `Telephony.Sms.getDefaultSmsPackage`, `PackageManager.resolveActivity`, `PackageManager.queryIntentActivities` | When Beeswax shows its list, when you lock, when you block an app, when a blocked app is installed or updated while locked, and when Guard starts |
| Shows which apps are blocked, and finds every blocked app to allow it again | `DevicePolicyManager.isApplicationHidden` | When Beeswax shows its list while locked, and when you tap Deactivate |
| Shows More settings' switches as Android has them | `DevicePolicyManager.getUserRestrictions` | When More settings shows |
| Reads the private DNS set in Settings, so Lock private DNS can show it and refuse when none is set | `DevicePolicyManager.getGlobalPrivateDnsMode`, `DevicePolicyManager.getGlobalPrivateDnsHost` | When More settings shows |
| Finds every app whose uninstall Beeswax blocked, to lift it | `DevicePolicyManager.isUninstallBlocked` | When you tap Deactivate |
| Finds which scheduled apps are paused, and every paused app to lift it | `DevicePolicyManager.isPackageSuspended` | Every minute while the screen is on and locked, and when you tap Deactivate |
| Checks for other users before the first Lock | `DevicePolicyManager.getSecondaryUsers` | When you tap Lock, and again when you confirm the lock code |
| Checks that Beeswax is the device owner | `DevicePolicyManager.isDeviceOwnerApp`, `isProfileOwnerApp` | Whenever Beeswax's screen opens or changes |

## Risks to using Beeswax

- Beeswax is a device-owner app, which gives it powers far beyond a normal app. Beeswax uses only the few listed above, but a future update signed with the same key could, for example, give Beeswax the ability to wipe your device.
- If the lock code is lost while Beeswax is locked, unblocking apps or allowing new apps requires a factory reset, unless Recovery wait was enabled beforehand. A recovery wait deliberately allows access without the code once the wait ends.
- By using Beeswax, you're using Android in a non-standard way. Beeswax blocks browsers by default, including the built-in browser (Vanadium on GrapheneOS). GrapheneOS recognizes device owners as the standard way to manage devices, and ADB is currently the only way to set one up. However, GrapheneOS advises against apps that use these APIs to "disallow a bunch of functionality." [1]

## Threat model

Beeswax has little attack surface. It has no network access and one permission, `QUERY_ALL_PACKAGES`. Its only component other apps can start is its launcher screen. Its code is Kotlin, which is memory safe. The only outside input it reads is other apps' names, icons and links they declare, which it uses to list apps and find browsers.

That leaves two threats: getting locked out of your own phone, and getting around the lock.

Anything you can do while the list is open with your code is out of scope, because with the code you can allow anything. Blocking an app you rely on, such as a VPN or a messenger, so that it stops getting updates, is your choice and also out of scope.

### Updates without installs

Android has no setting that allows app updates but blocks new installs. Blocking installs blocks updates too. So Beeswax leaves installs allowed and blocks each new app as soon as it's installed, until you allow it with your code. Updates to apps you already have install normally, from any app store. This keeps apps that are frequent targets for spyware, like messengers, up to date.

Android only installs an update signed by the same developer as the installed app. Beeswax recognizes an app by its package name and the time it was first installed, so uninstalling an allowed app and installing another one under the same name gets blocked.

### Getting locked out

Beeswax is designed so that a mistake or a lost code can't leave you without a working phone.

- A new lock code must be typed back before it's saved.
- Beeswax never blocks your enabled keyboards, so you can always type the code. It also never blocks Settings, itself, or the built-in phone, messaging and home screen apps you use.
- Android doesn't let Settings clear Beeswax's data, so the code can't be wiped by accident.
- If you lose the code, a recovery wait lets you back in once it ends, if you turned it on beforehand. Changing the clock doesn't shorten it.
- Without a recovery wait, a factory reset from the recovery screen always works, even with Block factory reset on. Until then, the apps you already allowed keep working and you can export your data.
- Each user created by Beeswax has its own code. If you lose it, delete that user from the Owner profile.

A bug in Beeswax that blocked one of these apps or broke the code check could still lock you out of new and blocked apps. Factory reset from the recovery screen doesn't depend on Beeswax, so it remains the way out.

### Getting around the lock

What Beeswax blocks:

- New apps, including ones installed in safe mode, which are blocked the next time the phone starts normally.
- Apps uninstalled and installed again under the same name.
- New users and Private Space, which would have their own browser and app store.
- "Uninstall updates" in Settings for a blocked built-in app, which would bring back an unblocked copy.
- ADB, through the debugging block, unless you allow developer options.
- Clock changes, if you turn on Lock date and time for a schedule.

Known ways around it:

- Browsers that don't declare that they open web links, such as Surf and Lite++, aren't detected. If they're installed before Lock, they're allowed like any other app.
- Apps with a built-in browser may open websites.
- Wi-Fi sign-in pages on hotel and café networks show web pages.
- An update to an allowed app can add new features, including a browser, because Beeswax lets updates through.
- An app can name itself anything, so check the package name shown under each app before allowing it.
- With developer options allowed, someone with a computer could start in safe mode and install a browser as a device admin, which Beeswax can't block.
- A factory reset removes Beeswax along with all your data.
- A recovery wait lets anyone with the phone in at the end of the wait, by design.

## Verifying a release

Use an [App Verifier](https://github.com/privacyguides/verified-apps-android)  with the package name and hash below. Beeswax has been [domain verified](https://github.com/privacyguides/verified-apps/issues/7308) and [submitted](https://github.com/privacyguides/verified-apps/issues/7307) to Privacy Guides' [verified-apps](https://github.com/privacyguides/verified-apps) database.

```
org.getflourish.beeswax
F5:37:A3:91:23:40:95:AC:42:53:85:D4:9E:B0:AA:43:C8:ED:84:4D:FC:84:15:F2:1C:C4:BF:63:52:86:34:54
```

## Reproducible builds

This confirms the released APK was built from the public source code. Your build will match the release in every file; only the signature differs. Being open source with reproducible builds doesn't mean a third party has checked that the code is safe or that the builds match.

You need:

-   JDK 21, for example [Temurin 21](https://adoptium.net/temurin/releases/?version=21). If you have more than one Java installed, set `JAVA_HOME` to the Java 21 folder. Check that `java -version` shows 21.
-   [Gradle 9.5.1](https://services.gradle.org/distributions/gradle-9.5.1-bin.zip), unzipped, with its `bin` folder on your `PATH` so the `gradle` command works. Package managers usually install a newer version, which won't match.
-   The Android SDK with platform 37.0 and build-tools 37.0.0, from Android Studio or Google's [command-line tools](https://developer.android.com/studio#command-line-tools-only). Set `ANDROID_HOME` to the SDK folder. With the command-line tools, install the parts with `sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-37.0" "build-tools;37.0.0"`.
    
1. Get the source for the release and build it:

   ```
   git clone https://github.com/flourish-today/beeswax.git
   cd beeswax
   git checkout v1.03
   gradle --no-daemon :app:assembleRelease -Pandroid.aapt2FromMavenOverride="$ANDROID_HOME/build-tools/37.0.0/aapt2"
   ```

2. Download `Beeswax-1.03.apk` from the [v1.03 release](https://github.com/flourish-today/beeswax/releases/tag/v1.03) into the `beeswax` folder.

3. Unpack both APKs and compare them:

   ```
   mkdir rel mine
   unzip -q Beeswax-1.03.apk -d rel
   unzip -q app/build/outputs/apk/release/app-release-unsigned.apk -d mine
   diff -rq rel mine && echo MATCH
   ```

If it prints `MATCH`, the release contains exactly what you built.

```
sha256sum app/build/outputs/apk/release/app-release-unsigned.apk
```

Please consider sharing the result in an issue no matter what result you got.

## Notes

[1] Relevant GrapheneOS posts about apps like Beeswax. They are not specific to Beeswax but device management apps in general.

[Tweet 1 (August 2021)](https://xxcancel.com/GrapheneOS/status/1422158415728627715)

> Device management APIs do work well already. You just need to grant those privileges to a device management app. It's possible Play supports it. We have no setup wizard integrating for setting a device policy manager as a device owner but that isn't an issue for work profiles.

[Tweet 2 (February 2026)](https://xxcancel.com/GrapheneOS/status/2023608038637146442)

> MDM apps are still sandboxed and can only do what's allowed by specific device management apps. ADB shell gives far more access than the device policy owner privileges. Just don't install and activate an app using APIs which mainly exist to disallow a bunch of functionality.
