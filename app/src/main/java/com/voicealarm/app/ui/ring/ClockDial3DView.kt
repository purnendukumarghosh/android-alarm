package com.voicealarm.app.ui.ring

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.voicealarm.app.ui.Neon
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Live analog clock drawn as pure line-art: one thin white circle, two
 * hairline hands and a sweeping red second hand, nothing else, like a
 * minimal watch face on a night sky. Animates only while attached and
 * visible. (Class name kept for the layouts that reference it.)
 */
class ClockDial3DView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val calendar = Calendar.getInstance()

    private val dial = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Neon.withAlpha(Neon.TEXT1, 0.92f) }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; color = Neon.withAlpha(Neon.TEXT1, 0.35f) }
    private val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; color = Neon.TEXT1 }
    private val second = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; color = Neon.RED }
    private val pin = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Neon.TEXT1 }
    private val pinFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Neon.BG0 }

    private var cx = 0f
    private var cy = 0f
    private var faceR = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = (280 * density).toInt()
        val s = min(resolveSize(wanted, widthMeasureSpec), resolveSize(wanted, heightMeasureSpec))
        setMeasuredDimension(s, s)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        faceR = min(w, h) / 2f * 0.74f
        dial.strokeWidth = 1.6f * density
        tick.strokeWidth = 1.2f * density
        pin.strokeWidth = 1.4f * density
    }

    override fun onDraw(canvas: Canvas) {
        if (faceR <= 0) return
        canvas.drawCircle(cx, cy, faceR, dial)
        // Four quiet quarter marks just inside the ring.
        for (i in 0 until 4) {
            val a = Math.toRadians(i * 90.0)
            val dx = sin(a).toFloat()
            val dy = -cos(a).toFloat()
            canvas.drawLine(cx + dx * faceR * 0.86f, cy + dy * faceR * 0.86f, cx + dx * faceR * 0.92f, cy + dy * faceR * 0.92f, tick)
        }

        calendar.timeInMillis = System.currentTimeMillis()
        val sec = calendar.get(Calendar.SECOND) + calendar.get(Calendar.MILLISECOND) / 1000f
        val min = calendar.get(Calendar.MINUTE) + sec / 60f
        val hr = calendar.get(Calendar.HOUR) + min / 60f

        drawHand(canvas, hr * 30f, faceR * 0.5f, 0f, 2.2f * density, hand)
        drawHand(canvas, min * 6f, faceR * 0.78f, 0f, 1.5f * density, hand)
        drawHand(canvas, sec * 6f, faceR * 0.84f, faceR * 0.16f, 1f * density, second)
        canvas.drawCircle(cx, cy, 3.5f * density, pinFill)
        canvas.drawCircle(cx, cy, 3.5f * density, pin)

        if (isAttachedToWindow && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun drawHand(canvas: Canvas, degrees: Float, length: Float, tail: Float, width: Float, paint: Paint) {
        val a = Math.toRadians(degrees.toDouble())
        val dx = sin(a).toFloat()
        val dy = -cos(a).toFloat()
        paint.strokeWidth = width
        canvas.drawLine(cx - dx * tail, cy - dy * tail, cx + dx * length, cy + dy * length, paint)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }
}
