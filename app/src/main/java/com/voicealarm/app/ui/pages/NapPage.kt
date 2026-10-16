package com.voicealarm.app.ui.pages

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.voicealarm.app.AlarmSaver
import com.voicealarm.app.AlarmScheduler
import com.voicealarm.app.AlarmStore
import com.voicealarm.app.AppSettings
import com.voicealarm.app.Format
import com.voicealarm.app.R
import com.voicealarm.app.VoiceEngine
import com.voicealarm.app.ui.FlowLayout
import com.voicealarm.app.ui.Neon
import com.voicealarm.app.ui.NeonButton
import com.voicealarm.app.ui.NeonChip
import com.voicealarm.app.ui.NeonSheet
import com.voicealarm.app.ui.NeonSlider
import com.voicealarm.app.ui.Page
import com.voicealarm.app.ui.PageHost
import com.voicealarm.app.ui.dpi
import com.voicealarm.app.ui.glassCard
import com.voicealarm.app.ui.hbox
import com.voicealarm.app.ui.icon
import com.voicealarm.app.ui.label
import com.voicealarm.app.ui.overline
import com.voicealarm.app.ui.vbox
import java.util.Calendar

/** Quick power naps: one tap sets a one-off alarm that says why it's ringing. */
class NapPage(private val activity: Activity, private val host: PageHost) : Page {
    private val kit = PageScroll(activity)
    override val view: View = kit.scroll
    override val title: CharSequence = activity.getString(R.string.nap_title)
    override val subtitle: CharSequence = activity.getString(R.string.nap_subtitle)

    private val main = Handler(Looper.getMainLooper())
    private var engine: VoiceEngine? = null
    private val chips = mutableListOf<NeonChip>()
    private lateinit var custom: android.widget.EditText
    private val upNext = activity.vbox()
    private var busy = false
    private var firstShow = true

    init {
        build()
    }

    override fun onShow() {
        if (engine == null) engine = VoiceEngine(activity) { }
        refreshUpNext()
        if (firstShow) {
            kit.animateIn()
            firstShow = false
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        engine?.shutdown()
        engine = null
    }

    private fun build() {
        // Hero.
        val hero = activity.glassCard(padH = 18, padV = 16, radiusDp = 26)
        val row = activity.hbox()
        row.addView(ImageView(activity).apply { setImageResource(R.drawable.art_moon); contentDescription = null }, LinearLayout.LayoutParams(activity.dpi(108), activity.dpi(108)))
        val t = activity.vbox()
        t.addView(activity.label(activity.getString(R.string.nap_hero_title), R.style.Text_Headline, sizeSp = 24f))
        t.addView(activity.label(activity.getString(R.string.nap_hero_body), sizeSp = 14f))
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = activity.dpi(10) })
        hero.addView(row)
        kit.add(hero, top = 8)

        kit.add(upNext, top = 0)

