package com.voicealarm.app.ui.pages

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.voicealarm.app.Alarm
import com.voicealarm.app.AlarmSaver
import com.voicealarm.app.AlarmScheduler
import com.voicealarm.app.AlarmStore
import com.voicealarm.app.Format
import com.voicealarm.app.R
import com.voicealarm.app.Reliability
import com.voicealarm.app.VoiceEngine
import com.voicealarm.app.ui.GlassCardDrawable
import com.voicealarm.app.ui.Neon
import com.voicealarm.app.ui.NeonButton
import com.voicealarm.app.ui.NeonSheet
import com.voicealarm.app.ui.Page
import com.voicealarm.app.ui.PageHost
import com.voicealarm.app.ui.Toggle3D
import com.voicealarm.app.ui.chevron
import com.voicealarm.app.ui.depthBackground
import com.voicealarm.app.ui.dp
import com.voicealarm.app.ui.dpi
import com.voicealarm.app.ui.glassCard
import com.voicealarm.app.ui.hbox
import com.voicealarm.app.ui.icon
import com.voicealarm.app.ui.iconTile
import com.voicealarm.app.ui.label
import com.voicealarm.app.ui.neonText
import com.voicealarm.app.ui.overline
import com.voicealarm.app.ui.vbox
import java.io.File

/** Home tab: next-alarm hero, reliability warning and the list of alarms. */
class AlarmsPage(private val activity: Activity, private val host: PageHost) : Page {
    private val kit = PageScroll(activity)
    override val view: View = kit.scroll
    override val title: CharSequence = activity.getString(R.string.alarms_title)

    private val main = Handler(Looper.getMainLooper())
    private var countdown: TextView? = null
    private var nextAt = 0L
    private var engine: VoiceEngine? = null
    private var firstShow = true

    private val tick = object : Runnable {
        override fun run() {
            countdown?.text = activity.getString(R.string.alarms_in, Format.shortDuration(nextAt - System.currentTimeMillis()))
            main.postDelayed(this, 30_000)
        }
    }

    override fun onShow() {
        rebuild()
        if (firstShow) {
            kit.animateIn()
            firstShow = false
        }
    }

    override fun onHide() {
        main.removeCallbacks(tick)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        engine?.shutdown()
        engine = null
    }

