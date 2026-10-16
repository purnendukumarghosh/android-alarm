package com.voicealarm.app

import android.content.Context

/** App-wide preferences (Settings screen). New alarms start from these defaults. */
object AppSettings {
    private const val PREFS = "settings"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Your first name; the voice greets you with it ("Good morning, Priya!"). */
    fun userName(context: Context): String = prefs(context).getString("user_name", "")!!.trim()
    fun setUserName(context: Context, value: String) = prefs(context).edit().putString("user_name", value.trim()).apply()

    /** Default TTS voice for new alarms; null = automatic (most natural). */
    fun defaultVoice(context: Context): String? = prefs(context).getString("default_voice", null)
    fun setDefaultVoice(context: Context, value: String?) = prefs(context).edit().putString("default_voice", value).apply()

    fun defaultSpeechRate(context: Context): Float = prefs(context).getFloat("default_rate", 0.92f)
    fun setDefaultSpeechRate(context: Context, value: Float) = prefs(context).edit().putFloat("default_rate", value).apply()

    fun defaultSnooze(context: Context): Int = prefs(context).getInt("default_snooze", 10)
    fun setDefaultSnooze(context: Context, value: Int) = prefs(context).edit().putInt("default_snooze", value).apply()

    fun defaultVibrate(context: Context): Boolean = prefs(context).getBoolean("default_vibrate", true)
    fun setDefaultVibrate(context: Context, value: Boolean) = prefs(context).edit().putBoolean("default_vibrate", value).apply()

    /** Minutes an unanswered alarm keeps ringing before it stops (and is marked missed). */
    fun ringMinutes(context: Context): Int = prefs(context).getInt("ring_minutes", 10)
    fun setRingMinutes(context: Context, value: Int) = prefs(context).edit().putInt("ring_minutes", value).apply()

    /** Start quietly and grow louder over ~30 s. */
    fun gradualVolume(context: Context): Boolean = prefs(context).getBoolean("gradual_volume", true)
    fun setGradualVolume(context: Context, value: Boolean) = prefs(context).edit().putBoolean("gradual_volume", value).apply()

    /** A new alarm pre-filled with the user's defaults. */
    fun newAlarm(context: Context, id: Int, hour: Int, minute: Int, label: String = "") = Alarm(
        id = id,
        hour = hour,
        minute = minute,
        label = label,
        voiceName = defaultVoice(context),
        speechRate = defaultSpeechRate(context),
        vibrate = defaultVibrate(context),
        snoozeMinutes = defaultSnooze(context),
    )
}
