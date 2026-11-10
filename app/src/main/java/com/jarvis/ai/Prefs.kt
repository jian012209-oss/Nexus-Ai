package com.jarvis.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray
import org.json.JSONObject

/**
 * Memory + settings store. Everything stays on the phone.
 * API keys, memories, routines and chat history are kept in encrypted storage.
 */
object Prefs {
    private lateinit var sp: SharedPreferences
    private lateinit var secure: SharedPreferences

    fun init(context: Context) {
        if (::sp.isInitialized) return
        val c = context.applicationContext
        sp = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        secure = try {
            val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "jarvis_secure", alias, c,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            sp // Some phones have a broken keystore; fall back to private storage.
        }
    }

    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun put(key: String, v: String) = sp.edit().putString(key, v).apply()

    // ---------- Personal ----------
    var name: String
        get() = str("name", "sir")
        set(v) = put("name", v)

    var wakeWord: String
        get() = str("wake", "Jarvis")
        set(v) = put("wake", v.trim().ifBlank { "Jarvis" })

    /** en-US, en-PH, fil-PH */
    var language: String
        get() = str("language", "en-US")
        set(v) = put("language", v)

    /** professional, friendly, witty */
    var personality: String
        get() = str("personality", "professional")
        set(v) = put("personality", v)

    var voiceName: String
        get() = str("voice", "")
        set(v) = put("voice", v)

    var speechRate: Float
        get() = sp.getFloat("rate", 1.0f)
        set(v) = sp.edit().putFloat("rate", v).apply()

    var pitch: Float
        get() = sp.getFloat("pitch", 0.9f)
        set(v) = sp.edit().putFloat("pitch", v).apply()

    /** Use the British male "JARVIS" voice when available. */
    var jarvisVoice: Boolean
        get() = sp.getBoolean("jarvis_voice", true)
        set(v) = sp.edit().putBoolean("jarvis_voice", v).apply()

    // ---------- Call assistant ----------
    /** off, answer (pick up and speak), text (decline and text back) */
    var awayMode: String
        get() = str("away_mode", "off")
        set(v) = put("away_mode", v)

    /** What JARVIS tells callers. {name} is replaced with your name. */
    var awayMessage: String
        get() = str("away_msg", "Hello, this is JARVIS, {name}'s personal assistant. {name} is not available right now and will call you back later. Goodbye.")
        set(v) = put("away_msg", v)

    fun awayMessageFilled(): String = awayMessage.replace("{name}", Prefs.name.replaceFirstChar { it.uppercase() })

    /** Say who is calling when the phone rings. */
    var announceCalls: Boolean
        get() = sp.getBoolean("announce_calls", true)
        set(v) = sp.edit().putBoolean("announce_calls", v).apply()

    var callLog: List<String>
        get() = jsonList(secure.getString("call_log", "[]"))
        set(v) = secure.edit().putString("call_log", JSONArray(v.takeLast(30)).toString()).apply()

