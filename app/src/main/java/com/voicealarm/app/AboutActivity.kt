package com.voicealarm.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.voicealarm.app.ui.GlassCardDrawable
import com.voicealarm.app.ui.GlowBackgroundDrawable
import com.voicealarm.app.ui.Neon
import com.voicealarm.app.ui.NeonButtonDrawable
import com.voicealarm.app.ui.SphereDrawable
import com.voicealarm.app.ui.depthBackground
import com.voicealarm.app.ui.dp
import com.voicealarm.app.ui.dpi
import com.voicealarm.app.ui.neonText

/** "About us": the app, its creator and a link to his LinkedIn profile. */
class AboutActivity : Activity() {

    private lateinit var content: LinearLayout
    private val animators = mutableListOf<ValueAnimator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { background = GlowBackgroundDrawable() }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(84), dpi(20), dpi(40))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(header(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dpi(72)))

        buildHero()
        buildCreator()
        buildStory()
        buildFeatures()
        buildFooter()
        setContentView(root)
        animateIn()
    }

    override fun onDestroy() {
        animators.forEach { it.cancel() }
        super.onDestroy()
    }

    // ---- header -------------------------------------------------------------

    private fun header(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12), dpi(8), dpi(20), 0)
        }
        val back = FrameLayout(this).apply {
            depthBackground(GlassCardDrawable(context, radius = -1f, elevation = dp(6), pressable = true))
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.about_back)
            setOnClickListener { finish() }
            addView(icon(R.drawable.ic_arrow_back, Neon.TEXT1, 22), FrameLayout.LayoutParams(dpi(22), dpi(22), Gravity.CENTER))
        }
        bar.addView(back, LinearLayout.LayoutParams(dpi(48) + 2 * dpi(6), dpi(48) + dpi(14)))
        bar.addView(text(getString(R.string.about_title), R.style.Text_Title).apply {
            textSize = 20f
            setPadding(dpi(8), 0, 0, dpi(4))
        })
        return bar
    }

    // ---- sections ------------------------------------------------------------

    private fun buildHero() {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val artId = resources.getIdentifier("art_clock", "drawable", packageName)
        val art = if (artId != 0) {
            ImageView(this).apply { setImageResource(artId); contentDescription = null }
        } else {
            FrameLayout(this).apply {
                depthBackground(SphereDrawable(context, Neon.CYAN, glow = false))
                addView(icon(R.drawable.ic_alarm, Neon.TEXT1, 48), FrameLayout.LayoutParams(dpi(48), dpi(48), Gravity.CENTER))
            }
        }
        hero.addView(art, LinearLayout.LayoutParams(dpi(168), dpi(168)))
        // Gentle floating motion for the 3D art.
        animators += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { art.translationY = -dp(6) * it.animatedValue as Float }
            start()
        }
        hero.addView(text(getString(R.string.app_name), R.style.Text_Headline).apply {
            textSize = 32f
            gravity = Gravity.CENTER
            neonText()
        }, matchWrap(top = 8))
        hero.addView(text(getString(R.string.about_tagline), R.style.Text_Body).apply { gravity = Gravity.CENTER }, matchWrap(top = 4))
        hero.addView(versionChip(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpi(12)
        })
        content.addView(hero, matchWrap())
    }

    private fun versionChip(): View = text(getString(R.string.about_version, versionName()), R.style.Text_Caption).apply {
        setTextColor(Neon.TEXT2)
        setPadding(dpi(14), dpi(6), dpi(14), dpi(6))
        depthBackground(GlassCardDrawable(context, radius = -1f, elevation = dp(6), topColor = 0))
    }

    private fun buildCreator() {
        content.addView(overline(getString(R.string.about_created_by)), matchWrap(top = 28))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(20), dpi(24), dpi(20), dpi(20))
            depthBackground(GlassCardDrawable(context))
        }

        // Avatar with a LinkedIn badge.
        val avatarBox = FrameLayout(this)
        avatarBox.addView(AvatarView(this, getString(R.string.about_creator_initials)).also { avatar ->
            animators += ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 9000
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { avatar.ringRotation = it.animatedValue as Float }
                start()
            }
        }, FrameLayout.LayoutParams(dpi(112), dpi(112), Gravity.CENTER))
        val badge = FrameLayout(this).apply {
            depthBackground(SphereDrawable(context, Neon.CYAN, glow = false, shadow = dp(5)).apply { base = Neon.BG0 })
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.about_open_linkedin)
            setOnClickListener { openLinkedIn() }
            addView(icon(R.drawable.about_ic_linkedin, Neon.TEXT1, 18), FrameLayout.LayoutParams(dpi(18), dpi(18), Gravity.CENTER))
        }
        avatarBox.addView(badge, FrameLayout.LayoutParams(dpi(36) + 2 * dpi(5), dpi(36) + dpi(10), Gravity.BOTTOM or Gravity.END))
        card.addView(avatarBox, LinearLayout.LayoutParams(dpi(132), dpi(124)))

        card.addView(text(getString(R.string.about_creator_name), R.style.Text_Headline).apply {
            textSize = 24f
            gravity = Gravity.CENTER
        }, matchWrap(top = 12))
        card.addView(text(getString(R.string.about_creator_role), R.style.Text_Body).apply { gravity = Gravity.CENTER }, matchWrap(top = 2))

        val linkedIn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dpi(56)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.about_open_linkedin)
            setOnClickListener { openLinkedIn() }
            depthBackground(NeonButtonDrawable(context))
            addView(icon(R.drawable.about_ic_linkedin, Color.WHITE, 22), LinearLayout.LayoutParams(dpi(22), dpi(22)))
            addView(text(getString(R.string.about_connect_linkedin), R.style.Text_Button).apply {
                setTextColor(Color.WHITE)
                setPadding(dpi(12), 0, 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        card.addView(linkedIn, matchWrap(top = 20))
        card.addView(text(getString(R.string.about_linkedin_handle), R.style.Text_Caption).apply {
            gravity = Gravity.CENTER
            setTextColor(Neon.TEXT2)
            minHeight = dpi(40)
            setOnClickListener { openLinkedIn() }
            setOnLongClickListener { copyLink(); true }
        }, matchWrap(top = 2))
        content.addView(card, matchWrap(top = 4))
    }

    private fun buildStory() {
        content.addView(overline(getString(R.string.about_story_title)), matchWrap(top = 24))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(18), dpi(20), dpi(18))
            depthBackground(GlassCardDrawable(context))
        }
        card.addView(text(getString(R.string.about_story), R.style.Text_Body).apply {
            textSize = 16f
            setTextColor(Neon.TEXT1)
            setLineSpacing(0f, 1.25f)
        }, matchWrap())
        content.addView(card, matchWrap(top = 4))
    }

    private fun buildFeatures() {
        content.addView(overline(getString(R.string.about_special_title)), matchWrap(top = 24))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16), dpi(8), dpi(16), dpi(8))
            depthBackground(GlassCardDrawable(context))
        }
        val features = listOf(
            Triple(R.drawable.ic_record_voice_over_fill, Neon.CYAN, R.string.about_feature_speaks to R.string.about_feature_speaks_desc),
            Triple(R.drawable.ic_mic_fill, Neon.MAGENTA, R.string.about_feature_own to R.string.about_feature_own_desc),
            Triple(R.drawable.ic_bolt_fill, Neon.GREEN, R.string.about_feature_offline to R.string.about_feature_offline_desc),
            Triple(R.drawable.ic_shield_fill, Neon.BLUE, R.string.about_feature_reliable to R.string.about_feature_reliable_desc),
        )
        features.forEachIndexed { i, (iconRes, color, texts) ->
            card.addView(featureRow(iconRes, color, getString(texts.first), getString(texts.second)), matchWrap())
            if (i < features.lastIndex) card.addView(View(this).apply { setBackgroundColor(0x14FFFFFF) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = dpi(60)
            })
        }
        content.addView(card, matchWrap(top = 4))

        val licenses = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(16), dpi(14), dpi(12), dpi(14))
            minimumHeight = dpi(56)
            isClickable = true
            isFocusable = true
            depthBackground(GlassCardDrawable(context, pressable = true))
            setOnClickListener { showLicenses() }
            addView(icon(R.drawable.ic_info, Neon.TEXT2, 22), LinearLayout.LayoutParams(dpi(22), dpi(22)))
            addView(text(getString(R.string.about_licenses), R.style.Text_Title).apply {
                textSize = 16f
                setPadding(dpi(14), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(icon(R.drawable.ic_chevron_right, Neon.TEXT3, 22), LinearLayout.LayoutParams(dpi(22), dpi(22)))
        }
        content.addView(licenses, matchWrap(top = 12))
    }

    private fun featureRow(iconRes: Int, color: Int, title: String, desc: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dpi(12), 0, dpi(12))
        val tile = FrameLayout(context).apply {
            background = TileDrawable(color)
            addView(icon(iconRes, color, 22), FrameLayout.LayoutParams(dpi(22), dpi(22), Gravity.CENTER))
        }
        addView(tile, LinearLayout.LayoutParams(dpi(44), dpi(44)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16), 0, 0, 0)
            addView(text(title, R.style.Text_Title).apply { textSize = 16f })
            addView(text(desc, R.style.Text_Body).apply { textSize = 13f })
        }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun buildFooter() {
        val made = SpannableString(getString(R.string.about_made_with))
        val heart = made.indexOf('♥')
        if (heart >= 0) made.setSpan(ForegroundColorSpan(Neon.RED), heart, heart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        content.addView(text("", R.style.Text_Body).apply {
            text = made
            gravity = Gravity.CENTER
            setTextColor(Neon.TEXT2)
        }, matchWrap(top = 32))
        content.addView(text(getString(R.string.about_copyright), R.style.Text_Caption).apply { gravity = Gravity.CENTER }, matchWrap(top = 4))
    }

    // ---- actions ---------------------------------------------------------------

    private fun openLinkedIn() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.about_linkedin_url)))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            copyLink()
            Toast.makeText(this, R.string.about_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    private fun copyLink() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("LinkedIn", getString(R.string.about_linkedin_url)))
        Toast.makeText(this, R.string.about_link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showLicenses() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about_licenses)
            .setMessage(R.string.about_licenses_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }

    // ---- small helpers -----------------------------------------------------------

    private fun animateIn() {
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            child.alpha = 0f
            child.translationY = dp(18)
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay(60L * i).setDuration(320)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun text(value: String, style: Int) = TextView(this).apply {
        setTextAppearance(style)
        text = value
    }

    private fun overline(value: String) = text(value, R.style.Text_Overline).apply { setPadding(dpi(4), 0, 0, 0) }

    private fun icon(res: Int, tint: Int, sizeDp: Int) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = android.content.res.ColorStateList.valueOf(tint)
        minimumWidth = dpi(sizeDp)
        minimumHeight = dpi(sizeDp)
    }

    private fun matchWrap(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(top) }
}

