package com.jono.nowwidget.core

/** What the app learns from one note in the vault's root: its title, what kind of thing it is, its aliases. */
data class VaultNote(val title: String, val kind: String, val aliases: List<String>)

/**
 * Reads the top of a vault note. The type of a note is a tag on its first non-blank line after the
 * optional frontmatter (`#person in [[Team]]`, `#place-com`, `#event`). Pure, so it's unit-tested.
 */
object VaultNotes {
    private val DATE = "\\d{4}-\\d{1,2}-\\d{1,2}"
    private val PLAIN_DAY = Regex("^$DATE$")
    private val DAY_SUFFIX = Regex("^$DATE\\s*\\([^()]*\\)$")
    private val DAY_TIME = Regex("^$DATE\\s+\\d{1,2}-\\d{2}$")
    private val OURS = Regex("^$DATE\\s*\\(now\\)(\\s*\\(\\d+\\))?$")
    private val TRIP = Regex("\\btrips?\\b", RegexOption.IGNORE_CASE)
    private val TAG = Regex("^#([A-Za-z0-9_][A-Za-z0-9_/-]*)(?=\\s|$)")
    private val KEY = Regex("^(?i:alias(?:es)?)\\s*:\\s*(.*)$")
    private val ITEM = Regex("^\\s*-\\s*(.*)$")

    /** How many lines of a note's top are looked at. */
    const val HEAD_LINES = 40

    /** The note's title (its file name without .md), or null for a file that isn't a note. */
    fun titleOf(fileName: String): String? =
        if (fileName.endsWith(".md", ignoreCase = true) && fileName.length > 3) fileName.dropLast(3) else null

    /** A daily note's companion: `2025-4-9 (long)`, `(now)`, `(VRChat)`, or a time-stamped `2025-4-9 14-30`. */
    fun isCompanion(title: String): Boolean {
        val t = title.trim()
        return DAY_SUFFIX.matches(t) || DAY_TIME.matches(t)
    }

    /**
     * True when a note can never be linked to, whatever its tag, so its file isn't even opened: plain
     * daily notes (`2025-4-9`) and the app's own `(now)` notes. Companions are read, since one that is
     * tagged as an event is a context; `2025-4-9 -- Title` notes are ordinary notes.
     */
    fun skip(title: String): Boolean = PLAIN_DAY.matches(title.trim()) || OURS.matches(title.trim()) || title.trimEnd().endsWith("(now)")

    /** True when a file name could be a linkable note; the file need not be read otherwise. */
    fun candidate(fileName: String): Boolean = titleOf(fileName)?.let { !skip(it) } ?: false

    /** Null when the file is skipped (not a note, a daily note, or one of ours). [head] is the note's first lines. */
    fun parse(fileName: String, head: List<String>): VaultNote? {
        val title = titleOf(fileName) ?: return null
        if (skip(title)) return null
        val lines = head.take(HEAD_LINES).mapIndexed { i, l ->
            (if (i == 0) l.removePrefix("﻿") else l).trimEnd('\r', '\n')
        }

        var front: List<String> = emptyList()
        var body: List<String> = lines
        if (lines.firstOrNull()?.trim() == "---") {
            val close = (1 until lines.size).firstOrNull { lines[it].trim() == "---" || lines[it].trim() == "..." }
            front = lines.subList(1, close ?: lines.size)
            body = if (close != null) lines.subList(close + 1, lines.size) else emptyList()
        }
        val tag = body.firstOrNull { it.isNotBlank() }?.trim()?.let { TAG.find(it)?.groupValues?.get(1)?.lowercase() }
        val aliases = aliases(front).filter { !it.equals(title, ignoreCase = true) }.distinctBy { it.lowercase() }
        // A daily note's companion counts only when its tag says it's an event.
        if (isCompanion(title) && !isEventTag(tag)) return null
        return VaultNote(title, kindOf(tag, title), aliases)
    }

    private fun isEventTag(tag: String?): Boolean = tag == "event" || (tag != null && tag.startsWith("event-"))

    /** Person and place tags decide; events and trips are contexts; the rest are just notes. */
    fun kindOf(tag: String?, title: String): String = when {
        tag == "person" || tag == "person-official" -> LinkKind.PERSON
        tag == "place" || tag == "place-com" -> LinkKind.PLACE
        isEventTag(tag) -> LinkKind.CONTEXT
        TRIP.containsMatchIn(title) -> LinkKind.CONTEXT
        else -> LinkKind.OTHER
    }

    /** `aliases:` as a block list, `[a, b]`, or one value; `alias:` too. */
    fun aliases(front: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < front.size) {
            val m = KEY.find(front[i].trimEnd())
            if (m != null) {
                val rest = m.groupValues[1].trim()
                when {
                    rest.startsWith("[") -> out.addAll(flowList(rest))
                    rest.isNotEmpty() && !rest.startsWith("#") -> out.add(unquote(rest))
                    else -> {
                        var j = i + 1
                        while (j < front.size) {
                            val item = ITEM.find(front[j])
                            if (item != null) out.add(unquote(item.groupValues[1]))
                            else if (front[j].isNotBlank()) break
                            j++
                        }
                    }
                }
            }
            i++
        }
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun flowList(s: String): List<String> {
        val inner = s.removePrefix("[").let { if (it.contains(']')) it.substring(0, it.lastIndexOf(']')) else it }
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var quote = '\u0000'
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            when {
                quote != '\u0000' -> {
                    cur.append(c)
                    if (c == '\\' && quote == '"' && i + 1 < inner.length) {
                        i++
                        cur.append(inner[i])
                    } else if (c == quote) quote = '\u0000'
                }
                c == '"' || c == '\'' -> {
                    quote = c
                    cur.append(c)
                }
                c == ',' -> {
                    parts.add(cur.toString())
                    cur.setLength(0)
                }
                else -> cur.append(c)
            }
            i++
        }
        parts.add(cur.toString())
        return parts.map { unquote(it) }
    }

    private fun unquote(raw: String): String {
        val s = raw.trim()
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        if (s.length >= 2 && s.startsWith("'") && s.endsWith("'")) {
            return s.substring(1, s.length - 1).replace("''", "'")
        }
        return s
    }

    /** Aliases as the cache stores them: one per line (an alias can't hold a line break). */
    fun packAliases(a: List<String>): String = a.joinToString("\n") { it.replace('\n', ' ') }

    fun unpackAliases(s: String): List<String> = if (s.isEmpty()) emptyList() else s.split('\n')
}
