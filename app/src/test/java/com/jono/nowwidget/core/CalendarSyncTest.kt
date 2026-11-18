package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A calendar store in memory, with the faults the real one can have. */
class FakeCalendar : CalProvider {
    class Ev(var calendarId: Long, var description: String, var from: Long, var to: Long, var title: String, var colorKey: String?, var deleted: Boolean = false)

    val events = LinkedHashMap<Long, Ev>()
    var nextId = 100L
    val calls = ArrayList<String>()
    var failDelete = false
    var failInsert = false
    /** Color keys the account doesn't have. */
    var badColors = setOf<String>()
    /** Reads miss these ids (a glitch). */
    var hide = setOf<Long>()

    fun own(cal: Long, description: String, from: Long = 0L, to: Long = 0L): Long {
        val id = nextId++
        events[id] = Ev(cal, description, from, to, "theirs", null)
        return id
    }

    override fun read(ids: Collection<Long>): Map<Long, SeenEvent> =
        ids.mapNotNull { id -> events[id]?.takeIf { id !in hide }?.let { id to SeenEvent(id, it.calendarId, it.description, it.deleted, it.from, it.to) } }.toMap()

    override fun findMarked(calendarId: Long, from: Long, to: Long): List<SeenEvent> {
        calls.add("find $calendarId")
        return events.filter { (_, e) -> e.calendarId == calendarId && !e.deleted && e.from >= from && e.from < to && e.description.contains("now-log:") }
            .map { (id, e) -> SeenEvent(id, e.calendarId, e.description, false, e.from, e.to) }
    }

    override fun insert(d: DesiredEvent): Long {
        calls.add("insert ${d.key}")
        if (failInsert) throw IllegalStateException("insert failed")
        if (d.colorKey != null && d.colorKey in badColors) throw IllegalArgumentException("no such color")
        val id = nextId++
        events[id] = Ev(d.calendarId, d.description, d.from, d.to, d.title, d.colorKey)
        return id
    }

    override fun update(eventId: Long, d: DesiredEvent) {
        calls.add("update $eventId")
        if (d.colorKey != null && d.colorKey in badColors) throw IllegalArgumentException("no such color")
        val e = events[eventId] ?: throw IllegalStateException("no event")
        e.description = d.description
        e.title = d.title
        e.from = d.from
        e.to = d.to
        e.colorKey = d.colorKey
    }

    override fun delete(eventId: Long) {
        calls.add("delete $eventId")
        if (failDelete) throw IllegalStateException("delete failed")
        events[eventId]?.deleted = true
    }
}

class FakeLedger : CalLedger {
    val records = LinkedHashMap<String, CalRecord>()
    val released = LinkedHashMap<String, CalendarOwnership.Verdict>()
    val left = ArrayList<CalRecord>()
    val dirty = HashMap<String, Int>()

    override fun gen(day: String) = dirty[day] ?: 0
    override fun clearIfUnchanged(day: String, gen: Int): Boolean {
        if ((dirty[day] ?: 0) == gen) dirty[day] = 0
        return (dirty[day] ?: 0) == 0
    }
    override fun records(day: String) = records.values.filter { CalendarDay.dayOf(it.key) == day }
    override fun released(day: String) = released.keys.filter { CalendarDay.dayOf(it) == day }.toSet()
    override fun save(r: CalRecord) { records[r.key] = r }
    override fun drop(key: String) { records.remove(key) }
    override fun leave(r: CalRecord) { records.remove(r.key); left.add(r) }
    override fun release(r: CalRecord, why: CalendarOwnership.Verdict) { records.remove(r.key); released[r.key] = why }
}

class CalendarSyncTest {
    private val log = 10L
    private val day = "2025-4-9"
    private val dayStart = 1_000L * 60_000L
    private val dayEnd = dayStart + 24 * 60 * 60_000L
    private val now = dayEnd + 60_000L

    private fun want(n: Int, title: String = "t", color: String? = "5") =
        DesiredEvent("$day/e$n", log, title, "notes\n\nnow-log:$day/e$n", dayStart + n * 3_600_000L, dayStart + n * 3_600_000L + 1_800_000L, color, "UTC")

