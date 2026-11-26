package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TogetherTest {
    private val m = 60_000L
    private val eat = Act(1, "Eat", "🍽️", 0, 0, true, "Body", false)
    private val family = Act(2, "Family", "🏡", 0, 1, true, "People", false)
    private val friends = Act(3, "Friends", "🫂", 0, 2, true, "People", false)
    private val hobby = Act(4, "Hobby", "🎨", 0, 3, true, "Play", false)
    private val groups = listOf(eat, family, friends, hobby).associate { it.id to it.group }

    private fun span(id: Long, act: Long, minutes: Long, extras: Set<Long> = emptySet()) =
        Span(Entry(id, act, 0, extras = extras), 0, minutes * m)

    // --- Stats ---

    @Test
    fun overlappingActivitiesEachCountFullyButTheClockOnce() {
        val spans = listOf(span(1, 1, 60, setOf(2)), span(2, 3, 30, setOf(4, 2)), span(3, 4, 15))
        assertEquals(mapOf(1L to 60 * m, 2L to 90 * m, 3L to 30 * m, 4L to 45 * m), Together.activityTotals(spans))
        assertEquals(105 * m, Together.clockTotal(spans))
        // Friends + Family are both "People": that span counts once for People.
        assertEquals(mapOf("Body" to 60 * m, "People" to 90 * m, "Play" to 45 * m), Together.groupTotals(spans, { groups[it] }))
        assertTrue(Together.overlaps(spans))
        assertFalse(Together.overlaps(listOf(span(3, 4, 15), span(4, 1, 5, setOf(1)))))
        assertEquals(mapOf("Ungrouped" to 5 * m), Together.groupTotals(listOf(span(5, 9, 5)), { null }))
    }

    // --- Head text ---

    @Test
    fun headTextShortensToPlusN() {
        assertEquals("🍽️ Eat + 🏡 Family", Together.label("Eat", "🍽️", listOf(family), emoji = true))
        assertEquals("Eat + Family", Together.label("Eat", "🍽️", listOf(family), emoji = false))
        assertEquals("Eat + Family", Together.short("Eat", listOf(family), 12))
        assertEquals("Eat +1", Together.short("Eat", listOf(family), 11))
        assertEquals("Eat +2", Together.short("Eat", listOf(family, friends), 13))
        assertEquals("Eat", Together.short("Eat", emptyList(), 0))
        assertEquals("fixing the bike +1", Together.short("fixing the bike", listOf(family), 0))
    }

    @Test
    fun addModeTogglesExtrasButNeverThePrimary() {
        val e = Entry(1, 1, 0, extras = setOf(2))
        assertEquals(setOf(2L, 3L), Together.toggle(e, 3))
        assertEquals(emptySet<Long>(), Together.toggle(e, 2))
        assertEquals(setOf(2L), Together.toggle(e, 1))
        assertEquals(listOf(1L, 2L, 4L), Together.acts(Entry(1, 1, 0, extras = setOf(4, 2, 1))))
    }

    // --- Undo and replace keep the extras with their entry ---

    @Test
    fun anEntryWithExtrasIsMarked() {
        val now = 10_000_000L
        val prev = Entry(1, 5, now - 10 * m)
        val withExtras = Entry(2, 1, now - 20_000L, extras = setOf(2))
        assertTrue(LogRules.marked(withExtras, null))
        // Tapping it again within a minute turns it off instead of undoing it (the extras stay on record).
        assertEquals(LogRules.Tap.Start(IDLE, now), LogRules.tap(withExtras, false, prev, 1, now, 0))
        // A mistaken switch back to what ran before keeps the entry's start (and Store keeps its extras).
        assertEquals(LogRules.Tap.Replace(withExtras.start), LogRules.tap(withExtras, false, prev, 5, now, 0))
        assertEquals(LogRules.Tap.Undo, LogRules.tap(withExtras.copy(extras = emptySet()), false, prev, 1, now, 0))
    }

    // --- Exporters ---

    private val utc = TimeZone.getTimeZone("UTC")

    private fun t(h: Int, min: Int = 0): Long = Calendar.getInstance(utc).apply {
        clear()
        set(2025, 3, 9, h, min, 0)
    }.timeInMillis

    private val fam = DayAct(2, "Family", "🏡", "People")
    private fun dspan(id: Long, from: Long, to: Long, also: List<DayAct> = listOf(fam), people: List<String> = listOf("Mom")) =
        DaySpan(id, 1, "Eat", "🍽️", "Body", from, to, people = people, also = also)

    private fun day(spans: List<DaySpan>) = DayData(2025, 4, 9, spans, emptyList(), utc)

    @Test
    fun mergingNeedsTheSameActivities() {
        val a = dspan(1, t(18), t(18, 30))
        val b = dspan(2, t(18, 30), t(19))
        val c = dspan(3, t(19), t(19, 10), also = emptyList())
        val d = dspan(4, t(19, 10), t(19, 20), also = listOf(fam, DayAct(3, "Friends", "🫂", "People")))
        assertEquals(listOf(setOf(1L, 2L), setOf(3L), setOf(4L)), ObsidianDay.merge(listOf(a, b, c, d)).map { it.entries.toSet() })
    }

    @Test
    fun obsidianBulletAndTotals() {
        val text = ObsidianDay.render(day(listOf(dspan(1, t(18), t(19, 10)))))
        assertTrue(text, text.contains("- 6p–7:10p 🍽️ Eat + 🏡 Family w [[Mom]]\n"))
        // Totals: each group once per span.
        assertTrue(text, text.contains("Body 1h 10m"))
        assertTrue(text, text.contains("People 1h 10m"))
    }

    @Test
    fun calendarTitleAndColorFromThePrimary() {
        val s = dspan(1, t(18), t(19, 10))
        assertEquals("🍽️ Eat + 🏡 Family · Mom", CalendarDay.title(s))
        val e = CalendarDay.events(day(listOf(s))).single()
        assertEquals("Body", e.group) // the primary's area decides the calendar and color
    }

    @Test
    fun togglProjectIsThePrimaryAndExtrasAreTags() {
        val s = dspan(1, t(18), t(19, 10))
        val w = TogglDay.entries(day(listOf(s)), 77L, notes = true, checkIns = false).single()
        assertEquals("Eat", w.project)
        assertEquals("🍽️ Eat + Family", w.description)
        assertEquals(listOf("Family", "Mom", "now"), w.tags)
    }
}
