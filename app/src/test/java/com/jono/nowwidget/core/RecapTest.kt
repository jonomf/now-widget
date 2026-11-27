package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RecapTest {
    private val la = TimeZone.getTimeZone("America/Los_Angeles")
    private val work = 1L
    private val eat = 2L
    private val other = 9L
    private val loft = 50L
    private val sam = 60L
    private val places = setOf(loft)

    /** Nov 20 2026 (a Friday), [h]:[m] local; h ≥ 24 is the next calendar day. */
    private fun t(h: Int, m: Int = 0, day: Int = 20): Long = Calendar.getInstance(la).apply {
        clear()
        set(2026, 10, day, 0, 0, 0)
        add(Calendar.MINUTE, h * 60 + m)
    }.timeInMillis

    private val dayStart get() = t(4)
    private val dayEnd get() = t(28)
    private val late get() = t(40) // well after the day

    private var nextId = 1L
    private fun e(act: Long, at: Long, links: Set<Long> = setOf(loft), title: String? = null) =
        Entry(nextId++, act, at, links = links, title = title)

    private fun build(
        entries: List<Entry>,
        now: Long = late,
        moments: List<Moment> = emptyList(),
        settings: RecapSettings = RecapSettings(),
    ) = Recap.build(dayStart, dayEnd, entries, moments, now, other, places, settings, la)

    private fun mood(at: Long) = Moment(500, at, MomentKind.FEELING, "", "", 3L, null)

    @Test
    fun gapsAreIdleStretchesBetweenTheFirstAndLastEntry() {
        val r = build(listOf(
            e(IDLE, t(2)), // the night before: not in range
            e(work, t(9)),
            e(IDLE, t(10)), // 10–11: a gap
            e(work, t(11)),
            e(IDLE, t(12)), // 12–12:20: too short
            e(eat, t(12, 20)),
            e(IDLE, t(13)), // after the last entry on a past day: not a gap
        ))
        assertEquals(1, r.gaps.size)
        val g = r.gaps[0]
        assertEquals(t(10), g.from)
        assertEquals(t(11), g.to)
        assertFalse(g.open)
        assertEquals(3L, g.headIdle)
        assertTrue(r.logged)
    }

    @Test
    fun theMinimumGapIsASetting() {
        val entries = listOf(e(work, t(9)), e(IDLE, t(10)), e(work, t(10, 45)), e(IDLE, t(12)))
        assertEquals(1, build(entries).gaps.size)
        assertEquals(0, build(entries, settings = RecapSettings(minGapMinutes = 60)).gaps.size)
        assertEquals(1, build(entries, settings = RecapSettings(minGapMinutes = 15)).gaps.size)
    }

    @Test
    fun todayRunsUntilNowAndTheLastIdleIsAnOpenGap() {
        val now = t(21, 30)
        val r = build(listOf(e(work, t(9)), e(IDLE, t(18))), now = now)
        val g = r.gaps.single()
        assertEquals(t(18), g.from)
        assertEquals(now, g.to)
        assertTrue(g.open)
    }

    @Test
    fun aShortIdleBeforeNowIsNoGap() {
        val r = build(listOf(e(work, t(9)), e(IDLE, t(21, 10))), now = t(21, 30))
        assertTrue(r.gaps.isEmpty())
    }

    @Test
    fun onlyClosedGapsAndUnlabelledNotify() {
        val now = t(21, 30)
        // Just the open stretch until now (and no mood): shown, but no notification.
        val open = build(listOf(e(work, t(9)), e(IDLE, t(18))), now = now)
        assertEquals(1, open.gaps.size)
        assertTrue(open.closedGaps.isEmpty())
        assertFalse(open.notify)
        // A closed gap does, and the text counts only it.
        val closed = build(listOf(e(work, t(9)), e(IDLE, t(10)), e(work, t(11)), e(IDLE, t(18))), now = now)
        assertTrue(closed.notify)
        assertEquals("1 gap today · How was today?", Recap.summary(closed, closedOnly = true))
        assertEquals("2 gaps today · How was today?", Recap.summary(closed))
        // So does an unlabelled entry.
        assertTrue(build(listOf(e(work, t(9), links = emptySet()), e(IDLE, t(12))), now = now, moments = listOf(mood(t(9)))).notify)
    }

    @Test
    fun fillingBackToBackIdleEntriesRemovesTheInnerOnes() {
        // 10:00 idle (head), 10:20 idle, 10:40 idle, until 11:00.
        val r = build(listOf(e(work, t(9)), e(IDLE, t(10)), e(IDLE, t(10, 20)), e(IDLE, t(10, 40)), e(work, t(11)), e(IDLE, t(12))))
        val g = r.gaps.single()
        assertEquals(2L, g.headIdle)
        assertEquals(listOf(RecapRow.IdleRef(3, t(10, 20)), RecapRow.IdleRef(4, t(10, 40))), g.inner)
        assertEquals(FillPlan(2L, null, null, listOf(3L, 4L)), Recap.plan(g, FillPart.WHOLE))
        // First half (to 10:30): the 10:20 one goes; idle again from 10:30.
        assertEquals(FillPlan(2L, null, t(10, 30), listOf(3L)), Recap.plan(g, FillPart.FIRST_HALF))
        // Last half (from 10:30): the 10:40 one goes.
        assertEquals(FillPlan(null, t(10, 30), null, listOf(4L)), Recap.plan(g, FillPart.LAST_HALF))
        // An idle entry right at the middle goes with the last half.
        val atMid = RecapRow.Gap(t(10), t(11), 2L, false, listOf(RecapRow.IdleRef(3, t(10, 30))))
        assertEquals(listOf(3L), Recap.plan(atMid, FillPart.LAST_HALF).delete)
        assertEquals(emptyList<Long>(), Recap.plan(atMid, FillPart.FIRST_HALF).delete)
    }

    @Test
    fun backToBackIdleEntriesAreOneGap() {
        val r = build(listOf(e(work, t(9)), e(IDLE, t(10)), e(IDLE, t(10, 20)), e(work, t(10, 40))))
        val g = r.gaps.single()
        assertEquals(t(10), g.from)
        assertEquals(t(10, 40), g.to)
        assertEquals(2L, g.headIdle)
    }

    @Test
    fun sleepIsNotAGap() {
        // Up at 3:30am for a moment after going to bed at 9pm: 6.5 hours across midnight is sleep.
        val r = build(listOf(e(work, t(20)), e(IDLE, t(21)), e(eat, t(27, 30)), e(IDLE, t(27, 45))))
        assertTrue(r.gaps.isEmpty())
        // The same length in the daytime is a gap.
        val day = build(listOf(e(work, t(5)), e(IDLE, t(8)), e(work, t(14, 30)), e(IDLE, t(15))))
        assertEquals(1, day.gaps.size)
        // Early start: 5am–12pm idle touches 5–6am.
        val early = build(listOf(e(work, t(4, 30)), e(IDLE, t(5)), e(work, t(12)), e(IDLE, t(13))))
        assertTrue(early.gaps.isEmpty())
        // Under 6 hours across midnight is still a gap.
        val short = build(listOf(e(work, t(22)), e(IDLE, t(23)), e(eat, t(27)), e(IDLE, t(27, 30))))
        assertEquals(1, short.gaps.size)
    }

    @Test
    fun sleepLikeNeedsBothLengthAndNight() {
        assertTrue(Recap.sleepLike(t(23), t(30), la))
        assertFalse(Recap.sleepLike(t(23), t(28, 59), la))
        assertFalse(Recap.sleepLike(t(7), t(14), la))
        assertTrue(Recap.sleepLike(t(29), t(36), la)) // 5am–12pm the next day
    }

    @Test
    fun otherWithNoTitleIsUnlabelledOnceFinished() {
        val r = build(listOf(e(work, t(9)), e(other, t(10)), e(other, t(11), title = "bike"), e(other, t(12))), now = t(13))
        val u = r.unlabelled
        assertEquals(1, u.size)
        assertEquals(t(10), u[0].from)
        assertTrue(u[0].noTitle)
        assertFalse(u[0].noPlace)
    }

    @Test
    fun noPlaceCountsFromThirtyMinutesAndIsASetting() {
        val entries = listOf(e(work, t(9), links = setOf(sam)), e(eat, t(10), links = emptySet()), e(work, t(10, 20)), e(IDLE, t(12)))
        val r = build(entries)
        assertEquals(1, r.unlabelled.size)
        assertEquals(t(9), r.unlabelled[0].from)
        assertTrue(r.unlabelled[0].noPlace)
        assertFalse(r.unlabelled[0].noTitle)
        assertTrue(build(entries, settings = RecapSettings(countNoPlace = false)).unlabelled.isEmpty())
    }

    @Test
    fun rowsAreInTimeOrderAndCoverTheDay() {
        val r = build(listOf(e(work, t(9)), e(IDLE, t(10)), e(eat, t(11), links = emptySet()), e(IDLE, t(12))))
        assertEquals(listOf(t(9), t(10), t(11)), r.rows.map { it.from })
        assertTrue(r.rows[0] is RecapRow.Logged)
        assertTrue(r.rows[1] is RecapRow.Gap)
        assertTrue(r.rows[2] is RecapRow.Unlabelled)
    }

    @Test
    fun anEntryFromTheDayBeforeIsClipped() {
        val r = build(listOf(e(work, t(3)), e(IDLE, t(5)), e(work, t(7)), e(IDLE, t(8))))
        assertEquals(t(4), r.rows.first().from)
        assertEquals(t(5), r.gaps.single().from)
    }

    @Test
    fun moodMissingAndWhereItGoes() {
        val entries = listOf(e(work, t(9)), e(IDLE, t(17)))
        val r = build(entries, now = t(21))
        assertTrue(r.moodMissing)
        assertEquals(t(17), r.moodAt)
        assertFalse(build(entries, now = t(21), moments = listOf(mood(t(12)))).moodMissing)
        // A feeling with words but no face counts as the day's feeling.
        assertFalse(build(entries, now = t(21), moments = listOf(Moment(1, t(12), MomentKind.FEELING, "", "", null, null, listOf(4L)))).moodMissing)
        // A note doesn't.
        assertTrue(build(entries, now = t(21), moments = listOf(Moment(1, t(12), MomentKind.NOTE, "", "hi", null, null))).moodMissing)
        // Running now: now.
        assertEquals(t(15), build(listOf(e(work, t(9))), now = t(15)).moodAt)
    }

    @Test
    fun nothingLoggedIsNothingToNotify() {
        val r = build(emptyList(), now = t(21))
        assertFalse(r.logged)
        assertTrue(r.moodMissing)
        assertFalse(r.notify)
        assertTrue(build(listOf(e(IDLE, t(9))), now = t(21)).rows.isEmpty())
    }

    @Test
    fun nothingToFixStaysQuiet() {
        val r = build(listOf(e(work, t(9)), e(IDLE, t(12))), moments = listOf(mood(t(11))))
        assertTrue(r.nothingToFix)
        assertFalse(r.notify)
        assertEquals("", Recap.summary(r))
    }

    @Test
    fun summaryIsCountsOnly() {
        val entries = listOf(
            e(work, t(9)), e(IDLE, t(10)), e(work, t(11)), e(IDLE, t(12)), e(other, t(13)), e(IDLE, t(14)), e(work, t(15)), e(IDLE, t(16)),
        )
        val r = build(entries)
        assertEquals("3 gaps and 1 unlabelled today · How was today?", Recap.summary(r))
        assertEquals("3 gaps and 1 unlabelled today", Recap.summary(build(entries, moments = listOf(mood(t(9))))))
        val one = build(listOf(e(work, t(9)), e(IDLE, t(10)), e(work, t(11)), e(IDLE, t(12))), moments = listOf(mood(t(9))))
        assertEquals("1 gap today", Recap.summary(one))
        val moodOnly = build(listOf(e(work, t(9)), e(IDLE, t(12))))
        assertEquals("How was today?", Recap.summary(moodOnly))
        // A missing mood alone doesn't notify.
        assertFalse(moodOnly.notify)
        assertEquals("1 gap", Recap.summary(one, suffix = ""))
    }

    @Test
    fun fillingAWholeGapChangesItsIdleEntry() {
        val g = RecapRow.Gap(t(10), t(11), 7L, open = false)
        assertEquals(FillPlan(7L, null, null), Recap.plan(g, FillPart.WHOLE))
    }

    @Test
    fun fillingAnOpenGapEndsItAtNow() {
        val g = RecapRow.Gap(t(18), t(21, 30), 7L, open = true)
        assertEquals(FillPlan(7L, null, t(21, 30)), Recap.plan(g, FillPart.WHOLE))
        assertEquals(FillPlan(null, t(19, 45), t(21, 30)), Recap.plan(g, FillPart.LAST_HALF))
    }

    @Test
    fun halves() {
        val g = RecapRow.Gap(t(10), t(11, 1), 7L, open = false)
        assertEquals(t(10, 30), Recap.middle(g))
        assertEquals(FillPlan(7L, null, t(10, 30)), Recap.plan(g, FillPart.FIRST_HALF))
        assertEquals(FillPlan(null, t(10, 30), null), Recap.plan(g, FillPart.LAST_HALF))
        // Too short to split: the whole of it.
        val tiny = RecapRow.Gap(t(10), t(10, 1), 7L, open = false)
        assertFalse(Recap.canSplit(tiny))
        assertEquals(FillPlan(7L, null, null), Recap.plan(tiny, FillPart.LAST_HALF))
    }

    @Test
    fun aGapWithNoIdleAtItsStartInserts() {
        val g = RecapRow.Gap(t(10), t(11), null, open = false)
        assertEquals(FillPlan(null, t(10), null), Recap.plan(g, FillPart.WHOLE))
        assertNull(Recap.plan(g, FillPart.FIRST_HALF).changeIdle)
    }

    @Test
    fun nextAtIsTodayOrTomorrow() {
        val nine30 = Recap.DEFAULT_TIME
        assertEquals(t(21, 30), Recap.nextAt(t(20), nine30, la))
        assertEquals(t(45, 30), Recap.nextAt(t(21, 30), nine30, la))
        assertEquals(t(45, 30), Recap.nextAt(t(22), nine30, la))
        // Right at the time (within a minute): tomorrow, so a finished run doesn't fire again.
        assertEquals(t(45, 30), Recap.nextAt(t(21, 29) + 30_000L, nine30, la))
    }

    @Test
    fun nextAtKeepsTheClockTimeAcrossTheClockChange() {
        // US clocks go back on 2026-11-01.
        val c = Calendar.getInstance(la).apply { clear(); set(2026, 9, 31, 22, 0, 0) }
        val next = Recap.nextAt(c.timeInMillis, Recap.DEFAULT_TIME, la)
        val n = Calendar.getInstance(la).apply { timeInMillis = next }
        assertEquals(1, n.get(Calendar.DAY_OF_MONTH))
        assertEquals(21, n.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, n.get(Calendar.MINUTE))
    }

    @Test
    fun theJobKeepsAPendingRunAndCatchesUpAMissedOne() {
        val time = Recap.DEFAULT_TIME
        val due = t(21, 30)
        // App opened in the afternoon with tonight's run set: keep it.
        assertNull(Recap.next(t(15), time, due, lastRun = t(-3), pending = true, fresh = false, tz = la))
        // Not set any more (after a restart): set it again.
        assertEquals(Recap.Next(due, due), Recap.next(t(15), time, due, lastRun = 0L, pending = false, fresh = false, tz = la))
        // Opened at 9:40pm before the run happened: run now, for 9:30.
        assertEquals(Recap.Next(t(21, 40), due), Recap.next(t(21, 40), time, due, lastRun = 0L, pending = true, fresh = false, tz = la))
        // Missed by more than two hours: tomorrow.
        assertEquals(t(45, 30), Recap.next(t(23, 45), time, due, lastRun = 0L, pending = false, fresh = false, tz = la)!!.runAt)
        // Already ran: tomorrow.
        assertEquals(t(45, 30), Recap.next(t(21, 40), time, due, lastRun = t(21, 31), pending = false, fresh = false, tz = la)!!.runAt)
        // After a run or a new time: from now.
        assertEquals(t(45, 30), Recap.next(t(21, 30) + 5_000L, time, due, lastRun = t(21, 30), pending = false, fresh = true, tz = la)!!.runAt)
        assertEquals(t(22), Recap.next(t(15), 22 * 60, due, lastRun = 0L, pending = true, fresh = true, tz = la)!!.runAt)
        // The run is going on right now (it just set lastRun): leave it.
        assertNull(Recap.next(t(21, 31) + 20_000L, time, due, lastRun = t(21, 31), pending = true, fresh = false, tz = la))
        // First ever.
        assertEquals(due, Recap.next(t(15), time, 0L, 0L, pending = false, fresh = false, tz = la)!!.runAt)
    }

    @Test
    fun rankActsByScoreThenOrder() {
        val acts = listOf(
            Act(work, "Work", "", 0, 0, true, "", false),
            Act(eat, "Eat", "", 0, 1, true, "", false),
            Act(3, "Old", "", 0, 2, true, "", true),
            Act(4, "Read", "", 0, 3, true, "", false),
        )
        assertEquals(listOf(eat, work, 4L), Recap.rankActs(acts, mapOf(eat to 3.0)).map { it.id })
    }
}