    private fun run(p: FakeCalendar, l: FakeLedger, desired: List<DesiredEvent>, at: Long = now, errors: MutableList<String> = ArrayList(), refused: (DesiredEvent) -> Unit = {}) =
        CalendarSync.writeDay(p, l, day, dayStart, dayEnd, desired, setOf(log), at, errors, refused)

    @Test
    fun writesThenKeepsThenUpdatesThenDeletes() {
        val p = FakeCalendar()
        val l = FakeLedger()
        l.dirty[day] = 1
        val a = want(1)
        val b = want(2)
        val r1 = run(p, l, listOf(a, b))
        assertEquals(2, r1.changed)
        assertTrue(r1.cleared)
        assertEquals(listOf("find 10", "insert $day/e1", "insert $day/e2"), p.calls)
        assertEquals(2, p.events.size)
        // Same again: nothing written, no lookups (every key has a record that holds up).
        p.calls.clear()
        l.dirty[day] = 2
        val r2 = run(p, l, listOf(a, b))
        assertEquals(0, r2.changed)
        assertTrue(p.calls.isEmpty())
        // An edit: one update. A run gone from the log: its event deleted.
        p.calls.clear()
        run(p, l, listOf(a.copy(title = "new")))
        assertEquals(listOf("update ${l.records.getValue(a.key).eventId}", "delete ${p.events.keys.last()}"), p.calls)
        assertEquals(setOf(a.key), l.records.keys)
    }

    @Test
    fun crashAfterInsertIsAdoptedNotDuplicated() {
        val p = FakeCalendar()
        val l = FakeLedger()
        val a = want(1)
        // The insert went through, but only the pending record was saved.
        l.save(CalRecord(a.key, log, 0L, ""))
        val id = p.insert(a)
        p.calls.clear()
        run(p, l, listOf(a))
        assertEquals(listOf("find 10", "update $id"), p.calls)
        assertEquals(id, l.records.getValue(a.key).eventId)
        assertEquals(1, p.events.size)
    }

    @Test
    fun aPendingRecordIsSavedBeforeEachInsert() {
        val p = FakeCalendar()
        val l = FakeLedger()
        p.failInsert = true
        val errors = ArrayList<String>()
        val r = run(p, l, listOf(want(1)), errors = errors)
        assertFalse(r.cleared)
        assertTrue(l.records.getValue("$day/e1").pending)
        assertEquals(1, errors.size)
    }

    @Test
    fun reinstallAdoptsOnlyAnExactSingleTwin() {
        val p = FakeCalendar()
        val l = FakeLedger()
        val a = want(1)
        val twin = p.own(log, "old\n\nnow-log:${a.key}", a.from, a.to)
        run(p, l, listOf(a))
        assertEquals(twin, l.records.getValue(a.key).eventId)
        assertEquals(1, p.events.size)
        // Moved by a minute: not adopted, a new one is inserted (the old one is never touched).
        val p2 = FakeCalendar()
        val l2 = FakeLedger()
        val other = p2.own(log, "old\n\nnow-log:${a.key}", a.from + 60_000L, a.to)
        run(p2, l2, listOf(a))
        assertTrue(l2.records.getValue(a.key).eventId != other)
        assertEquals("old\n\nnow-log:${a.key}", p2.events.getValue(other).description)
    }

    @Test
    fun editedEventIsReleasedAndCounted() {
        val p = FakeCalendar()
        val l = FakeLedger()
        val a = want(1)
        run(p, l, listOf(a))
        val id = l.records.getValue(a.key).eventId
        p.events.getValue(id).description = "I rewrote this"
        p.calls.clear()
        val r = run(p, l, listOf(a.copy(title = "changed")))
        assertTrue(p.calls.none { it.startsWith("insert") || it.startsWith("update") || it.startsWith("delete") })
        assertEquals(CalendarOwnership.Verdict.NO_MARKER, l.released[a.key])
        assertTrue(r.cleared)
        // And it stays released: later runs never write that key.
        p.calls.clear()
        run(p, l, listOf(a.copy(title = "again")))
        assertTrue(p.calls.isEmpty())
    }

