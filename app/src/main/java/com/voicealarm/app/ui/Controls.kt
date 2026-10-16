package com.voicealarm.app.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.voicealarm.app.R
import kotlin.math.roundToInt

// ============================================================== Toggle3D

/** Line switch: hairline capsule with a small disc; on = bright line, white disc on the right. */
class Toggle3D(context: Context) : View(context) {
    var onCheckedChange: ((Boolean) -> Unit)? = null
    var isChecked = false
        private set
    private var pos = 0f
    private var anim: ValueAnimator? = null
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val on = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { setChecked(!isChecked); onCheckedChange?.invoke(isChecked) }
    }

    fun setChecked(checked: Boolean, animate: Boolean = true) {
        if (checked == isChecked && anim == null) return
        isChecked = checked
        anim?.cancel()
        if (!animate || !isAttachedToWindow) {
            anim = null
            pos = if (checked) 1f else 0f
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(pos, if (checked) 1f else 0f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { anim = null }
            })
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dpi(60), dpi(48))

    override fun onDraw(canvas: Canvas) {
        val tw = dp(44); val th = dp(24)
        val left = (width - tw) / 2; val top = (height - th) / 2
        val sw = dp(1.2f)
        r.set(left + sw / 2, top + sw / 2, left + tw - sw / 2, top + th - sw / 2)
        val rad = r.height() / 2
        track.style = Paint.Style.STROKE
        track.strokeWidth = sw
        track.color = Neon.blend(0x47FFFFFF, Neon.withAlpha(Neon.TEXT1, 0.9f), pos)
        canvas.drawRoundRect(r, rad, rad, track)
        if (pos > 0f) {
            on.shader = null
            on.color = Neon.withAlpha(Color.WHITE, 0.08f * pos)
            canvas.drawRoundRect(r, rad, rad, on)
        }
        val kr = th / 2 - dp(5)
        val kx = left + dp(5) + kr + (tw - 2 * dp(5) - 2 * kr) * pos
        knob.shader = null
        knob.color = Neon.blend(Neon.TEXT3, Neon.TEXT1, pos)
        canvas.drawCircle(kx, top + th / 2, kr, knob)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Switch::class.java.name
        info.isCheckable = true
        info.isChecked = isChecked
    }
}

// ============================================================== NeonSlider

/** Slider: hairline track, white progress line, thin ring thumb. */
class NeonSlider(context: Context) : View(context) {
    var min = 0f
    var max = 1f
    var step = 0f
    var onChange: ((Float, Boolean) -> Unit)? = null
    var onChangeEnd: ((Float) -> Unit)? = null
    var value = 0f
        set(v) {
            field = snap(v)
            invalidate()
        }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var dragging = false

    init {
        isFocusable = true
        isClickable = true
    }

    private fun snap(v: Float): Float {
        var x = v.coerceIn(min, max)
        if (step > 0) x = min + ((x - min) / step).roundToInt() * step
        return x.coerceIn(min, max)
    }

    private val pad get() = dp(14)
    private val frac get() = if (max > min) (value - min) / (max - min) else 0f

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(resolveSize(dpi(280), w), dpi(48))

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val th = dp(1.5f)
        val x = pad + (width - 2 * pad) * frac
        track.color = 0x33FFFFFF
        canvas.drawRect(pad, cy - th / 2, width - pad, cy + th / 2, track)
        fill.shader = null
        fill.color = Neon.TEXT1
        canvas.drawRect(pad, cy - th / 2, x, cy + th / 2, fill)
        val kr = dp(if (dragging) 10 else 8)
        halo.shader = null
        halo.color = Neon.BG0
        canvas.drawCircle(x, cy, kr, halo)
        thumb.shader = null
        thumb.style = Paint.Style.STROKE
        thumb.strokeWidth = dp(1.4f)
        thumb.color = Neon.TEXT1
        canvas.drawCircle(x, cy, kr - thumb.strokeWidth / 2, thumb)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                val f = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
                val nv = snap(min + f * (max - min))
                if (nv != value) {
                    value = nv
                    onChange?.invoke(value, true)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                onChangeEnd?.invoke(value)
            }
        }
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.SeekBar::class.java.name
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, min, max, value)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        val s = if (step > 0) step else (max - min) / 20
        val nv = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> value + s
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> value - s
            else -> return super.performAccessibilityAction(action, args)
        }
        value = nv
        onChange?.invoke(value, true)
        onChangeEnd?.invoke(value)
        return true
    }
}

