package com.jono.nowwidget.core

import java.util.Locale

/** One child of a vault folder as the provider lists it. */
data class TreeEntry(val docId: String, val name: String, val modified: Long, val isDir: Boolean)

/** A file found by [VaultWalk.walk]: where it is ([path] of folder names, empty at the root). */
data class WalkFile(val entry: TreeEntry, val path: List<String>) {
    val depth: Int get() = path.size
}

/**
 * Walks the whole vault, not just its root: Obsidian resolves `[[Name]]` by file name in any folder
 * (his people live in `_jonoco/`, some daily notes in `_jonoco/Daily notes`). Pure: the folder
 * listing is passed in, so a fake tree tests it.
 */
object VaultWalk {
    const val MAX_DEPTH = 6
    const val MAX_FILES = 20_000

    class Result(
        val files: List<WalkFile>,
        /** Folders listed, the root included. */
        val folders: Int,
        /** The walk stopped at [MAX_FILES] or skipped folders deeper than [MAX_DEPTH]. */
        val truncated: Boolean,
        /** The root's own children (for `.obsidian/workspace.json`). */
        val root: List<TreeEntry>,
    )

    /** Hidden folders (`.obsidian`, `.trash`, …) and `_templates` are never read. */
    fun skipFolder(name: String): Boolean = name.startsWith(".") || name.equals("_templates", ignoreCase = true)

    /**
     * Breadth first, so a shallow note is always seen before a deeper one; folders below
     * [maxDepth] levels and files past [maxFiles] are left out (and [Result.truncated] says so).
     * A folder that can't be listed is skipped: [list] may throw for it.
     */
    fun walk(
        rootId: String,
        list: (String) -> List<TreeEntry>,
        maxDepth: Int = MAX_DEPTH,
        maxFiles: Int = MAX_FILES,
    ): Result {
        val files = ArrayList<WalkFile>()
        var folders = 0
        var truncated = false
        val rootList = list(rootId)
        val queue = ArrayDeque<Triple<String, List<String>, List<TreeEntry>?>>()
        queue.add(Triple(rootId, emptyList(), rootList))
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val (id, path, known) = queue.removeFirst()
            if (!seen.add(id)) continue // a folder linked twice
            val children = known ?: try {
                list(id)
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            folders++
            for (c in children.sortedBy { it.name.lowercase(Locale.ROOT) }) {
                if (c.isDir) {
                    if (skipFolder(c.name)) continue
                    if (path.size + 1 > maxDepth) {
                        truncated = true
                        continue
                    }
                    queue.add(Triple(c.docId, path + c.name, null))
                } else {
                    if (files.size >= maxFiles) {
                        truncated = true
                        break
                    }
                    files.add(WalkFile(c, path))
                }
            }
        }
        return Result(files, folders, truncated, rootList)
    }

    /**
     * Notes that share a title (`21H.md` at the root and in `_jonoco/Fam/`): one is the note the
     * link follows. A type tag (person, place, context) wins, then the shallower path, then the
     * path's name. Returns, per lowercased title, the files in that order (first = primary).
     */
    fun <T> byTitle(items: List<T>, title: (T) -> String, kind: (T) -> String, depth: (T) -> Int, path: (T) -> String): Map<String, List<T>> =
        items.groupBy { title(it).trim().lowercase(Locale.ROOT) }.mapValues { (_, v) ->
            v.sortedWith(compareBy<T>({ if (kind(it) == LinkKind.OTHER) 1 else 0 }, { depth(it) }, { path(it).lowercase(Locale.ROOT) }))
        }

    /**
     * Moves: on a phone's shared storage a document id is its path, so a note moved between folders
     * comes back with a new id. A new file with the same file name as exactly one vanished one (and
     * no other new file of that name) is that note, moved. Returns new id to old id.
     */
    fun moves(goneNames: Map<String, String>, newNames: Map<String, String>): Map<String, String> {
        val goneBy = goneNames.entries.groupBy { it.value.lowercase(Locale.ROOT) }
        val newBy = newNames.entries.groupBy { it.value.lowercase(Locale.ROOT) }
        val out = HashMap<String, String>()
        newBy.forEach { (name, news) ->
            val olds = goneBy[name] ?: return@forEach
            if (news.size == 1 && olds.size == 1) out[news[0].key] = olds[0].key
        }
        return out
    }
}

/**
 * People and places the mentions pass made with no note ("Mom", before the vault was read
 * recursively) that now turn out to have one. Pure.
 */
object LinkMerge {
    data class Cand(
        val id: Long,
        val title: String,
        val kind: String,
        /** Entries using it. */
        val uses: Int,
        /** Made by the mentions pass (link_source 'mentions'). */
        val fromMentions: Boolean,
        /** A vault note is bound to it (vault_note.link_id or link_note). */
        val hasNote: Boolean,
        /** Its notes' aliases. */
        val aliases: List<String> = emptyList(),
        val archived: Boolean = false,
    )

    /** What to do with a mention-made link. */
    sealed class Step {
        /** It has a note now (the scan matched it by title): it's an ordinary vault link. */
        data class Relabel(val id: Long) : Step()

        /** Two links for one person: keep [keep], move [drop]'s entries to it and remove [drop]. */
        data class Merge(val keep: Long, val drop: Long) : Step()
    }

    private fun norm(s: String) = s.trim().lowercase(Locale.ROOT)

    /**
     * For each mention-made link: if a note is bound to it, relabel it. Else if another link of a
     * note has its name as title or alias (the same kind, or other), merge them: the link with more
     * entries is kept (it keeps its id, so nothing else changes), ties keep the note's link.
     */
    fun plan(links: List<Cand>): List<Step> {
        val out = ArrayList<Step>()
        val dropped = HashSet<Long>()
        links.filter { it.fromMentions && !it.archived }.forEach { m ->
            if (m.hasNote) {
                out.add(Step.Relabel(m.id))
                return@forEach
            }
            val n = norm(m.title)
            val note = links.filter { it.id != m.id && it.hasNote && !it.archived && it.id !in dropped &&
                (it.kind == m.kind || it.kind == LinkKind.OTHER) && (norm(it.title) == n || it.aliases.any { a -> norm(a) == n }) }
                .minWithOrNull(compareBy<Cand>({ if (norm(it.title) == n) 0 else 1 }, { it.kind != m.kind }, { it.id })) ?: return@forEach
            if (m.uses > note.uses) {
                out.add(Step.Merge(m.id, note.id))
                dropped.add(note.id)
            } else {
                out.add(Step.Merge(note.id, m.id))
                dropped.add(m.id)
            }
        }
        return out
    }
}
