package com.jono.nowwidget.core

import java.util.Calendar
import java.util.TimeZone

/**
 * Diary days run from 4:00am to 4:00am, so a late night belongs to the day it started on.
 * Used for "today" on the widget, the stats periods and the timeline's days.
 */
object DiaryDay {
    const val START_HOUR = 4

    private fun cal(ms: Long, tz: TimeZone): Calendar =
        Calendar.getInstance(tz).apply { timeInMillis = ms }

    /** The 4:00am that starts the diary day containing [ms]. */
    fun start(ms: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val c = cal(ms, tz)
        if (c.get(Calendar.HOUR_OF_DAY) < START_HOUR) c.add(Calendar.DAY_OF_YEAR, -1)
        return atStartHour(c)
    }

    /** The start of the diary day [days] after the one starting at [dayStart], by the calendar. */
    fun add(dayStart: Long, days: Int, tz: TimeZone = TimeZone.getDefault()): Long {
        val c = cal(start(dayStart, tz), tz)
        c.add(Calendar.DAY_OF_YEAR, days)
        return atStartHour(c)
    }

    /** The diary day's date as year, month (1–12), day. */
    fun date(ms: Long, tz: TimeZone = TimeZone.getDefault()): Triple<Int, Int, Int> {
        val c = cal(start(ms, tz), tz)
        return Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /**
     * A clock time picked for the diary day starting at [dayStart]: times before 4:00am fall on
     * the next calendar date, since they're the end of that diary day.
     */
    fun at(dayStart: Long, hour: Int, minute: Int, tz: TimeZone = TimeZone.getDefault()): Long {
        val c = cal(start(dayStart, tz), tz)
        if (hour < START_HOUR) c.add(Calendar.DAY_OF_YEAR, 1)
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun atStartHour(c: Calendar): Long {
        c.set(Calendar.HOUR_OF_DAY, START_HOUR)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
