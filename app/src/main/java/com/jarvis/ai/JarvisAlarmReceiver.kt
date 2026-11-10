package com.jarvis.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Fires scheduled calls, task reminders and the morning briefing. Also restores them after reboot. */
class JarvisAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        Prefs.init(ctx)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> JarvisScheduler.rescheduleAll(ctx)

            JarvisScheduler.ACTION_CALL -> {
                val id = intent.getLongExtra(JarvisScheduler.EXTRA_ID, 0)
                JarvisScheduler.callDone(id)
                IncomingCall.ring(ctx, intent.getStringExtra(JarvisScheduler.EXTRA_REASON).orEmpty())
            }

            JarvisScheduler.ACTION_TASK -> {
                val text = intent.getStringExtra(JarvisScheduler.EXTRA_REASON).orEmpty()
                IncomingCall.notifyTask(ctx, text)
            }

            JarvisScheduler.ACTION_BRIEFING -> {
                JarvisScheduler.scheduleBriefing(ctx) // tomorrow's
                if (Prefs.briefingStyle == "call") IncomingCall.ring(ctx, IncomingCall.BRIEFING_REASON)
                else IncomingCall.notifyBriefing(ctx)
            }
        }
    }
}

/** Notifications for JARVIS calling you, task reminders and briefings. */
object IncomingCall {

    const val CALL_CHANNEL = "jarvis_calls_v1"
    const val TASK_CHANNEL = "jarvis_tasks_v1"
    const val CALL_NOTIFICATION_ID = 4242
    const val BRIEFING_REASON = "__briefing__"

    private fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CALL_CHANNEL) == null) {
            val ch = NotificationChannel(CALL_CHANNEL, "JARVIS calling you", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 800, 600, 800, 600, 800)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(TASK_CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(TASK_CHANNEL, "Tasks and briefing", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    /** Shows a full-screen "J.A.R.V.I.S. incoming call". */
    fun ring(ctx: Context, reason: String) {
        channels(ctx)
        val callIntent = Intent(ctx, JarvisCallActivity::class.java)
            .putExtra(JarvisCallActivity.EXTRA_REASON, reason)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val full = PendingIntent.getActivity(ctx, 11, callIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val answer = PendingIntent.getActivity(ctx, 12,
            Intent(callIntent).putExtra(JarvisCallActivity.EXTRA_AUTO_ANSWER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CALL_CHANNEL)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle("J.A.R.V.I.S.")
            .setContentText("Incoming call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(R.drawable.ic_phone, "Answer", answer)
            .setOngoing(true)
            .setAutoCancel(true)
            .setTimeoutAfter(35_000)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        try { NotificationManagerCompat.from(ctx).notify(CALL_NOTIFICATION_ID, n) } catch (_: SecurityException) { }
        // When phone control is on, Android also lets us open the call screen directly.
        try { ctx.startActivity(callIntent) } catch (_: Exception) { }
    }

    fun cancelRing(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(CALL_NOTIFICATION_ID)
    }

    fun missed(ctx: Context, reason: String) {
        channels(ctx)
        val open = PendingIntent.getActivity(ctx, 13,
            Intent(ctx, JarvisCallActivity::class.java).putExtra(JarvisCallActivity.EXTRA_REASON, reason)
                .putExtra(JarvisCallActivity.EXTRA_AUTO_ANSWER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = if (reason.isBlank() || reason == BRIEFING_REASON) "Tap to call back." else "About: $reason. Tap to call back."
        val n = NotificationCompat.Builder(ctx, TASK_CHANNEL)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle("Missed call from J.A.R.V.I.S.")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(4243, n) } catch (_: SecurityException) { }
    }

    fun notifyTask(ctx: Context, text: String) {
        channels(ctx)
        val open = PendingIntent.getActivity(ctx, 14,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, TASK_CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("Time to: $text")
            .setContentText("Reminder from J.A.R.V.I.S.")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % 100000).toInt() + 5000, n) } catch (_: SecurityException) { }
        JarvisCore.init(ctx)
        JarvisCore.announce("${Prefs.name.replaceFirstChar { it.uppercase() }}, it's time to $text.")
    }

    fun notifyBriefing(ctx: Context) {
        channels(ctx)
        val text = Tasks.briefingBase(ctx)
        val open = PendingIntent.getActivity(ctx, 15,
            Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_BRIEFING).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, TASK_CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("Your briefing")
            .setContentText(Tasks.todaySummary(ctx))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(4244, n) } catch (_: SecurityException) { }
    }
}
