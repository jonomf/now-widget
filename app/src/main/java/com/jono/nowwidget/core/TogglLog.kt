package com.jono.nowwidget.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The Toggl export's content rules, free of Android so they're unit-tested: which Toggl entries a
 * diary day should have, what one entry's request body looks like, and what the app remembers
 * about entries it sent. The network and the quota are in TogglNet.kt and TogglSync.kt.
 */

/** One Toggl entry a diary day should have. Times are whole seconds (UTC epoch). */
data class TogglWant(
    /** Stable across re-exports: the day and the first entry of the run (same as the calendar's key). */
    val key: String,
    val workspaceId: Long,
    /** The project's name (the activity). Blank: none. */
    val project: String,
    val description: String,
    /** Always ends with [TogglDay.TAG_NOW]. */
    val tags: List<String>,
    val startSec: Long,
    val stopSec: Long,
) {
    val durationSec: Long get() = stopSec - startSec

    /** Changes whenever anything that is sent changes. */
    val hash: String
        get() = ObsidianDay.hash(listOf(workspaceId, project, description, tags.joinToString("\u0001"), startSec, stopSec).joinToString("\u0000"))
}

/**
 * What the app remembers about an entry it sent. [entryId] 0 is a pending record: saved just before
 * a POST, so a POST whose answer never arrived can be recognised later by its start and duration
 * ([pendingStart], [pendingDur], and whether it was sent with tags, [pendingTags]); [hash] is then
 * the hash of what was being sent.
 */
data class TogglRecord(
    val key: String,
    val workspaceId: Long,
    val entryId: Long,
    val hash: String,
    val pendingStart: Long = 0L,
    val pendingDur: Long = 0L,
    val pendingTags: Boolean = true,
) {
    val pending: Boolean get() = entryId == 0L
}

/** How a record's hash column holds a pending record's details: `pending:<start>:<dur>:<t|n>:<hash>`. */
object TogglRecordCodec {
    private const val PENDING = "pending:"

    fun hashField(r: TogglRecord): String =
        if (!r.pending) r.hash else PENDING + r.pendingStart + ":" + r.pendingDur + ":" + (if (r.pendingTags) "t" else "n") + ":" + r.hash

    fun read(key: String, workspaceId: Long, entryId: Long, field: String): TogglRecord {
        if (entryId == 0L && field.startsWith(PENDING)) {
            val p = field.removePrefix(PENDING).split(':', limit = 4)
            val start = p.getOrNull(0)?.toLongOrNull()
            val dur = p.getOrNull(1)?.toLongOrNull()
            if (start != null && dur != null && p.size == 4) {
                return TogglRecord(key, workspaceId, 0L, p[3], start, dur, p[2] != "n")
            }
        }
        return TogglRecord(key, workspaceId, entryId, field)
    }
}

/** An entry as Toggl lists it (`GET /me/time_entries`). */
data class TogglSeen(val id: Long, val workspaceId: Long, val startSec: Long, val durationSec: Long, val tags: List<String>)

object TogglDay {
    /** Shorter finished runs aren't sent. */
    const val MIN_MS = 60_000L

    /** On every entry this app makes, so they can be told apart (and found again after a lost answer). */
    const val TAG_NOW = "now"
    const val CREATED_WITH = "Now widget"

    /** Toggl limits descriptions; stay well under. */
    const val MAX_DESCRIPTION = 2800
    const val MAX_TAG = 100

