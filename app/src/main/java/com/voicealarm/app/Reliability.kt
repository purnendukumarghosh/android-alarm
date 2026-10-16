package com.voicealarm.app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Everything an alarm needs from the system to ring reliably, with a way to
 * fix each item. Used by the Alarms page (warning banner) and Settings.
 */
object Reliability {

    enum class Kind { NOTIFICATIONS, EXACT_ALARMS, FULL_SCREEN, BATTERY, TTS }

    data class Check(
        val kind: Kind,
        val title: String,
        val description: String,
        val ok: Boolean,
        /** True when the alarm can still ring without it (shown as a tip, not a warning). */
        val optional: Boolean = false,
    )

    /** Checks that apply to this Android version. [ttsAvailable] = null when unknown yet. */
    fun checks(context: Context, ttsAvailable: Boolean? = null): List<Check> {
        val list = mutableListOf<Check>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Check(
                Kind.NOTIFICATIONS, "Notifications",
                "Needed to show and ring the alarm.",
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Check(
                Kind.EXACT_ALARMS, "Alarms & reminders",
                "Rings at the exact minute, even in battery saver.",
                AlarmScheduler.canScheduleExact(context),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            list += Check(
                Kind.FULL_SCREEN, "Full-screen alarm",
                "Opens the alarm screen over your lock screen.",
                context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
            )
        }
        list += Check(
            Kind.BATTERY, "Unrestricted battery",
            "Stops aggressive battery savers from killing alarms.",
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            optional = true,
        )
        if (ttsAvailable != null) {
            list += Check(
                Kind.TTS, "Natural voices",
                "Install “Speech Services by Google” for the most human-sounding voices.",
                ttsAvailable,
                optional = true,
            )
        }
        return list
    }

    /** Required checks that are failing. */
    fun problems(context: Context): List<Check> = checks(context).filter { !it.ok && !it.optional }

    /** Opens the screen (or permission prompt) that fixes [kind]. */
    fun fix(activity: Activity, kind: Kind, requestCode: Int = 41) {
        val pkg = Uri.parse("package:${activity.packageName}")
        when (kind) {
            Kind.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ||
                    !hasAskedNotifications(activity)
                ) {
                    markAskedNotifications(activity)
                    activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestCode)
                } else {
                    open(activity, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                }
            }
            Kind.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                open(activity, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
            }
            Kind.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                open(activity, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
            }
            Kind.BATTERY -> open(activity, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            Kind.TTS -> open(activity, Intent("com.android.settings.TTS_SETTINGS"))
        }
    }

    private fun open(activity: Activity, intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    private fun hasAskedNotifications(context: Context) =
        context.getSharedPreferences("reliability", Context.MODE_PRIVATE).getBoolean("asked_notif", false)

    private fun markAskedNotifications(context: Context) =
        context.getSharedPreferences("reliability", Context.MODE_PRIVATE).edit().putBoolean("asked_notif", true).apply()
}
