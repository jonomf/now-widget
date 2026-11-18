package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ObsidianDayTest {
    private val utc = TimeZone.getTimeZone("UTC")

    /** A clock time on the diary day 2025-4-9; hours before 4am are the next calendar date. */
    private fun t(h: Int, m: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(2025, 3, if (h < 4) 10 else 9, h, m, 0)
        }.timeInMillis

    /** 4am the next morning: where the diary day ends. */
    private val end: Long = t(3, 59) + 60_000L

    private fun span(
        id: Long,
        act: Long,
        name: String,
        emoji: String,
        group: String,
        from: Long,
        to: Long,
        people: List<String> = emptyList(),
        places: List<String> = emptyList(),
        context: String? = null,
        running: Boolean = false,
    ) = DaySpan(id, act, name, emoji, group, from, to, people, places, context, running)

    private fun day(spans: List<DaySpan>, moments: List<DayMoment> = emptyList()) =
        DayData(2025, 4, 9, spans, moments, utc)

    private fun note(ts: Long, text: String, entry: Long? = null) = DayMoment(ts, MomentKind.NOTE, entry, text = text)

    private fun feeling(
        ts: Long,
        mood: String,
        level: Int,
        tags: List<String> = emptyList(),
        text: String = "",
        entry: Long? = null,
        title: String = "",
    ) = DayMoment(ts, MomentKind.FEELING, entry, mood = mood, moodLevel = level, tags = tags, title = title, text = text)

    private val trip = "2025-4 Trip Home"

    /** A whole day: a header, links on spans, moments under spans, a loose note, an idle gap, a running span. */
    private fun fullDay(): DayData {
        val spans = listOf(
            span(1, 2, "Eat", "🍽️", "Body", t(7, 30), t(8, 15), listOf("Dulce"), listOf("The Loft"), trip),
            span(2, 8, "Hobby", "🎨", "Play", t(9), t(10, 40), listOf("Sam"), listOf("Tartine"), trip),
            span(3, 5, "Work", "💼", "Work", t(10, 40), t(12, 5), emptyList(), listOf("The Loft"), trip),
            span(4, 2, "Eat", "🍽️", "Body", t(12, 30), t(13), emptyList(), listOf("The Loft"), trip),
            span(5, 5, "Work", "💼", "Work", t(13), t(15, 5), emptyList(), listOf("The Loft"), trip),
            span(6, 3, "Rest", "🛋️", "Body", t(17), t(17, 40), listOf("Dulce"), emptyList(), trip, running = true),
        )
        val moments = listOf(
            note(t(9, 15), "Sketching the bridge from memory", entry = 2),
            feeling(t(10, 2), "good", 2, listOf("tired", "calm"), "slow start but nice", entry = 2),
            note(t(15, 30), "walked to the pier while nothing was on"),
            feeling(t(17, 10), "rad", 1, emptyList(), "", entry = 6),
        )
        return day(spans, moments)
    }

    @Test
    fun fullRealisticDay() {
        val expected = """
            #log-now
            At [[The Loft]] w [[Sam]] and [[Dulce]] in [[2025-4 Trip Home]]

            - 7:30a–8:15a 🍽️ Eat w [[Dulce]] at [[The Loft]]
            - 9a–10:40a 🎨 Hobby w [[Sam]] at [[Tartine]]
            	- 9:15a Sketching the bridge from memory
            	- 10:02a 🙂 good · tired, calm — slow start but nice
            - 10:40a–12:05p 💼 Work at [[The Loft]]
            - 12:30p–1p 🍽️ Eat at [[The Loft]]
            - 1p–3:05p 💼 Work at [[The Loft]]
            - 3:30p walked to the pier while nothing was on
            - 5p– 🛋️ Rest w [[Dulce]] (running)
            	- 5:10p 😄 rad

            ## totals
            - Work 3h 30m · Body 1h 55m · Play 1h 40m
        """.trimIndent() + "\n"
        val out = ObsidianDay.render(fullDay())
        println(out)
        assertEquals(expected, out)
    }

    @Test
    fun emptyDayWithOnlyMoments() {
        val d = day(
            emptyList(),
            listOf(
                feeling(t(21, 5), "meh", 3, listOf("bored")),
                note(t(9, 40), "Woke up early"),
            ),
        )
        assertEquals(
            "#log-now\n\n- 9:40a Woke up early\n- 9:05p 😐 meh · bored\n",
            ObsidianDay.render(d),
        )
        assertTrue(ObsidianDay.hasContent(d))
    }

    @Test
    fun nothingAtAllIsJustTheTag() {
        val d = day(emptyList(), listOf(DayMoment(t(10), MomentKind.NOTE)))
        assertEquals("#log-now\n", ObsidianDay.render(d))
        assertFalse(ObsidianDay.hasContent(d))
        assertEquals("#log-now\n", ObsidianDay.render(day(emptyList())))
    }

    @Test
    fun onlyARunningSpan() {
        val d = day(listOf(span(1, 5, "Work", "💼", "Work", t(9, 5), t(9, 50), running = true)))
        assertEquals(
            "#log-now\n\n- 9:05a– 💼 Work (running)\n\n## totals\n- Work 45m\n",
            ObsidianDay.render(d),
        )
    }

    @Test
    fun timesPastMidnightBelongToTheDay() {
        val d = day(
            listOf(
                span(1, 8, "Games", "🎮", "Play", t(23, 50), t(1, 20)),
                span(2, 1, "Sleep", "😴", "Body", t(1, 20), t(3, 30)),
                span(3, 1, "Sleep", "😴", "Body", t(3, 30), end),
            ),
            listOf(note(t(0, 5), "one more round", entry = 1)),
        )
        assertEquals(
            """
            #log-now

            - 11:50p–1:20a 🎮 Games
            	- 12:05a one more round
            - 1:20a–4a 😴 Sleep

            ## totals
            - Body 2h 40m · Play 1h 30m
            """.trimIndent() + "\n",
            ObsidianDay.render(d),
        )
        assertEquals("12a", ObsidianDay.time(t(0), utc))
        assertEquals("12p", ObsidianDay.time(t(12), utc))
        assertEquals("12:30p", ObsidianDay.time(t(12, 30), utc))
        assertEquals("3:05p", ObsidianDay.time(t(15, 5), utc))
        assertEquals("3:05a", ObsidianDay.time(t(3, 5), utc))
    }

    @Test
    fun backToBackSpansOfTheSameThingMerge() {
        val d = day(
            listOf(
                span(1, 5, "Work", "💼", "Work", t(9), t(10), listOf("Sam"), listOf("The Loft")),
                span(2, 5, "Work", "💼", "Work", t(10), t(11, 30), listOf("Sam"), listOf("The Loft")),
                // Same activity, different link: stays its own bullet.
                span(3, 5, "Work", "💼", "Work", t(11, 30), t(12), listOf("Dulce"), listOf("The Loft")),
                // Same links but not touching: stays its own bullet.
                span(4, 5, "Work", "💼", "Work", t(13), t(14), listOf("Dulce"), listOf("The Loft")),
            ),
            listOf(note(t(10, 30), "second half", entry = 2), note(t(9, 5), "first half", entry = 1)),
        )
        assertEquals(
            """
            #log-now
            At [[The Loft]] w [[Sam]] and [[Dulce]]

            - 9a–11:30a 💼 Work w [[Sam]] at [[The Loft]]
            	- 9:05a first half
            	- 10:30a second half
            - 11:30a–12p 💼 Work w [[Dulce]] at [[The Loft]]
            - 1p–2p 💼 Work w [[Dulce]] at [[The Loft]]

            ## totals
            - Work 4h
            """.trimIndent() + "\n",
            ObsidianDay.render(d),
        )
    }

    @Test
    fun multiLineNotesStayInsideTheirBullet() {
        val d = day(
            listOf(span(1, 5, "Work", "💼", "Work", t(9), t(10))),
            listOf(
                note(t(9, 10), "first line\r\nsecond line\n\nafter a gap  \n", entry = 1),
                feeling(t(9, 20), "good", 2, text = "line a\nline b", entry = 1, title = "Morning"),
                note(t(12), "loose one\n  indented\nlast"),
            ),
        )
        assertEquals(
            "#log-now\n\n" +
                "- 9a–10a 💼 Work\n" +
                "\t- 9:10a first line\n" +
                "\t  second line\n" +
                "\n" +
                "\t  after a gap\n" +
                "\t- 9:20a **Morning** 🙂 good — line a\n" +
                "\t  line b\n" +
                "- 12p loose one\n" +
                "    indented\n" +
                "  last\n" +
                "\n## totals\n- Work 1h\n",
            ObsidianDay.render(d),
        )
    }

    @Test
    fun titlesThatWouldBreakALinkBecomePlainText() {
        assertEquals("[[Sam]]", ObsidianDay.link("Sam"))
        assertEquals("M4: The Plan", ObsidianDay.link("M4: The Plan").removePrefix("[[").removeSuffix("]]"))
        assertEquals("a|b", ObsidianDay.link("a|b"))
        assertEquals("C# notes", ObsidianDay.link("C# notes"))
        assertEquals("x [[y]] z", ObsidianDay.link("x [[y]] z"))
        assertEquals("oops]]", ObsidianDay.link("oops]]"))
        assertEquals("[[Sam Jr.]]", ObsidianDay.link("Sam Jr."))
        assertEquals("[[two lines]]", ObsidianDay.link("two\nlines"))
        val d = day(
            listOf(span(1, 8, "Hobby", "🎨", "Play", t(9), t(10), listOf("Pipe|Name"), listOf("Café #2"), "Trip [[x]]")),
        )
        assertEquals(
            "#log-now\nAt Café #2 w Pipe|Name in Trip [[x]]\n\n- 9a–10a 🎨 Hobby w Pipe|Name at Café #2\n\n## totals\n- Play 1h\n",
            ObsidianDay.render(d),
        )
    }

    @Test
    fun headerPartsAreLeftOutWhenEmptyAndContextShowsOnlyWhereItDiffers() {
        val d = day(
            listOf(
                span(1, 8, "Hobby", "🎨", "Play", t(9), t(11), emptyList(), emptyList(), "Trip A"),
                span(2, 5, "Work", "💼", "Work", t(11), t(12), emptyList(), emptyList(), "Trip B"),
                span(3, 5, "Work", "💼", "Work", t(12), t(13), emptyList(), emptyList(), null),
            ),
        )
        val lines = ObsidianDay.render(d).lines()
        assertEquals("#log-now", lines[0])
        assertEquals("in [[Trip A]]", lines[1])
        assertEquals("- 9a–11a 🎨 Hobby", lines[3])
        assertEquals("- 11a–12p 💼 Work in [[Trip B]]", lines[4])
        assertEquals("- 12p–1p 💼 Work", lines[5])
        // No place, nobody, no context: no header line, one blank line after the tag.
        val bare = ObsidianDay.render(day(listOf(span(1, 5, "Work", "💼", "Work", t(9), t(10)))))
        assertEquals("#log-now\n\n- 9a–10a 💼 Work\n\n## totals\n- Work 1h\n", bare)
    }

    @Test
    fun peopleAreOrderedByTimeTogether() {
        val d = day(
            listOf(
                span(1, 8, "Hobby", "🎨", "Play", t(9), t(10), listOf("A", "B")),
                span(2, 8, "Games", "🎮", "Play", t(10), t(13), listOf("C", "B")),
                span(3, 8, "Read", "📚", "Play", t(13), t(14), listOf("D")),
            ),
        )
        assertEquals("w [[B]], [[C]], [[A]] and [[D]]", ObsidianDay.render(d).lines()[1])
    }

    @Test
    fun feelingsWithTagsOnlyOrTitleOnly() {
        val d = day(
            emptyList(),
            listOf(
                DayMoment(t(8), MomentKind.FEELING, tags = listOf("calm", " ", "rested")),
                DayMoment(t(9), MomentKind.FEELING, title = "Big day", text = "starting"),
                DayMoment(t(10), MomentKind.FEELING, mood = "Up and Down", moodLevel = 3, moodEmoji = "🎢"),
            ),
        )
        assertEquals(
            "#log-now\n\n- 8a calm, rested\n- 9a **Big day** — starting\n- 10a 🎢 Up and Down\n",
            ObsidianDay.render(d),
        )
    }

    @Test
    fun theSameDayAlwaysGivesTheSameBytes() {
        val d = fullDay()
        val a = ObsidianDay.render(d)
        val b = ObsidianDay.render(d.copy(spans = d.spans.reversed(), moments = d.moments.reversed()))
        assertEquals(a, b)
        assertEquals(ObsidianDay.hash(a), ObsidianDay.hash(b))
        assertEquals(64, ObsidianDay.hash(a).length)
        assertNotEquals(ObsidianDay.hash(a), ObsidianDay.hash(a + " "))
    }

    @Test
    fun totalsUseTheActivityNameWhenUngroupedAndLeaveNothingOut() {
        val d = day(
            listOf(
                span(1, 9, "Juggling", "🤹", "", t(9), t(9, 30)),
                span(2, 5, "Work", "💼", "Work", t(9, 30), t(11)),
                span(3, 5, "Chat", "💬", "Work", t(11), t(11), running = false),
            ),
        )
        assertTrue(ObsidianDay.render(d).endsWith("## totals\n- Work 1h 30m · Juggling 30m\n"))
    }

    @Test
    fun fileNamesAndKeysAreUnpadded() {
        assertEquals("2025-4-9 (now).md", ObsidianDay.fileName(2025, 4, 9))
        assertEquals("2026-11-17", ObsidianDay.key(2026, 11, 17))
    }

    @Test
    fun importedOnlyDaysAreRecognised() {
        val imp = DayMoment(t(9), MomentKind.FEELING, mood = "good", moodLevel = 2, imported = true)
        assertTrue(ObsidianDay.onlyImported(day(emptyList(), listOf(imp))))
        assertFalse(ObsidianDay.onlyImported(day(emptyList(), listOf(imp, note(t(10), "mine")))))
        assertFalse(ObsidianDay.onlyImported(day(listOf(span(1, 5, "Work", "💼", "Work", t(9), t(10))), listOf(imp))))
        assertFalse(ObsidianDay.onlyImported(day(emptyList())))
    }
}