    @Test
    fun htmlEditKeepsOwnership() {
        val p = FakeCalendar()
        val l = FakeLedger()
        val a = want(1)
        run(p, l, listOf(a))
        val id = l.records.getValue(a.key).eventId
        p.events.getValue(id).description = "<p>notes and more</p><p>now-log:${a.key}</p>"
        run(p, l, listOf(a.copy(title = "x")))
        assertEquals("x", p.events.getValue(id).title)
        assertTrue(l.released.isEmpty())
    }

    @Test
    fun aGlitchyReadDoesntDuplicate() {
        val p = FakeCalendar()
        val l = FakeLedger()
        val a = want(1)
        l.dirty[day] = 1
        run(p, l, listOf(a))
        val id = l.records.getValue(a.key).eventId
        p.hide = setOf(id)
        p.calls.clear()
        l.dirty[day] = 2
        val r = run(p, l, listOf(a))
        assertTrue(p.calls.isEmpty())
        assertFalse(r.cleared) // stays dirty until it's settled
        assertTrue(l.records.getValue(a.key).suspectSince != null)
        // Back before the wait is over: just cleared.
        p.hide = emptySet()
        run(p, l, listOf(a), at = now + 60_000L)
        assertNull(l.records.getValue(a.key).suspectSince)
        assertEquals(1, p.events.size)
    }

    @Test
    fun reallyGoneIsReplacedAfterTheWait() {
        val p = FakeCalendar()
        val l = FakeLedger()
        val a = want(1)
        run(p, l, listOf(a))
        val id = l.records.getValue(a.key).eventId
        p.events.remove(id)
        run(p, l, listOf(a))
        run(p, l, listOf(a), at = now + 5 * 60_000L)
        assertEquals(0, p.events.size)
        run(p, l, listOf(a), at = now + CalendarPlan.GONE_WAIT_MS)
        assertEquals(1, p.events.size)
        assertNull(l.records.getValue(a.key).suspectSince)
    }

    @Test
    fun aFailedDeleteStopsTheInsertForThatKey() {
        val p = FakeCalendar()
        val l = FakeLedger()
        val a = want(1)
        run(p, l, listOf(a))
        // The group moved to another (still log) calendar: delete in the old one, then insert in the new.
        val moved = a.copy(calendarId = 20L)
        p.failDelete = true
        p.calls.clear()
        val errors = ArrayList<String>()
        val r = CalendarSync.writeDay(p, l, day, dayStart, dayEnd, listOf(moved), setOf(log, 20L), now, errors)
        assertEquals(listOf("delete ${l.records.getValue(a.key).eventId}"), p.calls) // no insert after it
        assertFalse(r.cleared)
        assertEquals(log, l.records.getValue(a.key).calendarId)
    }

    @Test
    fun dirtyDayStaysDirtyWhenChangedDuringTheWrite() {
        val p = FakeCalendar()
        val l = object : CalLedger by FakeLedger() {
            var g = 1
            override fun gen(day: String) = g
            override fun clearIfUnchanged(day: String, gen: Int) = false.also { g++ }
        }
        assertTrue(run(p, FakeLedger().also { it.dirty[day] = 1 }, emptyList()).cleared)
        assertFalse(CalendarSync.writeDay(p, l, day, dayStart, dayEnd, listOf(want(1)), setOf(log), now, ArrayList()).cleared)
    }

    @Test
    fun refusedColorFallsBackAndIsReported() {
        val p = FakeCalendar()
        val l = FakeLedger()
        p.badColors = setOf("5")
        val refused = ArrayList<String>()
        run(p, l, listOf(want(1)), refused = { refused.add(it.colorKey!!) })
        assertEquals(listOf("5"), refused)
        val e = p.events.values.single()
        assertNull(e.colorKey)
        // The record holds the hash of what was written (no color)...
        assertEquals(want(1, color = null).hash, l.records.getValue("$day/e1").hash)
        // ...so once the caller stops asking for that key, nothing is rewritten.
        p.calls.clear()
        run(p, l, listOf(want(1, color = null)))
        assertTrue(p.calls.isEmpty())
    }

