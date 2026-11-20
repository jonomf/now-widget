package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TogglLogTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun t(h: Int, m: Int = 0, s: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(2025, 3, if (h < 4) 10 else 9, h, m, s)
        }.timeInMillis

    private fun span(
        id: Long, act: Long, name: String, emoji: String, from: Long, to: Long,
        people: List<String> = emptyList(), places: List<String> = emptyList(), context: String? = null,
        running: Boolean = false,
    ) = DaySpan(id, act, name, emoji, "Play", from, to, people, places, context, running)

    private fun day(spans: List<DaySpan>, moments: List<DayMoment> = emptyList()) = DayData(2025, 4, 9, spans, moments, utc)

    private fun entries(d: DayData, notes: Boolean = true, checkIns: Boolean = false) = TogglDay.entries(d, 77L, notes, checkIns)

    private val sample = day(
        listOf(span(7, 8, "Hobby", "🎨", t(9, 15), t(10, 40), listOf("Sam", "Dulce"), listOf("Tartine"), "2025-4 Trip Home")),
        listOf(
            DayMoment(t(9, 30), MomentKind.FEELING, 7, mood = "good", moodLevel = 2, tags = listOf("calm", "focused"), text = "Painting by the window"),
            DayMoment(t(10, 5), MomentKind.NOTE, 7, text = "Sam brought pastries\nfrom Tartine"),
        ),
    )

    @Test
    fun sampleEntryBody() {
        val w = entries(sample).single()
        assertEquals("2025-4-9/e7", w.key)
        assertEquals("Hobby", w.project)
        assertEquals("🎨 Hobby — Painting by the window / Sam brought pastries from Tartine", w.description)
        assertEquals(listOf("Sam", "Dulce", "Tartine", "2025-4 Trip Home", "now"), w.tags)
        assertEquals(5100L, w.durationSec)
        assertEquals(
            "{\"created_with\":\"Now widget\",\"description\":\"🎨 Hobby — Painting by the window / Sam brought pastries from Tartine\"," +
                "\"tags\":[\"Sam\",\"Dulce\",\"Tartine\",\"2025-4 Trip Home\",\"now\"],\"project_id\":4242,\"workspace_id\":77," +
                "\"start\":\"2025-04-09T09:15:00.000Z\",\"stop\":\"2025-04-09T10:40:00.000Z\",\"duration\":5100}",
            TogglJson.body(w, 4242L),
        )
    }

    @Test
    fun checkInsOnlyWhenAsked() {
        val on = entries(sample, notes = true, checkIns = true).single()
        assertEquals("🎨 Hobby — 🙂 good · calm, focused — Painting by the window / Sam brought pastries from Tartine", on.description)
        val off = entries(sample, notes = false, checkIns = false).single()
        assertEquals("🎨 Hobby", off.description)
        val onlyCheckIn = entries(sample, notes = false, checkIns = true).single()
        assertEquals("🎨 Hobby — 🙂 good · calm, focused", onlyCheckIn.description)
    }

    @Test
    fun mergesRunsAndKeysOnTheFirstEntry() {
        val d = day(listOf(
            span(3, 8, "Hobby", "🎨", t(9), t(9, 30), listOf("Sam")),
            span(4, 8, "Hobby", "🎨", t(9, 30), t(10), listOf("Sam")),
            span(5, 8, "Hobby", "🎨", t(10), t(11), listOf("Sam", "Dulce")),
        ))
        val e = entries(d)
        assertEquals(listOf("2025-4-9/e3", "2025-4-9/e5"), e.map { it.key })
        assertEquals(3600L, e[0].durationSec)
    }

    @Test
    fun oneMinuteOrMoreOnly() {
        val d = day(listOf(
            span(1, 8, "Hobby", "🎨", t(9), t(9, 0, 59)),
            span(2, 9, "Read", "📖", t(10), t(10, 1)),
            span(3, 10, "Walk", "🚶", t(11), t(11, 5), running = true),
        ))
        assertEquals(listOf("2025-4-9/e2"), entries(d).map { it.key })
    }

    @Test
    fun runningSpanWaitsUntilItFinishes() {
        val running = day(listOf(span(1, 8, "Hobby", "🎨", t(9), t(10), running = true)))
        assertTrue(entries(running).isEmpty())
        val done = day(listOf(span(1, 8, "Hobby", "🎨", t(9), t(10))))
        assertEquals(1, entries(done).size)
    }

    @Test
    fun nothingLoggedMeansNothingSent() {
        assertTrue(entries(day(emptyList())).isEmpty())
        // A check-in on its own, with no activity, is not a time entry.
        assertTrue(entries(day(emptyList(), listOf(DayMoment(t(9), MomentKind.NOTE, null, text = "hi")))).isEmpty())
    }

    @Test
    fun momentsWithoutAnEntryJoinTheRunTheyFallIn() {
        val d = day(
            listOf(span(1, 8, "Hobby", "🎨", t(9), t(10))),
            listOf(DayMoment(t(9, 30), MomentKind.NOTE, null, text = "loose note"), DayMoment(t(11), MomentKind.NOTE, null, text = "later")),
        )
        assertEquals("🎨 Hobby — loose note", entries(d).single().description)
    }

    @Test
    fun descriptionStaysUnderTheLimit() {
        val long = "word ".repeat(2000)
        val d = day(listOf(span(1, 8, "Hobby", "🎨", t(9), t(10))), listOf(DayMoment(t(9, 30), MomentKind.NOTE, 1, text = long)))
        val desc = entries(d).single().description
        assertTrue(desc.length <= TogglDay.MAX_DESCRIPTION)
        assertTrue(desc.endsWith("…"))
        assertTrue(desc.startsWith("🎨 Hobby — word word"))
        // Never splits an emoji.
        val s = "a" + "😀".repeat(10)
        val cut = TogglDay.truncate(s, 4)
        assertTrue(cut.endsWith("…"))
        assertFalse(Character.isHighSurrogate(cut[cut.length - 2]))
    }

    @Test
    fun tagsAreCleanDistinctAndEndWithNow() {
        val s = span(1, 8, "Hobby", "🎨", t(9), t(10), listOf("Sam", "sam", " Dulce "), listOf("Tartine"), "now")
        assertEquals(listOf("Sam", "Dulce", "Tartine", "now"), TogglDay.tags(s))
        assertEquals(listOf("now"), TogglDay.tags(span(1, 8, "Hobby", "🎨", t(9), t(10))))
    }

    @Test
    fun noEmojiAndBlankNames() {
        val d = day(listOf(span(1, 8, " Hobby ", "", t(9), t(10))))
        val w = entries(d).single()
        assertEquals("Hobby", w.description)
        assertEquals("Hobby", w.project)
    }

    @Test
    fun hashChangesWithAnythingSent() {
        val w = entries(sample).single()
        assertEquals(w.hash, entries(sample).single().hash)
        assertNotEquals(w.hash, w.copy(description = "x").hash)
        assertNotEquals(w.hash, w.copy(workspaceId = 78L).hash)
        assertNotEquals(w.hash, w.copy(tags = listOf("now")).hash)
        assertNotEquals(w.hash, w.copy(stopSec = w.stopSec + 1).hash)
        assertNotEquals(w.hash, w.copy(project = "Other").hash)
    }

    // --- Bodies and parsing ---

    @Test
    fun jsonEscapesAndOptionalParts() {
        val w = TogglWant("k", 1L, "", "a \"quote\" \\ and\nnew\tline \u0001", listOf("now"), 0L, 60L)
        val body = TogglJson.body(w, null, tags = false)
        assertEquals(
            "{\"created_with\":\"Now widget\",\"description\":\"a \\\"quote\\\" \\\\ and\\nnew\\tline \\u0001\",\"workspace_id\":1," +
                "\"start\":\"1970-01-01T00:00:00.000Z\",\"stop\":\"1970-01-01T00:01:00.000Z\",\"duration\":60}",
            body,
        )
        // The body is valid JSON that reads back the same.
        val back = Json.parse(body) as Map<*, *>
        assertEquals(w.description, back["description"])
        assertFalse(back.containsKey("tags"))
        assertFalse(back.containsKey("project_id"))
        assertEquals("{\"name\":\"Hobby \\\"x\\\"\",\"active\":true}", TogglJson.projectBody("Hobby \"x\""))
    }

    @Test
    fun readsTimeEntries() {
        val text = """[{"id":3649112345,"workspace_id":77,"start":"2025-04-09T09:15:00+00:00","stop":"2025-04-09T10:40:00+00:00","duration":5100,"tags":["now","Sam"],"description":"x"},
            {"id":5,"workspace_id":77,"start":"2025-04-09T11:00:00Z","duration":-1,"tags":null},{"workspace_id":1}]"""
        val seen = TogglJson.seen(text)
        assertEquals(2, seen.size)
        assertEquals(TogglSeen(3649112345L, 77L, t(9, 15) / 1000, 5100L, listOf("now", "Sam")), seen[0])
        assertEquals(emptyList<String>(), seen[1].tags)
        assertEquals(t(11) / 1000, seen[1].startSec)
        assertTrue(TogglJson.seen("{}").isEmpty())
        assertNull(TogglJson.parseIso("yesterday"))
    }

    @Test
    fun pendingRecordsRoundTrip() {
        val p = TogglRecord("2025-4-9/e7", 77L, 0L, "abc:def", 1700000000L, 5100L, false)
        val f = TogglRecordCodec.hashField(p)
        assertEquals("pending:1700000000:5100:n:abc:def", f)
        assertEquals(p, TogglRecordCodec.read(p.key, 77L, 0L, f))
        val done = TogglRecord("2025-4-9/e7", 77L, 99L, "abc")
        assertEquals("abc", TogglRecordCodec.hashField(done))
        assertEquals(done, TogglRecordCodec.read(done.key, 77L, 99L, "abc"))
        // A pending record from garbage still reads as a record.
        assertTrue(TogglRecordCodec.read("k", 1L, 0L, "pending:x").pending)
    }
}
