// SPDX-License-Identifier: CC0-1.0
package org.getflourish.beeswax

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

// Android needs a device admin receiver to make an app device owner. In the Owner profile Beeswax does all
// its work from its screen and Guard, and this receiver does nothing.
class Receiver : DeviceAdminReceiver() {
    // In a user made by Create user, Beeswax is in charge of that user (its profile owner), and Android calls
    // this on the user's first start, with the copy of the lock code as the intent's extras. The user is
    // locked from then on.
    override fun onEnabled(context: Context, intent: Intent) {
        if (!getManager(context).isProfileOwnerApp(context.packageName)) return
        val salt = intent.getStringExtra(Code.SALT) ?: return
        val hash = intent.getStringExtra(Code.HASH) ?: return
        Lock.lockUser(context, salt, hash)
    }
}
