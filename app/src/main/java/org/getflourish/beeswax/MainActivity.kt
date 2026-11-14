// SPDX-License-Identifier: CC0-1.0
package org.getflourish.beeswax

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.os.UserManager
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.text.DateFormatSymbols
import java.util.Calendar

class MainActivity : Activity() {
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName

    // Device owner in the Owner profile. In a user made by Create user, Beeswax is that user's profile owner
    // instead: the same lock and list, without Deactivate and the settings only a device owner has.
    private var deviceOwner = false

    // Set only by typing the right code. Leaving Beeswax keeps it, so apps can be installed and then allowed;
    // the screen turning off clears it. Kept in memory only, so it's also cleared when Android ends Beeswax.
    private var listOpen = false
    private val switches = mutableListOf<Switch>()

    // The screen shows one page at a time. More settings and Schedule are pages of the open list, and close
    // with it. About needs no code, and Licenses opens from it. Back returns to the page before.
    private enum class Page { MAIN, MORE, SCHEDULE, ABOUT, LICENSES }
    private var page = Page.MAIN
    private var backRegistered = false
    private val back = OnBackInvokedCallback { goBack() }

    // The switches changed before the first Lock: package name to on. Only a plan, kept in memory and applied
    // by Lock. Cleared once locked or no longer device owner.
    private val plan = mutableMapOf<String, Boolean>()

