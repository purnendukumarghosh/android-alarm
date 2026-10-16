package com.voicealarm.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * One alarm. [days] is a bitmask where bit 0 = Monday … bit 6 = Sunday;
 * 0 means "ring once".
 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val label: String,
    val days: Int = 0,
    val enabled: Boolean = true,
    /** TTS voice name, or null to pick the most natural voice automatically. */
    val voiceName: String? = null,
    /** 1.0 = normal speed. Slightly slower than normal sounds more relaxed and human. */
    val speechRate: Float = 0.92f,
    /** Play the user's own recording instead of a synthesized voice. */
    val useRecording: Boolean = false,
    /** Add a friendly greeting ("Good morning! Time to wake up.") before the label. */
    val greeting: Boolean = true,
    /** Also say the alarm time ("It's 7 AM."). */
    val sayTime: Boolean = false,
    val vibrate: Boolean = true,
    val snoozeMinutes: Int = 10,
) {
    val isRepeating: Boolean get() = days != 0

    fun repeatsOn(calendarDayOfWeek: Int): Boolean {
        // Calendar: SUNDAY=1 … SATURDAY=7  ->  our bit: MONDAY=0 … SUNDAY=6
        val bit = (calendarDayOfWeek + 5) % 7
        return days and (1 shl bit) != 0
    }

    fun timeText(context: Context): String = formatTime(context, hour, minute)

    fun daysText(context: Context): String {
        if (days == 0) return context.getString(R.string.once)
        if (days == 0b1111111) return context.getString(R.string.every_day)
        if (days == 0b0011111) return context.getString(R.string.weekdays)
        if (days == 0b1100000) return context.getString(R.string.weekends)
        val names = context.resources.getStringArray(R.array.day_short_names)
        return (0..6).filter { days and (1 shl it) != 0 }.joinToString(" ") { names[it] }
    }

    /** The sentence the alarm speaks, built to sound like a person talking to you. */
    fun spokenText(name: String = ""): String {
        val parts = mutableListOf<String>()
        val cleanLabel = label.trim().trimEnd('.', '!', ',').replaceFirstChar { it.uppercase() }
        val morning = hour in 3..11
        val you = name.trim().takeIf { it.isNotEmpty() }
        if (greeting || cleanLabel.isEmpty()) {
            parts += when {
                morning && you != null -> "Good morning, $you! Time to wake up."
                morning -> "Good morning! Time to wake up."
                you != null && cleanLabel.isEmpty() -> "Hey $you, this is your alarm."
                you != null -> "Hey $you, this is your reminder."
                cleanLabel.isEmpty() -> "Hey, this is your alarm."
                else -> "Hey, this is your reminder."
            }
        }
        if (sayTime) parts += "It's ${spokenTime(hour, minute)}."
        if (cleanLabel.isNotEmpty()) {
            // A short pause before the label makes it stand out, the way a person
            // would say "...for interview."
            parts += "$cleanLabel."
        }
        return parts.joinToString(" ")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("hour", hour)
        put("minute", minute)
        put("label", label)
        put("days", days)
        put("enabled", enabled)
        put("voiceName", voiceName ?: JSONObject.NULL)
        put("speechRate", speechRate.toDouble())
        put("useRecording", useRecording)
        put("greeting", greeting)
        put("sayTime", sayTime)
        put("vibrate", vibrate)
        put("snoozeMinutes", snoozeMinutes)
    }

    companion object {
        fun fromJson(o: JSONObject) = Alarm(
            id = o.getInt("id"),
            hour = o.getInt("hour"),
            minute = o.getInt("minute"),
            label = o.optString("label", ""),
            days = o.optInt("days", 0),
            enabled = o.optBoolean("enabled", true),
            voiceName = if (o.isNull("voiceName")) null else o.optString("voiceName"),
            speechRate = o.optDouble("speechRate", 0.92).toFloat(),
            useRecording = o.optBoolean("useRecording", false),
            greeting = o.optBoolean("greeting", true),
            sayTime = o.optBoolean("sayTime", false),
            vibrate = o.optBoolean("vibrate", true),
            snoozeMinutes = o.optInt("snoozeMinutes", 10),
        )

        fun formatTime(context: Context, hour: Int, minute: Int): String {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
            }
            return android.text.format.DateFormat.getTimeFormat(context).format(cal.time)
        }

        /** "7 AM", "7:30 AM" – a form every TTS engine reads naturally. */
        fun spokenTime(hour: Int, minute: Int): String {
            val h12 = if (hour % 12 == 0) 12 else hour % 12
            val ampm = if (hour < 12) "AM" else "PM"
            return if (minute == 0) "$h12 $ampm" else "$h12:${"%02d".format(minute)} $ampm"
        }
    }
}

/** Persists alarms as JSON in SharedPreferences and owns their audio files. */
object AlarmStore {
    private const val PREFS = "alarms"
    private const val KEY_LIST = "list"
    private const val KEY_NEXT_ID = "next_id"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(context: Context): List<Alarm> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Alarm.fromJson(arr.getJSONObject(it)) }
                .sortedWith(compareBy({ it.hour }, { it.minute }))
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(context: Context, id: Int): Alarm? = all(context).firstOrNull { it.id == id }

    @Synchronized
    fun save(context: Context, alarm: Alarm) {
        val list = all(context).filter { it.id != alarm.id } + alarm
        write(context, list)
    }

    @Synchronized
    fun delete(context: Context, id: Int) {
        write(context, all(context).filter { it.id != id })
        speechFile(context, id).delete()
        recordingFile(context, id).delete()
    }

    @Synchronized
    fun newId(context: Context): Int {
        val p = prefs(context)
        val id = p.getInt(KEY_NEXT_ID, 1)
        p.edit().putInt(KEY_NEXT_ID, id + 1).apply()
        return id
    }

    private fun write(context: Context, list: List<Alarm>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun voiceDir(context: Context) = File(context.filesDir, "voices").apply { mkdirs() }

    /** Pre-rendered TTS audio for an alarm. */
    fun speechFile(context: Context, id: Int) = File(voiceDir(context), "alarm_$id.wav")

    /** The user's own recording for an alarm. */
    fun recordingFile(context: Context, id: Int) = File(voiceDir(context), "recording_$id.m4a")
}
