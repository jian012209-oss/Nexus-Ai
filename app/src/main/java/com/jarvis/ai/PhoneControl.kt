package com.jarvis.ai

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Phone control engine. Every function performs a real Android action and
 * returns what actually happened. Failures are reported, never faked.
 */
object PhoneControl {

    var torchOn = false
        private set

    fun has(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    /** Starts an activity. Returns false if nothing on the phone can handle it. */
    fun launch(ctx: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    fun openUrl(ctx: Context, url: String) = launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    // ---------------- Apps ----------------

    private val appAliases = mapOf(
        "fb" to "facebook", "ml" to "mobile legends", "mlbb" to "mobile legends",
        "codm" to "call of duty", "cod" to "call of duty", "yt" to "youtube",
        "ig" to "instagram", "messenger" to "messenger", "gmail" to "gmail",
        "play store" to "play store", "google play" to "play store", "maps" to "maps",
        "camera" to "camera", "calculator" to "calculator", "clock" to "clock",
        "settings" to "settings", "gcash" to "gcash", "tiktok" to "tiktok", "tik tok" to "tiktok"
    )

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    data class AppMatch(val label: String, val pkg: String)

    fun findApp(ctx: Context, spoken: String): AppMatch? {
        val wanted = norm(appAliases[spoken.lowercase().trim()] ?: spoken)
        if (wanted.isEmpty()) return null
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(intent, 0)
        var best: AppMatch? = null
        var bestScore = 0
        for (ri in apps) {
            val label = ri.loadLabel(pm).toString()
            val n = norm(label)
            if (n.isEmpty()) continue
            val score = when {
                n == wanted -> 100
                n.startsWith(wanted) -> 80
                n.contains(wanted) -> 60
                wanted.contains(n) && n.length >= 3 -> 50
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                best = AppMatch(label, ri.activityInfo.packageName)
            }
        }
        return best
    }

    fun openApp(ctx: Context, spoken: String): String {
        val match = findApp(ctx, spoken) ?: return "I couldn't find an app called $spoken on this phone."
        val intent = ctx.packageManager.getLaunchIntentForPackage(match.pkg)
            ?: return "${match.label} can't be opened directly."
        return if (launch(ctx, intent)) {
            Prefs.appUsed(match.label)
            "Opening ${match.label}."
        } else "Android blocked opening ${match.label}. Turn on phone control so I can open apps from the background."
    }

    fun searchApps(ctx: Context, query: String): String {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val labels = pm.queryIntentActivities(intent, 0).map { it.loadLabel(pm).toString() }
            .filter { norm(it).contains(norm(query)) }.distinct().sorted()
        return when {
            labels.isEmpty() -> "No installed apps match $query."
            labels.size == 1 -> "You have ${labels[0]}."
            else -> "I found ${labels.size} apps: ${labels.take(8).joinToString(", ")}."
        }
    }

    // ---------------- Flashlight ----------------

    fun torch(ctx: Context, on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val id = cm.cameraIdList.firstOrNull {
                val c = cm.getCameraCharacteristics(it)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "This phone has no flashlight I can control."
            cm.setTorchMode(id, on)
            torchOn = on
            if (on) "Flashlight on." else "Flashlight off."
        } catch (e: Exception) {
            "The camera is busy, so I couldn't switch the flashlight. Close the camera app and try again."
        }
    }

    // ---------------- Volume ----------------

    private fun audio(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun volumeStep(ctx: Context, up: Boolean, steps: Int = 2): String {
        val am = audio(ctx)
        repeat(steps) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }
        return "Media volume is now ${volumePercent(ctx)} percent."
    }

    fun setVolumePercent(ctx: Context, pct: Int): String {
        val am = audio(ctx)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (pct.coerceIn(0, 100) / 100f * max).roundToInt()
        return try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            "Setting media volume to ${pct.coerceIn(0, 100)} percent."
        } catch (e: SecurityException) {
            "Android won't let me change volume while Do Not Disturb is on."
        }
    }

    fun volumePercent(ctx: Context): Int {
        val am = audio(ctx)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
    }

    fun mute(ctx: Context, mute: Boolean): String {
        audio(ctx).adjustStreamVolume(AudioManager.STREAM_MUSIC,
            if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
        return if (mute) "Media muted." else "Media unmuted."
    }

    fun ringerMode(ctx: Context, mode: Int): String = try {
        audio(ctx).ringerMode = mode
        when (mode) {
            AudioManager.RINGER_MODE_SILENT -> "Silent mode on."
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate mode on."
            else -> "Ringer back to normal."
        }
    } catch (e: SecurityException) {
        launch(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        "Android needs Do Not Disturb access first. Allow JARVIS AI on the screen I opened, then ask again."
    }

    // ---------------- Media ----------------

    fun mediaKey(ctx: Context, code: Int): String {
        val am = audio(ctx)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> "Skipping."
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Going back a track."
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "Paused."
            KeyEvent.KEYCODE_MEDIA_PLAY -> "Playing."
            else -> "Done."
        }
    }

    fun replay(ctx: Context): String {
        mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        return "Replaying."
    }

    fun playOnYoutube(ctx: Context, q: String): String =
        if (openUrl(ctx, "https://www.youtube.com/results?search_query=" + Uri.encode(q))) "Searching YouTube for $q."
        else "I couldn't open YouTube."

    fun playOnSpotify(ctx: Context, q: String): String {
        val ok = launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q))))
            || openUrl(ctx, "https://open.spotify.com/search/" + Uri.encode(q))
        return if (ok) "Looking up $q on Spotify." else "I couldn't open Spotify."
    }

    fun playMusic(ctx: Context): String {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra("query", "")
        return if (launch(ctx, i)) "Playing your music." else mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    // ---------------- Brightness ----------------

    fun brightness(ctx: Context, pct: Int?, delta: Int = 0, auto: Boolean = false): String {
        if (!Settings.System.canWrite(ctx)) {
            launch(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            return "I need permission to change system settings. Turn on \"Modify system settings\" for JARVIS AI, then ask again."
        }
        val cr = ctx.contentResolver
        if (auto) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return "Adaptive brightness on."
        }
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val target = when {
            pct != null -> (pct.coerceIn(1, 100) * 255 / 100)
            else -> (cur + delta).coerceIn(5, 255)
        }
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
        return "Brightness set to ${(target * 100 / 255)} percent."
    }

    // ---------------- Do Not Disturb ----------------

    fun dnd(ctx: Context, on: Boolean): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            launch(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return "I need Do Not Disturb access. Allow JARVIS AI on the screen I opened, then try again."
        }
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return if (on) "Do Not Disturb on." else "Do Not Disturb off."
    }

    fun dndIsOn(ctx: Context): Boolean {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    // ---------------- Device status ----------------

    fun batteryLevel(ctx: Context): Int =
        (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    fun isCharging(ctx: Context): Boolean =
        (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging

    fun battery(ctx: Context): String {
        val level = batteryLevel(ctx)
        return "Battery is at $level percent" + if (isCharging(ctx)) " and charging." else
            if (level <= 20) ". You should plug in soon." else "."
    }

    fun storage(): String {
        val st = StatFs(Environment.getDataDirectory().path)
        val free = st.availableBytes / 1_000_000_000.0
        val total = st.totalBytes / 1_000_000_000.0
        return String.format(java.util.Locale.US, "You have %.1f gigabytes free out of %.0f.", free, total)
    }

    fun memory(ctx: Context): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val free = mi.availMem / 1_000_000_000.0
        val total = mi.totalMem / 1_000_000_000.0
        return String.format(java.util.Locale.US, "%.1f of %.1f gigabytes of RAM are available%s",
            free, total, if (mi.lowMemory) ". Memory is low right now." else ".")
    }

    // ---------------- Alarms, timers, reminders ----------------

    fun setAlarm(ctx: Context, hour: Int, minute: Int, label: String = "JARVIS alarm"): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (launch(ctx, i)) "Alarm set for ${spokenTime(hour, minute)}."
        else "No clock app on this phone accepted the alarm."
    }

    fun setTimer(ctx: Context, seconds: Int, label: String = "JARVIS timer"): String {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (launch(ctx, i)) "Timer set for ${spokenDuration(seconds)}."
        else "No clock app on this phone accepted the timer."
    }

    fun spokenTime(h: Int, m: Int): String {
        val h12 = if (h % 12 == 0) 12 else h % 12
        val ampm = if (h < 12) "AM" else "PM"
        return if (m == 0) "$h12 $ampm" else String.format(java.util.Locale.US, "%d:%02d %s", h12, m, ampm)
    }

    fun spokenDuration(s: Int): String {
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += "$h hour" + if (h > 1) "s" else ""
        if (m > 0) parts += "$m minute" + if (m > 1) "s" else ""
        if (sec > 0) parts += "$sec second" + if (sec > 1) "s" else ""
        return parts.joinToString(" ")
    }

    // ---------------- Contacts, calls, SMS, email ----------------

    data class Contact(val name: String, val number: String)

    fun findContact(ctx: Context, name: String): Contact? {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return null
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        ctx.contentResolver.query(uri, proj,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")?.use { c ->
            if (c.moveToFirst()) return Contact(c.getString(0) ?: name, c.getString(1) ?: return null)
        }
        return null
    }

    /** Looks up a caller's name from your contacts. */
    fun contactName(ctx: Context, number: String): String? {
        if (number.isBlank() || !has(ctx, Manifest.permission.READ_CONTACTS)) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    /** True if JARVIS is your call screening app (needed for the call assistant). */
    fun hasCallRole(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val rm = ctx.getSystemService(android.app.role.RoleManager::class.java) ?: return false
        return rm.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)
    }

    /** Routes call audio to the loudspeaker so the caller can hear JARVIS. */
    fun speakerphone(ctx: Context, on: Boolean) {
        val am = audio(ctx)
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) {
                    am.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?.let { am.setCommunicationDevice(it) }
                } else am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = on
            }
        } catch (_: Exception) { }
    }

    fun call(ctx: Context, number: String): String {
        val uri = Uri.parse("tel:" + Uri.encode(number))
        return if (has(ctx, Manifest.permission.CALL_PHONE) && launch(ctx, Intent(Intent.ACTION_CALL, uri))) "Calling."
        else if (launch(ctx, Intent(Intent.ACTION_DIAL, uri))) "I opened the dialer. Tap call to connect."
        else "This phone can't make calls."
    }

    fun sendSms(ctx: Context, number: String, body: String): String {
        if (!has(ctx, Manifest.permission.SEND_SMS)) {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", body)
            return if (launch(ctx, i)) "The message is ready in your messaging app. Tap send." else "I couldn't open your messaging app."
        }
        return try {
            @Suppress("DEPRECATION")
            val sms: SmsManager = (if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else null)
                ?: SmsManager.getDefault()
            val parts = sms.divideMessage(body)
            sms.sendMultipartTextMessage(number, null, parts, null, null)
            "Message sent."
        } catch (e: Exception) {
            "Android couldn't send the message: ${e.message ?: "unknown error"}."
        }
    }

    fun draftEmail(ctx: Context, to: String, subject: String, body: String): String {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(to)))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
        return if (launch(ctx, i)) "Your email draft is open. Review it and tap send." else "No email app is installed."
    }

    // ---------------- Settings panels ----------------

    fun openPanel(ctx: Context, what: String): String {
        val (intent, label) = when (what) {
            "wifi" -> (if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)) to "Wi-Fi"
            "data" -> (if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)) to "mobile data"
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS) to "Bluetooth"
            "airplane" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS) to "airplane mode"
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS) to "location"
            "hotspot" -> Intent(Settings.ACTION_WIRELESS_SETTINGS) to "hotspot"
            "nfc" -> Intent(Settings.ACTION_NFC_SETTINGS) to "NFC"
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS) to "display settings"
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS) to "sound settings"
            else -> Intent(Settings.ACTION_SETTINGS) to "settings"
        }
        return if (launch(ctx, intent)) {
            if (what == "settings" || what == "display" || what == "sound") "Opening $label."
            else "Here's the $label switch. Android only lets you flip this one yourself."
        } else "I couldn't open $label on this phone."
    }

    // ---------------- Accessibility-powered actions ----------------

    fun needAccessibility(ctx: Context): String {
        launch(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        return "I need phone control turned on for that. In Accessibility, open JARVIS AI and switch it on."
    }

    fun global(ctx: Context, action: Int, done: String): String {
        val svc = JarvisAccessibilityService.instance ?: return needAccessibility(ctx)
        return if (svc.performGlobalAction(action)) done else "Android didn't allow that action on this phone."
    }

    fun lockScreen(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= 28) global(ctx, AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Locking the screen.")
        else "Locking the screen needs Android 9 or newer."

    fun screenshot(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= 28) global(ctx, AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Screenshot taken.")
        else "Screenshots need Android 9 or newer."

    fun camera(ctx: Context, selfie: Boolean): String {
        val i = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (selfie) {
            i.putExtra("android.intent.extras.CAMERA_FACING", 1)
            i.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
        return if (launch(ctx, i)) "Camera ready." else "I couldn't open the camera."
    }
}
