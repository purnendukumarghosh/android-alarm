package com.voicealarm.app.ui.ring

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.voicealarm.app.ui.Neon
import java.util.Random
import kotlin.math.min
import kotlin.math.pow

/** Hairline rings that keep expanding and fading behind the clock, like ripples on water. */
class PulseRingsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    /** Radius (px) where rings start: just outside the clock. */
    var startRadius = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val cy = h / 2f
        ring.color = Neon.TEXT1
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f
        val r0 = if (startRadius > 0) startRadius else maxR * 0.55f
        for (i in 0 until 3) {
            val p = (phase + i / 3f) % 1f
            val r = r0 + (maxR - r0) * p
            val alpha = (1 - p).pow(1.8f) * 0.4f
            ring.alpha = (alpha * 255).toInt()
            ring.strokeWidth = 1f * density
            canvas.drawCircle(cx, cy, r, ring)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}

/** Live "voice equalizer": thin white lines that move like speech. */
class VoiceWaveView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val bars = 26
    private val levels = FloatArray(bars) { 0.2f }
    private val targets = FloatArray(bars) { 0.2f }
    private val random = Random(7)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var frame = 0
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { step() }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // A pleasant resting shape (louder in the middle) so a still frame looks alive.
        for (i in 0 until bars) {
            val centre = 1f - kotlin.math.abs(i - bars / 2f) / (bars / 2f)
            levels[i] = 0.25f + 0.6f * centre * (0.6f + 0.4f * ((i * 37) % 10) / 10f)
            targets[i] = levels[i]
        }
    }

    private fun step() {
        if (frame++ % 4 == 0) {
            for (i in 0 until bars) {
                val centre = 1f - kotlin.math.abs(i - bars / 2f) / (bars / 2f)
                targets[i] = 0.12f + random.nextFloat() * (0.35f + 0.6f * centre)
            }
        }
        for (i in 0 until bars) levels[i] += (targets[i] - levels[i]) * 0.25f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        paint.color = Neon.withAlpha(Neon.TEXT1, 0.85f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 2f * density
    }

    override fun onDraw(canvas: Canvas) {
        val step = width / bars.toFloat()
        val mid = height / 2f
        val sw = paint.strokeWidth
        for (i in 0 until bars) {
            val h = (height * levels[i] * 0.8f).coerceAtLeast(sw)
            val x = step * (i + 0.5f)
            canvas.drawLine(x, mid - h / 2 + sw / 2, x, mid + h / 2 - sw / 2, paint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
