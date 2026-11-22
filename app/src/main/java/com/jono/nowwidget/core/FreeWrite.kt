package com.jono.nowwidget.core

/**
 * Free-written activities: "fixing the bike" instead of a button. The text is the entry's title;
 * the entry still has an activity (its category), chosen here. No Android in here.
 */
object FreeWrite {
    /** The built-in category for text that matches nothing. */
    const val OTHER_NAME = "Other"
    const val OTHER_EMOJI = "✏️"

    /** How many distinct recent titles are offered. */
    const val RECENT = 20

    /** Used this often, a title can become an activity of its own. */
    const val MAKE_ACTIVITY_AT = 5

    /** Trimmed, inner runs of spaces and line breaks made one space; null when nothing is left. */
    fun clean(text: String?): String? = text?.replace(Regex("\\s+"), " ")?.trim()?.ifEmpty { null }

    private fun words(s: String): String =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString(" ")

    fun same(a: String?, b: String?): Boolean {
        val x = clean(a)
        val y = clean(b)
        return x != null && y != null && x.equals(y, ignoreCase = true)
    }

    /** Is [text] just one of the activities (any case)? Then it's a normal switch, with no title. */
    fun exactActivity(text: String, acts: List<Act>): Act? {
        val t = clean(text) ?: return null
        return acts.filter { !it.archived }.firstOrNull { it.name.trim().equals(t, ignoreCase = true) }
    }

    /**
     * The category for a free-written [text]. In order:
     * 1. an activity whose name is the text;
     * 2. the activity he last used with the same title ([lastForTitle]), so recategorizing one in
     *    the timeline sticks;
     * 3. the first of his keyword rules (the calendar's, in his order) whose keyword the text has;
     * 4. an activity whose name starts a word of the text ("watching a film" → Watch), longest name
     *    first, names of 3+ letters only;
     * 5. [otherId], the built-in "✏️ Other".
     * Archived activities never count.
     */
    fun category(text: String, acts: List<Act>, lastForTitle: Long?, rules: List<KeywordRule>, otherId: Long): Long {
        val live = acts.filter { !it.archived }
        val ids = live.map { it.id }.toSet()
        exactActivity(text, live)?.let { return it.id }
        if (lastForTitle != null && lastForTitle in ids) return lastForTitle
        val t = clean(text) ?: return otherId
        rules.forEach { r ->
            val k = r.keyword.trim()
            if (k.isNotEmpty() && t.contains(k, ignoreCase = true) && r.activityId in ids) return r.activityId
        }
        // Words joined by single spaces, with one in front, so " name" finds a name at a word's start.
        val text = " " + words(t)
        live.filter { it.id != otherId && it.name.trim().length >= 3 }
            .sortedWith(compareByDescending<Act> { it.name.trim().length }.thenBy { it.pos })
            .forEach { a ->
                val n = words(a.name)
                if (n.isNotEmpty() && text.contains(" $n")) return a.id
            }
        return otherId
    }

    /** One past use of a title: its text as written, when, and its category then. */
    data class Used(val title: String, val at: Long, val activityId: Long = 0)

    /** The category he last gave this title (newest use first in [used] or not), or null. */
    fun lastActivityFor(title: String, used: List<Used>): Long? =
        used.filter { same(it.title, title) }.maxByOrNull { it.at }?.activityId

    /** The most recent [max] distinct titles (case doesn't make a new one), newest first, as last written. */
    fun recent(used: List<Used>, max: Int = RECENT): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        used.sortedByDescending { it.at }.forEach { u ->
            val t = clean(u.title) ?: return@forEach
            if (seen.add(t.lowercase())) out.add(t)
        }
        return out.take(max)
    }

    /** How many times each title (lowercased) was used. */
    fun counts(used: List<Used>): Map<String, Int> {
        val out = HashMap<String, Int>()
        used.forEach { u -> clean(u.title)?.lowercase()?.let { out[it] = (out[it] ?: 0) + 1 } }
        return out
    }

    /** What the quick box suggests for what's typed so far. */
    sealed class Suggestion {
        data class Activity(val act: Act) : Suggestion()
        data class Title(val title: String) : Suggestion()
    }

    /**
     * Activities whose name matches, then recent titles that match ([recent], newest first): a
     * match at the start comes before one inside. Nothing typed: the recent titles. A title that
     * is an activity's name isn't offered twice.
     */
    fun suggest(query: String, acts: List<Act>, recent: List<String>, max: Int = 8): List<Suggestion> {
        val q = clean(query)?.lowercase() ?: ""
        val live = acts.filter { !it.archived }
        val names = live.map { it.name.trim().lowercase() }.toSet()
        fun rank(s: String): Int? {
            val l = s.lowercase()
            return when {
                q.isEmpty() -> 1
                l.startsWith(q) -> 0
                l.split(' ').any { it.startsWith(q) } -> 1
                l.contains(q) -> 2
                else -> null
            }
        }
        val a = if (q.isEmpty()) emptyList() else live.mapNotNull { act -> rank(act.name)?.let { act to it } }
            .sortedWith(compareBy<Pair<Act, Int>> { it.second }.thenBy { it.first.pos })
            .map { Suggestion.Activity(it.first) }
        val t = recent.filter { it.lowercase() !in names }
            .mapIndexedNotNull { i, s -> rank(s)?.let { Triple(s, it, i) } }
            .sortedWith(compareBy<Triple<String, Int, Int>> { it.second }.thenBy { it.third })
            .map { Suggestion.Title(it.first) }
        return (a + t).take(max)
    }

    /** The name an entry goes by: its title if it has one, else its activity's name. */
    fun name(title: String?, act: Act?): String = clean(title) ?: act?.name ?: "Removed"
}
