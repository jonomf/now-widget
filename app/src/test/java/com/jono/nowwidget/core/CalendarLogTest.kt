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
        assertNull(CalendarDay.markerOf("now-log:2025-4-9/e7\nI added a line after it"))
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
    fun adoptionRules() {
        val allowed = setOf(logCal)
        val f = seen(id = 900L)
        assertTrue(CalendarOwnership.mayAdopt(rec.key, logCal, f, allowed, emptySet()))
        assertFalse(CalendarOwnership.mayAdopt(rec.key, logCal, f, allowed, setOf(900L)))
        assertFalse(CalendarOwnership.mayAdopt(rec.key, logCal, f.copy(deleted = true), allowed, emptySet()))
        assertFalse(CalendarOwnership.mayAdopt(rec.key, 11L, f, setOf(logCal, 11L), emptySet()))
        assertFalse(CalendarOwnership.mayAdopt(rec.key, logCal, f, emptySet(), emptySet()))
        assertFalse(CalendarOwnership.mayAdopt("2025-4-9/e8", logCal, f, allowed, emptySet()))
        assertFalse(CalendarOwnership.mayAdopt(rec.key, logCal, null, allowed, emptySet()))
    }

    // --- The plan ---

    private fun want(key: String, cal: Long = logCal, title: String = "t") =
        DesiredEvent(key, cal, title, "d\n\nnow-log:$key", 1000L, 2000L, "5", "UTC")

    private fun recOf(d: DesiredEvent, id: Long, cal: Long = d.calendarId, hash: String = d.hash) = CalRecord(d.key, cal, id, hash)
    private fun seenOf(r: CalRecord) = SeenEvent(r.eventId, r.calendarId, "x\n\nnow-log:${r.key}", false)

    @Test
    fun planInsertKeepUpdateDelete() {
        val a = want("2025-4-9/e1")
        val b = want("2025-4-9/e2")
        val c = want("2025-4-9/e3")
        val gone = want("2025-4-9/e4")
        val ra = recOf(a, 1L)
        val rb = recOf(b, 2L, hash = "old")
        val rGone = recOf(gone, 4L)
        val actions = CalendarPlan.plan(
            listOf(a, b.copy(title = "new"), c),
            listOf(ra, rb, rGone),
            listOf(ra, rb, rGone).associate { it.eventId to seenOf(it) },
            emptyMap(),
            setOf(logCal),
        )
        assertTrue(CalendarPlan.Action.Keep(ra) in actions)
        assertTrue(CalendarPlan.Action.Update(2L, b.copy(title = "new")) in actions)
        assertTrue(CalendarPlan.Action.Insert(c) in actions)
        assertTrue(CalendarPlan.Action.Delete(rGone) in actions)
        assertEquals(4, actions.size)
    }

    @Test
    fun neverDeletesWhatIsntOurs() {
        val x = want("2025-4-9/e9")
        val r = recOf(x, 9L)
        val cases = mapOf(
            CalendarOwnership.Verdict.GONE to null,
            CalendarOwnership.Verdict.NO_MARKER to SeenEvent(9L, logCal, "the user's own notes", false),
            CalendarOwnership.Verdict.OTHER_MARKER to SeenEvent(9L, logCal, "now-log:2025-4-9/e10", false),
            CalendarOwnership.Verdict.WRONG_CALENDAR to SeenEvent(9L, 3L, "now-log:2025-4-9/e9", false),
        )
        cases.forEach { (verdict, s) ->
            val seenMap = if (s == null) emptyMap() else mapOf(9L to s)
            // Not desired any more:
            assertEquals(listOf(CalendarPlan.Action.Forget(r, verdict)), CalendarPlan.plan(emptyList(), listOf(r), seenMap, emptyMap(), setOf(logCal, 3L)))
            // Still desired: a new event, the record of the old one dropped, nothing touched.
            assertEquals(
                listOf(CalendarPlan.Action.Forget(r, verdict), CalendarPlan.Action.Insert(x)),
                CalendarPlan.plan(listOf(x), listOf(r), seenMap, emptyMap(), setOf(logCal, 3L)),
            )
        }
        // Deleted by you in Google Calendar: it comes back as a new event, the old row isn't touched.
        val deleted = mapOf(9L to SeenEvent(9L, logCal, "now-log:2025-4-9/e9", true))
        assertEquals(
            listOf(CalendarPlan.Action.Forget(r, CalendarOwnership.Verdict.GONE), CalendarPlan.Action.Insert(x)),
            CalendarPlan.plan(listOf(x), listOf(r), deleted, emptyMap(), setOf(logCal)),
        )
    }

    @Test
    fun changedLogCalendarLeavesOldEventsAlone() {
        val newCal = 20L
        val x = want("2025-4-9/e9", cal = newCal)
        val r = CalRecord(x.key, logCal, 9L, "h")
        val s = mapOf(9L to seenOf(r))
        // The old calendar is no longer a log calendar: the event stays, its record is set aside.
        assertEquals(
            listOf(CalendarPlan.Action.Leave(r), CalendarPlan.Action.Insert(x)),
            CalendarPlan.plan(listOf(x), listOf(r), s, emptyMap(), setOf(newCal)),
        )
        // Not wanted at all any more, same thing.
        assertEquals(listOf(CalendarPlan.Action.Leave(r)), CalendarPlan.plan(emptyList(), listOf(r), s, emptyMap(), setOf(newCal)))
        // Both still log calendars (one per area, group moved): the old one is ours and goes.
        assertEquals(
            listOf(CalendarPlan.Action.Delete(r), CalendarPlan.Action.Insert(x)),
            CalendarPlan.plan(listOf(x), listOf(r), s, emptyMap(), setOf(newCal, logCal)),
        )
        // Not a log calendar and not ours either: only the record goes.
        val edited = mapOf(9L to SeenEvent(9L, logCal, "rewritten by hand", false))
        assertEquals(
            listOf(CalendarPlan.Action.Forget(r, CalendarOwnership.Verdict.NOT_LOG_CALENDAR)),
            CalendarPlan.plan(emptyList(), listOf(r), edited, emptyMap(), setOf(newCal)),
        )
    }

    @Test
    fun adoptsAfterReinstallInsteadOfDuplicating() {
        val x = want("2025-4-9/e9")
        val found = mapOf(x.key to SeenEvent(77L, logCal, "old text\n\nnow-log:2025-4-9/e9", false))
        assertEquals(listOf(CalendarPlan.Action.Adopt(77L, x)), CalendarPlan.plan(listOf(x), emptyList(), emptyMap(), found, setOf(logCal)))
        // Found in another calendar, or with a different marker, or already somebody's record: insert.
        val elsewhere = mapOf(x.key to SeenEvent(77L, 3L, "now-log:2025-4-9/e9", false))
        assertEquals(listOf(CalendarPlan.Action.Insert(x)), CalendarPlan.plan(listOf(x), emptyList(), emptyMap(), elsewhere, setOf(logCal, 3L)))
        val y = want("2025-4-9/e1")
        val ry = recOf(y, 77L)
        val taken = CalendarPlan.plan(listOf(x, y), listOf(ry), mapOf(77L to seenOf(ry)), found, setOf(logCal))
        assertTrue(CalendarPlan.Action.Insert(x) in taken)
    }

    @Test
    fun staleRecordWithAMarkedTwinAdoptsTheTwin() {
        val x = want("2025-4-9/e9")
        val r = recOf(x, 9L)
        val found = mapOf(x.key to SeenEvent(70L, logCal, "now-log:2025-4-9/e9", false))
        assertEquals(
            listOf(CalendarPlan.Action.Forget(r, CalendarOwnership.Verdict.GONE), CalendarPlan.Action.Adopt(70L, x)),
            CalendarPlan.plan(listOf(x), listOf(r), emptyMap(), found, setOf(logCal)),
        )
    }

    @Test
    fun neverWritesOutsideLogCalendars() {
        val x = want("2025-4-9/e9", cal = 3L)
        assertTrue(CalendarPlan.plan(listOf(x), emptyList(), emptyMap(), emptyMap(), setOf(logCal)).isEmpty())
    }

    @Test
    fun hashFollowsEveryField() {
        val a = want("k")
        val hashes = setOf(
            a.hash, a.copy(title = "u").hash, a.copy(description = "e").hash, a.copy(from = 1L).hash, a.copy(to = 3L).hash,
            a.copy(colorKey = null).hash, a.copy(calendarId = 3L).hash, a.copy(timeZone = "PST").hash,
        )
        assertEquals(8, hashes.size)
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
