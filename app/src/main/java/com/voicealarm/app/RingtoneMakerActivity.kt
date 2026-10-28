package com.voicealarm.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.voicealarm.app.audio.AudioConverter
import com.voicealarm.app.audio.RingtoneExporter
import com.voicealarm.app.audio.Sound
import com.voicealarm.app.audio.SoundLibrary
import com.voicealarm.app.ui.AppBar
import com.voicealarm.app.ui.FlowLayout
import com.voicealarm.app.ui.GlowBackgroundDrawable
import com.voicealarm.app.ui.Neon
import com.voicealarm.app.ui.NeonButton
import com.voicealarm.app.ui.NeonChip
import com.voicealarm.app.ui.NeonSheet
import com.voicealarm.app.ui.NeonSlider
import com.voicealarm.app.ui.ProgressLine
import com.voicealarm.app.ui.SoundPreview
import com.voicealarm.app.ui.dpi
import com.voicealarm.app.ui.glassCard
import com.voicealarm.app.ui.hairline
import com.voicealarm.app.ui.hbox
import com.voicealarm.app.ui.icon
import com.voicealarm.app.ui.label
import com.voicealarm.app.ui.overline
import com.voicealarm.app.ui.pages.sunkenInput
import com.voicealarm.app.ui.soundRow
import com.voicealarm.app.ui.vbox
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Ringtone maker: pick any video (or song) on the phone, choose a clip, and
 * convert its sound to AAC (.m4a), or MP3 when the sound already is MP3.
 * The result is added to the app's alarm sounds and saved to the phone's
 * Ringtones folder, and can be made the phone's ringtone in one tap.
 *
 * Also opens from the share sheet ("Share → Voice Alarm" on any video).
 * Started for a result with [EXTRA_FOR_ALARM], it hands the new sound's id
 * back as [RESULT_SOUND_ID].
 */
class RingtoneMakerActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val preview by lazy { SoundPreview(this) }
    private var forAlarm = false

    // source
    private var sourceUri: Uri? = null
    private var info: AudioConverter.Info? = null
    private var startMs = 0L
    private var lengthMs = DEFAULT_LENGTH_MS // ≤ 0 = whole
    private var format = AudioConverter.Format.AAC

    // conversion
    private var cancel: AtomicBoolean? = null
    private var result: Sound? = null
    private var exportedUri: Uri? = null
    private var pendingDefaultType = -1
    private var pendingExport: (() -> Unit)? = null

    // views
    private lateinit var sourceCard: LinearLayout
    private lateinit var clipSection: LinearLayout
    private lateinit var resultSection: LinearLayout
    private lateinit var soundsCard: LinearLayout
    private lateinit var nameEdit: EditText
    private lateinit var rangeText: TextView
    private lateinit var startSlider: NeonSlider
    private lateinit var formatNote: TextView
    private lateinit var convertButton: NeonButton
    private lateinit var progressLine: ProgressLine
    private lateinit var playClip: NeonButton
    private val lengthChips = mutableListOf<Pair<Long, NeonChip>>()
    private val formatChips = mutableListOf<Pair<AudioConverter.Format, NeonChip>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forAlarm = intent.getBooleanExtra(EXTRA_FOR_ALARM, false)
        setContentView(buildUi())
        preview.onStateChange = { renderSounds(); refreshPlayClip() }
        renderSource()
        renderSounds()
        incomingUri(intent)?.let { load(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingUri(intent)?.let { load(it) }
    }

    private fun incomingUri(i: Intent): Uri? = when (i.action) {
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") (i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        Intent.ACTION_VIEW -> i.data
        else -> null
    }

    // ---- UI -----------------------------------------------------------------------------

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { background = GlowBackgroundDrawable() }
        val column = vbox()
        val appBar = AppBar(this)
        appBar.setNavigation(R.drawable.ic_arrow_back, getString(R.string.maker_back)) { finish() }
        appBar.title = getString(R.string.maker_title)
        appBar.subtitle = getString(R.string.maker_subtitle)
        column.addView(appBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(64)).apply { topMargin = dpi(4) })
        val body = vbox().apply {
            setPadding(dpi(20), dpi(4), dpi(20), dpi(48))
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(body)
        }
        appBar.trackScroll(scroll)
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column)

        fun section(title: Int, content: View, top: Int = 22): View {
            val box = vbox()
            box.addView(overline(getString(title)))
            box.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(4) })
            body.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(top) })
            return box
        }

        sourceCard = glassCard(padH = 18, padV = 18, pressable = true, radiusDp = 24)
        sourceCard.setOnClickListener { pick() }
        section(R.string.maker_source, sourceCard, top = 6)

        // Clip, format, name and convert: shown once a file is loaded.
        clipSection = vbox()
        val clip = glassCard(padV = 12)
        rangeText = label("", R.style.Text_Title, sizeSp = 16f)
        clip.addView(rangeText)
        clip.addView(label(getString(R.string.maker_start), R.style.Text_Caption, sizeSp = 12f).apply { setPadding(0, dpi(10), 0, 0) })
        startSlider = NeonSlider(this).apply {
            min = 0f; max = 1f; step = 1000f
            onChange = { v, _ -> startMs = v.toLong(); refreshRange() }
        }
        clip.addView(startSlider)
        clip.addView(label(getString(R.string.maker_length), R.style.Text_Caption, sizeSp = 12f).apply { setPadding(0, dpi(2), 0, dpi(6)) })
        val lengths = FlowLayout(this)
        listOf(20_000L, 30_000L, 45_000L, 60_000L, 0L).forEach { len ->
            val chip = NeonChip(this, if (len > 0) getString(R.string.maker_seconds, len / 1000) else getString(R.string.maker_whole))
            chip.onToggle = { lengthMs = len; refreshRange() }
            lengthChips += len to chip
            lengths.addView(chip)
        }
        clip.addView(lengths)
        playClip = NeonButton(this, getString(R.string.maker_play_clip), R.drawable.ic_play_arrow_fill, NeonButton.Kind.GHOST)
        playClip.setOnClickListener { toggleClipPreview() }
        clip.addView(playClip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(4) })
        clipSection.addView(sectionBox(R.string.maker_clip, clip))

        val fmt = glassCard(padV = 12)
        val fmtRow = FlowLayout(this)
        listOf(AudioConverter.Format.AAC to R.string.maker_aac, AudioConverter.Format.MP3 to R.string.maker_mp3).forEach { (f, res) ->
            val chip = NeonChip(this, getString(res))
            chip.onToggle = { selectFormat(f) }
            formatChips += f to chip
            fmtRow.addView(chip)
        }
        fmt.addView(fmtRow)
        formatNote = label("", R.style.Text_Body, sizeSp = 13f).apply { setPadding(dpi(2), dpi(8), dpi(2), 0) }
        fmt.addView(formatNote)
        clipSection.addView(sectionBox(R.string.maker_format, fmt))

        val (nameBox, edit) = sunkenInput(getString(R.string.maker_name_hint), R.drawable.ic_label)
        nameEdit = edit
        clipSection.addView(sectionBox(R.string.maker_name, nameBox))

        convertButton = NeonButton(this, getString(R.string.maker_convert), R.drawable.ic_content_cut, NeonButton.Kind.PRIMARY)
        convertButton.setOnClickListener { if (cancel != null) cancel?.set(true) else convert() }
        clipSection.addView(convertButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(22) })
        progressLine = ProgressLine(this).apply { visibility = View.INVISIBLE }
        clipSection.addView(progressLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10); marginStart = dpi(24); marginEnd = dpi(24) })
        body.addView(clipSection, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        resultSection = vbox()
        body.addView(resultSection, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        soundsCard = glassCard(padV = 4)
        section(R.string.maker_your_sounds, soundsCard, top = 26)
        return root
    }

    private fun sectionBox(title: Int, content: View) = vbox().apply {
        addView(overline(getString(title)))
        addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(4) })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(22) }
    }

    private fun renderSource(reading: Boolean = false) {
        sourceCard.removeAllViews()
        val i = info
        val row = hbox()
        row.addView(icon(if (i == null || i.hasVideo) R.drawable.ic_movie else R.drawable.ic_audio_file, Neon.TEXT1, 30),
            LinearLayout.LayoutParams(dpi(30), dpi(30)).apply { marginEnd = dpi(16) })
        val texts = vbox()
        if (i == null) {
            texts.addView(label(getString(if (reading) R.string.maker_reading else R.string.maker_pick), R.style.Text_Title, sizeSp = 17f))
            texts.addView(label(getString(R.string.maker_pick_sub), R.style.Text_Body, sizeSp = 13f))
        } else {
            texts.addView(label(sourceName(), R.style.Text_Title, sizeSp = 16f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            val detail = if (i.hasVideo) getString(R.string.maker_info_video, Format.clock(i.durationMs), soundDescription(i))
            else getString(R.string.maker_info_audio, Format.clock(i.durationMs), soundDescription(i))
            texts.addView(label(detail, R.style.Text_Body, sizeSp = 13f))
            texts.addView(label(getString(R.string.maker_change), R.style.Text_Caption, Neon.TEXT1, 13f).apply { setPadding(0, dpi(6), 0, 0) })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sourceCard.addView(row)
        clipSection.visibility = if (i == null) View.GONE else View.VISIBLE
    }

    private fun soundDescription(i: AudioConverter.Info): String {
        val ch = when (i.channels) { 1 -> "mono"; 2 -> "stereo"; 0 -> ""; else -> "${i.channels} ch" }
        val rate = if (i.sampleRate > 0) "%.1f kHz".format(i.sampleRate / 1000f).replace(".0 ", " ") else ""
        return listOf(i.codecName, ch, rate).filter { it.isNotEmpty() }.joinToString(", ")
    }

    private fun sourceName(): String = sourceUri?.let { SoundLibrary.displayName(this, it) } ?: ""

    private fun refreshRange() {
        val i = info ?: return
        val dur = i.durationMs.coerceAtLeast(1000)
        startMs = startMs.coerceIn(0, (dur - 1000).coerceAtLeast(0))
        val end = clipEnd()
        rangeText.text = getString(R.string.maker_range, Format.clock(startMs), Format.clock(end), Format.clock(end - startMs))
        lengthChips.forEach { (len, chip) -> chip.setOn(len == lengthMs) }
        if (preview.playingKey == CLIP_KEY) preview.stop()
    }

    private fun clipEnd(): Long {
        val dur = info?.durationMs ?: 0L
        return when {
            lengthMs <= 0 -> dur
            dur <= 0 -> startMs + lengthMs // length unknown: just cut after lengthMs
            else -> (startMs + lengthMs).coerceAtMost(dur)
        }
    }

    private fun selectFormat(f: AudioConverter.Format) {
        val i = info ?: return
        format = if (f == AudioConverter.Format.MP3 && !i.canCopyToMp3) AudioConverter.Format.AAC else f
        formatChips.forEach { (ff, chip) -> chip.setOn(ff == format) }
        formatNote.text = when {
            f == AudioConverter.Format.MP3 && !i.canCopyToMp3 -> getString(R.string.maker_mp3_no, i.codecName)
            format == AudioConverter.Format.MP3 -> getString(R.string.maker_mp3_copy)
            i.audioMime == AudioConverter.MIME_AAC -> getString(R.string.maker_aac_copy)
            else -> getString(R.string.maker_aac_encode)
        }
    }

    private fun refreshPlayClip() {
        if (!::playClip.isInitialized) return
        playClip.setText(getString(if (preview.playingKey == CLIP_KEY) R.string.maker_stop else R.string.maker_play_clip))
    }

    private fun renderSounds() {
        soundsCard.removeAllViews()
        val sounds = SoundLibrary.all(this)
        if (sounds.isEmpty()) {
            soundsCard.addView(label(getString(R.string.maker_sounds_empty), R.style.Text_Body, sizeSp = 13f).apply { setPadding(0, dpi(12), 0, dpi(12)) })
            return
        }
        sounds.forEachIndexed { idx, s ->
            if (idx > 0) soundsCard.addView(hairline())
            soundsCard.addView(soundRow(s, preview.playingKey == s.id, null,
                onPlay = { s.file(this).takeIf { it.exists() }?.let { preview.toggle(s.id, it) } },
                onClick = { soundOptions(s) }))
        }
    }

    private fun renderResult() {
        resultSection.removeAllViews()
        val s = result ?: return
        val card = glassCard(padV = 14, outline = Neon.AQUA)
        val head = hbox()
        head.addView(icon(R.drawable.ic_check_circle, Neon.TEXT1, 26), LinearLayout.LayoutParams(dpi(26), dpi(26)).apply { marginEnd = dpi(12) })
        val t = vbox()
        t.addView(label(s.name, R.style.Text_Title, sizeSp = 17f))
        val f = s.file(this)
        t.addView(label(getString(R.string.maker_done_detail, f.extension.uppercase(), Format.clock(s.durationMs), Format.size(f.length())), R.style.Text_Body, sizeSp = 13f))
        head.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(head)
        fun status(text: String, ok: Boolean) {
            card.addView(hbox().apply {
                setPadding(0, dpi(10), 0, 0)
                addView(icon(if (ok) R.drawable.ic_check else R.drawable.ic_info, if (ok) Neon.TEXT2 else Neon.AMBER, 18), LinearLayout.LayoutParams(dpi(18), dpi(18)).apply { marginEnd = dpi(10) })
                addView(label(text, R.style.Text_Body, sizeSp = 13f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        status(getString(R.string.maker_done_library), true)
        when {
            exportedUri != null -> status(getString(R.string.maker_done_phone), true)
            pendingExport != null -> status(getString(R.string.maker_done_phone_permission), false)
            else -> status(getString(R.string.maker_done_phone_failed), false)
        }
        fun action(text: Int, kind: NeonButton.Kind, iconRes: Int, onClick: () -> Unit) {
            card.addView(NeonButton(this, getString(text), iconRes, kind).apply { setOnClickListener { onClick() } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10) })
        }
        if (forAlarm) {
            action(R.string.maker_use_here, NeonButton.Kind.PRIMARY, R.drawable.ic_alarm) {
                setResult(RESULT_OK, Intent().putExtra(RESULT_SOUND_ID, s.id))
                finish()
            }
        } else {
            action(R.string.maker_use_alarm, NeonButton.Kind.PRIMARY, R.drawable.ic_alarm_add) {
                startActivity(Intent(this, AlarmEditActivity::class.java).putExtra(AlarmEditActivity.EXTRA_MUSIC_ID, s.id))
            }
        }
        if (exportedUri != null) {
            action(R.string.maker_set_ringtone, NeonButton.Kind.SECONDARY, R.drawable.ic_ring_volume) { setPhoneDefault(RingtoneManager.TYPE_RINGTONE) }
            action(R.string.maker_set_alarm, NeonButton.Kind.SECONDARY, R.drawable.ic_alarm) { setPhoneDefault(RingtoneManager.TYPE_ALARM) }
        } else if (pendingExport != null) {
            action(R.string.maker_set_ringtone, NeonButton.Kind.SECONDARY, R.drawable.ic_ring_volume) { requestStorageThenExport() }
        }
        resultSection.addView(sectionBox(R.string.maker_done, card))
    }

    // ---- actions ------------------------------------------------------------------------

    private fun pick() {
        if (cancel != null) return
        preview.stop()
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
        try {
            startActivityForResult(open, REQ_PICK)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*"), REQ_PICK)
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    private fun load(uri: Uri) {
        preview.stop()
        sourceUri = uri
        info = null
        result = null
        exportedUri = null
        pendingExport = null
        renderResult()
        renderSource(reading = true)
        val app = applicationContext
        thread(name = "probe") {
            val probed = try {
                Result.success(AudioConverter.probe(app, uri))
            } catch (e: Exception) {
                Result.failure(e)
            }
            main.post {
                if (isDestroyed || sourceUri != uri) return@post
                probed.onSuccess { i ->
                    info = i
                    startMs = 0
                    lengthMs = if (i.durationMs in 1..DEFAULT_LENGTH_MS + 5_000) 0 else DEFAULT_LENGTH_MS
                    startSlider.max = (i.durationMs - 1000).coerceAtLeast(1000).toFloat()
                    startSlider.value = 0f
                    nameEdit.setText(sourceName().substringBeforeLast('.').take(60))
                    renderSource()
                    refreshRange()
                    selectFormat(if (i.canCopyToMp3) AudioConverter.Format.MP3 else AudioConverter.Format.AAC)
                }.onFailure { e ->
                    sourceUri = null
                    renderSource()
                    toast(messageFor(e))
                }
            }
        }
    }

    private fun toggleClipPreview() {
        val uri = sourceUri ?: return
        if (preview.playingKey == CLIP_KEY) preview.stop() else preview.play(CLIP_KEY, uri, startMs, clipEnd() - startMs)
    }

    private fun convert() {
        val uri = sourceUri ?: return
        val i = info ?: return
        preview.stop()
        val flag = AtomicBoolean(false)
        cancel = flag
        result = null
        exportedUri = null
        pendingExport = null
        renderResult()
        val name = nameEdit.text.toString().trim().ifEmpty { sourceName().substringBeforeLast('.') }.ifEmpty { getString(R.string.maker_title) }
        val fmt = format
        val start = startMs
        val end = if (lengthMs <= 0) 0L else clipEnd()
        val out = File(File(cacheDir, "convert").apply { mkdirs() }, "clip.${fmt.extension}")
        convertButton.setText(getString(R.string.maker_cancel))
        progressLine.progress = 0f
        progressLine.visibility = View.VISIBLE
        rangeText.alpha = 0.5f
        val app = applicationContext
        var lastPct = -1
        thread(name = "convert") {
            val outcome = try {
                val r = AudioConverter.convert(app, uri, out, fmt, start, end, flag) { p ->
                    val pct = (p * 100).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        main.post {
                            progressLine.progress = p
                            if (cancel === flag) convertButton.setText(getString(R.string.maker_converting, pct) + "  ·  " + getString(R.string.maker_cancel))
                        }
                    }
                }
                val sound = SoundLibrary.addConverted(app, r.file, name, r.durationMs)
                out.delete()
                Result.success(sound)
            } catch (e: Exception) {
                out.delete()
                Result.failure(e)
            }
            main.post {
                if (isDestroyed) return@post
                cancel = null
                convertButton.setText(getString(R.string.maker_convert))
                progressLine.visibility = View.INVISIBLE
                rangeText.alpha = 1f
                outcome.onSuccess { sound ->
                    result = sound
                    renderSounds()
                    exportToPhone(sound)
                }.onFailure { e -> toast(messageFor(e)) }
            }
        }
    }

    /** Saves the new sound to Ringtones/Voice Alarm so the phone's own pickers list it. */
    private fun exportToPhone(sound: Sound) {
        val job = {
            val app = applicationContext
            val mime = if (sound.fileName.endsWith(".mp3")) "audio/mpeg" else "audio/mp4"
            thread(name = "export") {
                val uri = RingtoneExporter.export(app, sound.file(app), sound.name, mime)
                main.post {
                    if (isDestroyed) return@post
                    pendingExport = null
                    exportedUri = uri
                    renderResult()
                }
            }
            Unit
        }
        if (RingtoneExporter.needsStoragePermission() &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingExport = job
            renderResult()
            requestStorageThenExport()
        } else {
            renderResult()
            job()
        }
    }

    private fun requestStorageThenExport() {
        requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
    }

    private fun setPhoneDefault(type: Int) {
        val uri = exportedUri ?: return
        if (!RingtoneExporter.canSetDefault(this)) {
            pendingDefaultType = type
            toast(getString(R.string.maker_allow_settings))
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
            }
            return
        }
        val ok = RingtoneExporter.setDefault(this, uri, type)
        toast(getString(if (!ok) R.string.maker_set_failed else if (type == RingtoneManager.TYPE_ALARM) R.string.maker_set_alarm_done else R.string.maker_set_ringtone_done))
    }

    private fun soundOptions(s: Sound) {
        val sheet = NeonSheet(this)
        sheet.title = s.name
        sheet.addAction(getString(if (forAlarm) R.string.maker_use_here else R.string.maker_use_alarm), NeonButton.Kind.PRIMARY, R.drawable.ic_alarm_add) {
            sheet.dismiss()
            if (forAlarm) {
                setResult(RESULT_OK, Intent().putExtra(RESULT_SOUND_ID, s.id))
                finish()
            } else {
                startActivity(Intent(this, AlarmEditActivity::class.java).putExtra(AlarmEditActivity.EXTRA_MUSIC_ID, s.id))
            }
        }
        sheet.addAction(getString(R.string.maker_delete), NeonButton.Kind.DANGER, R.drawable.ic_delete) {
            sheet.dismiss()
            confirmDelete(s)
        }
        sheet.show()
    }

    private fun confirmDelete(s: Sound) {
        val sheet = NeonSheet(this)
        sheet.title = getString(R.string.maker_delete_confirm, s.name)
        sheet.subtitle = getString(R.string.maker_delete_body)
        sheet.addAction(getString(R.string.maker_delete), NeonButton.Kind.DANGER, R.drawable.ic_delete) {
            sheet.dismiss()
            if (preview.playingKey == s.id) preview.stop()
            SoundLibrary.delete(this, s.id)
            if (result?.id == s.id) {
                result = null
                renderResult()
            }
            renderSounds()
        }
        sheet.addAction(getString(R.string.maker_keep), NeonButton.Kind.GHOST) { sheet.dismiss() }
        sheet.show()
    }

    private fun messageFor(e: Throwable): String = getString(
        when ((e as? AudioConverter.ConversionException)?.reason) {
            AudioConverter.Reason.NO_AUDIO -> R.string.maker_no_audio
            AudioConverter.Reason.UNSUPPORTED -> R.string.maker_unsupported
            AudioConverter.Reason.EMPTY -> R.string.maker_empty
            AudioConverter.Reason.CANCELLED -> R.string.maker_cancelled
            else -> R.string.maker_failed
        }
    )

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) data?.data?.let { load(it) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) pendingExport?.invoke()
    }

    override fun onResume() {
        super.onResume()
        // Back from "Modify system settings": finish what the user asked for.
        val type = pendingDefaultType
        if (type >= 0 && RingtoneExporter.canSetDefault(this)) {
            pendingDefaultType = -1
            setPhoneDefault(type)
        }
    }

    override fun onPause() {
        super.onPause()
        preview.stop()
    }

    override fun onDestroy() {
        cancel?.set(true)
        main.removeCallbacksAndMessages(null)
        preview.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FOR_ALARM = "for_alarm"
        const val RESULT_SOUND_ID = "sound_id"
        private const val REQ_PICK = 11
        private const val REQ_STORAGE = 12
        private const val CLIP_KEY = "clip"
        private const val DEFAULT_LENGTH_MS = 30_000L
    }
}
