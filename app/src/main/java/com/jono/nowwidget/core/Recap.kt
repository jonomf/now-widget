package com.jono.nowwidget.core

import java.util.Calendar
import java.util.TimeZone

/** The Recap settings that change what counts as something to fix. */
data class RecapSettings(
    /** An untracked stretch this long or longer is a gap. */
    val minGapMinutes: Int = 30,
    /** Entries of [Recap.NO_PLACE_MIN_MS] or more with no place count as unlabelled. */
    val countNoPlace: Boolean = true,
)

/** One row of a day's recap, in time order. */
sealed class RecapRow {
    abstract val from: Long
    abstract val to: Long

    /** Something logged that needs nothing. */
    data class Logged(val entry: Entry, override val from: Long, override val to: Long) : RecapRow()

    /**
     * Nothing logged (idle) for [from]–[to]. [headIdle]: the idle entry that starts exactly at [from]
     * (filling the gap changes it), if any. [open]: it runs until now (the latest entry is idle).
     * [inner]: the other idle entries that start inside it (back-to-back idle entries), oldest first.
     */
    data class Gap(
        override val from: Long,
        override val to: Long,
        val headIdle: Long?,
        val open: Boolean,
        val inner: List<IdleRef> = emptyList(),
    ) : RecapRow()

    /** An idle entry by id and start. */
    data class IdleRef(val id: Long, val start: Long)

    /** Logged, but on ✏️ Other with no words ([noTitle]) and/or with no place ([noPlace]). */
    data class Unlabelled(
        val entry: Entry,
        override val from: Long,
        override val to: Long,
        val noTitle: Boolean,
        val noPlace: Boolean,
    ) : RecapRow()
}

/** A diary day's recap: its rows and whether it has a mood yet. */
data class DayRecap(
    val dayStart: Long,
    val dayEnd: Long,
    val rows: List<RecapRow>,
    /** No feeling moment in the day at all (the "How was today?" row). */
    val moodMissing: Boolean,
    /** When a mood saved from the recap goes: the end of the last thing logged, or now if earlier. */
    val moodAt: Long,
    /** Anything non-idle was logged this day. */
    val logged: Boolean,
) {
    val gaps: List<RecapRow.Gap> get() = rows.filterIsInstance<RecapRow.Gap>()
    val unlabelled: List<RecapRow.Unlabelled> get() = rows.filterIsInstance<RecapRow.Unlabelled>()
    /** Gaps that have ended (between entries), not the stretch running until now. */
    val closedGaps: List<RecapRow.Gap> get() = gaps.filter { !it.open }
    val nothingToFix: Boolean get() = gaps.isEmpty() && unlabelled.isEmpty() && !moodMissing
    /**
     * The evening notification shows only when this is true: a closed gap or an unlabelled entry.
     * The open stretch until now and a missing mood don't count on their own.
     */
    val notify: Boolean get() = logged && (closedGaps.isNotEmpty() || unlabelled.isNotEmpty())
}

/** Which part of a gap a fill covers. */
enum class FillPart { WHOLE, FIRST_HALF, LAST_HALF }

/**
 * What filling (part of) a gap does, so no entry outside it changes:
 * - [changeIdle]: this idle entry becomes the chosen activity (it already starts at the gap's start);
 * - [insertAt]: a new entry of the chosen activity starts here;
 * - [idleAt]: a new idle entry starts here, so the rest of the gap stays unlogged (and a gap that ran
 *   until now doesn't turn into what's on now);
 * - [delete]: the gap's other idle entries inside the filled part, which would otherwise cut it short.
 */
data class FillPlan(val changeIdle: Long?, val insertAt: Long?, val idleAt: Long?, val delete: List<Long> = emptyList())

/**
 * The evening recap's rules: gaps (stretches with nothing logged), unlabelled entries and a missing
 * day mood, for one diary day (4am–4am). No Android in here.
 */
object Recap {
    const val MIN_MS = 60_000L
    /** An idle stretch longer than this that touches midnight–6am is sleep, not a gap. */
    const val SLEEP_MS = 6L * 60 * MIN_MS
    const val SLEEP_UNTIL_HOUR = 6
    /** "No place" only counts for entries at least this long. */
    const val NO_PLACE_MIN_MS = 30L * MIN_MS
    val GAP_CHOICES = listOf(15, 30, 60)
    /** 9:30pm, as minutes after midnight. */
    const val DEFAULT_TIME = 21 * 60 + 30

    private data class Seg(val entry: Entry, val from: Long, val to: Long, val last: Boolean) {
        val idle: Boolean get() = entry.activityId == IDLE
    }

