package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Toggl in memory, with the faults the real one has. */
class FakeToggl : TogglApi {
    class Entry(val workspaceId: Long, var startSec: Long, var durationSec: Long, var tags: List<String>, var description: String, var project: String)

    val entries = LinkedHashMap<Long, Entry>()
    var nextId = 5000L
    val calls = ArrayList<String>()
    var requests = 0

    /** After this many requests every call stops with the quota (null: never). */
    var quotaAfter: Int? = null

    /** The next create is made, then its answer is lost. */
    var loseNextAnswer = false

    /** The next create never reaches Toggl (connection failed before sending). */
    var failNextCreate = false
    var rejectTags = false
    var gone = HashSet<Long>()
    var rejectDeleteOf = HashSet<Long>()
    var onCreate: () -> Unit = {}

    private fun count(what: String) {
        calls.add(what)
        requests++
        val q = quotaAfter
        if (q != null && requests > q) throw TogglStop(StopKind.QUOTA, "quota", 60_000L)
    }

    fun foreign(ws: Long, start: Long, dur: Long, tags: List<String> = emptyList()): Long {
        val id = nextId++
        entries[id] = Entry(ws, start, dur, tags, "his own", "")
        return id
    }

    override fun create(w: TogglWant, tags: Boolean): Long {
        count("create ${w.key}" + if (tags) "" else " (no tags)")
        if (failNextCreate) {
            failNextCreate = false
            throw TogglStop(StopKind.NETWORK, "offline")
        }
        if (rejectTags && tags) throw TogglRejected(400, "bad tag")
        onCreate()
        val id = nextId++
        entries[id] = Entry(w.workspaceId, w.startSec, w.durationSec, if (tags) w.tags else emptyList(), w.description, w.project)
        if (loseNextAnswer) {
            loseNextAnswer = false
            throw TogglUnknown("timed out")
        }
        return id
    }

    override fun update(workspaceId: Long, entryId: Long, w: TogglWant, tags: Boolean) {
        count("update $entryId")
        if (entryId in gone) throw TogglGone()
        if (rejectTags && tags) throw TogglRejected(400, "bad tag")
        val e = entries[entryId] ?: throw TogglGone()
        e.startSec = w.startSec
        e.durationSec = w.durationSec
        e.description = w.description
        e.project = w.project
        e.tags = if (tags) w.tags else emptyList()
    }

    override fun delete(workspaceId: Long, entryId: Long) {
        count("delete $entryId")
        if (entryId in gone) throw TogglGone()
        if (entryId in rejectDeleteOf) throw TogglRejected(400, "no")
        entries.remove(entryId) ?: throw TogglGone()
    }

    override fun entriesBetween(from: Long, to: Long): List<TogglSeen> {
        count("list")
        return entries.map { (id, e) -> TogglSeen(id, e.workspaceId, e.startSec, e.durationSec, e.tags) }
            .filter { it.startSec * 1000 in from until to }
    }
}

class FakeTogglLedger : TogglLedger {
    val records = LinkedHashMap<String, TogglRecord>()
    val released = LinkedHashMap<String, String>()
    val dirty = HashMap<String, Int>()

    override fun gen(day: String) = dirty[day] ?: 0
    override fun clearIfUnchanged(day: String, gen: Int): Boolean {
        if ((dirty[day] ?: 0) == gen) dirty[day] = 0
        return (dirty[day] ?: 0) == 0
    }
    override fun records(day: String) = records.values.filter { CalendarDay.dayOf(it.key) == day }
    override fun released(day: String) = released.keys.filter { CalendarDay.dayOf(it) == day }.toSet()
    override fun save(r: TogglRecord) { records[r.key] = r }
    override fun drop(key: String) { records.remove(key) }
    override fun release(r: TogglRecord, why: String) { records.remove(r.key); released[r.key] = why }
}

class TogglSyncTest {
    private val ws = 77L
    private val day = "2025-4-9"
    private val dayStart = 1_700_000_000_000L
    private val dayEnd = dayStart + 24 * 3_600_000L

