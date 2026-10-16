package com.voicealarm.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.voicealarm.app.R

/**
 * Production top app bar, 64dp tall.
 *
 *  [ (≡) ]  Title                       [ (a) ] [ (b) ]
 *           Subtitle
 *
 * - Leading glass circle button (menu / back) with a lit rim; swapping its icon
 *   spins the old icon out and the new one in.
 * - Title ([Text.Title] at 20sp) + optional subtitle ([Text.Caption], TEXT2),
 *   both single-line/ellipsized; changes crossfade, and the title block glides
 *   when the subtitle appears or disappears.
 * - Trailing glass circle action buttons.
 * - Transparent at rest; [setElevated] fades in a glass backdrop with a neon
 *   hairline (use while the page content is scrolled, or call [trackScroll]).
 *
 * Mirrors itself for right-to-left layouts.
 */
class AppBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    private val barHeight = dpi(64)
    /** Distance from the screen edge to a glass button body. */
    private val buttonEdge = dpi(16)
    /** Title inset when there is no navigation button (screen gutter). */
    private val gutter = dpi(20)
    private val titleGap = dpi(14)
    private val actionGap = dpi(8)

    private val navButton = NavIconButton(context).apply { visibility = GONE }
    private val actions = ArrayList<NavIconButton>()

    private val titleView = TextView(context).apply {
        setTextAppearance(R.style.Text_Title)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        includeFontPadding = false
        maxLines = 1
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
    }
    private val subtitleView = TextView(context).apply {
        setTextAppearance(R.style.Text_Caption)
        setTextColor(Neon.TEXT2)
        includeFontPadding = false
        maxLines = 1
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        visibility = GONE
    }
    private val titleBlock = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        addView(titleView, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(
            subtitleView,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(3) },
        )
    }

    private var titleTarget: CharSequence = ""
    private var subtitleTarget: CharSequence? = null
    private var lastBlockTop = Int.MIN_VALUE

    // Elevated (scrolled) glass backdrop.
    private var elevated = false
    private var elevation01 = 0f
    private var elevationAnimator: ValueAnimator? = null
    private val backdropPaint = Paint()
    private val sheenPaint = Paint()
    private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hairlineHeight = maxOf(1f, dp(1))

    init {
        setWillNotDraw(false)
        clipChildren = false
        addView(navButton)
        addView(titleBlock)
    }

    /** The title shown in the bar. Changing it crossfades the text. */
    var title: CharSequence
        get() = titleTarget
        set(value) {
            if (TextUtils.equals(value, titleTarget) && TextUtils.equals(titleView.text, value)) return
            titleTarget = value
            crossfade(titleView) { titleView.text = titleTarget }
        }

    /** Optional second line under the title (null/empty hides it). Animated. */
    var subtitle: CharSequence?
        get() = subtitleTarget
        set(value) {
            val next = value?.takeIf { it.isNotEmpty() }
            if (TextUtils.equals(next, subtitleTarget)) return
            subtitleTarget = next
            val v = subtitleView
            if (!canAnimateNow()) {
                v.animate().cancel()
                v.text = next
                v.alpha = 1f
                v.translationY = 0f
                v.visibility = if (next == null) GONE else VISIBLE
                return
            }
            val shift = dp(4)
            when {
                next == null -> {
                    v.animate().cancel()
                    v.animate().alpha(0f).translationY(-shift).setStartDelay(0).setDuration(120)
                        .setInterpolator(AccelerateInterpolator())
                        .withEndAction {
                            if (subtitleTarget == null) {
                                v.visibility = GONE
                                v.text = null
                                v.translationY = 0f
                            }
                        }.start()
                }
                v.visibility != VISIBLE -> {
                    v.animate().cancel()
                    v.text = next
                    v.visibility = VISIBLE
                    v.alpha = 0f
                    v.translationY = shift
                    v.animate().alpha(1f).translationY(0f).setStartDelay(60).setDuration(220)
                        .setInterpolator(DecelerateInterpolator()).start()
                }
                else -> crossfade(v) { v.text = subtitleTarget }
            }
        }

    /** The leading navigation button (for tests, tooltips or extra a11y wiring). */
    val navigationView: View get() = navButton

    /**
     * Shows the leading glass button. Calling it again with another icon
     * (menu ⇄ back) animates the swap.
     */
    fun setNavigation(iconRes: Int, contentDescription: CharSequence, onClick: () -> Unit) {
        val wasVisible = navButton.visibility == VISIBLE
        navButton.setIcon(iconRes, animate = wasVisible && canAnimateNow())
        navButton.contentDescription = contentDescription
        navButton.tooltipText = contentDescription
        navButton.setOnClickListener { onClick() }
        if (!wasVisible) {
            navButton.visibility = VISIBLE
            requestLayout()
        }
    }

    /** Hides the leading button; the title moves to the screen gutter. */
    fun clearNavigation() {
        if (navButton.visibility == GONE) return
        navButton.visibility = GONE
        navButton.setOnClickListener(null)
        requestLayout()
    }

    /**
     * Adds a trailing glass action button (laid out from the end edge inwards,
     * in call order). Returns the button view so the caller can hide it or
     * anchor a popup to it.
     */
    fun addAction(iconRes: Int, contentDescription: CharSequence, onClick: () -> Unit): View {
        val button = NavIconButton(context)
        button.setIcon(iconRes, animate = false)
        button.contentDescription = contentDescription
        button.tooltipText = contentDescription
        button.setOnClickListener { onClick() }
        actions.add(button)
        addView(button)
        return button
    }

    fun clearActions() {
        actions.forEach { removeView(it) }
        actions.clear()
    }

    /**
     * Shows (true) or hides the glass backdrop + bottom hairline. Use while the
     * content under the bar is scrolled away from its top.
     */
    fun setElevated(elevated: Boolean, animate: Boolean = true) {
        val target = if (elevated) 1f else 0f
        if (this.elevated == elevated && (elevationAnimator != null || elevation01 == target)) return
        this.elevated = elevated
        elevationAnimator?.cancel()
        if (!animate || !canAnimateNow()) {
            elevation01 = target
            invalidate()
            return
        }
        elevationAnimator = ValueAnimator.ofFloat(elevation01, target).apply {
            duration = 200
            interpolator = if (elevated) DecelerateInterpolator() else AccelerateInterpolator()
            addUpdateListener {
                elevation01 = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (elevationAnimator === animation) elevationAnimator = null
                }
            })
            start()
        }
    }

    val isElevated: Boolean get() = elevated

    /**
     * Convenience: elevates the bar whenever [scrollable] (a ScrollView,
     * ListView, …) is scrolled away from its top. Replaces the view's
     * OnScrollChangeListener.
     */
    fun trackScroll(scrollable: View) {
        scrollable.setOnScrollChangeListener { v, _, _, _, _ -> setElevated(v.canScrollVertically(-1)) }
        setElevated(scrollable.canScrollVertically(-1), animate = false)
    }

    // ---------------------------------------------------------------- layout

    private val isRtl get() = layoutDirection == LAYOUT_DIRECTION_RTL

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> resources.displayMetrics.widthPixels
            else -> MeasureSpec.getSize(widthMeasureSpec)
        }
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            barHeight
        }
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        if (navButton.visibility != GONE) navButton.measure(unspecified, unspecified)
        actions.forEach { if (it.visibility != GONE) it.measure(unspecified, unspecified) }

        val (start, end) = titleBounds(width)
        titleBlock.measure(
            MeasureSpec.makeMeasureSpec((end - start).coerceAtLeast(0), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST),
        )
        setMeasuredDimension(width, height)
    }

    /** Horizontal range (in LTR coordinates) available to the title block. */
    private fun titleBounds(width: Int): Pair<Int, Int> {
        val start = if (navButton.visibility != GONE) buttonEdge + navButton.bodySize + titleGap else gutter
        var end = width - gutter
        var bodyStart = width - buttonEdge + actionGap
        actions.forEach {
            if (it.visibility == GONE) return@forEach
            bodyStart -= actionGap + it.bodySize
            end = bodyStart - titleGap
        }
        return start to end.coerceAtLeast(start)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t
        val centerY = height / 2

        fun place(view: View, ltrLeft: Int, top: Int) {
            val w = view.measuredWidth
            val left = if (isRtl) width - ltrLeft - w else ltrLeft
            view.layout(left, top, left + w, top + view.measuredHeight)
        }

        if (navButton.visibility != GONE) {
            place(navButton, buttonEdge - navButton.paddingLeft, centerY - navButton.bodySize / 2 - navButton.paddingTop)
        }
        var bodyEnd = width - buttonEdge
        actions.forEach {
            if (it.visibility == GONE) return@forEach
            val bodyStart = bodyEnd - it.bodySize
            place(it, bodyStart - it.paddingLeft, centerY - it.bodySize / 2 - it.paddingTop)
            bodyEnd = bodyStart - actionGap
        }

        val (start, _) = titleBounds(width)
        val blockTop = centerY - titleBlock.measuredHeight / 2
        place(titleBlock, start, blockTop)

        // Glide the title when the subtitle appears / disappears.
        if (lastBlockTop != Int.MIN_VALUE && blockTop != lastBlockTop && canAnimateNow() && !changed) {
            titleBlock.translationY += (lastBlockTop - blockTop).toFloat()
            titleBlock.animate().translationY(0f).setStartDelay(0).setDuration(220)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        lastBlockTop = blockTop
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        backdropPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Neon.BG1, Neon.BG0, Shader.TileMode.CLAMP,
        )
        sheenPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Neon.withAlpha(0xFFFFFFFF.toInt(), 0.0f), Neon.withAlpha(0xFFFFFFFF.toInt(), 0.0f), Shader.TileMode.CLAMP,
        )
        hairlinePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(
                Neon.withAlpha(0xFFFFFFFF.toInt(), 0.12f),
                Neon.withAlpha(0xFFFFFFFF.toInt(), 0.12f),
                Neon.withAlpha(0xFFFFFFFF.toInt(), 0.12f),
                Neon.withAlpha(0xFFFFFFFF.toInt(), 0.12f),
            ),
            floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val e = elevation01
        if (e <= 0f) return
        val w = width.toFloat()
        val h = height.toFloat()
        val a = (255 * e).toInt()
        backdropPaint.alpha = (a * 0.94f).toInt()
        canvas.drawRect(0f, 0f, w, h, backdropPaint)
        sheenPaint.alpha = a
        canvas.drawRect(0f, 0f, w, h, sheenPaint)
        hairlinePaint.alpha = a
        canvas.drawRect(0f, h - hairlineHeight, w, h, hairlinePaint)
    }

    override fun onDetachedFromWindow() {
        elevationAnimator?.end()
        super.onDetachedFromWindow()
    }

    private fun canAnimateNow() = isAttachedToWindow && isLaidOut

    /** Fades [view] out, applies the change, fades it back in. Rapid calls coalesce. */
    private fun crossfade(view: TextView, apply: () -> Unit) {
        if (!canAnimateNow() || view.visibility != VISIBLE || view.text.isNullOrEmpty()) {
            view.animate().cancel()
            apply()
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        val shift = dp(6)
        view.animate().cancel()
        view.animate().alpha(0f).translationY(-shift).setStartDelay(0).setDuration(110)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                apply()
                view.translationY = shift
                view.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(200)
                    .setInterpolator(DecelerateInterpolator()).start()
            }.start()
    }
}

