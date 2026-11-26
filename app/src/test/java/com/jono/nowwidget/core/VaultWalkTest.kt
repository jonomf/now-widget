package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultWalkTest {
    /** A fake vault: folder id to children. */
    private val tree = mapOf(
        "root" to listOf(
            TreeEntry("r/2025-4-9.md", "2025-4-9.md", 1, false),
            TreeEntry("r/21H.md", "21H.md", 1, false),
            TreeEntry("r/.obsidian", ".obsidian", 0, true),
            TreeEntry("r/.trash", ".trash", 0, true),
            TreeEntry("r/_templates", "_templates", 0, true),
            TreeEntry("r/_jonoco", "_jonoco", 0, true),
        ),
        "r/.obsidian" to listOf(TreeEntry("r/.obsidian/workspace.json", "workspace.json", 1, false)),
        "r/.trash" to listOf(TreeEntry("r/.trash/Old.md", "Old.md", 1, false)),
        "r/_templates" to listOf(TreeEntry("r/_templates/index.md", "index.md", 1, false)),
        "r/_jonoco" to listOf(
            TreeEntry("j/Mom.md", "Mom.md", 1, false),
            TreeEntry("j/The Loft.md", "The Loft.md", 1, false),
            TreeEntry("j/Fam", "Fam", 0, true),
            TreeEntry("j/Daily notes", "Daily notes", 0, true),
        ),
        "j/Fam" to listOf(TreeEntry("j/Fam/21H.md", "21H.md", 1, false), TreeEntry("j/Fam/a", "a", 0, true)),
        "j/Daily notes" to listOf(TreeEntry("j/d/2024-3-2.md", "2024-3-2.md", 1, false)),
        "j/Fam/a" to listOf(TreeEntry("j/Fam/a/b", "b", 0, true)),
        "j/Fam/a/b" to listOf(TreeEntry("deep.md", "Deep.md", 1, false)),
    )

    private fun walk(maxDepth: Int = VaultWalk.MAX_DEPTH, maxFiles: Int = VaultWalk.MAX_FILES) =
        VaultWalk.walk("root", { id -> tree[id] ?: throw IllegalStateException("can't list $id") }, maxDepth, maxFiles)

    @Test
    fun walksEveryFolderButHiddenAndTemplates() {
        val r = walk()
        val names = r.files.map { it.entry.name }
        assertEquals(listOf("2025-4-9.md", "21H.md", "Mom.md", "The Loft.md", "2024-3-2.md", "21H.md", "Deep.md"), names)
        assertTrue(names.none { it == "Old.md" || it == "index.md" || it == "workspace.json" })
        assertEquals(listOf("_jonoco", "Fam"), r.files.first { it.entry.docId == "j/Fam/21H.md" }.path)
        assertEquals(0, r.files.first().depth)
        assertEquals(6, r.folders) // root, _jonoco, Daily notes, Fam, a, b
        assertFalse(r.truncated)
        assertTrue(r.root.any { it.name == ".obsidian" }) // still there for workspace.json
    }

    @Test
    fun theWalkIsBounded() {
        val shallow = walk(maxDepth = 2)
        assertTrue(shallow.files.none { it.entry.name == "Deep.md" })
        assertTrue(shallow.truncated)
        val few = walk(maxFiles = 3)
        assertEquals(3, few.files.size)
        assertTrue(few.truncated)
        // A folder that can't be listed is skipped, not fatal.
        val broken = VaultWalk.walk("root", { id -> if (id == "j/Fam") throw IllegalStateException("no") else tree[id] ?: emptyList() })
        assertTrue(broken.files.none { it.entry.docId == "j/Fam/21H.md" })
        assertTrue(broken.files.any { it.entry.name == "Mom.md" })
    }

    @Test
    fun duplicateTitlesPreferATagThenTheShallowerPath() {
        data class N(val title: String, val kind: String, val depth: Int, val path: String)
        val notes = listOf(
            N("21H", LinkKind.OTHER, 0, ""), N("21h", LinkKind.PLACE, 2, "_jonoco/Fam"),
            N("Mom", LinkKind.PERSON, 2, "b/c"), N("Mom", LinkKind.PERSON, 1, "_jonoco"),
        )
        val g = VaultWalk.byTitle(notes, { it.title }, { it.kind }, { it.depth }, { it.path })
        assertEquals("_jonoco/Fam", g["21h"]!!.first().path)
        assertEquals(2, g["21h"]!!.size)
        assertEquals("_jonoco", g["mom"]!!.first().path)
    }

    @Test
    fun aMovedNoteIsTheSameNote() {
        val gone = mapOf("primary:Mom.md" to "Mom.md", "primary:Gone.md" to "Gone.md", "primary:a/X.md" to "X.md", "primary:b/X.md" to "X.md")
        val new = mapOf("primary:_jonoco/Mom.md" to "Mom.md", "primary:c/X.md" to "X.md", "primary:New.md" to "New.md")
        // Mom moved; X is ambiguous (two vanished), so it's treated as new; Gone is gone.
        assertEquals(mapOf("primary:_jonoco/Mom.md" to "primary:Mom.md"), VaultWalk.moves(gone, new))
        // Two new files with one name: ambiguous too.
        assertTrue(VaultWalk.moves(mapOf("1" to "Y.md"), mapOf("2" to "Y.md", "3" to "y.md")).isEmpty())
    }

    @Test
    fun aMovedNoteKeepsItsLink() {
        // The scan uses the old row's title and link for a moved note: same title, so the same link.
        val links = listOf(LinkEntry(7, "Mom", LinkKind.PERSON, emptyList(), 4, false))
        val plan = LinkBinding.plan(BindNote("primary:_jonoco/Mom.md", "Mom", emptyList()), LinkKind.PERSON, "Mom", 7, links)
        assertEquals(LinkBinding.Plan.Match(7), plan)
        // Moved and renamed at once still follows the rename.
        val renamed = LinkBinding.plan(BindNote("primary:_jonoco/Mother.md", "Mother", emptyList()), LinkKind.PERSON, "Mom", 7, links)
        assertEquals(LinkBinding.Plan.Rename(7, "Mother"), renamed)
    }

    // --- Merging mention-made links into their notes ---

    private fun c(id: Long, title: String, uses: Int, mentions: Boolean, note: Boolean, kind: String = LinkKind.PERSON, aliases: List<String> = emptyList()) =
        LinkMerge.Cand(id, title, kind, uses, mentions, note, aliases)

    @Test
    fun noNoteLinksMergeIntoTheirNotes() {
        val steps = LinkMerge.plan(
            listOf(
                c(1, "Mom", 12, mentions = true, note = false),          // the mentions made it; entries use it
                c(2, "Mom", 0, mentions = false, note = true),           // _jonoco/Mom.md, found now
                c(3, "The loft", 0, mentions = true, note = false, kind = LinkKind.PLACE),
                c(4, "The Loft", 3, mentions = false, note = true, kind = LinkKind.PLACE),
                c(5, "Simon", 2, mentions = true, note = true),          // matched by title: just relabel
                c(6, "Dave", 1, mentions = true, note = false),
                c(7, "David", 0, mentions = false, note = true, aliases = listOf("dave")),
                c(8, "Ted", 1, mentions = true, note = false),           // no note at all: stays
                c(9, "Steve", 1, mentions = true, note = false),
                c(10, "Steve", 0, mentions = false, note = true, kind = LinkKind.PLACE), // another kind: not merged
            )
        )
        assertEquals(
            listOf(
                LinkMerge.Step.Merge(keep = 1, drop = 2),  // more entries: the old id stays, the note joins it
                LinkMerge.Step.Merge(keep = 4, drop = 3),
                LinkMerge.Step.Relabel(5),
                LinkMerge.Step.Merge(keep = 6, drop = 7),  // by alias; ties go to the note, Dave has more uses
            ),
            steps,
        )
        // A tie keeps the note's link.
        assertEquals(listOf(LinkMerge.Step.Merge(keep = 2, drop = 1)),
            LinkMerge.plan(listOf(c(1, "Mom", 0, true, false), c(2, "Mom", 0, false, true))))
    }
}