    private fun rebuild() {
        main.removeCallbacks(tick)
        countdown = null
        val col = kit.column
        col.removeAllViews()
        val alarms = AlarmStore.all(activity)

        if (Reliability.problems(activity).isNotEmpty()) kit.add(warningRow(), top = 4)

        val next = alarms.filter { it.enabled }.minByOrNull { AlarmScheduler.nextTrigger(it) }
        when {
            alarms.isEmpty() -> kit.add(emptyHero(), top = 8)
            next == null -> kit.add(noneOnHero(), top = 8)
            else -> kit.add(nextHero(next), top = 8)
        }

        if (alarms.isNotEmpty()) {
            val header = activity.hbox().apply {
                addView(activity.overline(activity.getString(R.string.alarms_all)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(activity.label(activity.getString(R.string.alarms_count, alarms.count { it.enabled }), R.style.Text_Caption, Neon.CYAN))
            }
            kit.add(header, top = 26)
            alarms.forEach { kit.add(alarmCard(it), top = 6) }
        }
        if (next != null) main.postDelayed(tick, 30_000)
    }

    // ---- hero cards ---------------------------------------------------------------

    private fun nextHero(alarm: Alarm): View {
        nextAt = AlarmScheduler.nextTrigger(alarm)
        val card = activity.glassCard(padH = 22, padV = 20, glow = Neon.withAlpha(Neon.BLUE, 0.9f),
            outline = intArrayOf(Neon.withAlpha(Neon.CYAN, 0.7f), Neon.withAlpha(Neon.VIOLET, 0.5f)), pressable = true, radiusDp = 26)
        card.setOnClickListener { host.openEditor(alarm.id) }
        card.addView(activity.overline(activity.getString(R.string.alarms_next)))
        val (time, ampm) = splitTime(alarm)
        val timeRow = activity.hbox(Gravity.BOTTOM).apply {
            addView(activity.label(time, R.style.Text_Display, sizeSp = 64f).apply { neonText(Neon.AQUA) })
            if (ampm.isNotEmpty()) addView(activity.label(ampm, R.style.Text_Title, Neon.TEXT2, 20f).apply { setPadding(dpi(8), 0, 0, dpi(10)) })
        }
        card.addView(timeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpi(2) })
        val labelRow = activity.hbox().apply {
            addView(activity.icon(R.drawable.ic_label, Neon.MAGENTA, 20), LinearLayout.LayoutParams(dpi(20), dpi(20)).apply { marginEnd = dpi(8) })
            addView(activity.label(alarm.label.ifBlank { activity.getString(R.string.alarms_no_label) }.replaceFirstChar { it.uppercase() }, R.style.Text_Title, sizeSp = 19f).apply { maxLines = 2 })
        }
        card.addView(labelRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpi(4) })
        val chips = activity.hbox().apply {
            val cd = pill(R.drawable.ic_schedule, activity.getString(R.string.alarms_in, Format.shortDuration(nextAt - System.currentTimeMillis())), Neon.CYAN)
            countdown = (cd.getChildAt(1) as TextView)
            addView(cd)
            addView(pill(R.drawable.ic_repeat, alarm.daysText(activity), Neon.VIOLET), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(4) })
        }
        card.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpi(12) })
        card.addView(pill(if (alarm.useRecording) R.drawable.ic_mic else R.drawable.ic_record_voice_over,
            activity.getString(if (alarm.useRecording) R.string.alarms_own_voice else R.string.alarms_speaks), Neon.GREEN),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpi(4) })
        return card
    }

    private fun emptyHero(): View {
        val card = activity.glassCard(padH = 24, padV = 24, glow = Neon.withAlpha(Neon.VIOLET, 0.8f), radiusDp = 26)
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.addView(ImageView(activity).apply { setImageResource(R.drawable.art_clock); contentDescription = null },
            LinearLayout.LayoutParams(activity.dpi(190), activity.dpi(190)))
        card.addView(activity.label(activity.getString(R.string.alarms_empty_title), R.style.Text_Headline, sizeSp = 24f, gravity = Gravity.CENTER),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpi(4) })
        card.addView(activity.label(activity.getString(R.string.alarms_empty_body), gravity = Gravity.CENTER),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpi(6) })
        val cta = NeonButton(activity, activity.getString(R.string.alarms_create), R.drawable.ic_alarm_add)
        cta.setOnClickListener { host.openEditor(-1) }
        card.addView(cta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpi(18) })
        return card
    }

    private fun noneOnHero(): View {
        val card = activity.glassCard(padH = 20, padV = 18, radiusDp = 26)
        val row = activity.hbox()
        row.addView(ImageView(activity).apply { setImageResource(R.drawable.art_moon); contentDescription = null }, LinearLayout.LayoutParams(activity.dpi(96), activity.dpi(96)))
        val t = activity.vbox()
        t.addView(activity.label(activity.getString(R.string.alarms_none_on_title), R.style.Text_Title, sizeSp = 20f))
        t.addView(activity.label(activity.getString(R.string.alarms_none_on_body), sizeSp = 14f))
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = activity.dpi(12) })
        card.addView(row)
        return card
    }

    private fun warningRow(): View {
        val card = activity.glassCard(padH = 14, padV = 10, outline = intArrayOf(Neon.AMBER, Neon.withAlpha(Neon.AMBER, 0.3f)), pressable = true, radiusDp = 18)
        card.setOnClickListener { host.selectTab("settings") }
        val row = activity.hbox()
        row.addView(activity.iconTile(R.drawable.ic_warning_fill, Neon.AMBER, 40), LinearLayout.LayoutParams(activity.dpi(40), activity.dpi(40)).apply { marginEnd = activity.dpi(12) })
        val t = activity.vbox()
        t.addView(activity.label(activity.getString(R.string.alarms_warning_title), R.style.Text_Title, sizeSp = 15f))
        t.addView(activity.label(activity.getString(R.string.alarms_warning_body), R.style.Text_Caption, Neon.AMBER))
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(activity.chevron())
        card.addView(row)
        return card
    }

    // ---- alarm cards ---------------------------------------------------------------

    private fun alarmCard(alarm: Alarm): View {
        val card = activity.glassCard(padH = 18, padV = 12, pressable = true)
        card.setOnClickListener { host.openEditor(alarm.id) }
        card.setOnLongClickListener { actions(alarm); true }
        val row = activity.hbox()
        val left = activity.vbox()
        val (time, ampm) = splitTime(alarm)
        val timeRow = activity.hbox(Gravity.BOTTOM).apply {
            addView(activity.label(time, R.style.Text_Display, if (alarm.enabled) Neon.TEXT1 else Neon.TEXT3, 38f))
            if (ampm.isNotEmpty()) addView(activity.label(ampm, R.style.Text_Caption, Neon.TEXT2, 14f).apply { setPadding(dpi(6), 0, 0, dpi(6)) })
        }
        left.addView(timeRow)
        left.addView(activity.label(alarm.label.ifBlank { activity.getString(R.string.alarms_no_label) }.replaceFirstChar { it.uppercase() },
            R.style.Text_Title, if (alarm.enabled) Neon.TEXT1 else Neon.TEXT3, 16f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        left.addView(daysLine(alarm), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpi(4) })
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val toggle = Toggle3D(activity).apply {
            setChecked(alarm.enabled, animate = false)
            contentDescription = activity.getString(R.string.alarms_toggle_cd, alarm.timeText(activity))
            onCheckedChange = { on ->
                val updated = alarm.copy(enabled = on)
                AlarmStore.save(activity, updated)
                AlarmScheduler.schedule(activity, updated)
                if (on) host.showSnack(Format.timeUntilText(activity, AlarmScheduler.nextTrigger(updated)))
                main.postDelayed({ rebuild() }, 260)
            }
        }
        row.addView(toggle)
        val more = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            contentDescription = activity.getString(R.string.alarms_more)
            background = activity.getDrawable(R.drawable.ctl_row_ripple)
            addView(activity.icon(R.drawable.ic_more_vert, Neon.TEXT2, 22), FrameLayout.LayoutParams(dpi(22), dpi(22), Gravity.CENTER))
            setOnClickListener { actions(alarm) }
        }
        row.addView(more, LinearLayout.LayoutParams(activity.dpi(40), activity.dpi(48)))
        card.addView(row)
        return card
    }

    private fun daysLine(alarm: Alarm): View {
        if (!alarm.isRepeating) {
            return activity.label(alarm.daysText(activity), R.style.Text_Caption, Neon.TEXT2, 13f)
        }
        val letters = activity.getString(R.string.alarms_days_letters)
        val sb = SpannableStringBuilder()
        for (i in 0..6) {
            val start = sb.length
            sb.append(letters[i])
            val on = alarm.days and (1 shl i) != 0
            sb.setSpan(ForegroundColorSpan(if (on && alarm.enabled) Neon.CYAN else if (on) Neon.TEXT2 else Neon.withAlpha(Neon.TEXT3, 0.6f)), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (i < 6) sb.append("  ")
        }
        return activity.label(sb, R.style.Text_Overline).apply {
            letterSpacing = 0.05f
            contentDescription = alarm.daysText(activity)
        }
    }

    private fun pill(iconRes: Int, text: CharSequence, color: Int): LinearLayout = activity.hbox().apply {
        setPadding(dpi(12), dpi(5), dpi(14), dpi(5))
        depthBackground(GlassCardDrawable(context, radius = -1f, elevation = dp(4), topColor = Neon.withAlpha(color, 0.16f), bottomColor = Neon.withAlpha(color, 0.06f)))
        addView(activity.icon(iconRes, color, 16), LinearLayout.LayoutParams(dpi(16), dpi(16)).apply { marginEnd = dpi(6) })
        addView(activity.label(text, R.style.Text_Caption, Neon.TEXT1, 13f))
    }

    private fun splitTime(alarm: Alarm): Pair<String, String> {
        val full = alarm.timeText(activity)
        val idx = full.indexOfFirst { it.isLetter() }
        return if (idx > 0) full.substring(0, idx).trim() to full.substring(idx).trim() else full to ""
    }

    // ---- actions -----------------------------------------------------------------

    private fun actions(alarm: Alarm) {
        NeonSheet.actions(activity, alarm.timeText(activity) + (if (alarm.label.isNotBlank()) " · " + alarm.label else ""), listOf(
            NeonSheet.SheetAction(R.drawable.ic_edit, activity.getString(R.string.alarms_edit)) { host.openEditor(alarm.id) },
            NeonSheet.SheetAction(R.drawable.ic_history, activity.getString(R.string.alarms_duplicate)) { duplicate(alarm) },
            NeonSheet.SheetAction(R.drawable.ic_delete, activity.getString(R.string.alarms_delete), destructive = true) { delete(alarm) },
        ))
    }

    private fun duplicate(alarm: Alarm) {
        val copy = alarm.copy(id = AlarmStore.newId(activity), enabled = true)
        val rec = AlarmStore.recordingFile(activity, alarm.id)
        if (rec.exists()) rec.copyTo(AlarmStore.recordingFile(activity, copy.id), overwrite = true)
        val e = engine ?: VoiceEngine(activity) { }.also { engine = it }
        val save = {
            AlarmSaver.save(activity, copy, e) {
                rebuild()
                host.showSnack(activity.getString(R.string.alarms_duplicated))
            }
        }
        if (e.isReady || copy.useRecording) save() else main.postDelayed({ save() }, 1200)
    }

    private fun delete(alarm: Alarm) {
        val backupDir = File(activity.cacheDir, "undo").apply { mkdirs() }
        val files = listOf(AlarmStore.speechFile(activity, alarm.id), AlarmStore.recordingFile(activity, alarm.id))
        files.filter { it.exists() }.forEach { it.copyTo(File(backupDir, it.name), overwrite = true) }
        AlarmScheduler.cancel(activity, alarm.id)
        AlarmStore.delete(activity, alarm.id)
        rebuild()
        host.showSnack(activity.getString(R.string.alarms_deleted), activity.getString(R.string.alarms_undo)) {
            files.forEach { f -> File(backupDir, f.name).takeIf { it.exists() }?.copyTo(f, overwrite = true) }
            AlarmStore.save(activity, alarm)
            AlarmScheduler.schedule(activity, alarm)
            rebuild()
        }
    }
}
