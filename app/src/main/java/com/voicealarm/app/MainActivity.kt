package com.voicealarm.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.voicealarm.app.ui.AppBar
import com.voicealarm.app.ui.BottomNavBar
import com.voicealarm.app.ui.DrawerMenu
import com.voicealarm.app.ui.NeonDrawerLayout
import com.voicealarm.app.ui.NeonSheet
import com.voicealarm.app.ui.Page
import com.voicealarm.app.ui.PageHost
import com.voicealarm.app.ui.Snack
import com.voicealarm.app.ui.dpi
import com.voicealarm.app.ui.pages.AlarmsPage
import com.voicealarm.app.ui.pages.HowItWorks
import com.voicealarm.app.ui.pages.NapPage
import com.voicealarm.app.ui.pages.SettingsPage
import com.voicealarm.app.ui.pages.VoicesPage
import java.util.Calendar

/** App shell: 3D hamburger drawer, app bar, tab pages and a floating dock with a "+" FAB. */
class MainActivity : Activity(), PageHost {
    private lateinit var drawer: NeonDrawerLayout
    private lateinit var appBar: AppBar
    private lateinit var container: FrameLayout
    private lateinit var bottomNav: BottomNavBar
    private lateinit var snackHost: FrameLayout
    private lateinit var menu: DrawerMenu
    private val pages = LinkedHashMap<String, Page>()
    private var current: String = TAB_ALARMS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlarmService.ensureChannels(this)