    // ---------- JARVIS calls you ----------
    /** Scheduled calls as JSON objects: {id, at, reason} */
    fun scheduledCalls(): List<JSONObject> {
        val a = JSONArray(sp.getString("sched_calls", "[]") ?: "[]")
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    fun saveScheduledCalls(list: List<JSONObject>) {
        val a = JSONArray()
        list.forEach { a.put(it) }
        sp.edit().putString("sched_calls", a.toString()).apply()
    }

    // ---------- Daily briefing ----------
    var briefingEnabled: Boolean
        get() = sp.getBoolean("brief_on", false)
        set(v) = sp.edit().putBoolean("brief_on", v).apply()

    var briefingHour: Int
        get() = sp.getInt("brief_h", 7)
        set(v) = sp.edit().putInt("brief_h", v).apply()

    var briefingMinute: Int
        get() = sp.getInt("brief_m", 0)
        set(v) = sp.edit().putInt("brief_m", v).apply()

    /** notify or call */
    var briefingStyle: String
        get() = str("brief_style", "notify")
        set(v) = put("brief_style", v)

    /** City used for weather in the briefing. */
    var city: String
        get() = str("city", "")
        set(v) = put("city", v.trim())

    // ---------- Modes ----------
    /** hybrid, online, offline */
    var mode: String
        get() = str("mode", "hybrid")
        set(v) = put("mode", v)

    var privacyMode: Boolean
        get() = sp.getBoolean("privacy", false)
        set(v) {
            sp.edit().putBoolean("privacy", v).apply()
            if (v) { saveChat(emptyList()); recentCommands = emptyList() }
        }

    /** Silence the speech engine's beeps while listening. */
    var silentListening: Boolean
        get() = sp.getBoolean("silent_listening", true)
        set(v) = sp.edit().putBoolean("silent_listening", v).apply()

    /** True while JARVIS has muted sounds, so they can be restored after a crash. */
    var soundMutedByJarvis: Boolean
        get() = sp.getBoolean("muted_by_jarvis", false)
        set(v) = sp.edit().putBoolean("muted_by_jarvis", v).apply()

    var announceNotifications: Boolean
        get() = sp.getBoolean("announce", false)
        set(v) = sp.edit().putBoolean("announce", v).apply()

    // ---------- AI providers ----------
    /** gemini or claude */
    var provider: String
        get() = str("provider", "gemini")
        set(v) = put("provider", v)

    fun apiKeyFor(p: String): String = secure.getString("key_$p", "") ?: ""
    fun setApiKey(p: String, v: String) = secure.edit().putString("key_$p", v.trim()).apply()
    val apiKey: String get() = apiKeyFor(provider)

    fun defaultFast(p: String) = if (p == "claude") "claude-haiku-5-5" else "gemini-2.5-flash-lite"
    fun defaultSmart(p: String) = if (p == "claude") "claude-sonnet-5-5" else "gemini-2.5-flash"

    fun fastModel(p: String) = sp.getString("fast_$p", null)?.takeIf { it.isNotBlank() } ?: defaultFast(p)
    fun smartModel(p: String) = sp.getString("smart_$p", null)?.takeIf { it.isNotBlank() } ?: defaultSmart(p)
    fun setModels(p: String, fast: String, smart: String) =
        sp.edit().putString("fast_$p", fast.trim()).putString("smart_$p", smart.trim()).apply()

    // ---------- Memory ----------
    var memories: List<String>
        get() = jsonList(secure.getString("memories", "[]"))
        set(v) = secure.edit().putString("memories", JSONArray(v).toString()).apply()

    var recentCommands: List<String>
        get() = jsonList(sp.getString("recent", "[]"))
        set(v) = sp.edit().putString("recent", JSONArray(v.take(8)).toString()).apply()

    fun addRecent(cmd: String) {
        if (privacyMode || cmd.isBlank()) return
        recentCommands = (listOf(cmd) + recentCommands.filter { !it.equals(cmd, true) }).take(8)
    }

    /** Frequently used apps: label -> count */
    fun appUsed(label: String) {
        val o = JSONObject(sp.getString("apps", "{}") ?: "{}")
        o.put(label, o.optInt(label, 0) + 1)
        sp.edit().putString("apps", o.toString()).apply()
    }

    fun topApps(n: Int = 5): List<String> {
        val o = JSONObject(sp.getString("apps", "{}") ?: "{}")
        return o.keys().asSequence().sortedByDescending { o.optInt(it) }.take(n).toList()
    }

    // ---------- Routines: name -> comma-separated commands ----------
    private val defaultRoutines = linkedMapOf(
        "gaming mode" to "set volume to 70 percent, do not disturb on, open mobile legends",
        "good night" to "brightness 15 percent, do not disturb on, battery",
        "good morning" to "do not disturb off, brightness 60 percent, brief me",
        "focus mode" to "do not disturb on, set volume to 20 percent"
    )

    var routines: LinkedHashMap<String, String>
        get() {
            val raw = secure.getString("routines", null) ?: return LinkedHashMap(defaultRoutines)
            val o = JSONObject(raw)
            val m = LinkedHashMap<String, String>()
            o.keys().forEach { m[it] = o.optString(it) }
            return m
        }
        set(v) = secure.edit().putString("routines", JSONObject(v as Map<*, *>).toString()).apply()

    // ---------- Chat history ----------
    fun loadChat(): List<Pair<Boolean, String>> {
        if (privacyMode) return emptyList()
        val arr = JSONArray(secure.getString("chat", "[]") ?: "[]")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.optBoolean("u") to o.optString("t")
        }
    }

    fun saveChat(list: List<Pair<Boolean, String>>) {
        val arr = JSONArray()
        if (!privacyMode) list.takeLast(60).forEach { arr.put(JSONObject().put("u", it.first).put("t", it.second)) }
        secure.edit().putString("chat", arr.toString()).apply()
    }

    /** Everything Jarvis remembers, as plain text for export. */
    fun exportAll(): String = buildString {
        appendLine("JARVIS AI — stored information")
        appendLine("Name: $name")
        appendLine("Wake word: $wakeWord")
        appendLine("Language: $language")
        appendLine("Personality: $personality")
        appendLine("Mode: $mode")
        appendLine("Frequently used apps: ${topApps().joinToString().ifBlank { "none" }}")
        appendLine()
        appendLine("Memories:")
        memories.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Routines:")
        routines.forEach { (k, v) -> appendLine("- $k: $v") }
    }

    fun deleteAllPersonal() {
        memories = emptyList()
        recentCommands = emptyList()
        saveChat(emptyList())
        sp.edit().remove("apps").remove("name").apply()
        secure.edit().remove("routines").apply()
    }

    private fun jsonList(raw: String?): List<String> = try {
        val a = JSONArray(raw ?: "[]")
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) { emptyList() }
}
