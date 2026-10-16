package com.voicealarm.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
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
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import android.widget.ToggleButton
import java.io.File

class AlarmEditActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var alarmId = -1
    private var existing: Alarm? = null

    private lateinit var timePicker: TimePicker
    private lateinit var labelInput: EditText
    private lateinit var sourceTts: RadioButton
    private lateinit var sourceRecording: RadioButton
    private lateinit var ttsPanel: View
    private lateinit var recordPanel: View
    private lateinit var voiceSpinner: Spinner
    private lateinit var voiceStatus: TextView
    private lateinit var speedSeek: SeekBar
    private lateinit var speedValue: TextView
    private lateinit var greetingCheck: CheckBox
    private lateinit var sayTimeCheck: CheckBox
    private lateinit var spokenText: TextView
    private lateinit var recordButton: Button
    private lateinit var recordStatus: TextView
    private lateinit var vibrateCheck: CheckBox
    private lateinit var snoozeSpinner: Spinner
    private val dayToggles = mutableListOf<ToggleButton>()

    private var engine: VoiceEngine? = null
    private var voices: List<Voice> = emptyList()
    private var player: VoicePlayer? = null
    private var recorder: MediaRecorder? = null
    private val draftRecording by lazy { File(cacheDir, "draft_recording.m4a") }
    private var hasDraftRecording = false
    private var busy: AlertDialog? = null

    private val autoStopRecording = Runnable { stopRecording() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        actionBar?.setDisplayHomeAsUpEnabled(true)

        alarmId = intent.getIntExtra(EXTRA_ID, -1)
        existing = if (alarmId >= 0) AlarmStore.get(this, alarmId) else null
        if (existing == null) alarmId = -1
        setTitle(if (existing == null) R.string.new_alarm else R.string.edit_alarm)

        bindViews()
        fill(existing ?: Alarm(id = -1, hour = 7, minute = 0, label = ""))
        draftRecording.delete()

        engine = VoiceEngine(this) { ok -> onVoicesReady(ok) }
    }

    private fun bindViews() {
        timePicker = findViewById(R.id.timePicker)
        labelInput = findViewById(R.id.labelInput)
        sourceTts = findViewById(R.id.sourceTts)
        sourceRecording = findViewById(R.id.sourceRecording)
        ttsPanel = findViewById(R.id.ttsPanel)
        recordPanel = findViewById(R.id.recordPanel)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        voiceStatus = findViewById(R.id.voiceStatus)
        speedSeek = findViewById(R.id.speedSeek)
        speedValue = findViewById(R.id.speedValue)
        greetingCheck = findViewById(R.id.greetingCheck)
        sayTimeCheck = findViewById(R.id.sayTimeCheck)
        spokenText = findViewById(R.id.spokenText)
        recordButton = findViewById(R.id.recordButton)
        recordStatus = findViewById(R.id.recordStatus)
        vibrateCheck = findViewById(R.id.vibrateCheck)
        snoozeSpinner = findViewById(R.id.snoozeSpinner)

        timePicker.setIs24HourView(DateFormat.is24HourFormat(this))
        timePicker.setOnTimeChangedListener { _, _, _ -> updateSpokenText() }

        val dayNames = resources.getStringArray(R.array.day_short_names)
        val daysRow = findViewById<LinearLayout>(R.id.daysRow)
        for (i in 0..6) {
            val t = ToggleButton(this).apply {
                textOn = dayNames[i]
                textOff = dayNames[i]
                text = dayNames[i]
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(0, paddingTop, 0, paddingBottom)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            dayToggles += t
            daysRow.addView(t)
        }

        val snoozeAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            SNOOZE_OPTIONS.map { getString(R.string.minutes, it) },
        )
        snoozeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        snoozeSpinner.adapter = snoozeAdapter

        setVoiceItems(listOf(getString(R.string.loading_voices)))

        speedSeek.max = 60 // 0.60x … 1.20x
        speedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = updateSpeedLabel()
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        labelInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateSpokenText()
        })
        greetingCheck.setOnCheckedChangeListener { _, _ -> updateSpokenText() }
        sayTimeCheck.setOnCheckedChangeListener { _, _ -> updateSpokenText() }
        sourceTts.setOnCheckedChangeListener { _, _ -> updateSourcePanels() }
        sourceRecording.setOnCheckedChangeListener { _, _ -> updateSourcePanels() }

        recordButton.setOnClickListener { toggleRecording() }
        findViewById<Button>(R.id.previewButton).setOnClickListener { preview() }
        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
        findViewById<Button>(R.id.deleteButton).apply {
            visibility = if (existing != null) View.VISIBLE else View.GONE
            setOnClickListener { delete() }
        }
    }

    private fun fill(a: Alarm) {
        timePicker.hour = a.hour
        timePicker.minute = a.minute
        labelInput.setText(a.label)
        dayToggles.forEachIndexed { i, t -> t.isChecked = a.days and (1 shl i) != 0 }
        sourceRecording.isChecked = a.useRecording
        sourceTts.isChecked = !a.useRecording
        speedSeek.progress = ((a.speechRate - 0.6f) * 100).toInt().coerceIn(0, 60)
        greetingCheck.isChecked = a.greeting
        sayTimeCheck.isChecked = a.sayTime
        vibrateCheck.isChecked = a.vibrate
        snoozeSpinner.setSelection(SNOOZE_OPTIONS.indexOf(a.snoozeMinutes).coerceAtLeast(0))
        updateSpeedLabel()
        updateSourcePanels()
        updateSpokenText()
        updateRecordStatus()
    }

    private fun onVoicesReady(ok: Boolean) {
        voices = if (ok) engine?.naturalVoices().orEmpty() else emptyList()
        if (voices.isEmpty()) {
            setVoiceItems(listOf(getString(R.string.voice_auto)))
            voiceStatus.visibility = View.VISIBLE
            voiceStatus.setText(R.string.no_tts_voices)
            return
        }
        voiceStatus.visibility = View.GONE
        setVoiceItems(listOf(getString(R.string.voice_auto)) + voices.map { VoiceEngine.describe(it) })
        val wanted = existing?.voiceName
        val index = voices.indexOfFirst { it.name == wanted }
        voiceSpinner.setSelection(if (index >= 0) index + 1 else 0)
    }

    private fun setVoiceItems(items: List<String>) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        voiceSpinner.adapter = adapter
    }

    private fun selectedVoiceName(): String? {
        val pos = voiceSpinner.selectedItemPosition
        return if (pos >= 1 && pos - 1 < voices.size) voices[pos - 1].name else existing?.voiceName?.takeIf { voices.isEmpty() }
    }

    private fun speechRate() = 0.6f + speedSeek.progress / 100f

    private fun updateSpeedLabel() {
        val rate = speechRate()
        val word = getString(
            when {
                rate < 0.85f -> R.string.speed_slow
                rate <= 1.02f -> R.string.speed_natural
                else -> R.string.speed_fast
            }
        )
        speedValue.text = getString(R.string.speed_value, word, rate)
    }

    private fun updateSourcePanels() {
        val rec = sourceRecording.isChecked
        ttsPanel.visibility = if (rec) View.GONE else View.VISIBLE
        recordPanel.visibility = if (rec) View.VISIBLE else View.GONE
    }

    private fun updateSpokenText() {
        spokenText.text = getString(R.string.it_will_say, buildAlarm(alarmId).spokenText())
    }

    private fun buildAlarm(id: Int): Alarm {
        var days = 0
        dayToggles.forEachIndexed { i, t -> if (t.isChecked) days = days or (1 shl i) }
        return Alarm(
            id = id,
            hour = timePicker.hour,
            minute = timePicker.minute,
            label = labelInput.text.toString().trim(),
            days = days,
            enabled = true,
            voiceName = selectedVoiceName(),
            speechRate = speechRate(),
            useRecording = sourceRecording.isChecked,
            greeting = greetingCheck.isChecked,
            sayTime = sayTimeCheck.isChecked,
            vibrate = vibrateCheck.isChecked,
            snoozeMinutes = SNOOZE_OPTIONS.getOrElse(snoozeSpinner.selectedItemPosition) { 10 },
        )
    }

    // ---- preview -----------------------------------------------------------

    private fun preview() {
        stopRecording()
        stopPreview()
        val alarm = buildAlarm(alarmId)
        if (alarm.useRecording) {
            val file = currentRecording()
            if (file == null) {
                toast(R.string.record_first)
                return
            }
            playPreview(alarm, file)
            return
        }
        val e = engine
        if (e == null || !e.isReady) {
            toast(R.string.voice_not_ready)
            return
        }
        val out = File(cacheDir, "preview.wav")
        showBusy()
        e.render(alarm.spokenText(), alarm.voiceName, alarm.speechRate, out) { ok ->
            hideBusy()
            if (ok) playPreview(alarm, out) else toast(R.string.voice_failed)
        }
    }

    private fun playPreview(alarm: Alarm, file: File) {
        player = VoicePlayer(this, AudioAttributes.USAGE_MEDIA).also {
            it.play(alarm, file, loop = false, crescendo = false)
        }
    }

    private fun stopPreview() {
        player?.stop()
        player = null
    }

    // ---- recording your own voice ------------------------------------------

    private fun currentRecording(): File? {
        if (hasDraftRecording && draftRecording.exists()) return draftRecording
        if (alarmId >= 0) {
            val saved = AlarmStore.recordingFile(this, alarmId)
            if (saved.exists()) return saved
        }
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
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
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
            recordButton.setText(R.string.stop_recording)
            recordStatus.setText(R.string.recording_now)
            main.postDelayed(autoStopRecording, MAX_RECORDING_MS)
        } catch (e: Exception) {
            r.release()
            toast(R.string.recording_failed)
        }
    }

    private fun stopRecording() {
        main.removeCallbacks(autoStopRecording)
        val r = recorder ?: return
        recorder = null
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            false // stop() throws if nothing was recorded
        }
        r.release()
        hasDraftRecording = ok && draftRecording.length() > 0
        if (!hasDraftRecording) draftRecording.delete()
        recordButton.setText(R.string.record_voice)
        updateRecordStatus()
    }

    private fun updateRecordStatus() {
        recordStatus.setText(if (currentRecording() != null) R.string.recording_ready else R.string.recording_hint)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleRecording()
            else toast(R.string.mic_denied)
        }
    }

    // ---- save / delete -------------------------------------------------------

    private fun save() {
        stopRecording()
        stopPreview()
        val id = if (alarmId >= 0) alarmId else AlarmStore.newId(this).also { alarmId = it }
        val alarm = buildAlarm(id)

        if (alarm.useRecording) {
            if (hasDraftRecording) {
                draftRecording.copyTo(AlarmStore.recordingFile(this, id), overwrite = true)
                draftRecording.delete()
                hasDraftRecording = false
            }
            if (!AlarmStore.recordingFile(this, id).exists()) {
                toast(R.string.record_first)
                return
            }
            commit(alarm, voiceWarning = false)
            return
        }

        val speechFile = AlarmStore.speechFile(this, id)
        val e = engine
        if (e == null || !e.isReady) {
            speechFile.delete() // never keep audio for an old label
            commit(alarm, voiceWarning = true)
            return
        }
        showBusy()
        e.render(alarm.spokenText(), alarm.voiceName, alarm.speechRate, speechFile) { ok ->
            hideBusy()
            if (!ok) speechFile.delete() // the alarm will speak live instead
            commit(alarm, voiceWarning = !ok)
        }
    }

    private fun commit(alarm: Alarm, voiceWarning: Boolean) {
        AlarmStore.save(this, alarm)
        AlarmScheduler.schedule(this, alarm)
        var msg = timeUntilText(this, AlarmScheduler.nextTrigger(alarm))
        if (voiceWarning) msg += "\n" + getString(R.string.voice_fallback_warning)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun delete() {
        val a = existing ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_alarm)
            .setPositiveButton(R.string.delete) { _, _ ->
                AlarmScheduler.cancel(this, a.id)
                AlarmStore.delete(this, a.id)
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- misc ---------------------------------------------------------------

    private fun showBusy() {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(ProgressBar(this@AlarmEditActivity))
            addView(TextView(this@AlarmEditActivity).apply {
                setText(R.string.preparing_voice)
                setPadding(pad, 0, 0, 0)
                textSize = 16f
            })
        }
        busy = AlertDialog.Builder(this).setView(content).setCancelable(false).show()
    }

    private fun hideBusy() {
        busy?.dismiss()
        busy = null
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onPause() {
        super.onPause()
        stopPreview()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopRecording()
        stopPreview()
        hideBusy()
        engine?.shutdown()
        engine = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "alarm_id"
        private const val REQ_MIC = 2
        private const val MAX_RECORDING_MS = 20_000L
        private val SNOOZE_OPTIONS = listOf(5, 10, 15, 20, 30)

        fun timeUntilText(context: Context, triggerAt: Long): String {
            val totalMin = ((triggerAt - System.currentTimeMillis() + 59_999) / 60_000).coerceAtLeast(1)
            val days = totalMin / (60 * 24)
            val hours = (totalMin / 60) % 24
            val minutes = totalMin % 60
            val parts = mutableListOf<String>()
            if (days > 0) parts += context.resources.getQuantityString(R.plurals.days, days.toInt(), days.toInt())
            if (hours > 0) parts += context.resources.getQuantityString(R.plurals.hours, hours.toInt(), hours.toInt())
            if (minutes > 0 || parts.isEmpty()) {
                parts += context.resources.getQuantityString(R.plurals.minutes, minutes.toInt(), minutes.toInt())
            }
            return context.getString(R.string.alarm_set_in, parts.joinToString(" "))
        }
    }
}
