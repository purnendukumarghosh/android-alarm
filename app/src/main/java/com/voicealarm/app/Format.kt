package com.voicealarm.app

import android.content.Context

object Format {
    /** "Alarm set for 7 hours 23 minutes from now." */
    fun timeUntilText(context: Context, triggerAt: Long): String =
        context.getString(R.string.alarm_set_in, durationText(context, triggerAt - System.currentTimeMillis()))

    /** "7 hours 23 minutes" (rounded up to the minute, never less than 1 minute). */
    fun durationText(context: Context, millis: Long): String {
        val totalMin = ((millis + 59_999) / 60_000).coerceAtLeast(1)
        val days = (totalMin / (60 * 24)).toInt()
        val hours = ((totalMin / 60) % 24).toInt()
        val minutes = (totalMin % 60).toInt()
        val res = context.resources
        val parts = mutableListOf<String>()
        if (days > 0) parts += res.getQuantityString(R.plurals.days, days, days)
        if (hours > 0) parts += res.getQuantityString(R.plurals.hours, hours, hours)
        if (minutes > 0 || parts.isEmpty()) parts += res.getQuantityString(R.plurals.minutes, minutes, minutes)
        return parts.joinToString(" ")
    }

    /** Compact countdown for badges: "7h 23m", "45m", "2d 4h". */
    fun shortDuration(millis: Long): String {
        val totalMin = ((millis + 59_999) / 60_000).coerceAtLeast(1)
        val d = totalMin / (60 * 24)
        val h = (totalMin / 60) % 24
        val m = totalMin % 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }
}