/**
 * Round icon button of the app chrome: a line icon inside a faint hairline
 * ring; sinks (scales down) while pressed, bright ring when focused by
 * keyboard / D-pad. The visible body is [bodySize]; the view adds
 * the glass shadow insets around it so the touch target is ≥ 48dp.
 */
internal class NavIconButton(
    context: Context,
    val bodySize: Int = context.dpi(44),
) : View(context) {
    private val glass = GlassCardDrawable(
        context,
        radius = -1f,
        topColor = 0,
        elevation = context.dp(7),
        pressable = true,
    )
    private val iconSize = context.dpi(24)
    private var icon: Drawable? = null
    private var iconRes = 0
    private var iconScale = 1f
    private var iconRotation = 0f
    private var iconAlpha = 1f
    private var swapAnimator: ValueAnimator? = null

    var iconTint: Int = Neon.TEXT1
        set(value) {
            field = value
            icon?.setTint(value)
            invalidate()
        }

    init {
        depthBackground(glass)
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(bodySize + paddingLeft + paddingRight, bodySize + paddingTop + paddingBottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pivotX = paddingLeft + bodySize / 2f
        pivotY = paddingTop + bodySize / 2f
    }

    fun setIcon(resId: Int, animate: Boolean) {
        swapAnimator?.cancel() // finishes any running swap first (see listener)
        if (resId == iconRes && icon != null) return
        iconRes = resId
        val next = context.getDrawable(resId)?.mutate()?.also { it.setTint(iconTint) }
        if (!animate || icon == null) {
            icon = next
            resetIconTransform()
            return
        }
        var swapped = false
        swapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                if (t < 0.5f) {
                    val k = t / 0.5f
                    iconAlpha = 1f - k
                    iconScale = 1f - 0.4f * k
                    iconRotation = 90f * k
                } else {
                    if (!swapped) {
                        swapped = true
                        icon = next
                    }
                    val k = (t - 0.5f) / 0.5f
                    iconAlpha = k
                    iconScale = 0.6f + 0.4f * k
                    iconRotation = -90f * (1f - k)
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    icon = next
                    resetIconTransform()
                    if (swapAnimator === animation) swapAnimator = null
                }
            })
            start()
        }
    }

    private fun resetIconTransform() {
        iconScale = 1f
        iconRotation = 0f
        iconAlpha = 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val d = icon ?: return
        val cx = paddingLeft + bodySize / 2f
        val cy = paddingTop + bodySize / 2f
        val half = iconSize / 2
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(iconRotation)
        canvas.scale(iconScale, iconScale)
        d.setBounds(-half, -half, half, half)
        d.alpha = (255 * iconAlpha).toInt().coerceIn(0, 255)
        d.draw(canvas)
        canvas.restore()
    }

    override fun setPressed(pressed: Boolean) {
        val changed = pressed != isPressed
        super.setPressed(pressed)
        if (!changed || !isAttachedToWindow) return
        animate().scaleX(if (pressed) 0.92f else 1f).scaleY(if (pressed) 0.92f else 1f)
            .setStartDelay(0)
            .setDuration(if (pressed) 90L else 200L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        glass.outline = if (gainFocus) Neon.AQUA else null
        invalidate()
    }

    override fun onDetachedFromWindow() {
        swapAnimator?.end()
        super.onDetachedFromWindow()
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
}
