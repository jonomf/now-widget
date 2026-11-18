package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkIndexTest {
    private fun e(id: Long, title: String, kind: String = LinkKind.PERSON, aliases: List<String> = emptyList(), uses: Int = 0, archived: Boolean = false) =
        LinkEntry(id, title, kind, aliases, uses, archived)

    private val index = LinkIndex(
        listOf(
            e(1, "Sam", uses = 20),
            e(2, "Samantha Reed", uses = 3),
            e(3, "Samuel Jones", aliases = listOf("Sammy J")),
            e(4, "Dulce", uses = 9),
            e(5, "The Loft", LinkKind.PLACE, uses = 7),
            e(6, "Tartine", LinkKind.PLACE, aliases = listOf("Tartine Bakery")),
            e(7, "2025-4 Trip Home", LinkKind.CONTEXT),
            e(8, "Old Sam", archived = true),
            e(9, "Bob's Bakery", LinkKind.PLACE),
        )
    )
    private val people = setOf(LinkKind.PERSON)

    private fun titles(q: String, kinds: Set<String> = people, limit: Int = 8) = index.suggest(q, kinds, limit).map { it.link.title }

    @Test
    fun mostUsedComesFirstWithinTheSameKindOfMatch() {
        assertEquals(listOf("Sam", "Samantha Reed", "Samuel Jones", "Old Sam"), titles("sam"))
    }

    @Test
    fun exactBeatsPrefixBeatsWordBeatsContains() {
        val i = LinkIndex(listOf(e(1, "Xsamx", uses = 50), e(2, "Big Sam", uses = 40), e(3, "Samson", uses = 30), e(4, "Sam", uses = 1)))
        assertEquals(listOf("Sam", "Samson", "Big Sam", "Xsamx"), i.suggest("SAM", people).map { it.link.title })
    }

    @Test
    fun aliasesMatchAndPointAtTheCanonicalLink() {
        val s = index.suggest("sammy", people)
        assertEquals(1, s.size)
        assertEquals("Samuel Jones", s[0].link.title)
        assertEquals("Sammy J", s[0].alias)
        val bakery = index.suggest("tartine b", setOf(LinkKind.PLACE))
        assertEquals("Tartine", bakery[0].link.title)
        assertEquals("Tartine Bakery", bakery[0].alias)
        // A title match isn't reported as an alias match.
        assertNull(index.suggest("tartine", setOf(LinkKind.PLACE))[0].alias)
    }

    @Test
    fun kindsFilter() {
        assertEquals(listOf("The Loft"), titles("loft", setOf(LinkKind.PLACE)))
        assertEquals(emptyList<String>(), titles("loft"))
        assertEquals(listOf("2025-4 Trip Home"), titles("trip", setOf(LinkKind.CONTEXT, LinkKind.OTHER)))
        assertEquals(listOf("Bob's Bakery"), titles("bak", setOf(LinkKind.PLACE)).filter { it.startsWith("Bob") })
    }

    @Test
    fun nothingTypedShowsTheMostUsed() {
        assertEquals(listOf("Sam", "Dulce", "Samantha Reed"), titles(""))
        assertEquals(listOf("Sam"), titles("", limit = 1))
    }

    @Test
    fun wordStartsMatchInsideTitles() {
        assertEquals(listOf("2025-4 Trip Home"), titles("home", setOf(LinkKind.CONTEXT)))
        assertEquals(listOf("Samantha Reed"), titles("reed"))
    }

    @Test
    fun resolveFindsExactTitlesAndAliasesOnly() {
        assertEquals(3L, index.resolve("sammy j", people)!!.id)
        assertEquals(1L, index.resolve(" SAM ", people)!!.id)
        assertNull(index.resolve("Sa", people))
        assertNull(index.resolve("Sam", setOf(LinkKind.PLACE)))
        assertNull(index.resolve("", people))
    }

    @Test
    fun aVaultNoteMatchesAnExistingLinkInAnyCase() {
        val links = listOf(
            e(1, "tartine", LinkKind.PLACE, archived = true),
            e(2, "Tartine", LinkKind.PERSON),
            e(3, "TARTINE", LinkKind.PLACE),
        )
        assertEquals(3L, LinkIndex.matchForNote(links, "Tartine", LinkKind.PLACE)!!.id)
        assertEquals(2L, LinkIndex.matchForNote(links, "Tartine", LinkKind.PERSON)!!.id)
        assertEquals(2L, LinkIndex.matchForNote(links, "Tartine", LinkKind.OTHER)!!.id)
        assertNull(LinkIndex.matchForNote(links, "Other", LinkKind.PLACE))
    }
}