    @Test
    fun withColorFallbackOnlyCatchesRefusals() {
        val d = want(1)
        assertEquals("5", CalendarSync.withColorFallback(d, {}) { it.colorKey }.second.colorKey)
        var heard = 0
        val r = CalendarSync.withColorFallback(d, { heard++ }) { if (it.colorKey != null) throw IllegalArgumentException() else 7 }
        assertEquals(7, r.first)
        assertEquals(1, heard)
        var threw = false
        try {
            CalendarSync.withColorFallback(d, {}) { throw IllegalStateException() }
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun removeFromDeletesOnlyOwnEvents() {
        val p = FakeCalendar()
        val old = 30L
        val mine = p.own(old, "x\n\nnow-log:$day/e1")
        val edited = p.own(old, "rewritten")
        val elsewhere = p.own(log, "x\n\nnow-log:$day/e3")
        val records = listOf(
            CalRecord("$day/e1", old, mine, "h"),
            CalRecord("$day/e2", old, edited, "h"),
            CalRecord("$day/e3", log, elsewhere, "h"),
            CalRecord("$day/e4", old, 999L, "h"), // gone
        )
        val dropped = ArrayList<String>()
        val r = CalendarSync.removeFrom(p, records, old) { dropped.add(it.key) }
        assertEquals(CalendarSync.Removed(1, 1, 0), r)
        assertTrue(p.events.getValue(mine).deleted)
        assertFalse(p.events.getValue(edited).deleted)
        assertFalse(p.events.getValue(elsewhere).deleted)
        assertEquals(listOf("$day/e1", "$day/e2", "$day/e4"), dropped)
        // A failed delete keeps its record.
        val p2 = FakeCalendar()
        val id = p2.own(old, "now-log:$day/e1")
        p2.failDelete = true
        val kept = ArrayList<String>()
        assertEquals(CalendarSync.Removed(0, 0, 1), CalendarSync.removeFrom(p2, listOf(CalRecord("$day/e1", old, id, "h")), old) { kept.add(it.key) })
        assertTrue(kept.isEmpty())
    }

    @Test
    fun deleteTestsUsesOwnership() {
        val p = FakeCalendar()
        val t1 = p.own(log, "A test\n\nnow-log:test/1")
        val t2 = p.own(log, "edited by hand")
        val t3 = p.own(40L, "now-log:test/3") // not a log calendar any more
        val tests = listOf(
            CalRecord("test/1", log, t1, ""), CalRecord("test/2", log, t2, ""), CalRecord("test/3", 40L, t3, ""), CalRecord("test/4", log, 999L, ""),
        )
        val dropped = ArrayList<String>()
        val r = CalendarSync.deleteTests(p, tests, setOf(log)) { dropped.add(it.key) }
        assertEquals(1, r.deleted)
        assertEquals(1, r.gone)
        assertEquals(listOf(CalendarOwnership.Verdict.NO_MARKER, CalendarOwnership.Verdict.NOT_LOG_CALENDAR), r.leftAlone)
        assertEquals(listOf("test/1", "test/4"), dropped)
        assertTrue(p.events.getValue(t1).deleted)
        assertFalse(p.events.getValue(t2).deleted)
        assertFalse(p.events.getValue(t3).deleted)
    }

    @Test
    fun nextBoundaryForTheNudge() {
        val planned = listOf(PlannedEvent("a", 1, 100, 200), PlannedEvent("b", 1, 150, 300))
        assertEquals(100L, CalendarSync.nextBoundary(planned, 50))
        assertEquals(150L, CalendarSync.nextBoundary(planned, 100))
        assertEquals(200L, CalendarSync.nextBoundary(planned, 150))
        assertNull(CalendarSync.nextBoundary(planned, 300))
    }
}
