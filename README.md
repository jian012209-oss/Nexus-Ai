# J.A.R.V.I.S.

**Your Phone. Your Intelligence. Your Control.**

A JARVIS-style voice assistant for Android. Say **"Hey Jarvis"**, then give a command. It controls your phone, works offline, and uses cloud AI when you're online.

---

## Get the APK (no computer needed)

1. Create a free account at **github.com** and make a new repository (for example `jarvis-ai`). Leave it empty.
2. Upload every file and folder from this project, keeping the folder structure. The hidden folder `.github/workflows` must be included, because it holds the build script.
   - Easiest from a phone: tell Claude your repository name and connect GitHub, and the code can be pushed for you.
   - Or from a computer: drag the unzipped folder into the repo's "Upload files" page.
3. Open the **Actions** tab in your repo. A run called **Build Jarvis AI APK** starts by itself (or tap it, then **Run workflow**).
4. When it shows a green check (about 5–8 minutes), open the run and download **JarvisAI-APK** at the bottom. It's a zip with `app-debug.apk` inside.
5. On your phone, open the APK and allow installing from this source.

### Or with Android Studio
Open the project folder, wait for Gradle to sync, then **Build → Build APK(s)**. The APK is in `app/build/outputs/apk/debug/`.

---

## First-time setup on the phone

1. **Open JARVIS AI** and allow the permissions it asks for (microphone, contacts, phone, SMS, notifications).
2. **Phone control**: tap the yellow banner, then Open Accessibility, find *JARVIS AI phone control*, and switch it on.
   - If the switch is greyed out (Android 13+, apps installed outside the Play Store): Settings → Apps → JARVIS AI → ⋮ (top right) → **Allow restricted settings**, then try again.
3. **Cloud AI key (free)**: go to **aistudio.google.com/apikey**, create a key, then in JARVIS open ⋮ → Settings and paste it under API key.
4. **Offline voice**: in the Google app, go to Settings → Voice → Offline speech recognition and download English and/or Filipino.
5. Optional, from ⋮ → Permissions: Do Not Disturb access (for routines), Modify system settings (brightness), Notification access (read notifications), and Default assistant (long-press Home to open JARVIS).
6. Turn on **Always ready** to use "Hey Jarvis" while you're in other apps.

---

## New in v1.2

- **Call assistant**: "Jarvis, if anyone calls, tell them I'm in a meeting." JARVIS answers on speaker, gives your message, hangs up and later tells you who called. Or "if anyone calls, text them" to decline and text back. Say "I'm back" to turn it off. JARVIS also says who's calling when the phone rings.
- **JARVIS calls you**: "call me in 20 minutes about my deadline" or "call me at 7 PM to remind me to send the invoice". Your phone rings with a full-screen J.A.R.V.I.S. call, and you talk hands-free until you say bye.
- **Vision**: "what's this?" opens the camera; JARVIS identifies what you show it and answers follow-up questions.
- **To-do list and daily briefing**: "add gym at 7 to my list", "what do I need to do?", "mark call mum done", "brief me". Timed tasks notify you; the morning briefing can arrive as a notification or a J.A.R.V.I.S. call.
- **Red HUD design** and a **British male voice**.

Setup for the new features (Menu → Permissions): Call assistant (choose JARVIS as call screening app), Camera, Calendar, Exact reminders, Full-screen calls.
The call assistant plays JARVIS's message on speaker so the caller hears it; on some phones the caller hears it faintly because of echo cancellation. "Text them" mode always works.
J.A.R.V.I.S. calls are in-app calls (no phone network), so they're free and work offline.

## What it does

**Always-ready voice**: custom wake word (default "Jarvis"), works in the background with a visible notification, continuous conversation (you can reply without the wake word for a few seconds), tap the core to interrupt, English and Filipino, adjustable voice, speed, pitch and personality.

**Phone control (works offline)**
- Apps: open, switch, search installed apps, go home, back, recent apps
- Device: flashlight, volume (exact percent), brightness, Do Not Disturb, silent and vibrate, battery and charging, storage, RAM
- Settings panels: Wi-Fi, Bluetooth, mobile data, airplane mode, location, hotspot
- Screen: lock, screenshot, scroll, swipe, tap a button by its name, type into a text box, read the screen aloud
- Time: alarms, timers, reminders ("remind me to … at 5 PM" or "in 20 minutes"), notes
- Media: play, pause, next, previous, replay, YouTube and Spotify search
- People: call, text and email, always confirmed by you first ("Call Mom? Say yes or no.")
- Notifications: read them on request, or announce new ones (opt-in)

