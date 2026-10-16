package com.voicealarm.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.Duration

/**
 * Renders every screen with realistic data to PNG files in app/build/screenshots.
 * Doubles as a smoke test: each screen must create, lay out and draw without
 * crashing. CI publishes the images on the "screenshots" release.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class ScreenshotTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun seed() {
        AppSettings.setUserName(context, "Purnendu")
        AlarmStore.save(context, Alarm(1, 7, 0, "for interview", days = 0b0011111))
        AlarmStore.save(context, Alarm(2, 6, 30, "Gym time", days = 0b0010101, enabled = false))
        AlarmStore.save(context, Alarm(3, 21, 30, "Take your medicine", days = 0b1111111, useRecording = true))
        AlarmStore.recordingFile(context, 3).writeBytes(ByteArray(4096))
        AlarmStore.save(context, Alarm(4, 9, 15, "Catch the flight to Delhi", days = 0))
    }

    @Test
    fun mainAlarms() = shoot(MainActivity::class.java, "01_alarms", Intent().putExtra("start_tab", "alarms"))

    @Test
    fun mainDrawer() = shoot(
        MainActivity::class.java, "02_drawer",
        Intent().putExtra("start_tab", "alarms").putExtra("open_drawer", true),
    )

    @Test
    fun mainNap() = shoot(MainActivity::class.java, "03_nap", Intent().putExtra("start_tab", "nap"))

    @Test
    fun mainVoices() = shoot(MainActivity::class.java, "04_voices", Intent().putExtra("start_tab", "voices"))

    @Test
    fun mainSettings() = shoot(MainActivity::class.java, "05_settings", Intent().putExtra("start_tab", "settings"))

    @Test
    fun editorExisting() = shoot(AlarmEditActivity::class.java, "06_editor", Intent().putExtra("alarm_id", 1))

    @Test
    fun editorNew() = shoot(AlarmEditActivity::class.java, "07_editor_new", Intent().putExtra("alarm_id", -1))

    @Test
    fun editorRecording() = shoot(AlarmEditActivity::class.java, "08_editor_recording", Intent().putExtra("alarm_id", 3))

    @Test
    fun ringScreen() = shoot(
        AlarmRingActivity::class.java, "09_ring",
        Intent().putExtra("preview", true).putExtra(AlarmService.EXTRA_ALARM_ID, 1),
    )

    @Test
    fun about() = shoot(AboutActivity::class.java, "10_about", Intent())

    @Test
    fun emptyState() {
        AlarmStore.all(context).forEach { AlarmStore.delete(context, it.id) }
        shoot(MainActivity::class.java, "11_alarms_empty", Intent().putExtra("start_tab", "alarms"))
    }

    private fun <T : Activity> shoot(cls: Class<T>, name: String, intent: Intent) {
        intent.setClass(context, cls)
        val controller = Robolectric.buildActivity(cls, intent).setup()
        // Let entrance animations, drawer animations and async callbacks finish.
        repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500)) }
        val root = controller.get().window.decorView
        val dm = context.resources.displayMetrics
        root.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        val bitmap = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }
}
