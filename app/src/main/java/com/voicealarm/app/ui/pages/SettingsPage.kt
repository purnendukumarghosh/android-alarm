package com.voicealarm.app.ui.pages

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.voicealarm.app.AboutActivity
import com.voicealarm.app.AlarmRingActivity
import com.voicealarm.app.AppSettings
import com.voicealarm.app.R
import com.voicealarm.app.Reliability
import com.voicealarm.app.VoiceEngine
import com.voicealarm.app.ui.FlowLayout
import com.voicealarm.app.ui.Neon
import com.voicealarm.app.ui.NeonChip
import com.voicealarm.app.ui.NeonSheet
import com.voicealarm.app.ui.Page
import com.voicealarm.app.ui.PageHost
import com.voicealarm.app.ui.Toggle3D
import com.voicealarm.app.ui.chevron
import com.voicealarm.app.ui.dpi
import com.voicealarm.app.ui.glassCard
import com.voicealarm.app.ui.hairline
import com.voicealarm.app.ui.icon
import com.voicealarm.app.ui.label
import com.voicealarm.app.ui.listRow
import com.voicealarm.app.ui.overline
import com.voicealarm.app.ui.vbox

/** Settings: alarm defaults, reliability checklist with one-tap fixes, about. */
class SettingsPage(private val activity: Activity, private val host: PageHost) : Page {
    private val kit = PageScroll(activity)
    override val view: View = kit.scroll
    override val title: CharSequence = activity.getString(R.string.settings_title)
    override val subtitle: CharSequence = activity.getString(R.string.settings_subtitle)

    private val reliabilityCard = activity.glassCard(padV = 4)
    private var ttsAvailable: Boolean? = null
    private var engine: VoiceEngine? = null
    private var firstShow = true

    init {
        build()
    }

    override fun onShow() {
        if (engine == null) {
            engine = VoiceEngine(activity) { ok ->
                ttsAvailable = ok && engine?.naturalVoices().orEmpty().isNotEmpty()
                engine?.shutdown()
                renderReliability()
            }
        }
        renderReliability()
        if (firstShow) {
            kit.animateIn()
            firstShow = false
        }
    }

    override fun onDestroy() {
        engine?.shutdown()
    }

    private fun build() {
        kit.add(activity.overline(activity.getString(R.string.settings_defaults)), top = 8)
        val defaults = activity.glassCard(padV = 8)
        defaults.addView(activity.label(activity.getString(R.string.settings_snooze), R.style.Text_Title, sizeSp = 16f).apply { setPadding(0, dpi(8), 0, dpi(6)) })
        defaults.addView(choiceChips(listOf(5, 10, 15, 20, 30), AppSettings.defaultSnooze(activity)) { AppSettings.setDefaultSnooze(activity, it) })
        defaults.addView(activity.hairline().apply { (layoutParams as LinearLayout.LayoutParams).topMargin = activity.dpi(10) })
        defaults.addView(toggleRow(R.drawable.ic_vibration, Neon.VIOLET, R.string.settings_vibrate, R.string.settings_vibrate_sub, AppSettings.defaultVibrate(activity)) { AppSettings.setDefaultVibrate(activity, it) })
        defaults.addView(activity.hairline())
        defaults.addView(toggleRow(R.drawable.ic_volume_up, Neon.CYAN, R.string.settings_gentle, R.string.settings_gentle_sub, AppSettings.gradualVolume(activity)) { AppSettings.setGradualVolume(activity, it) })
        defaults.addView(activity.hairline())
        defaults.addView(activity.label(activity.getString(R.string.settings_ring_for), R.style.Text_Title, sizeSp = 16f).apply { setPadding(0, dpi(12), 0, dpi(6)) })
        defaults.addView(choiceChips(listOf(5, 10, 15, 30), AppSettings.ringMinutes(activity)) { AppSettings.setRingMinutes(activity, it) })
        defaults.addView(View(activity), LinearLayout.LayoutParams(1, activity.dpi(8)))
        kit.add(defaults, top = 4)

        kit.add(activity.overline(activity.getString(R.string.settings_reliability)), top = 24)
        kit.add(reliabilityCard, top = 4)
        kit.add(activity.label(activity.getString(R.string.settings_oem_tip), R.style.Text_Caption, Neon.TEXT2, 13f).apply { setPadding(dpi(6), dpi(8), dpi(6), 0) })

        kit.add(activity.overline(activity.getString(R.string.settings_about)), top = 24)
        val about = activity.glassCard(padV = 4)
        about.addView(activity.listRow(R.drawable.ic_person, Neon.MAGENTA, activity.getString(R.string.settings_about_us), activity.getString(R.string.settings_about_us_sub), activity.chevron()) {
            activity.startActivity(Intent(activity, AboutActivity::class.java))
        })
        about.addView(activity.hairline())
        about.addView(activity.listRow(R.drawable.ic_help, Neon.CYAN, activity.getString(R.string.settings_how), null, activity.chevron()) { HowItWorks.show(activity) })
        about.addView(activity.hairline())
        about.addView(activity.listRow(R.drawable.ic_info, Neon.BLUE, activity.getString(R.string.settings_licenses), null, activity.chevron()) {
            val sheet = NeonSheet(activity)
            sheet.title = activity.getString(R.string.settings_licenses)
            sheet.setContent(activity.label(activity.getString(R.string.about_licenses_body), R.style.Text_Body, sizeSp = 14f))
            sheet.addAction(activity.getString(R.string.main_got_it)) { sheet.dismiss() }
            sheet.show()
        })
        about.addView(activity.hairline())
        about.addView(activity.listRow(R.drawable.ic_star, Neon.AMBER, activity.getString(R.string.settings_version), versionName()))
        kit.add(about, top = 4)
    }