/** Avatar: initials in a thin white circle, orbited by a slowly turning hairline arc. */
private class AvatarView(context: Context, private val initials: String) : View(context) {
    var ringRotation = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Neon.TEXT1
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.08f
        typeface = context.resources.getFont(R.font.manrope_extralight)
    }
    private val arc = android.graphics.RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = minOf(w, h) / 2f
        disc.strokeWidth = context.dp(1.4f)
        disc.color = Neon.TEXT1
        ring.strokeWidth = context.dp(1f)
        label.textSize = r * 0.62f
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        val ringR = r * 0.9f
        arc.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        ring.color = 0x24FFFFFF
        canvas.drawCircle(cx, cy, ringR, ring)
        ring.color = Neon.withAlpha(Neon.TEXT1, 0.8f)
        canvas.drawArc(arc, ringRotation - 90f, 70f, false, ring)
        canvas.drawCircle(cx, cy, r * 0.72f, disc)
        val baseline = cy - (label.descent() + label.ascent()) / 2
        canvas.drawText(initials, cx, baseline, label)
    }
}

/** Thin ring framing a feature's line icon. */
private class TileDrawable(@Suppress("UNUSED_PARAMETER") color: Int) : android.graphics.drawable.Drawable() {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; setColor(0x2EFFFFFF) }

    override fun draw(canvas: Canvas) {
        val b = android.graphics.RectF(bounds)
        ring.strokeWidth = (b.width() / 40f).coerceAtLeast(1f)
        b.inset(ring.strokeWidth / 2, ring.strokeWidth / 2)
        canvas.drawOval(b, ring)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
