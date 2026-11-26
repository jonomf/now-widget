package com.jono.nowwidget.core

import java.util.Locale
import kotlin.math.pow

/**
 * How often and how recently his dated daily notes mention each person or place: the ranking
 * behind the WHO / WHERE chips and every people and places list. Obsidian keeps no record of page
 * opens, so mentions stand in for "commonly accessed". No Android in here.
 *
 * His log lines read `At [[Place]] w [[A]] and [[B]]` (also `@`, `with`, `w/`, `&`, commas), so
 * besides plain links a note says which targets came after "At" (places) and which were in the
 * "w" clause (people). Many of the most important ones (Mom, The Loft) have no note of their own.
 */
object Mentions {
    const val HALF_LIFE_DAYS = 180.0
    private const val DAY_MS = 86_400_000L

    /** An unresolved target becomes a person or place link after this many notes mention it. */
    const val PROMOTE_AT = 3

    /** "Now" use weighs this much more than a vault mention. */
    const val NOW_WEIGHT = 3.0

    /** A recently opened note's bonus. */
    const val OPEN_BONUS = 1.0

    private val DATED = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d)")
    private val LINK = Regex("\\[\\[([^\\[\\]\\n]+?)]]")
    /** A file embed or attachment ("img.png"), not a page. */
    private val FILE = Regex("\\.(?i:png|jpe?g|gif|webp|heic|pdf|mp3|m4a|mp4|mov|webm|wav|ogg|svg|canvas|excalidraw|base)$")

    /** `2025-4-9.md`, `2025-4-9 (long).md`, `2025-4-9 -- Title.md`: (year, month, day); null otherwise or for the app's own `(now)` notes. */
    fun datedNote(fileName: String): Triple<Int, Int, Int>? {
        val title = VaultNotes.titleOf(fileName) ?: return null
        if (title.trimEnd().endsWith("(now)") || Regex("\\(now\\)\\s*\\(\\d+\\)$").containsMatchIn(title)) return null
        val m = DATED.find(title.trim()) ?: return null
        val (y, mo, d) = m.destructured
        val month = mo.toInt()
        val day = d.toInt()
        if (month !in 1..12 || day !in 1..31) return null
        return Triple(y.toInt(), month, day)
    }

    /** A link's target: before `|alias`, `#heading` or `^block`, the last part of a path, trimmed. Null for files and blanks. */
    fun target(inner: String): String? {
        var t = inner.substringBefore('|').substringBefore('#').substringBefore('^')
        t = t.substringAfterLast('/').trim()
        if (t.endsWith(".md", ignoreCase = true)) t = t.dropLast(3).trim()
        if (t.isEmpty() || FILE.containsMatchIn(t)) return null
        return t.replace(Regex("\\s+"), " ")
    }

    /** Case doesn't make a different target ("The Loft" and "The loft"). */
    fun key(target: String): String = target.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    /** Every link target in [line], in order (repeats kept). */
    fun links(line: String): List<String> = LINK.findAll(line).mapNotNull { target(it.groupValues[1]) }.toList()

    /** The targets in a header line: those right after "At"/"@" and those in the "w"/"with"/"w/" clause. */
    data class Header(val at: List<String>, val with: List<String>)

    private val AT = Regex("(?i)(?:^|[\\s(,;:*_-])(?:at|@)\\s*(?=\\[\\[)|@(?=\\[\\[)")
    private val WITH = Regex("(?i)(?:^|[\\s(,;:*_-])(?:with|w/|w)\\s*(?=\\[\\[)")
    private val SEP = Regex("^\\s*(?:,|&|\\+|and|,\\s*and)\\s*(?=\\[\\[)", RegexOption.IGNORE_CASE)

    /** The links chained from [from]: `[[A]], [[B]] and [[C]]`. */
    private fun chain(line: String, from: Int): List<String> {
        val out = ArrayList<String>()
        var i = from
        while (true) {
            val m = LINK.find(line, i) ?: break
            if (m.range.first != i) break
            target(m.groupValues[1])?.let { out.add(it) }
            i = m.range.last + 1
            val sep = SEP.find(line.substring(i)) ?: break
            i += sep.range.last + 1
        }
        return out
    }

    fun header(line: String): Header {
        val at = AT.findAll(line).flatMap { chain(line, it.range.last + 1) }.toList()
        val with = WITH.findAll(line).flatMap { chain(line, it.range.last + 1) }.toList()
        return Header(at, with)
    }

    /** What one note says about one target: how it was spelled there, and whether it came after "At" / in a "w" clause. */
    data class NoteMention(val spelled: String, val at: Boolean, val with: Boolean)

    /** One note's mentions, by [key]. A target counts once per note, whatever the number of links. */
    fun parseNote(text: String): Map<String, NoteMention> {
        val out = LinkedHashMap<String, NoteMention>()
        text.lineSequence().forEach { line ->
            if (!line.contains("[[")) return@forEach
            val h = header(line)
            val atKeys = h.at.map { key(it) }.toSet()
            val withKeys = h.with.map { key(it) }.toSet()
            links(line).forEach { t ->
                val k = key(t)
                val old = out[k]
                out[k] = NoteMention(old?.spelled ?: t, (old?.at ?: false) || k in atKeys, (old?.with ?: false) || k in withKeys)
            }
        }
        return out
    }

    /** A stored mention: which note (its diary day), the target, and the header evidence. */
    data class Row(val docId: String, val key: String, val spelled: String, val day: Long, val at: Boolean, val with: Boolean)

    /** Everything known about one target across his notes. */
    data class Target(
        val key: String,
        /** The spelling used most (ties: the most recent). */
        val title: String,
        val score: Double,
        val notes: Int,
        val atNotes: Int,
        val withNotes: Int,
    )

    /** 0.5^(age / 180 days), age never below 0. */
    fun weight(day: Long, now: Long, halfLifeDays: Double = HALF_LIFE_DAYS): Double =
        0.5.pow(maxOf(0L, now - day).toDouble() / DAY_MS / halfLifeDays)

    /** Each note that mentions a target adds [weight], twice that when the mention is in a header ("At" / "w"). */
    fun targets(rows: List<Row>, now: Long): Map<String, Target> =
        rows.groupBy { it.key }.mapValues { (k, rs) ->
            val spell = rs.groupBy { it.spelled }.entries
                .sortedWith(compareByDescending<Map.Entry<String, List<Row>>> { it.value.size }.thenByDescending { e -> e.value.maxOf { it.day } })
                .first().key
            Target(
                key = k,
                title = spell,
                score = rs.sumOf { r -> weight(r.day, now) * (if (r.at || r.with) 2.0 else 1.0) },
                notes = rs.map { it.docId }.distinct().size,
                atNotes = rs.count { it.at },
                withNotes = rs.count { it.with },
            )
        }

    /**
     * The kind of a target: its note's tag when it has a note tagged person or place ([noteKind]);
     * otherwise the header evidence: mostly after "At" is a place, mostly in a "w" clause a person.
     * Anything else stays [LinkKind.OTHER] (not offered on who / where).
     */
    fun kind(t: Target, noteKind: String?): String {
        if (noteKind == LinkKind.PERSON || noteKind == LinkKind.PLACE || noteKind == LinkKind.CONTEXT) return noteKind
        return when {
            t.atNotes > t.withNotes -> LinkKind.PLACE
            t.withNotes > t.atNotes -> LinkKind.PERSON
            else -> LinkKind.OTHER
        }
    }

    /** An unresolved target that clearly is a person or place, mentioned often enough, becomes a link of that kind. */
    fun promote(t: Target): String? {
        val k = kind(t, null)
        return if (t.notes >= PROMOTE_AT && (k == LinkKind.PERSON || k == LinkKind.PLACE)) k else null
    }

    /** The targets in `.obsidian/workspace(-mobile).json`'s "lastOpenFiles", by [key]. Empty when it can't be read. */
    fun lastOpen(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        val root = try {
            Json.parse(json) as? Map<*, *>
        } catch (e: Exception) {
            null
        } ?: return emptySet()
        val list = root["lastOpenFiles"] as? List<*> ?: return emptySet()
        return list.mapNotNull { (it as? String)?.takeIf { p -> p.endsWith(".md", ignoreCase = true) } }
            .mapNotNull { target(it) }.map { key(it) }.toSet()
    }

    /** The one ranking used for every people and places list: Now use ×3, plus the vault's mentions. */
    fun combined(nowScore: Double, vaultScore: Double): Double = NOW_WEIGHT * nowScore + vaultScore

    fun combined(now: Map<Long, Double>, vault: Map<Long, Double>): Map<Long, Double> =
        (now.keys + vault.keys).associateWith { combined(now[it] ?: 0.0, vault[it] ?: 0.0) }

    /** Best first by [rank], then by name. */
    fun <T> ranked(items: List<T>, rank: Map<Long, Double>, id: (T) -> Long, name: (T) -> String): List<T> =
        items.sortedWith(compareByDescending<T> { rank[id(it)] ?: 0.0 }.thenBy { name(it).lowercase(Locale.ROOT) }.thenBy { id(it) })
}
