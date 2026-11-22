package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class FreeWriteTest {
    private fun act(id: Long, name: String, emoji: String = "", pos: Int = id.toInt(), archived: Boolean = false) =
        Act(id, name, emoji, 0, pos, true, "", archived)

    private val acts = listOf(
        act(1, "Watch", "🎬"), act(2, "Read", "📚"), act(3, "Work", "💼"), act(4, "Eat", "🍽️"),
        act(5, "Other", "✏️"), act(6, "Gone", archived = true), act(7, "Reading club", "📖"),
    )

    // --- Category ---

    @Test
    fun categoryPrefersTheActivityByName() {
        assertEquals(2L, FreeWrite.category("  read ", acts, 3, emptyList(), 5))
        assertEquals(2L, FreeWrite.exactActivity("READ", acts)!!.id)
        assertNull(FreeWrite.exactActivity("Gone", acts)) // archived ones never count
    }

    @Test
    fun thenWhatHeLastCalledThatTitle() {
        assertEquals(3L, FreeWrite.category("fixing the bike", acts, 3, emptyList(), 5))
        // An archived category doesn't stick.
        assertEquals(5L, FreeWrite.category("fixing the bike", acts, 6, emptyList(), 5))
        val used = listOf(FreeWrite.Used("Fixing the bike", 10, 4), FreeWrite.Used("fixing THE bike", 20, 3), FreeWrite.Used("other", 30, 1))
        assertEquals(3L, FreeWrite.lastActivityFor("fixing the bike", used))
        assertNull(FreeWrite.lastActivityFor("never", used))
    }

    @Test
    fun thenKeywordRulesThenActivityNamesInTheText() {
        val rules = listOf(KeywordRule("standup", 3), KeywordRule("bike", 4), KeywordRule("x", 6))
        assertEquals(3L, FreeWrite.category("Daily Standup notes", acts, null, rules, 5))
        assertEquals(4L, FreeWrite.category("fixing the bike", acts, null, rules, 5))
        // "watching" starts with Watch; the longer name wins ("reading club" over Read).
        assertEquals(1L, FreeWrite.category("watching a film", acts, null, emptyList(), 5))
        assertEquals(7L, FreeWrite.category("reading club tonight", acts, null, emptyList(), 5))
        assertEquals(2L, FreeWrite.category("reading Dune", acts, null, emptyList(), 5))
        // Not inside words ("tweak" has no "eat" at a word start), and nothing: Other.
        assertEquals(5L, FreeWrite.category("tweak the website", acts, null, emptyList(), 5))
        assertEquals(5L, FreeWrite.category("fixing the bike", acts, null, emptyList(), 5))
    }

    // --- Suggestions ---

    @Test
    fun recentTitlesAreNewestFirstAndDistinctIgnoringCase() {
        val used = listOf(
            FreeWrite.Used("fixing the bike", 10), FreeWrite.Used("Fixing  the bike", 50), FreeWrite.Used("tax forms", 30),
            FreeWrite.Used("  ", 60), FreeWrite.Used("Call mom", 40),
        )
        assertEquals(listOf("Fixing the bike", "Call mom", "tax forms"), FreeWrite.recent(used))
        assertEquals(listOf("Fixing the bike"), FreeWrite.recent(used, 1))
        val many = (1..40).map { FreeWrite.Used("t$it", it.toLong()) }
        assertEquals(FreeWrite.RECENT, FreeWrite.recent(many).size)
        assertEquals("t40", FreeWrite.recent(many).first())
        assertEquals(2, FreeWrite.counts(used)["fixing the bike"])
    }

    @Test
    fun suggestionsMatchActivitiesThenTitles() {
        val recent = listOf("reading Dune", "fixing the bike", "Read", "re-potting plants")
        val s = FreeWrite.suggest("re", acts, recent)
        assertEquals(
            listOf(
                FreeWrite.Suggestion.Activity(acts[1]), FreeWrite.Suggestion.Activity(acts[6]),
                FreeWrite.Suggestion.Title("reading Dune"), FreeWrite.Suggestion.Title("re-potting plants"),
            ),
            s,
        )
        // A word inside counts ("the bike"); case doesn't matter.
        assertEquals(listOf(FreeWrite.Suggestion.Title("fixing the bike")), FreeWrite.suggest("BIKE", acts, recent))
        // Nothing typed: the recent titles (not one that's an activity's name).
        assertEquals(3, FreeWrite.suggest("", acts, recent).size)
        assertTrue(FreeWrite.suggest("", acts, recent).none { it == FreeWrite.Suggestion.Title("Read") })
        assertTrue(FreeWrite.suggest("gone", acts, recent).isEmpty())
    }

    @Test
    fun namesAndCleaning() {
        assertEquals("fixing the bike", FreeWrite.name("  fixing\nthe   bike ", acts[4]))
        assertEquals("Other", FreeWrite.name("  ", acts[4]))
        assertEquals("Other", FreeWrite.name(null, acts[4]))
        assertNull(FreeWrite.clean(" \t "))
        assertTrue(FreeWrite.same("A b", "a  B"))
        assertFalse(FreeWrite.same(null, null))
    }

    // --- Exporters and merging ---

    private val utc = TimeZone.getTimeZone("UTC")

    private fun t(h: Int, m: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(2025, 3, 9, h, m, 0)
        }.timeInMillis

    private fun span(id: Long, from: Long, to: Long, title: String?, act: Long = 5, name: String = "Other", emoji: String = "✏️") =
        DaySpan(id, act, name, emoji, "Life", from, to, title = title)

    private fun day(spans: List<DaySpan>) = DayData(2025, 4, 9, spans, emptyList(), utc)

    @Test
    fun mergingNeedsTheSameTitle() {
        val a = span(1, t(14), t(15), "fixing the bike")
        val b = span(2, t(15), t(15, 10), "Fixing the bike")
        val c = span(3, t(15, 10), t(16), "tax forms")
        val d = span(4, t(16), t(17), null)
        val e = span(5, t(17), t(18), null)
        val runs = ObsidianDay.merge(listOf(a, b, c, d, e))
        assertEquals(listOf(setOf(1L, 2L), setOf(3L), setOf(4L, 5L)), runs.map { it.entries.toSet() })
    }

    @Test
    fun obsidianBulletUsesTheTitle() {
        val text = ObsidianDay.render(day(listOf(span(1, t(14), t(15, 10), "fixing the bike"), span(2, t(15, 10), t(16), null))))
        assertTrue(text, text.contains("- 2p–3:10p ✏️ fixing the bike\n"))
        assertTrue(text, text.contains("- 3:10p–4p ✏️ Other\n"))
        // Totals are by category.
        assertTrue(text, text.contains("- Life 2h"))
    }

    @Test
    fun calendarTitleUsesTheTitle() {
        val s = span(1, t(14), t(15), "fixing the bike").copy(people = listOf("Sam"), places = listOf("Garage"))
        assertEquals("✏️ fixing the bike · Sam @ Garage", CalendarDay.title(s))
        assertEquals("✏️ Other", CalendarDay.title(span(1, t(14), t(15), null)))
        val events = CalendarDay.events(day(listOf(s)))
        assertEquals("✏️ fixing the bike · Sam @ Garage", events.single().title)
    }

    @Test
    fun togglDescriptionStartsWithTheTitleAndTheProjectStaysTheCategory() {
        val s = span(1, t(14), t(15), "fixing the bike")
        assertEquals("✏️ fixing the bike", TogglDay.description(s, emptyList(), notes = true, checkIns = false))
        val w = TogglDay.entries(day(listOf(s)), 77L, notes = true, checkIns = false).single()
        assertEquals("Other", w.project)
        assertTrue(w.description.startsWith("✏️ fixing the bike"))
    }
}
