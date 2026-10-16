package com.voicealarm.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.voicealarm.app.R

/* Small builders that keep screen code short and consistent. */

fun Context.label(text: CharSequence?, style: Int = R.style.Text_Body, color: Int? = null, sizeSp: Float? = null, gravity: Int? = null) =
    TextView(this).apply {
        setTextAppearance(style)
        this.text = text
        color?.let { setTextColor(it) }
        sizeSp?.let { textSize = it }
        gravity?.let { this.gravity = it }
    }

fun Context.icon(res: Int, tint: Int = Neon.TEXT1, sizeDp: Int = 24) = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(Neon.lineTint(tint))
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    layoutParams = LinearLayout.LayoutParams(dpi(sizeDp), dpi(sizeDp))
}

fun Context.vbox(gravity: Int = Gravity.NO_GRAVITY) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    this.gravity = gravity
}

fun Context.hbox(gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    this.gravity = gravity
}

/** Hairline-outlined card (vertical). */
fun Context.glassCard(
    padH: Int = 16, padV: Int = 16,
    outline: IntArray? = null, glow: Int? = null, pressable: Boolean = false, radiusDp: Int = 22,
) = vbox().apply {
    setPadding(dpi(padH), dpi(padV), dpi(padH), dpi(padV))
    depthBackground(GlassCardDrawable(context, radius = dp(radiusDp), outline = outline, glowColor = glow, pressable = pressable))
    if (pressable) {
        isClickable = true
        isFocusable = true
    }
}

/** Section header in tracked caps. */
fun Context.overline(text: CharSequence) = label(text, R.style.Text_Overline).apply {
    setPadding(dpi(6), 0, 0, dpi(2))
}

fun Context.hairline() = View(this).apply {
    setBackgroundColor(0x12FFFFFF)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
}

fun Context.space(dp: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dpi(dp)) }

fun View.lp(
    w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    weight: Float = 0f, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0, gravity: Int = -1,
): View {
    layoutParams = LinearLayout.LayoutParams(w, h, weight).also {
        it.topMargin = dpi(top)
        it.bottomMargin = dpi(bottom)
        it.marginStart = dpi(start)
        it.marginEnd = dpi(end)
        if (gravity != -1) it.gravity = gravity
    }
    return this
}

fun Context.dpLp(wDp: Int, hDp: Int) = LinearLayout.LayoutParams(if (wDp < 0) wDp else dpi(wDp), if (hDp < 0) hDp else dpi(hDp))

/**
 * Thin ring that frames a line icon (settings rows, features). Monochrome:
 * the [color] only shows when it is a signal color (red / amber).
 */
class TileDrawable(private val color: Int) : Drawable() {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }

    override fun draw(canvas: Canvas) {
        val b = RectF(bounds)
        ring.strokeWidth = b.width() / 40f * 1.0f.coerceAtLeast(1f)
        val c = Neon.lineTint(color)
        ring.color = if (c == Neon.TEXT1) 0x2EFFFFFF else Neon.withAlpha(c, 0.55f)
        val h = ring.strokeWidth / 2
        b.inset(h, h)
        canvas.drawOval(b, ring)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

fun Context.iconTile(res: Int, color: Int, sizeDp: Int = 40) = FrameLayout(this).apply {
    background = TileDrawable(color)
    addView(icon(res, color, 20), FrameLayout.LayoutParams(dpi(20), dpi(20), Gravity.CENTER))
    layoutParams = LinearLayout.LayoutParams(dpi(sizeDp), dpi(sizeDp))
}
