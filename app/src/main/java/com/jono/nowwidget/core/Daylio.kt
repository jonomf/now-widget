package com.jono.nowwidget.core

import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Base64
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

/** A mood as Daylio has it. [level] is its group, 1 (best) to 5; null when a CSV doesn't say. */
data class DaylioMood(val id: Long, val name: String, val level: Int?)

/** Daylio calls these activities. */
data class DaylioTag(val id: Long, val name: String)

/** One check-in, at its wall-clock time. [month] is 1–12 here (Daylio's JSON counts from 0). */
data class DaylioEntry(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val moodId: Long,
    val tagIds: List<Long>,
    val note: String,
    val title: String,
)

data class DaylioData(
    val moods: List<DaylioMood>,
    val tags: List<DaylioTag>,
    val entries: List<DaylioEntry>,
    /** Rows or entries that had no usable date or mood. */
    val skipped: Int = 0,
) {
    fun mood(id: Long): DaylioMood? = moods.firstOrNull { it.id == id }

    /** How many entries use each tag. */
    fun uses(): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        entries.forEach { e -> e.tagIds.distinct().forEach { out[it] = (out[it] ?: 0) + 1 } }
        return out
    }
}

/** Where a Daylio tag goes on import. */
sealed class TagTarget {
    object Feeling : TagTarget()
    object Skip : TagTarget()

    /** An existing activity ([id] set) or a new one named [name]. */
    data class ToActivity(val id: Long?, val name: String) : TagTarget()

    /** An existing link ([id] set) or a new one of [kind] named [name]. */
    data class ToLink(val id: Long?, val kind: String, val name: String) : TagTarget()
}

/** Reads Daylio backups (.daylio, both formats) and Daylio CSV exports; writes Daylio CSV. */
object Daylio {
    /** Daylio's predefined_name_id and mood_group_id 1–5. */
    val DEFAULT_MOODS = listOf("rad", "good", "meh", "bad", "awful")

    const val CSV_HEADER = "full_date,date,weekday,time,mood,activities,note_title,note"
    private const val SEP = " | "

    /** Tags that look like activity names but are better kept as feelings. */
    private val KEEP_AS_FEELING = setOf("long", "other")

