package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionsTest {
    private val day = 86_400_000L
    private val now = 20_000L * day

    // --- Files ---

    @Test
    fun datedNotesButNotOurs() {
        assertEquals(Triple(2025, 4, 9), Mentions.datedNote("2025-4-9.md"))
        assertEquals(Triple(2025, 4, 9), Mentions.datedNote("2025-04-09 (long).md"))
        assertEquals(Triple(2025, 4, 9), Mentions.datedNote("2025-4-9 -- Dinner.md"))
        assertNull(Mentions.datedNote("2025-4-9 (now).md"))
        assertNull(Mentions.datedNote("2025-4-9 (now) (1).md"))
        assertNull(Mentions.datedNote("Dulce.md"))
        assertNull(Mentions.datedNote("2025-13-9.md"))
        assertNull(Mentions.datedNote("2025-4-9.png"))
        assertNull(Mentions.datedNote("20251-4-9.md"))
    }

    // --- Links ---

    @Test
    fun linkParserStripsAliasHeadingBlockAndPath() {
        assertEquals(
            listOf("Dulce", "Atlas Cafe", "Mateo", "The Loft", "Schoen", "Mom"),
            Mentions.links("[[Dulce|D]] then [[Atlas Cafe#Menu]] and [[Mateo^abc12]], [[People/The Loft]] [[Schoen.md]] [[ Mom ]]"),
        )
        // Files and empties aren't pages.
        assertEquals(listOf("Sam"), Mentions.links("![[photo.JPG]] [[ ]] [[#Heading]] [[Sam]] [[scan.pdf]]"))
        assertEquals(Mentions.key("The Loft"), Mentions.key("the  loft"))
    }

    // --- Headers ---

    @Test
    fun headerAtAndWithClauses() {
        val h = Mentions.header("At [[X]] w [[A]], [[B]] and [[C]]")
        assertEquals(listOf("X"), h.at)
        assertEquals(listOf("A", "B", "C"), h.with)
        assertEquals(listOf("Mom", "Dave"), Mentions.header("dinner with [[Mom]] & [[Dave]]").with)
        assertEquals(listOf("Simon"), Mentions.header("- 9a coffee w/ [[Simon]]").with)
        assertEquals(listOf("Simon"), Mentions.header("coffee w/[[Simon]]").with)
        assertEquals(listOf("The loft"), Mentions.header("work at [[The loft]] all day").at)
        assertEquals(listOf("21H"), Mentions.header("@[[21H]] w [[Cameron]]").at)
        assertEquals(listOf("Cameron"), Mentions.header("@ [[21H]] w [[Cameron]]").with)
        // Not inside a word ("flat", "new"), and a link not right after the word isn't in the clause.
        val none = Mentions.header("my flat [[Kitchen]] new [[Thing]] with a [[Book]]")
        assertTrue(none.at.isEmpty())
        assertTrue(none.with.isEmpty())
        // A chain stops at anything that isn't a separator.
        assertEquals(listOf("A"), Mentions.header("w [[A]] then [[B]]").with)
    }

    @Test
    fun aNoteCountsEachTargetOnceWithItsEvidence() {
        val note = """
            #daily
            At [[The Loft]] w [[Mom]] and [[Dulce]]
            - talked about [[Dulce]] and [[Atlas Cafe]]
            - later at [[the loft]] again
        """.trimIndent()
        val m = Mentions.parseNote(note)
        assertEquals(setOf("the loft", "mom", "dulce", "atlas cafe"), m.keys)
        assertEquals(Mentions.NoteMention("The Loft", at = true, with = false), m["the loft"])
        assertTrue(m["dulce"]!!.with)
        assertEquals(Mentions.NoteMention("Atlas Cafe", at = false, with = false), m["atlas cafe"])
    }

    // --- Scores ---

    private fun row(doc: String, key: String, daysAgo: Int, at: Boolean = false, w: Boolean = false, spelled: String = key) =
        Mentions.Row(doc, key, spelled, now - daysAgo * day, at, w)

    @Test
    fun scoreHalvesEvery180DaysAndHeadersCountDouble() {
        assertEquals(1.0, Mentions.weight(now, now), 1e-9)
        assertEquals(0.5, Mentions.weight(now - 180 * day, now), 1e-9)
        assertEquals(1.0, Mentions.weight(now + day, now), 1e-9) // never more than now
        val t = Mentions.targets(listOf(row("a", "x", 0), row("b", "x", 180, w = true), row("c", "y", 360)), now)
        assertEquals(1.0 + 2 * 0.5, t["x"]!!.score, 1e-9)
        assertEquals(0.25, t["y"]!!.score, 1e-9)
        assertEquals(2, t["x"]!!.notes)
        assertEquals(1, t["x"]!!.withNotes)
    }

    @Test
    fun caseVariantsMergeUnderTheMostUsedSpelling() {
        val t = Mentions.targets(
            listOf(row("a", "the loft", 1, spelled = "The Loft"), row("b", "the loft", 2, spelled = "The loft"), row("c", "the loft", 3, spelled = "The Loft")),
            now,
        )["the loft"]!!
        assertEquals("The Loft", t.title)
        assertEquals(3, t.notes)
        // A tie goes to the most recent spelling.
        assertEquals("the LOFT", Mentions.targets(listOf(row("a", "the loft", 9, spelled = "The Loft"), row("b", "the loft", 1, spelled = "the LOFT")), now)["the loft"]!!.title)
    }

    // --- Kind and promotion ---

    private fun target(notes: Int, at: Int, w: Int) = Mentions.Target("k", "K", 1.0, notes, at, w)

    @Test
    fun kindFromTheTagElseTheHeaders() {
        assertEquals(LinkKind.PERSON, Mentions.kind(target(5, 5, 0), LinkKind.PERSON)) // the tag decides
        assertEquals(LinkKind.PLACE, Mentions.kind(target(5, 3, 1), null))
        assertEquals(LinkKind.PLACE, Mentions.kind(target(5, 3, 1), LinkKind.OTHER))   // an untagged note: headers
        assertEquals(LinkKind.PERSON, Mentions.kind(target(5, 1, 3), null))
        assertEquals(LinkKind.OTHER, Mentions.kind(target(5, 2, 2), null))
        assertEquals(LinkKind.OTHER, Mentions.kind(target(5, 0, 0), null))
    }

    @Test
    fun unresolvedTargetsBecomeLinksFromThreeMentions() {
        assertEquals(LinkKind.PERSON, Mentions.promote(target(3, 0, 2)))
        assertEquals(LinkKind.PLACE, Mentions.promote(target(4, 3, 0)))
        assertNull(Mentions.promote(target(2, 0, 2)))  // too few
        assertNull(Mentions.promote(target(9, 0, 0)))  // no evidence: just a page
        assertNull(Mentions.promote(target(9, 2, 2)))
    }

    // --- Last open files ---

    @Test
    fun lastOpenFilesGiveABoostQuietly() {
        val json = """{"main":{},"lastOpenFiles":["People/Dulce.md","2025-4-9.md","img.png","Atlas Cafe.md"]}"""
        assertEquals(setOf("dulce", "2025-4-9", "atlas cafe"), Mentions.lastOpen(json))
        assertTrue(Mentions.lastOpen("not json").isEmpty())
        assertTrue(Mentions.lastOpen(null).isEmpty())
        assertTrue(Mentions.lastOpen("""{"main":{}}""").isEmpty())
    }

    // --- The combined ranking ---

    private fun person(id: Long, title: String, fromVault: Boolean = false) = Link(id, title, LinkKind.PERSON, "", id.toInt(), false, fromVault = fromVault)

    @Test
    fun combinedRankingWeighsNowThreeTimesThenName() {
        assertEquals(3.0 * 0.5 + 2.0, Mentions.combined(0.5, 2.0), 1e-9)
        val now = mapOf(1L to 1.0)      // Sam: used in Now lately
        val vault = mapOf(2L to 2.5, 3L to 2.5, 1L to 0.0) // Dulce and Mateo: in his notes
        val ranks = Mentions.combined(now, vault)
        val people = listOf(person(1, "Sam"), person(2, "Mateo", true), person(3, "Dulce", true), person(4, "Zed"), person(5, "amy"))
        assertEquals(listOf(1L, 3L, 2L, 5L, 4L), Mentions.ranked(people, ranks, { it.id }, { it.title }).map { it.id })
        // In the flow the defaults still come first, then the same ranking.
        assertEquals(listOf(4L, 1L, 3L, 2L, 5L), CheckInFlow.candidates(people, ranks, listOf(4L)).map { it.id })
        // A suggestion list uses it too.
        val index = LinkIndex(people.map { LinkEntry(it.id, it.title, it.kind, emptyList(), 0, false) }, ranks)
        assertEquals(listOf(1L, 3L, 2L), index.suggest("", setOf(LinkKind.PERSON), 3).map { it.link.id })
    }
}
