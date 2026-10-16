package com.voicealarm.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.View
import kotlin.math.ceil
import kotlin.math.min

/**
 * Monoline surfaces: flat near-black planes outlined by hairlines. Every
 * drawable reserves a little room around its body (see
 * [DepthDrawable.insets]) which works as consistent spacing between
 * neighbours; use [View.depthBackground] so it is added to the padding.
 *
 * [ShadowCache] is kept for the few soft effects that remain (it blurs once
 * into small ALPHA_8 bitmaps).
 */
abstract class DepthDrawable : Drawable() {
    /** Space around the body reserved for shadow and glow. */
    abstract val insets: Rect

    protected val body = RectF()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        body.set(
            (bounds.left + insets.left).toFloat(),
            (bounds.top + insets.top).toFloat(),
            (bounds.right - insets.right).toFloat(),
            (bounds.bottom - insets.bottom).toFloat(),
        )
        onBodyChanged()
    }

    protected open fun onBodyChanged() {}

    protected var drawAlpha = 255

    override fun setAlpha(alpha: Int) {
        drawAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    protected fun cornerRadius(radius: Float): Float =
        if (radius < 0) min(body.width(), body.height()) / 2f else radius

    override fun getOutline(outline: Outline) {
        // Lets ripples/clipping follow the body, not the shadow area.
        outline.setRoundRect(
            body.left.toInt(), body.top.toInt(), body.right.toInt(), body.bottom.toInt(),
            outlineRadius(),
        )
        outline.alpha = 0f
    }

    protected open fun outlineRadius(): Float = 0f
}

/** Applies [drawable] as background and grows the padding by its shadow insets. */
fun View.depthBackground(drawable: DepthDrawable) {
    val previous = getTag(TAG_INSETS) as? Rect
    val l = paddingLeft - (previous?.left ?: 0)
    val t = paddingTop - (previous?.top ?: 0)
    val r = paddingRight - (previous?.right ?: 0)
    val b = paddingBottom - (previous?.bottom ?: 0)
    background = drawable
    val ins = drawable.insets
    setPadding(l + ins.left, t + ins.top, r + ins.right, b + ins.bottom)
    setTag(TAG_INSETS, Rect(ins))
}

private val TAG_INSETS = com.voicealarm.app.R.id.tag_depth_insets

/** Cache of blurred shape masks used for shadows and glows. */
internal object ShadowCache {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun pad(blur: Float) = ceil(blur * 1.2f).toInt() + 1

    /** Blurred round-rect mask of size w×h, padded by [pad] on every side. */
    fun roundRect(w: Int, h: Int, radius: Float, blur: Float, oval: Boolean = false): Bitmap {
        val key = "$w:$h:${radius.toInt()}:${blur.toInt()}:$oval"
        cache.get(key)?.let { return it }
        val p = pad(blur)
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1) + 2 * p, h.coerceAtLeast(1) + 2 * p, Bitmap.Config.ALPHA_8)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            if (blur >= 1f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        val c = Canvas(bmp)
        val r = RectF(p.toFloat(), p.toFloat(), (p + w).toFloat(), (p + h).toFloat())
        if (oval) c.drawOval(r, paint) else c.drawRoundRect(r, radius, radius, paint)
        cache.put(key, bmp)
        return bmp
    }

    fun draw(canvas: Canvas, rect: RectF, radius: Float, blur: Float, dy: Float, paint: Paint, oval: Boolean = false) {
        if (rect.width() <= 0 || rect.height() <= 0) return
        val bmp = roundRect(rect.width().toInt(), rect.height().toInt(), radius, blur, oval)
        val p = pad(blur)
        canvas.drawBitmap(bmp, rect.left - p, rect.top - p + dy, paint)
    }
}

/**
 * Card: a flat surface a shade lighter than the backdrop, outlined by a
 * hairline. [outline] (any colors) switches to the strong white hairline used
 * for selected cards; [glowColor] is accepted for compatibility and adds only
 * a faint tint of the outline. Stateful: lightens slightly while pressed.
 */
