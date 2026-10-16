package com.voicealarm.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.voicealarm.app.R

// ============================================================== Bottom navigation

/**
 * Floating glass dock with tabs split around a raised neon "+" sphere.
 * Selected tab: filled icon in neon cyan with a glowing dot underneath.
 */
class BottomNavBar(context: Context) : FrameLayout(context) {
    data class Tab(val id: String, val iconRes: Int, val selectedIconRes: Int, val label: CharSequence)

    var onTabSelected: ((Tab) -> Unit)? = null
    var onTabReselected: ((Tab) -> Unit)? = null
    var selectedId: String? = null
        set(v) {
            field = v
            render()
        }

    private val dock = context.hbox(Gravity.CENTER_VERTICAL)
    private val tabViews = mutableListOf<Pair<Tab, LinearLayout>>()
    private var fab: FrameLayout? = null

    /** Height this bar covers at the bottom of the screen (pages pad their content by this). */
    val occupiedHeightPx: Int get() = dpi(OCCUPIED_DP)

    init {
        clipChildren = false
        clipToPadding = false
        dock.depthBackground(GlassCardDrawable(context, radius = dp(28), elevation = dp(14), topColor = 0xF21D2354.toInt(), bottomColor = 0xF212163A.toInt(),
            outline = intArrayOf(Neon.withAlpha(Neon.CYAN, 0.35f), Neon.withAlpha(Neon.VIOLET, 0.25f))))
        addView(dock, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(66) + dock.paddingTop + dock.paddingBottom, Gravity.BOTTOM).apply {
            leftMargin = dpi(4); rightMargin = dpi(4); bottomMargin = dpi(2)
        })
    }

    fun setTabs(tabs: List<Tab>) {
        dock.removeAllViews()
        tabViews.clear()
        val half = (tabs.size + 1) / 2
        tabs.forEachIndexed { i, tab ->
            if (i == half && fab != null) dock.addView(View(context), LinearLayout.LayoutParams(dpi(76), 1))
            val v = context.vbox(Gravity.CENTER).apply {
                isClickable = true
                isFocusable = true
                background = context.getDrawable(R.drawable.ctl_row_ripple)
                addView(ImageView(context), LinearLayout.LayoutParams(dpi(24), dpi(24)))
                addView(context.label(tab.label, R.style.Text_Caption, sizeSp = 11f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(3) })
                addView(View(context).apply { background = DotDrawable() }, LinearLayout.LayoutParams(dpi(16), dpi(6)).apply { topMargin = dpi(3) })
                setOnClickListener {
                    if (selectedId == tab.id) onTabReselected?.invoke(tab) else {
                        selectedId = tab.id
                        onTabSelected?.invoke(tab)
                    }
                }
            }
            tabViews += tab to v
            dock.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        render()
    }

    fun setCenterAction(iconRes: Int, description: CharSequence, onClick: () -> Unit) {
        fab?.let { removeView(it) }
        val f = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            contentDescription = description
            depthBackground(SphereDrawable(context, Neon.CYAN, Neon.GREEN, glow = true, shadow = dp(12)))
            addView(context.icon(iconRes, Neon.ON_NEON, 30), LayoutParams(dpi(30), dpi(30), Gravity.CENTER))
            setOnClickListener { onClick() }
        }
        f.accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        }
        fab = f
        val size = dpi(64)
        addView(f, LayoutParams(size + f.paddingLeft + f.paddingRight, size + f.paddingTop + f.paddingBottom, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        if (tabViews.isNotEmpty()) setTabs(tabViews.map { it.first })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dpi(OCCUPIED_DP), MeasureSpec.EXACTLY))
    }

    private fun render() {
        tabViews.forEach { (tab, v) ->
            val on = tab.id == selectedId
            val color = if (on) Neon.CYAN else Neon.TEXT3
            (v.getChildAt(0) as ImageView).apply {
                setImageResource(if (on) tab.selectedIconRes else tab.iconRes)
                imageTintList = ColorStateList.valueOf(color)
            }
            (v.getChildAt(1) as TextView).setTextColor(if (on) Neon.TEXT1 else Neon.TEXT3)
            v.getChildAt(2).visibility = if (on) View.VISIBLE else View.INVISIBLE
            v.isSelected = on
            v.contentDescription = tab.label
        }
    }

    /** Glowing indicator dot under the selected tab. */
    private class DotDrawable : android.graphics.drawable.Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val cx = bounds.exactCenterX(); val cy = bounds.exactCenterY()
            val r = bounds.height() / 2f
            p.shader = RadialGradient(cx, cy, r * 2.6f, Neon.withAlpha(Neon.CYAN, 0.6f), 0, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r * 2.6f, p)
            p.shader = null
            p.color = Neon.CYAN
            canvas.drawRoundRect(cx - r * 1.6f, cy - r * 0.6f, cx + r * 1.6f, cy + r * 0.6f, r, r, p)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    companion object {
        const val OCCUPIED_DP = 118
    }
}

