package com.jarvis.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.sin

/** Voice visualizer bars under the core. */
class WaveView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var mode = JarvisCore.State.IDLE
    private var level = 0f
    private val bars = FloatArray(29)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val cyan = ContextCompat.getColor(ctx, R.color.cyan)
    private val amber = ContextCompat.getColor(ctx, R.color.amber)
    private val start = SystemClock.uptimeMillis()

    fun setLevel(l: Float) { level = l }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val n = bars.size
        val gap = width / (n * 2f)
        paint.strokeWidth = gap * 1.1f
        paint.color = if (mode == JarvisCore.State.THINKING) amber else cyan
        val mid = n / 2
        for (i in 0 until n) {
            val falloff = 1f - abs(i - mid) / (mid + 1f)
            val target = when (mode) {
                JarvisCore.State.LISTENING -> level * falloff * (0.6f + 0.4f * abs(sin(t * 12f + i)))
                JarvisCore.State.SPEAKING -> falloff * (0.35f + 0.65f * abs(sin(t * 7f + i * 0.7f) * sin(t * 2.3f + i)))
                JarvisCore.State.THINKING -> falloff * 0.35f * abs(sin(t * 4f - i * 0.4f))
                JarvisCore.State.IDLE -> 0.06f
            }
            bars[i] += (target - bars[i]) * 0.3f
            val x = gap + i * gap * 2
            val h = (height * 0.08f).coerceAtLeast(2f) + bars[i] * height * 0.9f
            paint.alpha = (110 + 145 * falloff).toInt()
            c.drawLine(x, height / 2f - h / 2, x, height / 2f + h / 2, paint)
        }
        if (mode != JarvisCore.State.LISTENING) level *= 0.9f
        if (isShown) { if (mode == JarvisCore.State.IDLE) postInvalidateDelayed(66) else postInvalidateOnAnimation() }
    }
}
