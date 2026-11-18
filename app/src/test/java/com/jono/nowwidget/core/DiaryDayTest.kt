package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DiaryDayTest {
    private val la = TimeZone.getTimeZone("America/Los_Angeles")

    private fun t(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        Calendar.getInstance(la).apply {
            clear()
            set(y, m - 1, d, h, min, 0)
        }.timeInMillis

    @Test
    fun aDateStartsAtFour() {
        assertEquals(t(2026, 11, 16, 4), DiaryDay.startOf(2026, 11, 16, la))
        assertEquals(Triple(2026, 11, 16), DiaryDay.date(DiaryDay.startOf(2026, 11, 16, la), la))
        assertEquals(t(2026, 3, 8, 4), DiaryDay.startOf(2026, 3, 8, la))
    }

    @Test
    fun beforeFourBelongsToThePreviousDay() {
        assertEquals(t(2026, 11, 15, 4), DiaryDay.start(t(2026, 11, 16, 3, 59), la))
        assertEquals(t(2026, 11, 16, 4), DiaryDay.start(t(2026, 11, 16, 4, 0), la))
        assertEquals(t(2026, 11, 16, 4), DiaryDay.start(t(2026, 11, 16, 23, 59), la))
        assertEquals(t(2026, 11, 15, 4), DiaryDay.start(t(2026, 11, 16, 0, 0), la))
    }

    @Test
    fun newYearsEveRunsPastMidnight() {
        assertEquals(t(2025, 12, 31, 4), DiaryDay.start(t(2026, 1, 1, 2, 30), la))
        assertEquals(Triple(2025, 12, 31), DiaryDay.date(t(2026, 1, 1, 2, 30), la))
    }

    @Test
    fun addingDaysKeepsFourAmAcrossClockChanges() {
        // US clocks go forward on 2026-03-08 and back on 2026-11-01.
        assertEquals(t(2026, 3, 8, 4), DiaryDay.add(t(2026, 3, 7, 4), 1, la))
        assertEquals(t(2026, 11, 1, 4), DiaryDay.add(t(2026, 10, 31, 4), 1, la))
        assertEquals(t(2026, 10, 31, 4), DiaryDay.add(t(2026, 11, 1, 4), -1, la))
        assertEquals(23 * 3_600_000L, DiaryDay.add(t(2026, 3, 7, 4), 1, la) - t(2026, 3, 7, 4))
        assertEquals(25 * 3_600_000L, DiaryDay.add(t(2026, 10, 31, 4), 1, la) - t(2026, 10, 31, 4))
        // Any time in the day works as the base.
        assertEquals(t(2026, 11, 17, 4), DiaryDay.add(t(2026, 11, 16, 13, 15), 1, la))
    }

    @Test
    fun pickedTimesBeforeFourGoToTheNightAfter() {
        val day = t(2026, 11, 15, 4)
        assertEquals(t(2026, 11, 15, 9, 30), DiaryDay.at(day, 9, 30, la))
        assertEquals(t(2026, 11, 15, 23, 0), DiaryDay.at(day, 23, 0, la))
        assertEquals(t(2026, 11, 16, 1, 15), DiaryDay.at(day, 1, 15, la))
        assertEquals(t(2026, 11, 15, 4, 0), DiaryDay.at(day, 4, 0, la))
    }
}
