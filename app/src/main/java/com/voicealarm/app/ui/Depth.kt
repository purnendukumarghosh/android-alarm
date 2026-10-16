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
 * Hand-drawn 3D surfaces. Every drawable here reserves room around its body
 * for its own soft shadow / glow (see [DepthDrawable.insets]); use
 * [View.depthBackground] so that room is added to the view's padding.
 *
 * Shadows are blurred once into small ALPHA_8 bitmaps and cached, so they
 * render identically on every Android version and with hardware acceleration.
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
 * Raised glass card: soft drop shadow, vertical surface gradient, a sheen on
 * the upper half and a lit rim along the top edge. Optional neon outline and
 * neon glow (for selected / hero cards). Stateful: dims while pressed.
 */
class GlassCardDrawable(
    context: Context,
    private val radius: Float = context.dp(22),
    private val topColor: Int = Neon.SURFACE_TOP,
    private val bottomColor: Int = Neon.SURFACE_BOTTOM,
    private val elevation: Float = context.dp(14),
    var outline: IntArray? = null,
    var glowColor: Int? = null,
    private val pressable: Boolean = false,
) : DepthDrawable() {
    private val density = context.resources.displayMetrics.density
    override val insets = Rect(
        ceil(elevation * 0.9f).toInt(), ceil(elevation * 0.6f).toInt(),
        ceil(elevation * 0.9f).toInt(), ceil(elevation * 1.3f).toInt(),
    )
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Neon.withAlpha(0xFF000000.toInt(), 0.6f) }
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.1f * density }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f * density }
    private val press = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Neon.withAlpha(0xFF000000.toInt(), 0.22f) }
    private var pressed = false

    override fun onBodyChanged() {
        fill.shader = LinearGradient(0f, body.top, 0f, body.bottom, topColor, bottomColor, Shader.TileMode.CLAMP)
        sheen.shader = LinearGradient(
            0f, body.top, 0f, body.top + body.height() * 0.55f,
            Neon.withAlpha(0xFFFFFFFF.toInt(), 0.07f), 0, Shader.TileMode.CLAMP,
        )
        rim.shader = LinearGradient(
            0f, body.top, 0f, body.bottom,
            intArrayOf(Neon.withAlpha(0xFFFFFFFF.toInt(), 0.30f), Neon.withAlpha(0xFFFFFFFF.toInt(), 0.06f), Neon.withAlpha(0xFFFFFFFF.toInt(), 0.02f)),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        if (body.isEmpty) return
        val r = cornerRadius(radius)
        val a = drawAlpha / 255f
        shadowPaint.alpha = (0.6f * 255 * a * if (pressed) 0.7f else 1f).toInt()
        ShadowCache.draw(canvas, body, r, elevation * 0.8f, elevation * 0.35f, shadowPaint)
        glowColor?.let {
            glowPaint.color = Neon.withAlpha(it, 0.45f * a)
            ShadowCache.draw(canvas, body, r, elevation, 0f, glowPaint)
        }
        fill.alpha = drawAlpha
        canvas.drawRoundRect(body, r, r, fill)
        canvas.drawRoundRect(body, r, r, sheen)
        val half = rim.strokeWidth / 2
        val inner = RectF(body.left + half, body.top + half, body.right - half, body.bottom - half)
        val outlineColors = outline
        if (outlineColors != null) {
            border.shader = LinearGradient(body.left, body.top, body.right, body.bottom, outlineColors, null, Shader.TileMode.CLAMP)
            border.alpha = drawAlpha
            val bh = border.strokeWidth / 2
            canvas.drawRoundRect(RectF(body.left + bh, body.top + bh, body.right - bh, body.bottom - bh), r - bh, r - bh, border)
        } else {
            canvas.drawRoundRect(inner, r - half, r - half, rim)
        }
        if (pressed) canvas.drawRoundRect(body, r, r, press)
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
 * Glossy 3D push button: a darker "thickness" under the face, a diagonal
 * neon gradient face, a glossy highlight on its upper half and a colored glow
 * beneath. The face sinks into its base while pressed.
 */
class NeonButtonDrawable(
    context: Context,
    private val colors: IntArray = Neon.AQUA,
    private val radius: Float = -1f, // -1 = pill
    private val depth: Float = context.dp(4),
    private val glow: Boolean = true,
) : DepthDrawable() {
    private val density = context.resources.displayMetrics.density
    private val glowBlur = 14f * density
    override val insets = Rect(
        ceil(10f * density).toInt(), ceil(6f * density).toInt(),
        ceil(10f * density).toInt(), ceil(depth + 12f * density).toInt(),
    )
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val base = Paint(Paint.ANTI_ALIAS_FLAG)
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * density }
    private val faceRect = RectF()
    private var pressed = false
    private var enabled = true

    override fun onBodyChanged() = updateShaders()

    private fun updateShaders() {
        val shift = if (pressed) depth * 0.7f else 0f
        faceRect.set(body.left, body.top + shift, body.right, body.bottom - depth + shift)
        face.shader = LinearGradient(faceRect.left, faceRect.top, faceRect.right, faceRect.bottom, colors, null, Shader.TileMode.CLAMP)
        base.shader = LinearGradient(
            body.left, 0f, body.right, 0f,
            colors.map { Neon.darken(it, 0.55f) }.toIntArray(), null, Shader.TileMode.CLAMP,
        )
        gloss.shader = LinearGradient(
            0f, faceRect.top, 0f, faceRect.top + faceRect.height() * 0.55f,
            Neon.withAlpha(0xFFFFFFFF.toInt(), 0.42f), Neon.withAlpha(0xFFFFFFFF.toInt(), 0.02f), Shader.TileMode.CLAMP,
        )
        rim.shader = LinearGradient(
            0f, faceRect.top, 0f, faceRect.bottom,
            Neon.withAlpha(0xFFFFFFFF.toInt(), 0.65f), Neon.withAlpha(0xFFFFFFFF.toInt(), 0f), Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        if (body.isEmpty) return
        val a = (drawAlpha / 255f) * if (enabled) 1f else 0.38f
        val baseRect = RectF(body.left, body.top + depth, body.right, body.bottom)
        val r = cornerRadius(radius).coerceAtMost(faceRect.height() / 2)
        if (glow && enabled) {
            glowPaint.color = Neon.withAlpha(Neon.blend(colors.first(), colors.last(), 0.5f), (if (pressed) 0.35f else 0.6f) * a)
            ShadowCache.draw(canvas, baseRect, r, glowBlur, 4f * density, glowPaint)
        }
        base.alpha = (255 * a).toInt()
        face.alpha = base.alpha
        gloss.alpha = base.alpha
        canvas.drawRoundRect(baseRect, r, r, base)
        canvas.drawRoundRect(faceRect, r, r, face)
        val g = RectF(faceRect.left + 2 * density, faceRect.top + 1.5f * density, faceRect.right - 2 * density, faceRect.centerY())
        canvas.drawRoundRect(g, r * 0.9f, r * 0.9f, gloss)
        val h = rim.strokeWidth / 2
        canvas.drawRoundRect(RectF(faceRect.left + h, faceRect.top + h, faceRect.right - h, faceRect.bottom - h), r, r, rim)
    }

    override fun outlineRadius() = cornerRadius(radius)

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        val e = state.contains(android.R.attr.state_enabled)
        if (p == pressed && e == enabled) return false
        pressed = p
        enabled = e
        updateShaders()
        invalidateSelf()
        return true
    }
}

/**
 * Glossy sphere (FAB, switch knob, selected day bubble): a radial light
 * falloff, a specular highlight at the top-left, a dark rim at the bottom and
 * a soft contact shadow / neon glow underneath.
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
        ceil(shadow).toInt(), ceil(shadow * 0.6f).toInt(), ceil(shadow).toInt(), ceil(shadow * 1.4f).toInt(),
    )
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spec = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * density }
    private val circle = RectF()
    private var pressed = false

    override fun onBodyChanged() = refresh()

    fun refresh() {
        val d = min(body.width(), body.height())
        circle.set(body.centerX() - d / 2, body.centerY() - d / 2, body.centerX() + d / 2, body.centerY() + d / 2)
        if (d <= 0) return
        val c2 = color2 ?: color
        fill.shader = RadialGradient(
            circle.left + d * 0.36f, circle.top + d * 0.30f, d * 0.85f,
            intArrayOf(Neon.lighten(color, 0.45f), color, Neon.blend(c2, 0xFF000000.toInt(), 0.15f), Neon.darken(c2, 0.55f)),
            floatArrayOf(0f, 0.38f, 0.75f, 1f), Shader.TileMode.CLAMP,
        )
        spec.shader = RadialGradient(
            circle.left + d * 0.34f, circle.top + d * 0.26f, d * 0.26f,
            Neon.withAlpha(0xFFFFFFFF.toInt(), 0.85f), Neon.withAlpha(0xFFFFFFFF.toInt(), 0f), Shader.TileMode.CLAMP,
        )
        rim.shader = LinearGradient(
            0f, circle.top, 0f, circle.bottom,
            Neon.withAlpha(0xFFFFFFFF.toInt(), 0.45f), Neon.withAlpha(0xFFFFFFFF.toInt(), 0.0f), Shader.TileMode.CLAMP,
        )
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (circle.isEmpty) return
        val a = drawAlpha / 255f
        val shift = if (pressed) 1.5f * density else 0f
        val c = RectF(circle).apply { offset(0f, shift) }
        shadowPaint.color = if (glow) Neon.withAlpha(Neon.blend(color, color2 ?: color, 0.5f), 0.55f * a)
        else Neon.withAlpha(0xFF000000.toInt(), 0.55f * a)
        ShadowCache.draw(canvas, c, 0f, shadow, shadow * (if (pressed) 0.2f else 0.4f), shadowPaint, oval = true)
        fill.alpha = drawAlpha
        canvas.drawOval(c, fill)
        canvas.drawOval(c, spec)
        val h = rim.strokeWidth / 2
        canvas.drawOval(RectF(c.left + h, c.top + h, c.right - h, c.bottom - h), rim)
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
 * Inset (sunken) well for text fields, slider tracks and switch tracks: dark
 * fill, inner shadow along the top, faint lit lip along the bottom. Shows a
 * neon outline + glow while focused (or when [active] is set).
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
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lip = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * density }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * density }
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var focused = false

    override fun onBodyChanged() {
        fill.color = fillColor
        inner.shader = LinearGradient(
            0f, body.top, 0f, body.top + (10 * density).coerceAtMost(body.height() * 0.6f),
            Neon.withAlpha(0xFF000000.toInt(), 0.65f), 0, Shader.TileMode.CLAMP,
        )
        lip.shader = LinearGradient(
            0f, body.top, 0f, body.bottom,
            Neon.withAlpha(0xFFFFFFFF.toInt(), 0f), Neon.withAlpha(0xFFFFFFFF.toInt(), 0.12f), Shader.TileMode.CLAMP,
        )
        border.shader = LinearGradient(body.left, 0f, body.right, 0f, activeColors, null, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (body.isEmpty) return
        val r = cornerRadius(radius)
        val on = active || focused
        if (on) {
            glowPaint.color = Neon.withAlpha(activeColors.first(), 0.35f * drawAlpha / 255f)
            ShadowCache.draw(canvas, body, r, 3.5f * density, 0f, glowPaint)
        }
        fill.alpha = drawAlpha
        canvas.drawRoundRect(body, r, r, fill)
        canvas.drawRoundRect(body, r, r, inner)
        val h = lip.strokeWidth / 2
        canvas.drawRoundRect(RectF(body.left + h, body.top + h, body.right - h, body.bottom - h), r, r, lip)
        if (on) {
            border.shader = LinearGradient(body.left, 0f, body.right, 0f, activeColors, null, Shader.TileMode.CLAMP)
            val b = border.strokeWidth / 2
            canvas.drawRoundRect(RectF(body.left + b, body.top + b, body.right - b, body.bottom - b), r, r, border)
        }
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
 * Full-screen app backdrop: deep navy gradient lit by soft neon glows
 * (blue/violet top-left, cyan top-right, magenta bottom-right).
 */
class GlowBackgroundDrawable(private val accent: IntArray = intArrayOf(Neon.BLUE, Neon.CYAN, Neon.MAGENTA)) : Drawable() {
    private val base = Paint()
    private val glows = List(3) { Paint(Paint.ANTI_ALIAS_FLAG) }

    override fun onBoundsChange(bounds: Rect) {
        val w = bounds.width().toFloat().coerceAtLeast(1f)
        val h = bounds.height().toFloat().coerceAtLeast(1f)
        base.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Neon.BG1, Neon.BG0, Neon.BG0), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        val spots = listOf(
            Triple(w * 0.05f, h * 0.06f, 0.30f),
            Triple(w * 1.0f, h * 0.18f, 0.12f),
            Triple(w * 0.95f, h * 0.92f, 0.16f),
        )
        spots.forEachIndexed { i, (x, y, alpha) ->
            glows[i].shader = RadialGradient(
                x, y, maxOf(w, h) * 0.55f,
                Neon.withAlpha(accent[i], alpha), Neon.withAlpha(accent[i], 0f), Shader.TileMode.CLAMP,
            )
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, base)
        glows.forEach { canvas.drawRect(bounds, it) }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
