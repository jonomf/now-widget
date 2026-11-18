package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VaultNoteTest {
    private fun parse(name: String, text: String) = VaultNotes.parse(name, text.split("\n"))

    @Test
    fun tagsDecideTheKind() {
        assertEquals(LinkKind.PERSON, parse("Sam.md", "#person in [[Team]]\nblah")!!.kind)
        assertEquals(LinkKind.PERSON, parse("Mayor.md", "#person-official")!!.kind)
        assertEquals(LinkKind.PLACE, parse("Tartine.md", "#place-com ")!!.kind)
        assertEquals(LinkKind.PLACE, parse("The Loft.md", "#place\n")!!.kind)
        assertEquals(LinkKind.CONTEXT, parse("Conf.md", "#event")!!.kind)
        assertEquals(LinkKind.CONTEXT, parse("Conf 2.md", "#event-past")!!.kind)
        assertEquals(LinkKind.CONTEXT, parse("Conf 3.md", "#event-ongoing-log")!!.kind)
        assertEquals(LinkKind.OTHER, parse("Film.md", "#media")!!.kind)
        assertEquals(LinkKind.OTHER, parse("Plain.md", "just words")!!.kind)
        assertEquals(LinkKind.OTHER, parse("Empty.md", "")!!.kind)
        assertEquals(LinkKind.OTHER, parse("Heading.md", "# person\n")!!.kind)
        assertEquals(LinkKind.OTHER, parse("Late.md", "words first\n#person")!!.kind)
        assertEquals(LinkKind.PERSON, parse("Upper.md", "#Person")!!.kind)
    }

    @Test
    fun tripNotesAreContexts() {
        assertEquals(LinkKind.CONTEXT, parse("2025-4 Trip Home.md", "")!!.kind)
        assertEquals(LinkKind.CONTEXT, parse("2024-10 trip home.md", "some words")!!.kind)
        assertEquals(LinkKind.CONTEXT, parse("Road-Trip.md", "")!!.kind)
        assertEquals(LinkKind.OTHER, parse("Strip Mall.md", "")!!.kind)
        // A person or place tag still wins.
        assertEquals(LinkKind.PLACE, parse("Trip Hotel.md", "#place")!!.kind)
    }

    @Test
    fun plainDailyNotesAndOwnNotesAreSkipped() {
        assertNull(parse("2025-4-9.md", "#log"))
        assertNull(parse("2025-12-31.md", "#event"))
        assertNull(parse("2025-4-9 (now).md", "#log-now"))
        assertNull(parse("2025-4-9 (now) (1).md", "#log-now"))
        assertNull(parse("Weird (now).md", ""))
        assertNull(parse("image.png", ""))
        assertNull(parse(".md", ""))
        assertEquals("2025-4 Trip Home", parse("2025-4 Trip Home.md", "")!!.title)
        assertEquals("2025", parse("2025.md", "")!!.title)
        assertEquals(false, VaultNotes.candidate("2025-4-9.md"))
        assertEquals(false, VaultNotes.candidate("2025-4-9 (now).md"))
        assertEquals(false, VaultNotes.candidate("2025-4-9 (now) (1).md"))
        assertEquals(true, VaultNotes.candidate("2025-4-9 (long).md"))
        assertEquals(true, VaultNotes.candidate("Sam.md"))
        assertEquals(false, VaultNotes.candidate("Sam.png"))
    }

    @Test
    fun dailyCompanionsAreSkipped() {
        assertNull(parse("2025-4-9 (long).md", ""))
        assertNull(parse("2025-4-9 (VRChat).md", "#log"))
        assertNull(parse("2025-4-9 14-30.md", "#log"))
        assertNull(parse("2025-4-9 9-05.md", ""))
        assertEquals(true, VaultNotes.isCompanion("2025-4-9 (long)"))
        assertEquals(true, VaultNotes.isCompanion("2025-4-9 14-30"))
        assertEquals(false, VaultNotes.isCompanion("2025-4-9 -- Party"))
    }

    @Test
    fun datedEventNotesAreKeptAsContexts() {
        // The "-- Title" notes are kept, tagged or not.
        val party = parse("2025-12-31 -- Party.md", "#event")!!
        assertEquals("2025-12-31 -- Party", party.title)
        assertEquals(LinkKind.CONTEXT, party.kind)
        assertEquals(LinkKind.CONTEXT, parse("2025-6-1 -- Fair.md", "#event-com in [[Town]]")!!.kind)
        assertEquals(LinkKind.OTHER, parse("2025-6-1 -- Thoughts.md", "#log")!!.kind)
        // Any other dated note whose first-line tag is an event is a context.
        assertEquals(LinkKind.CONTEXT, parse("2025-6-1 Concert.md", "#event")!!.kind)
        assertEquals(LinkKind.CONTEXT, parse("2025-6-1 (long).md", "#event")!!.kind)
        assertEquals(LinkKind.CONTEXT, parse("2025-6-1 20-00.md", "---\ntags: x\n---\n#event-com")!!.kind)
        // ...but a plain daily note never is.
        assertNull(parse("2025-6-1.md", "#event"))
    }

    @Test
    fun crlfAndBomAreHandled() {
        val n = VaultNotes.parse("Sam.md", listOf("﻿---\r", "aliases:\r", "  - Sammy\r", "---\r", "\r", "#person\r"))!!
        assertEquals(LinkKind.PERSON, n.kind)
        assertEquals(listOf("Sammy"), n.aliases)
        assertEquals(LinkKind.PLACE, VaultNotes.parse("X.md", listOf("﻿#place\r"))!!.kind)
        assertEquals(LinkKind.PLACE, VaultNotes.parse("Y.md", listOf("\r", "", "#place-com\r"))!!.kind)
    }

    @Test
    fun aliasesInEveryShape() {
        val block = parse("Plan.md", "---\naliases:\n  - \"M4: The Plan\"\n  - 'It''s a plan'\n  - Plain Alias\nother: x\n---\n#media")!!
        assertEquals(listOf("M4: The Plan", "It's a plan", "Plain Alias"), block.aliases)
        assertEquals(LinkKind.OTHER, block.kind)

        assertEquals(emptyList<String>(), parse("A.md", "---\naliases: []\n---\n#person")!!.aliases)
        assertEquals(listOf("Al", "Bo, the Great"), parse("B.md", "---\naliases: [Al, \"Bo, the Great\"]\n---\n")!!.aliases)
        assertEquals(listOf("Solo"), parse("C.md", "---\nalias: Solo\n---\n")!!.aliases)
        assertEquals(listOf("One"), parse("D.md", "---\nalias:\n- One\n---\n")!!.aliases)
        assertEquals(listOf("Dup"), parse("E.md", "---\naliases:\n  - Dup\n  - dup\n  - E\n---\n")!!.aliases)
        assertEquals(listOf("Other"), parse("F.md", "---\ntags: [x]\nAliases:\n  - Other\n---\n#person")!!.aliases)
        // An unclosed frontmatter reads what's there and finds no tag.
        val open = parse("G.md", "---\naliases:\n  - Gee\n#person")!!
        assertEquals(listOf("Gee"), open.aliases)
        assertEquals(LinkKind.OTHER, open.kind)
    }

    @Test
    fun frontmatterThenTheTagLine() {
        assertEquals(LinkKind.PERSON, parse("Sam.md", "---\ntags: [a]\n---\n\n\n#person in [[Team]]")!!.kind)
        // Dashes that aren't frontmatter (not on line 1) don't hide the tag.
        assertEquals(LinkKind.OTHER, parse("Z.md", "intro\n---\n#person")!!.kind)
    }

    @Test
    fun onlyTheTopOfTheNoteIsRead() {
        val far = (1..60).joinToString("\n") { "line $it" } + "\n#person"
        assertEquals(LinkKind.OTHER, parse("Far.md", far)!!.kind)
    }

    @Test
    fun aliasesPackIntoTheCache() {
        val a = listOf("M4: The Plan", "Plain")
        assertEquals(a, VaultNotes.unpackAliases(VaultNotes.packAliases(a)))
        assertEquals(emptyList<String>(), VaultNotes.unpackAliases(""))
    }
}
