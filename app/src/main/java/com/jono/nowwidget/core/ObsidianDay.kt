package com.jono.nowwidget.core

import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone

/** One stretch of one activity on a diary day, with the names of what it was linked to. */
data class DaySpan(
    val entryId: Long,
    val activityId: Long,
    val activity: String,
    val emoji: String,
    /** The activity's group; blank when it has none. */
    val group: String,
    val from: Long,
    val to: Long,
    val people: List<String> = emptyList(),
    val places: List<String> = emptyList(),
    val context: String? = null,
    /** Still going: it has no end time yet. */
    val running: Boolean = false,
    /** Free-written words ("fixing the bike"); [activity] is then its category. */
    val title: String? = null,
    /** Extra activities at the same time ("Eat + Family"), in a stable order. */
    val also: List<DayAct> = emptyList(),
) {
    /** What it was: the free-written title, else the activity's name. */
    val label: String get() = FreeWrite.clean(title) ?: activity

    /** "🍽️ Eat + 🏡 Family": the primary (with its emoji) and every extra. */
    val fullLabel: String get() {
        val head = (if (emoji.isNotBlank()) emoji.trim() + " " else "") + label.trim()
        return head + also.joinToString("") { a -> " + " + (if (a.emoji.isNotBlank()) a.emoji.trim() + " " else "") + a.name.trim() }
    }
}

/** An extra activity of a span: its name, emoji and group. */
data class DayAct(val id: Long, val name: String, val emoji: String = "", val group: String = "")

/** A note or feeling, attached to an entry or on its own. */
data class DayMoment(
    val ts: Long,
    val kind: String,
    val entryId: Long? = null,
    /** The mood's name and level (1 is best); blank and null when it has none. */
    val mood: String = "",
    val moodLevel: Int? = null,
    /** The mood's own face; blank means the face for its level. */
    val moodEmoji: String = "",
    val tags: List<String> = emptyList(),
    val title: String = "",
    val text: String = "",
    /** Came in from a Daylio import, not from the phone's own log. */
    val imported: Boolean = false,
)

/** Everything one diary day's note is made from. [month] is 1–12. */
data class DayData(
    val year: Int,
    val month: Int,
    val day: Int,
    val spans: List<DaySpan>,
    val moments: List<DayMoment>,
    val tz: TimeZone = TimeZone.getDefault(),
    /** Link title to the title of the vault note it is bound to by alias, where that differs. */
    val notes: Map<String, String> = emptyMap(),
)

/**
 * Writes a diary day as the markdown of a `YYYY-M-D (now).md` note, in the style of his own logs.
 * Pure and deterministic: the same data always gives the same text, so an unchanged day is never
 * rewritten.
 */
object ObsidianDay {
    const val FIRST_LINE = "#log-now"

    /** Day key as export_day and export_state store it, and as the file name starts: unpadded. */
    fun key(year: Int, month: Int, day: Int): String = "$year-$month-$day"

    fun fileName(year: Int, month: Int, day: Int): String = key(year, month, day) + " (now).md"

    /** Anything to say about this day at all. */
    fun hasContent(d: DayData): Boolean = d.spans.isNotEmpty() || d.moments.any { momentText(it) != null }

