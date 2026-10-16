# Voice Alarm 🔊⏰

An Android alarm clock that **speaks the label you set**. Set an alarm for 7:00 AM
labelled **"for interview"** and, instead of a beep, a natural voice wakes you
up saying:

> *(soft chime)* "Good morning! Time to wake up. For interview."

The message repeats with a short pause and gets louder gradually, like a person
waking you up rather than a siren.

## Download the APK

Every push builds the app on GitHub Actions and publishes it as the
**`latest` release**:

1. On your phone, open
   **https://github.com/purnendukumarghosh/android-alarm/releases/latest**
2. Download **`VoiceAlarm.apk`** and tap it.
3. If asked, allow "Install unknown apps" for your browser or file manager.

(You can also take the `VoiceAlarm-apk` artifact from any run on the
**Actions** tab.)

## Making it sound human

- **Natural voice (default):** the app picks the most natural voice installed
  on your phone, using your phone's language and accent first, then the HD and
  online neural voices. You can try every voice with **▶ Preview** and choose
  the one you like. For the best voices, install or update
  **Speech Services by Google** from the Play Store. Then, in
  *Settings › Text-to-speech*, choose it and download the HD voices for your
  language (for example English (India)).
- **Relaxed pacing:** the speed defaults to slightly slower than normal
  (0.92×), and the label is spoken as its own sentence with a pause before it,
  which sounds much less robotic. You can change the speed per alarm.
- **Your own voice:** pick **"Record my own voice"** and record yourself, or a
  family member, saying the message. Nothing sounds more human than a real
  human.

The voice is rendered to an audio file **when you save the alarm**, so it works
offline at 7 AM even if you chose an online voice. If rendering fails, the
alarm speaks the label live. If text-to-speech isn't available at all, it
falls back to your normal alarm ringtone, so it always makes a sound.

## Features

- Any number of alarms, one-time or repeating on chosen weekdays
- The spoken label, an optional greeting, and optionally the time ("It's 7 AM")
- Voice picker with preview and an adjustable speaking speed
- Record your own voice instead of text-to-speech
- Gentle chime between repeats, with volume rising over about 30 seconds
- Full-screen ringing screen over the lock screen, with Snooze and Dismiss
- Snooze length per alarm (5 to 30 min); cancel a snooze from its notification
- Vibration (optional), stops by itself after 10 minutes and leaves a "missed
  alarm" notification
- Alarms survive reboots, time-zone changes and app updates
- Uses `AlarmManager.setAlarmClock`, so alarms ring on time even in Doze

## First launch: permissions

The app shows a banner for anything it still needs:

| Permission | Why |
| --- | --- |
| Notifications | to ring and show the alarm |
| Alarms & reminders | to ring at the exact minute |
| Full-screen alerts (Android 14+) | to open the alarm screen over the lock screen |
| Microphone (only if you record) | to record your own voice |

On Xiaomi, Oppo, Vivo, Realme and some Samsung phones, also open
*Settings › Apps › Voice Alarm › Battery* and choose **No restrictions**, and
enable **Autostart** if it exists. These phones sometimes kill alarm apps.

## Building it yourself

Requirements: JDK 17 and the Android SDK (API 34).

```bash
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

### Signing

Without extra setup the APK is signed with a debug key that changes on every CI
run. When you install a newer build you may then need to uninstall the old one
first, which also deletes your alarms. To keep the same key, create a keystore
once and add these repository secrets (*Settings › Secrets and variables ›
Actions*):

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | `base64 -w0 my.jks` |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | key alias |
| `SIGNING_KEY_PASSWORD` | key password |

## Code tour

| File | What it does |
| --- | --- |
| `Alarm.kt` | Alarm model, JSON storage, and the sentence the alarm speaks |
| `AlarmScheduler.kt` | Exact scheduling, repeat days and snooze |
| `Receivers.kt` | Alarm trigger, cancel snooze, rescheduling after a reboot |
| `AlarmService.kt` | Foreground service that rings: voice, vibration, notification |
| `VoiceEngine.kt` | Ranks voices by naturalness and renders the label to a WAV file |
| `VoicePlayer.kt` | Chime, voice, pause, repeat, volume ramp, and the fallbacks |
| `AlarmEditActivity.kt` | Editor: time, label, days, voice, speed, preview, recording |
| `AlarmRingActivity.kt` | Full-screen ringing screen |
| `MainActivity.kt` | Alarm list and permission banner |
