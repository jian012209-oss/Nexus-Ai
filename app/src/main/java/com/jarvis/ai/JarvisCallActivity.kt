package com.jarvis.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * "J.A.R.V.I.S. incoming call" screen. Rings like a phone call, works on the
 * lock screen, and once answered you talk to JARVIS hands-free until you say bye.
 */
class JarvisCallActivity : AppCompatActivity(), JarvisCore.Listener {

    companion object {
        const val EXTRA_REASON = "reason"
        const val EXTRA_AUTO_ANSWER = "auto_answer"
    }

    private lateinit var core: CoreView
    private lateinit var wave: WaveView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var caption: TextView
    private lateinit var buttons: LinearLayout
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private val ui = Handler(Looper.getMainLooper())
    private var answered = false
    private var reason = ""
    private var startedAt = 0L

    private val timeout = Runnable { if (!answered) { IncomingCall.missed(this, reason); finish() } }
    private val timer = object : Runnable {
        override fun run() {
            if (!answered) return
            val s = (System.currentTimeMillis() - startedAt) / 1000
            subtitle.text = String.format("● CONNECTED · %02d:%02d", s / 60, s % 60)
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JarvisCore.init(this)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        reason = intent.getStringExtra(EXTRA_REASON).orEmpty()
        buildUi()
        if (intent.getBooleanExtra(EXTRA_AUTO_ANSWER, false)) answer() else startRinging()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundResource(R.drawable.bg_main) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(48), dp(20), dp(36))
        }
        title = TextView(this).apply {
            text = "J.A.R.V.I.S."
            setTextColor(ContextCompat.getColor(this@JarvisCallActivity, R.color.cyan))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
        }
        subtitle = TextView(this).apply {
            text = "INCOMING CALL"
            setTextColor(ContextCompat.getColor(this@JarvisCallActivity, R.color.hud_green))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.2f
            gravity = Gravity.CENTER
        }
        core = CoreView(this).apply { label = "" }
        wave = WaveView(this)
        caption = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@JarvisCallActivity, R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_glass)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.INVISIBLE
            minLines = 2
        }
        buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }

        col.addView(title)
        col.addView(subtitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        col.addView(core, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })
        col.addView(wave, LinearLayout.LayoutParams(dp(220), dp(36)))
        col.addView(caption, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        col.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) })
        root.addView(col)
        setContentView(root)
        showRingingButtons()
    }

    private fun roundButton(text: String, colorRes: Int, onClick: () -> Unit): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val circle = TextView(this).apply {
            this.text = if (colorRes == R.color.good) "✆" else "✕"
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@JarvisCallActivity, R.color.bg))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@JarvisCallActivity, colorRes))
            }
            setOnClickListener { onClick() }
            contentDescription = text
        }
        wrap.addView(circle, LinearLayout.LayoutParams(dp(68), dp(68)))
        wrap.addView(TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@JarvisCallActivity, R.color.text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        return wrap
    }

    private fun showRingingButtons() {
        buttons.removeAllViews()
        val lp = LinearLayout.LayoutParams(0, -2, 1f)
        buttons.addView(roundButton("Decline", R.color.danger) { decline() }, lp)
        buttons.addView(roundButton("Answer", R.color.good) { answer() }, lp)
    }

    private fun showInCallButtons() {
        buttons.removeAllViews()
        buttons.addView(roundButton("End call", R.color.danger) { endCall() }, LinearLayout.LayoutParams(-2, -2))
    }

    private fun startRinging() {
        try {
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            if (Build.VERSION.SDK_INT >= 28) ringtone?.isLooping = true
            ringtone?.play()
        } catch (_: Exception) { }
        try {
            vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
            else legacyVibrator()
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 700), 0))
        } catch (_: Exception) { }
        ui.postDelayed(timeout, 35_000)
    }

    @Suppress("DEPRECATION")
    private fun legacyVibrator(): Vibrator? = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private fun stopRinging() {
        try { ringtone?.stop() } catch (_: Exception) { }
        try { vibrator?.cancel() } catch (_: Exception) { }
        ui.removeCallbacks(timeout)
        IncomingCall.cancelRing(this)
    }

    private fun answer() {
        if (answered) return
        answered = true
        stopRinging()
        startedAt = System.currentTimeMillis()
        ui.post(timer)
        caption.visibility = View.VISIBLE
        caption.text = "Connecting…"
        showInCallButtons()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            caption.text = "Allow the microphone in JARVIS to talk back."
        }
        JarvisCore.startCallConversation(this, reason)
    }

    private fun decline() {
        stopRinging()
        finish()
    }

    private fun endCall() {
        JarvisCore.endCallConversation()
        finish()
    }

    // ---------------- Core listener ----------------

    override fun onStart() {
        super.onStart()
        JarvisCore.addListener(this)
    }

    override fun onStop() {
        JarvisCore.removeListener(this)
        super.onStop()
    }

    override fun onDestroy() {
        stopRinging()
        ui.removeCallbacks(timer)
        if (answered) JarvisCore.endCallConversation()
        super.onDestroy()
    }

    override fun onState(state: JarvisCore.State) {
        core.mode = state
        wave.mode = state
    }

    override fun onLevel(level: Float) {
        core.setLevel(level)
        wave.setLevel(level)
    }

    override fun onPartial(text: String) {
        if (text.isNotBlank() && answered) caption.text = text
    }

    override fun onMessage(fromUser: Boolean, text: String) {
        if (answered) caption.text = if (fromUser) "YOU: $text" else "JARVIS: $text"
    }

    override fun onCallEnded() {
        subtitle.text = "● CALL ENDED"
        ui.postDelayed({ finish() }, 1200)
    }
}