    // Refresh as soon as Guard quarantines an app, an app is uninstalled, or Beeswax becomes
    // device owner, not only on reopening.
    private val lockListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = closeList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_main)
        // Release builds block screenshots and screen recording, so the code can't be recorded as it's typed.
        // Test builds allow them, for testing.
        if (!Lock.isTestBuild(this)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dpm = getSystemService(DevicePolicyManager::class.java)
        admin = ComponentName(this, Receiver::class.java)
        // Android draws apps edge to edge, so keep the content clear of the system bars and keyboard.
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
            val types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            val bars = insets.getInsets(types)
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<TextView>(R.id.title).text = applicationInfo.loadLabel(packageManager)
        findViewById<Button>(R.id.deactivate).setOnClickListener { confirmDeactivate() }
        findViewById<Button>(R.id.lock).setOnClickListener {
            if (canLock()) showNewCode()
        }
        findViewById<Button>(R.id.open_list).setOnClickListener { openList() }
        findViewById<Button>(R.id.close_list).setOnClickListener {
            closeList()
            refresh()
        }
        findViewById<Button>(R.id.new_code).setOnClickListener { showNewCode() }
        findViewById<Button>(R.id.more_settings).setOnClickListener { open(Page.MORE) }
        findViewById<Button>(R.id.schedule_open).setOnClickListener { open(Page.SCHEDULE) }
        findViewById<Button>(R.id.create_user).setOnClickListener { showCreateUser() }
        findViewById<Button>(R.id.recovery_wait).setOnClickListener { showRecoveryWait() }
        findViewById<Button>(R.id.recovery).setOnClickListener { confirmRecovery() }
        for (id in listOf(R.id.about_from_list, R.id.about_from_code, R.id.about_from_plan, R.id.about_from_setup)) {
            findViewById<Button>(id).setOnClickListener { open(Page.ABOUT) }
        }
        findViewById<Button>(R.id.licenses_open).setOnClickListener { open(Page.LICENSES) }
        for (id in listOf(R.id.more_back, R.id.schedule_back, R.id.about_back, R.id.licenses_back)) {
            findViewById<Button>(id).setOnClickListener { goBack() }
        }
        About.setUp(this)
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        Lock.prefs(this).registerOnSharedPreferenceChangeListener(lockListener)
        registerReceiver(refreshReceiver, IntentFilter(DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        val removed = IntentFilter(Intent.ACTION_PACKAGE_REMOVED).apply { addDataScheme("package") }
        registerReceiver(refreshReceiver, removed, Context.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    override fun onPause() {
        Lock.prefs(this).unregisterOnSharedPreferenceChangeListener(lockListener)
        unregisterReceiver(refreshReceiver)
        super.onPause()
    }

    // The screen turning off, or Lock at the top of the open list, closes the list and clears the code.
    // Beeswax stays locked. Reopening Beeswax refreshes the screen.
    private fun closeList() {
        listOpen = false
        page = Page.MAIN
        findViewById<EditText>(R.id.code).text.clear()
    }

    private fun open(next: Page) {
        page = next
        findViewById<ScrollView>(R.id.root).scrollTo(0, 0)
        refresh()
    }

    private fun goBack() = open(if (page == Page.LICENSES) Page.ABOUT else Page.MAIN)

    private fun refresh() {
        deviceOwner = dpm.isDeviceOwnerApp(packageName)
        val owner = deviceOwner || dpm.isProfileOwnerApp(packageName)
        if (!owner) plan.clear()
        if (owner) {
            val backupsOn = try {
                dpm.setBackupServiceEnabled(admin, true)
                true
            } catch (e: Exception) {
                false
            }
            findViewById<View>(R.id.backups_failed).visibility = if (backupsOn) View.GONE else View.VISIBLE
            refreshLock()
        }
        if ((page == Page.MORE || page == Page.SCHEDULE) && !(owner && listOpen)) page = Page.MAIN
        val main = page == Page.MAIN
        findViewById<View>(R.id.status).visibility = shownIf(!owner && main)
        findViewById<View>(R.id.setup_controls).visibility = shownIf(!owner && main)
        findViewById<View>(R.id.owner_controls).visibility = shownIf(owner && main)
        findViewById<View>(R.id.more).visibility = shownIf(page == Page.MORE)
        findViewById<View>(R.id.schedule).visibility = shownIf(page == Page.SCHEDULE)
        findViewById<View>(R.id.about).visibility = shownIf(page == Page.ABOUT)
        findViewById<View>(R.id.licenses).visibility = shownIf(page == Page.LICENSES)
        if (page == Page.MORE) refreshMore()
        if (page == Page.SCHEDULE) refreshSchedule()
        if (!main != backRegistered) {
            if (!main) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back)
            else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(back)
            backRegistered = !main
        }
    }

    // Each switch shows Android's own state, so nothing new is saved. Changes need the open list, so the code.
    private fun refreshMore() {
        val wait = Lock.recoveryMinutes(this)
        findViewById<Button>(R.id.recovery_wait).text =
            getString(R.string.recovery_wait, if (wait == 0) getString(R.string.recovery_off) else duration(wait))
        findViewById<View>(R.id.owner_settings).visibility = shownIf(deviceOwner)
        if (!deviceOwner) return
        val reset = UserManager.DISALLOW_FACTORY_RESET
        val dns = UserManager.DISALLOW_CONFIG_PRIVATE_DNS
        val debugging = UserManager.DISALLOW_DEBUGGING_FEATURES
        setUpSwitch(R.id.block_factory_reset, Lock.hasRestriction(this, reset)) {
            Lock.setRestriction(this, reset, it)
            null
        }
        // Beeswax can't set a host itself: Android tests it from the calling app, which would need internet access.
        // So it locks the one set in Settings, and only if there is one.
        val host = if (dpm.getGlobalPrivateDnsMode(admin) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME) {
            dpm.getGlobalPrivateDnsHost(admin)
        } else {
            null
        }
        val dnsLocked = Lock.hasRestriction(this, dns)
        setUpSwitch(R.id.lock_private_dns, dnsLocked) { on ->
            if (on && host == null) return@setUpSwitch getString(R.string.private_dns_not_set)
            Lock.setRestriction(this, dns, on)
            null
        }
        val note = getString(R.string.lock_private_dns_note)
        findViewById<TextView>(R.id.private_dns_note).text =
            if (dnsLocked && host != null) note + "\n\n" + getString(R.string.private_dns_kept, host) else note
        setUpSwitch(R.id.allow_developer_options, !Lock.hasRestriction(this, debugging)) {
            Lock.setRestriction(this, debugging, !it)
            null
        }
    }

    // Two times, each set with Android's own time picker, and the days, then the apps not blocked with the
    // code, under the main list's headings: switched off, an app is blocked between the two times.
    private fun refreshSchedule() {
        // The schedule goes by the phone's clock, which only the device owner can lock, for every user.
        // Automatic time and time zone first, so the clock that's locked is the network's.
        val time = UserManager.DISALLOW_CONFIG_DATE_TIME
        val clockLocked = getSystemService(UserManager::class.java).hasUserRestriction(time)
        findViewById<View>(R.id.lock_date_time).visibility = shownIf(deviceOwner)
        findViewById<View>(R.id.schedule_clock_note).visibility = shownIf(!deviceOwner && !clockLocked)
        if (deviceOwner) setUpSwitch(R.id.lock_date_time, clockLocked) { on ->
            if (on) {
                dpm.setAutoTimeEnabled(admin, true)
                dpm.setAutoTimeZoneEnabled(admin, true)
            }
            Lock.setRestriction(this, time, on)
            null
        }
        val from = Lock.from(this)
        val until = Lock.until(this)
        val days = Lock.days(this)
        setUpTime(R.id.schedule_from, R.string.schedule_from, from) { Lock.setSchedule(this, it, until, days) }
        setUpTime(R.id.schedule_until, R.string.schedule_until, until) { Lock.setSchedule(this, from, it, days) }
        setUpDays(days) { Lock.setSchedule(this, from, until, it) }
        val list = findViewById<LinearLayout>(R.id.schedule_list)
        list.removeAllViews()
        val (blocked, allowed) = Lock.schedulable(this).sortedBy { Lock.label(this, it).toString().lowercase() }
            .partition { it in Lock.scheduled(this) }
        for ((heading, rows) in listOf(R.string.list_hidden to blocked, R.string.list_visible to allowed)) {
            addHeading(list, heading)
            for (packageName in rows) {
                addRow(list, packageName, packageName, rows === allowed) { on ->
                    if (listOpen) inBackground {
                        Lock.setScheduled(this, packageName, !on)
                        null
                    } else {
                        refresh()
                    }
                }
            }
        }
    }

    // A time is minutes after midnight, shown the way the phone shows times.
    private fun setUpTime(id: Int, label: Int, minutes: Int, change: (Int) -> Unit) {
        val time = Calendar.getInstance()
        time.set(Calendar.HOUR_OF_DAY, minutes / 60)
        time.set(Calendar.MINUTE, minutes % 60)
        val button = findViewById<Button>(id)
        button.text = getString(label, DateFormat.getTimeFormat(this).format(time.time))
        button.setOnClickListener {
            TimePickerDialog(this, { _, hour, minute ->
                if (listOpen) inBackground {
                    change(hour * 60 + minute)
                    null
                }
            }, minutes / 60, minutes % 60, DateFormat.is24HourFormat(this)).show()
        }
    }

    // The days are one bit each, Sunday the lowest, listed from the first day of the week where the phone is set.
    private fun setUpDays(days: Int, change: (Int) -> Unit) {
        val names = DateFormatSymbols.getInstance()
        val order = List(7) { (Calendar.getInstance().firstDayOfWeek - 1 + it) % 7 }
        val chosen = order.filter { days shr it and 1 == 1 }
        val button = findViewById<Button>(R.id.schedule_days)
        button.text = getString(R.string.schedule_days, when (chosen.size) {
            7 -> getString(R.string.schedule_every_day)
            0 -> getString(R.string.schedule_no_days)
            else -> chosen.joinToString(", ") { names.shortWeekdays[it + 1] }
        })
        button.setOnClickListener {
            val checked = BooleanArray(7) { order[it] in chosen }
            show(AlertDialog.Builder(this)
                .setTitle(R.string.days)
                .setMultiChoiceItems(Array(7) { names.weekdays[order[it] + 1] }, checked) { _, which, on -> checked[which] = on }
                .setPositiveButton(R.string.code_save) { _, _ ->
                    if (listOpen) inBackground {
                        change(order.filterIndexed { index, _ -> checked[index] }.sumOf { 1 shl it })
                        null
                    } else {
                        refresh()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null))
        }
    }

    // The change returns a message to show, or null.
    private fun setUpSwitch(id: Int, on: Boolean, change: (Boolean) -> String?) {
        val switch = findViewById<Switch>(id)
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = on
        switch.setOnCheckedChangeListener { _, checked -> if (listOpen) inBackground { change(checked) } else refresh() }
    }

    private fun refreshLock() {
        val locked = Lock.isLocked(this)
        if (locked) plan.clear() else listOpen = false
        findViewById<TextView>(R.id.lock_status).setText(if (locked) R.string.lock_on else R.string.lock_off)
        findViewById<View>(R.id.lock_status).visibility = shownIf(!listOpen)
        findViewById<View>(R.id.lock_test_note).visibility = shownIf(Lock.isTestBuild(this))
        // Before the first Lock, the list shows as the plan for it. While locked, only the code field shows
        // until the right code opens the list. Deactivate shows before the first Lock and in the open list.
        // About sits beside each of them.
        findViewById<View>(R.id.close_list_row).visibility = shownIf(listOpen)
        findViewById<View>(R.id.lock_row).visibility = shownIf(!locked)
        findViewById<View>(R.id.code).visibility = shownIf(locked && !listOpen)
        findViewById<View>(R.id.open_list_row).visibility = shownIf(locked && !listOpen)
        findViewById<View>(R.id.new_code).visibility = shownIf(listOpen)
        findViewById<View>(R.id.more_settings).visibility = shownIf(listOpen)
        findViewById<View>(R.id.schedule_open).visibility = shownIf(listOpen)
        // A user made by Create user has no Deactivate: deleting the user in the Owner profile is its way out.
        findViewById<View>(R.id.deactivate).visibility = shownIf(deviceOwner && (!locked || listOpen))
        refreshRecovery(locked && !listOpen && Lock.recoveryMinutes(this) > 0)
        val list = findViewById<LinearLayout>(R.id.app_list)
        list.removeAllViews()
        switches.clear()
        if (locked && !listOpen) return
        val browsers = Lock.detectedBrowsers(this)
        val scheduled = if (locked) Lock.scheduled(this) else emptySet()
        val apps = Lock.listed(this).sortedBy { Lock.label(this, it).toString().lowercase() }.map { it to (it in browsers) }
        // Locked, a switch is on while the app is visible, or hidden only by the schedule. Before the first
        // Lock it shows the plan, and browsers start off.
        val (visible, hidden) = apps.partition { (packageName, web) ->
            if (locked) packageName in scheduled || !Lock.isHidden(this, packageName) else plan[packageName] ?: !web
        }
        for ((heading, rows) in listOf(R.string.list_hidden to hidden, R.string.list_visible to visible)) {
            addHeading(list, heading)
            for ((packageName, web) in rows) {
                val details = listOfNotNull(
                    packageName,
                    if (web) getString(R.string.app_opens_web) else null,
                    if (packageName in scheduled) getString(R.string.app_scheduled) else null,
                ).joinToString("\n")
                addRow(list, packageName, details, rows === visible) { on ->
                    when {
                        !Lock.isLocked(this) -> {
                            plan[packageName] = on
                            refresh()
                        }
                        !listOpen -> refresh()
                        else -> inBackground {
                            if (on) Lock.show(this, packageName) else Lock.hide(this, packageName)
                            null
                        }
                    }
                }
            }
        }
    }

    // On the locked screen, only when the recovery wait is turned on: Lost code starts the wait, and while
    // it runs the time left shows with a way to cancel it.
    private fun refreshRecovery(shown: Boolean) {
        findViewById<View>(R.id.recovery_row).visibility = shownIf(shown)
        if (!shown) return
        val left = Code.waitLeft(this)
        val status = findViewById<TextView>(R.id.recovery_status)
        status.visibility = shownIf(left != null)
        if (left == 0L) {
            status.setText(R.string.recovery_done)
        } else if (left != null) {
            status.text = getString(R.string.recovery_left, duration(((left + 59_999) / 60_000).toInt()))
        }
        findViewById<Button>(R.id.recovery).setText(if (left == null) R.string.lost_code else R.string.recovery_cancel)
    }

    private fun confirmRecovery() {
        val wait = Lock.recoveryMinutes(this)
        val running = Code.waitLeft(this) != null
        val builder = AlertDialog.Builder(this)
        if (running) {
            builder.setTitle(R.string.recovery_cancel_title)
        } else {
            builder.setTitle(R.string.recovery_start_title).setMessage(getString(R.string.recovery_start_message, duration(wait)))
        }
        show(builder
            .setPositiveButton(if (running) R.string.recovery_cancel else R.string.recovery_start) { _, _ ->
                if (running) Code.cancelWait(this)
                else if (Lock.isLocked(this) && Lock.recoveryMinutes(this) == wait && wait > 0) Code.startWait(this, wait)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null))
    }

    private fun duration(minutes: Int) = getString(R.string.duration, minutes / 1440, minutes / 60 % 24, minutes % 60)

    // In More settings, so with the code. Days, hours and minutes, each a number field under its name.
    private fun showRecoveryWait() {
        val wait = Lock.recoveryMinutes(this)
        val row = LinearLayout(this)
        row.setPadding(dp(24), 0, dp(24), 0)
        val fields = listOf(R.string.days to wait / 1440, R.string.hours to wait / 60 % 24, R.string.minutes to wait % 60).map { (name, value) ->
            val label = TextView(this)
            label.setText(name)
            val field = EditText(this)
            field.inputType = InputType.TYPE_CLASS_NUMBER
            if (value > 0) field.setText(value.toString())
            val column = LinearLayout(this)
            column.orientation = LinearLayout.VERTICAL
            column.addView(label)
            column.addView(field)
            row.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            field
        }
        show(AlertDialog.Builder(this)
            .setTitle(R.string.recovery_wait_title)
            .setMessage(R.string.recovery_wait_message)
            .setView(row)
            .setPositiveButton(R.string.code_save) { _, _ ->
                val (days, hours, minutes) = fields.map { it.text.toString().toIntOrNull()?.coerceAtMost(999) ?: 0 }
                if (listOpen) inBackground {
                    Lock.setRecoveryMinutes(this, (days * 24 + hours) * 60 + minutes)
                    null
                } else {
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null))
    }

    // In More settings, so with the code. The new user shows up in Settings → System → Users.
    private fun showCreateUser() {
        val field = EditText(this)
        field.setHint(R.string.user_name)
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        val frame = FrameLayout(this)
        frame.setPadding(dp(24), 0, dp(24), 0)
        frame.addView(field)
        show(AlertDialog.Builder(this)
            .setTitle(R.string.create_user)
            .setMessage(R.string.create_user_message)
            .setView(frame)
            .setPositiveButton(R.string.create_user) { _, _ ->
                val name = field.text.toString().trim()
                if (listOpen && deviceOwner && name.isNotEmpty()) inBackground {
                    Lock.createUser(this, name)
                    getString(R.string.user_created)
                } else {
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null))
    }

    private fun shownIf(shown: Boolean) = if (shown) View.VISIBLE else View.GONE

    private fun addHeading(list: LinearLayout, text: Int) {
        val heading = TextView(this)
        heading.setText(text)
        heading.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
        heading.setPadding(0, dp(24), 0, dp(8))
        list.addView(heading)
    }

    // One app: its icon, its name with its details below (its package name, and any notes), and its switch.
    private fun addRow(list: LinearLayout, packageName: String, text: String, on: Boolean, change: (Boolean) -> Unit) {
        val icon = ImageView(this)
        icon.setImageDrawable(Lock.icon(this, packageName))
        val name = TextView(this)
        name.text = Lock.label(this, packageName)
        val details = TextView(this)
        details.text = text
        details.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.addView(name)
        column.addView(details)
        val switch = Switch(this)
        switch.isChecked = on
        switch.contentDescription = name.text
        switch.setOnCheckedChangeListener { _, checked -> change(checked) }
        switches.add(switch)
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, dp(8))
        row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(16) })
        row.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(switch)
        list.addView(row)
    }

    // The right code opens the list. Checking it is one hash, quick enough for the main thread.
    private fun openList() {
        val field = findViewById<EditText>(R.id.code)
        val right = Code.matches(this, field.text.toString())
        // The right code ends a recovery wait. A wait that has run out opens the list without the code.
        if (right) Code.cancelWait(this)
        if (right || (Lock.recoveryMinutes(this) > 0 && Code.waitLeft(this) == 0L)) {
            listOpen = true
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
        } else {
            Toast.makeText(this, R.string.code_wrong, Toast.LENGTH_LONG).show()
        }
        field.text.clear()
        refresh()
    }

    // Other users have their own browsers. Other device admin apps are allowed: they're chosen at setup, and
    // one switched off stays on the blocked list, hidden once it's no longer a device admin.
    private fun canLock(): Boolean {
        if (!deviceOwner || dpm.getSecondaryUsers(admin).isEmpty()) return true
        show(AlertDialog.Builder(this).setMessage(R.string.lock_other_users).setPositiveButton(android.R.string.ok, null))
        return false
    }

    // A new code is shown, then has to be typed back before anything locks or it's saved, so it's known to be
    // saved. If the typed code doesn't match, the same code is shown again so the saved copy can be checked.
    private fun showNewCode(code: String = Code.create()) {
        val view = TextView(this)
        view.text = code
        view.typeface = Typeface.MONOSPACE
        view.textSize = 24f
        view.setTextIsSelectable(true)
        view.setPadding(dp(24), dp(12), dp(24), 0)
        // From New code in the list, it also says the current code stays until the new one is confirmed, and,
        // in a user or with users Beeswax created, that each one's code is its own.
        val users = !deviceOwner || dpm.getSecondaryUsers(admin).isNotEmpty()
        val message = listOfNotNull(
            if (Lock.isLocked(this)) getString(R.string.new_code_message) else null,
            if (Lock.isLocked(this) && users) getString(R.string.new_code_users) else null,
            getString(R.string.code_message),
        ).joinToString("\n\n")
        show(AlertDialog.Builder(this)
            .setTitle(R.string.code_title)
            .setMessage(message)
            .setView(view)
            .setPositiveButton(R.string.code_saved) { _, _ -> checkNewCode(code) }
            .setNegativeButton(android.R.string.cancel, null))
    }

    // Each dialog checks again when confirmed, since it may have been left open while another copy locked,
    // or the list closed.
    private fun checkNewCode(code: String) {
        val fromList = Lock.isLocked(this)
        val field = CodeField(this)
        field.typeface = Typeface.MONOSPACE
        field.setHint(R.string.code_hint)
        field.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        field.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val frame = FrameLayout(this)
        frame.setPadding(dp(24), 0, dp(24), 0)
        frame.addView(field)
        show(AlertDialog.Builder(this)
            .setTitle(R.string.code_check_title)
            .setView(frame)
            .setPositiveButton(if (fromList) R.string.code_save else R.string.lock) { _, _ ->
                if (Lock.isLocked(this) != fromList || (fromList && !listOpen)) {
                    refresh()
                } else if (Code.same(code, field.text.toString())) {
                    // A copy, so the background step gets the plan as it is now.
                    val plan = plan.toMap()
                    inBackground {
                        if (fromList) Lock.newCode(this, code) else Lock.lock(this, code, plan)
                        null
                    }
                } else {
                    Toast.makeText(this, R.string.code_mismatch, Toast.LENGTH_LONG).show()
                    showNewCode(code)
                }
            }
            .setNegativeButton(android.R.string.cancel, null))
    }

    // Dialogs have their own windows, so each needs the screen's block on screenshots too.
    fun show(builder: AlertDialog.Builder) {
        val dialog = builder.create()
        if (!Lock.isTestBuild(this)) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // Locking goes through every app, so it and the list's actions run off the main thread.
    // The task returns a message to show, or null.
    private fun inBackground(task: () -> String?) {
        setButtonsEnabled(false)
        Thread {
            val message = try {
                task()
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                findViewById<EditText>(R.id.code).text.clear()
                setButtonsEnabled(true)
                if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        findViewById<View>(R.id.lock).isEnabled = enabled
        findViewById<View>(R.id.new_code).isEnabled = enabled
        for (switch in switches) switch.isEnabled = enabled
    }

    @Suppress("DEPRECATION") // Still the only way for a device owner to remove itself.
    private fun confirmDeactivate() {
        show(AlertDialog.Builder(this)
            .setTitle(R.string.deactivate_confirm_title)
            .setMessage(R.string.deactivate_confirm_message)
            .setPositiveButton(R.string.deactivate) { _, _ ->
                if (Lock.isLocked(this) && !listOpen) return@setPositiveButton refresh()
                try {
                    // Only the device owner can unhide apps, so make sure nothing is left hidden
                    // before giving that up.
                    Lock.unlock(this)
                    Code.clear(this)
                    dpm.clearDeviceOwnerApp(packageName)
                } catch (e: Exception) {
                    val error = e.message ?: e.javaClass.simpleName
                    Toast.makeText(this, getString(R.string.deactivate_failed, error), Toast.LENGTH_LONG).show()
                }
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null))
    }
}
