package com.voicealarm.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/** Full-screen "alarm is ringing" screen, shown over the lock screen. */
class AlarmRingActivity : Activity() {

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AlarmService.ringingAlarmId == -1) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_ring)

        findViewById<Button>(R.id.snoozeButton).setOnClickListener { send(AlarmService.ACTION_SNOOZE) }
        findViewById<Button>(R.id.dismissButton).setOnClickListener { send(AlarmService.ACTION_DISMISS) }
        bind(intent)

        val filter = IntentFilter(AlarmService.ACTION_RING_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
    }

    override fun onResume() {
        super.onResume()
        if (AlarmService.ringingAlarmId == -1) finish()
    }

    private fun bind(intent: Intent) {
        val id = intent.getIntExtra(AlarmService.EXTRA_ALARM_ID, AlarmService.ringingAlarmId)
        val alarm = AlarmStore.get(this, id)
        findViewById<TextView>(R.id.ringTime).text =
            Alarm.formatTime(this, java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
                java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE))
        findViewById<TextView>(R.id.ringLabel).text =
            alarm?.label?.ifBlank { null } ?: getString(R.string.app_name)
        findViewById<Button>(R.id.snoozeButton).text =
            getString(R.string.snooze_minutes, alarm?.snoozeMinutes ?: 10)
    }

    private fun send(action: String) {
        startService(Intent(this, AlarmService::class.java).setAction(action))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Ignore back so the alarm can't be silenced by accident.
    }

    override fun onDestroy() {
        unregisterReceiver(stateReceiver)
        super.onDestroy()
    }
}
