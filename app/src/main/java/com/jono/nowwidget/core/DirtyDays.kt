package com.jono.nowwidget.core

import java.util.TimeZone

/** Which diary days a change can reach, kept to a cap. Pure, so it's unit-tested. */
object DirtyDays {
    /**
     * The diary days from [first] to [last] (day starts, inclusive), at most [cap] of them. The day
     * of the change comes first, then [last] itself (that's today when the change reaches it, so
     * today is never the one dropped), then the days after the change up to [last], and only then
     * the days before it, nearest first.
     */
    fun reach(changeDay: Long, first: Long, last: Long, cap: Int, tz: TimeZone = TimeZone.getDefault()): List<Long> {
        val out = LinkedHashSet<Long>()
        val lo = minOf(first, changeDay)
        val hi = maxOf(last, changeDay)
        if (cap <= 0) return emptyList()
        out.add(changeDay)
        if (out.size < cap) out.add(hi)
        var day = changeDay
        while (day <= hi && out.size < cap) {
            out.add(day)
            day = DiaryDay.add(day, 1, tz)
        }
        day = DiaryDay.add(changeDay, -1, tz)
        while (day >= lo && out.size < cap) {
            out.add(day)
            day = DiaryDay.add(day, -1, tz)
        }
        return out.toList()
    }
}
