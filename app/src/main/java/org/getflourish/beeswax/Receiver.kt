// SPDX-License-Identifier: CC0-1.0
package org.getflourish.beeswax

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class Receiver : DeviceAdminReceiver() {
    // adb's set-device-owner sends this just before it makes Beeswax device owner, so the screen runs
    // Lock.activate again on its next refresh. In a user made by Create user, Beeswax is already that user's
    // profile owner, and Android sends this on the user's first start with Owner's copy (Lock.createUser) as extras.
    override fun onEnabled(context: Context, intent: Intent) {
        val manager = getManager(context)
        val profileOwner = manager.isProfileOwnerApp(context.packageName)
        // Android hands the copy over only once, so the user locks before anything else can fail.
        if (profileOwner) Lock.lockUser(context, intent)
        if (profileOwner || manager.isDeviceOwnerApp(context.packageName)) Lock.activate(context)
    }
}
