package com.jono.nowwidget.core

/**
 * The calendar export's rules, kept free of Android so they're unit-tested: which events a diary
 * day should have, the marker that proves an event is the app's own, whether the app may touch an
 * event, and what to do to bring a day's calendar events in line with the log.
 *
 * The one rule that matters most: the app never updates or deletes an event it did not create.
 * See [CalendarOwnership].
 */

/** One event a diary day should have, before its calendar and color are known. */
data class LogEvent(
    /** Stable across re-exports: the day and the first entry of the run. */
    val key: String,
    /** The activity group, which decides the area (and so the calendar and color). Blank: ungrouped. */
    val group: String,
    val title: String,
    val description: String,
    val from: Long,
    val to: Long,
)

/** A [LogEvent] with where it goes and how it looks. */
data class DesiredEvent(
    val key: String,
    val calendarId: Long,
    val title: String,
    val description: String,
    val from: Long,
    val to: Long,
    /** A key from the account's event colors; null leaves the event in its calendar's color. */
    val colorKey: String?,
    val timeZone: String,
) {
    /** Changes whenever anything that is written to the event changes. */
    val hash: String
        get() = ObsidianDay.hash(listOf(calendarId, title, description, from, to, colorKey ?: "", timeZone).joinToString("\u0000"))
}

/** What the app remembers about an event it wrote. */
data class CalRecord(val key: String, val calendarId: Long, val eventId: Long, val hash: String)

/** An event as just read back from the phone's calendar store. */
data class SeenEvent(val eventId: Long, val calendarId: Long, val description: String, val deleted: Boolean)

object CalendarDay {
    /** Shorter finished runs aren't worth an event. */
    const val MIN_MS = 5L * 60_000L
    const val MARKER_PREFIX = "now-log:"
    private const val MAX_PEOPLE = 3

    fun key(dayKey: String, firstEntryId: Long): String = "$dayKey/e$firstEntryId"

    /** The day a key belongs to (`2026-11-18` from `2026-11-18/e42`). */
    fun dayOf(key: String): String = key.substringBefore('/')

    fun marker(key: String): String = MARKER_PREFIX + key

    /**
     * The key in an event description's marker: its last non-blank line, which must be exactly
     * `now-log:<key>`. Anything else (no marker, a marker that isn't last, extra words) is null.
     */
    fun markerOf(description: String?): String? {
        if (description == null) return null
        val last = description.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .lastOrNull { it.isNotBlank() }?.trim() ?: return null
        if (!last.startsWith(MARKER_PREFIX)) return null
        val key = last.removePrefix(MARKER_PREFIX)
        if (key.isEmpty() || key.any { it.isWhitespace() }) return null
        return key
    }

    /** `🎨 Hobby · Sam, Dulce @ Tartine`: people and place when there are any, kept short. */
    fun title(s: DaySpan): String {
        val sb = StringBuilder()
        if (s.emoji.isNotBlank()) sb.append(s.emoji.trim()).append(' ')
        sb.append(s.activity.trim())
        val people = s.people.map { it.trim() }.filter { it.isNotEmpty() }
        if (people.isNotEmpty()) {
            sb.append(" · ").append(people.take(MAX_PEOPLE).joinToString(", "))
            if (people.size > MAX_PEOPLE) sb.append(" +").append(people.size - MAX_PEOPLE)
        }
        val place = s.places.map { it.trim() }.firstOrNull { it.isNotEmpty() }
        if (place != null) sb.append(" @ ").append(place)
        return sb.toString().replace('\n', ' ')
    }

