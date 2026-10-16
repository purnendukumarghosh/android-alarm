package com.voicealarm.app.ui.pages

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.voicealarm.app.R
import com.voicealarm.app.ui.BottomNavBar
import com.voicealarm.app.ui.Neon
import com.voicealarm.app.ui.NeonSheet
import com.voicealarm.app.ui.SunkenDrawable
import com.voicealarm.app.ui.depthBackground
import com.voicealarm.app.ui.dp
import com.voicealarm.app.ui.dpi
import com.voicealarm.app.ui.hbox
import com.voicealarm.app.ui.icon
import com.voicealarm.app.ui.iconTile
import com.voicealarm.app.ui.label
import com.voicealarm.app.ui.vbox

/** Scrollable page body with gutters and room for the floating dock. */
class PageScroll(context: Context) {
    val column: LinearLayout = context.vbox().apply {
        setPadding(context.dpi(20), context.dpi(4), context.dpi(20), context.dpi(BottomNavBar.OCCUPIED_DP + 28))
        clipToPadding = false
        // Take initial focus ourselves so text fields don't grab it (and pop the keyboard) on open.
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
    }
    val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        clipToPadding = false
        addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun add(view: View, top: Int = 0) {
        column.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = column.context.dpi(top)
        })
    }

    /** Staggered fade/slide of the column's children. */
    fun animateIn() {
        for (i in 0 until column.childCount) {
            val c = column.getChildAt(i)
            c.alpha = 0f
            c.translationY = c.dp(16)
            c.animate().alpha(1f).translationY(0f).setStartDelay(35L * i).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
        }
    }
}

/** Sunken text field with neon focus ring. */
fun Context.sunkenInput(hint: CharSequence, iconRes: Int? = null): Pair<View, EditText> {
    val edit = EditText(this).apply {
        this.hint = hint
        background = null
        setTextAppearance(R.style.Widget_Neon_EditText)
        setTextColor(Neon.TEXT1)
        setHintTextColor(Neon.TEXT3)
        textSize = 17f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_DONE
        setSingleLine(true)
        setPadding(0, dpi(14), 0, dpi(14))
    }
    val box = hbox().apply {
        setPadding(dpi(16), 0, dpi(16), 0)
        val sunken = SunkenDrawable(context)
        depthBackground(sunken)
        iconRes?.let { addView(context.icon(it, Neon.TEXT2, 22), LinearLayout.LayoutParams(dpi(22), dpi(22)).apply { marginEnd = dpi(12) }) }
        addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        edit.setOnFocusChangeListener { _, has -> sunken.active = has; invalidate() }
    }
    return box to edit
}

/** "How it works" sheet with three illustrated steps. */
object HowItWorks {
    fun show(activity: Activity) {
        val sheet = NeonSheet(activity)
        sheet.title = activity.getString(R.string.main_how_title)
        val list = activity.vbox()
        val steps = listOf(
            Triple(R.drawable.ic_schedule, Neon.CYAN, R.string.main_how_1_title to R.string.main_how_1),
            Triple(R.drawable.ic_label, Neon.MAGENTA, R.string.main_how_2_title to R.string.main_how_2),
            Triple(R.drawable.ic_record_voice_over, Neon.GREEN, R.string.main_how_3_title to R.string.main_how_3),
        )
        steps.forEachIndexed { i, (icon, color, texts) ->
            val row = activity.hbox(Gravity.TOP).apply {
                setPadding(0, activity.dpi(10), 0, activity.dpi(10))
                addView(activity.iconTile(icon, color, 44), LinearLayout.LayoutParams(activity.dpi(44), activity.dpi(44)).apply { marginEnd = activity.dpi(16) })
                val t = activity.vbox()
                t.addView(activity.label("${i + 1}. " + activity.getString(texts.first), R.style.Text_Title, sizeSp = 16f))
                t.addView(activity.label(activity.getString(texts.second), R.style.Text_Body, sizeSp = 14f))
                addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            list.addView(row)
        }
        sheet.setContent(list)
        sheet.addAction(activity.getString(R.string.main_got_it)) { sheet.dismiss() }
        sheet.show()
    }
}
