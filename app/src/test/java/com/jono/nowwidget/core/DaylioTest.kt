package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DaylioTest {
    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("daylio/$name")!!.use { it.readBytes() }

    private fun keys(d: DaylioData) = d.entries.map { Daylio.key(it, d.mood(it.moodId)!!.name) }

    @Test
    fun androidBackupIsBase64Json() {
        val d = Daylio.read(fixture("android_v10.daylio"))
        assertEquals(5, d.entries.size)
        assertEquals(6, d.moods.size)
        assertEquals(6, d.tags.size)
        assertEquals(listOf("rad", "good", "meh", "Up and Down", "bad", "awful"), d.moods.map { it.name })
        assertEquals(3, d.moods.first { it.name == "Up and Down" }.level)
        assertEquals(listOf(1, 2, 3, 3, 4, 5), d.moods.map { it.level })

        val e = d.entries[0]
        assertEquals(listOf(2020, 8, 1, 23, 5), listOf(e.year, e.month, e.day, e.hour, e.minute)) // month 7 → August
        assertEquals("Late one, \"quoted\", with, commas", e.note)
        assertEquals(listOf(1L, 6L), e.tagIds)
        assertEquals("Line one\nLine two — café 😀", d.entries[1].note)
        assertEquals(12, d.entries[2].month) // December, from month 11
        assertEquals("", d.entries[3].note) // no note key at all
        assertEquals("", e.title) // v10 has no titles
        assertEquals(1, d.uses()[1L])
        assertEquals(1, d.uses()[99L]) // unknown tag id: counted, ignored on import
    }

    @Test
    fun iosBackupIsAZipOfBase64Json() {
        val d = Daylio.read(fixture("ios_v15.daylio"))
        assertEquals(3, d.entries.size)
        assertEquals("A title", d.entries[0].title)
        assertEquals(listOf(2021, 1, 8, 21, 43), d.entries[0].let { listOf(it.year, it.month, it.day, it.hour, it.minute) })
        assertEquals("Line one\nLine two — café 😀", d.entries[2].note)
    }

    @Test
    fun overlappingBackupsShareKeys() {
        val a = keys(Daylio.read(fixture("android_v10.daylio")))
        val i = keys(Daylio.read(fixture("ios_v15.daylio")))
        assertEquals(a.size, a.toSet().size)
        assertEquals(setOf("daylio:2020-08-01 23:05:good", "daylio:2020-01-04 09:30:rad"), a.toSet().intersect(i.toSet()))
        assertTrue("daylio:2019-12-31 03:15:up and down" in a)
    }

    @Test
    fun newCsvExport() {
        val d = Daylio.read(fixture("export_new.csv"))
        assertEquals(4, d.entries.size)
        assertEquals(listOf("good", "rad", "Up and Down"), d.moods.map { it.name })
        assertEquals(listOf(2, 1, null), d.moods.map { it.level })
        assertEquals(listOf("work", "tired", "family", "Sam", "long"), d.tags.map { it.name })
        assertEquals("Late one, \"quoted\", with, commas", d.entries[1].note)
        assertEquals("Line one\nLine two", d.entries[2].note)
        assertEquals(9 to 30, d.entries[2].hour to d.entries[2].minute)
        assertEquals("A title", d.entries[0].title)
        // Same check-ins as in the backups, so the same keys.
        val backup = keys(Daylio.read(fixture("ios_v15.daylio"))).toSet()
        assertTrue(backup.containsAll(keys(d).take(3)))
    }

    @Test
    fun oldCsvExport() {
        val d = Daylio.read(fixture("export_old.csv"))
        assertEquals(3, d.entries.size)
        val e = d.entries.map { listOf(it.year, it.month, it.day, it.hour, it.minute) }
        assertEquals(listOf(2020, 8, 1, 23, 5), e[0]) // "11:05 pm"
        assertEquals(listOf(2020, 1, 4, 9, 30), e[1]) // "4 January"
        assertEquals(listOf(2019, 12, 31, 0, 15), e[2]) // "12:15 am"
        assertEquals("Line one\nLine two", d.entries[1].note)
        assertEquals("", d.entries[1].title)
    }

    @Test
    fun csvRoundTrip() {
        val rows = listOf(
            Daylio.CsvRow(2021, 1, 8, 21, 43, "good", listOf("work", "tired"), "Title, with comma", "Note \"quoted\"\nsecond line"),
            Daylio.CsvRow(2019, 12, 31, 0, 5, "Up and Down", emptyList(), "", ""),
        )
        val text = Daylio.toCsv(rows)
        assertTrue(text.startsWith(Daylio.CSV_HEADER + "\n"))
        assertTrue(text.contains("2021-01-08,January 8,Friday,21:43,good,work | tired,"))
        val back = Daylio.parseCsv(text)
        assertEquals(2, back.entries.size)
        back.entries.zip(rows).forEach { (e, r) ->
            assertEquals(listOf(r.year, r.month, r.day, r.hour, r.minute), listOf(e.year, e.month, e.day, e.hour, e.minute))
            assertEquals(r.mood, back.mood(e.moodId)!!.name)
            assertEquals(r.activities, e.tagIds.map { id -> back.tags.first { it.id == id }.name })
            assertEquals(r.title, e.title)
            assertEquals(r.note, e.note)
        }
        // And through the auto-detecting reader.
        assertEquals(2, Daylio.read(text.toByteArray()).entries.size)
    }

    @Test
    fun guessesByExactNameElseFeeling() {
        val acts = listOf(
            Act(1, "Work", "💼", 0, 0, true, "", false),
            Act(2, "Other", "✳️", 0, 1, true, "", false),
        )
        val links = listOf(Link(9, "Sam", LinkKind.PERSON, "", 0, false), Link(10, "The Loft", LinkKind.PLACE, "", 1, false))
        assertEquals(TagTarget.ToActivity(1, "Work"), Daylio.guess("work", acts, links))
        assertEquals(TagTarget.ToLink(9, LinkKind.PERSON, "Sam"), Daylio.guess("SAM", acts, links))
        assertEquals(TagTarget.ToLink(10, LinkKind.PLACE, "The Loft"), Daylio.guess("the loft", acts, links))
        assertEquals(TagTarget.Feeling, Daylio.guess("Other", acts, links))
        assertEquals(TagTarget.Feeling, Daylio.guess("long", acts, links))
        assertEquals(TagTarget.Feeling, Daylio.guess("tired", acts, links))
    }

    @Test(expected = IllegalArgumentException::class)
    fun junkIsRejected() {
        Daylio.read("hello there, not a backup".toByteArray())
    }

    private fun entry(note: String) = DaylioEntry(2020, 1, 4, 9, 30, 1, emptyList(), note, "")

    @Test
    fun keysForSameMinuteAndMood() {
        val a = entry("first")
        val b = entry("second")
        assertEquals("daylio:2020-01-04 09:30:rad", Daylio.key(a, "Rad"))
        // The alternative key depends only on the note, so it's the same on every import.
        assertTrue(Daylio.altKey(b, "rad").startsWith("daylio:2020-01-04 09:30:rad:"))
        assertEquals(Daylio.altKey(b, "rad"), Daylio.altKey(entry("second"), "rad"))
        assertTrue(Daylio.altKey(a, "rad") != Daylio.altKey(b, "rad"))
    }

    @Test
    fun decideKeepsDifferentNotesAndSkipsRepeats() {
        val a = entry("first")
        val b = entry("second")
        // Nothing there yet: the plain key.
        assertEquals(Daylio.Decision.Insert(Daylio.key(a, "rad")), Daylio.decide(a, "rad", null, false, false))
        // Same key, same note: already imported.
        assertEquals(Daylio.Decision.Skip, Daylio.decide(a, "rad", "first", false, false))
        // Same key, different note: a second check-in in that minute, kept under its own key...
        assertEquals(Daylio.Decision.Insert(Daylio.altKey(b, "rad")), Daylio.decide(b, "rad", "first", false, false))
        // ...which a re-import then finds.
        assertEquals(Daylio.Decision.Skip, Daylio.decide(b, "rad", "first", true, false))
        // A feeling logged in Now at that minute and mood (exported, then imported back): skip.
        assertEquals(Daylio.Decision.Skip, Daylio.decide(a, "rad", null, false, true))
    }
}
