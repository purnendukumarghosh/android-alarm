package com.voicealarm.app

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.Voice
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.voicealarm.app.ui.AppBar
import com.voicealarm.app.ui.FlowLayout
import com.voicealarm.app.ui.GlassCardDrawable
import com.voicealarm.app.ui.GlowBackgroundDrawable
import com.voicealarm.app.ui.IconButton3D
import com.voicealarm.app.ui.Neon
import com.voicealarm.app.ui.NeonButton
import com.voicealarm.app.ui.NeonChip
import com.voicealarm.app.ui.NeonSheet
import com.voicealarm.app.ui.NeonSlider
import com.voicealarm.app.ui.SegmentedControl
import com.voicealarm.app.ui.SphereDrawable
import com.voicealarm.app.ui.SunkenDrawable
import com.voicealarm.app.ui.Toggle3D
import com.voicealarm.app.ui.chevron
import com.voicealarm.app.ui.depthBackground
import com.voicealarm.app.ui.dp
import com.voicealarm.app.ui.dpi
import com.voicealarm.app.ui.glassCard
import com.voicealarm.app.ui.hairline
import com.voicealarm.app.ui.hbox
import com.voicealarm.app.ui.icon
import com.voicealarm.app.ui.label
import com.voicealarm.app.ui.listRow
import com.voicealarm.app.ui.neonText
import com.voicealarm.app.ui.overline
import com.voicealarm.app.ui.pages.sunkenInput
import com.voicealarm.app.ui.vbox
import java.io.File
import java.util.Calendar