**Automation**
- Routines: *gaming mode*, *good night*, *good morning*, *focus mode*, plus your own. Edit them in ⋮ → Routines, or say "create routine work mode: do not disturb on, open Gmail".
- Chains: "open Spotify, play my music and set the volume to 40 percent" runs each step in order and reports any that fail.

**Hybrid intelligence**
- **Hybrid** (default): local engine for commands, cloud AI for everything else when online.
- **Online**: cloud AI answers all conversation.
- **Offline**: nothing leaves the phone. Questions that need the internet get an honest "I'm offline" answer.
- The cloud AI picks a fast or smart model by task and turns on live web search for weather, news, prices and other real-time questions.
- Providers: Google Gemini (free key) or Anthropic Claude. Model names are editable in Settings.
- The AI can also trigger phone actions, so loosely worded requests still work.

**Memory and privacy**
- Remembers your name, language, voice, frequent apps, notes, reminders and anything you say "remember that…" about.
- View, edit, export and delete everything in ⋮ → Memory.
- API keys, memories, routines and chat history are stored encrypted on the phone.
- Privacy mode stops saving the conversation.
- Permissions dashboard in ⋮ → Permissions.
- The microphone is only used when you tap the core or when Always ready is on, with Android's mic indicator and a notification showing.

---

## Try these

```
Hey Jarvis, open YouTube
Set my volume to 50 percent
Turn on the flashlight
What's the weather today?
Remind me to call Mom at 5 PM
Text Ana saying I'm on my way
Gaming mode
Good night, wake me at 6
Open Spotify, play my music and set the volume to 40 percent
Remember that my parking spot is B12
Scroll down / tap Subscribe / go home
Status report
```

---

## Testing checklist

1. Tap the core, say "what time is it". It should answer out loud.
2. Turn off Wi-Fi and data, say "battery", then "flashlight on". Both should work.
3. Still offline, ask "who won the last NBA finals". It should say it's offline.
4. Back online with a key set, ask the same. It should answer.
5. Turn on Always ready, open another app, say "Hey Jarvis, go home".
6. Say "call" plus a contact name, then say "no". Nothing should be dialed.
7. Say "gaming mode" and check volume, Do Not Disturb and the notification.

## Known limits

- Android doesn't let apps flip Wi-Fi, Bluetooth, mobile data or airplane mode directly; JARVIS opens the switch panel for you.
- On many phones the speech engine beeps when it restarts in Always ready mode. You can lower the system sound volume to quiet it.
- Background listening can be stopped by aggressive battery savers (Xiaomi, Oppo, Vivo, Realme). Set JARVIS AI's battery usage to "Unrestricted".
- Offline intelligence is a local command engine plus built-in replies. An on-device language model (about 1–2 GB) can be added later as a separate download.
- iPhone is not supported; iOS doesn't allow this level of phone control.

## Project layout

| File | Role |
|---|---|
| `MainActivity.kt` | Dashboard: core, voice visualizer, status, quick controls, recents, chat |
| `JarvisCore.kt` | Voice engine (speech in/out, wake word), hybrid routing, confirmations, automation runner |
| `CommandEngine.kt` | Offline command interpreter, chaining, routines |
| `PhoneControl.kt` | Real Android actions (apps, volume, torch, brightness, DND, alarms, calls, SMS…) |
| `OnlineAi.kt` | Cloud AI engine (Gemini / Claude), model selection, web search |
| `OfflineChat.kt`, `MathEval.kt` | Offline conversation and calculator |
| `Prefs.kt` | Encrypted memory and settings store |
| `ListenService.kt` | Background "Hey Jarvis" listening |
| `JarvisAccessibilityService.kt` | Phone control: home, back, lock, scroll, tap, type, read screen |
| `NotifListener.kt` | Reads notifications with permission |
| `CoreView.kt`, `WaveView.kt` | Animated AI core and sound-wave visualizer |
| `Dialogs.kt` | Settings, memory, routines, permissions, guide |
