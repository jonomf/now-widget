package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirtyDaysTest {
    private fun day(n: Int) = DiaryDay.add(DiaryDay.startOf(2025, 1, 1), n)

    @Test
    fun withinTheCapEveryDayIsMarked() {
        val r = DirtyDays.reach(day(5), day(3), day(8), 62)
        assertEquals((3..8).map { day(it) }.toSet(), r.toSet())
        assertEquals(day(5), r.first())
    }

    @Test
    fun aLongReachMarksTheChangeDayAndTodayFirstThenDaysAfterThenBefore() {
        // A change on day 100 whose entry runs to day 400 ("today"), with days before it too.
        val r = DirtyDays.reach(day(100), day(0), day(400), 10)
        assertEquals(10, r.size)
        assertTrue(day(100) in r)
        assertTrue("today is never dropped", day(400) in r)
        // The rest of the cap goes to the days right after the change, not the days before it.
        assertEquals((100..108).map { day(it) }.toSet() + day(400), r.toSet())
        assertTrue(r.none { it < day(100) })
    }

    @Test
    fun daysBeforeFillWhatIsLeft() {
        val r = DirtyDays.reach(day(10), day(0), day(12), 8)
        // 10, 12, 11 after; then 9, 8, 7, 6, 5 before (nearest first).
        assertEquals(listOf(10, 12, 11, 9, 8, 7, 6, 5).map { day(it) }, r)
    }

    @Test
    fun todayAtTheCapIsStillMarked() {
        val r = DirtyDays.reach(day(0), day(0), day(200), 62)
        assertEquals(62, r.size)
        assertTrue(day(0) in r && day(200) in r)
    }

    @Test
    fun degenerateInputs() {
        assertEquals(listOf(day(3)), DirtyDays.reach(day(3), day(3), day(3), 62))
        assertEquals(listOf(day(3)), DirtyDays.reach(day(3), day(5), day(1), 62))
        assertTrue(DirtyDays.reach(day(3), day(3), day(3), 0).isEmpty())
        assertEquals(listOf(day(3)), DirtyDays.reach(day(3), day(0), day(9), 1))
    }
}