        drawer = NeonDrawerLayout(this).apply { id = R.id.main_drawer }
        val content = FrameLayout(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appBar = AppBar(this)
        column.addView(appBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(64)).apply { topMargin = dpi(4) })
        container = FrameLayout(this)
        column.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bottomNav = BottomNavBar(this)
        content.addView(bottomNav, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = dpi(12); rightMargin = dpi(12); bottomMargin = dpi(6)
        })
        snackHost = FrameLayout(this)
        content.addView(snackHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        drawer.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        menu = DrawerMenu(this)
        drawer.setDrawer(menu)
        setContentView(drawer)

        appBar.setNavigation(R.drawable.ic_menu, getString(R.string.main_open_menu)) { drawer.toggle() }
        bottomNav.setCenterAction(R.drawable.ic_add, getString(R.string.main_new_alarm)) { openEditor(-1) }
        bottomNav.setTabs(listOf(
            BottomNavBar.Tab(TAB_ALARMS, R.drawable.ic_alarm, R.drawable.ic_alarm_fill, getString(R.string.main_tab_alarms)),
            BottomNavBar.Tab(TAB_NAP, R.drawable.ic_bedtime, R.drawable.ic_bedtime_fill, getString(R.string.main_tab_nap)),
            BottomNavBar.Tab(TAB_VOICES, R.drawable.ic_record_voice_over, R.drawable.ic_record_voice_over_fill, getString(R.string.main_tab_voices)),
            BottomNavBar.Tab(TAB_SETTINGS, R.drawable.ic_settings, R.drawable.ic_settings_fill, getString(R.string.main_tab_settings)),
        ))
        bottomNav.onTabSelected = { selectTab(it.id) }
        bottomNav.onTabReselected = { (pages[it.id]?.view as? ScrollView)?.smoothScrollTo(0, 0) }

        menu.setPrimaryItems(listOf(
            DrawerMenu.Item(TAB_ALARMS, R.drawable.ic_alarm, getString(R.string.main_menu_alarms)),
            DrawerMenu.Item(TAB_NAP, R.drawable.ic_bedtime, getString(R.string.main_menu_nap)),
            DrawerMenu.Item(TAB_VOICES, R.drawable.ic_record_voice_over, getString(R.string.main_menu_voices)),
            DrawerMenu.Item(TAB_SETTINGS, R.drawable.ic_settings, getString(R.string.main_menu_settings)),
        ))
        menu.setSecondaryItems(listOf(
            DrawerMenu.Item(ITEM_TEST, R.drawable.ic_notifications_active, getString(R.string.main_menu_test)),
            DrawerMenu.Item(ITEM_HOW, R.drawable.ic_help, getString(R.string.main_menu_how)),
            DrawerMenu.Item(ITEM_ABOUT, R.drawable.ic_info, getString(R.string.main_menu_about)),
        ))
        menu.setFooter(getString(R.string.main_footer, versionName()))
        menu.onItemClick = { item ->
            drawer.close()
            when (item.id) {
                ITEM_TEST -> startActivity(Intent(this, AlarmRingActivity::class.java).putExtra(AlarmRingActivity.EXTRA_PREVIEW, true))
                ITEM_HOW -> HowItWorks.show(this)
                ITEM_ABOUT -> startActivity(Intent(this, AboutActivity::class.java))
                else -> selectTab(item.id)
            }
        }

        selectTab(intent.getStringExtra(EXTRA_START_TAB) ?: savedInstanceState?.getString(STATE_TAB) ?: TAB_ALARMS)
        if (intent.getBooleanExtra(EXTRA_OPEN_DRAWER, false)) drawer.post { drawer.open(animate = false) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && savedInstanceState == null &&
            Reliability.checks(this).any { it.kind == Reliability.Kind.NOTIFICATIONS && !it.ok }
        ) {
            Reliability.fix(this, Reliability.Kind.NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_START_TAB)?.let { selectTab(it) }
        if (intent.getBooleanExtra(EXTRA_OPEN_DRAWER, false)) drawer.open()
    }

    override fun onResume() {
        super.onResume()
        pages[current]?.onShow()
        refreshChrome()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, current)
    }

    override fun onDestroy() {
        pages.values.forEach { it.onDestroy() }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pages[current]?.onShow()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            drawer.handleBack() -> Unit
            pages[current]?.onBack() == true -> Unit
            current != TAB_ALARMS -> selectTab(TAB_ALARMS)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ---- PageHost --------------------------------------------------------------

    override fun openEditor(alarmId: Int) {
        startActivity(Intent(this, AlarmEditActivity::class.java).putExtra(AlarmEditActivity.EXTRA_ID, alarmId))
    }

    override fun selectTab(tab: String) {
        val id = if (tab in TABS) tab else TAB_ALARMS
        val previous = pages[current]
        val page = pages.getOrPut(id) { createPage(id) }
        if (previous !== page || page.view.parent == null) {
            previous?.let {
                it.onHide()
                val old = it.view
                old.animate().cancel()
                old.animate().alpha(0f).setDuration(120).withEndAction { if (pages[current]?.view !== old) container.removeView(old) }.start()
            }
            (page.view.parent as? ViewGroup)?.removeView(page.view)
            container.addView(page.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            page.view.alpha = 0f
            page.view.translationY = dpi(12).toFloat()
            page.view.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
        current = id
        bottomNav.selectedId = id
        menu.selectedId = id
        (page.view as? ScrollView)?.let { appBar.trackScroll(it) } ?: appBar.setElevated(false)
        page.onShow()
        refreshChrome()
    }

    override fun showSnack(text: CharSequence, actionLabel: CharSequence?, action: (() -> Unit)?) {
        Snack.show(snackHost, text, actionLabel, action, bottomMarginPx = bottomNav.occupiedHeightPx)
    }

    override fun refreshChrome() {
        val page = pages[current] ?: return
        appBar.title = page.title
        appBar.subtitle = if (current == TAB_ALARMS) greeting() else page.subtitle
        menu.setGreeting(greeting())
    }

    private fun createPage(id: String): Page = when (id) {
        TAB_NAP -> NapPage(this, this)
        TAB_VOICES -> VoicesPage(this, this)
        TAB_SETTINGS -> SettingsPage(this, this)
        else -> AlarmsPage(this, this)
    }

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = getString(when (h) {
            in 4..11 -> R.string.main_greeting_morning
            in 12..16 -> R.string.main_greeting_afternoon
            in 17..21 -> R.string.main_greeting_evening
            else -> R.string.main_greeting_night
        })
        val name = AppSettings.userName(this)
        return if (name.isEmpty()) part else getString(R.string.main_greeting_named, part, name)
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "2.0"
    } catch (e: Exception) {
        "2.0"
    }

    companion object {
        const val EXTRA_START_TAB = "start_tab"
        const val EXTRA_OPEN_DRAWER = "open_drawer"
        const val TAB_ALARMS = "alarms"
        const val TAB_NAP = "nap"
        const val TAB_VOICES = "voices"
        const val TAB_SETTINGS = "settings"
        private val TABS = setOf(TAB_ALARMS, TAB_NAP, TAB_VOICES, TAB_SETTINGS)
        private const val ITEM_TEST = "test"
        private const val ITEM_HOW = "how"
        private const val ITEM_ABOUT = "about"
        private const val STATE_TAB = "tab"
    }
}
