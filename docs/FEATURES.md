# More features

Choose when apps are blocked, manage another user, or set a way back in if you lose your code.

## Scheduled blocks

Block chosen apps during one time window on the days you pick, such as overnight. Outside that window, the apps are allowed again. Turn off Use scheduled blocks to stop blocking them without losing your times, days and apps.

A window that crosses midnight belongs to the day it starts. Apps you block in the main list stay blocked at all times and aren't listed on the Scheduled blocks page.

Lock date and time turns on automatic time and time zone and prevents manual clock changes. Without it, changing the clock can bypass the schedule.

The schedule does not change in safe mode. While blocked, scheduled apps stay on the home screen, greyed out, and can't be opened.

## User profiles

Create another user from More settings in the Owner profile. Beeswax manages the apps in that user separately. The new user has the same built-in apps as one added in Settings.

The new user starts as a copy of the Owner profile: the same lock code, blocked apps, scheduled blocks and Lost code recovery. After that, each user keeps its own. Changing one user does not change the others.

Private Space is not supported. To remove Beeswax from a user, open Beeswax in that user and tap Deactivate. You can't lock that user again afterwards; create a new one instead.

## Lost code recovery

Turn on Lost code recovery in More settings and choose a wait in days, hours and minutes. If you lose your lock code, tap Lost code to start the wait. When it ends, you can open the list without the code and set a new one.

Lost code recovery is off by default. You must turn it on with your code before you lose the code. Without it, a factory reset is the way back in.

Changing the clock does not shorten the wait. Restarts can make it longer, and time with the phone off does not count.

[More about recovering access](FAQ.md#what-if-i-forget-my-lock-code)

## Extra settings

In the Owner profile, open More settings with your lock code to change these options.

### Block factory reset

Prevent a factory reset from Android Settings. A factory reset from the phone's recovery screen remains possible.

### Lock private DNS

Keep the private DNS host you chose in Android Settings, such as a filtering service. Set a host before turning this on. An allowed VPN app may replace it.

### Allow developer options

Developer options are blocked by default. Allow them if you need debugging tools. With ADB enabled, someone with a computer could use safe mode to install a browser as a device admin, which Beeswax cannot block.

[Privacy and security details](PRIVACY.md)
