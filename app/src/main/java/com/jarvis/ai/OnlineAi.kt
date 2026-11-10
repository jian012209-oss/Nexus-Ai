package com.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Online AI engine. Talks directly to the AI provider with your own key,
 * so there's no backend server to run or pay for.
 * - Picks a fast or smart model depending on the request.
 * - Turns on live web search for real-time questions (weather, news, prices).
 */
object OnlineAi {

    data class Answer(val text: String, val model: String, val usedWeb: Boolean)

    private val realtime = Regex(
        "\\b(weather|forecast|rain|news|today|tonight|tomorrow|latest|current|right now|price|cost|score|who won|" +
            "stock|exchange rate|peso|dollar|traffic|update|happening|trending|this week|schedule|open now|near)\\b",
        RegexOption.IGNORE_CASE
    )

    private val complex = Regex(
        "\\b(explain|code|program|debug|analy[sz]e|essay|write|compare|solve|translate|summari[sz]e|why|how does|" +
            "plan|story|step by step|calculate|proof|recipe|review|kotlin|python|java)\\b",
        RegexOption.IGNORE_CASE
    )

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, MMMM d, yyyy h:mm a", Locale.US).format(Date())
        val memories = Prefs.memories.joinToString("; ").ifBlank { "nothing yet" }
        val style = when (Prefs.personality) {
            "friendly" -> "warm, upbeat and encouraging"
            "witty" -> "dry, clever and a little playful, like JARVIS"
            else -> "calm, precise and professional"
        }
        val lang = when (Prefs.language) {
            "fil-PH" -> "The user prefers Filipino. Answer in Filipino or Taglish unless they write in English."
            "en-PH" -> "The user is in the Philippines. Match the language they use, English, Filipino or Taglish."
            else -> "Answer in the language the user uses."
        }
        return """
You are J.A.R.V.I.S., a calm, loyal and quietly witty British AI assistant in the style of Tony Stark's JARVIS, living on the user's Android phone. Your tone is $style.
Address the user as "${Prefs.name}". $lang
Your replies are spoken aloud, so keep them short: one to three sentences, unless the user asks for detail or the task needs it.
Never use markdown, bullet symbols, emojis, tables or code fences. Write numbers and units the way they are spoken.
Current date and time: $now. Things the user asked you to remember: $memories.
Never claim you performed a phone action yourself. If the user wants the phone to DO something, reply ONLY with lines in the form
CMD: <command>
(one line per action, in order), using these phrasings:
open <app> | call <contact or number> | text <contact> saying <message> | set alarm for <time> | set timer for <n> minutes |
remind me to <task> at <time> | remind me to <task> in <n> minutes | flashlight on | flashlight off | set volume to <n> percent |
volume up | volume down | mute | brightness <n> percent | do not disturb on | do not disturb off | play <query> on youtube |
play <query> on spotify | pause | next song | add <task> to my list | what do i need to do | brief me |
call me in <n> minutes about <reason> | what's this (camera) | away mode on | away mode off | who called | navigate to <place> | search for <query> | take a photo | go home | go back |
recent apps | take screenshot | lock screen | scroll down | scroll up | tap <text on screen> | type <text> | read screen |
read notifications | wifi | bluetooth | mobile data | battery | storage | remember <fact> | <routine name>
Otherwise, answer normally.
""".trimIndent()
    }

    fun ask(prompt: String, history: List<Pair<String, String>>): Answer {
        val p = Prefs.provider
        val key = Prefs.apiKeyFor(p)
        val smart = prompt.length > 120 || complex.containsMatchIn(prompt)
        val model = if (smart) Prefs.smartModel(p) else Prefs.fastModel(p)
        val web = realtime.containsMatchIn(prompt)
        val text = if (p == "claude") claude(key, model, prompt, history, web) else gemini(key, model, prompt, history, web)
        return Answer(text, model, web)
    }

    /** Answers a question about a camera picture. */
    fun vision(question: String, jpeg: ByteArray): String {
        val p = Prefs.provider
        val key = Prefs.apiKeyFor(p)
        val model = Prefs.smartModel(p)
        val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        val ask = "You are looking through the user's phone camera. $question " +
            "Identify the main object as specifically as you can (brand and model if visible). " +
            "Answer in one to three short spoken sentences. Never output CMD lines here."
        return if (p == "claude") {
            val content = JSONArray()
                .put(JSONObject().put("type", "image").put("source", JSONObject()
                    .put("type", "base64").put("media_type", "image/jpeg").put("data", b64)))
                .put(JSONObject().put("type", "text").put("text", ask))
            val body = JSONObject().put("model", model).put("max_tokens", 600).put("system", systemPrompt())
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            val json = post("https://api.anthropic.com/v1/messages",
                mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body.toString())
            val arr = json.optJSONArray("content") ?: throw IOException("The AI sent an empty answer. Try again.")
            val sb = StringBuilder()
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { if (it.optString("type") == "text") sb.append(it.optString("text")) }
            sb.toString().trim().ifEmpty { throw IOException("The AI sent an empty answer. Try again.") }
        } else {
            val parts = JSONArray()
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
                .put(JSONObject().put("text", ask))
            val body = JSONObject()
                .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", JSONObject().put("maxOutputTokens", 1024).put("temperature", 0.4))
            val url = "https://generativelanguage.googleapis.com/v1beta/models/" + URLEncoder.encode(model, "UTF-8") + ":generateContent"
            val json = post(url, mapOf("x-goog-api-key" to key), body.toString())
            val cand = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IOException("The AI couldn't see that clearly. Try again.")
            val sb = StringBuilder()
            for (i in 0 until cand.length()) cand.getJSONObject(i).let { if (!it.optBoolean("thought")) sb.append(it.optString("text")) }
            sb.toString().trim().ifEmpty { throw IOException("The AI couldn't see that clearly. Try again.") }
        }
    }

    private fun gemini(key: String, model: String, prompt: String, history: List<Pair<String, String>>, web: Boolean): String {
        val contents = JSONArray()
        history.forEach { (role, text) ->
            contents.put(JSONObject()
                .put("role", if (role == "assistant") "model" else "user")
                .put("parts", JSONArray().put(JSONObject().put("text", text))))
        }
        contents.put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt))))

        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", 2048).put("temperature", 0.7))
        if (web) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))

        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            URLEncoder.encode(model, "UTF-8") + ":generateContent"
        val json = post(url, mapOf("x-goog-api-key" to key), body.toString())

        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val blocked = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw IOException(if (blocked.isNotBlank()) "The AI declined to answer that." else "The AI sent an empty answer. Try again.")
        }
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: throw IOException("The AI sent an empty answer. Try again.")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (!part.optBoolean("thought", false)) sb.append(part.optString("text"))
        }
        val out = sb.toString().trim()
        if (out.isEmpty()) throw IOException("The AI sent an empty answer. Try again.")
        return out
    }

    private fun claude(key: String, model: String, prompt: String, history: List<Pair<String, String>>, web: Boolean): String {
        val messages = JSONArray()
        history.forEach { (role, text) -> messages.put(JSONObject().put("role", role).put("content", text)) }
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", systemPrompt())
            .put("messages", messages)
        if (web) {
            body.put("tools", JSONArray().put(JSONObject()
                .put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 3)))
        }
        val json = post("https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body.toString())
        val content = json.optJSONArray("content") ?: throw IOException("The AI sent an empty answer. Try again.")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        val out = sb.toString().trim()
        if (out.isEmpty()) throw IOException("The AI sent an empty answer. Try again.")
        return out
    }

    private fun post(url: String, headers: Map<String, String>, body: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(text).optJSONObject("error")?.optString("message").orEmpty()
                } catch (_: Exception) { "" }
                throw IOException(when (code) {
                    400 -> if (msg.contains("API key", true)) "Your AI key looks wrong. Check it in Settings." else "The AI rejected the request. ${msg.take(160)}"
                    401, 403 -> "Your AI key was rejected. Check it in Settings."
                    404 -> "That AI model wasn't found. Check the model names in Settings."
                    429 -> "The AI is busy or your free limit is used up. Try again in a minute."
                    in 500..599 -> "The AI service is having trouble. Try again shortly."
                    else -> "AI error $code. ${msg.take(160)}"
                })
            }
            return JSONObject(text)
        } catch (e: UnknownHostException) {
            throw IOException("I can't reach the AI. Check your internet connection.")
        } catch (e: SocketTimeoutException) {
            throw IOException("The AI took too long to answer. Try again.")
        } finally {
            conn.disconnect()
        }
    }
}
