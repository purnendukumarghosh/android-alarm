package com.voicealarm.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import com.voicealarm.app.Format
import com.voicealarm.app.R
import com.voicealarm.app.audio.Sound
import java.io.File

/**
 * Plays one sound (a library file, or a clip of any Uri) for previews.
 * Only one plays at a time; [onStateChange] reports the key now playing (or null).
 */
class SoundPreview(context: Context) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    var playingKey: String? = null
        private set
    var onStateChange: ((String?) -> Unit)? = null

    fun toggle(key: String, file: File) {
        if (playingKey == key) stop() else play(key, Uri.fromFile(file), 0, 0)
    }

    /** Plays [uri] from [startMs] for [lengthMs] (≤ 0 = to the end). */
    fun play(key: String, uri: Uri, startMs: Long, lengthMs: Long) {
        stop()
        val p = MediaPlayer()
        player = p
        playingKey = key
        onStateChange?.invoke(key)
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setDataSource(ctx, uri)
            p.setOnPreparedListener {
                if (player !== p) return@setOnPreparedListener
                if (startMs > 0) p.seekTo(startMs.toInt())
                p.start()
                if (lengthMs > 0) main.postDelayed({ if (player === p) stop() }, lengthMs)
            }
            p.setOnCompletionListener { if (player === p) stop() }
            p.setOnErrorListener { _, _, _ -> if (player === p) stop(); true }
            p.prepareAsync()
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        val p = player ?: return
        player = null
        playingKey = null
        try {
            p.stop()
        } catch (_: Exception) {
        }
        p.release()
        onStateChange?.invoke(null)
    }
}

/**
 * A row for a library sound: ringed line icon (or a selection check),
 * name, "Song · 3:24", and a play/stop button.
 */
fun Context.soundRow(
    sound: Sound,
    playing: Boolean,
    selected: Boolean?,
    onPlay: () -> Unit,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
): LinearLayout {
    val kind = getString(if (sound.kind == "video") R.string.sound_kind_video else R.string.sound_kind_music)
    val play = IconButton3D(this, if (playing) R.drawable.ic_stop_fill else R.drawable.ic_play_arrow_fill,
        getString(if (playing) R.string.sound_stop_cd else R.string.sound_play_cd, sound.name), sizeDp = 40)
    play.setOnClickListener { onPlay() }
    val icon = when (selected) {
        true -> R.drawable.ic_check_circle_fill
        false -> R.drawable.ic_radio_button_checked
        null -> if (sound.kind == "video") R.drawable.ic_movie else R.drawable.ic_music_note
    }
    val row = listRow(icon, if (selected == false) Neon.TEXT3 else Neon.TEXT1, sound.name, "$kind · ${Format.clock(sound.durationMs)}", play, onClick)
    (row.getChildAt(1) as? LinearLayout)?.let { texts ->
        (texts.getChildAt(0) as? android.widget.TextView)?.apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    }
    if (selected != null) row.isSelected = selected
    onLongClick?.let { lc -> row.setOnLongClickListener { lc(); true } }
    return row
}

/** Thin progress line: a hairline track with a white fill. */
class ProgressLine(context: Context) : View(context) {
    var progress = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    private val track = Paint().apply { color = 0x2EFFFFFF }
    private val fill = Paint().apply { color = Neon.TEXT1 }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(resolveSize(dpi(200), widthMeasureSpec), dpi(3))

    override fun onDraw(canvas: Canvas) {
        val h = dp(1.5f)
        val cy = height / 2f
        canvas.drawRect(0f, cy - h / 2, width.toFloat(), cy + h / 2, track)
        canvas.drawRect(0f, cy - h / 2, width * progress, cy + h / 2, fill)
    }
}
