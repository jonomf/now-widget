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
    /** Changes whenever anything that is written to the event changes, the color decision included. */
    val hash: String
        get() = ObsidianDay.hash(listOf(calendarId, title, description, from, to, colorKey ?: "-", timeZone).joinToString("\u0000"))
}

/**
 * What the app remembers about an event it wrote. [eventId] 0 is a pending record: saved just
 * before an insert, so an insert whose id never got saved is found again by its marker.
 * [suspectSince] is when the event was first read as gone (see [CalendarPlan.GONE_WAIT_MS]).
 */
data class CalRecord(
    val key: String,
    val calendarId: Long,
    val eventId: Long,
    val hash: String,
    val suspectSince: Long? = null,
) {
    val pending: Boolean get() = eventId == 0L
}

/** How a record's hash column holds the suspect time too: `suspect:<ms>:<hash>`. */
object CalRecordCodec {
    private const val SUSPECT = "suspect:"

    fun hashField(r: CalRecord): String = if (r.suspectSince == null) r.hash else SUSPECT + r.suspectSince + ":" + r.hash

    fun read(key: String, calendarId: Long, eventId: Long, field: String): CalRecord {
        if (field.startsWith(SUSPECT)) {
            val rest = field.removePrefix(SUSPECT)
            val since = rest.substringBefore(':').toLongOrNull()
            if (since != null) return CalRecord(key, calendarId, eventId, rest.substringAfter(':', ""), since)
        }
        return CalRecord(key, calendarId, eventId, field)
    }
}

/** An event as just read back from the phone's calendar store. */
data class SeenEvent(
    val eventId: Long,
    val calendarId: Long,
    val description: String,
    val deleted: Boolean,
    val from: Long = 0L,
    val to: Long = 0L,
)

object CalendarDay {
    /** Shorter finished runs aren't worth an event. */
    const val MIN_MS = 5L * 60_000L
    const val MARKER_PREFIX = "now-log:"
    private const val MAX_PEOPLE = 3

    fun key(dayKey: String, firstEntryId: Long): String = "$dayKey/e$firstEntryId"

    /** The day a key belongs to (`2026-11-18` from `2026-11-18/e42`). */
    fun dayOf(key: String): String = key.substringBefore('/')

    fun marker(key: String): String = MARKER_PREFIX + key

    private val BREAK = Regex("(?i)<br\\s*/?>|</p\\s*>|</div\\s*>")
    private val TAG = Regex("<[^>]*>")

    /**
     * A description as plain lines: Google Calendar on the web may save an edited description as
     * HTML, so line-break tags become line breaks, other tags go, and the common entities are decoded.
     */
    fun plainLines(description: String): List<String> {
        val text = description.replace(BREAK, "\n").replace(TAG, "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&amp;", "&").replace('\u00A0', ' ')
        return text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { it.trim() }
    }

    private fun keyOfLine(line: String): String? {
        if (!line.startsWith(MARKER_PREFIX)) return null
        val key = line.removePrefix(MARKER_PREFIX)
        if (key.isEmpty() || key.any { it.isWhitespace() }) return null
        return key
    }

