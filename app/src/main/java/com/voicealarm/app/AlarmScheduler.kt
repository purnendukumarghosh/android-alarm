package com.voicealarm.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

object AlarmScheduler {
    private const val TAG = "AlarmScheduler"

    /** Next time [alarm] should ring, strictly after [now]. */
    fun nextTrigger(alarm: Alarm, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        if (alarm.isRepeating) {
            var guard = 0
            while (!alarm.repeatsOn(cal.get(Calendar.DAY_OF_WEEK)) && guard++ < 8) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java)
        return am.canScheduleExactAlarms()
    }

    /** Schedules (or cancels, if disabled) the alarm's next regular ring. */
    fun schedule(context: Context, alarm: Alarm) {
        if (!alarm.enabled) {
            cancel(context, alarm.id)
            return
        }
        setAt(context, nextTrigger(alarm), firePendingIntent(context, alarm.id, snooze = false))
    }

    fun scheduleSnooze(context: Context, alarm: Alarm): Long {
        val at = System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L
        setAt(context, at, firePendingIntent(context, alarm.id, snooze = true))
        return at
    }

    fun cancel(context: Context, id: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(firePendingIntent(context, id, snooze = false))
        am.cancel(firePendingIntent(context, id, snooze = true))
    }

    fun cancelSnooze(context: Context, id: Int) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(firePendingIntent(context, id, snooze = true))
    }

    fun rescheduleAll(context: Context) {
        AlarmStore.all(context).forEach { schedule(context, it) }
    }

    private fun setAt(context: Context, triggerAt: Long, operation: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        val showIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            // setAlarmClock is the most reliable option: exact, survives Doze,
            // and shows the alarm icon in the status bar.
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), operation)
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarms not permitted, falling back to inexact", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        }
    }

    private fun firePendingIntent(context: Context, id: Int, snooze: Boolean): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            putExtra(AlarmReceiver.EXTRA_ALARM_ID, id)
            putExtra(AlarmReceiver.EXTRA_SNOOZE, snooze)
        }
        val requestCode = id * 2 + if (snooze) 1 else 0
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
