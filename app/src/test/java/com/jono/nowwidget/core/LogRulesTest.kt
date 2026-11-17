package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRulesTest {
    private val m = 60_000L

    @Test
    fun spansRunUntilTheNextEntryAndSkipIdle() {
        val rows = listOf(
            Entry(1, 5, 0),          // started before the range
            Entry(2, IDLE, 30 * m),  // turned off
            Entry(3, 7, 45 * m),
            Entry(4, 8, 90 * m),     // still running
        )
        val s = LogRules.spans(rows, 10 * m, 200 * m, 120 * m)
        assertEquals(listOf(1L, 3L, 4L), s.map { it.entry.id })
        assertEquals(10 * m, s[0].from) // clipped to the range
        assertEquals(30 * m, s[0].to)
        assertEquals(45 * m to 90 * m, s[1].from to s[1].to)
        assertEquals(120 * m, s[2].to) // the last one runs until now
    }

    @Test
    fun spansEndAtTheRangeAndDropEmptyOnes() {
        val rows = listOf(Entry(1, 5, 0), Entry(2, 6, 10 * m), Entry(3, 7, 10 * m))
        val s = LogRules.spans(rows, 0, 50 * m, 100 * m)
        assertEquals(listOf(1L, 3L), s.map { it.entry.id }) // entry 2 lasted no time
        assertEquals(50 * m, s.last().to)
        assertTrue(LogRules.spans(emptyList(), 0, 10, 10).isEmpty())
    }

    private fun act(id: Long, pos: Int) = Act(id, "a$id", "", 0, pos, true, "", false)

    @Test
    fun picksKeepTheCurrentOneAndYourOrder() {
        val all = (1L..6L).map { act(it, it.toInt()) }
        val scores = mapOf(5L to 3.0, 6L to 2.0)
        val p = LogRules.picks(all, all[0], 3, scores, smart = true)
        assertEquals(listOf(1L, 5L, 6L), p.map { it.id })
        assertEquals(listOf(1L, 2L, 3L), LogRules.picks(all, null, 3, scores, smart = false).map { it.id })
        assertEquals(all, LogRules.picks(all, null, 10, scores, smart = true))
    }

    private val now = 1_000_000_000L

    @Test
    fun tapRules() {
        val old = Entry(1, 5, now - 10 * m)
        val fresh = Entry(2, 6, now - 20_000L)
        val prev = Entry(1, 5, now - 10 * m)

        // Nothing yet, or a different activity: start it.
        assertEquals(LogRules.Tap.Start(7, now), LogRules.tap(null, false, null, 7, now, 0))
        assertEquals(LogRules.Tap.Start(7, now), LogRules.tap(old, false, null, 7, now, 0))
        // The current one again: turn off, or step back if that's the setting.
        assertEquals(LogRules.Tap.Start(IDLE, now), LogRules.tap(old, false, null, 5, now, 0))
        assertEquals(LogRules.Tap.MoveBack(5 * m), LogRules.tap(old, false, null, 5, now, 5))
        // Within a minute: a mistake.
        assertEquals(LogRules.Tap.Undo, LogRules.tap(fresh, false, prev, 6, now, 0))
        assertEquals(LogRules.Tap.Replace(fresh.start), LogRules.tap(fresh, false, prev, 7, now, 0))
        assertEquals(LogRules.Tap.Replace(null), LogRules.tap(fresh, false, prev, 5, now, 0))
        // ...unless it already has a note or feeling: then it's real.
        assertEquals(LogRules.Tap.Start(7, now), LogRules.tap(fresh, true, prev, 7, now, 0))
        assertEquals(LogRules.Tap.Start(IDLE, now), LogRules.tap(fresh, true, prev, 6, now, 0))
    }
}
