package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkBindingTest {
    private fun link(id: Long, title: String, kind: String = LinkKind.PERSON, archived: Boolean = false) =
        LinkEntry(id, title, kind, emptyList(), 0, archived)

    private fun note(doc: String, title: String, vararg aliases: String) = BindNote(doc, title, aliases.toList())

    // --- Aliases ---

    @Test
    fun aLinkThatIsOneNotesAliasIsBoundToIt() {
        val links = listOf(BindLink(1, "Tae"), BindLink(2, "Sam"))
        val notes = listOf(note("d1", "Taehyun Kim", "Tae", "T"), note("d2", "Sam"))
        assertEquals(mapOf(1L to "d1"), LinkBinding.aliasBindings(links, notes))
    }

    @Test
    fun anExactTitleBeatsAnAlias() {
        val links = listOf(BindLink(1, "Sam"))
        val notes = listOf(note("d1", "Sam"), note("d2", "Samuel", "sam"))
        assertEquals(emptyMap<Long, String>(), LinkBinding.aliasBindings(links, notes))
    }

    @Test
    fun anAliasSharedByTwoNotesBindsNothing() {
        val links = listOf(BindLink(1, "Alex"))
        val notes = listOf(note("d1", "Alexander", "Alex"), note("d2", "Alexandra", "ALEX"))
        assertEquals(emptyMap<Long, String>(), LinkBinding.aliasBindings(links, notes))
        // One note listing it twice is still one note.
        assertEquals(mapOf(1L to "d1"), LinkBinding.aliasBindings(links, listOf(note("d1", "Alexander", "Alex", "alex"))))
    }

    @Test
    fun removedLinksAndMissAreNotBound() {
        val links = listOf(BindLink(1, "Tae", archived = true), BindLink(2, "Nobody"))
        assertEquals(emptyMap<Long, String>(), LinkBinding.aliasBindings(links, listOf(note("d1", "Taehyun", "Tae"))))
    }

    @Test
    fun aBoundLinkIsWrittenWithTheNoteTitleAndItsOwnText() {
        val data = DayData(
            2025, 4, 9,
            listOf(DaySpan(1, 1, "Eat", "🍽️", "", 0, 3_600_000, people = listOf("Tae"), places = listOf("Loft"))),
            emptyList(),
            java.util.TimeZone.getTimeZone("UTC"),
            notes = mapOf("Tae" to "Taehyun Kim"),
        )
        val text = ObsidianDay.render(data)
        assertTrue(text, text.contains("w [[Taehyun Kim|Tae]]"))
        assertTrue(text, text.contains("at [[Loft]]"))
    }

    // --- Rename ---

    @Test
    fun aRenamedNoteRenamesItsLink() {
        val links = listOf(link(1, "Old Cafe", LinkKind.PLACE))
        val p = LinkBinding.plan(note("d1", "New Cafe"), LinkKind.PLACE, "Old Cafe", 1, links)
        assertEquals(LinkBinding.Plan.Rename(1, "New Cafe"), p)
    }

    @Test
    fun aCaseOnlyRenameCounts() {
        val p = LinkBinding.plan(note("d1", "old cafe"), LinkKind.PLACE, "Old Cafe", 1, listOf(link(1, "Old Cafe")))
        assertEquals(LinkBinding.Plan.Rename(1, "old cafe"), p)
    }

    @Test
    fun aLinkYouRenamedYourselfIsLeftAlone() {
        val links = listOf(link(1, "My Cafe", LinkKind.PLACE))
        // The note was "Old Cafe" and is now "New Cafe", but the link says something else: no title is changed.
        val p = LinkBinding.plan(note("d1", "New Cafe"), LinkKind.PLACE, "Old Cafe", 1, links)
        assertEquals(LinkBinding.Plan.Create, p)
    }

    @Test
    fun aRenameDoesNotTakeAnotherLinksTitle() {
        val links = listOf(link(1, "Old Cafe", LinkKind.PLACE), link(2, "New Cafe", LinkKind.PLACE))
        val p = LinkBinding.plan(note("d1", "New Cafe"), LinkKind.PLACE, "Old Cafe", 1, links)
        assertEquals(LinkBinding.Plan.Match(2), p)
    }

    @Test
    fun anUnchangedNoteMatchesItsLinkAndNewOnesMakeLinks() {
        val links = listOf(link(1, "Sam"))
        assertEquals(LinkBinding.Plan.Match(1), LinkBinding.plan(note("d1", "sam"), LinkKind.PERSON, "sam", 1, links))
        assertEquals(LinkBinding.Plan.Match(1), LinkBinding.plan(note("d9", "SAM"), LinkKind.PERSON, null, null, links))
        assertEquals(LinkBinding.Plan.Create, LinkBinding.plan(note("d3", "Dulce"), LinkKind.PERSON, null, null, links))
    }

    @Test
    fun aRenamedNotesAliasBindingFollowsItsDocument() {
        val links = listOf(BindLink(1, "Tae"))
        val before = LinkBinding.aliasBindings(links, listOf(note("d1", "Taehyun Kim", "Tae")))
        val after = LinkBinding.aliasBindings(links, listOf(note("d1", "Tae Kim", "Tae")))
        assertEquals(mapOf(1L to "d1"), before)
        assertEquals(before, after) // same document; the stored note title is what the scan updates
    }

    // --- Hand-made links stay chips ---

    @Test
    fun handMadePeopleAndPlacesAreNeverRemadeOrUnpinnedByAScan() {
        // What a v2 install has: hand-made links, no vault marks.
        val links = listOf(link(1, "Sam"), link(2, "The Loft", LinkKind.PLACE), link(3, "Dulce"))
        val scan = listOf(
            note("d1", "Sam") to LinkKind.PERSON,
            note("d2", "The Loft") to LinkKind.PLACE,
            note("d4", "Tartine") to LinkKind.PLACE,
        )
        val plans = scan.map { (n, k) -> n.title to LinkBinding.plan(n, k, null, null, links) }.toMap()
        assertEquals(LinkBinding.Plan.Match(1), plans["Sam"])
        assertEquals(LinkBinding.Plan.Match(2), plans["The Loft"])
        // Only the note with no link makes one (and that link is the scan-made kind that stays off the chips).
        assertEquals(LinkBinding.Plan.Create, plans["Tartine"])
        // Matched links are not scan-made, so they stay chips whether or not they're used or pinned.
        val handMade = Link(1, "Sam", LinkKind.PERSON, "", 0, false, pinned = false, inVault = true, fromVault = false, uses = 0)
        assertTrue(handMade.chip)
        val scanMade = handMade.copy(fromVault = true)
        assertFalse(scanMade.chip)
        assertTrue(scanMade.copy(uses = 1).chip)
        assertTrue(scanMade.copy(pinned = true).chip)
    }
}
