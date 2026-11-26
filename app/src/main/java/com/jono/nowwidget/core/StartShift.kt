package com.jono.nowwidget.core

import java.util.TimeZone

/**
 * Moving the current entry's start ("I started 30 minutes ago"). Entries only have a start, so an
 * earlier start cuts into the entry before (its end is this start) and a later one gives time back.
 * The entry before is never deleted this way, moments keep their own times, and the start never
 * goes into the future or past the next entry. No Android in here.
 */
object StartShift {
    const val MINUTE = 60_000L
    const val UNDO_MS = 60_000L
    const val ARM_MS = 5_000L
    const val MAX_OFFSETS = 5
    val DEFAULT_OFFSETS = listOf(-5, -15, -30, -60)
    /** The "Ask before cutting more than N minutes" choices; 0 is off. */
    val ASK_CHOICES = listOf(0, 15, 30, 60)

    /** What moving the start would do. */
    data class Shift(
        val entryId: Long,
        val oldStart: Long,
        val newStart: Long,
        /** It stopped one minute after the start of the entry before, so as not to delete it. */
        val clamped: Boolean,
        /** How much the entry before loses (0 when it gains, or when it was idle). */
        val cutMs: Long,
        /** The entry before was idle (nothing on): cutting it needs no warning. */
        val prevIdle: Boolean,
        val prevActivityId: Long?,
        /** The entry before's length afterwards; null when there is none. */
        val prevNewMs: Long?,
    ) {
        val changed: Boolean get() = newStart != oldStart
        /** Cuts into a real activity (not idle). */
        val cuts: Boolean get() = cutMs > 0 && !prevIdle
    }

    private fun floorMinute(ms: Long): Long = Math.floorDiv(ms, MINUTE) * MINUTE

    /** Rounded to the nearest minute. */
    fun roundMinute(ms: Long): Long = Math.floorDiv(ms + MINUTE / 2, MINUTE) * MINUTE

    /**
     * [cur]'s start moved to [target] (rounded to the minute), within the rules: not after [now]
     * (or one minute before [nextStart], when [cur] isn't the latest), and not at or before the
     * start of [prev]: then one minute after it, [Shift.clamped]. With no [prev], only the future
     * limit applies.
     */
    fun to(cur: Entry, prev: Entry?, target: Long, now: Long, nextStart: Long? = null): Shift {
        val hi = minOf(floorMinute(now), nextStart?.let { it - MINUTE } ?: Long.MAX_VALUE)
        var t = minOf(roundMinute(target), hi)
        var clamped = false
        if (prev != null && t <= prev.start) {
            t = prev.start + MINUTE
            clamped = true
        }
        // The entry before started less than a minute ago: there's no room either way, so nothing moves.
        if (t > hi) t = cur.start
        val prevIdle = prev != null && prev.activityId == IDLE
        return Shift(
            entryId = cur.id,
            oldStart = cur.start,
            newStart = t,
            clamped = clamped,
            cutMs = maxOf(0L, cur.start - t),
            prevIdle = prevIdle,
            prevActivityId = prev?.activityId,
            prevNewMs = prev?.let { t - it.start },
        )
    }

    /** By [minutes] (negative is earlier) from the current start. */
    fun by(cur: Entry, prev: Entry?, minutes: Int, now: Long, nextStart: Long? = null): Shift =
        to(cur, prev, cur.start + minutes * MINUTE, now, nextStart)

    /** The "Ask before cutting more than N minutes" rule: a cut of a real activity beyond [askOverMin]. */
    fun needsConfirm(s: Shift, askOverMin: Int): Boolean = askOverMin > 0 && s.cuts && s.cutMs > askOverMin * MINUTE

    /** A chip waiting for its confirming second tap. */
    data class Armed(val entryId: Long, val minutes: Int, val until: Long) {
        fun encode(): String = "$entryId|$minutes|$until"

        companion object {
            fun decode(s: String?): Armed? {
                val p = s?.split("|") ?: return null
                if (p.size != 3) return null
                return try {
                    Armed(p[0].toLong(), p[1].toInt(), p[2].toLong())
                } catch (e: NumberFormatException) {
                    null
                }
            }
        }
    }

    sealed class Tap {
        data class Apply(val shift: Shift) : Tap()
        /** First tap on a ⚠ chip: wait for a second within [ARM_MS]. */
        data class Arm(val armed: Armed) : Tap()
        /** Nothing would change. */
        object Nothing : Tap()
    }

    /** What a tap on the [minutes] chip does: apply, or arm first when it cuts more than [askOverMin]. */
    fun tap(shift: Shift, minutes: Int, askOverMin: Int, armed: Armed?, now: Long): Tap {
        if (!shift.changed) return Tap.Nothing
        if (!needsConfirm(shift, askOverMin)) return Tap.Apply(shift)
        val ok = armed != null && armed.entryId == shift.entryId && armed.minutes == minutes && now < armed.until
        return if (ok) Tap.Apply(shift) else Tap.Arm(Armed(shift.entryId, minutes, now + ARM_MS))
    }