    private fun want(n: Int, desc: String = "🎨 Hobby", workspace: Long = ws): TogglWant {
        val start = dayStart / 1000 + n * 3600L
        return TogglWant("$day/e$n", workspace, "Hobby", desc, listOf("Sam", "now"), start, start + 1800L)
    }

    private class Run(val api: FakeToggl, val ledger: FakeTogglLedger) {
        val stats = TogglStats()
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
    }

    private fun run(api: FakeToggl, l: FakeTogglLedger, desired: List<TogglWant>, r: Run = Run(api, l)): Pair<Boolean, Run> {
        val cleared = TogglSync.writeDay(api, l, day, dayStart, dayEnd, desired, r.stats, r.errors, r.warnings)
        return cleared to r
    }

    private fun writes(api: FakeToggl) = api.calls.filter { !it.startsWith("list") }

    @Test
    fun createsThenKeepsThenUpdatesThenDeletes() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        l.dirty[day] = 1
        val a = want(1)
        val b = want(2)
        val (cleared, r1) = run(api, l, listOf(a, b))
        assertTrue(cleared)
        assertEquals(2, r1.stats.created)
        assertEquals(listOf("create ${a.key}", "create ${b.key}"), api.calls)
        assertEquals(2, api.entries.size)
        // Same again: not a single request.
        api.calls.clear()
        l.dirty[day] = 2
        val (c2, _) = run(api, l, listOf(a, b))
        assertTrue(c2)
        assertTrue(api.calls.isEmpty())
        // An edit: one update. A run gone from the log: its entry deleted.
        val idA = l.records.getValue(a.key).entryId
        val idB = l.records.getValue(b.key).entryId
        run(api, l, listOf(a.copy(description = "🎨 Hobby — new")))
        assertEquals(listOf("delete $idB", "update $idA"), api.calls)
        assertEquals("🎨 Hobby — new", api.entries.getValue(idA).description)
        assertEquals(setOf(a.key), l.records.keys)
        assertEquals(setOf(idA), api.entries.keys)
    }

    @Test
    fun theSecondRunOfAnUnchangedDayNeverListsAnything() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        run(api, l, listOf(want(1)))
        api.calls.clear()
        run(api, l, listOf(want(1)))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun otherEntriesAreNeverTouched() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        // His own entries, one at the very time of a run and one with our tag from another install.
        val his = api.foreign(ws, want(1).startSec, 1800L)
        val other = api.foreign(ws, want(1).startSec, 1800L, listOf("now"))
        run(api, l, listOf(want(1)))
        run(api, l, listOf(want(1, "🎨 Hobby — changed")))
        run(api, l, emptyList())
        assertEquals(setOf(his, other), api.entries.keys)
        assertTrue(api.calls.none { it.endsWith(" $his") || it.endsWith(" $other") })
        assertTrue(api.calls.none { it == "list" })
        assertTrue(l.records.isEmpty())
    }

    @Test
    fun anEmptyDayWithNothingRecordedClearsWithoutRequests() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        l.dirty[day] = 3
        val (cleared, _) = run(api, l, emptyList())
        assertTrue(cleared)
        assertEquals(0, api.requests)
    }

    @Test
    fun aPendingRecordIsSavedBeforeEachPost() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        var seenPending: TogglRecord? = null
        api.onCreate = { seenPending = l.records["$day/e1"] }
        run(api, l, listOf(want(1)))
        val p = seenPending!!
        assertTrue(p.pending)
        assertEquals(want(1).startSec, p.pendingStart)
        assertEquals(1800L, p.pendingDur)
        assertFalse(l.records.getValue("$day/e1").pending)
    }

    // --- A POST whose answer was lost ---

    @Test
    fun lostAnswerIsAdoptedNotDuplicated() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        l.dirty[day] = 1
        val a = want(1)
        api.loseNextAnswer = true
        try {
            run(api, l, listOf(a))
            fail("the run should stop")
        } catch (e: TogglUnknown) {
            // stays pending
        }
        assertTrue(l.records.getValue(a.key).pending)
        assertEquals(1, api.entries.size)
        assertEquals(1, l.dirty[day])
        // Next run: one listing, then the entry is recorded without another POST or a PUT.
        api.calls.clear()
        val (cleared, r) = run(api, l, listOf(a))
        assertTrue(cleared)
        assertEquals(listOf("list"), api.calls)
        assertEquals(1, api.entries.size)
        assertEquals(api.entries.keys.single(), l.records.getValue(a.key).entryId)
        assertEquals(a.hash, l.records.getValue(a.key).hash)
        assertEquals(0, r.stats.created)
    }

    @Test
    fun lostAnswerForAnEditedRunIsAdoptedAndUpdated() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        val a = want(1)
        api.loseNextAnswer = true
        try { run(api, l, listOf(a)) } catch (e: TogglUnknown) { }
        api.calls.clear()
        val edited = a.copy(description = "🎨 Hobby — edited")
        run(api, l, listOf(edited))
        val id = api.entries.keys.single()
        assertEquals(listOf("list", "update $id"), api.calls)
        assertEquals("🎨 Hobby — edited", api.entries.getValue(id).description)
    }

    @Test
    fun lostAnswerThatNeverArrivedIsPostedAgain() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        val a = want(1)
        // The pending record is there, but the POST never reached Toggl.
        l.save(TogglRecord(a.key, ws, 0L, a.hash, a.startSec, a.durationSec, true))
        run(api, l, listOf(a))
        assertEquals(listOf("list", "create ${a.key}"), api.calls)
        assertEquals(1, api.entries.size)
        assertFalse(l.records.getValue(a.key).pending)
    }

    @Test
    fun onlyAnEntryWithOurTagAndTheSameStartAndDurationIsAdopted() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        val a = want(1)
        l.save(TogglRecord(a.key, ws, 0L, a.hash, a.startSec, a.durationSec, true))
        // His own entry at the same time (no tag), ours a second off, and one in another workspace.
        val his = api.foreign(ws, a.startSec, a.durationSec)
        val off = api.foreign(ws, a.startSec + 1, a.durationSec, listOf("now"))
        val elsewhere = api.foreign(99L, a.startSec, a.durationSec, listOf("now"))
        run(api, l, listOf(a))
        val id = l.records.getValue(a.key).entryId
        assertTrue(id !in setOf(his, off, elsewhere))
        assertEquals(4, api.entries.size)
        assertTrue(api.calls.none { it.endsWith(" $his") || it.endsWith(" $off") || it.endsWith(" $elsewhere") })
    }

    @Test
    fun lostAnswerOfATagLessRetryIsStillRecognised() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        val a = want(1)
        api.rejectTags = true
        api.loseNextAnswer = true
        try { run(api, l, listOf(a)) } catch (e: TogglUnknown) { }
        assertFalse(l.records.getValue(a.key).pendingTags)
        assertEquals(listOf(emptyList<String>()), api.entries.values.map { it.tags })
        api.calls.clear()
        api.rejectTags = false
        run(api, l, listOf(a))
        assertEquals(1, api.entries.size)
        assertEquals(listOf("list"), api.calls)
        assertFalse(l.records.getValue(a.key).pending)
    }

    @Test
    fun aPendingRecordForARunThatIsGoneDeletesItsEntry() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        val a = want(1)
        api.loseNextAnswer = true
        try { run(api, l, listOf(a)) } catch (e: TogglUnknown) { }
        val id = api.entries.keys.single()
        api.calls.clear()
        run(api, l, emptyList())
        assertEquals(listOf("list", "delete $id"), api.calls)
        assertTrue(api.entries.isEmpty())
        assertTrue(l.records.isEmpty())
        // And if it never reached Toggl, the record just goes.
        val l2 = FakeTogglLedger()
        l2.save(TogglRecord(a.key, ws, 0L, a.hash, a.startSec, a.durationSec, true))
        val api2 = FakeToggl()
        run(api2, l2, emptyList())
        assertEquals(listOf("list"), api2.calls)
        assertTrue(l2.records.isEmpty())
    }

    @Test
    fun aCreateThatNeverWentOutLeavesNoPendingRecord() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        api.failNextCreate = true
        try { run(api, l, listOf(want(1))) } catch (e: TogglStop) { }
        assertTrue(l.records.isEmpty())
    }

    // --- Deleted in Toggl ---

    @Test
    fun anEntryDeletedInTogglIsReleasedAndNeverMadeAgain() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        val a = want(1)
        run(api, l, listOf(a))
        val id = l.records.getValue(a.key).entryId
        api.entries.remove(id)
        // Nothing changed: we don't know, and don't ask.
        api.calls.clear()
        run(api, l, listOf(a))
        assertTrue(api.calls.isEmpty())
        // An edit: the update finds it gone.
        val (cleared, _) = run(api, l, listOf(a.copy(description = "x")))
        assertTrue(cleared)
        assertEquals(listOf("update $id"), api.calls)
        assertEquals("deleted in Toggl", l.released[a.key])
        assertTrue(l.records.isEmpty())
        // From now on that key is left alone, however the run changes.
        api.calls.clear()
        run(api, l, listOf(a.copy(description = "y")))
        run(api, l, emptyList())
        assertTrue(api.calls.isEmpty())
        assertTrue(api.entries.isEmpty())
    }

    @Test
    fun aDeleteThatFindsItGoneJustDropsTheRecord() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        val a = want(1)
        run(api, l, listOf(a))
        api.entries.clear()
        val (cleared, r) = run(api, l, emptyList())
        assertTrue(cleared)
        assertTrue(r.errors.isEmpty())
        assertTrue(l.records.isEmpty())
        assertTrue(l.released.isEmpty())
    }

    // --- A key that changes, a workspace that changes ---

    @Test
    fun aChangedKeyDeletesTheOldEntryFirstThenCreatesTheNew() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        val a = want(1)
        run(api, l, listOf(a))
        val id = l.records.getValue(a.key).entryId
        api.calls.clear()
        val moved = want(3)
        run(api, l, listOf(moved))
        assertEquals(listOf("delete $id", "create ${moved.key}"), api.calls)
        assertEquals(setOf(moved.key), l.records.keys)
        assertEquals(1, api.entries.size)
    }

    @Test
    fun aFailedDeleteHoldsBackTheReplacement() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        val a = want(1)
        run(api, l, listOf(a))
        val id = l.records.getValue(a.key).entryId
        api.rejectDeleteOf.add(id)
        api.calls.clear()
        // Same key, new workspace: the old entry can't be deleted, so no second one is made.
        val (cleared, r) = run(api, l, listOf(want(1, workspace = 88L)))
        assertFalse(cleared)
        assertEquals(listOf("delete $id"), api.calls)
        assertEquals(1, r.errors.size)
        assertEquals(id, l.records.getValue(a.key).entryId)
    }

    @Test
    fun aNewWorkspaceDeletesInTheOldOneAndCreatesInTheNew() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        run(api, l, listOf(want(1)))
        val id = l.records.getValue("$day/e1").entryId
        api.calls.clear()
        run(api, l, listOf(want(1, workspace = 88L)))
        assertEquals(listOf("delete $id", "create $day/e1"), api.calls)
        assertEquals(88L, l.records.getValue("$day/e1").workspaceId)
        assertEquals(listOf(88L), api.entries.values.map { it.workspaceId })
    }

    // --- Quota, stops and picking up where it left off ---

    @Test
    fun aStopLeavesTheDayDirtyAndTheNextRunPicksUp() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        l.dirty[day] = 1
        val all = (1..5).map { want(it) }
        api.quotaAfter = 2
        try {
            run(api, l, all)
            fail("quota should stop the run")
        } catch (e: TogglStop) {
            assertEquals(StopKind.QUOTA, e.kind)
        }
        assertEquals(2, api.entries.size)
        assertEquals(1, l.dirty[day])
        assertEquals(2, l.records.values.count { !it.pending })
        // The third was never sent, so it left no pending record behind.
        assertEquals(2, l.records.size)
        // Quota is back: the other three are made, nothing twice.
        api.quotaAfter = null
        api.calls.clear()
        val (cleared, _) = run(api, l, all)
        assertTrue(cleared)
        assertEquals(all.drop(2).map { "create ${it.key}" }, api.calls)
        assertEquals(5, api.entries.size)
        assertEquals(0, l.dirty[day])
    }

    @Test
    fun aChangeDuringTheRunKeepsTheDayDirty() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        l.dirty[day] = 1
        api.onCreate = { l.dirty[day] = 2 }
        val (cleared, _) = run(api, l, listOf(want(1)))
        assertFalse(cleared)
        assertEquals(2, l.dirty[day])
    }

    // --- Tags ---

    @Test
    fun tagsTogglRefusesAreRetriedOnceWithoutThem() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        api.rejectTags = true
        val (cleared, r) = run(api, l, listOf(want(1)))
        assertTrue(cleared)
        assertEquals(listOf("create $day/e1", "create $day/e1 (no tags)"), api.calls)
        assertEquals(1, r.warnings.size)
        assertEquals(emptyList<String>(), api.entries.values.single().tags)
        assertEquals(1, r.stats.created)
    }

    @Test
    fun otherRefusalsAreReportedAndTheNextEntryStillGoes() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        val bad = want(1)
        val good = want(2)
        var n = 0
        val failing = object : TogglApi by api {
            override fun create(w: TogglWant, tags: Boolean): Long {
                if (w.key == bad.key) throw TogglRejected(422, "no good")
                n++
                return api.create(w, tags)
            }
        }
        val r = Run(api, l)
        val cleared = TogglSync.writeDay(failing, l, day, dayStart, dayEnd, listOf(bad, good), r.stats, r.errors, r.warnings)
        assertFalse(cleared)
        assertEquals(1, r.errors.size)
        assertEquals(1, n)
        assertNull(l.records[bad.key])
        assertNotNull(l.records[good.key])
    }

    // --- Remove sent entries ---

    @Test
    fun removeDeletesOnlyRecordedEntriesOldestFirst() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        run(api, l, listOf(want(1), want(2)))
        val his = api.foreign(ws, 1L, 60L, listOf("now"))
        val recs = l.records.values.toList() + TogglRecord("2025-4-8/e1", ws, api.foreign(ws, 5L, 60L, listOf("now")), "h")
        val dropped = ArrayList<String>()
        api.calls.clear()
        val r = TogglSync.removeAll(api, recs) { dropped.add(it.key); l.drop(it.key) }
        assertEquals(3, r.removed)
        assertNull(r.stop)
        assertEquals("2025-4-8/e1", dropped.first())
        // Only his own entry is left: the ones with records, in either workspace listing, are gone.
        assertEquals(setOf(his), api.entries.keys)
        assertTrue(api.calls.none { it.endsWith(" $his") })
    }

    @Test
    fun removeStopsAtTheQuotaAndKeepsWhatIsLeft() {
        val api = FakeToggl()
        val l = FakeTogglLedger()
        run(api, l, (1..4).map { want(it) })
        api.quotaAfter = api.requests + 2
        val dropped = ArrayList<String>()
        val r = TogglSync.removeAll(api, l.records.values.toList()) { dropped.add(it.key); l.drop(it.key) }
        assertEquals(2, r.removed)
        assertEquals(StopKind.QUOTA, r.stop?.kind)
        assertEquals(2, l.records.size)
        assertEquals(2, api.entries.size)
        // The rest goes later.
        api.quotaAfter = null
        val r2 = TogglSync.removeAll(api, l.records.values.toList()) { l.drop(it.key) }
        assertEquals(2, r2.removed)
        assertTrue(l.records.isEmpty())
        assertTrue(api.entries.isEmpty())
    }

    @Test
    fun removeCountsGoneAndSkipsPending() {
        val api = FakeToggl()
        val recs = listOf(
            TogglRecord("2025-4-9/e1", ws, 123L, "h"),
            TogglRecord("2025-4-9/e2", ws, 0L, "h", 1L, 60L, true),
        )
        val dropped = ArrayList<String>()
        val r = TogglSync.removeAll(api, recs) { dropped.add(it.key) }
        assertEquals(0, r.removed)
        assertEquals(1, r.gone)
        assertEquals(1, r.unresolved)
        assertEquals(listOf("2025-4-9/e1"), dropped)
    }
}
