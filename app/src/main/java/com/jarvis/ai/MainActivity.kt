package com.jarvis.ai

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), JarvisCore.Listener {

    companion object {
        const val ACTION_CALL_SETUP = "com.jarvis.ai.CALL_SETUP"
        const val ACTION_BRIEFING = "com.jarvis.ai.BRIEFING"
    }

    private lateinit var core: CoreView
    private lateinit var wave: WaveView
    private lateinit var statusText: TextView
    private lateinit var partialText: TextView
    private lateinit var modeChip: TextView
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var batteryText: TextView
    private lateinit var chargeText: TextView
    private lateinit var controlText: TextView
    private lateinit var accBanner: TextView
    private lateinit var chatList: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var recentRow: LinearLayout
    private lateinit var recentScroll: HorizontalScrollView
    private lateinit var input: EditText
    private lateinit var alwaysSwitch: MaterialSwitch
    private lateinit var quickRow: LinearLayout

    private var torchBtn: View? = null
    private var awayBtn: View? = null
    private lateinit var todayList: LinearLayout
    private lateinit var todayTitle: TextView

    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val missing = mutableListOf(Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CONTACTS)
        if (Prefs.awayMode == "text") missing += Manifest.permission.SEND_SMS
        permLauncher.launch(missing.filter { p -> !PhoneControl.has(this, p) }.toTypedArray().ifEmpty { arrayOf(Manifest.permission.READ_CONTACTS) })
        refreshAll()
    }

    /** Asks Android to make JARVIS the call screening app (for the call assistant). */
    fun requestCallSetup() {
        if (Build.VERSION.SDK_INT < 29) return
        val rm = getSystemService(android.app.role.RoleManager::class.java) ?: return
        if (rm.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_SCREENING) && !rm.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)) {
            roleLauncher.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING))
        } else {
            permLauncher.launch(arrayOf(Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CONTACTS))
        }
    }
    private var pendingListen = false
    private var pendingAlways = false
    private var suppressSwitch = false
    private val ui = Handler(Looper.getMainLooper())

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingListen) { pendingListen = false; if (hasMic()) JarvisCore.listenOnce() }
        if (pendingAlways) {
            pendingAlways = false
            if (hasMic()) startAlways() else setSwitch(false)
        }
    }

    private val clockTick = object : Runnable {
        override fun run() {
            clockText.text = SimpleDateFormat("h:mm a", Locale.US).format(Date())
            dateText.text = SimpleDateFormat("EEEE, MMM d", Locale.US).format(Date())
            updateMode()
            ui.postDelayed(this, 20_000)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val pct = if (level >= 0) level * 100 / scale else PhoneControl.batteryLevel(c)
            batteryText.text = "$pct%"
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            chargeText.text = if (charging) "Charging" else "Battery"
            batteryText.setTextColor(ContextCompat.getColor(c, if (pct <= 15 && !charging) R.color.danger else R.color.text))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JarvisCore.init(this)
        setContentView(R.layout.activity_main)

        core = findViewById(R.id.core)
        wave = findViewById(R.id.wave)
        statusText = findViewById(R.id.statusText)
        partialText = findViewById(R.id.partialText)
        modeChip = findViewById(R.id.modeChip)
        clockText = findViewById(R.id.clockText)
        dateText = findViewById(R.id.dateText)
        batteryText = findViewById(R.id.batteryText)
        chargeText = findViewById(R.id.chargeText)
        controlText = findViewById(R.id.controlText)
        accBanner = findViewById(R.id.accBanner)
        chatList = findViewById(R.id.chatList)
        chatScroll = findViewById(R.id.chatScroll)
        recentRow = findViewById(R.id.recentRow)
        recentScroll = findViewById(R.id.recentScroll)
        input = findViewById(R.id.input)
        alwaysSwitch = findViewById(R.id.alwaysSwitch)
        quickRow = findViewById(R.id.quickRow)
        todayList = findViewById(R.id.todayList)
        todayTitle = findViewById(R.id.todayTitle)
        findViewById<TextView>(R.id.todayAdd).setOnClickListener { Dialogs.addTask(this) { renderToday() } }

        core.setOnClickListener { talk() }
        findViewById<ImageButton>(R.id.micBtn).setOnClickListener { talk() }
        findViewById<ImageButton>(R.id.sendBtn).setOnClickListener { sendTyped() }
        findViewById<ImageButton>(R.id.menuBtn).setOnClickListener { showMenu(it) }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
        }
        accBanner.setOnClickListener { Dialogs.phoneControlHelp(this) }
        modeChip.setOnClickListener {
            Prefs.mode = when (Prefs.mode) { "hybrid" -> "online"; "online" -> "offline"; else -> "hybrid" }
            updateMode()
            toastLine(when (Prefs.mode) {
                "online" -> "Online mode: the cloud AI answers everything it can."
                "offline" -> "Offline mode: nothing leaves this phone."
                else -> "Hybrid mode: cloud when available, offline when not."
            })
        }

        alwaysSwitch.setOnCheckedChangeListener { _, on ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            if (on) {
                if (hasMic()) startAlways() else { pendingAlways = true; askPermissions() }
            } else {
                ListenService.stop(this)
                JarvisCore.setContinuous(false)
            }
        }

        buildQuickControls()

        if (!getPreferences(MODE_PRIVATE).getBoolean("onboarded", false)) {
            Dialogs.onboarding(this) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("onboarded", true).apply()
                askPermissions()
            }
        }
        handleAssistIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAssistIntent(intent)
    }

    private fun handleAssistIntent(i: Intent?) {
        val a = i?.action ?: return
        if (a == Intent.ACTION_ASSIST || a == Intent.ACTION_VOICE_COMMAND) {
            ui.postDelayed({ talk() }, 400)
        }
        if (a == ACTION_CALL_SETUP) ui.postDelayed({ requestCallSetup() }, 300)
        if (a == ACTION_BRIEFING) ui.postDelayed({ JarvisCore.briefing() }, 500)
    }

    override fun onStart() {
        super.onStart()
        renderChat()
        renderRecents()
        JarvisCore.addListener(this)
        onState(JarvisCore.state)
        setSwitch(ListenService.running)
        alwaysSwitch.text = "Always ready: say “Hey ${Prefs.wakeWord}”"
        ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ui.post(clockTick)
    }

    override fun onResume() {
        super.onResume()
        updateControlStatus()
        updateMode()
        refreshQuickStates()
        renderToday()
    }

    override fun onStop() {
        JarvisCore.removeListener(this)
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) { }
        ui.removeCallbacks(clockTick)
        super.onStop()
    }

    // ---------------- Actions ----------------

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun askPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CALENDAR
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())
    }

    fun requestOne(perm: String) = permLauncher.launch(arrayOf(perm))

    private fun talk() {
        when {
            JarvisCore.state == JarvisCore.State.SPEAKING -> { JarvisCore.stopSpeaking(); if (hasMic()) JarvisCore.listenOnce() }
            JarvisCore.state == JarvisCore.State.LISTENING && !JarvisCore.continuous -> JarvisCore.cancelListening()
            hasMic() -> JarvisCore.listenOnce()
            else -> { pendingListen = true; askPermissions() }
        }
    }

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        JarvisCore.handleText(text)
    }

    private fun startAlways() {
        if (!hasMic()) { setSwitch(false); return }
        ListenService.start(this)
        ui.postDelayed({
            if (!ListenService.running) {
                setSwitch(false)
                toastLine("Android didn't allow background listening. Make sure the microphone permission is on.")
            }
        }, 1500)
    }

    private fun setSwitch(on: Boolean) {
        suppressSwitch = true
        alwaysSwitch.isChecked = on
        suppressSwitch = false
    }

    private fun toastLine(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Settings")
        pm.menu.add(0, 2, 1, "Memory")
        pm.menu.add(0, 3, 2, "Routines")
        pm.menu.add(0, 4, 3, "Permissions")
        pm.menu.add(0, 5, 4, if (Prefs.privacyMode) "Privacy mode: ON" else "Privacy mode: OFF")
        pm.menu.add(0, 6, 5, "Clear conversation")
        pm.menu.add(0, 7, 6, "Command guide")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> Dialogs.settings(this) { alwaysSwitch.text = "Always ready: say “Hey ${Prefs.wakeWord}”"; updateMode() }
                2 -> Dialogs.memory(this)
                3 -> Dialogs.routines(this)
                4 -> Dialogs.permissions(this)
                5 -> {
                    Prefs.privacyMode = !Prefs.privacyMode
                    toastLine(if (Prefs.privacyMode) "Privacy mode on. Conversation won't be saved." else "Privacy mode off.")
                    renderRecents()
                }
                6 -> { JarvisCore.clearConversation(); renderChat() }
                7 -> Dialogs.guide(this)
            }
            true
        }
        pm.show()
    }

    // ---------------- Quick controls ----------------

    private fun buildQuickControls() {
        data class Q(val icon: Int, val label: String, val action: () -> Unit)
        val items = listOf(
            Q(R.drawable.ic_torch, "Torch") { JarvisCore.quick(if (PhoneControl.torchOn) "flashlight off" else "flashlight on"); ui.postDelayed({ refreshQuickStates() }, 300) },
            Q(R.drawable.ic_vol_down, "Vol −") { JarvisCore.quick("volume down") },
            Q(R.drawable.ic_vol_up, "Vol +") { JarvisCore.quick("volume up") },
            Q(R.drawable.ic_eye, "Vision") { startActivity(Intent(this, VisionActivity::class.java)) },
            Q(R.drawable.ic_phone, "Away") { JarvisCore.handleText(if (Prefs.awayMode == "off") "away mode on" else "i'm back"); ui.postDelayed({ refreshQuickStates(); updateMode() }, 400) },
            Q(R.drawable.ic_bolt, "Routines") { Dialogs.runRoutinePicker(this) }
        )
        val dp = resources.displayMetrics.density
        items.forEachIndexed { idx, q ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_quick)
                setPadding(0, (8 * dp).toInt(), 0, (6 * dp).toInt())
                isClickable = true
                isFocusable = true
                contentDescription = q.label
                setOnClickListener { q.action() }
            }
            val icon = ImageView(this).apply {
                setImageResource(q.icon)
                setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.cyan))
            }
            cell.addView(icon, LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()))
            val label = TextView(this).apply {
                text = q.label
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                gravity = Gravity.CENTER
                maxLines = 1
            }
            cell.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (3 * dp).toInt() })
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (idx > 0) lp.marginStart = (6 * dp).toInt()
            quickRow.addView(cell, lp)
            if (idx == 0) torchBtn = cell
            if (idx == 4) awayBtn = cell
        }
    }

    private fun refreshQuickStates() {
        torchBtn?.isActivated = PhoneControl.torchOn
        awayBtn?.isActivated = Prefs.awayMode != "off"
    }

    // ---------------- Status ----------------

    private fun updateControlStatus() {
        val on = JarvisAccessibilityService.isEnabled(this)
        accBanner.visibility = if (on) View.GONE else View.VISIBLE
        controlText.text = if (on) "On" else "Off"
        controlText.setTextColor(ContextCompat.getColor(this, if (on) R.color.good else R.color.amber))
    }

    private fun updateMode() {
        val online = JarvisCore.hasInternet()
        val mode = Prefs.mode.uppercase(Locale.US)
        val net = when {
            Prefs.mode == "offline" -> "LOCAL"
            online -> "ONLINE"
            else -> "OFFLINE"
        }
        modeChip.text = "● $mode · $net" + if (Prefs.awayMode != "off") " · AWAY" else ""
        modeChip.setTextColor(ContextCompat.getColor(this, if (net == "ONLINE") R.color.cyan else R.color.amber))
    }

    // ---------------- Core listener ----------------

    override fun onState(state: JarvisCore.State) {
        core.mode = state
        wave.mode = state
        statusText.text = when (state) {
            JarvisCore.State.IDLE -> if (JarvisCore.continuous) "SAY “HEY ${Prefs.wakeWord.uppercase(Locale.US)}” OR TAP THE CORE" else "TAP THE CORE TO TALK"
            JarvisCore.State.LISTENING -> if (JarvisCore.continuous) "LISTENING FOR “${Prefs.wakeWord.uppercase(Locale.US)}”" else "LISTENING…"
            JarvisCore.State.THINKING -> "PROCESSING…"
            JarvisCore.State.SPEAKING -> "RESPONDING · TAP TO INTERRUPT"
        }
        statusText.setTextColor(ContextCompat.getColor(this, if (state == JarvisCore.State.THINKING) R.color.amber else R.color.cyan))
        if (state == JarvisCore.State.IDLE) { refreshQuickStates(); renderToday(); updateMode() }
    }

    override fun onMessage(fromUser: Boolean, text: String) {
        addBubble(fromUser, text)
        scrollChat()
    }

    override fun onPartial(text: String) { partialText.text = text }

    override fun onLevel(level: Float) {
        core.setLevel(level)
        wave.setLevel(level)
    }

    override fun onContinuousChanged(on: Boolean) {
        setSwitch(on && ListenService.running)
        onState(JarvisCore.state)
    }

    override fun onRecentChanged() = renderRecents()

    // ---------------- Chat + recents ----------------

    private fun renderChat() {
        chatList.removeAllViews()
        if (JarvisCore.history.isEmpty()) addBubble(false, JarvisCore.greetingText())
        JarvisCore.history.takeLast(80).forEach { addBubble(it.first, it.second) }
        scrollChat()
    }

    private fun scrollChat() = chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }

    private fun addBubble(fromUser: Boolean, text: String) {
        val dp = resources.displayMetrics.density
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.15f)
            setBackgroundResource(if (fromUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_ai)
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            setTextIsSelectable(true)
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (fromUser) Gravity.END else Gravity.START
            topMargin = (6 * dp).toInt()
        }
        chatList.addView(tv, lp)
    }

    private fun renderRecents() {
        recentRow.removeAllViews()
        val recents = Prefs.recentCommands
        recentScroll.visibility = if (recents.isEmpty()) View.GONE else View.VISIBLE
        val dp = resources.displayMetrics.density
        recents.forEach { cmd ->
            val chip = TextView(this).apply {
                text = cmd
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.cyan))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setBackgroundResource(R.drawable.bg_chip)
                setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
                maxLines = 1
                maxWidth = (220 * dp).toInt()
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener { JarvisCore.handleText(cmd) }
            }
            recentRow.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (6 * dp).toInt()
            })
        }
    }

    // ---------------- TODAY card ----------------

    fun renderToday() {
        todayList.removeAllViews()
        val tasks = Tasks.today(this)
        val open = tasks.count { !it.done }
        todayTitle.text = if (tasks.isEmpty()) "TODAY" else "TODAY · $open LEFT"
        val dp = resources.displayMetrics.density
        if (tasks.isEmpty()) {
            todayList.addView(TextView(this).apply {
                text = "Nothing yet. Say “add gym at 7 to my list”."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, (4 * dp).toInt(), 0, (2 * dp).toInt())
            })
            return
        }
        tasks.take(3).forEach { t ->
            val row = TextView(this).apply {
                val time = if (t.at > 0) "  ·  ${t.timeLabel()}" else ""
                text = (if (t.done) "●  " else "○  ") + t.text + time
                setTextColor(ContextCompat.getColor(this@MainActivity, if (t.done) R.color.text_dim else R.color.text))
                paintFlags = if (t.done) paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, (5 * dp).toInt(), 0, (5 * dp).toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener { Tasks.setDone(this@MainActivity, t.id, !t.done); renderToday() }
                setOnLongClickListener { Dialogs.taskList(this@MainActivity) { renderToday() }; true }
            }
            todayList.addView(row)
        }
        if (tasks.size > 3) {
            todayList.addView(TextView(this).apply {
                text = "+ ${tasks.size - 3} more"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.cyan))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setOnClickListener { Dialogs.taskList(this@MainActivity) { renderToday() } }
            })
        }
    }

    /** Called by dialogs after a permission/setting change. */
    fun refreshAll() {
        updateControlStatus()
        updateMode()
        refreshQuickStates()
        renderToday()
    }

    @Suppress("unused")
    private fun confirmBox(title: String, msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton("OK") { _, _ -> ok() }.setNegativeButton("Cancel", null).show()
    }
}