    /**
     * The events a diary day should have: one per finished run of [MIN_MS] or more, where a run is
     * back-to-back spans of the same activity with the same links (as in the Obsidian note). The
     * running activity is left out until it finishes. The description holds the run's notes and
     * check-ins with their times, then a blank line and the marker.
     */
    fun events(d: DayData): List<LogEvent> {
        val dayKey = ObsidianDay.key(d.year, d.month, d.day)
        val runs = ObsidianDay.merge(d.spans)
        val allEntries = d.spans.map { it.entryId }.toSet()
        val out = ArrayList<LogEvent>()
        runs.forEach { run ->
            val s = run.span
            if (s.running || s.to - s.from < MIN_MS) return@forEach
            val first = d.spans.filter { it.entryId in run.entries }.minByOrNull { it.from }?.entryId ?: s.entryId
            val key = key(dayKey, first)
            val moments = d.moments.filter { m ->
                val e = m.entryId
                if (e != null && e in allEntries) e in run.entries else m.ts >= s.from && m.ts < s.to
            }.sortedBy { it.ts }
            val lines = ArrayList<String>()
            if (!s.context.isNullOrBlank()) lines.add("in " + s.context.trim())
            moments.forEach { m ->
                ObsidianDay.momentText(m)?.let { text ->
                    val parts = text.split("\n")
                    lines.add(ObsidianDay.time(m.ts, d.tz) + " " + parts[0])
                    parts.drop(1).forEach { lines.add(it) }
                }
            }
            val description = if (lines.isEmpty()) marker(key) else lines.joinToString("\n") + "\n\n" + marker(key)
            out.add(LogEvent(key, s.group, title(s), description, s.from, s.to))
        }
        return out
    }
}

/**
 * May the app change or delete this event? Only when all of these hold: the app has its own record
 * of it ([CalRecord]); the event read back has that id, is not deleted, and sits in the calendar the
 * record says; that calendar is one the app is allowed to write ([allowed], the log calendars); and
 * the event's description ends with the marker for the record's key. Anything else, hands off.
 */
object CalendarOwnership {
    enum class Verdict {
        /** Ours: may be updated or deleted. */
        OURS,
        /** No record: the app never wrote it (as far as it knows). */
        NO_RECORD,
        /** The event is gone or deleted (by you, or by sync). Nothing to touch. */
        GONE,
        /** The id now belongs to some other event. */
        ID_MISMATCH,
        /** The event is in a different calendar than the record says. */
        WRONG_CALENDAR,
        /** The calendar isn't a log calendar (any more). */
        NOT_LOG_CALENDAR,
        /** The description has no marker (edited, or not ours). */
        NO_MARKER,
        /** The marker is for another key. */
        OTHER_MARKER,
    }

    fun check(record: CalRecord?, seen: SeenEvent?, allowed: Set<Long>): Verdict {
        if (record == null) return Verdict.NO_RECORD
        if (seen == null || seen.deleted) return Verdict.GONE
        if (seen.eventId != record.eventId) return Verdict.ID_MISMATCH
        if (seen.calendarId != record.calendarId) return Verdict.WRONG_CALENDAR
        if (record.calendarId !in allowed) return Verdict.NOT_LOG_CALENDAR
        val marker = CalendarDay.markerOf(seen.description) ?: return Verdict.NO_MARKER
        if (marker != record.key) return Verdict.OTHER_MARKER
        return Verdict.OURS
    }

    fun mayTouch(record: CalRecord?, seen: SeenEvent?, allowed: Set<Long>): Boolean =
        check(record, seen, allowed) == Verdict.OURS

    /**
     * An event with no record that may be taken over (after a reinstall, or a write cut short
     * between inserting the event and saving its record): in the calendar it should be in, which
     * is a log calendar, not deleted, its marker exactly [key], and no other record has its id.
     */
    fun mayAdopt(key: String, calendarId: Long, seen: SeenEvent?, allowed: Set<Long>, recordedIds: Set<Long>): Boolean =
        seen != null && !seen.deleted && seen.calendarId == calendarId && calendarId in allowed &&
            seen.eventId !in recordedIds && CalendarDay.markerOf(seen.description) == key
}

/** What to do to one day's events. */
object CalendarPlan {
    sealed class Action {
        data class Insert(val event: DesiredEvent) : Action()
        /** Ownership was checked. */
        data class Update(val eventId: Long, val event: DesiredEvent) : Action()
        /** An unrecorded event of ours (found by its marker): write it and record it. */
        data class Adopt(val eventId: Long, val event: DesiredEvent) : Action()
        /** Ownership was checked. The record goes once it's deleted. */
        data class Delete(val record: CalRecord) : Action()
        /** The event isn't ours to touch (gone, edited, moved, reused id): only the record goes. */
        data class Forget(val record: CalRecord, val why: CalendarOwnership.Verdict) : Action()
        /** Its calendar isn't a log calendar any more: the event stays, and its record is kept aside for "Remove". */
        data class Leave(val record: CalRecord) : Action()
        data class Keep(val record: CalRecord) : Action()
    }

