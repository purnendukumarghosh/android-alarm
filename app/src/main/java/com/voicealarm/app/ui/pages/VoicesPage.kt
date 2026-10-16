package com.voicealarm.app.ui.pages

import android.app.Activity
import android.media.AudioAttributes
import android.speech.tts.Voice
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.voicealarm.app.Alarm
import com.voicealarm.app.AlarmSaver
import com.voicealarm.app.AppSettings
import com.voicealarm.app.R
import com.voicealarm.app.Reliability
import com.voicealarm.app.VoiceEngine
import com.voicealarm.app.VoicePlayer
import com.voicealarm.app.ui.IconButton3D
import com.voicealarm.app.ui.Neon
import com.voicealarm.app.ui.NeonButton
import com.voicealarm.app.ui.NeonSlider
import com.voicealarm.app.ui.Page
import com.voicealarm.app.ui.PageHost
import com.voicealarm.app.ui.SphereDrawable
import com.voicealarm.app.ui.depthBackground
import com.voicealarm.app.ui.dp
import com.voicealarm.app.ui.dpi
import com.voicealarm.app.ui.glassCard
import com.voicealarm.app.ui.hairline
import com.voicealarm.app.ui.hbox
import com.voicealarm.app.ui.label
import com.voicealarm.app.ui.overline
import com.voicealarm.app.ui.vbox
import java.io.File

/** Voice studio: default voice (with previews), speaking speed, your name, try any phrase. */
class VoicesPage(private val activity: Activity, private val host: PageHost) : Page {
    private val kit = PageScroll(activity)
    override val view: View = kit.scroll
    override val title: CharSequence = activity.getString(R.string.voices_title)
    override val subtitle: CharSequence = activity.getString(R.string.voices_subtitle)

    private var engine: VoiceEngine? = null
    private var player: VoicePlayer? = null
    private var voices: List<Voice> = emptyList()
    private var showAll = false
    private val voiceList = activity.vbox()
    private lateinit var nameExample: TextView
    private lateinit var speedValue: TextView
    private var firstShow = true

    init {
        build()
    }

    override fun onShow() {
        if (engine == null) {
            engine = VoiceEngine(activity) { ok ->
                voices = if (ok) engine?.naturalVoices().orEmpty() else emptyList()
                renderVoices(loaded = true)
            }
        }
        if (firstShow) {
            kit.animateIn()
            firstShow = false
        }
    }

    override fun onHide() = stop()

    override fun onDestroy() {
        stop()
        engine?.shutdown()
        engine = null
    }