    /** Nothing but Daylio-imported check-ins: the day is only written when that's asked for. */
    fun onlyImported(d: DayData): Boolean = d.spans.isEmpty() && d.moments.isNotEmpty() && d.moments.all { it.imported }

    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> String.format("%02x", b.toInt() and 0xFF) }

    /** His short style: 9a, 10:40a, 12p, 3:05p. Past midnight (before 4am) reads the same. */
    fun time(ms: Long, tz: TimeZone): String {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = ms
        val h24 = c.get(Calendar.HOUR_OF_DAY)
        val min = c.get(Calendar.MINUTE)
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        val ap = if (h24 < 12) "a" else "p"
        return if (min == 0) "$h12$ap" else "$h12:" + min.toString().padStart(2, '0') + ap
    }

    /**
     * `[[Title]]`, unless the title has something that would break a link (a pipe, a hash,
     * brackets) or a line break: then plain text, so no link is ever half right. A link that is
     * bound to a note under another title (an alias) is `[[Note Title|Link title]]`; if either part
     * would break the link, the link title is written as plain text.
     */
    fun link(name: String, note: String? = null): String {
        val n = clean(name)
        val target = if (note == null) n else clean(note)
        if (broken(n) || broken(target)) return n
        return if (target.equals(n, ignoreCase = false)) "[[$n]]" else "[[$target|$n]]"
    }

    private fun clean(s: String): String = s.replace("\r", " ").replace("\n", " ").trim()

    private fun broken(n: String): Boolean = n.isEmpty() || n.contains('|') || n.contains('#') || n.contains("[[") ||
        n.contains("]]") || n.startsWith("[") || n.endsWith("]")

    /** "a", "a and b", "a, b and c". */
    fun names(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    /** A run of back-to-back spans of one activity with the same links, and the entries in it. */
    class Merged(var span: DaySpan, val entries: MutableSet<Long>)

    private fun sameThings(a: DaySpan, b: DaySpan): Boolean =
        a.activityId == b.activityId && sameTitle(a.title, b.title) && a.also.map { it.id }.toSet() == b.also.map { it.id }.toSet() &&
            a.people.toSet() == b.people.toSet() &&
            a.places.toSet() == b.places.toSet() && a.context == b.context

    /** Both plain, or the same free-written title (any case). */
    private fun sameTitle(a: String?, b: String?): Boolean {
        val x = FreeWrite.clean(a)
        val y = FreeWrite.clean(b)
        return if (x == null || y == null) x == y else x.equals(y, ignoreCase = true)
    }

    /** Back-to-back spans of the same activity, title and links become one. Also used by the calendar export. */
    fun merge(spans: List<DaySpan>): List<Merged> {
        val out = ArrayList<Merged>()
        spans.sortedBy { it.from }.forEach { s ->
            val last = out.lastOrNull()
            if (last != null && sameThings(last.span, s) && s.from <= last.span.to) {
                last.span = last.span.copy(to = maxOf(last.span.to, s.to), running = s.running)
                last.entries.add(s.entryId)
            } else {
                out.add(Merged(s, linkedSetOf(s.entryId)))
            }
        }
        return out
    }

    /** The name with the most time; the first one seen wins a tie. */
    private fun top(weights: List<Pair<String, Long>>): List<String> {
        val m = LinkedHashMap<String, Long>()
        weights.forEach { (n, ms) -> if (n.isNotBlank()) m[n] = (m[n] ?: 0L) + ms }
        return m.entries.sortedByDescending { it.value }.map { it.key }
    }

    private fun header(d: DayData): Pair<String, String?> {
        val place = top(d.spans.flatMap { s -> s.places.map { it to (s.to - s.from) } }).firstOrNull()
        val people = top(d.spans.flatMap { s -> s.people.map { it to (s.to - s.from) } })
        val context = top(d.spans.mapNotNull { s -> s.context?.let { it to (s.to - s.from) } }).firstOrNull()
        val parts = ArrayList<String>()
        if (place != null) parts.add("At " + link(place, d.notes[place]))
        if (people.isNotEmpty()) parts.add("w " + names(people.map { link(it, d.notes[it]) }))
        if (context != null) parts.add("in " + link(context, d.notes[context]))
        return parts.joinToString(" ") to context
    }

    fun feelingPart(m: DayMoment): String {
        val mood = if (m.mood.isBlank()) "" else {
            val face = m.moodEmoji.ifBlank { levelFace(m.moodLevel ?: 3) }
            "$face ${m.mood.trim()}"
        }
        val tags = m.tags.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        return listOf(mood, tags).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** The words after a moment's time, possibly over several lines; null when there's nothing. */
    fun momentText(m: DayMoment): String? {
        val title = m.title.replace("\r", " ").replace("\n", " ").trim()
        val head = listOf(if (title.isEmpty()) "" else "**$title**", feelingPart(m)).filter { it.isNotEmpty() }
            .joinToString(" ")
        val text = m.text.replace("\r\n", "\n").replace('\r', '\n').trim().lines().joinToString("\n") { it.trimEnd() }
        return when {
            head.isEmpty() && text.isEmpty() -> null
            text.isEmpty() -> head
            head.isEmpty() -> text
            else -> "$head — $text"
        }
    }

    /** One bullet. Continuation lines sit under the text so a multi-line note stays in its item. */
    private fun bullet(sb: MutableList<String>, indent: String, time: String, body: String) {
        val lines = body.split("\n")
        sb.add("$indent- $time ${lines[0]}")
        lines.drop(1).forEach { sb.add(if (it.isEmpty()) "" else "$indent  $it") }
    }

    private class Item(val time: Long, val order: Int, val lines: List<String>)

    fun render(d: DayData): String {
        val out = ArrayList<String>()
        out.add(FIRST_LINE)
        if (d.spans.isEmpty() && d.moments.none { momentText(it) != null }) return out.joinToString("\n") + "\n"

        val (head, headContext) = header(d)
        if (head.isNotEmpty()) out.add(head)
        out.add("")

        val merged = merge(d.spans)
        val byEntry = HashMap<Long, Int>()
        merged.forEachIndexed { i, m -> m.entries.forEach { byEntry[it] = i } }
        val attached = merged.map { ArrayList<DayMoment>() }
        val loose = ArrayList<DayMoment>()
        d.moments.sortedBy { it.ts }.forEach { m ->
            val i = m.entryId?.let { byEntry[it] }
            if (i != null) attached[i].add(m) else loose.add(m)
        }

        val items = ArrayList<Item>()
        merged.forEachIndexed { i, m ->
            val s = m.span
            val lines = ArrayList<String>()
            val sb = StringBuilder("- ")
            sb.append(time(s.from, d.tz)).append('–')
            if (!s.running) sb.append(time(s.to, d.tz))
            sb.append(' ')
            sb.append(s.fullLabel)
            if (s.people.isNotEmpty()) sb.append(" w ").append(names(s.people.map { link(it, d.notes[it]) }))
            if (s.places.isNotEmpty()) sb.append(" at ").append(names(s.places.map { link(it, d.notes[it]) }))
            if (s.context != null && s.context != headContext) sb.append(" in ").append(link(s.context, d.notes[s.context]))
            if (s.running) sb.append(" (running)")
            lines.add(sb.toString())
            attached[i].forEach { mo ->
                momentText(mo)?.let { bullet(lines, "\t", time(mo.ts, d.tz), it) }
            }
            items.add(Item(s.from, 0, lines))
        }
        loose.forEach { mo ->
            momentText(mo)?.let {
                val lines = ArrayList<String>()
                bullet(lines, "", time(mo.ts, d.tz), it)
                items.add(Item(mo.ts, 1, lines))
            }
        }
        items.sortedWith(compareBy<Item>({ it.time }, { it.order })).forEach { out.addAll(it.lines) }

        val totals = LinkedHashMap<String, Long>()
        // Each group once per span, even when two of its activities are on it.
        d.spans.forEach { s ->
            (listOf(s.group.ifBlank { s.activity }) + s.also.map { it.group.ifBlank { it.name } }).toSet().forEach { g ->
                totals[g] = (totals[g] ?: 0L) + (s.to - s.from)
            }
        }
        if (totals.isNotEmpty()) {
            out.add("")
            out.add("## totals")
            out.add("- " + totals.entries.sortedByDescending { it.value }
                .joinToString(" · ") { (g, ms) -> g + " " + formatDuration(ms) })
        }
        return out.joinToString("\n") + "\n"
    }
}