// ============================================================== Chips + FlowLayout

/** Outlined pill chip; checked = bright white outline and text. */
class NeonChip(context: Context, text: CharSequence, iconRes: Int? = null, val checkable: Boolean = true) : LinearLayout(context) {
    var onToggle: ((Boolean) -> Unit)? = null
    var isOn = false
        private set
    private val textView = context.label(text, R.style.Text_Caption, Neon.TEXT2, 14f)
    private val iconView = iconRes?.let { context.icon(it, Neon.TEXT2, 18) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = dpi(36)
        isClickable = true
        isFocusable = true
        iconView?.let { addView(it, LayoutParams(dpi(18), dpi(18)).apply { marginEnd = dpi(6) }) }
        addView(textView)
        render()
        setOnClickListener {
            if (checkable) setOn(!isOn)
            onToggle?.invoke(isOn)
        }
    }

    fun setOn(on: Boolean) {
        if (on == isOn) return
        isOn = on
        render()
    }

    private fun render() {
        setTag(R.id.tag_depth_insets, null)
        setPadding(dpi(14), dpi(6), dpi(14), dpi(6))
        depthBackground(GlassCardDrawable(context, radius = -1f, elevation = dp(5), pressable = true, topColor = 0,
            outline = if (isOn) Neon.AQUA else null))
        val c = if (isOn) Neon.TEXT1 else Neon.TEXT2
        textView.setTextColor(c)
        iconView?.imageTintList = android.content.res.ColorStateList.valueOf(if (isOn) Neon.TEXT1 else Neon.TEXT2)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (checkable) {
            info.className = android.widget.CheckBox::class.java.name
            info.isCheckable = true
            info.isChecked = isOn
        } else {
            info.className = android.widget.Button::class.java.name
        }
    }
}

