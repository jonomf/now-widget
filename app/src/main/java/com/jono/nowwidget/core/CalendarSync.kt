package com.jono.nowwidget.core

/** The phone's calendar store as the export sees it. The real one is CalendarAccess; tests use a fake. */
interface CalProvider {
    /** These events as they are now, by id. An id that isn't there is gone. */
    fun read(ids: Collection<Long>): Map<Long, SeenEvent>

    /** Events in one calendar starting in [from, to) whose description has a marker, any order. */
    fun findMarked(calendarId: Long, from: Long, to: Long): List<SeenEvent>

    fun insert(d: DesiredEvent): Long
    fun update(eventId: Long, d: DesiredEvent)
    fun delete(eventId: Long)
}

/** What the export remembers. The real one is the Store (export_state and export_day). */
interface CalLedger {
    fun gen(day: String): Int
    fun clearIfUnchanged(day: String, gen: Int): Boolean
    /** This day's records, pending and suspect ones included. */
    fun records(day: String): List<CalRecord>
    /** Keys of this day's events that were edited outside Now. */
    fun released(day: String): Set<String>
    fun save(r: CalRecord)
    fun drop(key: String)
    fun leave(r: CalRecord)
    fun release(r: CalRecord, why: CalendarOwnership.Verdict)
}

/** What one day's run did. */
data class DayResult(val changed: Int, val cleared: Boolean, val actions: List<CalendarPlan.Action>)

/** The export's decisions for one day, end to end, without Android. */
object CalendarSync {
    private const val HOUR = 3_600_000L

    /**
     * Writes one day: read the change counter, read back the recorded events, look for marked
     * events only where some key has no record that holds up (or a pending one), plan, apply in
     * order, and clear the day only when every action worked and nothing changed it meanwhile.
     * A failed delete stops the insert for the same key. [refusedColor] hears about a color key the
     * provider refused (the event is then written without one, see [withColorFallback]).
     */
    fun writeDay(
        p: CalProvider,
        ledger: CalLedger,
        day: String,
        dayStart: Long,
        dayEnd: Long,
        desired: List<DesiredEvent>,
        allowed: Set<Long>,
        now: Long,
        errors: MutableList<String>,
        refusedColor: (DesiredEvent) -> Unit = {},
    ): DayResult {
        val gen = ledger.gen(day)
        val records = ledger.records(day)
        val released = ledger.released(day)
        if (desired.isEmpty() && records.isEmpty()) {
            return DayResult(0, ledger.clearIfUnchanged(day, gen), emptyList())
        }
        val seen = p.read(records.filter { !it.pending }.map { it.eventId })
        val byKey = records.associateBy { it.key }
        val look = HashSet<Long>()
        desired.filter { it.calendarId in allowed && it.key !in released }.forEach { d ->
            val r = byKey[d.key]
            if (r == null || r.pending) look.add(d.calendarId)
        }
        records.filter { it.pending }.forEach { look.add(it.calendarId) }
        val found = HashMap<String, MutableList<SeenEvent>>()
        look.filter { it in allowed }.forEach { cal ->
            p.findMarked(cal, dayStart - HOUR, dayEnd + HOUR).forEach { s ->
                val k = CalendarDay.markerOf(s.description) ?: return@forEach
                found.getOrPut(k) { ArrayList() }.add(s)
            }
        }
        val actions = CalendarPlan.plan(desired, records, released, seen, found, allowed, now)
        val failedDeletes = HashSet<String>()
        var ok = true
        var n = 0
        actions.forEach { a ->
            try {
                when (a) {
                    is CalendarPlan.Action.Insert -> {
                        if (a.event.key in failedDeletes) return@forEach
                        val e = a.event
                        // Pending first: if the insert happens but its id isn't saved, the next run finds it by marker.
                        ledger.save(CalRecord(e.key, e.calendarId, 0L, ""))
                        val (id, written) = withColorFallback(e, refusedColor) { p.insert(it) }
                        ledger.save(CalRecord(e.key, e.calendarId, id, written.hash))
                        n++
                    }
                    is CalendarPlan.Action.Update -> {
                        val (_, written) = withColorFallback(a.event, refusedColor) { p.update(a.eventId, it) }
                        ledger.save(CalRecord(a.event.key, a.event.calendarId, a.eventId, written.hash))
                        n++
                    }
                    is CalendarPlan.Action.Adopt -> {
                        // Record first (with no hash): if the update is cut short, the next run updates it again.
                        ledger.save(CalRecord(a.event.key, a.event.calendarId, a.eventId, ""))
                        val (_, written) = withColorFallback(a.event, refusedColor) { p.update(a.eventId, it) }
                        ledger.save(CalRecord(a.event.key, a.event.calendarId, a.eventId, written.hash))
                        n++
                    }
                    is CalendarPlan.Action.Delete -> {
                        try {
                            p.delete(a.record.eventId)
                        } catch (e: Exception) {
                            failedDeletes.add(a.record.key)
                            throw e
                        }
                        ledger.drop(a.record.key)
                        n++
                    }
                    is CalendarPlan.Action.Forget -> ledger.drop(a.record.key)
                    is CalendarPlan.Action.Leave -> ledger.leave(a.record)
                    is CalendarPlan.Action.Release -> ledger.release(a.record, a.why)
                    is CalendarPlan.Action.Suspect -> ledger.save(a.record.copy(suspectSince = a.since))
                    is CalendarPlan.Action.Unsuspect -> ledger.save(a.record.copy(suspectSince = null))
                    is CalendarPlan.Action.Keep -> Unit
                }
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                ok = false
                errors.add(day + ": " + (e.message ?: e.javaClass.simpleName))
            }
        }
        // A suspect record waits for a later run, so the day stays dirty until it's settled.
        val waiting = actions.any { it is CalendarPlan.Action.Suspect || (it is CalendarPlan.Action.Keep && it.record.suspectSince != null) }
        val cleared = ok && !waiting && ledger.clearIfUnchanged(day, gen)
        return DayResult(n, cleared, actions)
    }

