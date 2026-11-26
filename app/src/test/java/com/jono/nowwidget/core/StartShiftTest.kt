package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class StartShiftTest {
    private val m = 60_000L
    private val utc = TimeZone.getTimeZone("UTC")

    private fun t(day: Int, h: Int, min: Int = 0, sec: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(2025, 3, day, h, min, sec)
        }.timeInMillis

    private val now = t(9, 15, 0, 20)
    private val prev = Entry(1, 4, t(9, 14))            // 🍽️ Eat from 2p
    private val cur = Entry(2, 8, t(9, 14, 30))         // Hobby from 2:30p

    @Test
    fun earlierCutsIntoTheEntryBefore() {
        val s = StartShift.by(cur, prev, -15, now)
        assertEquals(t(9, 14, 15), s.newStart)
        assertEquals(15 * m, s.cutMs)
        assertFalse(s.clamped)
        assertTrue(s.cuts)
        assertEquals(15 * m, s.prevNewMs) // Eat now 15m
    }

    @Test
    fun neverDeletesTheEntryBefore() {
        val s = StartShift.by(cur, prev, -60, now)
        assertEquals(prev.start + m, s.newStart)
        assertTrue(s.clamped)
        assertEquals(29 * m, s.cutMs)
        // Exactly reaching its start clamps too.
        assertTrue(StartShift.by(cur, prev, -30, now).clamped)
        assertEquals(prev.start + m, StartShift.by(cur, prev, -30, now).newStart)
        // No entry before at all: only the future limit.
        assertEquals(t(9, 13, 30), StartShift.by(cur, null, -60, now).newStart)
    }

    @Test
    fun laterGivesTimeBackButNeverPastNowOrTheNextEntry() {
        val s = StartShift.by(cur, prev, 5, now)
        assertEquals(t(9, 14, 35), s.newStart)
        assertEquals(0L, s.cutMs)
        assertEquals(35 * m, s.prevNewMs)
        // Not into the future: now is 3:00:20p, so at most 3:00p.
        assertEquals(t(9, 15), StartShift.by(cur, prev, 90, now).newStart)
        // Not past the next entry (one minute before it).
        assertEquals(t(9, 14, 39), StartShift.by(cur, prev, 30, now, nextStart = t(9, 14, 40)).newStart)
    }

    @Test
    fun roundsToTheMinute() {
        val odd = cur.copy(start = t(9, 14, 30, 40))
        assertEquals(t(9, 14, 26), StartShift.by(odd, prev, -5, now).newStart)
        assertEquals(t(9, 14, 31), StartShift.roundMinute(t(9, 14, 30, 30)))
        assertEquals(t(9, 14, 30), StartShift.roundMinute(t(9, 14, 30, 29)))
    }

    @Test
    fun idleBeforeNeedsNoWarning() {
        val idle = Entry(1, IDLE, t(9, 12))
        val s = StartShift.by(cur, idle, -15, now)
        assertTrue(s.prevIdle)
        assertFalse(s.cuts)
        assertEquals("−15", StartShift.chipLabel(-15, s, 5, false))
        assertFalse(StartShift.needsConfirm(s, 5))
        assertNull(StartShift.feedback(StartShift.Undo.of(s, now), "Idle", ""))
        // Clamped against idle: no ⇤ either.
        assertEquals("−600", StartShift.chipLabel(-600, StartShift.by(cur, idle, -600, now), 0, false))
    }

    @Test
    fun noRoomMeansNoChange() {
        val justNow = Entry(1, 4, t(9, 14, 59, 50))
        val c = Entry(2, 8, t(9, 15))
        assertFalse(StartShift.by(c, justNow, -5, t(9, 15, 0, 30)).changed)
    }

    @Test
    fun undoOnlyWithinTheWindowAndUntilTheNextChange() {
        val s = StartShift.by(cur, prev, -15, now)
        val u = StartShift.Undo.of(s, now)
        val moved = cur.copy(start = s.newStart)
        assertTrue(StartShift.undoLive(u, moved, now + 59_000))
        assertFalse(StartShift.undoLive(u, moved, now + StartShift.UNDO_MS))
        assertFalse(StartShift.undoLive(u, moved.copy(start = moved.start - m), now)) // moved again since
        assertFalse(StartShift.undoLive(u, Entry(3, 9, now), now))                    // switched since
        assertFalse(StartShift.undoLive(u, null, now))                                // turned off
        assertEquals(u, StartShift.Undo.decode(u.encode()))
        val idleU = StartShift.Undo.of(StartShift.by(cur, null, -5, now), now)
        assertEquals(idleU, StartShift.Undo.decode(idleU.encode()))
        assertNull(StartShift.Undo.decode("junk"))
    }

    @Test
    fun confirmArming() {
        val s = StartShift.by(cur, prev, -15, now) // cuts 15m
        assertTrue(StartShift.tap(s, -15, 0, null, now) is StartShift.Tap.Apply)
        assertTrue(StartShift.tap(s, -15, 15, null, now) is StartShift.Tap.Apply) // not more than 15
        val first = StartShift.tap(s, -15, 10, null, now)
        assertTrue(first is StartShift.Tap.Arm)
        val armed = (first as StartShift.Tap.Arm).armed
        assertEquals(now + StartShift.ARM_MS, armed.until)
        assertTrue(StartShift.tap(s, -15, 10, armed, now + 4_000) is StartShift.Tap.Apply)
        assertTrue(StartShift.tap(s, -15, 10, armed, now + 5_000) is StartShift.Tap.Arm)  // too late: arms again
        assertTrue(StartShift.tap(StartShift.by(cur, prev, -20, now), -20, 10, armed, now) is StartShift.Tap.Arm) // another chip
        assertEquals(StartShift.Tap.Nothing, StartShift.tap(StartShift.by(cur, prev, 0, now), 0, 0, null, now))
        assertEquals(armed, StartShift.Armed.decode(armed.encode()))
        assertTrue(StartShift.isArmed(armed, cur.id, -15, now))
        assertFalse(StartShift.isArmed(armed, cur.id, -30, now))
    }

    @Test
    fun labels() {
        assertEquals("−15 ✂", StartShift.chipLabel(-15, StartShift.by(cur, prev, -15, now), 0, false))
        assertEquals("−60 ⇤", StartShift.chipLabel(-60, StartShift.by(cur, prev, -60, now), 0, false))
        assertEquals("−15 ✂ ⚠", StartShift.chipLabel(-15, StartShift.by(cur, prev, -15, now), 10, false))
        assertEquals("−15 ✂ ⚠ again?", StartShift.chipLabel(-15, StartShift.by(cur, prev, -15, now), 10, true))
        assertEquals("+5", StartShift.chipLabel(5, StartShift.by(cur, prev, 5, now), 10, false))
        val u = StartShift.Undo.of(StartShift.by(cur, prev, -15, now), now)
        assertEquals("✂ 15m from 🍽️ Eat", StartShift.feedback(u, "Eat", "🍽️"))
        assertEquals("Eat now 15m", StartShift.prevLength(u, "Eat"))
        val clamped = StartShift.Undo.of(StartShift.by(cur, prev, -60, now), now)
        assertEquals("⇤ to start of Eat", StartShift.feedback(clamped, "Eat", "🍽️"))
        assertEquals("Eat now 1m", StartShift.prevLength(clamped, "Eat"))
        assertNull(StartShift.feedback(StartShift.Undo.of(StartShift.by(cur, prev, 5, now), now), "Eat", "🍽️"))
    }

    @Test
    fun offsetsSetting() {
        assertEquals(listOf(-5, -15, 30), StartShift.parseOffsets("−5, -15  +30, 0, x, -15"))
        assertEquals(5, StartShift.parseOffsets("-1,-2,-3,-4,-5,-6,-7").size)
        assertEquals(emptyList<Int>(), StartShift.parseOffsets("-9999"))
        assertEquals("-5, -15, -30, -60", StartShift.offsetsText(StartShift.DEFAULT_OFFSETS))
        assertEquals(StartShift.DEFAULT_OFFSETS, StartShift.parseOffsets(StartShift.offsetsText(StartShift.DEFAULT_OFFSETS)))
    }

    @Test
    fun anAdjustmentAcross4amTouchesBothDiaryDays() {
        // Started 4:10am on the 10th; moved back 30 minutes to 3:40am, which is still the 9th's diary day.
        val c = Entry(2, 8, t(10, 4, 10))
        val p = Entry(1, 0, t(10, 1))
        val s = StartShift.by(c, p, -30, t(10, 5))
        assertEquals(t(10, 3, 40), s.newStart)
        val days = StartShift.daysTouched(s.oldStart, s.newStart, utc)
        assertEquals(listOf(DiaryDay.start(t(10, 3, 40), utc), DiaryDay.start(t(10, 4, 10), utc)), days)
        assertEquals(2, days.distinct().size)
        // Within one diary day: just that one.
        assertEquals(1, StartShift.daysTouched(t(9, 14), t(9, 15), utc).size)
    }
}
