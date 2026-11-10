package com.jarvis.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Phone control. Only acts when you give a command: go home, back, recents,
 * lock, screenshot, scroll, tap a button by its text, type into a field,
 * or read the screen aloud. It never records or sends screen content anywhere.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JarvisAccessibilityService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return enabled.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        JarvisCore.init(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun switchToLastApp() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        Handler(Looper.getMainLooper()).postDelayed({ performGlobalAction(GLOBAL_ACTION_RECENTS) }, 400)
    }

    private fun swipeGesture(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun scroll(down: Boolean): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val top = dm.heightPixels * 0.28f
        val bottom = dm.heightPixels * 0.72f
        return if (down) swipeGesture(x, bottom, x, top) else swipeGesture(x, top, x, bottom)
    }

    fun swipe(left: Boolean): Boolean {
        val dm = resources.displayMetrics
        val y = dm.heightPixels / 2f
        val l = dm.widthPixels * 0.15f
        val r = dm.widthPixels * 0.85f
        return if (left) swipeGesture(r, y, l, y) else swipeGesture(l, y, r, y)
    }

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (n in nodes) {
            var node: AccessibilityNodeInfo? = n
            var depth = 0
            while (node != null && depth < 6) {
                if (node.isClickable && node.isEnabled) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node = node.parent
                depth++
            }
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: findEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        if (depth > 30) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditable(child, depth + 1)?.let { return it }
        }
        return null
    }

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        collect(root, sb, 0)
        return sb.toString().trim().take(700)
    }

    private fun collect(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (sb.length > 900 || depth > 40) return
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrEmpty() && !node.isPassword) sb.append(t).append(". ")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, sb, depth + 1)
        }
    }
}
