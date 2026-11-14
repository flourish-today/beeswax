// SPDX-License-Identifier: CC0-1.0
package org.getflourish.beeswax

import android.app.admin.DeviceAdminService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

// Enabled only while locked. Android keeps the DeviceAdminService of a device owner, or of the app in charge
// of a user, running and restarts it after a crash, so Guard hears every "package added" broadcast.
// Only the system can bind it (BIND_DEVICE_ADMIN), and its receiver isn't exported.
class Guard : DeviceAdminService() {
    // Android's clock broadcasts carry no app name: every minute the screen is on, when the screen turns on,
    // and when the time or time zone changes. Each one applies the schedule and counts a recovery wait.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val packageName = intent.data?.schemeSpecificPart
            if (packageName != null) Lock.check(context, packageName) else tick()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val added = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        registerReceiver(receiver, added, Context.RECEIVER_NOT_EXPORTED)
        val clock = IntentFilter(Intent.ACTION_TIME_TICK).apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        registerReceiver(receiver, clock, Context.RECEIVER_NOT_EXPORTED)
        Lock.enforce(this)
        tick()
    }

    private fun tick() {
        Lock.applySchedule(this)
        Code.waitLeft(this)
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }
}
