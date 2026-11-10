package com.jarvis.ai

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi

/**
 * Android's call screening hook. Only active after you choose JARVIS as
 * your call screening app. It announces callers and runs the away mode.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class JarvisCallService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        JarvisCore.init(this)
        val allow = CallResponse.Builder().build()

        if (details.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondToCall(details, allow)
            return
        }

        val number = details.handle?.schemeSpecificPart.orEmpty()
        val who = PhoneControl.contactName(this, number) ?: number.ifBlank { "Unknown caller" }

        when (Prefs.awayMode) {
            "text" -> {
                respondToCall(details, CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipNotification(false)
                    .build())
                CallAssistant.declineAndText(this, who, number)
            }
            "answer" -> {
                respondToCall(details, allow)
                CallAssistant.answerAndSpeak(this, who)
            }
            else -> {
                respondToCall(details, allow)
                if (Prefs.announceCalls) JarvisCore.announce("${Prefs.name.replaceFirstChar { it.uppercase() }}, $who is calling.")
            }
        }
    }
}