    /** Any of the formats, told apart by content: zip, CSV, JSON, or base64 JSON. */
    fun read(bytes: ByteArray): DaylioData {
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            return read(unzip(bytes))
        }
        val text = String(bytes, Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
        if (text.startsWith("{")) return parseBackupJson(text)
        val firstLine = text.substringBefore('\n').lowercase(Locale.ROOT)
        if (firstLine.contains("mood") && firstLine.contains(",")) return parseCsv(text)
        val decoded = try {
            Base64.getMimeDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Not a Daylio backup or CSV")
        }
        val inner = String(decoded, Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
        if (!inner.startsWith("{")) throw IllegalArgumentException("Not a Daylio backup or CSV")
        return parseBackupJson(inner)
    }

    /** The iOS format: a zip holding backup.daylio. */
    private fun unzip(bytes: ByteArray): ByteArray {
        var fallback: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.substringAfterLast('/')
                if (name == "backup.daylio") return z.readBytes()
                if (fallback == null && name.endsWith(".daylio")) fallback = z.readBytes()
            }
        }
        return fallback ?: throw IllegalArgumentException("No backup.daylio in the zip")
    }

    // --- Backup JSON ---

    @Suppress("UNCHECKED_CAST")
    fun parseBackupJson(text: String): DaylioData {
        val root = Json.parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("Not a Daylio backup")
        val moodMaps = (root["customMoods"] as? List<Any?>)?.mapNotNull { it as? Map<String, Any?> }
        val moods = if (moodMaps.isNullOrEmpty()) {
            DEFAULT_MOODS.mapIndexed { i, n -> DaylioMood((i + 1).toLong(), n, i + 1) }
        } else {
            moodMaps.mapNotNull { m ->
                val id = long(m["id"]) ?: return@mapNotNull null
                val predefined = long(m["predefined_name_id"])?.toInt()
                val custom = (m["custom_name"] as? String)?.trim().orEmpty()
                val preName = if (predefined != null && predefined in 1..5) DEFAULT_MOODS[predefined - 1] else null
                val name = custom.ifEmpty { preName ?: "mood $id" }
                val group = long(m["mood_group_id"])?.toInt()?.takeIf { it in 1..5 }
                    ?: predefined?.takeIf { it in 1..5 } ?: 3
                DaylioMood(id, name, group)
            }
        }
        val tags = (root["tags"] as? List<Any?>).orEmpty().mapNotNull { t ->
            val m = t as? Map<String, Any?> ?: return@mapNotNull null
            val id = long(m["id"]) ?: return@mapNotNull null
            DaylioTag(id, (m["name"] as? String)?.trim().orEmpty().ifEmpty { "tag $id" })
        }
        val moodIds = moods.map { it.id }.toSet()
        var skipped = 0
        val entries = ArrayList<DaylioEntry>()
        (root["dayEntries"] as? List<Any?>).orEmpty().forEach { x ->
            val m = x as? Map<String, Any?>
            val e = m?.let { entry(it, moodIds) }
            if (e == null) skipped++ else entries.add(e)
        }
        return DaylioData(moods, tags, entries, skipped)
    }

    private fun entry(m: Map<String, Any?>, moodIds: Set<Long>): DaylioEntry? {
        val mood = long(m["mood"])?.takeIf { it in moodIds } ?: return null
        val y0 = long(m["year"])?.toInt()
        val mo0 = long(m["month"])?.toInt()?.plus(1) // Daylio counts months from 0
        val d0 = long(m["day"])?.toInt()
        val wall: IntArray = if (y0 != null && mo0 != null && d0 != null) {
            intArrayOf(y0, mo0, d0, long(m["hour"])?.toInt() ?: 12, long(m["minute"])?.toInt() ?: 0)
        } else {
            // No wall-clock fields: fall back to the timestamp in the phone's time zone.
            val ms = long(m["datetime"]) ?: return null
            val c = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = ms }
            intArrayOf(
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
            )
        }
        if (wall[1] !in 1..12 || wall[2] !in 1..31) return null
        val tagIds = (m["tags"] as? List<*>).orEmpty().mapNotNull { long(it) }
        return DaylioEntry(
            wall[0], wall[1], wall[2], wall[3].coerceIn(0, 23), wall[4].coerceIn(0, 59),
            mood, tagIds,
            (m["note"] as? String).orEmpty(),
            (m["note_title"] as? String).orEmpty(),
        )
    }

    private fun long(v: Any?): Long? = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        is String -> v.trim().toLongOrNull()
        else -> null
    }

    // --- CSV ---

    /** Both header styles: `full_date,date,weekday,time,…` and the older `year,date,weekday,time,…`. */
    fun parseCsv(text: String): DaylioData {
        val rows = Csv.parse(text)
        if (rows.isEmpty()) return DaylioData(emptyList(), emptyList(), emptyList())
        val head = rows[0].map { it.trim().lowercase(Locale.ROOT) }
        fun col(name: String) = head.indexOf(name)
        val cFull = col("full_date")
        val cYear = col("year")
        val cDate = col("date")
        val cTime = col("time")
        val cMood = col("mood")
        val cActs = col("activities")
        val cTitle = col("note_title")
        val cNote = col("note")
        if (cMood < 0 || (cFull < 0 && (cYear < 0 || cDate < 0))) {
            throw IllegalArgumentException("Not a Daylio CSV")
        }
        val moods = LinkedHashMap<String, DaylioMood>()
        val tags = LinkedHashMap<String, DaylioTag>()
        val entries = ArrayList<DaylioEntry>()
        var skipped = 0
        rows.drop(1).forEach { r ->
            fun at(c: Int) = if (c >= 0 && c < r.size) r[c] else ""
            val date = if (cFull >= 0 && at(cFull).isNotBlank()) isoDate(at(cFull)) else oldDate(at(cYear), at(cDate))
            val time = clock(at(cTime))
            val moodName = at(cMood).trim()
            if (date == null || moodName.isEmpty()) {
                skipped++
                return@forEach
            }
            val mood = moods.getOrPut(moodName.lowercase(Locale.ROOT)) {
                val lvl = DEFAULT_MOODS.indexOf(moodName.lowercase(Locale.ROOT)).takeIf { it >= 0 }?.plus(1)
                DaylioMood(moods.size + 1L, moodName, lvl)
            }
            val tagIds = at(cActs).split(" | ", "|").map { it.trim() }.filter { it.isNotEmpty() }.map { n ->
                tags.getOrPut(n) { DaylioTag(tags.size + 1L, n) }.id
            }
            entries.add(
                DaylioEntry(
                    date.first, date.second, date.third, time?.first ?: 12, time?.second ?: 0,
                    mood.id, tagIds, at(cNote), at(cTitle),
                )
            )
        }
        return DaylioData(moods.values.toList(), tags.values.toList(), entries, skipped)
    }

    private fun isoDate(s: String): Triple<Int, Int, Int>? {
        val p = s.trim().split('-')
        if (p.size != 3) return null
        val y = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val d = p[2].take(2).toIntOrNull() ?: return null
        return if (m in 1..12 && d in 1..31) Triple(y, m, d) else null
    }

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** "January 4" or "4 January", with the year from its own column. */
    private fun oldDate(year: String, date: String): Triple<Int, Int, Int>? {
        val y = year.trim().toIntOrNull() ?: return null
        val words = date.trim().split(' ', ',', '.').filter { it.isNotEmpty() }
        var m: Int? = null
        var d: Int? = null
        words.forEach { w ->
            val n = w.toIntOrNull()
            if (n != null) {
                d = n
            } else {
                val i = MONTHS.indexOf(w.take(3).lowercase(Locale.ROOT))
                if (i >= 0) m = i + 1
            }
        }
        val mm = m ?: return null
        val dd = d ?: return null
        return if (dd in 1..31) Triple(y, mm, dd) else null
    }

    private val CLOCK = Regex("""(\d{1,2}):(\d{2})\s*([aApP])?""")

    /** "21:43", "9:43 pm" or "09:43 AM" as hour and minute. */
    private fun clock(s: String): Pair<Int, Int>? {
        val m = CLOCK.find(s) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        val ap = m.groupValues[3].lowercase(Locale.ROOT)
        if (ap == "p" && h < 12) h += 12
        if (ap == "a" && h == 12) h = 0
        return if (h in 0..23 && min in 0..59) h to min else null
    }

    /** One row of a Daylio CSV export. */
    data class CsvRow(
        val year: Int,
        val month: Int,
        val day: Int,
        val hour: Int,
        val minute: Int,
        val mood: String,
        val activities: List<String>,
        val title: String,
        val note: String,
    )

    /** Daylio's own CSV layout, newest first as Daylio writes it. */
    fun toCsv(rows: List<CsvRow>): String {
        val sb = StringBuilder(CSV_HEADER).append('\n')
        rows.sortedWith(compareByDescending<CsvRow> { it.year }.thenByDescending { it.month }
            .thenByDescending { it.day }.thenByDescending { it.hour }.thenByDescending { it.minute })
            .forEach { r ->
                val d = LocalDate.of(r.year, r.month, r.day)
                val fields = listOf(
                    String.format(Locale.ROOT, "%04d-%02d-%02d", r.year, r.month, r.day),
                    d.month.getDisplayName(TextStyle.FULL, Locale.US) + " " + r.day,
                    d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US),
                    String.format(Locale.ROOT, "%02d:%02d", r.hour, r.minute),
                    r.mood,
                    r.activities.joinToString(SEP),
                    r.title,
                    r.note,
                )
                sb.append(Csv.line(fields)).append('\n')
            }
        return sb.toString()
    }

    // --- Import helpers ---

    /** The dedupe key: wall-clock date and time plus the mood's name, so overlapping imports add nothing. */
    fun key(e: DaylioEntry, moodName: String): String = String.format(
        Locale.ROOT, "daylio:%04d-%02d-%02d %02d:%02d:%s",
        e.year, e.month, e.day, e.hour, e.minute, moodName.trim().lowercase(Locale.ROOT),
    )

    /**
     * The key for a check-in whose plain [key] is already taken by one with a different note
     * (two check-ins in the same minute with the same mood): the plain key plus a short hash of
     * its note. Depends only on the entry, so importing the file again finds it.
     */
    fun altKey(e: DaylioEntry, moodName: String): String {
        val crc = java.util.zip.CRC32()
        crc.update(e.note.toByteArray(Charsets.UTF_8))
        return key(e, moodName) + ":" + String.format(Locale.ROOT, "%08x", crc.value)
    }

    /** What to do with one incoming check-in, given what's already stored. */
    sealed class Decision {
        /** Already here (same key and note, or a feeling you logged in Now at that minute and mood). */
        object Skip : Decision()

        data class Insert(val key: String) : Decision()
    }

    /**
     * [noteAtKey] is the note of the stored moment with the plain key (null if none);
     * [altTaken] says whether the alternative key is stored; [nativeMatch] says whether a feeling
     * logged in Now (not imported) has the same minute and mood.
     */
    fun decide(
        e: DaylioEntry,
        moodName: String,
        noteAtKey: String?,
        altTaken: Boolean,
        nativeMatch: Boolean,
    ): Decision = when {
        nativeMatch -> Decision.Skip
        noteAtKey == null -> Decision.Insert(key(e, moodName))
        noteAtKey == e.note -> Decision.Skip
        altTaken -> Decision.Skip
        else -> Decision.Insert(altKey(e, moodName))
    }

    /**
     * First guess for a tag: an activity or link with exactly that name (any case), else a
     * feeling tag. "long" and "Other" stay feelings even though "Other" is also an activity.
     */
    fun guess(tag: String, acts: List<Act>, links: List<Link>): TagTarget {
        val n = tag.trim().lowercase(Locale.ROOT)
        if (n in KEEP_AS_FEELING) return TagTarget.Feeling
        val act = acts.filter { it.name.trim().lowercase(Locale.ROOT) == n }.minByOrNull { if (it.archived) 1 else 0 }
        if (act != null) return TagTarget.ToActivity(act.id, act.name)
        val link = links.filter { it.title.trim().lowercase(Locale.ROOT) == n }.minByOrNull { if (it.archived) 1 else 0 }
        if (link != null) return TagTarget.ToLink(link.id, link.kind, link.title)
        return TagTarget.Feeling
    }
}