    /** Keeps at most [max] characters, ending in an ellipsis when something was cut, never splitting a surrogate pair. */
    fun truncate(s: String, max: Int): String {
        if (s.length <= max) return s
        var end = max - 1
        if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, maxOf(end, 0)).trimEnd() + "…"
    }

    private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    /** The words one moment adds to a description: its check-in part (when [checkIns]) then its note (when [notes]); null when nothing. */
    fun momentPart(m: DayMoment, notes: Boolean, checkIns: Boolean): String? {
        val feeling = if (checkIns) oneLine(ObsidianDay.feelingPart(m)) else ""
        val note = if (notes) {
            val title = oneLine(m.title)
            val text = oneLine(m.text)
            listOf(title, text).filter { it.isNotEmpty() }.joinToString(" — ")
        } else ""
        return listOf(feeling, note).filter { it.isNotEmpty() }.joinToString(" — ").ifEmpty { null }
    }

    /** `🎨 Hobby`, then ` — ` and the moments' words joined with ` / `, kept under [MAX_DESCRIPTION]. */
    fun description(s: DaySpan, moments: List<DayMoment>, notes: Boolean, checkIns: Boolean): String {
        val head = (s.emoji.trim() + " " + s.activity.trim()).trim()
        val parts = moments.sortedBy { it.ts }.mapNotNull { momentPart(it, notes, checkIns) }
        val text = if (parts.isEmpty()) head else head + " — " + parts.joinToString(" / ")
        return truncate(text, MAX_DESCRIPTION)
    }

    /** People, places and context as tag names (blanks and repeats dropped), then `now`. */
    fun tags(s: DaySpan): List<String> {
        val names = ArrayList<String>()
        (s.people + s.places + listOfNotNull(s.context)).forEach { n ->
            val t = truncate(oneLine(n), MAX_TAG)
            if (t.isNotEmpty() && names.none { it.equals(t, ignoreCase = true) } && !t.equals(TAG_NOW, ignoreCase = true)) names.add(t)
        }
        names.add(TAG_NOW)
        return names
    }

    /**
     * The entries a diary day should have: one per finished run of [MIN_MS] or more, where a run is
     * back-to-back spans of the same activity with the same links (the same merge as the Obsidian
     * note and the calendar). The running activity is left out until it finishes. Idle has no span.
     */
    fun entries(d: DayData, workspaceId: Long, notes: Boolean, checkIns: Boolean): List<TogglWant> {
        val dayKey = ObsidianDay.key(d.year, d.month, d.day)
        val runs = ObsidianDay.merge(d.spans)
        val allEntries = d.spans.map { it.entryId }.toSet()
        val out = ArrayList<TogglWant>()
        runs.forEach { run ->
            val s = run.span
            if (s.running || s.to - s.from < MIN_MS) return@forEach
            val startSec = Math.floorDiv(s.from, 1000L)
            val stopSec = Math.floorDiv(s.to, 1000L)
            if (stopSec <= startSec) return@forEach
            val first = d.spans.filter { it.entryId in run.entries }.minByOrNull { it.from }?.entryId ?: s.entryId
            val moments = d.moments.filter { m ->
                val e = m.entryId
                if (e != null && e in allEntries) e in run.entries else m.ts >= s.from && m.ts < s.to
            }
            out.add(TogglWant(CalendarDay.key(dayKey, first), workspaceId, s.activity.trim(), description(s, moments, notes, checkIns), tags(s), startSec, stopSec))
        }
        return out
    }
}

/** The request bodies, written by hand so they come out the same in tests as on the phone. */
object TogglJson {
    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        s.forEach { c ->
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** `2026-11-20T09:15:00.000Z` */
    fun iso(sec: Long): String = ISO.format(Instant.ofEpochSecond(sec))

    /** Seconds since the epoch from Toggl's `2026-11-20T09:15:00+00:00` or `...Z`, or null. */
    fun parseIso(s: String?): Long? = try {
        if (s == null) null else java.time.OffsetDateTime.parse(s).toEpochSecond()
    } catch (e: Exception) {
        null
    }

    /** The body of a create (POST) or update (PUT). [projectId] null leaves the project out; [tags] false leaves tags out. */
    fun body(w: TogglWant, projectId: Long?, tags: Boolean = true): String {
        val sb = StringBuilder("{")
        sb.append("\"created_with\":").append(quote(TogglDay.CREATED_WITH))
        sb.append(",\"description\":").append(quote(w.description))
        if (tags) sb.append(",\"tags\":[").append(w.tags.joinToString(",") { quote(it) }).append(']')
        if (projectId != null) sb.append(",\"project_id\":").append(projectId)
        sb.append(",\"workspace_id\":").append(w.workspaceId)
        sb.append(",\"start\":").append(quote(iso(w.startSec)))
        sb.append(",\"stop\":").append(quote(iso(w.stopSec)))
        sb.append(",\"duration\":").append(w.durationSec)
        return sb.append('}').toString()
    }

    /** `{"name":"Hobby","active":true}` */
    fun projectBody(name: String): String = "{\"name\":" + quote(name) + ",\"active\":true}"

    /** Reads a list of entries from `GET /me/time_entries`; entries without an id or times are skipped. */
    fun seen(text: String): List<TogglSeen> {
        val arr = Json.parse(text) as? List<*> ?: return emptyList()
        return arr.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val id = (m["id"] as? Number)?.toLong() ?: return@mapNotNull null
            val ws = (m["workspace_id"] as? Number)?.toLong() ?: return@mapNotNull null
            val start = parseIso(m["start"] as? String) ?: return@mapNotNull null
            val dur = (m["duration"] as? Number)?.toLong() ?: return@mapNotNull null
            val tags = (m["tags"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            TogglSeen(id, ws, start, dur, tags)
        }
    }
}
