package com.voicealarm.app.ui.ring

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import com.voicealarm.app.R
import com.voicealarm.app.ui.Neon
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Live 3D analog clock: brushed-chrome bezel, domed navy glass face with a
 * curved reflection, glowing neon ticks, shadowed hands and a smoothly
 * sweeping neon second hand. Animates only while attached and visible.
 */
class ClockDial3DView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val calendar = Calendar.getInstance()

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bezel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bezelEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val neonRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val faceShade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val tickGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val handShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; color = 0x66000000 }
    private val second = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; color = Neon.RED }
    private val secondGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; color = Neon.withAlpha(Neon.MAGENTA, 0.35f) }
    private val cap = Paint(Paint.ANTI_ALIAS_FLAG)
    private val reflection = Paint(Paint.ANTI_ALIAS_FLAG)
    private val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Neon.TEXT3
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.25f
        typeface = context.resources.getFont(R.font.manrope_extrabold)
    }
    private val reflectionPath = Path()
    private val brandText = context.getString(R.string.ring_brand)

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var faceR = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = (280 * density).toInt()
        val w = resolveSize(wanted, widthMeasureSpec)
        val h = resolveSize(wanted, heightMeasureSpec)
        val s = min(w, h)
        setMeasuredDimension(s, s)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f * 0.84f // leave room for the outer glow
        val bezelW = radius * 0.13f
        faceR = radius - bezelW
        val mid = radius - bezelW / 2

        glow.shader = RadialGradient(
            cx, cy, radius * 1.18f,
            intArrayOf(0, 0, Neon.withAlpha(Neon.RED, 0.55f), Neon.withAlpha(Neon.MAGENTA, 0.18f), 0),
            floatArrayOf(0f, 0.78f, 0.86f, 0.93f, 1f), Shader.TileMode.CLAMP,
        )
        bezel.strokeWidth = bezelW
        bezel.shader = SweepGradient(
            cx, cy,
            intArrayOf(0xFFF4F6FF.toInt(), 0xFF6B7099.toInt(), 0xFFDDE2FF.toInt(), 0xFF3C4070.toInt(), 0xFFB9C0F0.toInt(), 0xFF2A2E58.toInt(), 0xFFF4F6FF.toInt()),
            floatArrayOf(0f, 0.15f, 0.32f, 0.5f, 0.68f, 0.85f, 1f),
        )
        bezelEdge.strokeWidth = 1.5f * density
        bezelEdge.shader = LinearGradient(
            cx - radius, cy - radius, cx + radius, cy + radius,
            Neon.withAlpha(Color.WHITE, 0.9f), Neon.withAlpha(Color.BLACK, 0.6f), Shader.TileMode.CLAMP,
        )
        neonRing.strokeWidth = 2.5f * density
        neonRing.shader = SweepGradient(cx, cy, intArrayOf(Neon.RED, Neon.MAGENTA, Neon.VIOLET, Neon.MAGENTA, Neon.RED), null)

        face.shader = RadialGradient(
            cx - faceR * 0.3f, cy - faceR * 0.35f, faceR * 1.5f,
            intArrayOf(0xFF2A3280.toInt(), 0xFF111644.toInt(), 0xFF05071A.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        faceShade.shader = RadialGradient(
            cx, cy, faceR,
            intArrayOf(0, 0, Neon.withAlpha(Color.BLACK, 0.55f)),
            floatArrayOf(0f, 0.82f, 1f), Shader.TileMode.CLAMP,
        )
        cap.shader = RadialGradient(
            cx - faceR * 0.02f, cy - faceR * 0.025f, faceR * 0.07f,
            intArrayOf(Neon.lighten(Neon.MAGENTA, 0.6f), Neon.RED, Neon.darken(Neon.RED, 0.5f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        hand.shader = LinearGradient(cx - faceR, cy - faceR, cx + faceR, cy + faceR, Color.WHITE, 0xFFB8C6FF.toInt(), Shader.TileMode.CLAMP)
        // Curved reflection band across the upper-left of the glass.
        reflectionPath.reset()
        val outer = RectF(cx - faceR * 0.92f, cy - faceR * 0.92f, cx + faceR * 0.92f, cy + faceR * 0.92f)
        val inner = RectF(cx - faceR * 0.72f, cy - faceR * 0.86f, cx + faceR * 0.82f, cy + faceR * 0.70f)
        reflectionPath.arcTo(outer, 200f, 110f)
        reflectionPath.arcTo(inner, 310f, -110f)
        reflectionPath.close()
        reflection.shader = LinearGradient(
            cx, cy - faceR, cx, cy,
            Neon.withAlpha(Color.WHITE, 0.16f), Neon.withAlpha(Color.WHITE, 0f), Shader.TileMode.CLAMP,
        )
        brand.textSize = faceR * 0.075f
    }

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0) return
        canvas.drawCircle(cx, cy, radius * 1.18f, glow)
        val bezelMid = radius - bezel.strokeWidth / 2
        canvas.drawCircle(cx, cy, bezelMid, bezel)
        canvas.drawCircle(cx, cy, radius - bezelEdge.strokeWidth / 2, bezelEdge)
        canvas.drawCircle(cx, cy, faceR, face)
        canvas.drawCircle(cx, cy, faceR - neonRing.strokeWidth / 2, neonRing)
        drawTicks(canvas)
        canvas.drawText(brandText, cx, cy - faceR * 0.36f, brand)

        calendar.timeInMillis = System.currentTimeMillis()
        val ms = calendar.get(Calendar.MILLISECOND)
        val sec = calendar.get(Calendar.SECOND) + ms / 1000f
        val min = calendar.get(Calendar.MINUTE) + sec / 60f
        val hr = (calendar.get(Calendar.HOUR) + min / 60f)

        val shadowDx = 2f * density
        val shadowDy = 5f * density
        drawHand(canvas, hr * 30f, faceR * 0.5f, faceR * 0.1f, 9f * density, shadowDx, shadowDy)
        drawHand(canvas, min * 6f, faceR * 0.74f, faceR * 0.12f, 6f * density, shadowDx, shadowDy)

        // Neon second hand with counterweight tail.
        val a = Math.toRadians((sec * 6f).toDouble())
        val sx = sin(a).toFloat()
        val sy = -cos(a).toFloat()
        secondGlow.strokeWidth = 8f * density
        second.strokeWidth = 2.5f * density
        canvas.drawLine(cx - sx * faceR * 0.2f, cy - sy * faceR * 0.2f, cx + sx * faceR * 0.82f, cy + sy * faceR * 0.82f, secondGlow)
        canvas.drawLine(cx - sx * faceR * 0.2f, cy - sy * faceR * 0.2f, cx + sx * faceR * 0.82f, cy + sy * faceR * 0.82f, second)
        canvas.drawCircle(cx, cy, faceR * 0.07f, cap)

        canvas.drawCircle(cx, cy, faceR, faceShade)
        canvas.drawPath(reflectionPath, reflection)

        if (isAttachedToWindow && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun drawTicks(canvas: Canvas) {
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0)
            val dx = sin(a).toFloat()
            val dy = -cos(a).toFloat()
            val hour = i % 5 == 0
            val major = i % 15 == 0
            val r1 = faceR * 0.9f
            val r0 = when {
                major -> faceR * 0.74f
                hour -> faceR * 0.8f
                else -> faceR * 0.86f
            }
            if (major) {
                tickGlow.strokeWidth = 12f * density
                tickGlow.color = Neon.withAlpha(Neon.CYAN, 0.28f)
                canvas.drawLine(cx + dx * r0, cy + dy * r0, cx + dx * r1, cy + dy * r1, tickGlow)
                tick.color = 0xFFB8F6FF.toInt()
                tick.strokeWidth = 5f * density
            } else if (hour) {
                tick.color = Neon.CYAN
                tick.strokeWidth = 3.5f * density
            } else {
                tick.color = Neon.withAlpha(Neon.TEXT2, 0.55f)
                tick.strokeWidth = 1.5f * density
            }
            canvas.drawLine(cx + dx * r0, cy + dy * r0, cx + dx * r1, cy + dy * r1, tick)
        }
    }

    private fun drawHand(canvas: Canvas, degrees: Float, length: Float, tail: Float, width: Float, sdx: Float, sdy: Float) {
        val a = Math.toRadians(degrees.toDouble())
        val dx = sin(a).toFloat()
        val dy = -cos(a).toFloat()
        handShadow.strokeWidth = width
        canvas.drawLine(cx - dx * tail + sdx, cy - dy * tail + sdy, cx + dx * length + sdx, cy + dy * length + sdy, handShadow)
        hand.strokeWidth = width
        canvas.drawLine(cx - dx * tail, cy - dy * tail, cx + dx * length, cy + dy * length, hand)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }
}
