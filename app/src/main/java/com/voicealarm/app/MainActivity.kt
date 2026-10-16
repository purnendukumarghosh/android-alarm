package com.voicealarm.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var adapter: AlarmAdapter
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerButton: Button
    private var voiceCheck: VoiceEngine? = null
    private var noVoicesInstalled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AlarmService.ensureChannels(this)

        banner = findViewById(R.id.permissionBanner)
        bannerText = findViewById(R.id.permissionText)
        bannerButton = findViewById(R.id.permissionButton)

        adapter = AlarmAdapter()
        val list = findViewById<ListView>(R.id.alarmList)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.emptyView)
        list.setOnItemClickListener { _, _, position, _ ->
            openEditor(adapter.getItem(position).id)
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(adapter.getItem(position))
            true
        }
        findViewById<Button>(R.id.addButton).setOnClickListener { openEditor(-1) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }

        // Check once that the phone has a text-to-speech voice installed.
        voiceCheck = VoiceEngine(this) { ok ->
            noVoicesInstalled = !ok || voiceCheck?.naturalVoices().isNullOrEmpty()
            voiceCheck?.shutdown()
            voiceCheck = null
            updateBanner()
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.reload()
        updateBanner()
    }

    override fun onDestroy() {
        voiceCheck?.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateBanner()
    }

    private fun openEditor(id: Int) {
        startActivity(Intent(this, AlarmEditActivity::class.java).putExtra(AlarmEditActivity.EXTRA_ID, id))
    }

    private fun confirmDelete(alarm: Alarm) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_alarm)
            .setMessage("${alarm.timeText(this)}  ${alarm.label}")
            .setPositiveButton(R.string.delete) { _, _ ->
                AlarmScheduler.cancel(this, alarm.id)
                AlarmStore.delete(this, alarm.id)
                adapter.reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Shows the most important missing permission/setting, one at a time. */
    private fun updateBanner() {
        val pkgUri = Uri.parse("package:$packageName")
        val nm = getSystemService(NotificationManager::class.java)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                showBanner(R.string.perm_notifications) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
                }

            !AlarmScheduler.canScheduleExact(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                showBanner(R.string.perm_exact) {
                    open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri))
                }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !nm.canUseFullScreenIntent() ->
                showBanner(R.string.perm_full_screen) {
                    open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri))
                }

            noVoicesInstalled ->
                showBanner(R.string.perm_tts) {
                    open(Intent("com.android.settings.TTS_SETTINGS"))
                }

            else -> banner.visibility = View.GONE
        }
    }

    private fun showBanner(textRes: Int, action: () -> Unit) {
        banner.visibility = View.VISIBLE
        bannerText.setText(textRes)
        bannerButton.setOnClickListener { action() }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.open_settings_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private inner class AlarmAdapter : BaseAdapter() {
        private var alarms: List<Alarm> = emptyList()

        fun reload() {
            alarms = AlarmStore.all(this@MainActivity)
            notifyDataSetChanged()
        }

        override fun getCount() = alarms.size
        override fun getItem(position: Int) = alarms[position]
        override fun getItemId(position: Int) = alarms[position].id.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_alarm, parent, false)
            val alarm = alarms[position]
            view.findViewById<TextView>(R.id.itemTime).text = alarm.timeText(this@MainActivity)
            view.findViewById<TextView>(R.id.itemLabel).apply {
                text = alarm.label.ifBlank { getString(R.string.no_label) }
            }
            view.findViewById<TextView>(R.id.itemDays).text = buildString {
                append(alarm.daysText(this@MainActivity))
                append(" · ")
                append(getString(if (alarm.useRecording) R.string.voice_own_short else R.string.voice_tts_short))
            }
            val toggle = view.findViewById<Switch>(R.id.itemSwitch)
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = alarm.enabled
            view.alpha = if (alarm.enabled) 1f else 0.55f
            toggle.setOnCheckedChangeListener { _, checked ->
                val updated = alarm.copy(enabled = checked)
                AlarmStore.save(this@MainActivity, updated)
                AlarmScheduler.schedule(this@MainActivity, updated)
                if (checked) {
                    Toast.makeText(
                        this@MainActivity,
                        AlarmEditActivity.timeUntilText(this@MainActivity, AlarmScheduler.nextTrigger(updated)),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                reload()
            }
            return view
        }
    }

    companion object {
        private const val REQ_NOTIFICATIONS = 1
    }
}
