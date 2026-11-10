package com.jarvis.ai

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Offline command engine: turns plain language into real phone actions.
 * Works with no internet. Returns the spoken reply, or null if it doesn't
 * understand (then the online AI gets a chance).
 */
object CommandEngine {

    /** Returned when the action replies by itself later (routines, chains). */
    const val ASYNC = "\u0000async"

    private val verbs = listOf(
        "open", "launch", "start", "set", "turn", "play", "pause", "resume", "call", "text", "send",
        "increase", "decrease", "lower", "raise", "mute", "unmute", "take", "go", "lock", "search",
        "navigate", "read", "scroll", "tap", "type", "remind", "flashlight", "torch", "volume",
        "brightness", "enable", "disable", "switch", "show", "check", "tell", "skip", "next",
        "previous", "do not disturb", "wake", "close"
    ).joinToString("|")

    /** Splits "open spotify, play music and set volume to 40 percent" into steps. */
    fun splitChain(text: String): List<String> {
        val first = text.split(Regex("\\s*(?:,\\s*and then|,\\s*then|\\band then\\b|;)\\s*", RegexOption.IGNORE_CASE))
        val out = mutableListOf<String>()
        val second = Regex("\\s*,\\s*(?:and\\s+)?(?=(?:$verbs)\\b)|\\s+and\\s+(?=(?:$verbs)\\b)", RegexOption.IGNORE_CASE)
        for (part in first) {
            val p = part.trim()
            if (p.isEmpty()) continue
            // Don't split inside message bodies, notes or searches.
            if (Regex("^(text|message|send|sms|email|remember|note|type|search|google|remind|create|make)\\b|^open (youtube|spotify) and ", RegexOption.IGNORE_CASE).containsMatchIn(p)) {
                out += p
            } else {
                out += p.split(second).map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
        return out
    }

    private fun normalize(input: String): String {
        var t = input.lowercase(Locale.US).trim()
            .replace(Regex("[!?]"), "").trimEnd('.').replace(Regex("\\s+"), " ")
        t = t.replace(Regex("^(please|can you|could you|would you|will you|i want you to|i need you to)\\s+"), "")
            .replace(Regex("\\s+(please|for me|now)$"), "")
            .replace(Regex("^(please)\\s+"), "")
        return t.trim()
    }

    private val numWords = linkedMapOf(
        "forty five" to "45", "thirty" to "30", "forty" to "40", "fifty" to "50", "sixty" to "60",
        "seventy" to "70", "eighty" to "80", "ninety" to "90", "hundred" to "100", "twenty" to "20",
        "fifteen" to "15", "twelve" to "12", "eleven" to "11", "ten" to "10", "nine" to "9",
        "eight" to "8", "seven" to "7", "six" to "6", "five" to "5", "four" to "4", "three" to "3",
        "two" to "2", "one" to "1"
    )

    fun digitsPublic(s: String) = digits(s)

    private fun digits(s: String): String {
        var r = s
        numWords.forEach { (w, d) -> r = r.replace(Regex("\\b$w\\b"), d) }
        return r
    }

    private fun percent(t: String): Int? =
        Regex("(\\d{1,3})\\s*(?:%|percent|per cent)").find(digits(t))?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\\bto (\\d{1,3})$").find(digits(t))?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it <= 100 }

    private fun isUp(t: String) = Regex("\\b(up|increase|raise|higher|brighter|louder|more|turn it up)\\b").containsMatchIn(t)
    private fun isDown(t: String) = Regex("\\b(down|decrease|lower|dimmer|dim|quieter|softer|less|reduce)\\b").containsMatchIn(t)
    private fun isOff(t: String) = Regex("\\b(off|disable|deactivate|stop|close)\\b").containsMatchIn(t)
    private fun isOn(t: String) = Regex("\\b(on|enable|activate|start|open)\\b").containsMatchIn(t)

    fun parseTime(text: String): Pair<Int, Int>? {
        val s = digits(text).replace("a.m.", "am").replace("p.m.", "pm")
            .replace("a. m.", "am").replace("p. m.", "pm").replace("o'clock", "")
        if (Regex("\\bnoon\\b").containsMatchIn(s)) return 12 to 0
        if (Regex("\\bmidnight\\b").containsMatchIn(s)) return 0 to 0
        val m = Regex("\\b(\\d{1,2})(?:[:.\\s](\\d{2}))?\\s*(am|pm)?\\b").findAll(s)
            .firstOrNull { (it.groupValues[1].toIntOrNull() ?: 99) <= 23 } ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toIntOrNull() ?: 0
        when (m.groupValues[3]) {
            "pm" -> if (h < 12) h += 12
            "am" -> if (h == 12) h = 0
            else -> if (h < 12 && Regex("\\b(evening|tonight|night|afternoon)\\b").containsMatchIn(s)) h += 12
        }
        if (h > 23 || min > 59) return null
        return h to min
    }

    fun parseDuration(text: String): Int {
        val s = digits(text)
        var total = 0
        Regex("(\\d+)\\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)").findAll(s).forEach {
            val n = it.groupValues[1].toInt()
            val u = it.groupValues[2]
            total += when { u.startsWith("h") -> n * 3600; u.startsWith("m") -> n * 60; else -> n }
        }
        if (total == 0) total = when {
            s.contains("half an hour") -> 1800
            s.contains("an hour") -> 3600
            s.contains("a minute") -> 60
            else -> 0
        }
        return total
    }

    private fun spacedNumber(n: String) = n.replace(Regex("(\\d)(?=\\d)"), "$1 ")

    fun process(ctx: Context, input: String): String? {
        val t = normalize(input)
        val name = Prefs.name
        if (t.isEmpty() || t == Prefs.wakeWord.lowercase()) return "Yes, $name?"

        // ===== JARVIS calls you =====
        if (Regex("^(call me|ring me|phone me|give me a call)( now| please)?$").containsMatchIn(t)) {
            IncomingCall.ring(ctx, "")
            return ""
        }
        Regex("^(?:call me|ring me|phone me|give me a call) ((?=.*\\b(?:in|at|tomorrow|tonight|later|about|remind|noon|midnight)\\b).*)$").find(t)?.let { m ->
            val rest = m.groupValues[1].trim()
            val reason = Regex("\\b(?:to remind me (?:to |about |of )?|about |to tell me (?:to |about )?|so i (?:don't forget to |remember to )?|for )(.+)$")
                .find(rest)?.groupValues?.get(1)?.trim().orEmpty()
            val whenPart = if (reason.isNotEmpty()) rest.substringBefore(reason).trim() else rest
            val secs = if (Regex("\\bin\\b").containsMatchIn(whenPart)) parseDuration(whenPart) else 0
            val at: Long = when {
                secs > 0 -> System.currentTimeMillis() + secs * 1000L
                parseTime(whenPart) != null -> parseTime(whenPart)!!.let { Tasks.nextOccurrence(it.first, it.second, whenPart.contains("tomorrow")) }
                else -> return "When should I call you? For example, \"call me in 20 minutes\" or \"call me at 7 PM\"."
            }
            JarvisScheduler.scheduleCall(ctx, at, reason)
            val label = java.text.SimpleDateFormat("h:mm a", Locale.US).format(java.util.Date(at))
            return "I'll call you at $label" + (if (reason.isNotEmpty()) " about $reason." else ".")
        }
        if (Regex("^(cancel|stop) (all )?(your |jarvis )?(scheduled )?calls?$").containsMatchIn(t)) {
            val n = JarvisScheduler.cancelAllCalls(ctx)
            return if (n == 0) "There were no calls scheduled." else "Cancelled $n scheduled call${if (n > 1) "s" else ""}."
        }

        // ===== Call assistant (away mode) =====
        if (Regex("^(away mode off|turn off away mode|stop answering( my)? calls|i'm back|im back|i am back|i'm available|i am available)$").containsMatchIn(t)) {
            Prefs.awayMode = "off"
            return "Welcome back, $name. " + CallAssistant.summary(clear = true)
        }
        if (Regex("\\b(who called|any (missed )?calls|did anyone call|missed calls)\\b").containsMatchIn(t)) {
            return CallAssistant.summary(clear = false)
        }
        if (Regex("\\b(announce (my )?calls|announce callers)\\b").containsMatchIn(t)) {
            Prefs.announceCalls = !isOff(t)
            if (Prefs.announceCalls && !PhoneControl.hasCallRole(ctx)) { requestCallSetup(ctx); return "I'll announce callers once you allow me as your call screening app on the next screen." }
            return if (Prefs.announceCalls) "I'll tell you who's calling." else "I'll stop announcing calls."
        }
        if (Regex("^(if (anyone|someone|somebody) calls|when (anyone|someone|somebody) calls|answer (my )?calls|take my calls|screen my calls|away mode( on)?|turn on away mode|i'm busy|im busy|i am busy|busy mode)").containsMatchIn(t)) {
            if (android.os.Build.VERSION.SDK_INT < 29) return "The call assistant needs Android 10 or newer."
            val textBack = Regex("\\b(text|message|sms)\\b").containsMatchIn(t) && !Regex("\\btell them\\b").containsMatchIn(t)
            Regex("\\b(?:tell them|say|let them know)(?: that)? (.+)$").find(input.trim())?.let { mm ->
                var msg = mm.groupValues[1].trim().trimEnd('.')
                val nm = name.replaceFirstChar { it.uppercase() }
                msg = msg.replace(Regex("^(?i)i'?m "), "$nm is ").replace(Regex("^(?i)i am "), "$nm is ")
                    .replace(Regex("^(?i)i'?ll "), "$nm will ").replace(Regex("^(?i)i will "), "$nm will ")
                    .replace(Regex("\\b(?i)I'll\\b"), "$nm will").replace(Regex("\\b(?i)I'm\\b"), "$nm is")
                Prefs.awayMessage = "Hello, this is JARVIS, {name}'s personal assistant. ${msg.replaceFirstChar { it.uppercase() }}. Goodbye."
            }
            Prefs.awayMode = if (textBack) "text" else "answer"
            if (!PhoneControl.hasCallRole(ctx) || !PhoneControl.has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) {
                requestCallSetup(ctx)
                return "Away mode is ready. Allow JARVIS as your call screening app and allow phone call access on the next screen, then I'll take your calls."
            }
            return if (textBack) "Understood. If anyone calls, I'll decline and text them that you're unavailable."
            else "Understood, $name. If anyone calls, I'll answer and tell them: ${Prefs.awayMessageFilled()}"
        }

        // ===== To-do list and briefing =====
        if (Regex("^(brief me|briefing|daily briefing|morning briefing|give me (my |a )?(daily |morning )?briefing|what's my day( like)?|how does my day look)$").containsMatchIn(t)) {
            JarvisCore.briefing()
            return ASYNC
        }
        if (Regex("\\b(what do i (need|have) to do|what's on my (list|to ?do|agenda)|what are my tasks|my to ?do list|read my (list|tasks|to ?do)|anong gagawin ko)\\b").containsMatchIn(t)) {
            return Tasks.todaySummary(ctx)
        }
        Regex("^(?:add|put) (.+?) (?:to|on|in) my (?:to ?do |task |)(?:list|tasks|to ?dos?)$|^(?:add (?:a )?task|new task|to ?do):? (.+)$|^i need to (.+)$").find(t)?.let { m ->
            var item = (m.groupValues[1].ifBlank { m.groupValues[2] }.ifBlank { m.groupValues[3] }).trim()
            var at = 0L
            Regex("\\s*\\b(?:at|by) ([0-9: .]+\\s*(?:am|pm|a\\.m\\.|p\\.m\\.)?|noon|midnight)( tomorrow)?$").find(digitsPublic(item))?.let { tm ->
                parseTime(tm.groupValues[1])?.let { (hh, mm) -> at = Tasks.nextOccurrence(hh, mm, tm.groupValues[2].isNotEmpty()) }
                item = digitsPublic(item).removeRange(tm.range).trim()
            }
            if (item.isBlank()) return "What should I add?"
            Tasks.add(ctx, item, at)
            return "Added ${item}" + (if (at > 0) " at ${java.text.SimpleDateFormat("h:mm a", Locale.US).format(java.util.Date(at))}. I'll notify you." else " to your list.")
        }
        Regex("^(?:mark |cross off |check off |i (?:did|finished|completed) |done with |finished )(.+?)(?: as done| done| off)?$").find(t)?.let { m ->
            val task = Tasks.find(ctx, m.groupValues[1]) ?: return@let
            Tasks.setDone(ctx, task.id, true)
            val left = Tasks.today(ctx).count { !it.done }
            return "Done: ${task.text}. " + if (left == 0) "Your list is clear." else "$left left."
        }
        Regex("^(?:remove|delete) (.+?) from my (?:to ?do |task )?(?:list|tasks)$").find(t)?.let { m ->
            val task = Tasks.find(ctx, m.groupValues[1]) ?: return "That isn't on your list."
            Tasks.remove(ctx, task.id)
            return "Removed ${task.text}."
        }
        if (Regex("^(clear|clean up) (completed|done|finished) tasks$").containsMatchIn(t)) { Tasks.clearDone(ctx); return "Cleared finished tasks." }

        // ===== Vision =====
        if (Regex("^(what('s| is) (this|that)|what am i holding|look at (this|that)|what do you see|identify (this|that)|scan (this|that)|read (this|that)( for me)?|open vision|vision mode|use (the )?camera to see|can you see (this|that))$").containsMatchIn(t)) {
            val i = android.content.Intent(ctx, VisionActivity::class.java)
                .putExtra(VisionActivity.EXTRA_QUESTION, if (t.startsWith("open vision") || t.startsWith("vision mode")) "" else input.trim())
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            return if (PhoneControl.launch(ctx, i)) "Let me take a look." else "I couldn't open the camera."
        }

        // ===== Jarvis itself =====
        if (t in setOf("stop", "cancel", "never mind", "nevermind", "shut up", "be quiet", "quiet", "enough", "stop talking")) return ""
        if (Regex("\\b(stop|quit) listening\\b|^go to sleep$|^sleep$").containsMatchIn(t)) {
            JarvisCore.setContinuous(false)
            ListenService.stop(ctx)
            return "Going quiet. Tap the core when you need me."
        }
        if (Regex("^(switch to |go |use )?offline mode$|^go offline$").containsMatchIn(t)) {
            Prefs.mode = "offline"; return "Offline mode. Everything stays on this phone."
        }
        if (Regex("^(switch to |go |use )?online mode$|^go online$").containsMatchIn(t)) {
            Prefs.mode = "online"
            return if (JarvisCore.hasInternet()) "Online mode. Cloud intelligence is active." else "Online mode set, but there's no internet connection right now."
        }
        if (Regex("^(switch to |go |use )?hybrid mode$|^(automatic|auto) mode$").containsMatchIn(t)) {
            Prefs.mode = "hybrid"; return "Hybrid mode. I'll use the cloud when it's available and work offline when it isn't."
        }
        if (Regex("^(turn on |enable )?privacy mode( on)?$").containsMatchIn(t)) { Prefs.privacyMode = true; return "Privacy mode on. I won't save our conversation." }
        if (Regex("^(turn off |disable )?privacy mode( off)?$").containsMatchIn(t) && t.contains("off") || t == "disable privacy mode") {
            Prefs.privacyMode = false; return "Privacy mode off."
        }
        Regex("^(?:my name is|call me|you can call me) (.+)$").find(t)?.let {
            val nm = it.groupValues[1].trim().split(" ").joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
            Prefs.name = nm
            return "Understood. I'll call you $nm."
        }
        Regex("^(?:change|set) (?:your |the )?wake word to (.+)$").find(t)?.let {
            val w = it.groupValues[1].trim().replaceFirstChar { c -> c.uppercase() }
            Prefs.wakeWord = w
            JarvisCore.rebuildWakeWord()
            return "Done. Say \"Hey $w\" to wake me."
        }

        // ===== Routines =====
        Regex("^(?:create|make|add|save) (?:a |new )?routine (?:called |named )?(.+?)(?::| that| which| to| with)\\s+(?:does |runs |do |will )?(.+)$").find(t)?.let {
            val rName = it.groupValues[1].trim()
            val steps = splitChain(it.groupValues[2]).joinToString(", ")
            val r = Prefs.routines; r[rName] = steps; Prefs.routines = r
            return "Routine $rName saved. It will: $steps. Say \"$rName\" to run it."
        }
        Regex("^(?:delete|remove) (?:the )?routine (.+)$").find(t)?.let {
            val r = Prefs.routines
            val key = r.keys.firstOrNull { k -> k.equals(it.groupValues[1].trim(), true) } ?: return "There's no routine called ${it.groupValues[1]}."
            r.remove(key); Prefs.routines = r
            return "Routine $key deleted."
        }
        if (Regex("^(list|show|what are) (my |your )?routines$|^what routines").containsMatchIn(t)) {
            val r = Prefs.routines
            return if (r.isEmpty()) "You have no routines yet." else "Your routines are: ${r.keys.joinToString(", ")}."
        }
        val routineCall = t.replace(Regex("^(run|start|activate|begin|enable|do|turn on)\\s+(the\\s+)?"), "").replace(Regex("\\s+routine$"), "")
        Prefs.routines.entries.firstOrNull { routineCall == it.key || routineCall.startsWith(it.key + " ") || routineCall.startsWith(it.key + ",") }?.let { e ->
            val steps = splitChain(e.value).toMutableList()
            val extra = routineCall.removePrefix(e.key).trim(' ', ',')
            if (extra.isNotEmpty() && parseTime(extra) != null) steps += "set alarm for $extra"
            JarvisCore.runSequence(ctx, steps, intro = "${e.key.replaceFirstChar { it.uppercase() }} activated.", notifyTitle = e.key)
            return ASYNC
        }

        // ===== Time, date, status =====
        if (Regex("\\b(what time|the time|time is it|current time|oras na)\\b").containsMatchIn(t) && !Regex("\\btime (is it )?in [a-z]").containsMatchIn(t)) {
            return "It's ${SimpleDateFormat("h:mm a", Locale.US).format(Date())}."
        }
        if (Regex("\\b(what('s| is) (the )?date|what day|today's date|date today|what is today|anong araw)\\b").containsMatchIn(t)) {
            return "Today is ${SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(Date())}."
        }
        if (Regex("\\b(status report|system status|systems check|diagnostics)\\b").containsMatchIn(t)) {
            val net = if (JarvisCore.isOnline()) "online" else "offline"
            val ctl = if (JarvisAccessibilityService.isEnabled(ctx)) "Phone control is active" else "Phone control is off"
            return "All systems nominal, $name. ${PhoneControl.battery(ctx)} I'm $net. $ctl. ${PhoneControl.storage()}"
        }
        if (Regex("\\bbattery\\b|\\bcharging\\b|\\bbaterya\\b").containsMatchIn(t)) return PhoneControl.battery(ctx)
        if (Regex("\\b(storage|free space|space left|disk space)\\b").containsMatchIn(t)) return PhoneControl.storage()
        if (Regex("\\b(ram|phone memory|available memory|memory usage)\\b").containsMatchIn(t)) return PhoneControl.memory(ctx)

        // ===== Flashlight =====
        if ((Regex("\\b(flashlight|flash light|torch|flash)\\b").containsMatchIn(t) && !t.contains("flash drive")) ||
            Regex("^(open|close|turn on|turn off|switch on|switch off|on|off)( the| my)? lights?$|^lights? (on|off)$|^ilaw$|^buksan( ang)? ilaw$|^patayin( ang)? ilaw$").containsMatchIn(t)) {
            val on = when { isOff(t) || t.contains("patayin") -> false; isOn(t) || t.contains("buksan") -> true; else -> !PhoneControl.torchOn }
            return PhoneControl.torch(ctx, on)
        }

        // ===== Volume, ringer, DND =====
        if (Regex("^(unmute|un mute)").containsMatchIn(t)) return PhoneControl.mute(ctx, false)
        if (Regex("^(mute|silence)( the)?( media| sound| audio| volume| music)?$").containsMatchIn(t)) return PhoneControl.mute(ctx, true)
        if (Regex("\\b(silent mode|silent)\\b").containsMatchIn(t) && !isOff(t)) return PhoneControl.ringerMode(ctx, AudioManager.RINGER_MODE_SILENT)
        if (Regex("\\b(vibrate|vibration) mode\\b|^vibrate$").containsMatchIn(t)) return PhoneControl.ringerMode(ctx, AudioManager.RINGER_MODE_VIBRATE)
        if (Regex("\\b(ring|normal|sound) mode\\b").containsMatchIn(t) || (t.contains("silent") && isOff(t))) return PhoneControl.ringerMode(ctx, AudioManager.RINGER_MODE_NORMAL)
        if (Regex("\\b(do not disturb|dnd|don't disturb)\\b").containsMatchIn(t)) return PhoneControl.dnd(ctx, !isOff(t))
        if (Regex("\\b(volume|louder|quieter|softer|lakasan|hinaan)\\b").containsMatchIn(t)) {
            val pct = percent(t)
            return when {
                pct != null -> PhoneControl.setVolumePercent(ctx, pct)
                Regex("\\b(max|maximum|full|highest)\\b").containsMatchIn(t) -> PhoneControl.setVolumePercent(ctx, 100)
                Regex("\\b(min|minimum|lowest|zero)\\b").containsMatchIn(t) -> PhoneControl.setVolumePercent(ctx, 0)
                isUp(t) || t.contains("lakasan") -> PhoneControl.volumeStep(ctx, true)
                isDown(t) || t.contains("hinaan") -> PhoneControl.volumeStep(ctx, false)
                else -> "Media volume is at ${PhoneControl.volumePercent(ctx)} percent."
            }
        }

        // ===== Brightness =====
        if (Regex("\\b(brightness|brighter|dimmer|dim the screen|screen light)\\b").containsMatchIn(t)) {
            val pct = percent(t)
            return when {
                Regex("\\b(auto|automatic|adaptive)\\b").containsMatchIn(t) -> PhoneControl.brightness(ctx, null, auto = true)
                pct != null -> PhoneControl.brightness(ctx, pct)
                Regex("\\b(max|maximum|full|highest)\\b").containsMatchIn(t) -> PhoneControl.brightness(ctx, 100)
                Regex("\\b(min|minimum|lowest)\\b").containsMatchIn(t) -> PhoneControl.brightness(ctx, 2)
                isDown(t) -> PhoneControl.brightness(ctx, null, delta = -50)
                else -> PhoneControl.brightness(ctx, null, delta = 50)
            }
        }

        // ===== Settings panels =====
        if (Regex("\\b(wifi|wi-fi|wi fi)\\b").containsMatchIn(t)) return PhoneControl.openPanel(ctx, "wifi")
        if (Regex("\\bbluetooth\\b").containsMatchIn(t)) return PhoneControl.openPanel(ctx, "bluetooth")
        if (Regex("\\b(mobile data|cellular data|data connection)\\b|^(turn (on|off) )?data$").containsMatchIn(t)) return PhoneControl.openPanel(ctx, "data")
        if (Regex("\\b(airplane|flight) mode\\b").containsMatchIn(t)) return PhoneControl.openPanel(ctx, "airplane")
        if (Regex("\\b(location|gps)\\b").containsMatchIn(t) && !t.startsWith("navigate")) return PhoneControl.openPanel(ctx, "location")
        if (Regex("\\bhotspot\\b").containsMatchIn(t)) return PhoneControl.openPanel(ctx, "hotspot")
        if (Regex("\\bnfc\\b").containsMatchIn(t)) return PhoneControl.openPanel(ctx, "nfc")
        if (Regex("^(open |go to |show )?(the )?(phone |system )?settings$").containsMatchIn(t)) return PhoneControl.openPanel(ctx, "settings")

        // ===== Alarms, timers, reminders, notes =====
        if (Regex("\\b(alarm|wake me)\\b").containsMatchIn(t)) {
            val tm = parseTime(t) ?: return "What time should I set the alarm for?"
            return PhoneControl.setAlarm(ctx, tm.first, tm.second)
        }
        if (Regex("\\btimer\\b").containsMatchIn(t)) {
            val secs = parseDuration(t)
            if (secs <= 0) return "How long should the timer be?"
            return PhoneControl.setTimer(ctx, secs)
        }
        Regex("^remind me (?:to |about )?(.+)$").find(t)?.let { m ->
            val rest = m.groupValues[1]
            val inPart = Regex("\\s*\\bin (\\d+|a|an|half an|one|two|three|five|ten|fifteen|twenty|thirty) .*?(hours?|minutes?|mins?|seconds?)\\b").find(digits(rest))
            val atPart = Regex("\\s*\\b(?:at|by|on) ([0-9: .]+\\s*(?:am|pm|a\\.m\\.|p\\.m\\.)?|noon|midnight)\\b.*$").find(digits(rest))
            return when {
                inPart != null -> {
                    val task = digits(rest).removeRange(inPart.range).trim().removePrefix("to ").ifBlank { "your reminder" }
                    val secs = parseDuration(inPart.value)
                    Prefs.memories = Prefs.memories + "Reminder: $task"
                    PhoneControl.setTimer(ctx, secs, "Reminder: $task").let { r ->
                        if (r.startsWith("Timer set")) "I'll remind you to $task in ${PhoneControl.spokenDuration(secs)}." else r
                    }
                }
                atPart != null -> {
                    val task = digits(rest).removeRange(atPart.range).trim().removePrefix("to ").ifBlank { "your reminder" }
                    val tm = parseTime(atPart.value) ?: return "What time should I remind you?"
                    Prefs.memories = Prefs.memories + "Reminder: $task at ${PhoneControl.spokenTime(tm.first, tm.second)}"
                    PhoneControl.setAlarm(ctx, tm.first, tm.second, "Reminder: $task").let { r ->
                        if (r.startsWith("Alarm set")) "I'll remind you to $task at ${PhoneControl.spokenTime(tm.first, tm.second)}." else r
                    }
                }
                else -> {
                    Prefs.memories = Prefs.memories + "Reminder: $rest"
                    "Saved: $rest. Tell me a time, like \"remind me to $rest at 5 PM\", and I'll set an alert."
                }
            }
        }
        Regex("^(?:take a note|make a note|add a note|note that|note|write down)(?: that)?:? (.+)$").find(t)?.let {
            Prefs.memories = Prefs.memories + "Note: ${it.groupValues[1]}"
            return "Noted."
        }
        if (Regex("\\b(read|show|what are) (my )?(notes|reminders)\\b").containsMatchIn(t)) {
            val kind = if (t.contains("reminder")) "Reminder:" else "Note:"
            val items = Prefs.memories.filter { it.startsWith(kind) }.map { it.removePrefix(kind).trim() }
            return if (items.isEmpty()) "You have no ${if (kind == "Note:") "notes" else "reminders"}." else items.takeLast(6).joinToString(". ") + "."
        }

        // ===== Memory =====
        Regex("^remember (?:that )?(.+)$").find(t)?.let {
            Prefs.memories = Prefs.memories + it.groupValues[1]
            return "I'll remember that."
        }
        if (Regex("what do you (remember|know about me)|what did i tell you").containsMatchIn(t)) {
            val m = Prefs.memories.filter { !it.startsWith("Note:") && !it.startsWith("Reminder:") }
            return if (m.isEmpty()) "You haven't asked me to remember anything yet." else "You told me: ${m.takeLast(6).joinToString("; ")}."
        }
        if (Regex("^(forget|delete|erase) (everything|all memories|your memory|all my data)$").containsMatchIn(t)) {
            JarvisCore.requestConfirm { Prefs.deleteAllPersonal(); "Everything I stored about you has been deleted." }
            return "This deletes all memories, notes, routines and chat history. Are you sure? Say yes or no."
        }
        Regex("^forget (?:that )?(.+)$").find(t)?.let { m ->
            val before = Prefs.memories
            val after = before.filter { !it.lowercase().contains(m.groupValues[1]) }
            Prefs.memories = after
            return if (after.size < before.size) "Forgotten." else "I don't have anything about ${m.groupValues[1]}."
        }

        // ===== Calls, messages, email (always confirmed) =====
        Regex("^(?:call|phone|dial|ring|tawagan) (.+)$").find(t)?.let { m ->
            val who = m.groupValues[1].trim()
            val num = who.replace(Regex("[^0-9+]"), "")
            val contact = if (num.length >= 3 && !who.contains(Regex("[a-z]"))) PhoneControl.Contact(num, num) else {
                if (!PhoneControl.has(ctx, Manifest.permission.READ_CONTACTS)) return "I need contacts permission to find $who. Open Permissions from the menu and allow Contacts."
                PhoneControl.findContact(ctx, who) ?: return "I couldn't find $who in your contacts."
            }
            JarvisCore.requestConfirm { PhoneControl.call(ctx, contact.number) }
            return "Call ${contact.name}${if (contact.name != contact.number) ", ${spacedNumber(contact.number.replace(" ", ""))}" else ""}? Say yes or no."
        }
        Regex("^(?:send (?:a )?(?:text|message|sms) to|text|message|sms|i-text) (.+?) (?:saying|that says|that|say|and say|to say|telling (?:him|her|them)) (.+)$").find(t)?.let { m ->
            val who = m.groupValues[1].trim()
            val body = m.groupValues[2].trim().replaceFirstChar { it.uppercase() }
            val num = who.replace(Regex("[^0-9+]"), "")
            val contact = if (num.length >= 3 && !who.contains(Regex("[a-z]"))) PhoneControl.Contact(num, num) else {
                if (!PhoneControl.has(ctx, Manifest.permission.READ_CONTACTS)) return "I need contacts permission to find $who. Open Permissions from the menu and allow Contacts."
                PhoneControl.findContact(ctx, who) ?: return "I couldn't find $who in your contacts."
            }
            JarvisCore.requestConfirm { PhoneControl.sendSms(ctx, contact.number, body) }
            return "Send \"$body\" to ${contact.name}? Say yes or no."
        }
        Regex("^(?:send (?:an )?email to|email|draft an email to|write an email to) (\\S+?)(?: (?:about|saying|subject) (.+))?$").find(t)?.let { m ->
            val to = m.groupValues[1].replace(" at ", "@").let { if (it.contains("@")) it else "" }
            val about = m.groupValues[2]
            return PhoneControl.draftEmail(ctx, to, about.replaceFirstChar { it.uppercase() }, "")
        }

        // ===== Notifications =====
        if (Regex("\\b(read|check|what are) (my )?notifications\\b|any notifications").containsMatchIn(t)) {
            if (!NotifListener.isEnabled(ctx)) {
                PhoneControl.launch(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                return "I need notification access to read them. Allow JARVIS AI on the screen I opened."
            }
            val list = NotifListener.instance?.summaries() ?: return "Notification access is on, but the listener hasn't connected yet. Try again in a moment."
            return if (list.isEmpty()) "You have no new notifications." else "You have ${list.size}. " + list.joinToString(". ") + "."
        }
        if (Regex("\\bannounce notifications\\b").containsMatchIn(t)) {
            Prefs.announceNotifications = !isOff(t)
            return if (Prefs.announceNotifications) "I'll announce new notifications." else "I'll stop announcing notifications."
        }

        // ===== Media =====
        if (Regex("^(pause|pause (the )?(music|song|video|playback)|stop (the )?(music|song))$").containsMatchIn(t)) return PhoneControl.mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
        if (Regex("^(resume|continue|unpause|resume (the )?(music|song|playback))$").containsMatchIn(t)) return PhoneControl.mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
        if (Regex("^(next|skip|next (song|track|video)|skip (this )?(song|track))$").containsMatchIn(t)) return PhoneControl.mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT)
        if (Regex("^(previous|previous (song|track)|last (song|track)|go back a (song|track))$").containsMatchIn(t)) return PhoneControl.mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        if (Regex("^(replay|restart|play (it |that )?again|replay (the |this )?(song|track))$").containsMatchIn(t)) return PhoneControl.replay(ctx)
        if (Regex("^play (my |some )?(music|songs|playlist)$").containsMatchIn(t)) return PhoneControl.playMusic(ctx)
        Regex("^(?:play|search|watch) (.+?) on youtube$|^(?:youtube|search youtube for|open youtube and (?:search|play)(?: for)?) (.+)$").find(t)?.let {
            return PhoneControl.playOnYoutube(ctx, it.groupValues[1].ifBlank { it.groupValues[2] })
        }
        Regex("^play (.+?) on spotify$|^(?:open spotify and play) (.+)$").find(t)?.let {
            return PhoneControl.playOnSpotify(ctx, it.groupValues[1].ifBlank { it.groupValues[2] })
        }
        Regex("^play (.+)$").find(t)?.let {
            if (!it.groupValues[1].contains("store")) return PhoneControl.playOnYoutube(ctx, it.groupValues[1])
        }

        // ===== Camera =====
        if (Regex("\\b(selfie|front camera)\\b").containsMatchIn(t)) return PhoneControl.camera(ctx, true)
        if (Regex("\\b(record (a )?video|video camera)\\b").containsMatchIn(t)) {
            return if (PhoneControl.launch(ctx, Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA))) "Video camera ready." else "I couldn't open the video camera."
        }
        if (Regex("\\b(take a (photo|picture|pic)|open (the )?camera|camera)\\b").containsMatchIn(t)) return PhoneControl.camera(ctx, false)

        // ===== Navigation, search, web =====
        Regex("^(?:navigate to|directions to|take me to|drive to|route to|how do i get to) (.+)$").find(t)?.let {
            val q = it.groupValues[1]
            val ok = PhoneControl.launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(q))))
                || PhoneControl.launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))
            return if (ok) "Starting directions to $q." else "No maps app is installed."
        }
        Regex("^(?:find|show me|where is|where's) (.+?)(?: on (?:the )?map| near me| nearby)$|^(?:nearest|closest) (.+)$").find(t)?.let {
            val q = it.groupValues[1].ifBlank { it.groupValues[2] }
            return if (PhoneControl.launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))) "Showing $q on the map." else "No maps app is installed."
        }
        Regex("^(?:open|go to|visit) ([a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|net|org|ph|io|gov|edu|co|app|dev|ai)(?:\\.[a-z]{2})?)$").find(t)?.let {
            return if (PhoneControl.openUrl(ctx, "https://" + it.groupValues[1])) "Opening ${it.groupValues[1]}." else "I couldn't open a browser."
        }
        Regex("^(?:search (?:the web |google )?for|search|google|look up|i-search) (.+)$").find(t)?.let {
            val q = it.groupValues[1]
            if (q.startsWith("app") || q.startsWith("my apps")) return@let
            return if (PhoneControl.openUrl(ctx, "https://www.google.com/search?q=" + Uri.encode(q))) "Searching for $q." else "I couldn't open a browser."
        }

        // ===== Phone control (Accessibility) =====
        if (Regex("^(go )?(to )?(the )?home( screen)?$|^go home$|^minimi[sz]e").containsMatchIn(t)) return PhoneControl.global(ctx, AccessibilityService.GLOBAL_ACTION_HOME, "Going home.")
        if (Regex("^(go )?back$|^press back$|^go back$").containsMatchIn(t)) return PhoneControl.global(ctx, AccessibilityService.GLOBAL_ACTION_BACK, "Back.")
        if (Regex("\\b(recent apps|recents|app switcher|multitask|open apps)\\b").containsMatchIn(t)) return PhoneControl.global(ctx, AccessibilityService.GLOBAL_ACTION_RECENTS, "Here are your recent apps.")
        if (Regex("^(switch|switch apps|switch app|last app|previous app|go to (the )?last app)$").containsMatchIn(t)) {
            val svc = JarvisAccessibilityService.instance ?: return PhoneControl.needAccessibility(ctx)
            svc.switchToLastApp(); return "Switching."
        }
        if (Regex("\\b(notification (panel|shade|bar)|pull down notifications|open notifications|show notifications)\\b").containsMatchIn(t)) return PhoneControl.global(ctx, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notifications open.")
        if (Regex("\\b(quick settings|control (center|centre))\\b").containsMatchIn(t)) return PhoneControl.global(ctx, AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings open.")
        if (Regex("\\block (the )?(screen|phone|device)\\b|^lock$").containsMatchIn(t)) return PhoneControl.lockScreen(ctx)
        if (Regex("\\b(screenshot|screen shot|capture (the )?screen)\\b").containsMatchIn(t)) return PhoneControl.screenshot(ctx)
        if (Regex("\\b(power menu|power options|turn off (the )?phone|restart (the )?phone|shut ?down)\\b").containsMatchIn(t)) return PhoneControl.global(ctx, AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, "Here's the power menu. Pick an option to confirm.")
        if (Regex("\\bsplit screen\\b").containsMatchIn(t)) return PhoneControl.global(ctx, AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN, "Split screen.")
        if (Regex("^scroll (down|up)$|^swipe (up|down)$").containsMatchIn(t)) {
            val svc = JarvisAccessibilityService.instance ?: return PhoneControl.needAccessibility(ctx)
            val down = t.contains("scroll down") || t.contains("swipe up")
            return if (svc.scroll(down)) "" else "That screen didn't scroll."
        }
        if (Regex("^swipe (left|right)$|^next page$|^previous page$").containsMatchIn(t)) {
            val svc = JarvisAccessibilityService.instance ?: return PhoneControl.needAccessibility(ctx)
            return if (svc.swipe(t.contains("left") || t == "next page")) "" else "That didn't swipe."
        }
        Regex("^(?:tap|click|press|select|tap on|click on) (.+)$").find(t)?.let {
            val svc = JarvisAccessibilityService.instance ?: return PhoneControl.needAccessibility(ctx)
            return if (svc.clickText(it.groupValues[1])) "" else "I can't find \"${it.groupValues[1]}\" on the screen."
        }
        Regex("^(?:type|write|enter|input) (.+)$").find(t)?.let {
            val svc = JarvisAccessibilityService.instance ?: return PhoneControl.needAccessibility(ctx)
            val original = input.trim().replace(Regex("^(?i)(type|write|enter|input)\\s+"), "")
            return if (svc.typeText(original)) "Typed." else "There's no text box selected. Tap one first."
        }
        if (Regex("\\b(read (the |my )?screen|what's on (the |my )?screen|what is on (the |my )?screen)\\b").containsMatchIn(t)) {
            val svc = JarvisAccessibilityService.instance ?: return PhoneControl.needAccessibility(ctx)
            val text = svc.readScreen()
            return if (text.isBlank()) "I don't see any readable text on the screen." else text
        }

        // ===== Apps =====
        Regex("^(?:do i have|is there|search (?:my )?apps? for|find (?:the )?app|search app) (.+?)(?: installed| app)?$").find(t)?.let {
            return PhoneControl.searchApps(ctx, it.groupValues[1])
        }
        Regex("^(?:close|exit|quit) (.+)$").find(t)?.let {
            return PhoneControl.global(ctx, AccessibilityService.GLOBAL_ACTION_HOME, "Closed. Android keeps apps paused in the background; I sent you home.")
        }
        Regex("^(?:open|launch|start|run|switch to|go to|buksan) (?:the |my )?(.+?)(?: app| application| game)?$").find(t)?.let {
            return PhoneControl.openApp(ctx, it.groupValues[1])
        }

        // ===== Math =====
        MathEval.tryAnswer(t)?.let { return it }

        // ===== Offline conversation =====
        if (Prefs.mode == "online" && JarvisCore.isOnline()) return null
        return OfflineChat.reply(ctx, t)
    }

    /** Opens the main screen to ask for call-screening role and call permissions. */
    private fun requestCallSetup(ctx: Context) {
        val i = android.content.Intent(ctx, MainActivity::class.java)
            .setAction(MainActivity.ACTION_CALL_SETUP)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        PhoneControl.launch(ctx, i)
    }

    /** Explains what Jarvis can do. */
    fun help(): String =
        "I can answer your calls when you're busy, call you with reminders, look through the camera and tell you what something is, keep your to-do list, brief you on your day, open apps, call and text with your confirmation, set alarms, timers and reminders, control volume, brightness, " +
            "flashlight and Do Not Disturb, play music, navigate, take screenshots, scroll and tap for you, read notifications, " +
            "run routines like gaming mode, and remember things. Online, I can answer almost anything."

    @Suppress("unused")
    private fun hourNow() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
}
