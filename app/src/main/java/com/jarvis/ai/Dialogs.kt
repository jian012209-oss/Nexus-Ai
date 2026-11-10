package com.jarvis.ai

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch

/** Settings, memory, routines, permissions and help screens. */
object Dialogs {

    // ---------------- Small builders ----------------

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun column(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
    }

    private fun scroll(ctx: Context, content: View) = ScrollView(ctx).apply { addView(content) }

    private fun label(ctx: Context, text: String, small: Boolean = false) = TextView(ctx).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(ctx, if (small) R.color.text_dim else R.color.cyan))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 12f else 13f)
        setPadding(0, dp(ctx, if (small) 2 else 14), 0, dp(ctx, 2))
        if (!small) letterSpacing = 0.06f
        setTextIsSelectable(small)
    }

    private fun field(ctx: Context, hint: String, value: String, password: Boolean = false, multiline: Boolean = false) =
        EditText(ctx).apply {
            this.hint = hint
            setText(value)
            setTextColor(ContextCompat.getColor(ctx, R.color.text))
            setHintTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multiline) { minLines = 2; maxLines = 6 } else maxLines = 1
        }

    private fun radios(ctx: Context, options: List<Pair<String, String>>, selected: String): RadioGroup =
        RadioGroup(ctx).apply {
            orientation = RadioGroup.VERTICAL
            options.forEachIndexed { i, (value, text) ->
                val rb = RadioButton(ctx).apply {
                    id = View.generateViewId()
                    this.text = text
                    tag = value
                    setTextColor(ContextCompat.getColor(ctx, R.color.text))
                }
                addView(rb)
                if (value == selected || (i == 0 && options.none { it.first == selected })) check(rb.id)
            }
        }

    private fun RadioGroup.value(): String = findViewById<RadioButton>(checkedRadioButtonId)?.tag as? String ?: ""

    private fun seek(ctx: Context, min: Float, max: Float, value: Float): SeekBar = SeekBar(ctx).apply {
        this.max = 100
        progress = (((value - min) / (max - min)) * 100).toInt().coerceIn(0, 100)
    }

    private fun SeekBar.toValue(min: Float, max: Float) = min + (progress / 100f) * (max - min)

    private fun builder(ctx: Context) = AlertDialog.Builder(ctx)

    // ---------------- Onboarding ----------------

    fun onboarding(a: MainActivity, done: () -> Unit) {
        builder(a)
            .setTitle("Welcome to JARVIS")
            .setMessage(
                "I'll ask for a few permissions. You can change any of them later under Menu, Permissions.\n\n" +
                    "• Microphone: to hear your voice commands. I only listen when you tap the core, or when \"Always ready\" is on, and a notification is shown the whole time.\n" +
                    "• Contacts: to find who to call or text.\n" +
                    "• Phone and SMS: to call or send texts, always after you say yes.\n" +
                    "• Notifications: to show routine and listening status.\n\n" +
                    "For full phone control (home, back, lock, scroll, tap), turn on JARVIS in Accessibility afterwards.\n\n" +
                    "Online answers need a free AI key, which you can add in Settings. Phone commands work offline without one."
            )
            .setCancelable(false)
            .setPositiveButton("Continue") { _, _ -> done() }
            .show()
    }

    fun phoneControlHelp(a: MainActivity) {
        builder(a)
            .setTitle("Turn on phone control")
            .setMessage(
                "1. Tap Open Accessibility.\n" +
                    "2. Find JARVIS AI phone control (it may be under Installed or Downloaded apps).\n" +
                    "3. Switch it on and allow.\n\n" +
                    "If the switch is greyed out (Android 13+ for apps installed outside the Play Store): tap App info, then the ⋮ menu at the top right, then \"Allow restricted settings\", and try again.\n\n" +
                    "JARVIS only uses this when you give a command. It never records your screen."
            )
            .setPositiveButton("Open Accessibility") { _, _ ->
                PhoneControl.launch(a, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("App info") { _, _ ->
                PhoneControl.launch(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
            }
            .setNegativeButton("Later", null)
            .show()
    }

    // ---------------- Settings ----------------

    fun settings(a: MainActivity, onSaved: () -> Unit) {
        val col = column(a)

        col.addView(label(a, "YOUR NAME"))
        val nameField = field(a, "What should I call you?", Prefs.name)
        col.addView(nameField)

        col.addView(label(a, "WAKE WORD"))
        val wakeField = field(a, "Jarvis", Prefs.wakeWord)
        col.addView(wakeField)
        col.addView(label(a, "You'll say \"Hey\" plus this word. Short, unusual words work best.", true))

        col.addView(label(a, "MODE"))
        val modeGroup = radios(a, listOf(
            "hybrid" to "Hybrid: cloud AI when online, local engine when offline",
            "online" to "Online: send every question to the cloud AI",
            "offline" to "Offline: nothing leaves this phone"
        ), Prefs.mode)
        col.addView(modeGroup)

        col.addView(label(a, "CLOUD AI PROVIDER"))
        val providerGroup = radios(a, listOf("gemini" to "Google Gemini (free key available)", "claude" to "Anthropic Claude"), Prefs.provider)
        col.addView(providerGroup)

        val keys = mutableMapOf("gemini" to Prefs.apiKeyFor("gemini"), "claude" to Prefs.apiKeyFor("claude"))
        val fasts = mutableMapOf("gemini" to Prefs.fastModel("gemini"), "claude" to Prefs.fastModel("claude"))
        val smarts = mutableMapOf("gemini" to Prefs.smartModel("gemini"), "claude" to Prefs.smartModel("claude"))
        var current = Prefs.provider

        col.addView(label(a, "API KEY"))
        val keyField = field(a, "Paste your key", keys[current] ?: "", password = true)
        col.addView(keyField)
        val keyHelp = label(a, "", true)
        col.addView(keyHelp)
        col.addView(label(a, "FAST MODEL (quick answers)"))
        val fastField = field(a, "", fasts[current] ?: "")
        col.addView(fastField)
        col.addView(label(a, "SMART MODEL (code, writing, analysis)"))
        val smartField = field(a, "", smarts[current] ?: "")
        col.addView(smartField)

        fun showHelp(p: String) {
            keyHelp.text = if (p == "claude") "Get a key at console.anthropic.com (paid usage)."
            else "Get a free key at aistudio.google.com/apikey and paste it here. It's stored encrypted on this phone."
        }
        showHelp(current)
        providerGroup.setOnCheckedChangeListener { g, _ ->
            keys[current] = keyField.text.toString()
            fasts[current] = fastField.text.toString()
            smarts[current] = smartField.text.toString()
            current = (g as RadioGroup).value()
            keyField.setText(keys[current] ?: "")
            fastField.setText(fasts[current] ?: "")
            smartField.setText(smarts[current] ?: "")
            showHelp(current)
        }

        col.addView(label(a, "LANGUAGE"))
        val langGroup = radios(a, listOf("en-US" to "English (US)", "en-PH" to "English (Philippines)", "fil-PH" to "Filipino"), Prefs.language)
        col.addView(langGroup)

        col.addView(label(a, "PERSONALITY"))
        val persGroup = radios(a, listOf("professional" to "Professional", "friendly" to "Friendly", "witty" to "Witty"), Prefs.personality)
        col.addView(persGroup)

        col.addView(label(a, "VOICE"))
        val voices = listOf("Default") + JarvisCore.voiceNames()
        val voiceSpinner = Spinner(a).apply {
            adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, voices)
            setSelection(voices.indexOf(Prefs.voiceName).coerceAtLeast(0))
        }
        col.addView(voiceSpinner)
        col.addView(label(a, "Speaking speed", true))
        val speed = seek(a, 0.6f, 1.8f, Prefs.speechRate)
        col.addView(speed)
        col.addView(label(a, "Voice pitch", true))
        val pitch = seek(a, 0.6f, 1.4f, Prefs.pitch)
        col.addView(pitch)

        fun applyVoiceFields() {
            Prefs.voiceName = voices.getOrNull(voiceSpinner.selectedItemPosition)?.takeIf { it != "Default" } ?: ""
            Prefs.speechRate = speed.toValue(0.6f, 1.8f)
            Prefs.pitch = pitch.toValue(0.6f, 1.4f)
            Prefs.language = langGroup.value()
        }
        col.addView(Button(a).apply {
            text = "Test voice"
            setOnClickListener { applyVoiceFields(); JarvisCore.previewVoice() }
        })

        val jarvisVoice = MaterialSwitch(a).apply {
            text = "J.A.R.V.I.S. voice (British male)"
            isChecked = Prefs.jarvisVoice
            setTextColor(ContextCompat.getColor(a, R.color.text))
        }
        col.addView(jarvisVoice)
        col.addView(label(a, "Uses your phone's UK English male voice. If it sounds wrong, install \"English (United Kingdom)\" in Google Speech Services voice data.", true))

        col.addView(label(a, "CALLS"))
        val announceCalls = MaterialSwitch(a).apply {
            text = "Say who's calling"
            isChecked = Prefs.announceCalls
            setTextColor(ContextCompat.getColor(a, R.color.text))
        }
        col.addView(announceCalls)
        col.addView(label(a, "Away message ({name} = your name)", true))
        val awayMsg = field(a, "", Prefs.awayMessage, multiline = true)
        col.addView(awayMsg)

        col.addView(label(a, "DAILY BRIEFING"))
        val cityField = field(a, "Your city, for weather (e.g. Quezon City)", Prefs.city)
        col.addView(cityField)
        val briefOn = MaterialSwitch(a).apply {
            text = "Brief me every morning"
            isChecked = Prefs.briefingEnabled
            setTextColor(ContextCompat.getColor(a, R.color.text))
        }
        col.addView(briefOn)
        val briefTime = field(a, "Time, e.g. 7:00 AM", PhoneControl.spokenTime(Prefs.briefingHour, Prefs.briefingMinute))
        col.addView(briefTime)
        val briefStyle = radios(a, listOf("notify" to "As a notification", "call" to "J.A.R.V.I.S. calls me"), Prefs.briefingStyle)
        col.addView(briefStyle)

        col.addView(label(a, "SOUND"))
        val silent = MaterialSwitch(a).apply {
            text = "Silence listening beeps"
            isChecked = Prefs.silentListening
            setTextColor(ContextCompat.getColor(a, R.color.text))
        }
        col.addView(silent)
        col.addView(label(a, "Mutes system and notification sounds while JARVIS is listening. Calls still ring and music you're playing isn't muted.", true))

        col.addView(label(a, "PRIVACY"))
        val announce = MaterialSwitch(a).apply {
            text = "Announce new notifications aloud"
            isChecked = Prefs.announceNotifications
            setTextColor(ContextCompat.getColor(a, R.color.text))
        }
        col.addView(announce)
        val privacy = MaterialSwitch(a).apply {
            text = "Privacy mode (don't save conversation)"
            isChecked = Prefs.privacyMode
            setTextColor(ContextCompat.getColor(a, R.color.text))
        }
        col.addView(privacy)

        builder(a)
            .setTitle("Settings")
            .setView(scroll(a, col))
            .setPositiveButton("Save") { _, _ ->
                keys[current] = keyField.text.toString()
                fasts[current] = fastField.text.toString()
                smarts[current] = smartField.text.toString()
                Prefs.name = nameField.text.toString().trim().ifBlank { "sir" }
                Prefs.wakeWord = wakeField.text.toString()
                Prefs.mode = modeGroup.value().ifBlank { "hybrid" }
                Prefs.provider = current
                listOf("gemini", "claude").forEach { p ->
                    Prefs.setApiKey(p, keys[p] ?: "")
                    Prefs.setModels(p, fasts[p] ?: "", smarts[p] ?: "")
                }
                Prefs.personality = persGroup.value().ifBlank { "professional" }
                applyVoiceFields()
                Prefs.announceNotifications = announce.isChecked
                Prefs.silentListening = silent.isChecked
                Prefs.jarvisVoice = jarvisVoice.isChecked
                Prefs.announceCalls = announceCalls.isChecked
                awayMsg.text.toString().trim().takeIf { it.isNotEmpty() }?.let { Prefs.awayMessage = it }
                Prefs.city = cityField.text.toString()
                Prefs.briefingEnabled = briefOn.isChecked
                CommandEngine.parseTime(briefTime.text.toString().lowercase())?.let { (h, m) -> Prefs.briefingHour = h; Prefs.briefingMinute = m }
                Prefs.briefingStyle = briefStyle.value().ifBlank { "notify" }
                JarvisScheduler.scheduleBriefing(a)
                if (announceCalls.isChecked && Build.VERSION.SDK_INT >= 29 && !PhoneControl.hasCallRole(a)) a.requestCallSetup()
                if (privacy.isChecked != Prefs.privacyMode) Prefs.privacyMode = privacy.isChecked
                JarvisCore.applyVoice()
                JarvisCore.rebuildWakeWord()
                if (announce.isChecked && !NotifListener.isEnabled(a)) {
                    PhoneControl.launch(a, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- Memory ----------------

    fun memory(a: MainActivity) {
        val col = column(a)
        col.addView(label(a, "ABOUT YOU"))
        col.addView(label(a, "Name: ${Prefs.name}\nLanguage: ${Prefs.language}\nVoice: ${Prefs.voiceName.ifBlank { "Default" }}\nFrequent apps: ${Prefs.topApps().joinToString().ifBlank { "none yet" }}", true))
        col.addView(label(a, "MEMORIES, NOTES AND REMINDERS"))
        val items = Prefs.memories
        if (items.isEmpty()) col.addView(label(a, "Nothing saved yet. Say \"remember that…\" or \"take a note…\".", true))
        var dialog: AlertDialog? = null
        items.forEachIndexed { index, item ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val tv = TextView(a).apply {
                text = item
                setTextColor(ContextCompat.getColor(a, R.color.text))
                setPadding(0, dp(a, 8), dp(a, 8), dp(a, 8))
                setOnClickListener {
                    dialog?.dismiss()
                    editMemory(a, index, item)
                }
            }
            row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(a, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Delete"
                setTextColor(ContextCompat.getColor(a, R.color.danger))
                setOnClickListener {
                    Prefs.memories = Prefs.memories.toMutableList().also { l -> if (index < l.size) l.removeAt(index) }
                    dialog?.dismiss()
                    memory(a)
                }
            })
            col.addView(row)
        }
        val addField = field(a, "Add something to remember", "")
        col.addView(label(a, "ADD"))
        col.addView(addField)

        dialog = builder(a)
            .setTitle("Memory")
            .setView(scroll(a, col))
            .setPositiveButton("Save new") { _, _ ->
                val t = addField.text.toString().trim()
                if (t.isNotEmpty()) Prefs.memories = Prefs.memories + t
            }
            .setNeutralButton("Export") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Prefs.exportAll())
                a.startActivity(Intent.createChooser(send, "Export JARVIS data"))
            }
            .setNegativeButton("Delete all") { _, _ ->
                builder(a)
                    .setTitle("Delete everything?")
                    .setMessage("This erases all memories, notes, reminders, routines, recent commands and the saved conversation. It can't be undone.")
                    .setPositiveButton("Delete") { _, _ -> Prefs.deleteAllPersonal(); JarvisCore.clearConversation(); a.recreate() }
                    .setNegativeButton("Keep", null)
                    .show()
            }
            .show()
    }

    private fun editMemory(a: MainActivity, index: Int, item: String) {
        val f = field(a, "", item, multiline = true)
        val col = column(a).apply { addView(f) }
        builder(a).setTitle("Edit memory").setView(col)
            .setPositiveButton("Save") { _, _ ->
                val l = Prefs.memories.toMutableList()
                if (index < l.size) { l[index] = f.text.toString().trim(); Prefs.memories = l.filter { it.isNotBlank() } }
                memory(a)
            }
            .setNegativeButton("Cancel") { _, _ -> memory(a) }
            .show()
    }

    // ---------------- Routines ----------------

    fun routines(a: MainActivity) {
        val r = Prefs.routines
        val names = r.keys.toList()
        val labels = names.map { "${it.replaceFirstChar { c -> c.uppercase() }}\n${r[it]}" }.toTypedArray()
        builder(a)
            .setTitle("Routines")
            .setItems(labels) { _, which -> editRoutine(a, names[which]) }
            .setPositiveButton("New routine") { _, _ -> editRoutine(a, null) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun editRoutine(a: MainActivity, name: String?) {
        val r = Prefs.routines
        val col = column(a)
        col.addView(label(a, "NAME (what you'll say)"))
        val nameField = field(a, "e.g. work mode", name ?: "")
        col.addView(nameField)
        col.addView(label(a, "STEPS, separated by commas"))
        val steps = field(a, "e.g. do not disturb on, set volume to 20 percent, open gmail", name?.let { r[it] } ?: "", multiline = true)
        col.addView(steps)
        col.addView(label(a, "Each step is a normal command. Sensitive steps (calls, texts) will still ask you to confirm.", true))

        val b = builder(a)
            .setTitle(if (name == null) "New routine" else "Edit routine")
            .setView(scroll(a, col))
            .setPositiveButton("Save") { _, _ ->
                val n = nameField.text.toString().trim().lowercase()
                val s = steps.text.toString().trim()
                if (n.isNotEmpty() && s.isNotEmpty()) {
                    val m = Prefs.routines
                    if (name != null && name != n) m.remove(name)
                    m[n] = s
                    Prefs.routines = m
                }
                routines(a)
            }
            .setNegativeButton("Cancel") { _, _ -> routines(a) }
        if (name != null) {
            b.setNeutralButton("Delete") { _, _ ->
                val m = Prefs.routines; m.remove(name); Prefs.routines = m
                routines(a)
            }
        }
        b.show()
    }

    fun runRoutinePicker(a: MainActivity) {
        val names = Prefs.routines.keys.toList()
        if (names.isEmpty()) { routines(a); return }
        builder(a)
            .setTitle("Run a routine")
            .setItems(names.map { it.replaceFirstChar { c -> c.uppercase() } }.toTypedArray()) { _, which ->
                JarvisCore.handleText(names[which])
            }
            .setPositiveButton("Edit routines") { _, _ -> routines(a) }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------- Tasks ----------------

    fun addTask(a: MainActivity, done: () -> Unit) {
        val col = column(a)
        val text = field(a, "What do you need to do?", "")
        val time = field(a, "Time (optional), e.g. 7 PM", "")
        col.addView(label(a, "TASK"))
        col.addView(text)
        col.addView(label(a, "TIME"))
        col.addView(time)
        col.addView(label(a, "With a time, J.A.R.V.I.S. will notify you when it's due.", true))
        builder(a).setTitle("Add to today").setView(col)
            .setPositiveButton("Add") { _, _ ->
                val t = text.text.toString().trim()
                if (t.isNotEmpty()) {
                    val at = CommandEngine.parseTime(time.text.toString().lowercase())?.let { (h, m) -> Tasks.nextOccurrence(h, m) } ?: 0L
                    Tasks.add(a, t, at)
                    done()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun taskList(a: MainActivity, done: () -> Unit) {
        val tasks = Tasks.all(a).sortedWith(compareBy({ it.done }, { if (it.at == 0L) Long.MAX_VALUE else it.at }))
        if (tasks.isEmpty()) { addTask(a, done); return }
        val labels = tasks.map { (if (it.done) "● " else "○ ") + it.text + if (it.at > 0) "  ·  ${it.timeLabel()}" else "" }.toTypedArray()
        builder(a).setTitle("Your list")
            .setItems(labels) { _, which ->
                val t = tasks[which]
                builder(a).setTitle(t.text)
                    .setPositiveButton(if (t.done) "Not done" else "Done") { _, _ -> Tasks.setDone(a, t.id, !t.done); done() }
                    .setNegativeButton("Delete") { _, _ -> Tasks.remove(a, t.id); done() }
                    .setNeutralButton("Close", null)
                    .show()
            }
            .setPositiveButton("Add") { _, _ -> addTask(a, done) }
            .setNeutralButton("Clear finished") { _, _ -> Tasks.clearDone(a); done() }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------- Permissions dashboard ----------------

    fun permissions(a: MainActivity) {
        val col = column(a)
        var dialog: AlertDialog? = null

        fun row(title: String, granted: Boolean, why: String, action: () -> Unit) {
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(a).apply {
                text = title
                setTextColor(ContextCompat.getColor(a, R.color.text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            texts.addView(TextView(a).apply {
                text = why
                setTextColor(ContextCompat.getColor(a, R.color.text_dim))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            })
            r.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(Button(a, null, android.R.attr.borderlessButtonStyle).apply {
                text = if (granted) "On" else "Allow"
                setTextColor(ContextCompat.getColor(a, if (granted) R.color.good else R.color.amber))
                setOnClickListener { dialog?.dismiss(); action() }
            })
            r.setPadding(0, dp(a, 6), 0, dp(a, 6))
            col.addView(r)
        }

        fun runtime(perm: String) = PhoneControl.has(a, perm)
        row("Microphone", runtime(Manifest.permission.RECORD_AUDIO), "Hear voice commands") { a.requestOne(Manifest.permission.RECORD_AUDIO) }
        row("Contacts", runtime(Manifest.permission.READ_CONTACTS), "Find people to call or text") { a.requestOne(Manifest.permission.READ_CONTACTS) }
        row("Phone calls", runtime(Manifest.permission.CALL_PHONE), "Call after you confirm") { a.requestOne(Manifest.permission.CALL_PHONE) }
        row("SMS", runtime(Manifest.permission.SEND_SMS), "Send texts after you confirm") { a.requestOne(Manifest.permission.SEND_SMS) }
        if (Build.VERSION.SDK_INT >= 33) {
            row("Notifications", runtime(Manifest.permission.POST_NOTIFICATIONS), "Show listening and routine status") { a.requestOne(Manifest.permission.POST_NOTIFICATIONS) }
        }
        row("Camera", runtime(Manifest.permission.CAMERA), "Vision: identify what you show me") { a.requestOne(Manifest.permission.CAMERA) }
        row("Calendar", runtime(Manifest.permission.READ_CALENDAR), "Events in your daily briefing") { a.requestOne(Manifest.permission.READ_CALENDAR) }
        if (Build.VERSION.SDK_INT >= 29) {
            row("Call assistant", PhoneControl.hasCallRole(a) && runtime(Manifest.permission.ANSWER_PHONE_CALLS), "Announce callers and answer when you're away") { a.requestCallSetup() }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val am = a.getSystemService(android.app.AlarmManager::class.java)
            row("Exact reminders", am?.canScheduleExactAlarms() == true, "Calls and task alerts right on time") {
                PhoneControl.launch(a, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${a.packageName}")))
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val nmf = a.getSystemService(NotificationManager::class.java)
            row("Full-screen calls", nmf?.canUseFullScreenIntent() == true, "Show J.A.R.V.I.S. calls on the lock screen") {
                PhoneControl.launch(a, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${a.packageName}")))
            }
        }
        row("Phone control", JarvisAccessibilityService.isEnabled(a), "Home, back, lock, scroll, tap, type") { phoneControlHelp(a) }
        row("Notification access", NotifListener.isEnabled(a), "Read or announce notifications") {
            PhoneControl.launch(a, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        val nm = a.getSystemService(NotificationManager::class.java)
        row("Do Not Disturb access", nm?.isNotificationPolicyAccessGranted == true, "Focus, gaming and night routines") {
            PhoneControl.launch(a, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        row("Modify system settings", Settings.System.canWrite(a), "Change screen brightness") {
            PhoneControl.launch(a, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${a.packageName}")))
        }
        row("Default assistant", false, "Open JARVIS by long-pressing Home") {
            PhoneControl.launch(a, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
        col.addView(label(a, "Notifications enabled: ${if (NotificationManagerCompat.from(a).areNotificationsEnabled()) "yes" else "no"}. " +
            "If a permission was denied twice, Android stops asking; turn it on from App info.", true))

        dialog = builder(a)
            .setTitle("Permissions")
            .setView(scroll(a, col))
            .setPositiveButton("Done", null)
            .setNeutralButton("App info") { _, _ ->
                PhoneControl.launch(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
            }
            .setOnDismissListener { a.refreshAll() }
            .show()
    }

    // ---------------- Guide ----------------

    fun guide(a: MainActivity) {
        val w = Prefs.wakeWord
        builder(a)
            .setTitle("Command guide")
            .setMessage(
                "Wake: \"Hey $w\" (with Always ready on), or tap the core.\n\n" +
                    "APPS: open YouTube · switch to Messenger · do I have GCash · close this\n" +
                    "DEVICE: flashlight on · set volume to 50 percent · brightness 30 percent · do not disturb on · silent mode · battery · storage · RAM\n" +
                    "SETTINGS: wifi · bluetooth · mobile data · airplane mode · location\n" +
                    "CONTROL: go home · go back · recent apps · lock screen · take screenshot · scroll down · tap Subscribe · type hello · read screen\n" +
                    "TIME: set alarm for 6:30 AM · set a timer for 10 minutes · remind me to call Mom at 5 PM · remind me to stretch in 20 minutes\n" +
                    "PEOPLE: call Mom · text Ana saying I'm on my way · email boss@example.com about meeting\n" +
                    "MEDIA: play lo-fi on YouTube · play Bini on Spotify · pause · next song · replay\n" +
                    "PLACES: navigate to SM Megamall · nearest gas station · search for weather Manila\n" +
                    "MEMORY: remember that my plate number is ABC 123 · what do you remember · take a note buy milk · read my notes\n" +
                    "CALLS: if anyone calls, tell them I'm in a meeting · away mode on · if anyone calls, text them · I'm back · who called\n" +
                    "JARVIS CALLS YOU: call me in 20 minutes about my deadline · call me at 7 PM to remind me to send the invoice · call me now\n" +
                    "VISION: what's this? · what am I holding? · read this · then ask: what is it used for?\n" +
                    "TO-DO: add gym at 7 to my list · what do I need to do today? · mark call mum done · brief me\n" +
                    "ROUTINES: gaming mode · good night, wake me at 6 · create routine work mode: do not disturb on, open Gmail\n" +
                    "CHAINS: open Spotify, play my music and set the volume to 40 percent\n" +
                    "MODES: hybrid mode · online mode · offline mode · privacy mode\n\n" +
                    "Anything else goes to the cloud AI when you're online."
            )
            .setPositiveButton("Got it", null)
            .show()
    }
}
