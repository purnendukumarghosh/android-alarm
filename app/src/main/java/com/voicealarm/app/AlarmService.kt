package com.voicealarm.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File

/** Rings an alarm: plays the voice, vibrates and shows the full-screen alarm UI. */
class AlarmService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var player: VoicePlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val autoStop = Runnable { stopRinging(missed = true) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> stopRinging(missed = false)
            ACTION_SNOOZE -> snooze()
            else -> startRinging(intent?.getIntExtra(EXTRA_ALARM_ID, -1) ?: -1)
        }
        return START_NOT_STICKY
    }

    private fun startRinging(id: Int) {
        val alarm = AlarmStore.get(this, id)
        // startForeground must always be called after startForegroundService().
        goForeground(alarm ?: Alarm(id, 0, 0, ""))
        if (alarm == null) {
            stopRinging(missed = false)
            return
        }
        // A second alarm replaces one that is still ringing.
        releaseMedia()
        ringingAlarmId = alarm.id
        getSystemService(NotificationManager::class.java).cancel(infoNotificationId(alarm.id))

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceAlarm:ring")
            .apply { acquire(ringTimeoutMs() + 60_000) }

        player = VoicePlayer(this, AudioAttributes.USAGE_ALARM).also {
            it.play(alarm, voiceFileFor(this, alarm), loop = true, crescendo = AppSettings.gradualVolume(this), musicFile = musicFileFor(this, alarm))
        }
        if (alarm.vibrate) startVibration()

        main.removeCallbacks(autoStop)
        main.postDelayed(autoStop, ringTimeoutMs())
        sendBroadcast(Intent(ACTION_RING_STATE).setPackage(packageName))
    }

    private fun ringTimeoutMs() = AppSettings.ringMinutes(this).coerceIn(1, 60) * 60_000L

    private fun snooze() {
        val alarm = AlarmStore.get(this, ringingAlarmId)
        if (alarm != null) {
            val at = AlarmScheduler.scheduleSnooze(this, alarm)
            notifySimple(
                alarm.id,
                getString(R.string.snoozed_until, Alarm.formatTime(this, hourOf(at), minuteOf(at))),
                alarm.label,
                cancelSnooze = true,
            )
        }
        stopRinging(missed = false)
    }

    private fun stopRinging(missed: Boolean) {
        val alarm = AlarmStore.get(this, ringingAlarmId)
        if (missed && alarm != null) {
            notifySimple(alarm.id, getString(R.string.missed_alarm, alarm.timeText(this)), alarm.label)
        }
        releaseMedia()
        main.removeCallbacksAndMessages(null)
        ringingAlarmId = -1
        sendBroadcast(Intent(ACTION_RING_STATE).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseMedia() {
        player?.stop()
        player = null
        vibrator?.cancel()
        vibrator = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseMedia()
        main.removeCallbacksAndMessages(null)
        ringingAlarmId = -1
        super.onDestroy()
    }

    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        val pattern = longArrayOf(0, 600, 400, 600, 1400)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        @Suppress("DEPRECATION")
        v.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs)
        vibrator = v
    }

    private fun goForeground(alarm: Alarm) {
        ensureChannels(this)
        val fullScreen = PendingIntent.getActivity(
            this, alarm.id,
            Intent(this, AlarmRingActivity::class.java)
                .putExtra(EXTRA_ALARM_ID, alarm.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = alarm.label.ifBlank { getString(R.string.app_name) }
        val notification = Notification.Builder(this, CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(com.voicealarm.app.ui.Neon.MAGENTA)
            .setContentTitle(title)
            .setContentText(alarm.timeText(this))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(Notification.Action.Builder(null, getString(R.string.snooze), serviceIntent(this, ACTION_SNOOZE)).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.dismiss), serviceIntent(this, ACTION_DISMISS)).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_RINGING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_RINGING, notification)
        }
    }

    private fun notifySimple(id: Int, title: String, text: String, cancelSnooze: Boolean = false) {
        val builder = Notification.Builder(this, CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(com.voicealarm.app.ui.Neon.CYAN)
            .setContentTitle(title)
            .setContentText(text.ifBlank { getString(R.string.app_name) })
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        if (cancelSnooze) {
            val cancel = PendingIntent.getBroadcast(
                this, id,
                Intent(this, AlarmReceiver::class.java)
                    .setAction(AlarmReceiver.ACTION_CANCEL_SNOOZE)
                    .putExtra(AlarmReceiver.EXTRA_ALARM_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(null, getString(R.string.cancel_snooze), cancel).build())
        }
        getSystemService(NotificationManager::class.java).notify(infoNotificationId(id), builder.build())
    }

    private fun hourOf(millis: Long) =
        java.util.Calendar.getInstance().apply { timeInMillis = millis }.get(java.util.Calendar.HOUR_OF_DAY)

    private fun minuteOf(millis: Long) =
        java.util.Calendar.getInstance().apply { timeInMillis = millis }.get(java.util.Calendar.MINUTE)

    companion object {
        const val ACTION_DISMISS = "com.voicealarm.app.DISMISS"
        const val ACTION_SNOOZE = "com.voicealarm.app.SNOOZE"
        const val ACTION_RING_STATE = "com.voicealarm.app.RING_STATE"
        const val EXTRA_ALARM_ID = "alarm_id"

        private const val CHANNEL_RINGING = "ringing"
        private const val CHANNEL_INFO = "info"
        private const val NOTIF_RINGING = 1

        /** Id of the alarm currently ringing, or -1. */
        @Volatile
        var ringingAlarmId: Int = -1
            private set

        fun infoNotificationId(alarmId: Int) = 1000 + alarmId

        fun start(context: Context, alarmId: Int) {
            context.startForegroundService(
                Intent(context, AlarmService::class.java).putExtra(EXTRA_ALARM_ID, alarmId)
            )
        }

        fun serviceIntent(context: Context, action: String): PendingIntent = PendingIntent.getService(
            context, action.hashCode(),
            Intent(context, AlarmService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun voiceFileFor(context: Context, alarm: Alarm): File? {
            val recording = AlarmStore.recordingFile(context, alarm.id)
            if (alarm.useRecording && recording.exists()) return recording
            val speech = AlarmStore.speechFile(context, alarm.id)
            return if (speech.exists()) speech else null
        }

        /** The alarm's song, if it has one and the file still exists. */
        fun musicFileFor(context: Context, alarm: Alarm): File? =
            if (alarm.usesMusic) com.voicealarm.app.audio.SoundLibrary.fileFor(context, alarm.musicId) else null

        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RINGING,
                    context.getString(R.string.channel_ringing),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    setSound(null, null) // the service plays the voice itself
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_INFO,
                    context.getString(R.string.channel_info),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
    }
}