class GlassCardDrawable(
    context: Context,
    private val radius: Float = context.dp(22),
    private val topColor: Int = Neon.SURFACE_TOP,
    @Suppress("UNUSED_PARAMETER") bottomColor: Int = Neon.SURFACE_BOTTOM,
    private val elevation: Float = context.dp(14),
    var outline: IntArray? = null,
    var glowColor: Int? = null,
    private val pressable: Boolean = false,
) : DepthDrawable() {
    private val density = context.resources.displayMetrics.density
    override val insets = Rect(
        ceil(elevation * 0.5f).toInt(), ceil(elevation * 0.35f).toInt(),
        ceil(elevation * 0.5f).toInt(), ceil(elevation * 0.55f).toInt(),
    )
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * density }
    private val press = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x0FFFFFFF }
    private val inner = RectF()
    private var pressed = false

    override fun draw(canvas: Canvas) {
        if (body.isEmpty) return
        val r = cornerRadius(radius)
        fill.color = topColor
        fill.alpha = (android.graphics.Color.alpha(topColor) * drawAlpha / 255f).toInt()
        canvas.drawRoundRect(body, r, r, fill)
        if (pressed) canvas.drawRoundRect(body, r, r, press)
        val selected = outline != null
        line.strokeWidth = (if (selected) 1.2f else 1f) * density
        line.color = if (selected) Neon.withAlpha(Neon.TEXT1, 0.75f * drawAlpha / 255f)
        else Neon.withAlpha(0xFFFFFFFF.toInt(), 0.11f * drawAlpha / 255f)
        val h = line.strokeWidth / 2
        inner.set(body.left + h, body.top + h, body.right - h, body.bottom - h)
        canvas.drawRoundRect(inner, r - h, r - h, line)
    }

    override fun outlineRadius() = cornerRadius(radius)

    override fun isStateful() = pressable

    override fun onStateChange(state: IntArray): Boolean {
        val p = pressable && state.contains(android.R.attr.state_pressed)
        if (p == pressed) return false
        pressed = p
        invalidateSelf()
        return true
    }
}

/**
 * Outlined pill button. White hairline for primary actions, red hairline for
 * destructive ones ([colors] containing [Neon.RED]), a quieter grey line for
 * everything else. Pressing fills it with a faint wash; disabled dims it.
 */
class NeonButtonDrawable(
    context: Context,
    private val colors: IntArray = Neon.AQUA,
    private val radius: Float = -1f, // -1 = pill
    @Suppress("UNUSED_PARAMETER") depth: Float = context.dp(4),
    @Suppress("UNUSED_PARAMETER") glow: Boolean = true,
) : DepthDrawable() {
    private val density = context.resources.displayMetrics.density
    override val insets = Rect(
        ceil(4f * density).toInt(), ceil(4f * density).toInt(),
        ceil(4f * density).toInt(), ceil(6f * density).toInt(),
    )
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * density }
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inner = RectF()
    private var pressed = false
    private var enabled = true

    private val lineColor: Int
        get() = when {
            colors.contains(Neon.RED) -> Neon.RED
            colors.all { it == Neon.CYAN } -> Neon.TEXT1
            else -> Neon.lineTint(colors.first()).let { if (it == Neon.TEXT1) Neon.withAlpha(Neon.TEXT1, 0.55f) else it }
        }

    override fun draw(canvas: Canvas) {
        if (body.isEmpty) return
        val a = (drawAlpha / 255f) * if (enabled) 1f else 0.35f
        val r = cornerRadius(radius)
        val c = lineColor
        if (pressed) {
            wash.color = Neon.withAlpha(c, 0.12f * a)
            canvas.drawRoundRect(body, r, r, wash)
        }
        line.color = Neon.withAlpha(c, (android.graphics.Color.alpha(c) / 255f) * 0.9f * a)
        val h = line.strokeWidth / 2
        inner.set(body.left + h, body.top + h, body.right - h, body.bottom - h)
        canvas.drawRoundRect(inner, r - h, r - h, line)
    }

    override fun outlineRadius() = cornerRadius(radius)

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        val e = state.contains(android.R.attr.state_enabled)
        if (p == pressed && e == enabled) return false
        pressed = p
        enabled = e
        invalidateSelf()
        return true
    }
}

/**
 * Circle drawn as a single thin line (FAB, record button, selected day,
 * avatar). [color] tints the line when it carries meaning (red while
 * recording); otherwise it is white. With [glow] set the circle also gets a
 * whisper-faint fill so it reads as "active".
 */
class SphereDrawable(
    context: Context,
    var color: Int = Neon.CYAN,
    var color2: Int? = null,
    var glow: Boolean = true,
    private val shadow: Float = context.dp(8),
) : DepthDrawable() {
    private val density = context.resources.displayMetrics.density
    override val insets = Rect(
        ceil(shadow * 0.4f).toInt(), ceil(shadow * 0.4f).toInt(), ceil(shadow * 0.4f).toInt(), ceil(shadow * 0.4f).toInt(),
    )
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.3f * density }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circle = RectF()
    private var pressed = false

    /** Optional opaque disc behind the line (for circles floating over content). */
    var base: Int? = null

    override fun onBodyChanged() = refresh()

    fun refresh() {
        val d = min(body.width(), body.height())
        circle.set(body.centerX() - d / 2, body.centerY() - d / 2, body.centerX() + d / 2, body.centerY() + d / 2)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (circle.isEmpty) return
        val a = drawAlpha / 255f
        val c = Neon.lineTint(color)
        base?.let {
            fill.color = Neon.withAlpha(it, a)
            canvas.drawOval(circle, fill)
        }
        if (glow || pressed) {
            fill.color = Neon.withAlpha(c, (if (pressed) 0.16f else 0.06f) * a)
            canvas.drawOval(circle, fill)
        }
        line.color = Neon.withAlpha(c, android.graphics.Color.alpha(c) / 255f * 0.92f * a)
        val h = line.strokeWidth / 2
        canvas.drawOval(RectF(circle.left + h, circle.top + h, circle.right - h, circle.bottom - h), line)
    }

    override fun getOutline(outline: Outline) {
        outline.setOval(circle.left.toInt(), circle.top.toInt(), circle.right.toInt(), circle.bottom.toInt())
        outline.alpha = 0f
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        if (p == pressed) return false
        pressed = p
        invalidateSelf()
        return true
    }
}

