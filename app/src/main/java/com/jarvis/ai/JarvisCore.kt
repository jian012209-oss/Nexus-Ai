package com.jarvis.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

/**
 * JARVIS's core: voice engine (speech in/out), hybrid brain routing,
 * confirmations for sensitive actions, and the automation runner.
 */
object JarvisCore {

    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    interface Listener {
        fun onState(state: State) {}
        fun onMessage(fromUser: Boolean, text: String) {}
        fun onPartial(text: String) {}
        fun onLevel(level: Float) {}
        fun onContinuousChanged(on: Boolean) {}
        fun onRecentChanged() {}
        fun onCallEnded() {}
        fun onVisionRequest(question: String) {}
    }

    private lateinit var app: Context
    private var initialized = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listeners = mutableSetOf<Listener>()

    /** Conversation on screen: (fromUser, text). Survives going offline and back online. */
    val history = mutableListOf<Pair<Boolean, String>>()

    var state = State.IDLE
        private set
    var continuous = false
        private set

    private var listening = false
    private var awaitingCommand = false
    private var followUpUntil = 0L
    private var listenAfterSpeech = false
    private var lastFromVoice = false
    private var pending: (() -> String)? = null

    // Vision
    var visionActive = false
    private var lastImage: ByteArray? = null

    // "JARVIS calls you" conversation
    var callMode = false
        private set
    private var callSilence = 0
    private var endAfterSpeech = false

    // Real phone calls (call assistant)
    private var callPaused = false
    private var callSpeechDone: (() -> Unit)? = null

    private var wakeRegex = Regex("^$")