    /**
     * The recap of the diary day [dayStart, dayEnd).
     * - [entries]: oldest first, starting with the one running at [dayStart] (earlier ones are fine),
     *   with their links; an entry runs until the next starts, the last until [now].
     * - [moments]: the day's notes and feelings.
     * - [otherId]: the ✏️ Other activity, if there is one. [placeIds]: every place link.
     *
     * Gaps are idle stretches between the start of the first non-idle entry and the end of the last
     * one (today: until now) of at least [RecapSettings.minGapMinutes], except sleep ([sleepLike]).
     * Unlabelled: finished entries on Other with no title, and (setting) entries of 30+ min with no place.
     */
    fun build(
        dayStart: Long,
        dayEnd: Long,
        entries: List<Entry>,
        moments: List<Moment>,
        now: Long,
        otherId: Long?,
        placeIds: Set<Long>,
        settings: RecapSettings = RecapSettings(),
        tz: TimeZone = TimeZone.getDefault(),
    ): DayRecap {
        val limit = minOf(dayEnd, now)
        val today = now < dayEnd
        val segs = ArrayList<Seg>()
        entries.forEachIndexed { i, e ->
            val end = if (i + 1 < entries.size) entries[i + 1].start else limit
            val a = maxOf(dayStart, e.start)
            val b = minOf(end, limit)
            if (b > a) segs.add(Seg(e, a, b, i == entries.lastIndex))
        }
        val busy = segs.filter { !it.idle }
        val hasMood = moments.any { it.kind == MomentKind.FEELING && it.ts >= dayStart && it.ts < dayEnd }
        val moodAt = minOf(busy.lastOrNull()?.to ?: now, now, dayEnd - 1).coerceAtLeast(dayStart)
        if (busy.isEmpty()) return DayRecap(dayStart, dayEnd, emptyList(), !hasMood, moodAt, logged = false)

        val rangeFrom = busy.first().from
        val rangeTo = if (today) limit else busy.last().to
        val minGap = maxOf(1, settings.minGapMinutes) * MIN_MS
        val rows = ArrayList<RecapRow>()

        // Non-idle entries: logged or unlabelled.
        busy.forEach { s ->
            val running = s.last && today && s.to == limit
            val noTitle = otherId != null && s.entry.activityId == otherId && s.entry.title == null && !running
            val noPlace = settings.countNoPlace && s.to - s.from >= NO_PLACE_MIN_MS && s.entry.links.none { it in placeIds }
            rows.add(
                if (noTitle || noPlace) RecapRow.Unlabelled(s.entry, s.from, s.to, noTitle, noPlace)
                else RecapRow.Logged(s.entry, s.from, s.to)
            )
        }

        // Idle stretches in range, back-to-back idle entries as one.
        var i = 0
        while (i < segs.size) {
            if (!segs[i].idle) {
                i++
                continue
            }
            var j = i
            while (j + 1 < segs.size && segs[j + 1].idle && segs[j + 1].from == segs[j].to) j++
            val run = segs.subList(i, j + 1)
            val a = maxOf(run.first().from, rangeFrom)
            val b = minOf(run.last().to, rangeTo)
            if (b - a >= minGap && !sleepLike(a, b, tz)) {
                val head = run.firstOrNull { it.entry.start == a }?.entry?.id
                val open = today && run.last().last && b == limit
                val inner = run.filter { it.entry.start > a && it.entry.start < b }.map { RecapRow.IdleRef(it.entry.id, it.entry.start) }
                rows.add(RecapRow.Gap(a, b, head, open, inner))
            }
            i = j + 1
        }
        rows.sortWith(compareBy<RecapRow> { it.from }.thenBy { it.to })
        return DayRecap(dayStart, dayEnd, rows, !hasMood, moodAt, logged = true)
    }

