package com.voicealarm.app

import android.content.Context

/** Saves an alarm, prepares its voice audio and schedules it. Shared by every screen that creates alarms. */
object AlarmSaver {

    /**
     * Stores [alarm], renders its spoken message with [engine] (unless it uses
     * a recording), then schedules it. [done] runs on the main thread with
     * `voiceReady = false` when the voice could not be pre-rendered; the alarm
     * then speaks live when it rings.
     *
     * For recorded alarms the caller must already have written
     * [AlarmStore.recordingFile].
     */
    fun save(context: Context, alarm: Alarm, engine: VoiceEngine?, done: (voiceReady: Boolean) -> Unit) {
        val app = context.applicationContext
        if (alarm.useRecording) {
            commit(app, alarm)
            done(AlarmStore.recordingFile(app, alarm.id).exists())
            return
        }
        val speechFile = AlarmStore.speechFile(app, alarm.id)
        if (engine == null || !engine.isReady) {
            speechFile.delete() // never keep audio for an old label
            commit(app, alarm)
            done(false)
            return
        }
        engine.render(alarm.spokenText(AppSettings.userName(app)), alarm.voiceName, alarm.speechRate, speechFile) { ok ->
            if (!ok) speechFile.delete() // the alarm will speak live instead
            commit(app, alarm)
            done(ok)
        }
    }

    /** Re-renders the voice of every text-to-speech alarm (e.g. after the user's name changed). */
    fun rerenderAll(context: Context, engine: VoiceEngine, done: () -> Unit) {
        val pending = AlarmStore.all(context).filter { !it.useRecording }.toMutableList()
        fun next() {
            val alarm = pending.removeFirstOrNull() ?: return done()
            save(context, alarm, engine) { next() }
        }
        next()
    }

    private fun commit(context: Context, alarm: Alarm) {
        AlarmStore.save(context, alarm)
        AlarmScheduler.schedule(context, alarm)
    }
}