    /**
     * The key in an event description's marker: the last non-blank line when it is exactly
     * `now-log:<key>`; otherwise the one marker line, when the description has exactly one (text
     * typed after it is fine). None, two or more, or a marker with extra words on its line: null.
     */
    fun markerOf(description: String?): String? {
        if (description == null) return null
        val lines = plainLines(description).filter { it.isNotEmpty() }
        val last = lines.lastOrNull() ?: return null
        keyOfLine(last)?.let { return it }
        val markers = lines.filter { it.startsWith(MARKER_PREFIX) }
        if (markers.size != 1) return null
        return keyOfLine(markers[0])
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
 * of it ([CalRecord], not a pending one); the event read back has that id, is not deleted, and sits
 * in the calendar the record says; that calendar is one the app is allowed to write ([allowed]);
 * and the event's description carries the marker for the record's key. Anything else, hands off.
 */
object CalendarOwnership {
    enum class Verdict {
        /** Ours: may be updated or deleted. */
        OURS,
        /** No record (or only a pending one): the app never wrote it, as far as it knows. */
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
        if (record == null || record.pending) return Verdict.NO_RECORD
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

    /** A marked event that could be the one an unrecorded insert made: right calendar, a log calendar, not deleted, its marker exactly [key], nobody's record. */
    fun candidate(key: String, calendarId: Long, seen: SeenEvent, allowed: Set<Long>, recordedIds: Set<Long>): Boolean =
        !seen.deleted && seen.calendarId == calendarId && calendarId in allowed &&
            seen.eventId !in recordedIds && CalendarDay.markerOf(seen.description) == key

    private fun minute(ms: Long) = Math.floorDiv(ms, 60_000L)

    /**
     * Adoption (no record at all for the key: after a reinstall): exactly one [candidate] among
     * [found], and its start and end match the run's to the minute. Anything less, insert instead.
     */
    fun adoptable(d: DesiredEvent, found: List<SeenEvent>, allowed: Set<Long>, recordedIds: Set<Long>): SeenEvent? {
        val c = found.filter { candidate(d.key, d.calendarId, it, allowed, recordedIds) }
        val one = c.singleOrNull() ?: return null
        return one.takeIf { minute(it.from) == minute(d.from) && minute(it.to) == minute(d.to) }
    }
}

/** What to do to one day's events. */
object CalendarPlan {
    /** A recorded event read as gone is only replaced if it still reads gone this long after. */
    const val GONE_WAIT_MS = 10L * 60_000L

    sealed class Action {
        /** Saves a pending record first, then inserts, then records the id. */
        data class Insert(val event: DesiredEvent) : Action()
        /** Ownership was checked. */
        data class Update(val eventId: Long, val event: DesiredEvent) : Action()
        /** An unrecorded event of ours (pending record resolved, or reinstall): write it and record it. */
        data class Adopt(val eventId: Long, val event: DesiredEvent) : Action()
        /** Ownership was checked. The record goes once it's deleted; if the delete fails, no insert for that key follows. */
        data class Delete(val record: CalRecord) : Action()
        /** The event is gone (or never got an id): only the record goes. */
        data class Forget(val record: CalRecord, val why: CalendarOwnership.Verdict) : Action()
        /** Its calendar isn't a log calendar any more: the event stays, and its record is kept aside for "Remove". */
        data class Leave(val record: CalRecord) : Action()
        /** Edited or moved outside Now: never touched again and never replaced. The record is kept so the key stays taken. */
        data class Release(val record: CalRecord, val why: CalendarOwnership.Verdict) : Action()
        /** Read as gone for the first time: wait before replacing it. */
        data class Suspect(val record: CalRecord, val since: Long) : Action()
        /** It's back (or ours after all): clear the suspicion. */
        data class Unsuspect(val record: CalRecord) : Action()
        data class Keep(val record: CalRecord) : Action()
    }

    /**
     * Brings one day in line. [desired]: the events the log wants. [recorded]: the app's records for
     * that day (pending and suspect ones included). [released]: keys of events edited outside Now,
     * which are left alone for good. [seen]: recorded events as read back now, by id (missing means
     * gone). [found]: marked events in the log calendars by the key in their marker. [allowed]: the
     * log calendars. Deletes and updates only act on events [CalendarOwnership.check] says are ours.
     */
    fun plan(
        desired: List<DesiredEvent>,
        recorded: List<CalRecord>,
        released: Set<String>,
        seen: Map<Long, SeenEvent>,
        found: Map<String, List<SeenEvent>>,
        allowed: Set<Long>,
        now: Long,
    ): List<Action> {
        val out = ArrayList<Action>()
        val records = recorded.associateBy { it.key }
        val recordedIds = recorded.filter { !it.pending }.map { it.eventId }.toSet()
        // Never write into a calendar that isn't a log calendar, whatever the caller asked.
        val usable = desired.filter { it.calendarId in allowed && it.key !in released }.distinctBy { it.key }
        val wanted = usable.associateBy { it.key }

        /** A pending record's insert may have happened: the lowest-id candidate is that event. */
        fun pendingTwin(r: CalRecord): SeenEvent? =
            found[r.key].orEmpty().filter { CalendarOwnership.candidate(r.key, r.calendarId, it, allowed, recordedIds) }
                .minByOrNull { it.eventId }

        /** A record whose event shouldn't stay where it is. True when a replacement may follow. */
        fun retire(r: CalRecord): Boolean {
            if (r.pending) {
                val twin = pendingTwin(r)
                if (twin != null) out.add(Action.Delete(r.copy(eventId = twin.eventId)))
                else out.add(Action.Forget(r, CalendarOwnership.Verdict.NO_RECORD))
                return true
            }
            when (val v = CalendarOwnership.check(r, seen[r.eventId], allowed)) {
                CalendarOwnership.Verdict.OURS -> out.add(Action.Delete(r))
                CalendarOwnership.Verdict.GONE -> out.add(Action.Forget(r, v))
                CalendarOwnership.Verdict.NOT_LOG_CALENDAR -> {
                    // Still ours by every other test, but in a calendar you no longer log to: leave it.
                    if (CalendarOwnership.check(r, seen[r.eventId], allowed + r.calendarId) == CalendarOwnership.Verdict.OURS) {
                        out.add(Action.Leave(r))
                    } else {
                        out.add(Action.Release(r, v))
                        return false
                    }
                }
                else -> {
                    out.add(Action.Release(r, v))
                    return false
                }
            }
            return true
        }

        usable.forEach { d ->
            val r = records[d.key]
            if (r == null) {
                val twin = CalendarOwnership.adoptable(d, found[d.key].orEmpty(), allowed, recordedIds)
                out.add(if (twin != null) Action.Adopt(twin.eventId, d) else Action.Insert(d))
                return@forEach
            }
            if (r.pending) {
                val twin = if (r.calendarId == d.calendarId) pendingTwin(r) else null
                if (twin != null) {
                    out.add(Action.Adopt(twin.eventId, d))
                } else {
                    if (r.calendarId != d.calendarId && retire(r).not()) return@forEach
                    out.add(Action.Insert(d))
                }
                return@forEach
            }
            if (r.calendarId != d.calendarId) {
                if (retire(r)) out.add(Action.Insert(d))
                return@forEach
            }
            when (val v = CalendarOwnership.check(r, seen[r.eventId], allowed)) {
                CalendarOwnership.Verdict.OURS -> when {
                    r.hash != d.hash -> out.add(Action.Update(r.eventId, d))
                    r.suspectSince != null -> out.add(Action.Unsuspect(r))
                    else -> out.add(Action.Keep(r))
                }
                CalendarOwnership.Verdict.GONE -> {
                    val since = r.suspectSince
                    when {
                        since == null -> out.add(Action.Suspect(r, now))
                        now - since >= GONE_WAIT_MS -> {
                            out.add(Action.Forget(r, v))
                            out.add(Action.Insert(d))
                        }
                        else -> out.add(Action.Keep(r))
                    }
                }
                CalendarOwnership.Verdict.NOT_LOG_CALENDAR -> {
                    out.add(Action.Leave(r))
                    out.add(Action.Insert(d))
                }
                else -> out.add(Action.Release(r, v))
            }
        }
        recorded.filter { it.key !in wanted && it.key !in released }.forEach { r ->
            // A desired key that was dropped above (not a log calendar) still gets its record retired.
            retire(r)
        }
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