    /** Longer than 6 hours and touching midnight–6am on some night: sleep, not a gap. */
    fun sleepLike(from: Long, to: Long, tz: TimeZone = TimeZone.getDefault()): Boolean {
        if (to - from <= SLEEP_MS) return false
        val c = Calendar.getInstance(tz)
        c.timeInMillis = from
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        while (c.timeInMillis < to) {
            val midnight = c.timeInMillis
            val c2 = c.clone() as Calendar
            c2.set(Calendar.HOUR_OF_DAY, SLEEP_UNTIL_HOUR)
            val six = c2.timeInMillis
            if (from < six && to > midnight) return true
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return false
    }

    /** The middle of a gap, on a whole minute. */
    fun middle(gap: RecapRow.Gap): Long {
        val mid = gap.from + (gap.to - gap.from) / 2
        return (mid / MIN_MS) * MIN_MS
    }

    /** Halves only make sense for a gap of two minutes or more. */
    fun canSplit(gap: RecapRow.Gap): Boolean = gap.to - gap.from >= 2 * MIN_MS && middle(gap) > gap.from && middle(gap) < gap.to

    /** What filling [part] of [gap] writes. */
    fun plan(gap: RecapRow.Gap, part: FillPart): FillPlan {
        val first = gap.headIdle
        val p = if (part != FillPart.WHOLE && !canSplit(gap)) FillPart.WHOLE else part
        val mid = middle(gap)
        // The filled span, and the gap's other idle entries in it (they'd cut the fill short).
        fun inside(from: Long, to: Long, withFrom: Boolean) =
            gap.inner.filter { (it.start > from || (withFrom && it.start == from)) && it.start < to }.map { it.id }
        val end = if (gap.open) gap.to else null
        return when (p) {
            FillPart.WHOLE ->
                if (first != null) FillPlan(first, null, end, inside(gap.from, gap.to, false))
                else FillPlan(null, gap.from, end, inside(gap.from, gap.to, true))
            FillPart.FIRST_HALF ->
                if (first != null) FillPlan(first, null, mid, inside(gap.from, mid, false))
                else FillPlan(null, gap.from, mid, inside(gap.from, mid, true))
            // The new entry starts at the middle; an idle entry starting right there would sit under it, so it goes too.
            FillPart.LAST_HALF -> FillPlan(null, mid, end, inside(mid, gap.to, true))
        }
    }

    /**
     * The words for the notification and the screen's top line: counts only, never what or who
     * ("3 gaps and 1 unlabelled today", then "· How was today?" without a mood). Blank when there's
     * nothing to fix.
     */
    fun summary(r: DayRecap, suffix: String = " today", closedOnly: Boolean = false): String {
        val parts = ArrayList<String>()
        val g = if (closedOnly) r.closedGaps.size else r.gaps.size
        val u = r.unlabelled.size
        if (g > 0) parts.add(if (g == 1) "1 gap" else "$g gaps")
        if (u > 0) parts.add("$u unlabelled")
        val counts = if (parts.isEmpty()) "" else parts.joinToString(" and ") + suffix
        return when {
            !r.moodMissing -> counts
            counts.isEmpty() -> "How was today?"
            else -> "$counts · How was today?"
        }
    }

    /** The next time the recap is due: at [minuteOfDay] today, or tomorrow when that's past (a minute's margin). */
    fun nextAt(now: Long, minuteOfDay: Int, tz: TimeZone = TimeZone.getDefault()): Long {
        val m = minuteOfDay.coerceIn(0, 24 * 60 - 1)
        val c = Calendar.getInstance(tz)
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, m / 60)
        c.set(Calendar.MINUTE, m % 60)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= now + MIN_MS) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** A run that was due this recently but didn't happen (phone off, app opened first) still runs. */
    const val CATCH_UP_MS = 2L * 60 * MIN_MS

    /** When the recap job should run: at [runAt], for the time it was due, [due]. */
    data class Next(val runAt: Long, val due: Long)

    /**
     * Where to set the recap job. [fresh]: after a run or a settings change, the next time from now.
     * Otherwise (app open, boot, update) keep a run that's still [pending] for a later [due], and run
     * one that was missed by under [CATCH_UP_MS] right away. Null: keep what's set.
     */
    fun next(
        now: Long, minuteOfDay: Int, due: Long, lastRun: Long, pending: Boolean, fresh: Boolean,
        tz: TimeZone = TimeZone.getDefault(), slackMs: Long = 15L * MIN_MS,
    ): Next? {
        // The run for [due] is going on right now (it set lastRun moments ago): leave it be.
        if (!fresh && due > 0L && lastRun >= due - slackMs && now - lastRun < MIN_MS) return null
        if (!fresh && due > 0L) {
            if (due <= now && lastRun < due && now - due < CATCH_UP_MS) return Next(now, due)
            if (due > now && pending) return null
        }
        val at = nextAt(now, minuteOfDay, tz)
        return Next(at, at)
    }

    /** Activity ids best first by [scores] (what he usually does at that time), then his own order. */
    fun rankActs(acts: List<Act>, scores: Map<Long, Double>): List<Act> =
        acts.filter { !it.archived && it.id != IDLE }
            .sortedWith(compareByDescending<Act> { scores[it.id] ?: 0.0 }.thenBy { it.pos }.thenBy { it.id })
}