    private fun build() {
        // Hero.
        val hero = activity.glassCard(padH = 18, padV = 16, glow = Neon.withAlpha(Neon.CYAN, 0.7f), radiusDp = 26)
        val row = activity.hbox()
        row.addView(ImageView(activity).apply { setImageResource(R.drawable.art_voice); contentDescription = null }, LinearLayout.LayoutParams(activity.dpi(112), activity.dpi(112)))
        val t = activity.vbox()
        t.addView(activity.label(activity.getString(R.string.voices_hero_title), R.style.Text_Headline, sizeSp = 22f))
        t.addView(activity.label(activity.getString(R.string.voices_hero_body), sizeSp = 14f))
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = activity.dpi(10) })
        hero.addView(row)
        val hear = NeonButton(activity, activity.getString(R.string.voices_hear), R.drawable.ic_play_arrow_fill, NeonButton.Kind.PRIMARY)
        hear.setOnClickListener { speak(activity.getString(R.string.voices_sample), AppSettings.defaultVoice(activity)) }
        hero.addView(hear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpi(12) })
        kit.add(hero, top = 8)

        // Your name.
        kit.add(activity.overline(activity.getString(R.string.voices_name)), top = 24)
        val nameCard = activity.glassCard()
        val (box, edit) = activity.sunkenInput(activity.getString(R.string.voices_name_hint), R.drawable.ic_waving_hand)
        edit.setText(AppSettings.userName(activity))
        edit.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        nameCard.addView(box)
        nameExample = activity.label(null, R.style.Text_Body, Neon.TEXT2, 14f).apply { setPadding(dpi(4), dpi(10), 0, 0) }
        nameCard.addView(nameExample)
        updateNameExample(AppSettings.userName(activity))
        val commit = {
            val name = edit.text.toString().trim()
            if (name != AppSettings.userName(activity)) {
                AppSettings.setUserName(activity, name)
                updateNameExample(name)
                host.refreshChrome()
                engine?.let { e -> AlarmSaver.rerenderAll(activity, e) { host.showSnack(activity.getString(R.string.voices_updated)) } }
            }
        }
        edit.setOnEditorActionListener { v, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) {
                commit()
                v.clearFocus()
                (activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)).hideSoftInputFromWindow(v.windowToken, 0)
            }
            false
        }
        edit.setOnFocusChangeListener { _, has -> if (!has) commit() }
        kit.add(nameCard, top = 4)

        // Default voice.
        kit.add(activity.overline(activity.getString(R.string.voices_default)), top = 24)
        val listCard = activity.glassCard(padV = 6)
        listCard.addView(voiceList)
        kit.add(listCard, top = 4)
        renderVoices(loaded = false)

        // Speed.
        kit.add(activity.overline(activity.getString(R.string.voices_speed)), top = 24)
        val speedCard = activity.glassCard()
        speedValue = activity.label(null, R.style.Text_Title, Neon.CYAN, 16f)
        val slider = NeonSlider(activity).apply {
            min = 0.6f; max = 1.2f; step = 0.02f; value = AppSettings.defaultSpeechRate(activity)
            onChange = { v, _ -> updateSpeed(v) }
            onChangeEnd = { v -> AppSettings.setDefaultSpeechRate(activity, v) }
        }
        updateSpeed(slider.value)
        speedCard.addView(speedValue)
        speedCard.addView(slider)
        kit.add(speedCard, top = 4)

        // Try any phrase.
        kit.add(activity.overline(activity.getString(R.string.voices_try)), top = 24)
        val tryCard = activity.glassCard()
        val tryRow = activity.hbox()
        val (tryBox, tryEdit) = activity.sunkenInput(activity.getString(R.string.voices_try_hint))
        tryRow.addView(tryBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val play = IconButton3D(activity, R.drawable.ic_play_arrow_fill, activity.getString(R.string.voices_play), Neon.AQUA)
        play.setOnClickListener {
            val text = tryEdit.text.toString().trim().ifEmpty { activity.getString(R.string.voices_sample) }
            speak(text, AppSettings.defaultVoice(activity))
        }
        tryRow.addView(play, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = activity.dpi(8) })
        tryCard.addView(tryRow)
        kit.add(tryCard, top = 4)

        // Tip.
        val tip = activity.glassCard(outline = Neon.ELECTRIC)
        tip.addView(activity.label(activity.getString(R.string.voices_tip_title), R.style.Text_Title, sizeSp = 16f))
        tip.addView(activity.label(activity.getString(R.string.voices_tip_body), sizeSp = 14f))
        val open = NeonButton(activity, activity.getString(R.string.voices_open_settings), R.drawable.ic_settings, NeonButton.Kind.GHOST)
        open.setOnClickListener { Reliability.fix(activity, Reliability.Kind.TTS) }
        tip.addView(open, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpi(4) })
        kit.add(tip, top = 24)
    }

    private fun updateNameExample(name: String) {
        nameExample.text = if (name.isEmpty()) activity.getString(R.string.voices_name_example_none) else activity.getString(R.string.voices_name_example, name)
    }

    private fun updateSpeed(v: Float) {
        val word = activity.getString(when {
            v < 0.85f -> R.string.speed_slow
            v <= 1.02f -> R.string.speed_natural
            else -> R.string.speed_fast
        })
        speedValue.text = activity.getString(R.string.voices_speed_value, word, v)
    }

    private fun renderVoices(loaded: Boolean) {
        voiceList.removeAllViews()
        if (!loaded) {
            voiceList.addView(activity.label(activity.getString(R.string.voices_loading), R.style.Text_Body).apply { setPadding(0, dpi(14), 0, dpi(14)) })
            return
        }
        if (voices.isEmpty()) {
            voiceList.addView(activity.label(activity.getString(R.string.voices_none_title), R.style.Text_Title, sizeSp = 16f).apply { setPadding(0, dpi(10), 0, 0) })
            voiceList.addView(activity.label(activity.getString(R.string.voices_none_body), sizeSp = 14f))
            val b = NeonButton(activity, activity.getString(R.string.voices_open_settings), R.drawable.ic_settings, NeonButton.Kind.SECONDARY)
            b.setOnClickListener { Reliability.fix(activity, Reliability.Kind.TTS) }
            voiceList.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpi(10); bottomMargin = activity.dpi(8) })
            return
        }
        val selected = AppSettings.defaultVoice(activity)
        voiceList.addView(voiceRow(null, activity.getString(R.string.voices_auto), activity.getString(R.string.voices_auto_detail), selected == null))
        val shown = if (showAll) voices else voices.take(8)
        shown.forEach { v ->
            voiceList.addView(activity.hairline())
            val parts = VoiceEngine.describe(v).split(" · ")
            val name = parts.getOrNull(1) ?: v.name
            val detail = (listOf(parts.getOrNull(0)) + parts.drop(2)).filterNotNull().joinToString(" · ")
            voiceList.addView(voiceRow(v.name, name, detail, selected == v.name))
        }
        if (!showAll && voices.size > 8) {
            voiceList.addView(activity.hairline())
            val more = NeonButton(activity, activity.getString(R.string.voices_show_all, voices.size), R.drawable.ic_expand_more, NeonButton.Kind.GHOST)
            more.setOnClickListener { showAll = true; renderVoices(true) }
            voiceList.addView(more)
        }
    }

    private fun voiceRow(voiceName: String?, title: String, detail: String, selected: Boolean): View {
        val row = activity.hbox().apply {
            minimumHeight = dpi(64)
            isClickable = true
            isFocusable = true
            isSelected = selected
            background = activity.getDrawable(R.drawable.ctl_row_ripple)
            contentDescription = "$title, $detail"
            setOnClickListener {
                AppSettings.setDefaultVoice(activity, voiceName)
                renderVoices(true)
            }
        }
        val dot = View(activity).apply {
            if (selected) depthBackground(SphereDrawable(activity, Neon.CYAN, Neon.GREEN, glow = true, shadow = dp(5)))
            else background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke(dpi(2), Neon.TEXT3)
            }
        }
        val size = if (selected) activity.dpi(18) + dot.paddingLeft + dot.paddingRight else activity.dpi(18)
        row.addView(dot, LinearLayout.LayoutParams(size, if (selected) activity.dpi(18) + dot.paddingTop + dot.paddingBottom else activity.dpi(18)).apply {
            marginEnd = activity.dpi(if (selected) 6 else 16)
            if (!selected) marginStart = activity.dpi(5)
        })
        val t = activity.vbox()
        t.addView(activity.label(title, R.style.Text_Title, if (selected) Neon.TEXT1 else Neon.TEXT1, 16f))
        t.addView(activity.label(detail, R.style.Text_Caption, if (selected) Neon.CYAN else Neon.TEXT2, 12f))
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val play = IconButton3D(activity, R.drawable.ic_play_arrow_fill, activity.getString(R.string.voices_preview_cd, title), sizeDp = 40)
        play.setOnClickListener { speak(activity.getString(R.string.voices_sample), voiceName) }
        row.addView(play)
        return row
    }

    private fun speak(text: String, voiceName: String?) {
        stop()
        val e = engine ?: return
        if (!e.isReady) return
        val out = File(activity.cacheDir, "studio_preview.wav")
        val rate = AppSettings.defaultSpeechRate(activity)
        e.render(text, voiceName, rate, out) { ok ->
            if (!ok) {
                host.showSnack(activity.getString(R.string.voices_failed))
                return@render
            }
            val sample = Alarm(-1, 7, 0, "", greeting = false)
            player = VoicePlayer(activity, AudioAttributes.USAGE_MEDIA).also { it.play(sample, out, loop = false, crescendo = false) }
        }
    }

    private fun stop() {
        player?.stop()
        player = null
    }
}
