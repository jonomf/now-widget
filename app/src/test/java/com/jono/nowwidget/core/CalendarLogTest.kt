package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CalendarLogTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun t(h: Int, m: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(2025, 3, if (h < 4) 10 else 9, h, m, 0)
        }.timeInMillis

    private fun span(
        id: Long, act: Long, name: String, emoji: String, group: String, from: Long, to: Long,
        people: List<String> = emptyList(), places: List<String> = emptyList(), context: String? = null,
        running: Boolean = false,
    ) = DaySpan(id, act, name, emoji, group, from, to, people, places, context, running)

    private fun day(spans: List<DaySpan>, moments: List<DayMoment> = emptyList()) = DayData(2025, 4, 9, spans, moments, utc)

    // --- Desired events ---

    @Test
    fun sampleEvent() {
        val d = day(
            listOf(span(7, 8, "Hobby", "🎨", "Play", t(9), t(10, 40), listOf("Sam", "Dulce"), listOf("Tartine"), "2025-4 Trip Home")),
            listOf(
                DayMoment(t(9, 15), MomentKind.FEELING, 7, mood = "good", moodLevel = 2, tags = listOf("calm", "focused"), text = "Painting by the window"),
                DayMoment(t(10, 5), MomentKind.NOTE, 7, text = "Sam brought pastries\nfrom Tartine"),
            ),
        )
        val e = CalendarDay.events(d).single()
        assertEquals("2025-4-9/e7", e.key)
        assertEquals("🎨 Hobby · Sam, Dulce @ Tartine", e.title)
        assertEquals(
            "in 2025-4 Trip Home\n" +
                "9:15a 🙂 good · calm, focused — Painting by the window\n" +
                "10:05a Sam brought pastries\n" +
                "from Tartine\n" +
                "\n" +
                "now-log:2025-4-9/e7",
            e.description,
        )
        assertEquals("Play", e.group)
        assertEquals(t(9), e.from)
        assertEquals(t(10, 40), e.to)
    }

    @Test
    fun mergesBackToBackRunsAndKeysOnTheFirstEntry() {
        val d = day(listOf(
            span(3, 8, "Hobby", "🎨", "Play", t(9), t(9, 30), listOf("Sam")),
            span(4, 8, "Hobby", "🎨", "Play", t(9, 30), t(10), listOf("Sam")),
            span(5, 8, "Hobby", "🎨", "Play", t(10), t(11)),
        ))
        val es = CalendarDay.events(d)
        assertEquals(listOf("2025-4-9/e3", "2025-4-9/e5"), es.map { it.key })
        assertEquals(t(10), es[0].to)
    }

    @Test
    fun shortRunningAndIdleLeftOut() {
        val d = day(listOf(
            span(1, 2, "Eat", "🍽️", "Body", t(8), t(8, 4)),
            span(2, 5, "Work", "💼", "Work", t(9), t(9, 5)),
            span(3, 8, "Hobby", "🎨", "Play", t(10), t(12), running = true),
        ))
        assertEquals(listOf("2025-4-9/e2"), CalendarDay.events(d).map { it.key })
    }

    @Test
    fun runningPartKeepsTheWholeRunBack() {
        val d = day(listOf(
            span(3, 8, "Hobby", "🎨", "Play", t(9), t(10)),
            span(4, 8, "Hobby", "🎨", "Play", t(10), t(11), running = true),
        ))
        assertTrue(CalendarDay.events(d).isEmpty())
    }

    @Test
    fun looseMomentsInsideARunCount_attachedElsewhereDont() {
        val d = day(
            listOf(span(1, 5, "Work", "💼", "Work", t(9), t(10)), span(2, 8, "Hobby", "🎨", "Play", t(10), t(11))),
            listOf(
                DayMoment(t(9, 30), MomentKind.NOTE, null, text = "loose"),
                DayMoment(t(9, 40), MomentKind.NOTE, 2, text = "edited onto hobby"),
                DayMoment(t(12), MomentKind.NOTE, null, text = "after everything"),
            ),
        )
        val es = CalendarDay.events(d)
        assertEquals("9:30a loose\n\nnow-log:2025-4-9/e1", es[0].description)
        assertEquals("9:40a edited onto hobby\n\nnow-log:2025-4-9/e2", es[1].description)
    }

    @Test
    fun titleKeepsShort() {
        val s = span(1, 1, "Friends", "🫂", "", t(9), t(10), listOf("A", "B", "C", "D", "E"), listOf("Park", "Cafe"))
        assertEquals("🫂 Friends · A, B, C +2 @ Park", CalendarDay.title(s))
        assertEquals("Read", CalendarDay.title(span(1, 1, "Read", "", "", t(9), t(10))))
    }

    @Test
    fun markers() {
        assertEquals("2025-4-9/e7", CalendarDay.markerOf("notes\n\nnow-log:2025-4-9/e7\n"))
        assertEquals("2025-4-9/e7", CalendarDay.markerOf("now-log:2025-4-9/e7"))
        assertEquals("2025-4-9/e7", CalendarDay.markerOf("x\r\n\r\nnow-log:2025-4-9/e7  "))
        assertNull(CalendarDay.markerOf(null))
        assertNull(CalendarDay.markerOf(""))
        // Text typed after the only marker is fine.
        assertEquals("2025-4-9/e7", CalendarDay.markerOf("now-log:2025-4-9/e7\nI added a line after it"))
        assertNull(CalendarDay.markerOf("see now-log:2025-4-9/e7"))
        assertNull(CalendarDay.markerOf("now-log:"))
        assertNull(CalendarDay.markerOf("now-log:2025-4-9/e7 extra"))
        assertEquals("2025-4-9", CalendarDay.dayOf("2025-4-9/e7"))
    }

    // --- Ownership ---

    private val logCal = 10L
    private val rec = CalRecord("2025-4-9/e7", logCal, 500L, "h")
    private fun seen(id: Long = 500L, cal: Long = logCal, desc: String = "x\n\nnow-log:2025-4-9/e7", deleted: Boolean = false) =
        SeenEvent(id, cal, desc, deleted)

    @Test
    fun ownershipNeedsEverything() {
        val allowed = setOf(logCal)
        assertEquals(CalendarOwnership.Verdict.OURS, CalendarOwnership.check(rec, seen(), allowed))
        assertEquals(CalendarOwnership.Verdict.NO_RECORD, CalendarOwnership.check(null, seen(), allowed))
        assertEquals(CalendarOwnership.Verdict.GONE, CalendarOwnership.check(rec, null, allowed))
        assertEquals(CalendarOwnership.Verdict.GONE, CalendarOwnership.check(rec, seen(deleted = true), allowed))
        assertEquals(CalendarOwnership.Verdict.ID_MISMATCH, CalendarOwnership.check(rec, seen(id = 501L), allowed))
        assertEquals(CalendarOwnership.Verdict.WRONG_CALENDAR, CalendarOwnership.check(rec, seen(cal = 11L), allowed + 11L))
        assertEquals(CalendarOwnership.Verdict.NOT_LOG_CALENDAR, CalendarOwnership.check(rec, seen(), setOf(11L)))
        assertEquals(CalendarOwnership.Verdict.NOT_LOG_CALENDAR, CalendarOwnership.check(rec, seen(), emptySet()))
        assertEquals(CalendarOwnership.Verdict.NO_MARKER, CalendarOwnership.check(rec, seen(desc = "my own meeting"), allowed))
        assertEquals(CalendarOwnership.Verdict.NO_MARKER, CalendarOwnership.check(rec, seen(desc = ""), allowed))
        assertEquals(CalendarOwnership.Verdict.OTHER_MARKER, CalendarOwnership.check(rec, seen(desc = "now-log:2025-4-9/e8"), allowed))
        assertEquals(CalendarOwnership.Verdict.OTHER_MARKER, CalendarOwnership.check(rec, seen(desc = "now-log:2025-4-10/e7"), allowed))
        assertTrue(CalendarOwnership.mayTouch(rec, seen(), allowed))
        assertFalse(CalendarOwnership.mayTouch(rec, seen(desc = "now-log:2025-4-9/e77"), allowed))
    }

    @Test
    fun idReusedByAnotherEventIsNotOurs() {
        // The provider handed our old id to an event someone else made: no marker, or another one.
        assertFalse(CalendarOwnership.mayTouch(rec, seen(desc = "Dentist"), setOf(logCal)))
        assertFalse(CalendarOwnership.mayTouch(rec, seen(cal = 3L, desc = "Dentist"), setOf(logCal, 3L)))
    }

    @Test
    fun markersInHtmlDescriptions() {
        // Google Calendar on the web may save an edited description as HTML.
        assertEquals("2025-4-9/e7", CalendarDay.markerOf("9:15a good<br>notes<br><br>now-log:2025-4-9/e7"))
        assertEquals("2025-4-9/e7", CalendarDay.markerOf("<p>notes</p><p>now-log:2025-4-9/e7</p>"))
        assertEquals("2025-4-9/e7", CalendarDay.markerOf("<div>a &amp; b</div><div>now-log:2025-4-9/e7&nbsp;</div>"))
        assertEquals("2025-4-9/e7", CalendarDay.markerOf("<div><b>now-log:2025-4-9/e7</b></div><div>my own words</div>"))
        assertEquals(listOf("a < b", "\"q\" 'x'"), CalendarDay.plainLines("a &lt; b<BR/>&quot;q&quot; &#39;x&#39;"))
    }

    @Test
    fun twoMarkers() {
        // The last line wins when it's a marker.
        assertEquals("2025-4-9/e8", CalendarDay.markerOf("now-log:2025-4-9/e7\nnow-log:2025-4-9/e8"))
        // Otherwise two markers mean nobody can tell: none.
        assertNull(CalendarDay.markerOf("now-log:2025-4-9/e7\nnow-log:2025-4-9/e8\nand more"))
    }

    @Test
    fun adoptionNeedsOneCandidateWithTheSameTimes() {
        val allowed = setOf(logCal)
        val d = DesiredEvent(rec.key, logCal, "t", "d", 60_000L * 100, 60_000L * 160, null, "UTC")
        val f = seen(id = 900L).copy(from = d.from + 20_000L, to = d.to) // same minute
        assertEquals(f, CalendarOwnership.adoptable(d, listOf(f), allowed, emptySet()))
        assertNull(CalendarOwnership.adoptable(d, listOf(f.copy(from = d.from + 60_000L)), allowed, emptySet()))
        assertNull(CalendarOwnership.adoptable(d, listOf(f.copy(to = d.to - 60_000L)), allowed, emptySet()))
        assertNull(CalendarOwnership.adoptable(d, listOf(f, f.copy(eventId = 901L)), allowed, emptySet()))
        assertNull(CalendarOwnership.adoptable(d, listOf(f), allowed, setOf(900L)))
        assertNull(CalendarOwnership.adoptable(d, listOf(f.copy(deleted = true)), allowed, emptySet()))
        assertNull(CalendarOwnership.adoptable(d, listOf(f.copy(calendarId = 11L)), setOf(logCal, 11L), emptySet()))
        assertNull(CalendarOwnership.adoptable(d, listOf(f), emptySet(), emptySet()))
        assertNull(CalendarOwnership.adoptable(d.copy(key = "2025-4-9/e8"), listOf(f), allowed, emptySet()))
        assertNull(CalendarOwnership.adoptable(d, emptyList(), allowed, emptySet()))
        // A pending record isn't proof of anything by itself.
        assertEquals(CalendarOwnership.Verdict.NO_RECORD, CalendarOwnership.check(rec.copy(eventId = 0L), seen(id = 0L), allowed))
    }

    // --- The plan ---

    private val now = 1_000_000_000L

    private fun want(key: String, cal: Long = logCal, title: String = "t") =
        DesiredEvent(key, cal, title, "d\n\nnow-log:$key", 60_000L * 1000, 60_000L * 1060, "5", "UTC")

    private fun recOf(d: DesiredEvent, id: Long, cal: Long = d.calendarId, hash: String = d.hash) = CalRecord(d.key, cal, id, hash)
    private fun seenOf(r: CalRecord) = SeenEvent(r.eventId, r.calendarId, "x\n\nnow-log:${r.key}", false)
    private fun twinOf(d: DesiredEvent, id: Long) = SeenEvent(id, d.calendarId, "old\n\nnow-log:${d.key}", false, d.from, d.to)

    private fun plan(
        desired: List<DesiredEvent>,
        recorded: List<CalRecord> = emptyList(),
        seen: Map<Long, SeenEvent> = recorded.associate { it.eventId to seenOf(it) },
        found: Map<String, List<SeenEvent>> = emptyMap(),
        allowed: Set<Long> = setOf(logCal),
        released: Set<String> = emptySet(),
        at: Long = now,
    ) = CalendarPlan.plan(desired, recorded, released, seen, found, allowed, at)

    @Test
    fun planInsertKeepUpdateDelete() {
        val a = want("2025-4-9/e1")
        val b = want("2025-4-9/e2")
        val c = want("2025-4-9/e3")
        val gone = want("2025-4-9/e4")
        val ra = recOf(a, 1L)
        val rb = recOf(b, 2L, hash = "old")
        val rGone = recOf(gone, 4L)
        val actions = plan(listOf(a, b.copy(title = "new"), c), listOf(ra, rb, rGone))
        assertTrue(CalendarPlan.Action.Keep(ra) in actions)
        assertTrue(CalendarPlan.Action.Update(2L, b.copy(title = "new")) in actions)
        assertTrue(CalendarPlan.Action.Insert(c) in actions)
        assertTrue(CalendarPlan.Action.Delete(rGone) in actions)
        assertEquals(4, actions.size)
    }

    @Test
    fun editedOutsideNowIsReleasedNeverReplaced() {
        val x = want("2025-4-9/e9")
        val r = recOf(x, 9L)
        val cases = mapOf(
            CalendarOwnership.Verdict.NO_MARKER to SeenEvent(9L, logCal, "the user's own notes", false),
            CalendarOwnership.Verdict.OTHER_MARKER to SeenEvent(9L, logCal, "now-log:2025-4-9/e10", false),
            CalendarOwnership.Verdict.WRONG_CALENDAR to SeenEvent(9L, 3L, "now-log:2025-4-9/e9", false),
        )
        cases.forEach { (verdict, s) ->
            val found = mapOf(x.key to listOf(twinOf(x, 70L)))
            // Still desired: released, nothing inserted, no twin adopted.
            assertEquals(listOf(CalendarPlan.Action.Release(r, verdict)), plan(listOf(x), listOf(r), mapOf(9L to s), found, setOf(logCal, 3L)))
            // Not desired any more: released too, never deleted.
            assertEquals(listOf(CalendarPlan.Action.Release(r, verdict)), plan(emptyList(), listOf(r), mapOf(9L to s), found, setOf(logCal, 3L)))
        }
        // A released key is skipped for good, even with no record.
        assertTrue(plan(listOf(x), released = setOf(x.key), found = mapOf(x.key to listOf(twinOf(x, 70L)))).isEmpty())
    }

    @Test
    fun goneWaitsBeforeAReplacement() {
        val x = want("2025-4-9/e9")
        val r = recOf(x, 9L)
        // First read as gone: only marked.
        assertEquals(listOf(CalendarPlan.Action.Suspect(r, now)), plan(listOf(x), listOf(r), emptyMap()))
        val deleted = mapOf(9L to SeenEvent(9L, logCal, "now-log:2025-4-9/e9", true))
        assertEquals(listOf(CalendarPlan.Action.Suspect(r, now)), plan(listOf(x), listOf(r), deleted))
        // Still gone, too soon: wait.
        val sus = r.copy(suspectSince = now)
        assertEquals(listOf(CalendarPlan.Action.Keep(sus)), plan(listOf(x), listOf(sus), emptyMap(), at = now + CalendarPlan.GONE_WAIT_MS - 1))
        // Still gone after the wait: a new event (never an adopted twin).
        val twin = mapOf(x.key to listOf(twinOf(x, 70L)))
        assertEquals(
            listOf(CalendarPlan.Action.Forget(sus, CalendarOwnership.Verdict.GONE), CalendarPlan.Action.Insert(x)),
            plan(listOf(x), listOf(sus), emptyMap(), twin, at = now + CalendarPlan.GONE_WAIT_MS),
        )
        // Back again (a read glitch): the suspicion is cleared, nothing else.
        assertEquals(listOf(CalendarPlan.Action.Unsuspect(sus)), plan(listOf(x), listOf(sus), mapOf(9L to seenOf(r))))
        // Gone and not wanted: just forgotten.
        assertEquals(listOf(CalendarPlan.Action.Forget(r, CalendarOwnership.Verdict.GONE)), plan(emptyList(), listOf(r), emptyMap()))
    }

    @Test
    fun changedLogCalendarLeavesOldEventsAlone() {
        val newCal = 20L
        val x = want("2025-4-9/e9", cal = newCal)
        val r = CalRecord(x.key, logCal, 9L, "h")
        val s = mapOf(9L to seenOf(r))
        assertEquals(listOf(CalendarPlan.Action.Leave(r), CalendarPlan.Action.Insert(x)), plan(listOf(x), listOf(r), s, allowed = setOf(newCal)))
        assertEquals(listOf(CalendarPlan.Action.Leave(r)), plan(emptyList(), listOf(r), s, allowed = setOf(newCal)))
        // Both still log calendars (one per area, group moved): the old one is ours and goes first.
        assertEquals(listOf(CalendarPlan.Action.Delete(r), CalendarPlan.Action.Insert(x)), plan(listOf(x), listOf(r), s, allowed = setOf(newCal, logCal)))
        // Not a log calendar and edited by hand: released, and no new event either.
        val edited = mapOf(9L to SeenEvent(9L, logCal, "rewritten by hand", false))
        assertEquals(listOf(CalendarPlan.Action.Release(r, CalendarOwnership.Verdict.NOT_LOG_CALENDAR)), plan(listOf(x), listOf(r), edited, allowed = setOf(newCal)))
    }

    @Test
    fun adoptsAfterReinstallOnlyWithNoRecordAtAll() {
        val x = want("2025-4-9/e9")
        val found = mapOf(x.key to listOf(twinOf(x, 77L)))
        assertEquals(listOf(CalendarPlan.Action.Adopt(77L, x)), plan(listOf(x), found = found))
        // Two candidates, other times, another calendar, or already somebody's record: insert.
        assertEquals(listOf(CalendarPlan.Action.Insert(x)), plan(listOf(x), found = mapOf(x.key to listOf(twinOf(x, 77L), twinOf(x, 78L)))))
        assertEquals(listOf(CalendarPlan.Action.Insert(x)), plan(listOf(x), found = mapOf(x.key to listOf(twinOf(x, 77L).copy(to = x.to + 120_000L)))))
        val y = want("2025-4-9/e1")
        val ry = recOf(y, 77L)
        assertTrue(CalendarPlan.Action.Insert(x) in plan(listOf(x, y), listOf(ry), found = found))
    }

    @Test
    fun staleRecordsNeverAdoptATwin() {
        val x = want("2025-4-9/e9")
        val r = recOf(x, 9L)
        val found = mapOf(x.key to listOf(twinOf(x, 70L)))
        // Gone (first time): suspect, no adoption.
        assertEquals(listOf(CalendarPlan.Action.Suspect(r, now)), plan(listOf(x), listOf(r), emptyMap(), found))
        // Edited: released, no adoption.
        val edited = mapOf(9L to SeenEvent(9L, logCal, "mine now", false))
        assertEquals(listOf(CalendarPlan.Action.Release(r, CalendarOwnership.Verdict.NO_MARKER)), plan(listOf(x), listOf(r), edited, found))
        // Suspect and past the wait: a fresh insert, not the twin.
        val sus = r.copy(suspectSince = now - CalendarPlan.GONE_WAIT_MS)
        assertFalse(plan(listOf(x), listOf(sus), emptyMap(), found).any { it is CalendarPlan.Action.Adopt })
    }

    @Test
    fun pendingRecordsResolveByMarker() {
        val x = want("2025-4-9/e9")
        val pending = CalRecord(x.key, logCal, 0L, "")
        // The insert happened but its id wasn't saved: that event is taken over (times may differ).
        val twin = twinOf(x, 70L).copy(from = 0L, to = 1L)
        assertEquals(listOf(CalendarPlan.Action.Adopt(70L, x)), plan(listOf(x), listOf(pending), emptyMap(), mapOf(x.key to listOf(twin))))
        // It never happened: insert.
        assertEquals(listOf(CalendarPlan.Action.Insert(x)), plan(listOf(x), listOf(pending), emptyMap()))
        // Not wanted any more: the twin (ours by marker and the pending record) is deleted, else the record goes.
        assertEquals(listOf(CalendarPlan.Action.Delete(pending.copy(eventId = 70L))), plan(emptyList(), listOf(pending), emptyMap(), mapOf(x.key to listOf(twin))))
        assertEquals(listOf(CalendarPlan.Action.Forget(pending, CalendarOwnership.Verdict.NO_RECORD)), plan(emptyList(), listOf(pending), emptyMap()))
    }

    @Test
    fun neverWritesOutsideLogCalendars() {
        val x = want("2025-4-9/e9", cal = 3L)
        assertTrue(plan(listOf(x)).isEmpty())
    }

    @Test
    fun recordCodecKeepsSuspectTime() {
        val r = CalRecord("2025-4-9/e1", 1L, 2L, "abc", 12345L)
        assertEquals(r, CalRecordCodec.read(r.key, 1L, 2L, CalRecordCodec.hashField(r)))
        val plain = r.copy(suspectSince = null)
        assertEquals("abc", CalRecordCodec.hashField(plain))
        assertEquals(plain, CalRecordCodec.read(r.key, 1L, 2L, "abc"))
        assertTrue(CalRecord("k", 1L, 0L, "").pending)
    }

    @Test
    fun hashFollowsEveryField() {
        val a = want("k")
        val hashes = setOf(
            a.hash, a.copy(title = "u").hash, a.copy(description = "e").hash, a.copy(from = 1L).hash, a.copy(to = 3L).hash,
            a.copy(colorKey = null).hash, a.copy(calendarId = 3L).hash, a.copy(timeZone = "PST").hash, a.copy(colorKey = "6").hash,
        )
        assertEquals(9, hashes.size)
    }

    // --- Colors ---

    @Test
    fun nearestEventColor() {
        // Google's event palette (a few of the 11).
        val opts = listOf(
            "1" to 0xFFA4BDFC.toInt(), "2" to 0xFF7AE7BF.toInt(), "5" to 0xFFFBD75B.toInt(),
            "9" to 0xFF5484ED.toInt(), "10" to 0xFF51B749.toInt(), "11" to 0xFFDC2127.toInt(),
        )
        assertEquals("11", EventColors.nearest(0xFFD50000.toInt(), opts))
        assertEquals("10", EventColors.nearest(0xFF0B8043.toInt(), opts))
        assertEquals("9", EventColors.nearest(0xFF3F51B5.toInt(), opts))
        assertEquals("5", EventColors.nearest(0xFFF6BF26.toInt(), opts))
        assertNull(EventColors.nearest(0xFF000000.toInt(), emptyList()))
    }
}
