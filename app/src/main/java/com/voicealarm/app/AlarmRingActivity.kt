package com.voicealarm.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.voicealarm.app.ui.GlassCardDrawable
import com.voicealarm.app.ui.GlowBackgroundDrawable
import com.voicealarm.app.ui.Neon
import com.voicealarm.app.ui.depthBackground
import com.voicealarm.app.ui.dp
import com.voicealarm.app.ui.dpi
import com.voicealarm.app.ui.neonText
import com.voicealarm.app.ui.ring.ClockDial3DView
import com.voicealarm.app.ui.ring.PulseRingsView
import com.voicealarm.app.ui.ring.SlideToDismissView
import com.voicealarm.app.ui.ring.VoiceWaveView
import java.util.Calendar

/**
 * Full-screen ringing screen, shown over the lock screen: animated 3D clock
 * inside pulsing neon rings, the alarm's label, what the voice is saying,
 * a live voice visualizer, Snooze and slide-to-dismiss.
 *
 * With [EXTRA_PREVIEW] it shows without a ringing alarm (Settings → "Test
 * alarm screen", screenshots): buttons just close it.
 */
class AlarmRingActivity : Activity() {

    private var preview = false
    private var receiverRegistered = false

    private lateinit var timeText: TextView
    private lateinit var labelText: TextView
    private lateinit var speakingText: TextView
    private lateinit var snoozeText: TextView
    private lateinit var snoozeButton: View
    private lateinit var clockBox: View
    private lateinit var bottom: View

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!preview && AlarmService.ringingAlarmId == -1) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        bind(intent)

        if (!preview) {
            val filter = IntentFilter(AlarmService.ACTION_RING_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(stateReceiver, filter)
            }
            receiverRegistered = true
        }
        if (savedInstanceState == null) animateIn()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!preview && AlarmService.ringingAlarmId == -1) finish()
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(stateReceiver)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // While ringing, back must not silence the alarm by accident.
        if (preview) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---- UI -------------------------------------------------------------------

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            background = GlowBackgroundDrawable(intArrayOf(Neon.RED, Neon.RED, Neon.RED))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(24), dpi(28), dpi(24), dpi(28))
        }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Top: overline chip + time.
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(14), dpi(6), dpi(16), dpi(6))
            depthBackground(GlassCardDrawable(context, radius = -1f, elevation = dp(8), topColor = 0))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_notifications_active_fill)
                imageTintList = ColorStateList.valueOf(Neon.RED)
            }, LinearLayout.LayoutParams(dpi(18), dpi(18)))
            addView(TextView(context).apply {
                setTextAppearance(R.style.Text_Overline)
                setTextColor(Neon.TEXT1)
                text = getString(if (preview) R.string.ring_preview_badge else R.string.ring_overline)
                setPadding(dpi(8), 0, 0, 0)
            })
        }
        column.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        timeText = TextView(this).apply {
            setTextAppearance(R.style.Text_Title)
            setTextColor(Neon.TEXT2)
            gravity = Gravity.CENTER
        }
        column.addView(timeText, wrap(top = 10))

        // Clock inside pulsing rings.
        val box = FrameLayout(this)
        val pulse = PulseRingsView(this)
        val clock = ClockDial3DView(this)
        box.addView(pulse, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.addView(clock, FrameLayout.LayoutParams(dpi(250), dpi(250), Gravity.CENTER))
        box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> pulse.startRadius = clock.width / 2f * 0.9f }
        clockBox = box
        column.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Label + what the voice says + visualizer.
        labelText = TextView(this).apply {
            setTextAppearance(R.style.Text_Headline)
            textSize = 36f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        column.addView(labelText, wrap(top = 4))
        speakingText = TextView(this).apply {
            setTextAppearance(R.style.Text_Body)
            setTypeface(typeface, Typeface.ITALIC)
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        column.addView(speakingText, wrap(top = 8))
        column.addView(VoiceWaveView(this), LinearLayout.LayoutParams(dpi(200), dpi(44)).apply { topMargin = dpi(14) })

        // Bottom actions.
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        snoozeText = TextView(this).apply {
            setTextAppearance(R.style.Text_Button)
            setTextColor(Neon.TEXT1)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        snoozeButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dpi(56)
            setPadding(dpi(28), 0, dpi(28), 0)
            isClickable = true
            isFocusable = true
            depthBackground(GlassCardDrawable(context, radius = -1f, elevation = dp(10), topColor = 0, outline = Neon.AQUA, pressable = true))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_snooze)
                imageTintList = ColorStateList.valueOf(Neon.TEXT1)
            }, LinearLayout.LayoutParams(dpi(22), dpi(22)))
            addView(snoozeText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(10) })
            setOnClickListener { act(AlarmService.ACTION_SNOOZE) }
        }
        actions.addView(snoozeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val slider = SlideToDismissView(this).apply { onDismiss = { act(AlarmService.ACTION_DISMISS) } }
        actions.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(68)).apply { topMargin = dpi(18) })
        bottom = actions
        column.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(20) })
        return root
    }

    private fun bind(intent: Intent) {
        val id = intent.getIntExtra(AlarmService.EXTRA_ALARM_ID, AlarmService.ringingAlarmId)
        val now = Calendar.getInstance()
        val alarm = AlarmStore.get(this, id)
            ?: if (preview) {
                Alarm(-1, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), getString(R.string.ring_sample_label))
            } else {
                null
            }
        val pattern = DateFormat.getBestDateTimePattern(resources.configuration.locales[0], "EEEMMMd")
        timeText.text = "${Alarm.formatTime(this, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))} · ${DateFormat.format(pattern, now)}"
        val label = alarm?.label?.trim()?.trimEnd('.', '!')?.replaceFirstChar { it.uppercase() }
        labelText.text = if (label.isNullOrEmpty()) getString(R.string.ring_wake_up) else label
        labelText.neonText(Neon.FLAME)
        speakingText.text = alarm?.let { getString(R.string.ring_speaking, it.spokenText(AppSettings.userName(this))) } ?: ""
        val snooze = alarm?.snoozeMinutes ?: AppSettings.defaultSnooze(this)
        snoozeText.text = getString(R.string.ring_snooze, snooze)
        snoozeButton.contentDescription = getString(R.string.ring_snooze_cd, snooze)
    }

    private fun act(action: String) {
        if (!preview) startService(Intent(this, AlarmService::class.java).setAction(action))
        finish()
    }

    private fun animateIn() {
        clockBox.scaleX = 0.85f
        clockBox.scaleY = 0.85f
        clockBox.alpha = 0f
        clockBox.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(520).setInterpolator(OvershootInterpolator(1.1f)).start()
        for ((i, v) in listOf(labelText, speakingText, bottom).withIndex()) {
            v.alpha = 0f
            v.translationY = dp(24)
            v.animate().alpha(1f).translationY(0f).setStartDelay(160L + 80L * i).setDuration(360).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun wrap(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(top) }

    companion object {
        /** Show the screen without a ringing alarm (test / preview). */
        const val EXTRA_PREVIEW = "preview"
    }
}
