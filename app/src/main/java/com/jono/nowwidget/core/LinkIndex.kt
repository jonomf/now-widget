package com.jono.nowwidget.core

import java.util.Locale

/** A link as the picker sees it: its title, the aliases its vault note has, and how much it's used. */
data class LinkEntry(
    val id: Long,
    val title: String,
    val kind: String,
    val aliases: List<String>,
    val uses: Int,
    val archived: Boolean,
    val pinned: Boolean = false,
)

/** [alias] is the alias that matched, when the title itself didn't. Choosing it means [link]. */
data class Suggestion(val link: LinkEntry, val alias: String?)

/**
 * Type-ahead over links: titles and aliases, best ranked first ([rank]: Now use and the vault's
 * mentions, see [Mentions.combined]; without one, most used first). Pure.
 */
class LinkIndex(private val entries: List<LinkEntry>, private val rank: Map<Long, Double> = emptyMap()) {
    private fun score(e: LinkEntry): Double = rank[e.id] ?: 0.0
    private fun norm(s: String) = s.trim().lowercase(Locale.ROOT)

    private fun words(s: String): List<String> = s.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** The link whose title or alias is exactly [text] (any case), among [kinds]; titles win over aliases. */
    fun resolve(text: String, kinds: Set<String>): LinkEntry? {
        val q = norm(text)
        if (q.isEmpty()) return null
        val pool = entries.filter { it.kind in kinds }
        val order = compareBy<LinkEntry>({ it.archived }, { -it.uses }, { it.id })
        return pool.filter { norm(it.title) == q }.minWithOrNull(order)
            ?: pool.filter { e -> e.aliases.any { norm(it) == q } }.minWithOrNull(order)
    }

    /** Best matches first. With nothing typed: the ones you use most. */
    fun suggest(query: String, kinds: Set<String>, limit: Int = 8): List<Suggestion> {
        val q = norm(query)
        val pool = entries.filter { it.kind in kinds }
        if (q.isEmpty()) {
            return pool.filter { it.uses > 0 || it.pinned || score(it) > 0.0 }
                .sortedWith(compareBy<LinkEntry>({ it.archived }, { -score(it) }, { -it.uses }, { it.title.lowercase(Locale.ROOT) }))
                .take(limit).map { Suggestion(it, null) }
        }
        class Hit(val e: LinkEntry, val tier: Int, val alias: String?)
        val hits = ArrayList<Hit>()
        pool.forEach { e ->
            val t = norm(e.title)
            var tier = when {
                t == q -> 0
                t.startsWith(q) -> 2
                words(t).any { it.startsWith(q) } -> 4
                t.contains(q) -> 5
                else -> 99
            }
            var alias: String? = null
            e.aliases.forEach { a ->
                val n = norm(a)
                val at = when {
                    n == q -> 1
                    n.startsWith(q) -> 3
                    n.contains(q) -> 6
                    else -> 99
                }
                if (at < tier) {
                    tier = at
                    alias = a
                }
            }
            if (tier < 99) hits.add(Hit(e, tier, alias))
        }
        return hits.sortedWith(
            compareBy<Hit>({ it.tier }, { it.e.archived }, { -score(it.e) }, { -it.e.uses }, { it.e.title.length }, { it.e.title.lowercase(Locale.ROOT) })
        ).take(limit).map { Suggestion(it.e, it.alias) }
    }

    companion object {
        /**
         * The existing link a vault note with [title] and [kind] should be matched with, if any: same
         * title in any case, preferring the same kind, then one that isn't removed, then the oldest.
         */
        fun matchForNote(candidates: List<LinkEntry>, title: String, kind: String): LinkEntry? {
            val t = title.trim().lowercase(Locale.ROOT)
            return candidates.filter { it.title.trim().lowercase(Locale.ROOT) == t }
                .minWithOrNull(compareBy<LinkEntry>({ it.kind != kind }, { it.archived }, { it.id }))
        }
    }
}