        // Duration tiles (3 x 3).
        kit.add(activity.overline(activity.getString(R.string.nap_wake_in)), top = 24)
        val values = listOf(5, 10, 15, 20, 30, 45, 60, 90, -1)
        values.chunked(3).forEach { triple ->
            val r = activity.hbox()
            triple.forEachIndexed { i, minutes ->
                r.addView(tile(minutes), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = activity.dpi(2)
                })
            }
            kit.add(r, top = 4)
        }

        // Message.
        kit.add(activity.overline(activity.getString(R.string.nap_say)), top = 22)
        val flow = FlowLayout(activity)
        listOf(R.string.nap_label_over, R.string.nap_label_work, R.string.nap_label_medicine, R.string.nap_label_oven, R.string.nap_label_study).forEachIndexed { i, res ->
            val chip = NeonChip(activity, activity.getString(res))
            if (i == 0) chip.setOn(true)
            chip.onToggle = { on ->
                chips.forEach { if (it !== chip) it.setOn(false) }
                if (!on) chip.setOn(true)
                custom.setText("")
            }
            chips += chip
            flow.addView(chip)
        }
        kit.add(flow, top = 4)
        val (box, edit) = activity.sunkenInput(activity.getString(R.string.nap_custom_hint), R.drawable.ic_edit)
        custom = edit
        edit.setOnFocusChangeListener { v, has -> if (has) chips.forEach { it.setOn(false) } }
        kit.add(box, top = 8)
    }

    private fun tile(minutes: Int): View {
        val card = activity.glassCard(padH = 8, padV = 14, pressable = true, radiusDp = 20)
        card.gravity = Gravity.CENTER
        if (minutes > 0) {
            card.addView(activity.label(minutes.toString(), R.style.Text_Display, sizeSp = 34f, gravity = Gravity.CENTER))
            card.addView(activity.label(activity.getString(R.string.nap_min), R.style.Text_Caption, Neon.TEXT3, gravity = Gravity.CENTER))
            card.contentDescription = activity.getString(R.string.nap_tile_cd, minutes)
            card.setOnClickListener { start(minutes, card) }
        } else {
            card.addView(activity.icon(R.drawable.ic_tune, Neon.MAGENTA, 30), LinearLayout.LayoutParams(activity.dpi(30), activity.dpi(36)))
            card.addView(activity.label(activity.getString(R.string.nap_custom), R.style.Text_Caption, Neon.TEXT3, gravity = Gravity.CENTER))
            card.setOnClickListener { customSheet() }
        }
        return card
    }

    private fun customSheet() {
        val sheet = NeonSheet(activity)
        sheet.title = activity.getString(R.string.nap_custom_title)
        val col = activity.vbox()
        val value = activity.label(activity.getString(R.string.nap_custom_value, 25), R.style.Text_Display, Neon.TEXT1, 40f, Gravity.CENTER)
        val slider = NeonSlider(activity).apply {
            min = 5f; max = 180f; step = 5f; this.value = 25f
            onChange = { v, _ -> value.text = activity.getString(R.string.nap_custom_value, v.toInt()) }
        }
        col.addView(value)
        col.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpi(8) })
        sheet.setContent(col)
        sheet.addAction(activity.getString(R.string.nap_start), NeonButton.Kind.PRIMARY, R.drawable.ic_bedtime) {
            sheet.dismiss()
            start(slider.value.toInt(), null)
        }
        sheet.show()
    }

    private fun message(): String {
        val typed = custom.text.toString().trim()
        if (typed.isNotEmpty()) return typed
        val i = chips.indexOfFirst { it.isOn }.coerceAtLeast(0)
        return activity.getString(listOf(R.string.nap_label_over, R.string.nap_label_work, R.string.nap_label_medicine, R.string.nap_label_oven, R.string.nap_label_study)[i])
    }

    private fun start(minutes: Int, tile: View?) {
        if (busy) return
        busy = true
        tile?.animate()?.alpha(0.5f)?.setDuration(150)?.start()
        val at = Calendar.getInstance().apply {
            add(Calendar.MINUTE, minutes)
            if (get(Calendar.SECOND) > 0 || get(Calendar.MILLISECOND) > 0) add(Calendar.MINUTE, 1)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val alarm = AppSettings.newAlarm(activity, AlarmStore.newId(activity), at.get(Calendar.HOUR_OF_DAY), at.get(Calendar.MINUTE), message())
            .copy(greeting = false, sayTime = false, days = 0)
        AlarmSaver.save(activity, alarm, engine) {
            busy = false
            tile?.animate()?.alpha(1f)?.setDuration(150)?.start()
            refreshUpNext()
            host.showSnack(activity.getString(R.string.nap_set, alarm.timeText(activity)), activity.getString(R.string.alarms_undo)) {
                AlarmScheduler.cancel(activity, alarm.id)
                AlarmStore.delete(activity, alarm.id)
                refreshUpNext()
            }
        }
    }

    private fun refreshUpNext() {
        upNext.removeAllViews()
        val now = System.currentTimeMillis()
        val soon = AlarmStore.all(activity)
            .filter { it.enabled && !it.isRepeating }
            .map { it to AlarmScheduler.nextTrigger(it) }
            .filter { it.second - now <= 3 * 60 * 60_000L }
            .minByOrNull { it.second } ?: return
        val (alarm, at) = soon
        upNext.addView(activity.overline(activity.getString(R.string.nap_up_next)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpi(22) })
        val card = activity.glassCard(padH = 16, padV = 12)
        val row = activity.hbox()
        row.addView(activity.icon(R.drawable.ic_hourglass_top, Neon.CYAN, 26), LinearLayout.LayoutParams(activity.dpi(26), activity.dpi(26)).apply { marginEnd = activity.dpi(14) })
        val t = activity.vbox()
        t.addView(activity.label(alarm.timeText(activity) + " · " + activity.getString(R.string.alarms_in, Format.shortDuration(at - now)), R.style.Text_Title, sizeSp = 16f))
        t.addView(activity.label(alarm.label, R.style.Text_Body, sizeSp = 13f))
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val cancel = NeonButton(activity, activity.getString(R.string.nap_cancel), null, NeonButton.Kind.GHOST)
        cancel.setOnClickListener {
            AlarmScheduler.cancel(activity, alarm.id)
            AlarmStore.delete(activity, alarm.id)
            refreshUpNext()
            host.showSnack(activity.getString(R.string.nap_cancelled))
        }
        row.addView(cancel)
        card.addView(row)
        upNext.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpi(4) })
    }
}