/**
 * Field / track well: transparent with a resting hairline that turns into a
 * strong white line while focused (or when [active] is set).
 */
class SunkenDrawable(
    context: Context,
    private val radius: Float = context.dp(16),
    var active: Boolean = false,
    var activeColors: IntArray = Neon.AQUA,
    private val fillColor: Int = Neon.SUNKEN,
) : DepthDrawable() {
    private val density = context.resources.displayMetrics.density
    override val insets = Rect(ceil(4 * density).toInt(), ceil(4 * density).toInt(), ceil(4 * density).toInt(), ceil(4 * density).toInt())
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * density }
    private var focused = false

    override fun draw(canvas: Canvas) {
        if (body.isEmpty) return
        val r = cornerRadius(radius)
        val on = active || focused
        fill.color = Neon.withAlpha(fillColor, 0.6f * drawAlpha / 255f)
        canvas.drawRoundRect(body, r, r, fill)
        line.color = if (on) Neon.withAlpha(Neon.lineTint(activeColors.first()), 0.8f * drawAlpha / 255f)
        else Neon.withAlpha(0xFFFFFFFF.toInt(), 0.13f * drawAlpha / 255f)
        val h = line.strokeWidth / 2
        canvas.drawRoundRect(RectF(body.left + h, body.top + h, body.right - h, body.bottom - h), r - h, r - h, line)
    }

    override fun outlineRadius() = cornerRadius(radius)

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val f = state.contains(android.R.attr.state_focused)
        if (f == focused) return false
        focused = f
        invalidateSelf()
        return true
    }
}

/**
 * Full-screen backdrop: a night sky. Near-black with a barely-there lift at
 * the top, a sparse field of faint stars (deterministic, so screenshots are
 * stable) and a soft horizon haze low on the screen tinted by [accent]'s
 * first color only when it signals something (red on the ringing screen).
 */
class GlowBackgroundDrawable(private val accent: IntArray = intArrayOf(Neon.BLUE, Neon.CYAN, Neon.MAGENTA)) : Drawable() {
    private val base = Paint()
    private val haze = Paint(Paint.ANTI_ALIAS_FLAG)
    private val star = Paint(Paint.ANTI_ALIAS_FLAG)
    private var stars = FloatArray(0)

    override fun onBoundsChange(bounds: Rect) {
        val w = bounds.width().toFloat().coerceAtLeast(1f)
        val h = bounds.height().toFloat().coerceAtLeast(1f)
        base.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFF0B0D12.toInt(), Neon.BG0, Neon.BG0), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        val tint = if (accent.first() == Neon.RED) Neon.RED else 0xFF8FA6C8.toInt()
        haze.shader = RadialGradient(
            w * 0.5f, h * 1.05f, maxOf(w, h) * 0.6f,
            Neon.withAlpha(tint, if (accent.first() == Neon.RED) 0.16f else 0.07f), Neon.withAlpha(tint, 0f), Shader.TileMode.CLAMP,
        )
        // x, y, radius, alpha — a seeded LCG keeps the sky identical between runs.
        val dpx = (bounds.width() / 360f).coerceAtLeast(1f)
        val n = ((w / dpx) * (h / dpx) / 3000f).toInt().coerceIn(24, 140)
        var seed = 0x2545F491L
        fun rnd(): Float { seed = (seed * 6364136223846793005L + 1442695040888963407L); return ((seed ushr 33) % 10000L) / 10000f }
        val s = FloatArray(n * 4)
        val d = dpx
        for (i in 0 until n) {
            s[i * 4] = bounds.left + rnd() * w
            s[i * 4 + 1] = bounds.top + rnd() * rnd() * h * 0.75f
            s[i * 4 + 2] = (0.35f + rnd() * rnd() * 0.9f) * d
            s[i * 4 + 3] = 0.08f + rnd() * rnd() * 0.42f
        }
        stars = s
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, base)
        for (i in 0 until stars.size / 4) {
            star.color = Neon.withAlpha(0xFFFFFFFF.toInt(), stars[i * 4 + 3])
            canvas.drawCircle(stars[i * 4], stars[i * 4 + 1], stars[i * 4 + 2], star)
        }
        canvas.drawRect(bounds, haze)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
