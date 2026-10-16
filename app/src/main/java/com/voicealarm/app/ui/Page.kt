package com.voicealarm.app.ui

import android.view.View

/** One tab of the main screen. Created once by MainActivity and kept alive. */
interface Page {
    /** Root view; MainActivity adds it to the page container. */
    val view: View

    /** Title / subtitle shown in the app bar while this page is visible. */
    val title: CharSequence
    val subtitle: CharSequence? get() = null

    /** Called when the page becomes visible (and on every Activity resume while visible). */
    fun onShow() {}

    fun onHide() {}

    fun onDestroy() {}

    /** Return true if the page consumed the back press (e.g. closed an inline panel). */
    fun onBack(): Boolean = false
}

/** What a page may ask of the main screen. Implemented by MainActivity. */
interface PageHost {
    fun openEditor(alarmId: Int)

    /** Switch tabs: "alarms" | "nap" | "voices" | "settings". */
    fun selectTab(tab: String)

    /** Short message in a glass snackbar above the bottom bar, with an optional action. */
    fun showSnack(text: CharSequence, actionLabel: CharSequence? = null, action: (() -> Unit)? = null)

    /** Ask the host to refresh its app bar (title/subtitle may have changed). */
    fun refreshChrome()
}
