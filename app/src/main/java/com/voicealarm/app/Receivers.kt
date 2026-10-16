package com.voicealarm.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fired by AlarmManager when an alarm (or a snooze) is due. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ALARM_ID, -1)
        if (intent.action == ACTION_CANCEL_SNOOZE) {
            AlarmScheduler.cancelSnooze(context, id)
            context.getSystemService(NotificationManager::class.java)
                .cancel(AlarmService.infoNotificationId(id))
            return
        }
        if (intent.action != ACTION_FIRE) return
        val snooze = intent.getBooleanExtra(EXTRA_SNOOZE, false)
        val alarm = AlarmStore.get(context, id) ?: return

        if (!snooze) {
            if (alarm.isRepeating) {
                AlarmScheduler.schedule(context, alarm)
            } else {
                AlarmStore.save(context, alarm.copy(enabled = false))
            }
        }

        AlarmService.start(context, id)
    }

    companion object {
        const val ACTION_FIRE = "com.voicealarm.app.FIRE"
        const val ACTION_CANCEL_SNOOZE = "com.voicealarm.app.CANCEL_SNOOZE"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_SNOOZE = "snooze"
    }
}

/** Alarms are forgotten by the system on reboot or clock changes; put them back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(context)
    }
}
