package com.jono.nowwidget.core

/**
 * Several activities at once ("Eat + Family"): an entry has a primary activity and extras. They
 * overlap; they're tags, not a split, so each gets the entry's whole time, while the clock time
 * counts once. No Android in here.
 */
object Together {
    /** Every activity of [e], the primary first. */
    fun acts(e: Entry): List<Long> = listOf(e.activityId) + e.extras.filter { it != e.activityId }.sorted()

    /** Per activity: the full time of every span it's on (primary or extra). */
    fun activityTotals(spans: List<Span>): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        spans.forEach { s -> acts(s.entry).forEach { a -> out[a] = (out[a] ?: 0L) + s.ms } }
        return out
    }

    /** The clock time, once. */
    fun clockTotal(spans: List<Span>): Long = spans.sumOf { it.ms }

    /** Per group: each span counts once for every group among its activities (blank groups as [ungrouped]). */
    fun groupTotals(spans: List<Span>, groupOf: (Long) -> String?, ungrouped: String = "Ungrouped"): Map<String, Long> {
        val out = HashMap<String, Long>()
        spans.forEach { s ->
            acts(s.entry).map { groupOf(it)?.ifBlank { null } ?: ungrouped }.toSet().forEach { g -> out[g] = (out[g] ?: 0L) + s.ms }
        }
        return out
    }

    /** Any span with extras: the stats say "Overlapping activities each count fully." */
    fun overlaps(spans: List<Span>): Boolean = spans.any { s -> s.entry.extras.any { it != s.entry.activityId } }

    /** "🍽️ Eat + 🏡 Family" (with emoji) or "Eat + Family"; [primary] is the entry's own name (a free-written title too). */
    fun label(primary: String, primaryEmoji: String, extras: List<Act>, emoji: Boolean): String {
        val head = if (emoji && primaryEmoji.isNotBlank()) primaryEmoji.trim() + " " + primary else primary
        if (extras.isEmpty()) return head
        return head + extras.joinToString("") { a -> " + " + if (emoji && a.emoji.isNotBlank()) a.emoji.trim() + " " + a.name else a.name }
    }

    /** The widget head: "Eat + Family" when it fits in [maxChars], else "Eat +1"; just the name with no room. */
    fun short(primary: String, extras: List<Act>, maxChars: Int): String {
        if (extras.isEmpty()) return primary
        val full = label(primary, "", extras, emoji = false)
        if (full.length <= maxChars) return full
        return primary + " +" + extras.size
    }

    /** Tapping [act] in Add mode: on or off as an extra; the primary can't be removed this way. */
    fun toggle(e: Entry, act: Long): Set<Long> = when {
        act == e.activityId -> e.extras
        act in e.extras -> e.extras - act
        else -> e.extras + act
    }
}
