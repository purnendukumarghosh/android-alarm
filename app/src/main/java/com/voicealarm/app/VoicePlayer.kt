package com.voicealarm.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Plays an alarm the way a person would wake you: a soft chime, then the
 * spoken message, a short pause, and again – getting gradually louder.
 *
 * Audio source, in order of preference:
 *  1. the given voice file (pre-rendered TTS or the user's own recording),
 *  2. live text-to-speech, if the file is missing,
 *  3. the phone's default alarm ringtone, if TTS is unavailable – so the
 *     alarm always makes a sound.
 */
class VoicePlayer(context: Context, usage: Int = AudioAttributes.USAGE_ALARM) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = ctx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attrs)
        .build()

    private var chime: MediaPlayer? = null
    private var voice: MediaPlayer? = null
    private var liveTts: TextToSpeech? = null
    private var ringtone: MediaPlayer? = null

    private var stopped = true
    private var loop = false
    private var volume = 1f
    private var onFinished: (() -> Unit)? = null

    fun play(
        alarm: Alarm,
        voiceFile: File?,
        loop: Boolean,
        crescendo: Boolean,
        onFinished: (() -> Unit)? = null,
    ) {
        stop()
        stopped = false
        this.loop = loop
        this.onFinished = onFinished
        volume = if (crescendo) START_VOLUME else 1f
        audioManager.requestAudioFocus(focusRequest)

        chime = createPlayer(Chime.file(ctx))
        voice = voiceFile?.takeIf { it.exists() && it.length() > 0 }?.let { createPlayer(it) }

        if (voice != null) {
            cycle()
        } else {
            startLiveTts(alarm)
        }
        if (crescendo) main.postDelayed(crescendoStep, 1_000)
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        listOf(chime, voice, ringtone).forEach { p ->
            try {
                p?.stop()
            } catch (_: Exception) {
            }
            p?.release()
        }
        chime = null
        voice = null
        ringtone = null
        try {
            liveTts?.stop()
            liveTts?.shutdown()
        } catch (_: Exception) {
        }
        liveTts = null
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    // chime -> message -> pause -> repeat
    private fun cycle() {
        if (stopped) return
        val c = chime
        if (c != null) {
            c.setOnCompletionListener { main.postDelayed({ speak() }, 250) }
            c.start()
        } else {
            speak()
        }
    }

    private fun speak() {
        if (stopped) return
        val v = voice
        if (v != null) {
            v.setOnCompletionListener { afterMessage() }
            v.start()
        } else {
            speakLive()
        }
    }

    private fun afterMessage() {
        if (stopped) return
        if (loop) {
            main.postDelayed({ cycle() }, GAP_MS)
        } else {
            stopped = true
            audioManager.abandonAudioFocusRequest(focusRequest)
            onFinished?.invoke()
        }
    }

    // ---- live TTS fallback ------------------------------------------------

    private var liveText = ""

    private fun startLiveTts(alarm: Alarm) {
        liveText = alarm.spokenText(AppSettings.userName(ctx))
        // If TTS hasn't started within a few seconds, ring the normal way.
        val giveUp = Runnable { if (!stopped && liveTts != null) startRingtone() }
        main.postDelayed(giveUp, 8_000)
        // Post the init result: on instant failure TextToSpeech calls back from
        // inside its constructor, before liveTts is assigned.
        liveTts = TextToSpeech(ctx) { status -> main.post { onLiveTtsReady(status, alarm, giveUp) } }
    }

    private fun onLiveTtsReady(status: Int, alarm: Alarm, giveUp: Runnable) {
        main.removeCallbacks(giveUp)
        val tts = liveTts
        if (stopped) return
        if (tts == null || status != TextToSpeech.SUCCESS) {
            startRingtone()
            return
        }
        tts.setAudioAttributes(attrs)
        val voices = try {
            tts.voices
        } catch (e: Exception) {
            null
        } ?: emptySet()
        val preferred = alarm.voiceName?.let { n -> voices.firstOrNull { it.name == n } }
        (preferred ?: VoiceEngine.rankVoices(voices).firstOrNull { !it.isNetworkConnectionRequired })
            ?.let { tts.voice = it }
        tts.setSpeechRate(alarm.speechRate)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) {
                main.post { afterMessage() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                main.post { if (!stopped) startRingtone() }
            }
        })
        cycle()
    }

    private fun speakLive() {
        val tts = liveTts ?: return
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
        if (tts.speak(liveText, TextToSpeech.QUEUE_FLUSH, params, "alarm") != TextToSpeech.SUCCESS) {
            startRingtone()
        }
    }

    // ---- last-resort ringtone -------------------------------------------

    private fun startRingtone() {
        if (ringtone != null) return
        Log.w(TAG, "Falling back to the default alarm ringtone")
        try {
            liveTts?.shutdown()
        } catch (_: Exception) {
        }
        liveTts = null
        main.removeCallbacksAndMessages(null)
        val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not play ringtone", e)
            null
        }
    }

    // ---- helpers ---------------------------------------------------------

    private val crescendoStep = object : Runnable {
        override fun run() {
            if (stopped) return
            volume = (volume + VOLUME_STEP).coerceAtMost(1f)
            chime?.setVolume(volume, volume)
            voice?.setVolume(volume, volume)
            if (volume < 1f) main.postDelayed(this, 1_000)
        }
    }

    private fun createPlayer(file: File): MediaPlayer? = try {
        MediaPlayer().apply {
            setAudioAttributes(attrs)
            FileInputStream(file).use { setDataSource(it.fd) }
            prepare()
            setVolume(volume, volume)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Cannot play $file", e)
        null
    }

    companion object {
        private const val TAG = "VoicePlayer"
        private const val GAP_MS = 2_500L
        private const val START_VOLUME = 0.25f
        private const val VOLUME_STEP = 0.025f // full volume after ~30 s
    }
}

/** A gentle three-note bell, synthesized once and cached as a WAV file. */
object Chime {
    private const val RATE = 44_100

    fun file(context: Context): File {
        val f = File(context.filesDir, "chime_v1.wav")
        if (!f.exists() || f.length() == 0L) {
            try {
                write(f)
            } catch (e: Exception) {
                Log.e("Chime", "Could not write chime", e)
            }
        }
        return f
    }

    private fun write(f: File) {
        val notes = listOf(0.0 to 659.25, 0.16 to 783.99, 0.32 to 1046.50) // E5 G5 C6
        val n = (RATE * 1.9).toInt()
        val pcm = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            var s = 0.0
            for ((start, freq) in notes) {
                if (t < start) continue
                val x = t - start
                val env = (1 - exp(-x * 300)) * exp(-x * 3.0)
                s += env * (sin(2 * PI * freq * x) +
                    0.30 * exp(-x * 4) * sin(2 * PI * 2 * freq * x) +
                    0.10 * exp(-x * 6) * sin(2 * PI * 3 * freq * x))
            }
            pcm.putShort((s * 0.22 * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort())
        }
        val tmp = File(f.path + ".part")
        FileOutputStream(tmp).use { out ->
            out.write(wavHeader(n * 2))
            out.write(pcm.array())
        }
        tmp.renameTo(f)
    }

    private fun wavHeader(dataLen: Int): ByteArray =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataLen); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataLen)
        }.array()
}