    /**
     * Brings one day in line: [desired] are the events the log wants; [recorded] the app's records
     * for that day; [seen] the recorded events as read back now (by event id; missing means gone);
     * [found] unrecorded events in the target calendars carrying a marker for one of the desired
     * keys; [allowed] the log calendars. Deletes and updates only ever act on events that
     * [CalendarOwnership.check] says are ours.
     */
    fun plan(
        desired: List<DesiredEvent>,
        recorded: List<CalRecord>,
        seen: Map<Long, SeenEvent>,
        found: Map<String, SeenEvent>,
        allowed: Set<Long>,
    ): List<Action> {
        val out = ArrayList<Action>()
        val records = recorded.associateBy { it.key }
        val recordedIds = recorded.map { it.eventId }.toSet()
        // Never write into a calendar that isn't a log calendar, whatever the caller asked.
        val usable = desired.filter { it.calendarId in allowed }.distinctBy { it.key }
        val wanted = usable.associateBy { it.key }

        fun place(d: DesiredEvent) {
            val f = found[d.key]
            if (f != null && CalendarOwnership.mayAdopt(d.key, d.calendarId, f, allowed, recordedIds)) {
                out.add(Action.Adopt(f.eventId, d))
            } else {
                out.add(Action.Insert(d))
            }
        }

        /** A record whose event shouldn't stay where it is. */
        fun retire(r: CalRecord) {
            when (val v = CalendarOwnership.check(r, seen[r.eventId], allowed)) {
                CalendarOwnership.Verdict.OURS -> out.add(Action.Delete(r))
                CalendarOwnership.Verdict.NOT_LOG_CALENDAR -> {
                    // Still ours by every other test, but in a calendar you no longer log to: leave it.
                    if (CalendarOwnership.check(r, seen[r.eventId], allowed + r.calendarId) == CalendarOwnership.Verdict.OURS) {
                        out.add(Action.Leave(r))
                    } else {
                        out.add(Action.Forget(r, v))
                    }
                }
                else -> out.add(Action.Forget(r, v))
            }
        }

        usable.forEach { d ->
            val r = records[d.key]
            if (r == null) {
                place(d)
                return@forEach
            }
            if (r.calendarId != d.calendarId) {
                retire(r)
                place(d)
                return@forEach
            }
            when (val v = CalendarOwnership.check(r, seen[r.eventId], allowed)) {
                CalendarOwnership.Verdict.OURS ->
                    out.add(if (r.hash == d.hash) Action.Keep(r) else Action.Update(r.eventId, d))
                else -> {
                    out.add(Action.Forget(r, v))
                    place(d)
                }
            }
        }
        recorded.filter { it.key !in wanted }.forEach { retire(it) }
        return out
    }
}

/** Picking the account's event color nearest to a calendar's color. */
object EventColors {
    /**
     * The key of the option nearest to [target] (ARGB), by a weighted RGB distance that follows how
     * different colors look; null when there are no options. Ties go to the first.
     */
    fun nearest(target: Int, options: List<Pair<String, Int>>): String? =
        options.minByOrNull { distance(target, it.second) }?.first

    fun distance(a: Int, b: Int): Double {
        val r1 = (a shr 16) and 0xFF
        val g1 = (a shr 8) and 0xFF
        val b1 = a and 0xFF
        val r2 = (b shr 16) and 0xFF
        val g2 = (b shr 8) and 0xFF
        val b2 = b and 0xFF
        val rm = (r1 + r2) / 2.0
        val dr = (r1 - r2).toDouble()
        val dg = (g1 - g2).toDouble()
        val db = (b1 - b2).toDouble()
        return Math.sqrt((2.0 + rm / 256.0) * dr * dr + 4.0 * dg * dg + (2.0 + (255.0 - rm) / 256.0) * db * db)
    }
}