// ============================================================== Drawer menu

/** Content of the hamburger drawer: logo header, primary + secondary items, footer. */
class DrawerMenu(context: Context) : FrameLayout(context) {
    data class Item(val id: String, val iconRes: Int, val label: CharSequence, val badge: String? = null)

    var onItemClick: ((Item) -> Unit)? = null
    var selectedId: String? = null
        set(v) {
            field = v
            render()
        }

    private val list = context.vbox()
    private val greeting = context.label(null, R.style.Text_Body, sizeSp = 14f)
    private val primary = context.vbox()
    private val secondary = context.vbox()
    private val footer = context.label(null, R.style.Text_Caption)
    private val rows = mutableListOf<Pair<Item, LinearLayout>>()

    init {
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
        list.setPadding(dpi(18), dpi(36), dpi(18), dpi(20))
        // Header: glossy 3D logo sphere + name + greeting.
        val logo = FrameLayout(context).apply {
            depthBackground(SphereDrawable(context, Neon.CYAN, Neon.VIOLET, glow = true, shadow = dp(10)))
            addView(context.icon(R.drawable.ic_alarm_fill, Neon.ON_NEON, 30), LayoutParams(dpi(30), dpi(30), Gravity.CENTER))
        }
        list.addView(logo, LinearLayout.LayoutParams(dpi(60) + logo.paddingLeft + logo.paddingRight, dpi(60) + logo.paddingTop + logo.paddingBottom))
        list.addView(context.label(context.getString(R.string.app_name), R.style.Text_Headline, sizeSp = 24f).apply { neonText(Neon.AQUA) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(6) })
        list.addView(greeting)
        list.addView(context.hairline().apply { (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dpi(22); bottomMargin = dpi(14) } })
        list.addView(primary)
        list.addView(context.hairline().apply { (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dpi(14); bottomMargin = dpi(14) } })
        list.addView(secondary)
        list.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        list.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(24); marginStart = dpi(8) })
        scroll.addView(list)
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Staggered slide-in of the menu rows; called by NeonDrawerLayout as it starts opening. */
    fun playEntrance() {
        rows.forEachIndexed { i, (_, row) ->
            row.animate().cancel()
            row.alpha = 0f
            row.translationX = -dp(24)
            row.animate().alpha(1f).translationX(0f).setStartDelay(40L * i + 60).setDuration(260)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    fun setGreeting(text: CharSequence) { greeting.text = text }
    fun setFooter(text: CharSequence) { footer.text = text }
    fun setPrimaryItems(items: List<Item>) = fill(primary, items)
    fun setSecondaryItems(items: List<Item>) = fill(secondary, items)

    private fun fill(group: LinearLayout, items: List<Item>) {
        group.removeAllViews()
        rows.removeAll { (it.second.parent as? ViewGroup) == group }
        items.forEach { item ->
            val row = context.hbox().apply {
                minimumHeight = dpi(52)
                isClickable = true
                isFocusable = true
                addView(context.icon(item.iconRes, Neon.TEXT2, 22), LinearLayout.LayoutParams(dpi(22), dpi(22)).apply { marginEnd = dpi(16) })
                addView(context.label(item.label, R.style.Text_Title, Neon.TEXT1, 16f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                item.badge?.let { addView(context.label(it, R.style.Text_Caption, Neon.ON_NEON).apply {
                    setPadding(dpi(8), dpi(2), dpi(8), dpi(2))
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10); setColor(Neon.AMBER) }
                }) }
                setOnClickListener { onItemClick?.invoke(item) }
            }
            rows += item to row
            group.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(2) })
        }
        render()
    }

    private fun render() {
        rows.forEach { (item, row) ->
            val on = item.id == selectedId
            if (on) {
                row.setTag(R.id.tag_depth_insets, null)
                row.setPadding(dpi(12), 0, dpi(12), 0)
                row.depthBackground(GlassCardDrawable(context, radius = dp(18), elevation = dp(6), topColor = Neon.SURFACE_HI, outline = Neon.AQUA, glowColor = Neon.withAlpha(Neon.CYAN, 0.5f)))
            } else {
                row.background = context.getDrawable(R.drawable.ctl_row_ripple)
                row.setTag(R.id.tag_depth_insets, null)
                row.setPadding(dpi(17), dpi(4), dpi(17), dpi(8))
            }
            (row.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(if (on) Neon.CYAN else Neon.TEXT2)
            row.isSelected = on
        }
    }
}
