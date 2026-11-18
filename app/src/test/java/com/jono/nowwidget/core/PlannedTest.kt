package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlannedTest {
    private val m = 60_000L
    private val work = 1L
    private val play = 2L
    private val body = 3L

    @Test
    fun matchCountsPlannedMinutesSpentInTheSameArea() {
        val planned = listOf(
            PlannedEvent("Standup", work, 0, 60 * m),
            PlannedEvent("Gym", body, 120 * m, 180 * m),
        )
        val actual = listOf(
            AreaSpan(work, 0, 45 * m),        // 45 of the 60 planned work minutes
            AreaSpan(play, 45 * m, 130 * m),  // wrong area during planned time
            AreaSpan(body, 130 * m, 200 * m), // 50 of 60 gym minutes, then 20 unplanned
        )
        val c = PlannedVsActual.compare(planned, actual, 0, 24 * 60 * m)
        assertEquals(120 * m, c.plannedMs)
        assertEquals(95 * m, c.matchedMs)
        assertEquals(95.0 / 120.0, c.match!!, 1e-9)
        val byArea = c.areas.associateBy { it.area }
        assertEquals(AreaTotal(work, 60 * m, 45 * m), byArea[work])
        assertEquals(AreaTotal(body, 60 * m, 70 * m), byArea[body])
        assertEquals(AreaTotal(play, 0, 85 * m), byArea[play])
    }

    @Test
    fun overlappingPlansCountOnceAndEitherAreaMatches() {
        val planned = listOf(PlannedEvent("A", work, 0, 60 * m), PlannedEvent("B", play, 30 * m, 90 * m))
        val actual = listOf(AreaSpan(play, 0, 90 * m))
        val c = PlannedVsActual.compare(planned, actual, 0, 1000 * m)
        assertEquals(90 * m, c.plannedMs)
        assertEquals(60 * m, c.matchedMs) // 0–30 only work is planned; 30–90 play is too
    }

    @Test
    fun noAreaNeverMatchesAndClipsToTheDay() {
        val planned = listOf(PlannedEvent("Late", work, -30 * m, 30 * m))
        val actual = listOf(AreaSpan(null, 0, 30 * m))
        val c = PlannedVsActual.compare(planned, actual, 0, 100 * m)
        assertEquals(30 * m, c.plannedMs)
        assertEquals(0L, c.matchedMs)
        assertEquals(null, c.areas.last().area) // the no-area bucket last
        assertNull(PlannedVsActual.compare(emptyList(), actual, 0, 100 * m).match)
    }

    // --- Nudge ---

    private val rules = listOf(KeywordRule("gym", 30L), KeywordRule("1:1", 40L), KeywordRule("  ", 50L))

    @Test
    fun keywordFirstThenMostUsedOfTheArea() {
        val usable = setOf(30L, 40L, 41L, 50L)
        val fallback = mapOf(work to 41L)
        assertEquals(30L, PlannedNudge.activityFor(PlannedEvent("Gym with Sam", body, 0, 1), rules, fallback, usable))
        assertEquals(40L, PlannedNudge.activityFor(PlannedEvent("1:1 Dana", work, 0, 1), rules, fallback, usable))
        assertEquals(41L, PlannedNudge.activityFor(PlannedEvent("Planning", work, 0, 1), rules, fallback, usable))
        assertNull(PlannedNudge.activityFor(PlannedEvent("Planning", play, 0, 1), rules, fallback, usable))
        // A rule for a hidden or removed activity is skipped.
        assertEquals(41L, PlannedNudge.activityFor(PlannedEvent("gym", work, 0, 1), rules, fallback, setOf(41L)))
    }

    @Test
    fun pickUsesWhatsOnNow_latestStartFirst() {
        val planned = listOf(
            PlannedEvent("Workday", work, 0, 480 * m),
            PlannedEvent("Gym", body, 60 * m, 120 * m),
            PlannedEvent("Later", work, 200 * m, 300 * m),
        )
        val fallback = mapOf(work to 41L)
        val usable = setOf(30L, 41L)
        assertEquals(30L, PlannedNudge.pick(90 * m, planned, rules, fallback, usable))
        assertEquals(41L, PlannedNudge.pick(130 * m, planned, rules, fallback, usable))
        assertEquals(41L, PlannedNudge.pick(120 * m, planned, rules, fallback, usable)) // gym ended at 120
        assertNull(PlannedNudge.pick(500 * m, planned, rules, fallback, usable))
    }

    @Test
    fun mostUsedPerArea() {
        val uses = mapOf(1L to 5, 2L to 9, 3L to 9, 4L to 100)
        val areaOf = mapOf(1L to work, 2L to work, 3L to work, 4L to null)
        assertEquals(mapOf(work to 2L), PlannedNudge.mostUsed(uses, areaOf))
    }

    @Test
    fun rulesRoundTrip() {
        val r = listOf(KeywordRule("gym", 3L), KeywordRule("tab\there", 4L), KeywordRule(" ", 5L))
        val back = PlannedNudge.decode(PlannedNudge.encode(r))
        assertEquals(listOf(KeywordRule("gym", 3L), KeywordRule("tab here", 4L)), back)
        assertEquals(emptyList<KeywordRule>(), PlannedNudge.decode(null))
        assertEquals(emptyList<KeywordRule>(), PlannedNudge.decode("junk\nx\ty"))
    }

    // --- Where the nudged activity goes ---

    private fun act(id: Long, pos: Int) = Act(id, "a$id", "", 0, pos, true, "", false)

    @Test
    fun nudgeGoesFirst() {
        val a = act(1, 0)
        val b = act(2, 1)
        val c = act(3, 2)
        val d = act(4, 3)
        // Already among the picks: moves to the front.
        assertEquals(listOf(c, a, b), LogRules.withNudge(listOf(a, b, c), c, null, 3))
        // Not there: replaces the last pick that isn't the current one.
        assertEquals(listOf(d, a, c), LogRules.withNudge(listOf(a, b, c), d, c, 3))
        assertEquals(listOf(d, a, b), LogRules.withNudge(listOf(a, b, c), d, null, 3))
        // Room to spare: just added.
        assertEquals(listOf(d, a, b), LogRules.withNudge(listOf(a, b), d, null, 3))
        // It's what you're doing, or nothing is nudged, or no room: unchanged.
        assertEquals(listOf(a, b, c), LogRules.withNudge(listOf(a, b, c), b, b, 3))
        assertEquals(listOf(a, b, c), LogRules.withNudge(listOf(a, b, c), null, null, 3))
        assertEquals(listOf(a), LogRules.withNudge(listOf(a), d, a, 1))
        assertEquals(listOf(d), LogRules.withNudge(listOf(a), d, null, 1))
    }
}
