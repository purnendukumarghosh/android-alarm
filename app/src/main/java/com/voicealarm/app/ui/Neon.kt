package com.voicealarm.app.ui

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.TextView

/**
 * "Monoline" design tokens. Keep in sync with res/values/colors.xml.
 *
 * The interface is drawn in thin white lines on near-black, like a watch face
 * at night. Color is never decoration: it only signals state, as a small dot
 * or a single thin line (green = on, red = ringing / destructive, amber =
 * needs attention). The legacy names (CYAN, AQUA, ...) are kept so every
 * screen picks up the new language from here.
 */
object Neon {
    const val BG0 = 0xFF050608.toInt()
    const val BG1 = 0xFF0A0B0F.toInt()
    const val BG2 = 0xFF121419.toInt()

    const val SURFACE_TOP = 0xFF0D0E12.toInt()
    const val SURFACE_BOTTOM = 0xFF0B0C10.toInt()
    const val SURFACE_HI = 0xFF15171C.toInt()
    const val SUNKEN = 0xFF07080A.toInt()

    /** Hairlines: resting, and selected / focused. */
    const val LINE = 0x24FFFFFF
    const val LINE_STRONG = 0xCCFFFFFF.toInt()

    /** Primary "accent" is plain white: lines, active states, primary text. */
    const val CYAN = 0xFFF2F3F5.toInt()
    const val GREEN = 0xFF8EE6B0.toInt()
    const val BLUE = 0xFF94B4FF.toInt()
    const val VIOLET = 0xFFB7AAFF.toInt()
    const val RED = 0xFFFF5D6C.toInt()
    const val MAGENTA = 0xFFFF7C9C.toInt()
    const val AMBER = 0xFFF0C46C.toInt()

    const val TEXT1 = 0xFFF2F3F5.toInt()
    const val TEXT2 = 0xFF9A9DA6.toInt()
    const val TEXT3 = 0xFF5F626B.toInt()
    /** Foreground on primary controls (outlined, so it is white too). */
    const val ON_NEON = TEXT1

    val AQUA = intArrayOf(CYAN, CYAN)
    val FLAME = intArrayOf(RED, RED)
    val ELECTRIC = intArrayOf(BLUE, BLUE)

    /**
     * Icons stay monochrome line-art: saturated tints collapse to white,
     * except the two signal colors that carry meaning (red, amber).
     */
    fun lineTint(color: Int): Int {
        if (color == 0 || color == RED || color == AMBER) return color
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        return if (hsv[1] < 0.2f) color else TEXT1
    }

    fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

    /** Mix [color] towards white by [amount] (0..1). */
    fun lighten(color: Int, amount: Float) = blend(color, Color.WHITE, amount)

    /** Mix [color] towards black by [amount] (0..1). */
    fun darken(color: Int, amount: Float) = blend(color, Color.BLACK, amount)

    fun blend(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - k) + ((b shr shift) and 0xFF) * k).toInt()
        return (Color.alpha(a) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

fun Context.dp(value: Number): Float = value.toFloat() * resources.displayMetrics.density
fun Context.dpi(value: Number): Int = (dp(value) + 0.5f).toInt()
fun View.dp(value: Number): Float = context.dp(value)
fun View.dpi(value: Number): Int = context.dpi(value)

/**
 * Former neon gradient text. In the Monoline language emphasis comes from
 * size and weight, so this just clears any shader / glow and uses the
 * primary text color.
 */
@Suppress("UNUSED_PARAMETER")
fun TextView.neonText(colors: IntArray = Neon.AQUA, glow: Boolean = true) {
    (getTag(NEON_TEXT_TAG) as? View.OnLayoutChangeListener)?.let { removeOnLayoutChangeListener(it) }
    setTag(NEON_TEXT_TAG, null)
    paint.shader = null
    setShadowLayer(0f, 0f, 0f, 0)
    setTextColor(Neon.TEXT1)
}

private val NEON_TEXT_TAG = com.voicealarm.app.R.id.tag_neon_text
