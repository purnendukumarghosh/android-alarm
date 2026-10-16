package com.voicealarm.app

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Wraps the phone's text-to-speech engine. Picks the most natural-sounding
 * voice available and renders alarm messages to audio files ahead of time, so
 * the alarm never depends on the TTS engine (or the internet) at ring time.
 */
class VoiceEngine(context: Context, private val onReady: (Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, (Boolean) -> Unit>()
    private var counter = 0
    private var tts: TextToSpeech? = null

    var isReady = false
        private set

    init {
        // Posted so the callback never runs inside the constructor (TextToSpeech
        // reports an instant failure synchronously), before `tts` is assigned.
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                isReady = status == TextToSpeech.SUCCESS && tts != null
                if (isReady) tts?.setOnUtteranceProgressListener(listener)
                onReady(isReady)
            }
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {}
        override fun onDone(utteranceId: String) {
            main.post { finish(utteranceId, true) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            main.post { finish(utteranceId, false) }
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            Log.w(TAG, "TTS error $errorCode for $utteranceId")
            main.post { finish(utteranceId, false) }
        }
    }

    /** Usable voices, most natural first. */
    fun naturalVoices(): List<Voice> = rankVoices(
        try {
            tts?.voices?.toList()
        } catch (e: Exception) {
            null
        } ?: emptyList()
    )

    /**
     * Renders [text] to [out] (a WAV file). If the chosen voice is an online
     * one and synthesis fails (e.g. no internet), retries with the best
     * offline voice. [done] is called on the main thread.
     */
    fun render(text: String, voiceName: String?, rate: Float, out: File, done: (Boolean) -> Unit) {
        if (!isReady || tts == null) {
            done(false)
            return
        }
        val voices = naturalVoices()
        val first = voiceName?.let { name -> voices.firstOrNull { it.name == name } } ?: voices.firstOrNull()
        attempt(text, first, rate, out) { ok ->
            if (ok || first == null || !first.isNetworkConnectionRequired) {
                done(ok)
            } else {
                val offline = voices.firstOrNull {
                    !it.isNetworkConnectionRequired && it.locale.language == first.locale.language
                } ?: voices.firstOrNull { !it.isNetworkConnectionRequired }
                if (offline == null) done(false) else attempt(text, offline, rate, out, done)
            }
        }
    }

    private fun attempt(text: String, voice: Voice?, rate: Float, out: File, done: (Boolean) -> Unit) {
        val engine = tts ?: return done(false)
        if (voice != null) engine.voice = voice
        engine.setSpeechRate(rate)
        engine.setPitch(1.0f)

        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".part")
        tmp.delete()
        val id = "render-${counter++}"
        pending[id] = { ok ->
            val good = ok && tmp.length() > 2_000
            if (good) {
                out.delete()
                tmp.renameTo(out)
            } else {
                tmp.delete()
            }
            done(good)
        }
        val result = try {
            engine.synthesizeToFile(text, Bundle(), tmp, id)
        } catch (e: Exception) {
            TextToSpeech.ERROR
        }
        if (result != TextToSpeech.SUCCESS) {
            finish(id, false)
        } else {
            main.postDelayed({ finish(id, false) }, RENDER_TIMEOUT_MS)
        }
    }

    private fun finish(id: String, ok: Boolean) {
        pending.remove(id)?.invoke(ok)
    }

    fun shutdown() {
        pending.clear()
        main.removeCallbacksAndMessages(null)
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
    }

    companion object {
        private const val TAG = "VoiceEngine"
        private const val RENDER_TIMEOUT_MS = 30_000L

        /**
         * Ranks voices so the most human-sounding one comes first: the phone's
         * own language and accent, then the engine's quality rating; online
         * voices (usually the newest neural models) win ties.
         */
        fun rankVoices(all: Collection<Voice>): List<Voice> {
            val device = Locale.getDefault()
            val installed = all.filter { v ->
                val features = v.features ?: emptySet()
                !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            val relevant = installed.filter {
                it.locale.language == device.language || it.locale.language == "en"
            }.ifEmpty { installed }
            return relevant.sortedWith(
                compareByDescending<Voice> { score(it, device) }.thenBy { it.name }
            )
        }

        private fun score(v: Voice, device: Locale): Int {
            var s = 0
            if (v.locale.language == device.language) s += 1000
            if (v.locale.country == device.country) s += 300
            s += v.quality
            if (v.isNetworkConnectionRequired) s += 50
            if (v.features?.contains("legacySetLanguageVoice") == true) s -= 150
            return s
        }

        /** "English (India) · Voice ENE · HD · online" */
        fun describe(v: Voice): String {
            val lang = v.locale.getDisplayName(Locale.getDefault())
            val code = Regex("-x-([a-z]+)").find(v.name)?.groupValues?.get(1)?.uppercase()
            val name = if (code != null) "Voice $code" else v.name
            val quality = when {
                v.quality >= Voice.QUALITY_VERY_HIGH -> "HD"
                v.quality >= Voice.QUALITY_HIGH -> "High quality"
                else -> "Standard"
            }
            val online = if (v.isNetworkConnectionRequired) " · online" else ""
            return "$lang · $name · $quality$online"
        }
    }
}