/** Alarm editor: time, label, repeat, voice (TTS or own recording), options, save. */
class AlarmEditActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var alarmId = -1
    private var existing: Alarm? = null
    private lateinit var initial: Alarm

    // state
    private var hour = 7
    private var minute = 0
    private var days = 0
    private var voiceName: String? = null
    private var speechRate = 0.92f
    private var useRecording = false
    private var greeting = true
    private var sayTime = false
    private var vibrate = true
    private var snooze = 10

    // views
    private lateinit var timeText: TextView
    private lateinit var ampmText: TextView
    private lateinit var ringsIn: TextView
    private lateinit var labelEdit: android.widget.EditText
    private val presetChips = mutableListOf<NeonChip>()
    private val dayViews = mutableListOf<TextView>()
    private lateinit var ttsPanel: View
    private lateinit var recPanel: View
    private lateinit var voiceValue: TextView
    private lateinit var speedValue: TextView
    private lateinit var sayText: TextView
    private lateinit var recButton: FrameLayout
    private lateinit var recStatus: TextView
    private lateinit var recPlay: NeonButton
    private lateinit var previewButton: NeonButton
    private lateinit var saveButton: NeonButton
    private var recPulse: ValueAnimator? = null

    private var engine: VoiceEngine? = null
    private var voices: List<Voice> = emptyList()
    private var player: VoicePlayer? = null
    private var recorder: MediaRecorder? = null
    private var recStartedAt = 0L
    private val draftRecording by lazy { File(cacheDir, "draft_recording.m4a") }
    private var hasDraftRecording = false
    private var saving = false

    private val recTick = object : Runnable {
        override fun run() {
            val s = ((System.currentTimeMillis() - recStartedAt) / 1000).toInt()
            recStatus.text = "${getString(R.string.edit_recording_now)}  ${s / 60}:${"%02d".format(s % 60)}"
            if (s >= MAX_RECORDING_S) stopRecording() else main.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alarmId = intent.getIntExtra(EXTRA_ID, -1)
        existing = if (alarmId >= 0) AlarmStore.get(this, alarmId) else null
        if (existing == null) alarmId = -1
        val now = Calendar.getInstance()
        initial = existing ?: AppSettings.newAlarm(this, -1, (now.get(Calendar.HOUR_OF_DAY) + 1) % 24, 0)
        initial.let {
            hour = it.hour; minute = it.minute; days = it.days; voiceName = it.voiceName; speechRate = it.speechRate
            useRecording = it.useRecording; greeting = it.greeting; sayTime = it.sayTime; vibrate = it.vibrate; snooze = it.snoozeMinutes
        }
        draftRecording.delete()
        setContentView(buildUi())
        labelEdit.setText(initial.label)
        refreshAll()
        engine = VoiceEngine(this) { ok ->
            voices = if (ok) engine?.naturalVoices().orEmpty() else emptyList()
            refreshVoiceRow()
        }
    }

    // ---- UI ---------------------------------------------------------------------------

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { background = GlowBackgroundDrawable() }
        val column = vbox()
        val appBar = AppBar(this)
        appBar.setNavigation(R.drawable.ic_arrow_back, getString(R.string.edit_back)) { attemptClose() }
        appBar.title = getString(if (existing == null) R.string.edit_new else R.string.edit_edit)
        if (existing != null) appBar.addAction(R.drawable.ic_delete, getString(R.string.edit_delete)) { confirmDelete() }
        column.addView(appBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(64)).apply { topMargin = dpi(4) })

        val body = vbox().apply {
            setPadding(dpi(20), dpi(4), dpi(20), dpi(130))
            // Keep the label field from grabbing focus (and the keyboard) when the editor opens.
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

        fun section(title: Int, content: View, top: Int = 22) {
            body.addView(overline(getString(title)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(top) })
            body.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(4) })
        }

        // Hero time.
        val hero = glassCard(padH = 20, padV = 18, pressable = true, radiusDp = 26)
        hero.gravity = Gravity.CENTER_HORIZONTAL
        hero.contentDescription = getString(R.string.edit_change_time)
        hero.setOnClickListener { pickTime() }
        timeText = label("", R.style.Text_Display, sizeSp = 72f)
        ampmText = label("", R.style.Text_Title, Neon.TEXT2, 22f).apply { setPadding(dpi(8), 0, 0, dpi(12)) }
        hero.addView(hbox(Gravity.BOTTOM).apply { addView(timeText); addView(ampmText) })
        ringsIn = label("", R.style.Text_Caption, Neon.TEXT2, 13f, Gravity.CENTER)
        hero.addView(ringsIn)
        val adjust = hbox(Gravity.CENTER)
        listOf(R.string.edit_minus5 to -5, R.string.edit_plus5 to 5, R.string.edit_plus15 to 15, R.string.edit_plus60 to 60).forEach { (res, delta) ->
            adjust.addView(NeonChip(this, getString(res), checkable = false).apply { onToggle = { shift(delta) } },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        hero.addView(adjust, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10) })
        body.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(6) })

        // Label.
        val labelCard = glassCard()
        val (box, edit) = sunkenInput(getString(R.string.edit_label_hint), R.drawable.ic_label)
        labelEdit = edit
        edit.filters = arrayOf(android.text.InputFilter.LengthFilter(120))
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshSay()
        })
        labelCard.addView(box)
        val sugg = FlowLayout(this).apply { setPadding(0, dpi(8), 0, 0) }
        listOf(R.string.edit_sugg_interview, R.string.edit_sugg_wake, R.string.edit_sugg_gym, R.string.edit_sugg_medicine, R.string.edit_sugg_meeting, R.string.edit_sugg_flight).forEach { res ->
            sugg.addView(NeonChip(this, getString(res), checkable = false).apply {
                onToggle = { labelEdit.setText(getString(res)); labelEdit.setSelection(labelEdit.text.length) }
            })
        }
        labelCard.addView(sugg)
        section(R.string.edit_label, labelCard)

        // Repeat.
        val repeatCard = glassCard()
        val presets = FlowLayout(this)
        listOf(R.string.edit_once to 0, R.string.edit_weekdays to 0b0011111, R.string.edit_weekends to 0b1100000, R.string.edit_every_day to 0b1111111).forEach { (res, mask) ->
            val chip = NeonChip(this, getString(res))
            chip.tag = mask
            chip.onToggle = { days = mask; refreshDays() }
            presetChips += chip
            presets.addView(chip)
        }
        repeatCard.addView(presets)
        val dayRow = hbox(Gravity.CENTER).apply { setPadding(0, dpi(12), 0, dpi(4)) }
        val letters = getString(R.string.alarms_days_letters)
        val names = resources.getStringArray(R.array.day_short_names)
        for (i in 0..6) {
            val v = label(letters[i].toString(), R.style.Text_Title, sizeSp = 15f, gravity = Gravity.CENTER).apply {
                isClickable = true
                isFocusable = true
                contentDescription = names[i]
                setOnClickListener {
                    days = days xor (1 shl i)
                    animate().scaleX(1.12f).scaleY(1.12f).setDuration(90).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start()
                    refreshDays()
                }
            }
            dayViews += v
            dayRow.addView(v, LinearLayout.LayoutParams(0, dpi(48), 1f))
        }
        repeatCard.addView(dayRow)
        section(R.string.edit_repeat, repeatCard)

        // Voice.
        val voiceCard = glassCard()
        val seg = SegmentedControl(this).apply {
            setOptions(listOf(getString(R.string.edit_natural), getString(R.string.edit_recording)), listOf(R.drawable.ic_record_voice_over, R.drawable.ic_mic))
            selectedIndex = if (useRecording) 1 else 0
            onSelect = { useRecording = it == 1; stopPreview(); refreshVoicePanels() }
        }
        voiceCard.addView(seg)
        ttsPanel = vbox().apply {
            voiceValue = label("", R.style.Text_Caption, Neon.CYAN, 13f)
            addView(listRow(R.drawable.ic_voice_selection, Neon.CYAN, getString(R.string.edit_voice_row), null,
                hbox().apply { addView(voiceValue, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(4) }); addView(chevron()) }) { chooseVoice() })
            addView(hairline())
            speedValue = label("", R.style.Text_Caption, Neon.CYAN, 13f)
            addView(hbox().apply {
                setPadding(0, dpi(12), 0, 0)
                addView(label(getString(R.string.edit_speed), R.style.Text_Title, sizeSp = 16f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(speedValue)
            })
            addView(NeonSlider(this@AlarmEditActivity).apply {
                min = 0.6f; max = 1.2f; step = 0.02f; value = speechRate
                onChange = { v, _ -> speechRate = v; refreshSpeed() }
            })
            addView(hairline())
            addView(toggleRow(R.drawable.ic_waving_hand, Neon.AMBER, R.string.edit_greeting, R.string.edit_greeting_sub, greeting) { greeting = it; refreshSay() })
            addView(hairline())
            addView(toggleRow(R.drawable.ic_schedule, Neon.VIOLET, R.string.edit_say_time, R.string.edit_say_time_sub, sayTime) { sayTime = it; refreshSay() })
            val bubble = glassCard(padH = 16, padV = 12, radiusDp = 18)
            bubble.addView(label(getString(R.string.edit_it_will_say), R.style.Text_Overline))
            sayText = label("", R.style.Text_Body, Neon.TEXT1, 16f).apply { setTypeface(typeface, Typeface.ITALIC) }
            bubble.addView(sayText)
            addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10) })
        }
        voiceCard.addView(ttsPanel)
        recPanel = vbox(Gravity.CENTER_HORIZONTAL).apply {
            setPadding(0, dpi(16), 0, dpi(4))
            recButton = FrameLayout(this@AlarmEditActivity).apply {
                isClickable = true
                isFocusable = true
                contentDescription = getString(R.string.edit_record_cd)
                setOnClickListener { toggleRecording() }
            }
            addView(recButton, LinearLayout.LayoutParams(dpi(150), dpi(150)))
            recStatus = label("", R.style.Text_Title, sizeSp = 16f, gravity = Gravity.CENTER)
            addView(recStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(4) })
            addView(label(getString(R.string.edit_record_hint), R.style.Text_Body, sizeSp = 13f, gravity = Gravity.CENTER),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(2) })
            recPlay = NeonButton(this@AlarmEditActivity, getString(R.string.edit_record_play), R.drawable.ic_play_arrow_fill, NeonButton.Kind.GHOST)
            recPlay.setOnClickListener { previewOrStop() }
            addView(recPlay)
        }
        voiceCard.addView(recPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        previewButton = NeonButton(this, getString(R.string.edit_preview), R.drawable.ic_play_arrow_fill, NeonButton.Kind.SECONDARY)
        previewButton.setOnClickListener { previewOrStop() }
        voiceCard.addView(previewButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(12) })
        section(R.string.edit_voice, voiceCard)

        // Options.
        val options = glassCard(padV = 6)
        options.addView(toggleRow(R.drawable.ic_vibration, Neon.VIOLET, R.string.edit_vibrate, null, vibrate) { vibrate = it })
        options.addView(hairline())
        options.addView(label(getString(R.string.edit_snooze), R.style.Text_Title, sizeSp = 16f).apply { setPadding(0, dpi(12), 0, dpi(6)) })
        val snoozeFlow = FlowLayout(this).apply { setPadding(0, 0, 0, dpi(10)) }
        val snoozeChips = mutableListOf<NeonChip>()
        listOf(5, 10, 15, 20, 30).forEach { m ->
            val chip = NeonChip(this, getString(R.string.settings_minutes, m))
            chip.setOn(m == snooze)
            chip.onToggle = { snooze = m; snoozeChips.forEach { it.setOn(it === chip) } }
            snoozeChips += chip
            snoozeFlow.addView(chip)
        }
        options.addView(snoozeFlow)
        section(R.string.edit_options, options)

        // Sticky footer.
        val footer = FrameLayout(this).apply {
            setPadding(dpi(12), dpi(4), dpi(12), dpi(8))
            depthBackground(GlassCardDrawable(context, radius = dp(28), elevation = dp(14), topColor = 0xF21D2354.toInt(), bottomColor = 0xF212163A.toInt()))
        }
        saveButton = NeonButton(this, getString(R.string.edit_save), R.drawable.ic_check, NeonButton.Kind.PRIMARY)
        saveButton.setOnClickListener { save() }
        footer.addView(saveButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(footer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = dpi(10); rightMargin = dpi(10); bottomMargin = dpi(6)
        })
        return root
    }

    private fun toggleRow(icon: Int, accent: Int, title: Int, sub: Int?, value: Boolean, onChange: (Boolean) -> Unit): View {
        val toggle = Toggle3D(this).apply {
            setChecked(value, animate = false)
            contentDescription = getString(title)
            onCheckedChange = onChange
        }
        return listRow(icon, accent, getString(title), sub?.let { getString(it) }, toggle) { toggle.performClick() }
    }

    // ---- refresh -----------------------------------------------------------------------

    private fun refreshAll() {
        refreshTime()
        refreshDays()
        refreshVoicePanels()
        refreshVoiceRow()
        refreshSpeed()
        refreshSay()
    }

    private fun refreshTime() {
        val full = Alarm.formatTime(this, hour, minute)
        val idx = full.indexOfFirst { it.isLetter() }
        timeText.text = if (idx > 0) full.substring(0, idx).trim() else full
        ampmText.text = if (idx > 0) full.substring(idx).trim() else ""
        timeText.neonText()
        ringsIn.text = getString(R.string.edit_rings_in, Format.durationText(this, AlarmScheduler.nextTrigger(current(-1)) - System.currentTimeMillis()))
        refreshSay()
    }

    private fun refreshDays() {
        presetChips.forEach { it.setOn(it.tag == days) }
        dayViews.forEachIndexed { i, v ->
            val on = days and (1 shl i) != 0
            v.setTag(R.id.tag_depth_insets, null)
            v.setPadding(0, 0, 0, 0)
            if (on) {
                v.depthBackground(SphereDrawable(this, Neon.CYAN, glow = true, shadow = dp(5)))
                v.setTextColor(Neon.ON_NEON)
            } else {
                v.depthBackground(SunkenDrawable(this, radius = -1f).also { it.alpha = 255 })
                v.setTextColor(Neon.TEXT2)
            }
            v.isSelected = on
        }
        if (::ringsIn.isInitialized) ringsIn.text = getString(R.string.edit_rings_in, Format.durationText(this, AlarmScheduler.nextTrigger(current(-1)) - System.currentTimeMillis()))
    }

    private fun refreshVoicePanels() {
        ttsPanel.visibility = if (useRecording) View.GONE else View.VISIBLE
        recPanel.visibility = if (useRecording) View.VISIBLE else View.GONE
        refreshRecordUi()
    }

    private fun refreshVoiceRow() {
        val v = voices.firstOrNull { it.name == voiceName }
        voiceValue.text = if (v == null) getString(R.string.edit_voice_auto) else VoiceEngine.describe(v).split(" · ").let { p -> listOfNotNull(p.getOrNull(1), p.getOrNull(2)).joinToString(" · ") }
    }

    private fun refreshSpeed() {
        val word = getString(when {
            speechRate < 0.85f -> R.string.speed_slow
            speechRate <= 1.02f -> R.string.speed_natural
            else -> R.string.speed_fast
        })
        speedValue.text = getString(R.string.voices_speed_value, word, speechRate)
    }

    private fun refreshSay() {
        if (!::sayText.isInitialized || !::labelEdit.isInitialized) return
        sayText.text = "“" + current(-1).spokenText(AppSettings.userName(this)) + "”"
    }

    private fun refreshRecordUi() {
        val recording = recorder != null
        val has = currentRecording() != null
        recButton.removeAllViews()
        recButton.setTag(R.id.tag_depth_insets, null)
        recButton.setPadding(0, 0, 0, 0)
        recButton.background = null
        recButton.addView(RecordRings(this, recording), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val ball = FrameLayout(this).apply {
            depthBackground(SphereDrawable(context, if (recording) Neon.RED else Neon.CYAN, glow = recording, shadow = dp(10)))
            addView(icon(if (recording) R.drawable.ic_stop_fill else R.drawable.ic_mic_fill, if (recording) android.graphics.Color.WHITE else Neon.ON_NEON, 40), FrameLayout.LayoutParams(dpi(40), dpi(40), Gravity.CENTER))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        recButton.addView(ball, FrameLayout.LayoutParams(dpi(96) + ball.paddingLeft + ball.paddingRight, dpi(96) + ball.paddingTop + ball.paddingBottom, Gravity.CENTER))
        recButton.contentDescription = getString(if (recording) R.string.edit_record_stop_cd else R.string.edit_record_cd)
        if (!recording) recStatus.text = getString(if (has) R.string.edit_record_ready else R.string.edit_record)
        recPlay.visibility = if (has && !recording) View.VISIBLE else View.GONE
    }

    // ---- actions --------------------------------------------------------------------------

    private fun current(id: Int) = Alarm(
        id = id, hour = hour, minute = minute, label = labelEdit.text.toString().trim(), days = days, enabled = true,
        voiceName = voiceName, speechRate = speechRate, useRecording = useRecording, greeting = greeting,
        sayTime = sayTime, vibrate = vibrate, snoozeMinutes = snooze,
    )

    private fun pickTime() {
        TimePickerDialog(this, R.style.NeonDialog, { _, h, m ->
            hour = h; minute = m; refreshTime()
        }, hour, minute, DateFormat.is24HourFormat(this)).show()
    }

    private fun shift(delta: Int) {
        val total = ((hour * 60 + minute + delta) % 1440 + 1440) % 1440
        hour = total / 60; minute = total % 60
        refreshTime()
    }

    private fun chooseVoice() {
        val sheet = NeonSheet(this)
        sheet.title = getString(R.string.edit_choose_voice)
        val list = vbox()
        fun row(name: String?, title: String, detail: String?) {
            val selected = name == voiceName
            val play = IconButton3D(this, R.drawable.ic_play_arrow_fill, getString(R.string.voices_preview_cd, title), sizeDp = 40)
            play.setOnClickListener { previewWith(name) }
            val r = listRow(if (selected) R.drawable.ic_check_circle_fill else R.drawable.ic_radio_button_checked,
                if (selected) Neon.CYAN else Neon.TEXT3, title, detail, play) {
                voiceName = name
                refreshVoiceRow()
                sheet.dismiss()
            }
            list.addView(r)
        }
        row(null, getString(R.string.voices_auto), getString(R.string.voices_auto_detail))
        voices.forEach { v ->
            val p = VoiceEngine.describe(v).split(" · ")
            row(v.name, p.getOrNull(1) ?: v.name, (listOfNotNull(p.getOrNull(0)) + p.drop(2)).joinToString(" · "))
        }
        sheet.setContent(list)
        sheet.onDismiss = { stopPreview() }
        sheet.show()
    }

    private fun previewOrStop() {
        if (player != null) {
            stopPreview()
            return
        }
        previewWith(voiceName)
    }

    private fun previewWith(name: String?) {
        stopRecording()
        stopPreview()
        val alarm = current(alarmId).copy(voiceName = name)
        if (alarm.useRecording) {
            val f = currentRecording() ?: return toast(R.string.record_first)
            play(alarm, f)
            return
        }
        val e = engine
        if (e == null || !e.isReady) return toast(R.string.voice_not_ready)
        previewButton.setLoading(true, getString(R.string.edit_preparing))
        val out = File(cacheDir, "preview.wav")
        e.render(alarm.spokenText(AppSettings.userName(this)), name, alarm.speechRate, out) { ok ->
            previewButton.setLoading(false)
            if (ok) play(alarm, out) else toast(R.string.voice_failed)
        }
    }

    private fun play(alarm: Alarm, file: File) {
        previewButton.setText(getString(R.string.edit_stop))
        player = VoicePlayer(this, AudioAttributes.USAGE_MEDIA).also {
            it.play(alarm, file, loop = false, crescendo = false) {
                player = null
                previewButton.setText(getString(R.string.edit_preview))
            }
        }
    }

    private fun stopPreview() {
        player?.stop()
        player = null
        if (::previewButton.isInitialized) previewButton.setText(getString(R.string.edit_preview))
    }

    private fun currentRecording(): File? {
        if (hasDraftRecording && draftRecording.exists()) return draftRecording
        if (alarmId >= 0) AlarmStore.recordingFile(this, alarmId).takeIf { it.exists() }?.let { return it }
        return null
    }

    private fun toggleRecording() {
        if (recorder != null) {
            stopRecording()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        stopPreview()
        draftRecording.delete()
        hasDraftRecording = false
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(128_000)
            r.setOutputFile(draftRecording.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recStartedAt = System.currentTimeMillis()
            refreshRecordUi()
            main.post(recTick)
        } catch (e: Exception) {
            r.release()
            toast(R.string.recording_failed)
        }
    }

    private fun stopRecording() {
        main.removeCallbacks(recTick)
        val r = recorder ?: return
        recorder = null
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            false
        }
        r.release()
        hasDraftRecording = ok && draftRecording.length() > 0
        if (!hasDraftRecording) draftRecording.delete()
        if (!isDestroyed) refreshRecordUi()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleRecording() else toast(R.string.mic_denied)
        }
    }

    private fun save() {
        if (saving) return
        stopRecording()
        stopPreview()
        val id = if (alarmId >= 0) alarmId else AlarmStore.newId(this).also { alarmId = it }
        val alarm = current(id)
        if (alarm.useRecording) {
            if (hasDraftRecording) {
                draftRecording.copyTo(AlarmStore.recordingFile(this, id), overwrite = true)
                draftRecording.delete()
                hasDraftRecording = false
            }
            if (!AlarmStore.recordingFile(this, id).exists()) return toast(R.string.record_first)
        }
        saving = true
        saveButton.setLoading(true, getString(R.string.edit_preparing))
        AlarmSaver.save(this, alarm, engine) { voiceReady ->
            var msg = Format.timeUntilText(this, AlarmScheduler.nextTrigger(alarm))
            if (!voiceReady && !alarm.useRecording) msg += "\n" + getString(R.string.voice_fallback_warning)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun confirmDelete() {
        val a = existing ?: return
        val sheet = NeonSheet(this)
        sheet.title = getString(R.string.edit_delete_confirm)
        sheet.subtitle = getString(R.string.edit_delete_body)
        sheet.addAction(getString(R.string.alarms_delete), NeonButton.Kind.DANGER, R.drawable.ic_delete) {
            sheet.dismiss()
            AlarmScheduler.cancel(this, a.id)
            AlarmStore.delete(this, a.id)
            finish()
        }
        sheet.addAction(getString(R.string.edit_keep), NeonButton.Kind.GHOST) { sheet.dismiss() }
        sheet.show()
    }

    private fun hasChanges(): Boolean {
        val now = current(initial.id)
        return now.copy(enabled = initial.enabled) != initial || hasDraftRecording
    }

    private fun attemptClose() {
        if (!hasChanges() || saving) {
            finish()
            return
        }
        val sheet = NeonSheet(this)
        sheet.title = getString(R.string.edit_discard_title)
        sheet.addAction(getString(R.string.edit_discard), NeonButton.Kind.DANGER) { sheet.dismiss(); finish() }
        sheet.addAction(getString(R.string.edit_keep), NeonButton.Kind.SECONDARY) { sheet.dismiss() }
        sheet.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = attemptClose()

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    override fun onPause() {
        super.onPause()
        stopPreview()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopRecording()
        stopPreview()
        engine?.shutdown()
        engine = null
        super.onDestroy()
    }

    /** Pulsing neon rings around the record button while recording. */
    private class RecordRings(context: android.content.Context, private val active: Boolean) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var phase = 0f
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
        }

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r0 = dp(52); val r1 = minOf(width, height) / 2f
            for (i in 0 until 2) {
                val p = if (active) (phase + i / 2f) % 1f else 0.25f + i * 0.35f
                paint.color = if (active) Neon.RED else Neon.TEXT1
                paint.alpha = ((1 - p) * (if (active) 200 else 70)).toInt()
                paint.strokeWidth = dp(3) * (1 - p) + dp(1)
                canvas.drawCircle(cx, cy, r0 + (r1 - r0) * p, paint)
            }
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); if (active) anim.start() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    }

    companion object {
        const val EXTRA_ID = "alarm_id"
        private const val REQ_MIC = 2
        private const val MAX_RECORDING_S = 20
    }
}