    // ---- Silencing the speech engine's start/stop beeps ----
    private var beepsMuted = false
    private var musicMuted = false
    private val beepStreams = intArrayOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION)

    private fun audio() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun adjust(am: AudioManager, stream: Int, mute: Boolean) {
        try {
            am.adjustStreamVolume(stream, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: Exception) { }
    }

    /** Mutes the beep Android plays every time listening starts or stops. */
    private fun muteBeeps() {
        if (!Prefs.silentListening) return
        val am = audio()
        if (!beepsMuted) {
            beepStreams.forEach { adjust(am, it, true) }
            beepsMuted = true
            Prefs.soundMutedByJarvis = true
        }
        // Never silence music you're actually listening to.
        if (am.isMusicActive) {
            if (musicMuted) { adjust(am, AudioManager.STREAM_MUSIC, false); musicMuted = false }
        } else if (!musicMuted) {
            adjust(am, AudioManager.STREAM_MUSIC, true)
            musicMuted = true
        }
    }

    /** Restores normal sound (before JARVIS speaks, and whenever listening ends). */
    private fun unmuteBeeps() {
        val am = audio()
        if (beepsMuted) { beepStreams.forEach { adjust(am, it, false) }; beepsMuted = false }
        if (musicMuted) { adjust(am, AudioManager.STREAM_MUSIC, false); musicMuted = false }
        Prefs.soundMutedByJarvis = false
    }

    /** Safety net: if the app was closed while muted, sound comes back on next start. */
    private fun restoreAllSound() {
        if (!Prefs.soundMutedByJarvis) return
        Prefs.soundMutedByJarvis = false
        val am = audio()
        beepStreams.forEach { adjust(am, it, false) }
        adjust(am, AudioManager.STREAM_MUSIC, false)
    }

    const val EVENTS_CHANNEL = "jarvis_events"

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        Prefs.init(app)
        rebuildWakeWord()
        restoreAllSound()
        history.addAll(Prefs.loadChat())
        ensureChannel(app, EVENTS_CHANNEL, "Routines and alerts", NotificationManager.IMPORTANCE_DEFAULT)
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                applyVoice()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { main.post { setState(State.SPEAKING) } }
                    override fun onDone(utteranceId: String?) { main.post { speechFinished(utteranceId) } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { main.post { speechFinished(utteranceId) } }
                })
            }
        }
    }

    fun ensureChannel(ctx: Context, id: String, name: String, importance: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(id) == null) nm.createNotificationChannel(NotificationChannel(id, name, importance))
    }

    // ---------------- Voice settings ----------------

    fun applyVoice() {
        val t = tts ?: return
        val locale = Locale.forLanguageTag(Prefs.language)
        val r = t.setLanguage(locale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.US)
        val wanted = Prefs.voiceName
        try {
            val voices = t.voices
            val chosen = when {
                wanted.isNotBlank() -> voices?.firstOrNull { it.name == wanted }
                Prefs.jarvisVoice && !Prefs.language.startsWith("fil") -> pickJarvisVoice(voices)
                else -> null
            }
            if (chosen != null) t.setVoice(chosen)
            else if (Prefs.jarvisVoice && !Prefs.language.startsWith("fil")) t.setLanguage(Locale.UK)
        } catch (_: Exception) { }
        t.setSpeechRate(Prefs.speechRate)
        t.setPitch(Prefs.pitch)
    }

    /** British male voices, closest to the film JARVIS. Local voices first so it works offline. */
    private fun pickJarvisVoice(voices: Set<android.speech.tts.Voice>?): android.speech.tts.Voice? {
        if (voices.isNullOrEmpty()) return null
        val preferred = listOf("en-gb-x-rjs-local", "en-gb-x-gbd-local", "en-gb-x-gbb-local",
            "en-gb-x-rjs-network", "en-gb-x-gbd-network", "en-gb-x-gbb-network")
        for (name in preferred) voices.firstOrNull { it.name == name }?.let { return it }
        return voices.firstOrNull { it.locale.language == "en" && it.locale.country == "GB" && !it.isNetworkConnectionRequired && it.name.contains("male", true) && !it.name.contains("female", true) }
            ?: voices.firstOrNull { it.locale.language == "en" && it.locale.country == "GB" && !it.isNetworkConnectionRequired }
    }

    /** Installed voices for the chosen language. */
    fun voiceNames(): List<String> = try {
        val lang = Locale.forLanguageTag(Prefs.language).language
        tts?.voices?.filter { it.locale.language == lang && !it.isNetworkConnectionRequired }
            ?.map { it.name }?.sorted() ?: emptyList()
    } catch (_: Exception) { emptyList() }

    fun previewVoice() {
        applyVoice()
        say("Hello ${Prefs.name}. This is how I sound.", keepListening = false)
    }

    fun rebuildWakeWord() {
        val w = Prefs.wakeWord.lowercase(Locale.US).trim()
        val alts = mutableListOf(Regex.escape(w))
        if (w == "jarvis") alts += listOf("jervis", "javis", "travis", "jarves")
        wakeRegex = Regex("(?:^|\\b(?:hey|hi|ok|okay|hoy)\\s+)(?:${alts.joinToString("|")})\\b", RegexOption.IGNORE_CASE)
    }

    // ---------------- Listeners ----------------

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private fun setState(s: State) {
        state = s
        listeners.toList().forEach { it.onState(s) }
    }

    private fun addMessage(fromUser: Boolean, text: String) {
        history.add(fromUser to text)
        while (history.size > 200) history.removeAt(0)
        Prefs.saveChat(history)
        listeners.toList().forEach { it.onMessage(fromUser, text) }
    }

    fun clearConversation() {
        history.clear()
        Prefs.saveChat(history)
    }

    fun greetingText(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when (h) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }
        return "$part, ${Prefs.name}. JARVIS is online. Tap the core to talk, or turn on \"Hey ${Prefs.wakeWord}\" and just call my name."
    }

    // ---------------- Network / modes ----------------

    fun hasInternet(): Boolean {
        if (!initialized) return false
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** True when the cloud AI may be used right now. */
    fun isOnline(): Boolean = Prefs.mode != "offline" && hasInternet()

    // ---------------- Listening ----------------

    /** Tap-to-talk. Also interrupts speech. */
    fun listenOnce() {
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            reply("Speech recognition isn't available on this phone. Install or update the Google app, then try again.")
            return
        }
        tts?.stop()
        listenAfterSpeech = false
        awaitingCommand = true
        if (listening) { setState(State.LISTENING); return }
        startRecognizer()
    }

    fun cancelListening() {
        stopRecognizer()
        if (!continuous) unmuteBeeps()
        awaitingCommand = false
        listeners.toList().forEach { it.onPartial("") }
        setState(State.IDLE)
        if (continuous) main.postDelayed({ restartIfContinuous() }, 500)
    }

    fun setContinuous(on: Boolean) {
        if (continuous == on) return
        continuous = on
        listeners.toList().forEach { it.onContinuousChanged(on) }
        if (on) {
            if (state == State.IDLE) startRecognizer()
        } else {
            stopRecognizer()
            awaitingCommand = false
            unmuteBeeps()
            if (state == State.LISTENING) setState(State.IDLE)
        }
    }

    private fun phoneInCall(): Boolean {
        val m = audio().mode
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE
    }

    private fun restartIfContinuous() {
        if (!continuous || listening || state == State.SPEAKING || state == State.THINKING) return
        // Don't fight the phone app for the microphone during a call.
        if (callPaused || phoneInCall()) { main.postDelayed({ restartIfContinuous() }, 4000); return }
        startRecognizer()
    }

    /** Called by the call assistant while it handles a real phone call. */
    fun pauseForCall(on: Boolean) {
        callPaused = on
        if (on) { stopRecognizer(); unmuteBeeps(); tts?.stop() }
        else main.postDelayed({ restartIfContinuous() }, 1500)
    }

    private fun startRecognizer() {
        if (listening) return
        val r = recognizer ?: try {
            SpeechRecognizer.createSpeechRecognizer(app).also {
                it.setRecognitionListener(recListener)
                recognizer = it
            }
        } catch (e: Exception) {
            reply("I couldn't start the microphone. ${e.message ?: ""}")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            if (!hasInternet() || Prefs.mode == "offline") putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        listening = true
        setState(State.LISTENING)
        muteBeeps()
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            listening = false
            setState(State.IDLE)
        }
    }

    private fun stopRecognizer() {
        if (listening) {
            listening = false
            try { recognizer?.cancel() } catch (_: Exception) { }
        }
    }

    private val recListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            listeners.toList().forEach { it.onLevel(level) }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank() && (awaitingCommand || !continuous || wakeRegex.containsMatchIn(t))) {
                listeners.toList().forEach { it.onPartial(t) }
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            listeners.toList().forEach { it.onPartial("") }
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (text.isEmpty()) { handleError(SpeechRecognizer.ERROR_NO_MATCH); return }
            onHeard(text)
        }

        override fun onError(error: Int) {
            listening = false
            listeners.toList().forEach { it.onPartial("") }
            handleError(error)
        }
    }

    private fun handleError(error: Int) {
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            if (continuous) { setContinuous(false); ListenService.stop(app) }
            if (callMode) endAfterSpeech = true
            reply("I need microphone permission to hear you. Open Permissions from the menu.")
            return
        }
        if (callMode) {
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                callSilence++
                if (callSilence >= 3) {
                    endAfterSpeech = true
                    addMessage(false, "I'll let you go. Goodbye, ${Prefs.name}.")
                    say("I'll let you go. Goodbye, ${Prefs.name}.", keepListening = false)
                } else {
                    awaitingCommand = true
                    main.postDelayed({ startRecognizer() }, 300)
                }
            } else {
                main.postDelayed({ if (callMode) { awaitingCommand = true; startRecognizer() } }, 1500)
            }
            return
        }
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            if (pending == null) awaitingCommand = false
        }
        if (continuous) {
            val wait = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> 900L
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> 2500L
                else -> 250L
            }
            if (state == State.LISTENING) setState(State.IDLE)
            main.postDelayed({ restartIfContinuous() }, wait)
            return
        }
        awaitingCommand = false
        unmuteBeeps()
        setState(State.IDLE)
        when (error) {
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                reply("I couldn't reach the speech service. For voice without internet, download your language under Google, Offline speech recognition.")
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                reply("That speech language isn't available offline. Download it in Google speech settings or connect to the internet.")
            else -> {}
        }
    }

    private fun onHeard(raw: String) {
        lastFromVoice = true
        val inFollowUp = SystemClock.elapsedRealtime() < followUpUntil
        if (continuous && !awaitingCommand && !inFollowUp) {
            val m = wakeRegex.find(raw)
            if (m == null) {
                setState(State.IDLE)
                main.postDelayed({ restartIfContinuous() }, 150)
                return
            }
            val rest = raw.substring(m.range.last + 1).trim(' ', ',', '.', '!', '?')
            if (rest.isEmpty()) {
                addMessage(true, raw)
                awaitingCommand = true
                val ack = listOf("Yes, ${Prefs.name}?", "At your service.", "I'm listening.", "Go ahead, ${Prefs.name}.").random()
                addMessage(false, ack)
                say(ack, keepListening = true)
                return
            }
            handle(rest, raw)
        } else {
            handle(stripWake(raw), raw)
        }
    }

    private fun stripWake(text: String): String = wakeRegex.replace(text, "").trim(' ', ',', '.', '!', '?')

    // ---------------- Brain routing ----------------

    /** Typed in the chat box. */
    fun handleText(text: String) {
        lastFromVoice = false
        tts?.stop()
        stopRecognizer()
        handle(stripWake(text), text)
    }

    /** Quick-control buttons: runs a command without adding it to recents. */
    fun quick(command: String) {
        lastFromVoice = false
        tts?.stop()
        stopRecognizer()
        val r = safeProcess(command)
        if (r != null && r != CommandEngine.ASYNC) reply(r)
    }

    fun requestConfirm(action: () -> String) { pending = action }

    private val yes = Regex("^(yes|yeah|yep|yup|sure|confirm|confirmed|do it|go ahead|send it|call|ok|okay|oo|opo|sige|correct|affirmative)\\b.*", RegexOption.IGNORE_CASE)
    private val no = Regex("^(no|nope|cancel|stop|don't|do not|never mind|hindi|wag|huwag|abort)\\b.*", RegexOption.IGNORE_CASE)

    private fun safeProcess(cmd: String): String? = try {
        CommandEngine.process(app, cmd)
    } catch (e: SecurityException) {
        "Android blocked that action. Check Permissions in the menu."
    } catch (e: Exception) {
        "That command failed: ${e.message ?: "unknown error"}."
    }

    private fun handle(command: String, display: String) {
        awaitingCommand = false
        addMessage(true, display)

        pending?.let { action ->
            pending = null
            val c = command.trim()
            if (yes.matches(c)) {
                val result = try { action() } catch (e: Exception) { "That failed: ${e.message ?: "unknown error"}." }
                reply(result); return
            }
            if (no.matches(c)) { reply("Cancelled."); return }
        }

        if (callMode) {
            callSilence = 0
            if (Regex("^(bye|goodbye|good bye|that's all|thats all|that is all|end call|hang up|thanks,? bye|thank you,? bye|nothing|no that's it|that's it)\\b", RegexOption.IGNORE_CASE).containsMatchIn(command.trim())) {
                endAfterSpeech = true
                reply(listOf("Goodbye, ${Prefs.name}.", "Very well. Talk soon, ${Prefs.name}.", "Always a pleasure. Goodbye.").random())
                return
            }
        }

        if (visionActive) {
            val c = command.lowercase(Locale.US).trim()
            if (Regex("^(what('s| is) (this|that|it)|what am i holding|look( at (this|that))?|scan( this)?|what about (this|now|that)|and this|identify|read (this|that|it)|what do you see|tingnan mo)").containsMatchIn(c)) {
                listeners.toList().forEach { it.onVisionRequest(command) }
                return
            }
            if (lastImage != null && !Regex("^(open|close|call|text|set|turn|go|lock|play|pause|stop|exit|take|volume|flashlight|remind)\\b").containsMatchIn(c)) {
                askVision(command, null)
                return
            }
        }

        Prefs.addRecent(command.ifBlank { display }.trim())
        listeners.toList().forEach { it.onRecentChanged() }

        val steps = CommandEngine.splitChain(command)
        if (steps.size > 1) {
            runSequence(app, steps, intro = null, notifyTitle = null)
            return
        }
        val result = safeProcess(command)
        when {
            result == CommandEngine.ASYNC -> {}
            result != null -> reply(result)
            else -> askAi(command)
        }
    }

    /** Automation engine: runs steps in order and reports each result honestly. */
    fun runSequence(ctx: Context, steps: List<String>, intro: String?, notifyTitle: String?) {
        stopRecognizer()
        setState(State.THINKING)
        scope.launch {
            val results = mutableListOf<String>()
            intro?.let { results += it }
            for (step in steps) {
                val r = safeProcess(step)
                when {
                    r == null -> results += "I couldn't do \"$step\"."
                    r == CommandEngine.ASYNC -> {}
                    r.isNotBlank() -> results += r
                }
                if (pending != null) break
                val opensApp = Regex("^(open|launch|start|run|switch to)\\b", RegexOption.IGNORE_CASE).containsMatchIn(step.trim())
                delay(if (opensApp) 1800L else 350L)
            }
            if (notifyTitle != null) postEvent(ctx, "${notifyTitle.replaceFirstChar { it.uppercase() }} activated", results.drop(1).joinToString(" "))
            setState(State.IDLE)
            reply(results.joinToString(" "))
        }
    }

    private fun askAi(command: String) {
        if (Prefs.mode == "offline") {
            reply("I'm in offline mode, so I can't answer that. Phone controls, alarms, reminders, notes and math still work. Say \"hybrid mode\" to use the cloud.")
            return
        }
        if (!hasInternet()) {
            reply("I'm offline right now, so I can't look that up. Phone controls, alarms, reminders, notes and math still work.")
            return
        }
        if (Prefs.apiKey.isBlank()) {
            reply("I'm online, but no AI key is set yet. Open Settings and paste a free Gemini key. Phone commands work without it.")
            return
        }
        stopRecognizer()
        setState(State.THINKING)
        val past = history.takeLast(12).dropLast(1).map { (u, t) -> (if (u) "user" else "assistant") to t }
            .let { trimToAlternating(it) }
        scope.launch {
            val answer = try {
                withContext(Dispatchers.IO) { OnlineAi.ask(command, past) }
            } catch (e: Exception) {
                setState(State.IDLE)
                reply(e.message ?: "The AI didn't answer. Try again.")
                return@launch
            }
            val cmds = answer.text.lines().map { it.trim() }.filter { it.startsWith("CMD:", ignoreCase = true) }
                .map { it.substring(4).trim() }.filter { it.isNotEmpty() }
            setState(State.IDLE)
            if (cmds.isNotEmpty()) runSequence(app, cmds, intro = null, notifyTitle = null)
            else reply(answer.text)
        }
    }

    /** AI APIs want user/assistant turns to alternate and start with the user. */
    private fun trimToAlternating(list: List<Pair<String, String>>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (p in list) {
            if (out.isEmpty() && p.first != "user") continue
            if (out.isNotEmpty() && out.last().first == p.first) {
                out[out.size - 1] = p.first to (out.last().second + "\n" + p.second)
            } else out += p
        }
        if (out.isNotEmpty() && out.last().first == "user") out.removeAt(out.size - 1)
        return out
    }

    // ---------------- Speaking ----------------

    fun reply(text: String) {
        if (text.isBlank()) {
            tts?.stop()
            afterSpeaking()
            return
        }
        addMessage(false, text)
        val keep = (callMode && !endAfterSpeech) || (pending != null && (lastFromVoice || continuous))
        if (continuous && pending == null) followUpUntil = SystemClock.elapsedRealtime() + 9000
        say(text, keepListening = keep)
    }

    /** Spoken alerts (e.g. new notifications) that don't interrupt a command. */
    fun announce(text: String) {
        main.post {
            if (state == State.THINKING || (listening && awaitingCommand)) return@post
            reply(text)
        }
    }

    private fun say(text: String, keepListening: Boolean) {
        listenAfterSpeech = keepListening
        stopRecognizer()
        unmuteBeeps()
        val clean = text.replace(Regex("[*#`_>|]"), "").replace(Regex("\\s+"), " ").trim().take(3900)
        if (!ttsReady || tts == null) { afterSpeaking(); return }
        val res = tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, Bundle(), "jarvis-${System.nanoTime()}")
        if (res != TextToSpeech.SUCCESS) afterSpeaking()
    }

    fun stopSpeaking() {
        tts?.stop()
        listenAfterSpeech = false
        afterSpeaking()
    }

    private fun speechFinished(id: String?) {
        if (id != null && id.startsWith("callmsg")) {
            resetTtsAudio()
            val done = callSpeechDone
            callSpeechDone = null
            done?.invoke()
            return
        }
        afterSpeaking()
    }

    private fun afterSpeaking() {
        if (state == State.SPEAKING || state == State.THINKING) setState(State.IDLE)
        if (endAfterSpeech) {
            endAfterSpeech = false
            endCallConversation()
            return
        }
        if (listening) return
        when {
            listenAfterSpeech -> {
                listenAfterSpeech = false
                awaitingCommand = true
                main.postDelayed({ startRecognizer() }, 300)
            }
            continuous -> main.postDelayed({ restartIfContinuous() }, 350)
            else -> unmuteBeeps()
        }
    }

    // ---------------- Call assistant: speaking to a caller ----------------

    /** Speaks into an answered phone call (played on speaker so the caller hears it). */
    fun speakIntoCall(text: String, done: () -> Unit) {
        val t = tts
        if (!ttsReady || t == null) { done(); return }
        callSpeechDone = done
        try {
            t.setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        } catch (_: Exception) { }
        val res = t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "callmsg-${System.nanoTime()}")
        if (res != TextToSpeech.SUCCESS) { callSpeechDone = null; resetTtsAudio(); done() }
    }

    private fun resetTtsAudio() {
        try {
            tts?.setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        } catch (_: Exception) { }
    }

    // ---------------- JARVIS calls you ----------------

    fun startCallConversation(ctx: Context, reason: String) {
        callMode = true
        callSilence = 0
        endAfterSpeech = false
        stopRecognizer()
        val name = Prefs.name
        when {
            reason == IncomingCall.BRIEFING_REASON -> briefing(asCall = true)
            reason.isBlank() -> reply("Hello, $name. JARVIS here. You asked me to call. What can I do for you?")
            else -> reply("Hello, $name. JARVIS here. You asked me to remind you: $reason. Is there anything else you need?")
        }
    }

    fun endCallConversation() {
        if (!callMode) return
        callMode = false
        endAfterSpeech = false
        awaitingCommand = false
        stopRecognizer()
        tts?.stop()
        unmuteBeeps()
        setState(State.IDLE)
        listeners.toList().forEach { it.onCallEnded() }
        if (continuous) main.postDelayed({ restartIfContinuous() }, 800)
    }

    // ---------------- Daily briefing ----------------

    fun briefing(asCall: Boolean = false) {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when (h) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }
        val date = java.text.SimpleDateFormat("EEEE, MMMM d", Locale.US).format(java.util.Date())
        val head = "$part, ${Prefs.name}. It's $date."
        val tail = "${Tasks.todaySummary(app)} ${PhoneControl.battery(app)}" + if (asCall) " Anything else?" else ""
        val city = Prefs.city
        if (city.isBlank() || !isOnline() || Prefs.apiKey.isBlank()) {
            val note = if (city.isBlank()) "" else if (!isOnline()) " I can't check the weather offline." else ""
            reply("$head$note $tail")
            return
        }
        setState(State.THINKING)
        scope.launch {
            val weather = try {
                withContext(Dispatchers.IO) {
                    OnlineAi.ask("What's the weather in $city right now and the forecast for the rest of today? " +
                        "Reply with ONE short spoken sentence with the temperature in Celsius. No commands.", emptyList()).text
                }
            } catch (e: Exception) { "" }
            setState(State.IDLE)
            reply("$head ${weather.lines().firstOrNull { !it.startsWith("CMD:") }.orEmpty()} $tail")
        }
    }

    // ---------------- Vision ----------------

    /** Sends a camera snapshot to the cloud AI and speaks what it sees. */
    fun askVision(question: String, jpeg: ByteArray?) {
        if (jpeg != null) lastImage = jpeg
        val img = lastImage ?: run { reply("Point the camera at something and tap Scan."); return }
        if (!isOnline()) {
            reply("I need an internet connection to identify things, ${Prefs.name}. I'm offline right now.")
            return
        }
        if (Prefs.apiKey.isBlank()) {
            reply("Add an AI key in Settings so I can see. A free Gemini key works.")
            return
        }
        stopRecognizer()
        setState(State.THINKING)
        scope.launch {
            val answer = try {
                withContext(Dispatchers.IO) { OnlineAi.vision(question, img) }
            } catch (e: Exception) {
                setState(State.IDLE)
                reply(e.message ?: "I couldn't analyze that. Try again.")
                return@launch
            }
            setState(State.IDLE)
            reply(answer)
        }
    }

    // ---------------- Notifications ----------------

    fun postEvent(ctx: Context, title: String, text: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, EVENTS_CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(text.ifBlank { "Done." })
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.ifBlank { "Done." }))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify((System.currentTimeMillis() % 100000).toInt() + 10, n) } catch (_: SecurityException) { }
    }
}
