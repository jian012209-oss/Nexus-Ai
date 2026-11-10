package com.jarvis.ai

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Your to-do list and the daily briefing.
 * Tasks are stored on the phone. A task with a time gets a notification when it's due.
 */
object Tasks {

    data class Task(val id: Long, val text: String, val at: Long, val done: Boolean) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("at", at).put("done", done)
        fun timeLabel(): String = if (at > 0) SimpleDateFormat("h:mm a", Locale.US).format(Date(at)) else ""
    }

    private fun parse(o: JSONObject) = Task(o.optLong("id"), o.optString("text"), o.optLong("at"), o.optBoolean("done"))

    fun all(ctx: Context): List<Task> {
        Prefs.init(ctx)
        val raw = ctx.getSharedPreferences("jarvis_tasks", Context.MODE_PRIVATE).getString("tasks", "[]") ?: "[]"
        val a = JSONArray(raw)
        return (0 until a.length()).map { parse(a.getJSONObject(it)) }
    }

    private fun save(ctx: Context, list: List<Task>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        ctx.getSharedPreferences("jarvis_tasks", Context.MODE_PRIVATE).edit().putString("tasks", a.toString()).apply()
    }

    /** Tasks for today: undone ones without a time, plus anything due today. */
    fun today(ctx: Context): List<Task> {
        val start = startOfDay(0)
        val end = startOfDay(1)
        return all(ctx).filter { (it.at == 0L && !it.done) || (it.at in start until end) }
            .sortedWith(compareBy({ it.done }, { if (it.at == 0L) Long.MAX_VALUE else it.at }))
    }

    fun add(ctx: Context, text: String, at: Long): Task {
        val t = Task(System.currentTimeMillis(), text.trim().replaceFirstChar { it.uppercase() }, at, false)
        save(ctx, all(ctx) + t)
        if (at > System.currentTimeMillis()) JarvisScheduler.scheduleTask(ctx, t)
        return t
    }

    fun setDone(ctx: Context, id: Long, done: Boolean) {
        save(ctx, all(ctx).map { if (it.id == id) it.copy(done = done) else it })
        if (done) JarvisScheduler.cancelTask(ctx, id)
    }

    fun remove(ctx: Context, id: Long) {
        save(ctx, all(ctx).filter { it.id != id })
        JarvisScheduler.cancelTask(ctx, id)
    }

    fun clearDone(ctx: Context) = save(ctx, all(ctx).filter { !it.done })

    /** Finds a task by spoken words ("call mum"). */
    fun find(ctx: Context, words: String): Task? {
        val w = words.lowercase(Locale.US).trim()
        val list = all(ctx).filter { !it.done }
        return list.firstOrNull { it.text.lowercase(Locale.US) == w }
            ?: list.firstOrNull { it.text.lowercase(Locale.US).contains(w) }
            ?: list.firstOrNull { w.contains(it.text.lowercase(Locale.US)) }
    }

    fun startOfDay(offsetDays: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, offsetDays)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Converts an hour/minute into the next time it happens (today or tomorrow). */
    fun nextOccurrence(hour: Int, minute: Int, tomorrow: Boolean = false): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (tomorrow) c.add(Calendar.DAY_OF_YEAR, 1)
        else if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** Today's events from the phone's calendar (needs Calendar permission). */
    fun calendarToday(ctx: Context): List<String> {
        if (!PhoneControl.has(ctx, Manifest.permission.READ_CALENDAR)) return emptyList()
        val out = mutableListOf<String>()
        try {
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, System.currentTimeMillis())
            ContentUris.appendId(builder, startOfDay(1))
            ctx.contentResolver.query(builder.build(),
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
                null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext() && out.size < 6) {
                    val title = c.getString(0) ?: continue
                    val allDay = c.getInt(2) == 1
                    out += if (allDay) title else "$title at ${SimpleDateFormat("h:mm a", Locale.US).format(Date(c.getLong(1)))}"
                }
            }
        } catch (_: Exception) { }
        return out
    }

    /** Spoken summary of what you need to do. */
    fun todaySummary(ctx: Context): String {
        val tasks = today(ctx).filter { !it.done }
        val events = calendarToday(ctx)
        val parts = mutableListOf<String>()
        if (tasks.isNotEmpty()) {
            val items = tasks.take(6).joinToString(", ") { if (it.at > 0) "${it.text} at ${it.timeLabel()}" else it.text }
            parts += "You've got ${tasks.size} thing${if (tasks.size > 1) "s" else ""} to do: $items."
        }
        if (events.isNotEmpty()) parts += "On your calendar: ${events.joinToString(", ")}."
        if (parts.isEmpty()) parts += "Your list is clear today."
        return parts.joinToString(" ")
    }

    /** Offline part of the morning briefing. The weather is added by JarvisCore when online. */
    fun briefingBase(ctx: Context): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when (h) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }
        val date = SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date())
        return "$part, ${Prefs.name}. It's $date. ${todaySummary(ctx)} ${PhoneControl.battery(ctx)}"
    }
}