    /**
     * The provider refuses a color key the account doesn't have (IllegalArgumentException). Then the
     * event is written without a color and that is what's returned (and recorded), and [refused]
     * hears about it so the key isn't tried again.
     */
    fun <T> withColorFallback(d: DesiredEvent, refused: (DesiredEvent) -> Unit, write: (DesiredEvent) -> T): Pair<T, DesiredEvent> {
        if (d.colorKey == null) return write(d) to d
        return try {
            write(d) to d
        } catch (e: IllegalArgumentException) {
            refused(d)
            val plain = d.copy(colorKey = null)
            write(plain) to plain
        }
    }

    /** What "Remove log events from …" did. */
    data class Removed(val removed: Int, val leftAlone: Int, val failed: Int)

    /**
     * Deletes the app's own events from [calendarId], a calendar that is no longer a log calendar.
     * Each must pass [CalendarOwnership.check] with only that calendar allowed (the app wrote there
     * while it was one, and the record says so); anything else is left alone. [drop] is told about
     * every record that's done with (deleted, gone or not ours); a failed delete keeps its record.
     */
    fun removeFrom(p: CalProvider, records: List<CalRecord>, calendarId: Long, drop: (CalRecord) -> Unit): Removed {
        val mine = records.filter { it.calendarId == calendarId && !it.pending }
        val seen = p.read(mine.map { it.eventId })
        var removed = 0
        var alone = 0
        var failed = 0
        mine.forEach { r ->
            when (CalendarOwnership.check(r, seen[r.eventId], setOf(calendarId))) {
                CalendarOwnership.Verdict.OURS -> {
                    try {
                        p.delete(r.eventId)
                        removed++
                    } catch (e: SecurityException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        return@forEach
                    }
                }
                CalendarOwnership.Verdict.GONE -> Unit
                else -> alone++
            }
            drop(r)
        }
        return Removed(removed, alone, failed)
    }

    data class TestsDeleted(val deleted: Int, val gone: Int, val leftAlone: List<CalendarOwnership.Verdict>)

    /** Deletes every recorded test event that is still the app's own; [drop] hears which records are done with. */
    fun deleteTests(p: CalProvider, tests: List<CalRecord>, allowed: Set<Long>, drop: (CalRecord) -> Unit): TestsDeleted {
        val seen = p.read(tests.map { it.eventId })
        var deleted = 0
        var gone = 0
        val alone = ArrayList<CalendarOwnership.Verdict>()
        tests.forEach { r ->
            when (val v = CalendarOwnership.check(r, seen[r.eventId], allowed)) {
                CalendarOwnership.Verdict.OURS -> {
                    p.delete(r.eventId)
                    deleted++
                    drop(r)
                }
                CalendarOwnership.Verdict.GONE -> {
                    gone++
                    drop(r)
                }
                else -> alone.add(v)
            }
        }
        return TestsDeleted(deleted, gone, alone)
    }

    /** The next time after [now] that a planned event starts or ends: when the nudge may change. */
    fun nextBoundary(planned: List<PlannedEvent>, now: Long): Long? =
        planned.flatMap { listOf(it.from, it.to) }.filter { it > now }.minOrNull()
}