    fun isArmed(armed: Armed?, entryId: Long, minutes: Int, now: Long): Boolean =
        armed != null && armed.entryId == entryId && armed.minutes == minutes && now < armed.until

    /** What an adjustment can undo, for [UNDO_MS], and what the head line says meanwhile. */
    data class Undo(
        val entryId: Long,
        val oldStart: Long,
        val newStart: Long,
        val until: Long,
        val cutMs: Long,
        val clamped: Boolean,
        val prevIdle: Boolean,
        val prevActivityId: Long?,
        val prevNewMs: Long?,
    ) {
        fun encode(): String = listOf(entryId, oldStart, newStart, until, cutMs, if (clamped) 1 else 0, if (prevIdle) 1 else 0,
            prevActivityId ?: "", prevNewMs ?: "").joinToString("|")

        companion object {
            fun of(s: Shift, now: Long) = Undo(s.entryId, s.oldStart, s.newStart, now + UNDO_MS, s.cutMs, s.clamped, s.prevIdle,
                s.prevActivityId, s.prevNewMs)

            fun decode(s: String?): Undo? {
                val p = s?.split("|") ?: return null
                if (p.size != 9) return null
                return try {
                    Undo(p[0].toLong(), p[1].toLong(), p[2].toLong(), p[3].toLong(), p[4].toLong(), p[5] == "1", p[6] == "1",
                        p[7].takeIf { it.isNotEmpty() }?.toLong(), p[8].takeIf { it.isNotEmpty() }?.toLong())
                } catch (e: NumberFormatException) {
                    null
                }
            }
        }
    }

    /**
     * Undo is there for [UNDO_MS] after an adjustment, and only while nothing else changed: the
     * same entry is still the current one, with the start the adjustment gave it.
     */
    fun undoLive(u: Undo?, cur: Entry?, now: Long): Boolean =
        u != null && cur != null && cur.id == u.entryId && cur.start == u.newStart && now < u.until

    // --- Labels ---

    /** "−30", "+5". */
    fun offsetText(minutes: Int): String = if (minutes < 0) "−${-minutes}" else "+$minutes"

    /**
     * A chip: "−30", then ⇤ when it would stop at the start of the entry before, else ✂ when it
     * cuts into a real activity, then ⚠ when it needs a confirming tap ("tap again" while armed).
     */
    fun chipLabel(minutes: Int, s: Shift, askOverMin: Int, armed: Boolean): String {
        val sb = StringBuilder(offsetText(minutes))
        if (s.clamped && !s.prevIdle) sb.append(" ⇤") else if (s.cuts) sb.append(" ✂")
        if (needsConfirm(s, askOverMin)) sb.append(if (armed) " ⚠ again?" else " ⚠")
        return sb.toString()
    }

    /** After applying: "✂ 15m from 🍽️ Eat", "⇤ to start of Eat"; null when there's nothing to say (idle, or later). */
    fun feedback(u: Undo, prevName: String?, prevEmoji: String): String? {
        if (u.prevIdle || prevName == null) return null
        if (u.clamped) return "⇤ to start of $prevName"
        if (u.cutMs <= 0) return null
        val who = (prevEmoji.trim() + " " + prevName).trim()
        return "✂ " + formatDuration(u.cutMs) + " from " + who
    }

    /** The panel's "Eat now 25m". */
    fun prevLength(u: Undo, prevName: String?): String? =
        if (u.prevIdle || prevName == null || u.prevNewMs == null) null else "$prevName now " + formatDuration(u.prevNewMs)

    // --- Settings ---

    /** "−5, -15, 30,  " → [-5, -15, 30]: whole minutes, not 0, at most 12 hours, distinct, at most [MAX_OFFSETS]. */
    fun parseOffsets(text: String): List<Int> =
        text.split(',', ' ', ';').map { it.trim().replace('−', '-') }.filter { it.isNotEmpty() }
            .mapNotNull { it.removePrefix("+").toIntOrNull() }
            .filter { it != 0 && it in -720..720 }
            .distinct().take(MAX_OFFSETS)

    fun offsetsText(offsets: List<Int>): String = offsets.joinToString(", ")

    /**
     * The diary days an adjustment from [oldStart] to [newStart] changes: every day from the earlier
     * to the later one (the entry before ends, and this one starts, somewhere in there).
     */
    fun daysTouched(oldStart: Long, newStart: Long, tz: TimeZone = TimeZone.getDefault()): List<Long> {
        val lo = DiaryDay.start(minOf(oldStart, newStart), tz)
        val hi = DiaryDay.start(maxOf(oldStart, newStart), tz)
        val out = ArrayList<Long>()
        var d = lo
        while (d <= hi && out.size < 62) {
            out.add(d)
            d = DiaryDay.add(d, 1, tz)
        }
        return out
    }
}
