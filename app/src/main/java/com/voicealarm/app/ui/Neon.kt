package com.voicealarm.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.view.View
import android.widget.TextView

/**
 * "Neon Night" design tokens. Keep in sync with res/values/colors.xml.
 *
 * Gradients:
 *  - AQUA     cyan → green     primary actions, "on" states, FAB
 *  - FLAME    red → magenta    ringing alarm, dismiss, destructive
 *  - ELECTRIC blue → violet    secondary accents, selection
 */
object Neon {
    const val BG0 = 0xFF05070F.toInt()
    const val BG1 = 0xFF0A0F24.toInt()
    const val BG2 = 0xFF11163A.toInt()

    const val SURFACE_TOP = 0xFF1D2354.toInt()
    const val SURFACE_BOTTOM = 0xFF12163A.toInt()
    const val SURFACE_HI = 0xFF283070.toInt()
    const val SUNKEN = 0xFF070A1C.toInt()

    const val CYAN = 0xFF00E5FF.toInt()
    const val GREEN = 0xFF2BFF88.toInt()
    const val BLUE = 0xFF3D7BFF.toInt()
    const val VIOLET = 0xFF8A5CFF.toInt()
    const val RED = 0xFFFF2E63.toInt()
    const val MAGENTA = 0xFFFF3CAC.toInt()
    const val AMBER = 0xFFFFB443.toInt()

    const val TEXT1 = 0xFFF2F5FF.toInt()
    const val TEXT2 = 0xFFA3ACD9.toInt()
    const val TEXT3 = 0xFF67709E.toInt()
    /** Text on bright neon (aqua) buttons. */
    const val ON_NEON = 0xFF04101F.toInt()

    val AQUA = intArrayOf(CYAN, GREEN)
    val FLAME = intArrayOf(RED, MAGENTA)
    val ELECTRIC = intArrayOf(BLUE, VIOLET)

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
 * Paints the text with a horizontal neon gradient and a soft glow of the
 * first color. Re-applies itself whenever the view is laid out.
 */
fun TextView.neonText(colors: IntArray = Neon.AQUA, glow: Boolean = true) {
    fun apply() {
        val w = if (width > 0) width.toFloat() else paint.measureText(text.toString())
        paint.shader = LinearGradient(0f, 0f, w.coerceAtLeast(1f), 0f, colors, null, Shader.TileMode.CLAMP)
        invalidate()
    }
    if (glow) setShadowLayer(dp(14), 0f, 0f, Neon.withAlpha(colors.first(), 0.55f))
    (getTag(NEON_TEXT_TAG) as? View.OnLayoutChangeListener)?.let { removeOnLayoutChangeListener(it) }
    val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply() }
    setTag(NEON_TEXT_TAG, listener)
    addOnLayoutChangeListener(listener)
    apply()
}

private val NEON_TEXT_TAG = com.voicealarm.app.R.id.tag_neon_text