/** Wraps children into rows. */
class FlowLayout(context: Context, private val hGap: Int = context.dpi(4), private val vGap: Int = context.dpi(2)) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            x += c.measuredWidth + hGap
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(resolveSize(maxW + paddingLeft + paddingRight, widthMeasureSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + hGap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

// ============================================================== Buttons

/** Outlined pill button with optional leading line icon and a loading state. */
class NeonButton(context: Context, text: CharSequence, iconRes: Int? = null, val kind: Kind = Kind.PRIMARY) : LinearLayout(context) {
    enum class Kind { PRIMARY, DANGER, SECONDARY, GHOST }

    private val textView = context.label(text, R.style.Text_Button)
    private val iconView = iconRes?.let { context.icon(it, 0, 22) }
    private var original: CharSequence = text
    private var pulse: ValueAnimator? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        val fg = when (kind) {
            Kind.PRIMARY -> Neon.TEXT1
            Kind.DANGER -> Neon.RED
            Kind.SECONDARY -> Neon.TEXT2
            Kind.GHOST -> Neon.TEXT1
        }
        textView.setTextColor(fg)
        iconView?.let {
            it.imageTintList = android.content.res.ColorStateList.valueOf(fg)
            addView(it, LayoutParams(dpi(22), dpi(22)).apply { marginEnd = dpi(10) })
        }
        addView(textView)
        setPadding(dpi(24), 0, dpi(24), 0)
        when (kind) {
            Kind.PRIMARY -> { minimumHeight = dpi(56); depthBackground(NeonButtonDrawable(context, Neon.AQUA)) }
            Kind.DANGER -> { minimumHeight = dpi(56); depthBackground(NeonButtonDrawable(context, Neon.FLAME)) }
            Kind.SECONDARY -> { minimumHeight = dpi(52); depthBackground(NeonButtonDrawable(context, Neon.ELECTRIC)) }
            Kind.GHOST -> { minimumHeight = dpi(48) }
        }
    }

    fun setText(text: CharSequence) {
        original = text
        textView.text = text
    }

    fun setLoading(loading: Boolean, loadingText: CharSequence? = null) {
        isEnabled = !loading
        pulse?.cancel()
        if (loading) {
            textView.text = loadingText ?: original
            pulse = ValueAnimator.ofFloat(1f, 0.45f).apply {
                duration = 600
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { textView.alpha = it.animatedValue as Float; iconView?.alpha = it.animatedValue as Float }
                start()
            }
        } else {
            textView.text = original
            textView.alpha = 1f
            iconView?.alpha = 1f
        }
    }

    override fun onDetachedFromWindow() {
        pulse?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Button::class.java.name
    }
}

/** Circular icon button: a thin ring around a line icon (white ring when [colors] given). */
class IconButton3D(context: Context, iconRes: Int, description: CharSequence, colors: IntArray? = null, sizeDp: Int = 44) : FrameLayout(context) {
    private val iconView = context.icon(iconRes, if (colors?.contains(Neon.RED) == true) Neon.RED else Neon.TEXT1, 22)

    init {
        isClickable = true
        isFocusable = true
        contentDescription = description
        if (colors == null) {
            depthBackground(SphereDrawable(context, 0x40FFFFFF, glow = false, shadow = dp(6)))
        } else {
            depthBackground(SphereDrawable(context, colors.first(), glow = true, shadow = dp(6)))
        }
        addView(iconView, LayoutParams(dpi(22), dpi(22), Gravity.CENTER))
        minimumWidth = dpi(sizeDp)
        minimumHeight = dpi(sizeDp)
    }

    fun setIcon(res: Int) = iconView.setImageResource(res)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = minimumWidth + paddingLeft + paddingRight
        val h = minimumHeight + paddingTop + paddingBottom
        super.onMeasure(MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Button::class.java.name
    }
}

// ============================================================== SegmentedControl

/** Hairline capsule with segments; the selected one is outlined in white. */
class SegmentedControl(context: Context) : LinearLayout(context) {
    var onSelect: ((Int) -> Unit)? = null
    var selectedIndex = 0
        set(v) {
            field = v
            render()
        }
    private val segments = mutableListOf<LinearLayout>()

    init {
        orientation = HORIZONTAL
        setPadding(dpi(4), dpi(4), dpi(4), dpi(4))
        depthBackground(SunkenDrawable(context, radius = dp(22)))
    }

    fun setOptions(labels: List<String>, icons: List<Int?>? = null) {
        removeAllViews()
        segments.clear()
        labels.forEachIndexed { i, text ->
            val seg = context.hbox(Gravity.CENTER).apply {
                minimumHeight = dpi(44)
                isClickable = true
                isFocusable = true
                icons?.getOrNull(i)?.let { addView(context.icon(it, Neon.TEXT2, 18), LayoutParams(dpi(18), dpi(18)).apply { marginEnd = dpi(8) }) }
                addView(context.label(text, R.style.Text_Caption, Neon.TEXT2, 14f))
                setOnClickListener {
                    if (selectedIndex != i) {
                        selectedIndex = i
                        onSelect?.invoke(i)
                    }
                }
            }
            segments += seg
            addView(seg, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        render()
    }

    private fun render() {
        segments.forEachIndexed { i, seg ->
            val on = i == selectedIndex
            if (on) {
                seg.setTag(R.id.tag_depth_insets, null)
                seg.setPadding(0, 0, 0, 0)
                seg.depthBackground(GlassCardDrawable(context, radius = -1f, elevation = dp(5), outline = Neon.AQUA, topColor = 0x0DFFFFFF))
            } else {
                seg.background = null
                seg.setTag(R.id.tag_depth_insets, null)
                seg.setPadding(dpi(5), dpi(3), dpi(5), dpi(7))
            }
            for (k in 0 until seg.childCount) {
                when (val c = seg.getChildAt(k)) {
                    is TextView -> c.setTextColor(if (on) Neon.TEXT1 else Neon.TEXT2)
                    is ImageView -> c.imageTintList = android.content.res.ColorStateList.valueOf(if (on) Neon.TEXT1 else Neon.TEXT2)
                }
            }
            seg.isSelected = on
        }
    }
}

// ============================================================== ListRow

/** Settings / menu row: ringed line icon, title, subtitle, trailing view. */
fun Context.listRow(
    iconRes: Int?, accent: Int, title: CharSequence, subtitle: CharSequence? = null,
    trailing: View? = null, onClick: (() -> Unit)? = null,
): LinearLayout = hbox().apply {
    minimumHeight = dpi(64)
    setPadding(0, dpi(10), 0, dpi(10))
    iconRes?.let { addView(iconTile(it, accent), LinearLayout.LayoutParams(dpi(40), dpi(40)).apply { marginEnd = dpi(14) }) }
    val texts = vbox()
    texts.addView(label(title, R.style.Text_Title, sizeSp = 15f))
    subtitle?.let { texts.addView(label(it, R.style.Text_Body, sizeSp = 13f)) }
    addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    trailing?.let { addView(it) }
    if (onClick != null) {
        isClickable = true
        isFocusable = true
        background = context.getDrawable(R.drawable.ctl_row_ripple)
        setOnClickListener { onClick() }
    }
}

fun Context.chevron() = icon(R.drawable.ic_chevron_right, Neon.TEXT3, 22)

// ============================================================== Bottom sheet

/** Bottom sheet: near-black panel with a hairline edge, grab handle, title, content and actions. */
class NeonSheet(private val activity: Activity) {
    private val dialog = Dialog(activity, R.style.NeonBottomSheet)
    private val root = FrameLayout(activity)
    private val panel = activity.vbox()
    private val titleView = activity.label(null, R.style.Text_Title, sizeSp = 20f)
    private val subtitleView = activity.label(null, R.style.Text_Body)
    private val content = FrameLayout(activity)
    private val actions = activity.vbox()
    var onDismiss: (() -> Unit)? = null
    private var closing = false

    var title: CharSequence?
        get() = titleView.text
        set(v) { titleView.text = v; titleView.visibility = if (v.isNullOrEmpty()) View.GONE else View.VISIBLE }
    var subtitle: CharSequence?
        get() = subtitleView.text
        set(v) { subtitleView.text = v; subtitleView.visibility = if (v.isNullOrEmpty()) View.GONE else View.VISIBLE }

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        root.setOnClickListener { dismiss() }
        panel.isClickable = true
        panel.setPadding(activity.dpi(20), activity.dpi(10), activity.dpi(20), activity.dpi(28) + activity.dpi(28))
        panel.depthBackground(GlassCardDrawable(activity, radius = activity.dp(28), elevation = activity.dp(16), topColor = Neon.BG1))
        val handle = View(activity).apply {
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = activity.dp(2); setColor(0x40FFFFFF) }
        }
        panel.addView(handle, LinearLayout.LayoutParams(activity.dpi(36), activity.dpi(3)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = activity.dpi(14) })
        panel.addView(titleView)
        panel.addView(subtitleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpi(4) })
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val max = (resources.displayMetrics.heightPixels * 0.62f).toInt()
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
            }
        }
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(content)
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpi(12) })
        panel.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpi(8) })
        // Bottom corners hang below the screen edge so the sheet looks top-rounded.
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = -activity.dpi(28) })
        dialog.setContentView(root)
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        dialog.setOnCancelListener { onDismiss?.invoke() }
        title = null
        subtitle = null
    }

    fun setContent(view: View) {
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun addAction(text: CharSequence, kind: NeonButton.Kind = NeonButton.Kind.PRIMARY, iconRes: Int? = null, onClick: () -> Unit): NeonButton {
        val b = NeonButton(activity, text, iconRes, kind)
        b.setOnClickListener { onClick() }
        actions.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dpi(6) })
        return b
    }

    fun show() {
        if (activity.isFinishing) return
        dialog.show()
        panel.translationY = activity.resources.displayMetrics.heightPixels.toFloat()
        panel.post {
            panel.translationY = panel.height.toFloat()
            panel.animate().translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    fun dismiss() {
        if (closing || !dialog.isShowing) return
        closing = true
        panel.animate().translationY(panel.height.toFloat()).setDuration(200).setInterpolator(AccelerateInterpolator())
            .withEndAction {
                if (dialog.isShowing) dialog.dismiss()
                onDismiss?.invoke()
            }.start()
    }

    data class SheetAction(val iconRes: Int, val label: CharSequence, val destructive: Boolean = false, val onClick: () -> Unit)

    companion object {
        /** Quick action menu (rows with icons). */
        fun actions(activity: Activity, title: CharSequence?, items: List<SheetAction>): NeonSheet {
            val sheet = NeonSheet(activity)
            sheet.title = title
            val list = activity.vbox()
            items.forEach { item ->
                val color = if (item.destructive) Neon.RED else Neon.TEXT1
                val row = activity.listRow(item.iconRes, color, item.label) {
                    sheet.dismiss()
                    item.onClick()
                }
                if (item.destructive) (row.getChildAt(1) as? LinearLayout)?.let { (it.getChildAt(0) as? TextView)?.setTextColor(Neon.RED) }
                list.addView(row)
            }
            sheet.setContent(list)
            sheet.show()
            return sheet
        }
    }
}

// ============================================================== Snack

/** Hairline snackbar that slides up inside [parent]. */
object Snack {
    private var current: View? = null

    fun show(parent: FrameLayout, text: CharSequence, actionLabel: CharSequence? = null, action: (() -> Unit)? = null, bottomMarginPx: Int = 0, durationMs: Long = 3500) {
        val ctx = parent.context
        current?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val bar = ctx.hbox().apply {
            setPadding(ctx.dpi(18), ctx.dpi(8), ctx.dpi(8), ctx.dpi(8))
            minimumHeight = ctx.dpi(56)
            depthBackground(GlassCardDrawable(ctx, radius = ctx.dp(18), elevation = ctx.dp(12), topColor = Neon.SURFACE_HI))
            addView(ctx.label(text, R.style.Text_Body, Neon.TEXT1, 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        if (actionLabel != null && action != null) {
            val btn = ctx.label(actionLabel, R.style.Text_Button, Neon.TEXT1, 15f).apply {
                setPadding(ctx.dpi(14), ctx.dpi(12), ctx.dpi(14), ctx.dpi(12))
                isClickable = true
                isFocusable = true
                background = ctx.getDrawable(R.drawable.ctl_row_ripple)
            }
            btn.setOnClickListener { action(); hide(bar) }
            bar.addView(btn)
        }
        parent.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = ctx.dpi(12); rightMargin = ctx.dpi(12); bottomMargin = bottomMarginPx
        })
        current = bar
        bar.alpha = 0f
        bar.translationY = ctx.dp(40)
        bar.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        bar.announceForAccessibility(text)
        bar.postDelayed({ hide(bar) }, durationMs)
    }

    private fun hide(bar: View) {
        if (bar.parent == null) return
        bar.animate().alpha(0f).translationY(bar.context.dp(40)).setDuration(180).withEndAction {
            (bar.parent as? ViewGroup)?.removeView(bar)
            if (current === bar) current = null
        }.start()
    }
}
