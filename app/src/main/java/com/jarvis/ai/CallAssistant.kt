package com.jarvis.ai

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles incoming calls while you're away:
 *  - "answer": picks up, puts the call on speaker, says your message, hangs up.
 *  - "text":   declines and texts your message back.
 * Every call is logged so JARVIS can tell you who called.
 */
object CallAssistant {

    private val main = Handler(Looper.getMainLooper())

    fun log(who: String, action: String) {
        val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
        Prefs.callLog = Prefs.callLog + "$who at $time ($action)"
    }

    fun summary(clear: Boolean): String {
        val log = Prefs.callLog
        if (log.isEmpty()) return "No one called while you were away, ${Prefs.name}."
        val text = "You had ${log.size} call${if (log.size > 1) "s" else ""}: " +
            log.takeLast(6).joinToString("; ") { it.substringBefore(" (") } + "."
        if (clear) Prefs.callLog = emptyList()
        return text
    }

    /** Picks up the ringing call and speaks the away message to the caller. */
    fun answerAndSpeak(ctx: Context, who: String) {
        val app = ctx.applicationContext
        if (!PhoneControl.has(app, Manifest.permission.ANSWER_PHONE_CALLS)) {
            log(who, "missed, no answer permission")
            return
        }
        val tm = app.getSystemService(TelecomManager::class.java) ?: return
        JarvisCore.pauseForCall(true)
        // Let it ring briefly, like a person picking up.
        main.postDelayed({
            try {
                @Suppress("DEPRECATION")
                tm.acceptRingingCall()
            } catch (e: Exception) {
                log(who, "missed, Android blocked answering")
                JarvisCore.pauseForCall(false)
                return@postDelayed
            }
            main.postDelayed({
                PhoneControl.speakerphone(app, true)
                main.postDelayed({
                    JarvisCore.speakIntoCall(Prefs.awayMessageFilled()) {
                        main.postDelayed({
                            try {
                                if (Build.VERSION.SDK_INT >= 28) tm.endCall()
                            } catch (_: Exception) { }
                            PhoneControl.speakerphone(app, false)
                            JarvisCore.pauseForCall(false)
                            log(who, "answered by JARVIS")
                            JarvisCore.postEvent(app, "JARVIS answered a call", "$who called. JARVIS gave them your message.")
                        }, 1200)
                    }
                }, 700)
            }, 1300)
        }, 2500)
    }

    /** Declines the call and texts the away message back. */
    fun declineAndText(ctx: Context, who: String, number: String) {
        val app = ctx.applicationContext
        if (number.isNotBlank() && PhoneControl.has(app, Manifest.permission.SEND_SMS)) {
            val r = PhoneControl.sendSms(app, number, Prefs.awayMessageFilled())
            log(who, if (r.startsWith("Message sent")) "declined, texted back" else "declined")
        } else {
            log(who, "declined")
        }
        JarvisCore.postEvent(app, "JARVIS declined a call", "$who called. ${if (number.isNotBlank()) "JARVIS texted them your message." else ""}")
    }
}
