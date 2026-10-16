package com.voicealarm.app.ui.ring

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import com.voicealarm.app.R
import com.voicealarm.app.ui.Neon

/**
 * "Slide to dismiss": a sunken pill track with shimmering text and a glossy
 * neon sphere knob. Dragging the knob past 85% completes; otherwise it springs
 * back. TalkBack users activate it with a double-tap (performClick).
 */
class SlideToDismissView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var onDismiss: (() -> Unit)? = null
    var label: CharSequence = context.getString(R.string.ring_slide_to_dismiss)
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Neon.SUNKEN }
    private val innerShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lip = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * resources.displayMetrics.density }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = context.resources.getFont(R.font.manrope_extrabold)
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
        letterSpacing = 0.04f
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobSpec = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shimmerMatrix = Matrix()
    private val arrow: Drawable? = context.getDrawable(R.drawable.ic_chevron_right)?.mutate()?.apply { setTint(0xFFFFFFFF.toInt()) }
    private val rect = RectF()

    private var progress = 0f
    private var dragging = false
    private var downX = 0f
    private var startProgress = 0f
    private var done = false
    private var shimmer = 0f
    private val shimmerAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            shimmer = it.animatedValue as Float
            invalidate()
        }
    }
    private var settle: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.ring_dismiss_cd)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = resolveSize((68 * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(resolveSize((320 * density).toInt(), widthMeasureSpec), h)
    }

    private val pad get() = 6f * density
    private val knobR get() = height / 2f - pad
    private val travel get() = (width - 2 * pad - 2 * knobR).coerceAtLeast(1f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        innerShadow.shader = LinearGradient(0f, 0f, 0f, h * 0.45f, Neon.withAlpha(0xFF000000.toInt(), 0.7f), 0, Shader.TileMode.CLAMP)
        lip.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0, Neon.withAlpha(0xFFFFFFFF.toInt(), 0.14f), Shader.TileMode.CLAMP)
        text.shader = LinearGradient(
            0f, 0f, w * 0.35f, 0f,
            intArrayOf(Neon.TEXT3, Neon.TEXT1, Neon.TEXT3), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val r = h / 2
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)
        canvas.drawRoundRect(rect, r, r, innerShadow)
        rect.inset(lip.strokeWidth / 2, lip.strokeWidth / 2)
        canvas.drawRoundRect(rect, r, r, lip)

        val kx = pad + knobR + travel * progress
        val ky = h / 2
        // Neon trail filling the track behind the knob.
        if (progress > 0.01f) {
            fill.shader = LinearGradient(0f, 0f, kx + knobR, 0f, Neon.withAlpha(Neon.RED, 0.15f), Neon.withAlpha(Neon.MAGENTA, 0.55f), Shader.TileMode.CLAMP)
            rect.set(pad, pad, kx + knobR, h - pad)
            canvas.drawRoundRect(rect, knobR, knobR, fill)
        }

        // Shimmering label, fading out as the knob moves.
        shimmerMatrix.setTranslate(-w * 0.35f + (w * 1.35f) * shimmer, 0f)
        text.shader?.setLocalMatrix(shimmerMatrix)
        text.alpha = ((1f - progress * 1.6f).coerceIn(0f, 1f) * 255).toInt()
        val baseline = h / 2 - (text.descent() + text.ascent()) / 2
        canvas.drawText(label, 0, label.length, w / 2 + knobR * 0.6f, baseline, text)

        // Knob: contact shadow, glossy FLAME sphere, specular highlight, arrow.
        knobShadow.shader = RadialGradient(kx, ky + 3 * density, knobR * 1.25f, Neon.withAlpha(Neon.MAGENTA, 0.55f), 0, Shader.TileMode.CLAMP)
        canvas.drawCircle(kx, ky + 3 * density, knobR * 1.25f, knobShadow)
        knob.shader = RadialGradient(
            kx - knobR * 0.35f, ky - knobR * 0.45f, knobR * 1.7f,
            intArrayOf(Neon.lighten(Neon.MAGENTA, 0.45f), Neon.RED, Neon.darken(Neon.RED, 0.55f)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(kx, ky, knobR, knob)
        knobSpec.shader = RadialGradient(kx - knobR * 0.3f, ky - knobR * 0.42f, knobR * 0.45f, Neon.withAlpha(0xFFFFFFFF.toInt(), 0.75f), 0, Shader.TileMode.CLAMP)
        canvas.drawCircle(kx - knobR * 0.3f, ky - knobR * 0.42f, knobR * 0.45f, knobSpec)
        arrow?.let {
            val s = (knobR * 0.62f).toInt()
            it.setBounds((kx - s).toInt(), (ky - s).toInt(), (kx + s).toInt(), (ky + s).toInt())
            it.draw(canvas)
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (done || !isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val kx = pad + knobR + travel * progress
                if (kotlin.math.abs(event.x - kx) > knobR * 1.6f) return false
                settle?.cancel()
                dragging = true
                downX = event.x
                startProgress = progress
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                progress = (startProgress + (event.x - downX) / travel).coerceIn(0f, 1f)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP && progress >= 0.85f) complete() else springBack()
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (!done) complete()
        return true
    }

    private fun complete() {
        done = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        animateTo(1f, 160) { onDismiss?.invoke() }
    }

    private fun springBack() = animateTo(0f, 420, OvershootInterpolator(1.4f))

    private fun animateTo(target: Float, ms: Long, interp: android.animation.TimeInterpolator = DecelerateInterpolator(), end: (() -> Unit)? = null) {
        settle?.cancel()
        settle = ValueAnimator.ofFloat(progress, target).apply {
            duration = ms
            interpolator = interp
            addUpdateListener {
                progress = (it.animatedValue as Float).coerceIn(0f, 1f)
                invalidate()
            }
            if (end != null) {
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = end()
                })
            }
            start()
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Button::class.java.name
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        shimmerAnim.start()
    }

    override fun onDetachedFromWindow() {
        shimmerAnim.cancel()
        settle?.cancel()
        super.onDetachedFromWindow()
    }
}
