package com.jono.nowwidget.core

import java.util.Locale

/** A link as binding sees it. [scanMade]: the vault scan created it. */
data class BindLink(val id: Long, val title: String, val archived: Boolean = false, val scanMade: Boolean = false)

/** A vault note as binding sees it, keyed by its document id (which survives a rename). */
data class BindNote(val docId: String, val title: String, val aliases: List<String>)

/**
 * How links and vault notes find each other. Pure, so it's unit-tested.
 *
 * Exact titles are matched by the scan itself ([LinkIndex.matchForNote]). On top of that:
 *  - a link whose title matches no note's title but is exactly one note's alias is bound to that
 *    note, and renders as `[[Note Title|Link title]]`;
 *  - a note renamed in the vault (same document id, new name) renames the link that carried its
 *    old title, instead of leaving that link behind and making a new one.
 */
object LinkBinding {
    private fun norm(s: String) = s.trim().lowercase(Locale.ROOT)

    /**
     * link id to the id of the one note it is bound to by alias. A link is bound only when no note
     * has its title, and exactly one note (not several) lists it as an alias. Removed links aren't bound.
     */
    fun aliasBindings(links: List<BindLink>, notes: List<BindNote>): Map<Long, String> {
        val titles = notes.map { norm(it.title) }.toHashSet()
        val owners = HashMap<String, MutableSet<String>>()
        notes.forEach { n ->
            n.aliases.map { norm(it) }.filter { it.isNotEmpty() && it != norm(n.title) }.forEach { a ->
                owners.getOrPut(a) { LinkedHashSet() }.add(n.docId)
            }
        }
        val out = HashMap<Long, String>()
        links.forEach { l ->
            val t = norm(l.title)
            if (l.archived || t.isEmpty() || t in titles) return@forEach
            val who = owners[t] ?: return@forEach
            if (who.size == 1) out[l.id] = who.first()
        }
        return out
    }

    /**
     * True when a link should follow its note to the note's new title: the link still carries the
     * note's old title (so it was bound to it by that title; a link you renamed yourself is left
     * alone), and no other link already has the new one.
     */
    fun followsRename(oldNoteTitle: String, linkTitle: String, newNoteTitle: String, newTitleTakenByOther: Boolean): Boolean =
        norm(oldNoteTitle) == norm(linkTitle) && linkTitle.trim() != newNoteTitle.trim() && !newTitleTakenByOther

    /** What the scan does with one note. */
    sealed class Plan {
        /** Use this existing link as is. A hand-made link is never changed by it. */
        data class Match(val linkId: Long) : Plan()

        /** Make a new link marked as made by the vault. */
        object Create : Plan()

        /** The note was renamed: give its link the new title. */
        data class Rename(val linkId: Long, val newTitle: String) : Plan()
    }

    /**
     * The plan for [note], seen before as [oldTitle] under link [oldLinkId] (both null when the
     * document is new to the cache). [links] are all the links. A renamed note renames its link;
     * otherwise the same title (any case) means that link; otherwise a new link.
     */
    fun plan(note: BindNote, kind: String, oldTitle: String?, oldLinkId: Long?, links: List<LinkEntry>): Plan {
        val byOld = oldLinkId?.let { id -> links.firstOrNull { it.id == id } }
        if (oldTitle != null && byOld != null && oldTitle.trim() != note.title.trim()) {
            val taken = links.any { it.id != byOld.id && norm(it.title) == norm(note.title) }
            if (followsRename(oldTitle, byOld.title, note.title, taken)) return Plan.Rename(byOld.id, note.title.trim())
        }
        val found = LinkIndex.matchForNote(links, note.title, kind)
        return if (found == null) Plan.Create else Plan.Match(found.id)
    }
}
