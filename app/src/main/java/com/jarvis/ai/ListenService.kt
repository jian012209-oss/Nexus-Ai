package com.jarvis.ai

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps "Hey Jarvis" listening while you use other apps.
 * Android requires a visible notification while the microphone is in use,
 * so you always know JARVIS is listening. Tap Stop in the notification to end it.
 */
class ListenService : Service() {

    companion object {
        const val CHANNEL = "jarvis_listen"
        private const val ACTION_STOP = "com.jarvis.ai.STOP_LISTENING"
        var running = false
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ListenService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ListenService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        JarvisCore.init(this)
        JarvisCore.ensureChannel(this, CHANNEL, "Listening for wake word", NotificationManager.IMPORTANCE_LOW)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val stopIntent = PendingIntent.getService(this, 1,
            Intent(this, ListenService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val openIntent = PendingIntent.getActivity(this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("JARVIS is listening")
            .setContentText("Say \"Hey ${Prefs.wakeWord}\" followed by a command.")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_mic, "Stop", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            // Android refused (e.g. microphone permission missing or app not in foreground).
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        JarvisCore.setContinuous(true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        JarvisCore.setContinuous(false)
        super.onDestroy()
    }
}