    private fun renderReliability() {
        reliabilityCard.removeAllViews()
        Reliability.checks(activity, ttsAvailable).forEachIndexed { i, check ->
            if (i > 0) reliabilityCard.addView(activity.hairline())
            val trailing: View = if (check.ok) {
                activity.icon(R.drawable.ic_check_circle_fill, Neon.GREEN, 26).apply {
                    contentDescription = activity.getString(R.string.settings_ok_cd, check.title)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }
            } else {
                NeonChip(activity, activity.getString(R.string.settings_fix), R.drawable.ic_bolt_fill, checkable = false).apply {
                    onToggle = { Reliability.fix(activity, check.kind) }
                }
            }
            val accent = if (check.ok) Neon.GREEN else if (check.optional) Neon.BLUE else Neon.AMBER
            val icon = when (check.kind) {
                Reliability.Kind.NOTIFICATIONS -> R.drawable.ic_notifications_active
                Reliability.Kind.EXACT_ALARMS -> R.drawable.ic_schedule
                Reliability.Kind.FULL_SCREEN -> R.drawable.ic_lock
                Reliability.Kind.BATTERY -> R.drawable.ic_battery_charging_full
                Reliability.Kind.TTS -> R.drawable.ic_record_voice_over
            }
            reliabilityCard.addView(activity.listRow(icon, accent, check.title, check.description, trailing,
                if (check.ok) null else ({ Reliability.fix(activity, check.kind) })))
        }
        reliabilityCard.addView(activity.hairline())
        reliabilityCard.addView(activity.listRow(R.drawable.ic_notifications_active_fill, Neon.MAGENTA, activity.getString(R.string.settings_test),
            activity.getString(R.string.settings_test_sub), activity.chevron()) {
            activity.startActivity(Intent(activity, AlarmRingActivity::class.java).putExtra(AlarmRingActivity.EXTRA_PREVIEW, true))
        })
    }

    private fun toggleRow(icon: Int, accent: Int, title: Int, sub: Int, value: Boolean, onChange: (Boolean) -> Unit): View {
        val toggle = Toggle3D(activity).apply {
            setChecked(value, animate = false)
            contentDescription = activity.getString(title)
            onCheckedChange = onChange
        }
        return activity.listRow(icon, accent, activity.getString(title), activity.getString(sub), toggle) { toggle.performClick() }
    }

    private fun choiceChips(values: List<Int>, current: Int, onPick: (Int) -> Unit): View {
        val flow = FlowLayout(activity)
        val chips = mutableListOf<NeonChip>()
        values.forEach { v ->
            val chip = NeonChip(activity, activity.getString(R.string.settings_minutes, v))
            chip.setOn(v == current)
            chip.onToggle = {
                chips.forEach { it.setOn(it === chip) }
                onPick(v)
            }
            chips += chip
            flow.addView(chip)
        }
        return flow
    }

    private fun versionName(): String = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "2.0"
    } catch (e: Exception) {
        "2.0"
    }
}
