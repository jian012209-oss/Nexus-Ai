package com.jarvis.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject

/**
 * Schedules everything time-based: JARVIS calling you, task reminders
 * and the daily briefing. Uses exact alarms when Android allows them.
 */
object JarvisScheduler {

    const val ACTION_CALL = "com.jarvis.ai.ALARM_CALL"
    const val ACTION_TASK = "com.jarvis.ai.ALARM_TASK"
    const val ACTION_BRIEFING = "com.jarvis.ai.ALARM_BRIEFING"
    const val EXTRA_REASON = "reason"
    const val EXTRA_ID = "id"

    private fun alarms(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pending(ctx: Context, action: String, id: Long, extras: Intent.() -> Unit = {}): PendingIntent {
        val i = Intent(ctx, JarvisAlarmReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id).apply(extras)
        return PendingIntent.getBroadcast(ctx, (id % Int.MAX_VALUE).toInt(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun setAt(ctx: Context, at: Long, pi: PendingIntent) {
        val am = alarms(ctx)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    // ---------- JARVIS calls you ----------

    fun scheduleCall(ctx: Context, at: Long, reason: String): Long {
        val id = System.currentTimeMillis()
        Prefs.saveScheduledCalls(Prefs.scheduledCalls() + JSONObject().put("id", id).put("at", at).put("reason", reason))
        setAt(ctx, at, pending(ctx, ACTION_CALL, id) { putExtra(EXTRA_REASON, reason) })
        return id
    }

    fun callDone(id: Long) {
        Prefs.saveScheduledCalls(Prefs.scheduledCalls().filter { it.optLong("id") != id })
    }

    fun cancelAllCalls(ctx: Context): Int {
        val list = Prefs.scheduledCalls()
        list.forEach { alarms(ctx).cancel(pending(ctx, ACTION_CALL, it.optLong("id"))) }
        Prefs.saveScheduledCalls(emptyList())
        return list.size
    }

    // ---------- Task reminders ----------

    fun scheduleTask(ctx: Context, t: Tasks.Task) {
        setAt(ctx, t.at, pending(ctx, ACTION_TASK, t.id) { putExtra(EXTRA_REASON, t.text) })
    }

    fun cancelTask(ctx: Context, id: Long) {
        alarms(ctx).cancel(pending(ctx, ACTION_TASK, id))
    }

    // ---------- Daily briefing ----------

    private const val BRIEFING_ID = 777L

    fun scheduleBriefing(ctx: Context) {
        val pi = pending(ctx, ACTION_BRIEFING, BRIEFING_ID)
        alarms(ctx).cancel(pi)
        if (!Prefs.briefingEnabled) return
        setAt(ctx, Tasks.nextOccurrence(Prefs.briefingHour, Prefs.briefingMinute), pi)
    }

    /** After a reboot alarms are lost, so put them back. */
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val calls = Prefs.scheduledCalls()
        calls.filter { it.optLong("at") > now }.forEach { c ->
            setAt(ctx, c.optLong("at"), pending(ctx, ACTION_CALL, c.optLong("id")) { putExtra(EXTRA_REASON, c.optString("reason")) })
        }
        Prefs.saveScheduledCalls(calls.filter { it.optLong("at") > now })
        Tasks.all(ctx).filter { !it.done && it.at > now }.forEach { scheduleTask(ctx, it) }
        scheduleBriefing(ctx)
    }
}
