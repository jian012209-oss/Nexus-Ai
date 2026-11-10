package com.jarvis.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The J.A.R.V.I.S. core: a red reactor with a radial burst,
 * green and orange HUD arcs, and corner brackets.
 */
class CoreView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var mode = JarvisCore.State.IDLE
        set(v) { field = v; invalidate() }

    var label = "J.A.R.V.I.S."
    var showBrackets = true

    private var level = 0f
    private var smooth = 0f
    fun setLevel(l: Float) { level = l }

    private val red = ContextCompat.getColor(ctx, R.color.cyan)
    private val deep = ContextCompat.getColor(ctx, R.color.electric)
    private val orange = ContextCompat.getColor(ctx, R.color.amber)
    private val green = ContextCompat.getColor(ctx, R.color.hud_green)
    private val white = ContextCompat.getColor(ctx, R.color.text)
    private val start = SystemClock.uptimeMillis()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.18f
    }
    private val rect = RectF()

    private fun alpha(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.95f
        if (r <= 0) return
        val t = (SystemClock.uptimeMillis() - start) / 1000f

        val main = if (mode == JarvisCore.State.THINKING) orange else red
        val speed = when (mode) {
            JarvisCore.State.IDLE -> 10f
            JarvisCore.State.LISTENING -> 35f
            JarvisCore.State.THINKING -> 120f
            JarvisCore.State.SPEAKING -> 55f
        }
        if (mode != JarvisCore.State.LISTENING) level *= 0.9f
        smooth += (level - smooth) * 0.25f
        val pulse = when (mode) {
            JarvisCore.State.SPEAKING -> 0.55f + 0.45f * abs(sin(t * 9f) * sin(t * 3.1f))
            JarvisCore.State.LISTENING -> 0.25f + smooth * 0.75f
            JarvisCore.State.THINKING -> 0.5f + 0.5f * sin(t * 4f)
            JarvisCore.State.IDLE -> 0.3f + 0.12f * sin(t * 1.3f)
        }

        // HUD corner brackets
        if (showBrackets) {
            stroke.color = alpha(red, 150)
            stroke.strokeWidth = r * 0.012f
            val pad = r * 0.04f
            val len = r * 0.16f
            val l = pad; val tp = pad; val rt = width - pad; val b = height - pad
            c.drawLine(l, tp, l + len, tp, stroke); c.drawLine(l, tp, l, tp + len, stroke)
            c.drawLine(rt, tp, rt - len, tp, stroke); c.drawLine(rt, tp, rt, tp + len, stroke)
            c.drawLine(l, b, l + len, b, stroke); c.drawLine(l, b, l, b - len, stroke)
            c.drawLine(rt, b, rt - len, b, stroke); c.drawLine(rt, b, rt, b - len, stroke)
        }

        // Outer glow
        glow.shader = RadialGradient(cx, cy, r * 0.75f,
            intArrayOf(alpha(main, (60 + 90 * pulse).toInt()), alpha(deep, 40), alpha(deep, 0)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 0.75f, glow)

        // Green HUD arc (outer)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = alpha(green, 200)
        stroke.strokeWidth = r * 0.016f
        rect.set(cx - r * 0.92f, cy - r * 0.92f, cx + r * 0.92f, cy + r * 0.92f)
        c.drawArc(rect, t * speed * 0.4f + 200f, 150f, false, stroke)
        c.drawArc(rect, t * speed * 0.4f + 20f, 40f, false, stroke)

        // Orange segments
        stroke.color = alpha(orange, 190)
        stroke.strokeWidth = r * 0.03f
        rect.set(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f)
        c.drawArc(rect, -t * speed * 0.6f + 90f, 18f, false, stroke)
        c.drawArc(rect, -t * speed * 0.6f + 270f, 8f, false, stroke)

        // Thin red rings
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.color = alpha(red, 170)
        stroke.strokeWidth = r * 0.01f
        c.drawCircle(cx, cy, r * 0.79f, stroke)
        stroke.color = alpha(red, 90)
        c.drawCircle(cx, cy, r * 0.74f, stroke)

        // Tick ring
        stroke.color = alpha(red, 120)
        stroke.strokeWidth = r * 0.008f
        c.save()
        c.rotate(t * speed * 0.1f, cx, cy)
        for (i in 0 until 90) {
            val inner = if (i % 10 == 0) r * 0.66f else r * 0.69f
            c.drawLine(cx, cy - r * 0.72f, cx, cy - inner, stroke)
            c.rotate(4f, cx, cy)
        }
        c.restore()

        // Radial burst (reacts to voice)
        val core = r * 0.38f
        stroke.color = alpha(main, 210)
        stroke.strokeWidth = r * 0.012f
        val rays = 120
        for (i in 0 until rays) {
            val a = (i.toFloat() / rays) * (2 * Math.PI).toFloat()
            val n = 0.5f + 0.5f * sin(a * 7f + t * 3f) * cos(a * 3f - t * 2f)
            val len = r * (0.04f + 0.2f * pulse * n)
            val x1 = cx + cos(a) * core
            val y1 = cy + sin(a) * core
            val x2 = cx + cos(a) * (core + len)
            val y2 = cy + sin(a) * (core + len)
            c.drawLine(x1, y1, x2, y2, stroke)
        }

        // Reactor core
        fill.shader = RadialGradient(cx, cy, core,
            intArrayOf(alpha(main, 255), alpha(deep, 240), alpha(Color.BLACK, 230)),
            floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, core * (0.95f + 0.05f * pulse), fill)
        fill.shader = null
        stroke.color = alpha(main, 230)
        stroke.strokeWidth = r * 0.014f
        c.drawCircle(cx, cy, core, stroke)

        // Label
        text.color = alpha(white, 240)
        text.textSize = r * 0.1f
        c.drawText(label, cx, cy + text.textSize * 0.35f, text)

        if (isShown) {
            if (mode == JarvisCore.State.IDLE) postInvalidateDelayed(66) else postInvalidateOnAnimation()
        }
    }
}
