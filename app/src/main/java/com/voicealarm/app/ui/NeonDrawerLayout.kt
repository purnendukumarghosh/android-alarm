package com.voicealarm.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.voicealarm.app.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Root layout with a 3D side navigation drawer.
 *
 * - The main content is whatever is added as a child (XML children or
 *   [addView]); it lives in an internal "card" that, as the drawer opens,
 *   scales to 0.88, slides towards the end edge, rounds its corners, gains a
 *   soft shadow + neon rim, tilts back around the Y axis and is dimmed by a
 *   scrim. The card paints [contentBackground] (the app's glow backdrop by
 *   default), so the content itself can stay transparent.
 * - The drawer panel ([setDrawer]) is min(320dp, 84% of width) wide, has its
 *   own glass background with a neon edge, and slides in from the start edge.
 * - Gestures: swipe from the start edge (20dp) to open; drag the panel or the
 *   card to close; flings; tap the card to close. While closed, only touches
 *   that start inside the edge strip are watched (and only stolen once they
 *   move horizontally), so content scrolling is never blocked.
 * - [handleBack] closes the drawer. Open state survives configuration changes
 *   when this view has an id.
 *
 * Mirrors itself for right-to-left layouts.
 */
class NeonDrawerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Called when the drawer commits to opening (true) or closing (false). */
    var onDrawerStateChanged: ((open: Boolean) -> Unit)? = null

    /** Called on every frame of the slide with the open fraction (0 = closed, 1 = open). */
    var onDrawerSlide: ((fraction: Float) -> Unit)? = null

    /** Edge-swipe / drag gestures. Taps on the dimmed card still close an open drawer. */
    var gesturesEnabled = true

    /** True when the drawer is open or committed to opening. */
    val isOpen: Boolean get() = committedOpen

    /** Current open fraction, 0..1. */
    val slideFraction: Float get() = slide

    /** The drawer panel view passed to [setDrawer]. */
    var drawerView: View? = null
        private set

    /** The content (first child added to this layout), if any. */
    val contentView: View? get() = if (card.childCount > 0) card.getChildAt(0) else null

    /** Backdrop painted behind the content inside the 3D card. */
    var contentBackground: Drawable?
        get() = card.background
        set(value) {
            card.background = value
        }

    // ------------------------------------------------------------ structure

    private val card = FrameLayout(context)
    private val panel = Panel(context)
    private val overlay = CardOverlay()
    private var addingInternal = false

    private var drawerWidth = 0
    private var slide = 0f
    private var committedOpen = false
    private var animator: ValueAnimator? = null

    // Card transform.
    private val maxCardElevation = dp(16)
    private val cardRadius = dp(26)
    private var cardCorner = 0f
    private var backdropShown = false

    // Space backdrop revealed around the card.
    private val spaceBase = Paint().apply { color = Neon.BG0 }
    private val spaceGlowA = Paint()
    private val spaceGlowB = Paint()

    // Gestures.
    private val config = ViewConfiguration.get(context)
    private val touchSlop = config.scaledTouchSlop
    private val minFlingVelocity = max(config.scaledMinimumFlingVelocity.toFloat(), dp(420))
    private val maxFlingVelocity = config.scaledMaximumFlingVelocity.toFloat()
    private val edgeSize = dp(20)
    private var velocityTracker: VelocityTracker? = null
    private var activePointerId = INVALID_POINTER
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var tracking = false
    private var dragging = false
    private var downOnCard = false

    init {
        setWillNotDraw(false)
        addingInternal = true
        card.background = GlowBackgroundDrawable()
        card.foreground = overlay
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cardCorner)
                outline.alpha = 1f
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            card.outlineAmbientShadowColor = Neon.CYAN
            card.outlineSpotShadowColor = Neon.VIOLET
        }
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        panel.visibility = INVISIBLE
        addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addingInternal = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            panel.accessibilityPaneTitle = context.getString(R.string.nav_drawer_title)
        }
    }

    /** External children become the content (inside the 3D card). */
    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        if (addingInternal) super.addView(child, index, params) else card.addView(child, index, params)
    }

    /** Sets (replaces) the drawer panel content, e.g. a [DrawerMenu]. */
    fun setDrawer(view: View) {
        drawerView?.let { panel.removeView(it) }
        (view.parent as? ViewGroup)?.removeView(view)
        drawerView = view
        panel.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applySlide()
    }

    fun open(animate: Boolean = true) = settle(true, animate)

    fun close(animate: Boolean = true) = settle(false, animate)

    fun toggle() = if (committedOpen) close() else open()

    /** Closes the drawer if it is open. Returns true if the back press was consumed. */
    fun handleBack(): Boolean {
        if (!committedOpen && slide == 0f) return false
        close()
        return true
    }

    // --------------------------------------------------------------- layout

    private val isRtl get() = layoutDirection == LAYOUT_DIRECTION_RTL
    private val dir get() = if (isRtl) -1f else 1f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        drawerWidth = min(dpi(320), (w * 0.84f).toInt())
        card.measure(exactly(w), exactly(h))
        panel.measure(exactly(drawerWidth), exactly(h))
    }

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        card.layout(0, 0, w, h)
        if (isRtl) panel.layout(w - drawerWidth, 0, w, h) else panel.layout(0, 0, drawerWidth, h)
        panel.mirrored = isRtl
        applySlide()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fw = w.toFloat().coerceAtLeast(1f)
        val fh = h.toFloat().coerceAtLeast(1f)
        val farX = if (isRtl) 0f else fw
        spaceGlowA.shader = RadialGradient(
            farX, fh * 0.42f, max(fw, fh) * 0.6f,
            Neon.withAlpha(Neon.VIOLET, 0.24f), Neon.withAlpha(Neon.VIOLET, 0f), Shader.TileMode.CLAMP,
        )
        spaceGlowB.shader = RadialGradient(
            farX, fh * 0.95f, max(fw, fh) * 0.45f,
            Neon.withAlpha(Neon.CYAN, 0.14f), Neon.withAlpha(Neon.CYAN, 0f), Shader.TileMode.CLAMP,
        )
        // Perspective for the Y tilt: camera ~700dp away from the card (the
        // framework divides by densityDpi and works in 1/72 inch units).
        card.cameraDistance = dp(700) * resources.displayMetrics.densityDpi / 72f
    }

    override fun onDraw(canvas: Canvas) {
        if (slide <= 0f || background != null) return
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, spaceBase)
        canvas.drawRect(0f, 0f, w, h, spaceGlowA)
        canvas.drawRect(0f, 0f, w, h, spaceGlowB)
    }

    // ---------------------------------------------------------------- slide

    private fun setSlide(value: Float) {
        val p = value.coerceIn(0f, 1f)
        if (p == slide) return
        if (slide == 0f && p > 0f) (drawerView as? DrawerMenu)?.playEntrance()
        slide = p
        applySlide()
        onDrawerSlide?.invoke(p)
    }

    private fun applySlide() {
        val p = slide
        val w = width.toFloat()
        val h = height.toFloat()
        if (p <= 0f) {
            card.scaleX = 1f
            card.scaleY = 1f
            card.translationX = 0f
            card.rotationY = 0f
            card.elevation = 0f
            card.clipToOutline = false
            overlay.update(0f, 0f, 1f)
            panel.translationX = -dir * drawerWidth
            panel.visibility = INVISIBLE
            drawerView?.translationX = 0f
            if (backdropShown) {
                backdropShown = false
                invalidate()
            }
            return
        }
        if (!backdropShown) {
            backdropShown = true
            invalidate()
        }
        panel.visibility = VISIBLE
        panel.translationX = -dir * drawerWidth * (1f - p)
        drawerView?.translationX = -dir * dp(28) * (1f - p) // parallax: menu lags behind its glass

        val scale = 1f - 0.12f * p
        card.pivotX = w / 2f
        card.pivotY = h / 2f
        card.scaleX = scale
        card.scaleY = scale
        // Keep the card's near edge tucked 28dp under the drawer's edge.
        val scaledInset = w / 2f * (1f - scale)
        card.translationX = dir * (p * (drawerWidth - dp(28)) - scaledInset)
        card.rotationY = -6f * p * dir
        card.elevation = maxCardElevation * p
        cardCorner = cardRadius * p / scale
        card.clipToOutline = true
        card.invalidateOutline()
        overlay.update(p, cardCorner, scale)
    }

    private fun settle(open: Boolean, animate: Boolean) {
        commit(open)
        animator?.cancel()
        val target = if (open) 1f else 0f
        if (!animate || !isAttachedToWindow || !isLaidOut) {
            setSlide(target)
            return
        }
        if (slide == target) return
        animator = ValueAnimator.ofFloat(slide, target).apply {
            duration = (SETTLE_MS * abs(target - slide)).toLong().coerceAtLeast(150L)
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { setSlide(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) animator = null
                }
            })
            start()
        }
    }

    private fun commit(open: Boolean) {
        if (open == committedOpen) return
        committedOpen = open
        card.importantForAccessibility =
            if (open) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            announceForAccessibility(context.getString(if (open) R.string.nav_drawer_opened else R.string.nav_drawer_closed))
        }
        onDrawerStateChanged?.invoke(open)
    }

    // -------------------------------------------------------------- touches

    /** End edge of the visible panel, in this view's coordinates. */
    private fun isOverPanel(x: Float): Boolean {
        val visible = drawerWidth * slide
        return if (isRtl) x >= width - visible else x <= visible
    }

    private fun isInEdge(x: Float) = if (isRtl) x >= width - edgeSize else x <= edgeSize

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (drawerView == null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetTouch()
                activePointerId = ev.getPointerId(0)
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                trackVelocity(ev)
                if (animator != null && slide > 0f && gesturesEnabled) {
                    // Catch the drawer mid-flight.
                    downOnCard = !isOverPanel(ev.x)
                    startDrag()
                    return true
                }
                if (slide > 0f) {
                    if (!isOverPanel(ev.x)) {
                        downOnCard = true
                        tracking = true
                        return true
                    }
                    tracking = gesturesEnabled
                } else if (gesturesEnabled && isInEdge(ev.x)) {
                    tracking = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                trackVelocity(ev)
                val index = ev.findPointerIndex(activePointerId)
                if (index < 0) return false
                if (shouldStartDrag(ev.getX(index), ev.getY(index))) {
                    lastX = ev.getX(index)
                    startDrag()
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> onSecondaryPointerUp(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> resetTouch()
        }
        return false
    }

    /** Decides whether a tracked gesture became a horizontal drawer drag. */
    private fun shouldStartDrag(x: Float, y: Float): Boolean {
        val dx = (x - downX) * dir
        val dy = y - downY
        if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
            if (!downOnCard) tracking = false // vertical: leave it to the content
            return false
        }
        if (!gesturesEnabled) return false
        val wanted = if (slide > 0f) dx < -touchSlop else dx > touchSlop
        return wanted && abs(dx) > abs(dy)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (drawerView == null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Reached when we intercepted the DOWN, or when no child took an
                // edge-strip DOWN (keep the gesture so it can open the drawer).
                return tracking || dragging
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking && !dragging) return false
                trackVelocity(ev)
                val index = ev.findPointerIndex(activePointerId)
                if (index < 0) return true
                val x = ev.getX(index)
                if (!dragging) {
                    if (shouldStartDrag(x, ev.getY(index))) {
                        lastX = x
                        startDrag()
                    } else {
                        if (downOnCard && (abs(x - downX) > touchSlop || abs(ev.getY(index) - downY) > touchSlop)) {
                            // Not a tap any more; ignore until release.
                            downOnCard = false
                        }
                        return tracking || downOnCard || slide > 0f
                    }
                }
                if (dragging && drawerWidth > 0) {
                    val delta = (x - lastX) * dir
                    lastX = x
                    setSlide(slide + delta / drawerWidth)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                activePointerId = ev.getPointerId(i)
                lastX = ev.getX(i)
                trackVelocity(ev)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                onSecondaryPointerUp(ev)
                return true
            }
            MotionEvent.ACTION_UP -> {
                trackVelocity(ev)
                if (dragging) {
                    val vt = velocityTracker
                    var vx = 0f
                    if (vt != null) {
                        vt.computeCurrentVelocity(1000, maxFlingVelocity)
                        vx = vt.getXVelocity(activePointerId) * dir
                    }
                    val open = when {
                        vx > minFlingVelocity -> true
                        vx < -minFlingVelocity -> false
                        else -> slide > 0.5f
                    }
                    settle(open, animate = true)
                } else if (downOnCard && slide > 0f) {
                    playSoundEffect(android.view.SoundEffectConstants.CLICK)
                    close()
                }
                resetTouch()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) settle(slide > 0.5f, animate = true)
                resetTouch()
                return true
            }
        }
        return tracking || dragging
    }

    private fun startDrag() {
        animator?.cancel()
        animator = null
        tracking = true
        dragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
        if (slide == 0f) {
            // Make the panel visible right away so the first frame is not empty.
            panel.visibility = VISIBLE
        }
    }

    private fun onSecondaryPointerUp(ev: MotionEvent) {
        val i = ev.actionIndex
        if (ev.getPointerId(i) == activePointerId) {
            val newIndex = if (i == 0) 1 else 0
            if (newIndex < ev.pointerCount) {
                activePointerId = ev.getPointerId(newIndex)
                lastX = ev.getX(newIndex)
                velocityTracker?.clear()
            }
        }
    }

    private fun trackVelocity(ev: MotionEvent) {
        val vt = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        vt.addMovement(ev)
    }

    private fun resetTouch() {
        tracking = false
        dragging = false
        downOnCard = false
        activePointerId = INVALID_POINTER
        velocityTracker?.recycle()
        velocityTracker = null
    }

    // ----------------------------------------------------- lifecycle / state

    override fun onDetachedFromWindow() {
        animator?.end()
        resetTouch()
        super.onDetachedFromWindow()
    }

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable(KEY_SUPER, super.onSaveInstanceState())
        putBoolean(KEY_OPEN, committedOpen)
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            @Suppress("DEPRECATION")
            super.onRestoreInstanceState(state.getParcelable(KEY_SUPER))
            if (state.getBoolean(KEY_OPEN)) open(animate = false)
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    // -------------------------------------------------------------- drawing

    /** Scrim + neon rim drawn over the content card (clipped to its rounded outline). */
    private inner class CardOverlay : Drawable() {
        private var fraction = 0f
        private var radius = 0f
        private val scrim = Paint().apply { color = Neon.BG0 }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()

        fun update(fraction: Float, radius: Float, scale: Float) {
            this.fraction = fraction
            this.radius = radius
            rim.strokeWidth = dp(1.4f) / scale
            invalidateSelf()
        }

        override fun onBoundsChange(bounds: Rect) {
            rim.shader = LinearGradient(
                bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
                intArrayOf(Neon.CYAN, Neon.BLUE, Neon.VIOLET, Neon.MAGENTA), null, Shader.TileMode.CLAMP,
            )
        }

        override fun draw(canvas: Canvas) {
            val f = fraction
            if (f <= 0f) return
            scrim.alpha = (255 * 0.5f * f).toInt()
            canvas.drawRect(bounds, scrim)
            rim.alpha = (255 * 0.75f * f).toInt()
            val h = rim.strokeWidth / 2f
            rect.set(bounds.left + h, bounds.top + h, bounds.right - h, bounds.bottom - h)
            canvas.drawRoundRect(rect, max(0f, radius - h), max(0f, radius - h), rim)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** The sliding drawer sheet: opaque glass with rounded end corners and a neon edge. */
    private class Panel(context: Context) : FrameLayout(context) {
        private val radius = context.dp(28)
        private val glass = PanelDrawable(context, radius)

        var mirrored = false
            set(value) {
                if (field == value) return
                field = value
                glass.mirrored = value
                invalidateOutline()
            }

        init {
            background = glass
            elevation = context.dp(24)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val r = radius.toInt()
                    if (mirrored) {
                        outline.setRoundRect(0, 0, view.width + r, view.height, radius)
                    } else {
                        outline.setRoundRect(-r, 0, view.width, view.height, radius)
                    }
                }
            }
            clipToOutline = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineAmbientShadowColor = Neon.BLUE
                outlineSpotShadowColor = Neon.VIOLET
            }
        }

        // Consume touches on empty parts of the sheet so they never reach the card below.
        override fun onTouchEvent(event: MotionEvent): Boolean = true
    }

    private class PanelDrawable(context: Context, private val radius: Float) : Drawable() {
        private val density = context.resources.displayMetrics.density
        var mirrored = false
            set(value) {
                field = value
                onBoundsChange(bounds)
                invalidateSelf()
            }
        private val fill = Paint()
        private val sheen = Paint()
        private val lightA = Paint()
        private val lightB = Paint()
        private val edgeGlow = Paint()
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f * density }
        private val rimGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f * density }
        private val edgePath = Path()

        override fun onBoundsChange(bounds: Rect) {
            val l = bounds.left.toFloat()
            val t = bounds.top.toFloat()
            val r = bounds.right.toFloat()
            val b = bounds.bottom.toFloat()
            val w = (r - l).coerceAtLeast(1f)
            val h = (b - t).coerceAtLeast(1f)
            fill.shader = LinearGradient(
                0f, t, 0f, b,
                intArrayOf(Neon.blend(Neon.SURFACE_TOP, Neon.BG1, 0.3f), Neon.BG1, Neon.blend(Neon.BG1, Neon.BG0, 0.5f)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
            sheen.shader = LinearGradient(
                0f, t, 0f, t + h * 0.35f,
                Neon.withAlpha(0xFFFFFFFF.toInt(), 0.05f), Neon.withAlpha(0xFFFFFFFF.toInt(), 0f), Shader.TileMode.CLAMP,
            )
            val nearX = if (mirrored) r else l
            val farX = if (mirrored) l else r
            lightA.shader = RadialGradient(
                nearX + (farX - nearX) * 0.15f, t + h * 0.05f, w * 0.95f,
                Neon.withAlpha(Neon.VIOLET, 0.20f), Neon.withAlpha(Neon.VIOLET, 0f), Shader.TileMode.CLAMP,
            )
            lightB.shader = RadialGradient(
                nearX, b, w * 0.9f,
                Neon.withAlpha(Neon.CYAN, 0.08f), Neon.withAlpha(Neon.CYAN, 0f), Shader.TileMode.CLAMP,
            )
            val glowW = 40f * density
            edgeGlow.shader = LinearGradient(
                farX - (if (mirrored) -glowW else glowW), 0f, farX, 0f,
                Neon.withAlpha(Neon.CYAN, 0f), Neon.withAlpha(Neon.CYAN, 0.09f), Shader.TileMode.CLAMP,
            )
            val rimShader = LinearGradient(
                0f, t, 0f, b,
                intArrayOf(Neon.CYAN, Neon.BLUE, Neon.VIOLET, Neon.MAGENTA),
                floatArrayOf(0f, 0.35f, 0.75f, 1f), Shader.TileMode.CLAMP,
            )
            rim.shader = rimShader
            rimGlow.shader = rimShader

            // The visible (end) edge: top corner arc, straight edge, bottom corner arc.
            edgePath.reset()
            val inset = rim.strokeWidth / 2f
            val rr = radius
            if (!mirrored) {
                val x = r - inset
                edgePath.moveTo(x - rr, t + inset)
                edgePath.arcTo(RectF(x - 2 * rr, t + inset, x, t + inset + 2 * rr), -90f, 90f, false)
                edgePath.lineTo(x, b - inset - rr)
                edgePath.arcTo(RectF(x - 2 * rr, b - inset - 2 * rr, x, b - inset), 0f, 90f, false)
            } else {
                val x = l + inset
                edgePath.moveTo(x + rr, t + inset)
                edgePath.arcTo(RectF(x, t + inset, x + 2 * rr, t + inset + 2 * rr), -90f, -90f, false)
                edgePath.lineTo(x, b - inset - rr)
                edgePath.arcTo(RectF(x, b - inset - 2 * rr, x + 2 * rr, b - inset), 180f, -90f, false)
            }
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawRect(b, fill)
            canvas.drawRect(b, lightA)
            canvas.drawRect(b, lightB)
            canvas.drawRect(b, sheen)
            canvas.drawRect(b, edgeGlow)
            rimGlow.alpha = (255 * 0.16f).toInt()
            canvas.drawPath(edgePath, rimGlow)
            rim.alpha = (255 * 0.7f).toInt()
            canvas.drawPath(edgePath, rim)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val SETTLE_MS = 260f
        const val INVALID_POINTER = -1
        const val KEY_SUPER = "nav_drawer_super"
        const val KEY_OPEN = "nav_drawer_open"
    }
}
