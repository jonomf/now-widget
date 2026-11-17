package com.jono.nowwidget

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jono.nowwidget.core.Act
import com.jono.nowwidget.core.Daylio
import com.jono.nowwidget.core.DaylioData
import com.jono.nowwidget.core.Entry
import com.jono.nowwidget.core.FeelingTag
import com.jono.nowwidget.core.IDLE
import com.jono.nowwidget.core.Link
import com.jono.nowwidget.core.LinkKind
import com.jono.nowwidget.core.LogRules
import com.jono.nowwidget.core.Moment
import com.jono.nowwidget.core.MomentKind
import com.jono.nowwidget.core.Mood
import com.jono.nowwidget.core.PALETTE
import com.jono.nowwidget.core.Schema
import com.jono.nowwidget.core.Span
import com.jono.nowwidget.core.TagTarget
import com.jono.nowwidget.core.levelFace
import java.util.Calendar
import java.util.Locale

private class Seed(val name: String, val emoji: String, val group: String)

private val SEEDS = listOf(
    Seed("Sleep", "😴", "Body"),
    Seed("Eat", "🍽️", "Body"),
    Seed("Move", "🏃", "Body"),
    Seed("Rest", "🛋️", "Body"),
    Seed("Work", "💼", "Work"),
    Seed("Family", "🏡", "People"),
    Seed("Friends", "🫂", "People"),
    Seed("Hobby", "🎨", "Play"),
    Seed("Read", "📚", "Play"),
    Seed("Games", "🎮", "Play"),
    Seed("Watch", "🎬", "Play"),
    Seed("Chores", "🧹", "Life"),
    Seed("Errands", "🛒", "Life"),
    Seed("Commute", "🚗", "Life"),
    Seed("Phone", "📱", "Life"),
    Seed("Other", "✳️", "Life"),
)

/**
 * The SQL lives in core/Schema.kt, where tools/test_migration.py checks it. SQLiteOpenHelper runs
 * onCreate and onUpgrade inside one transaction together with the version bump, so an upgrade
 * that fails part way leaves version 1 exactly as it was, and a finished one never runs again.
 */
private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "now.db", null, Schema.VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        Schema.create().forEach { db.execSQL(it) }
        SEEDS.forEachIndexed { i, s ->
            db.insert("activity", null, ContentValues().apply {
                put("name", s.name)
                put("emoji", s.emoji)
                put("color", PALETTE[i % PALETTE.size])
                put("pos", i)
                put("grp", s.group)
            })
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) Schema.upgradeFrom1().forEach { db.execSQL(it) }
    }
}

/** What an import did. */
data class ImportResult(val added: Int, val alreadyThere: Int, val skipped: Int)

object Store {
    private const val DOUBLE_TAP_MS = 700L
    private const val DAY_MS = 86_400_000L

    private var helper: Helper? = null
    private var lastTapAct = -1L
    private var lastTapAt = 0L

    @Synchronized
    private fun db(ctx: Context): SQLiteDatabase {
        val h = helper ?: Helper(ctx.applicationContext).also { helper = it }
        return h.writableDatabase
    }

    private fun args(vararg v: Any): Array<String> = v.map { it.toString() }.toTypedArray()

    private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)

    private fun <T> tx(ctx: Context, block: (SQLiteDatabase) -> T): T {
        val d = db(ctx)
        d.beginTransaction()
        try {
            val r = block(d)
            d.setTransactionSuccessful()
            return r
        } finally {
            d.endTransaction()
        }
    }

    // --- Activities ---

    /** Every activity, including removed ones, so old entries can still be named. */
    fun allActs(ctx: Context): List<Act> {
        val out = ArrayList<Act>()
        db(ctx).rawQuery(
            "SELECT id, name, emoji, color, pos, show_btn, grp, archived FROM activity ORDER BY pos, id",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Act(
                        c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4),
                        c.getInt(5) != 0, c.getString(6), c.getInt(7) != 0,
                    )
                )
            }
        }
        return out
    }

    fun saveAct(ctx: Context, id: Long?, name: String, emoji: String, color: Int, group: String, show: Boolean): Long {
        val v = ContentValues().apply {
            put("name", name)
            put("emoji", emoji)
            put("color", color)
            put("grp", group)
            put("show_btn", if (show) 1 else 0)
        }
        return if (id == null) {
            v.put("pos", nextPos(ctx, "activity"))
            db(ctx).insert("activity", null, v)
        } else {
            db(ctx).update("activity", v, "id=?", args(id))
            id
        }
    }

    private fun nextPos(ctx: Context, table: String): Int =
        db(ctx).rawQuery("SELECT COALESCE(MAX(pos), -1) + 1 FROM $table", null).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    /** Removing keeps the history; it just takes the item off the buttons and lists. */
    fun archive(ctx: Context, table: String, id: Long) {
        val v = ContentValues().apply { put("archived", 1) }
        db(ctx).update(table, v, "id=?", args(id))
    }

    fun setOrder(ctx: Context, table: String, ids: List<Long>) {
        tx(ctx) { d ->
            ids.forEachIndexed { i, id ->
                val v = ContentValues().apply { put("pos", i) }
                d.update(table, v, "id=?", args(id))
            }
        }
    }

    // --- Links: people, places, contexts ---

    /** Every link, including removed ones, in your order. */
    fun allLinks(ctx: Context): List<Link> {
        val out = ArrayList<Link>()
        db(ctx).rawQuery("SELECT id, title, kind, emoji, pos, archived FROM link ORDER BY pos, id", null).use { c ->
            while (c.moveToNext()) {
                out.add(Link(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getInt(5) != 0))
            }
        }
        return out
    }

    fun links(ctx: Context, kind: String): List<Link> = allLinks(ctx).filter { it.kind == kind && !it.archived }

    fun saveLink(ctx: Context, id: Long?, title: String, kind: String, emoji: String): Long {
        val v = ContentValues().apply {
            put("title", title)
            put("kind", kind)
            put("emoji", emoji)
        }
        return if (id == null) {
            v.put("pos", nextPos(ctx, "link"))
            db(ctx).insert("link", null, v)
        } else {
            db(ctx).update("link", v, "id=?", args(id))
            id
        }
    }

    /** The link of that kind and name (any case), brought back if it was removed, or a new one. */
    fun findOrAddLink(ctx: Context, kind: String, title: String): Long {
        val t = title.trim()
        val found = allLinks(ctx).filter { it.kind == kind && it.title.equals(t, ignoreCase = true) }
            .minByOrNull { if (it.archived) 1 else 0 }
        if (found != null) {
            if (found.archived) unarchive(ctx, "link", found.id)
            return found.id
        }
        return saveLink(ctx, null, t, kind, "")
    }

    private fun unarchive(ctx: Context, table: String, id: Long) {
        val v = ContentValues().apply { put("archived", 0) }
        db(ctx).update(table, v, "id=?", args(id))
    }

    // --- Moods and feeling tags ---

    fun allMoods(ctx: Context): List<Mood> {
        val out = ArrayList<Mood>()
        db(ctx).rawQuery("SELECT id, name, level, emoji, pos, archived FROM mood ORDER BY pos, id", null).use { c ->
            while (c.moveToNext()) {
                out.add(Mood(c.getLong(0), c.getString(1), c.getInt(2), c.getString(3), c.getInt(4), c.getInt(5) != 0))
            }
        }
        return out
    }

    /** A new mood goes after the others of its level, so the faces stay in order. */
    fun saveMood(ctx: Context, id: Long?, name: String, emoji: String, level: Int): Long {
        val v = ContentValues().apply {
            put("name", name)
            put("emoji", emoji)
            put("level", level.coerceIn(1, 5))
        }
        if (id != null) {
            db(ctx).update("mood", v, "id=?", args(id))
            return id
        }
        return tx(ctx) { d ->
            val pos = d.rawQuery(
                "SELECT COALESCE(MAX(pos), -1) + 1 FROM mood WHERE level <= ?", args(level),
            ).use { c ->
                c.moveToFirst()
                c.getInt(0)
            }
            d.execSQL("UPDATE mood SET pos = pos + 1 WHERE pos >= ?", arrayOf<Any>(pos))
            v.put("pos", pos)
            d.insert("mood", null, v)
        }
    }

    fun allTags(ctx: Context): List<FeelingTag> {
        val out = ArrayList<FeelingTag>()
        db(ctx).rawQuery("SELECT id, name, emoji, pos, archived FROM feeling_tag ORDER BY pos, id", null).use { c ->
            while (c.moveToNext()) {
                out.add(FeelingTag(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4) != 0))
            }
        }
        return out
    }

    /** How often each feeling tag has been used. */
    fun tagUses(ctx: Context): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        db(ctx).rawQuery("SELECT tag_id, COUNT(*) FROM moment_tag GROUP BY tag_id", null).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getInt(1)
        }
        return out
    }

    fun saveTag(ctx: Context, id: Long?, name: String, emoji: String): Long {
        val v = ContentValues().apply {
            put("name", name)
            put("emoji", emoji)
        }
        return if (id == null) {
            v.put("pos", nextPos(ctx, "feeling_tag"))
            db(ctx).insert("feeling_tag", null, v)
        } else {
            db(ctx).update("feeling_tag", v, "id=?", args(id))
            id
        }
    }

    fun findOrAddTag(ctx: Context, name: String): Long {
        val n = name.trim()
        val found = allTags(ctx).filter { it.name.equals(n, ignoreCase = true) }.minByOrNull { if (it.archived) 1 else 0 }
        if (found != null) {
            if (found.archived) unarchive(ctx, "feeling_tag", found.id)
            return found.id
        }
        return saveTag(ctx, null, n, "")
    }

    // --- The log ---

    /** What you're doing now; null when nothing is on. */
    fun current(ctx: Context): Entry? = latest(ctx)?.takeIf { it.activityId != IDLE }

    /** When you turned everything off; null if something is on. */
    fun idleSince(ctx: Context): Long? = latest(ctx)?.takeIf { it.activityId == IDLE }?.start

    private const val ENTRY_COLS = "id, activity_id, start_ms, context_id"

    private fun readEntry(c: Cursor) = Entry(c.getLong(0), c.getLong(1), c.getLong(2), contextId = c.longOrNull(3))

    private fun latest(ctx: Context): Entry? {
        val e = db(ctx).rawQuery(
            "SELECT $ENTRY_COLS FROM entry ORDER BY start_ms DESC, id DESC LIMIT 1",
            null,
        ).use { c -> if (c.moveToFirst()) readEntry(c) else null } ?: return null
        return e.copy(links = linksFor(ctx, listOf(e.id))[e.id] ?: emptySet())
    }

    private fun before(ctx: Context, e: Entry): Entry? =
        db(ctx).rawQuery(
            "SELECT $ENTRY_COLS FROM entry " +
                "WHERE (start_ms < ? OR (start_ms = ? AND id < ?)) ORDER BY start_ms DESC, id DESC LIMIT 1",
            args(e.start, e.start, e.id),
        ).use { c -> if (c.moveToFirst()) readEntry(c) else null }

    private fun insert(ctx: Context, activityId: Long, start: Long, contextId: Long? = null): Long =
        db(ctx).insert("entry", null, ContentValues().apply {
            put("activity_id", activityId)
            put("start_ms", start)
            if (contextId != null) put("context_id", contextId) else putNull("context_id")
        })

    /** The one thing the buttons do. The rules are in [LogRules.tap]. */
    fun tap(ctx: Context, activityId: Long) {
        val now = System.currentTimeMillis()
        if (activityId == lastTapAct && now - lastTapAt < DOUBLE_TAP_MS) return
        lastTapAct = activityId
        lastTapAt = now

        val cur = latest(ctx)
        val action = LogRules.tap(
            latest = cur,
            latestHasMoments = cur != null && hasMoments(ctx, cur.id),
            before = cur?.let { before(ctx, it) },
            activityId = activityId,
            now = now,
            stepMinutes = Prefs.stepMinutes(ctx),
        )
        when (action) {
            is LogRules.Tap.Start -> insert(ctx, action.activityId, action.at)
            is LogRules.Tap.MoveBack -> if (cur != null) moveBack(ctx, cur, action.byMs)
            is LogRules.Tap.Undo -> if (cur != null) deleteEntry(ctx, cur.id)
            is LogRules.Tap.Replace -> if (cur != null) {
                deleteEntry(ctx, cur.id)
                val at = action.startAt
                if (at != null) insert(ctx, activityId, at)
            }
        }
    }

    private fun moveBack(ctx: Context, cur: Entry, byMs: Long) {
        val prev = before(ctx, cur)
        val floor = if (prev != null) prev.start + 60_000L else 0L
        val start = maxOf(cur.start - byMs, floor)
        if (start < cur.start) setStart(ctx, cur.id, start)
    }

    private fun setStart(ctx: Context, id: Long, start: Long) {
        val v = ContentValues().apply { put("start_ms", start) }
        db(ctx).update("entry", v, "id=?", args(id))
    }

    /**
     * A chip on the widget: mark or unmark a person for what you're doing now. A place replaces
     * any other place, since you're in one place at a time.
     */
    fun toggleLink(ctx: Context, linkId: Long) {
        val cur = current(ctx) ?: return
        val link = allLinks(ctx).firstOrNull { it.id == linkId } ?: return
        if (link.kind == LinkKind.PLACE) {
            setPlace(ctx, cur.id, if (linkId in cur.links) null else linkId)
            return
        }
        val d = db(ctx)
        if (linkId in cur.links) {
            d.delete("entry_link", "entry_id=? AND link_id=?", args(cur.id, linkId))
        } else {
            d.insertWithOnConflict("entry_link", null, ContentValues().apply {
                put("entry_id", cur.id)
                put("link_id", linkId)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    /** Sets where an entry happened: [placeId], or nowhere when null. */
    fun setPlace(ctx: Context, entryId: Long, placeId: Long?) {
        tx(ctx) { d ->
            d.execSQL(
                "DELETE FROM entry_link WHERE entry_id = ? AND link_id IN (SELECT id FROM link WHERE kind = ?)",
                arrayOf<Any>(entryId, LinkKind.PLACE),
            )
            if (placeId != null) {
                d.insertWithOnConflict("entry_link", null, ContentValues().apply {
                    put("entry_id", entryId)
                    put("link_id", placeId)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    fun setContext(ctx: Context, entryId: Long, contextId: Long?) {
        val v = ContentValues().apply { if (contextId != null) put("context_id", contextId) else putNull("context_id") }
        db(ctx).update("entry", v, "id=?", args(entryId))
    }

    fun addEntry(ctx: Context, activityId: Long, start: Long, links: Set<Long>, contextId: Long?) {
        tx(ctx) {
            val id = insert(ctx, activityId, start, contextId)
            writeLinks(ctx, id, links)
        }
    }

    fun updateEntry(ctx: Context, id: Long, activityId: Long, start: Long, links: Set<Long>, contextId: Long?) {
        tx(ctx) { d ->
            val v = ContentValues().apply {
                put("activity_id", activityId)
                put("start_ms", start)
                if (contextId != null) put("context_id", contextId) else putNull("context_id")
            }
            d.update("entry", v, "id=?", args(id))
            writeLinks(ctx, id, links)
        }
    }

    private fun writeLinks(ctx: Context, entryId: Long, links: Set<Long>) {
        val d = db(ctx)
        d.delete("entry_link", "entry_id=?", args(entryId))
        links.forEach { l ->
            d.insert("entry_link", null, ContentValues().apply {
                put("entry_id", entryId)
                put("link_id", l)
            })
        }
    }

    /** Its notes and feelings stay, as moments of their own at the same time. */
    fun deleteEntry(ctx: Context, id: Long) {
        tx(ctx) { d ->
            d.execSQL("UPDATE moment SET entry_id = NULL WHERE entry_id = ?", arrayOf<Any>(id))
            d.delete("entry_link", "entry_id=?", args(id))
            d.delete("entry", "id=?", args(id))
        }
    }

    private fun linksFor(ctx: Context, ids: List<Long>): Map<Long, Set<Long>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, MutableSet<Long>>()
        db(ctx).rawQuery(
            "SELECT entry_id, link_id FROM entry_link WHERE entry_id IN (${ids.joinToString(",")})",
            null,
        ).use { c ->
            while (c.moveToNext()) out.getOrPut(c.getLong(0)) { HashSet() }.add(c.getLong(1))
        }
        return out
    }

    /** Everything that happened in [from, to), oldest first. An entry runs until the next one starts. */
    fun spans(ctx: Context, from: Long, to: Long): List<Span> {
        val rows = ArrayList<Entry>()
        db(ctx).rawQuery(
            "SELECT $ENTRY_COLS FROM entry " +
                "WHERE start_ms >= (SELECT COALESCE(MAX(start_ms), 0) FROM entry WHERE start_ms <= ?) " +
                "AND start_ms < ? ORDER BY start_ms, id",
            args(from, to),
        ).use { c ->
            while (c.moveToNext()) rows.add(readEntry(c))
        }
        val links = linksFor(ctx, rows.map { it.id })
        return LogRules.spans(rows.map { it.copy(links = links[it.id] ?: emptySet()) }, from, to, System.currentTimeMillis())
    }

    // --- Moments: notes and feelings ---

    fun hasMoments(ctx: Context, entryId: Long): Boolean =
        db(ctx).rawQuery("SELECT 1 FROM moment WHERE entry_id = ? LIMIT 1", args(entryId)).use { it.moveToFirst() }

    private const val MOMENT_COLS = "id, ts, kind, title, text, mood_id, entry_id"

    private fun readMoments(ctx: Context, where: String, a: Array<String>): List<Moment> {
        val rows = ArrayList<Moment>()
        db(ctx).rawQuery("SELECT $MOMENT_COLS FROM moment WHERE $where ORDER BY ts, id", a).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    Moment(
                        c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4),
                        c.longOrNull(5), c.longOrNull(6),
                    )
                )
            }
        }
        if (rows.isEmpty()) return rows
        val tags = HashMap<Long, MutableList<Long>>()
        rows.chunked(500).forEach { chunk ->
            db(ctx).rawQuery(
                "SELECT moment_id, tag_id FROM moment_tag WHERE moment_id IN (${chunk.joinToString(",") { it.id.toString() }})",
                null,
            ).use { c ->
                while (c.moveToNext()) tags.getOrPut(c.getLong(0)) { ArrayList() }.add(c.getLong(1))
            }
        }
        return rows.map { it.copy(tags = tags[it.id] ?: emptyList()) }
    }

    /** Moments in [from, to), oldest first. */
    fun moments(ctx: Context, from: Long, to: Long): List<Moment> =
        readMoments(ctx, "ts >= ? AND ts < ?", args(from, to))

    fun momentsOf(ctx: Context, entryId: Long): List<Moment> =
        readMoments(ctx, "entry_id = ?", args(entryId))

    fun allFeelings(ctx: Context): List<Moment> =
        readMoments(ctx, "kind = ? AND mood_id IS NOT NULL", args(MomentKind.FEELING))

    /** Activities and links a moment points to (from a Daylio import), as (kind, id). */
    fun refsFor(ctx: Context): Map<Long, List<Pair<String, Long>>> {
        val out = HashMap<Long, MutableList<Pair<String, Long>>>()
        db(ctx).rawQuery("SELECT moment_id, kind, ref_id FROM moment_ref", null).use { c ->
            while (c.moveToNext()) out.getOrPut(c.getLong(0)) { ArrayList() }.add(c.getString(1) to c.getLong(2))
        }
        return out
    }

    /** A feeling when there's a mood or tags; otherwise a note. Returns the new moment's id. */
    fun addMoment(
        ctx: Context,
        ts: Long,
        title: String,
        text: String,
        moodId: Long?,
        tags: Collection<Long>,
        entryId: Long?,
    ): Long = tx(ctx) { d ->
        val v = ContentValues().apply {
            put("ts", ts)
            put("kind", if (moodId != null || tags.isNotEmpty()) MomentKind.FEELING else MomentKind.NOTE)
            put("title", title)
            put("text", text)
            if (moodId != null) put("mood_id", moodId) else putNull("mood_id")
            if (entryId != null) put("entry_id", entryId) else putNull("entry_id")
        }
        val id = d.insert("moment", null, v)
        writeTags(d, id, tags)
        id
    }

    fun updateMoment(ctx: Context, m: Moment, ts: Long, text: String, moodId: Long?, tags: Collection<Long>) {
        tx(ctx) { d ->
            val v = ContentValues().apply {
                put("ts", ts)
                put("kind", if (moodId != null || tags.isNotEmpty()) MomentKind.FEELING else MomentKind.NOTE)
                put("text", text)
                if (moodId != null) put("mood_id", moodId) else putNull("mood_id")
            }
            d.update("moment", v, "id=?", args(m.id))
            d.delete("moment_tag", "moment_id=?", args(m.id))
            writeTags(d, m.id, tags)
        }
    }

    private fun writeTags(d: SQLiteDatabase, momentId: Long, tags: Collection<Long>) {
        tags.distinct().forEach { t ->
            d.insert("moment_tag", null, ContentValues().apply {
                put("moment_id", momentId)
                put("tag_id", t)
            })
        }
    }

    fun deleteMoment(ctx: Context, id: Long) {
        tx(ctx) { d ->
            d.delete("moment_tag", "moment_id=?", args(id))
            d.delete("moment_ref", "moment_id=?", args(id))
            d.delete("moment", "id=?", args(id))
        }
    }

    // --- Daylio ---

    /**
     * Each Daylio entry becomes one feeling moment at its wall-clock time, never a time span.
     * A stable key per entry (date, time, mood) means importing the same or an overlapping backup
     * again adds nothing. All in one transaction: it all goes in, or none of it does.
     */
    fun importDaylio(
        ctx: Context,
        data: DaylioData,
        targets: Map<Long, TagTarget>,
        progress: (done: Int) -> Unit,
    ): ImportResult = tx(ctx) { d ->
        // Moods: match by name, else add with the Daylio group as the level.
        val moods = allMoods(ctx)
        val moodIds = HashMap<Long, Long>()
        data.moods.forEach { m ->
            val found = moods.firstOrNull { it.name.equals(m.name, ignoreCase = true) }
            val level = m.level ?: 3
            moodIds[m.id] = found?.id ?: saveMood(ctx, null, m.name, levelFace(level), level)
        }

        // Tags: resolved (and created) only when an entry uses them.
        val tagNames = data.tags.associate { it.id to it.name }
        val feelingIds = HashMap<Long, Long>()
        val refIds = HashMap<Long, Pair<String, Long>?>()
        fun resolve(tagId: Long): Pair<String, Long>? {
            if (refIds.containsKey(tagId)) return refIds[tagId]
            val name = tagNames[tagId] ?: return null
            val r: Pair<String, Long>? = when (val t = targets[tagId] ?: TagTarget.Feeling) {
                is TagTarget.Feeling -> "tag" to feelingIds.getOrPut(tagId) { findOrAddTag(ctx, name) }
                is TagTarget.Skip -> null
                is TagTarget.ToActivity -> "activity" to (t.id ?: findOrAddAct(ctx, t.name))
                is TagTarget.ToLink -> "link" to (t.id ?: findOrAddLink(ctx, t.kind, t.name))
            }
            refIds[tagId] = r
            return r
        }

        var added = 0
        var there = 0
        var skipped = data.skipped
        val cal = Calendar.getInstance()
        data.entries.forEachIndexed { i, e ->
            if (i % 100 == 0) progress(i)
            val mood = data.mood(e.moodId)
            val moodId = moodIds[e.moodId]
            if (mood == null || moodId == null) {
                skipped++
                return@forEachIndexed
            }
            val key = Daylio.key(e, mood.name)
            val exists = d.rawQuery("SELECT 1 FROM moment WHERE source_key = ? LIMIT 1", arrayOf(key)).use { it.moveToFirst() }
            if (exists) {
                there++
                return@forEachIndexed
            }
            cal.clear()
            cal.set(e.year, e.month - 1, e.day, e.hour, e.minute, 0)
            val id = d.insert("moment", null, ContentValues().apply {
                put("ts", cal.timeInMillis)
                put("kind", MomentKind.FEELING)
                put("title", e.title)
                put("text", e.note)
                put("mood_id", moodId)
                putNull("entry_id")
                put("source_key", key)
            })
            e.tagIds.distinct().mapNotNull { resolve(it) }.distinct().forEach { (kind, refId) ->
                if (kind == "tag") {
                    d.insert("moment_tag", null, ContentValues().apply {
                        put("moment_id", id)
                        put("tag_id", refId)
                    })
                } else {
                    d.insertWithOnConflict("moment_ref", null, ContentValues().apply {
                        put("moment_id", id)
                        put("kind", kind)
                        put("ref_id", refId)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            added++
        }
        progress(data.entries.size)
        ImportResult(added, there, skipped)
    }

    private fun findOrAddAct(ctx: Context, name: String): Long {
        val all = allActs(ctx)
        val found = all.filter { it.name.equals(name.trim(), ignoreCase = true) }.minByOrNull { if (it.archived) 1 else 0 }
        if (found != null) return found.id
        // A new activity from an import gets no button until you choose to show one.
        return saveAct(ctx, null, name.trim(), name.trim().take(1).uppercase(Locale.ROOT),
            PALETTE[all.size % PALETTE.size], "", false)
    }

    // --- Smart button order ---

    /**
     * How likely each activity is right now, from the last 8 weeks: starts within an hour of this
     * time of day count most, and it counts for more on the same kind of day (weekday or weekend).
     */
    fun scores(ctx: Context, now: Long): Map<Long, Double> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val nowWeekend = isWeekend(cal)
        val out = HashMap<Long, Double>()
        db(ctx).rawQuery(
            "SELECT activity_id, start_ms FROM entry WHERE start_ms >= ?",
            args(now - 56 * DAY_MS),
        ).use { c ->
            while (c.moveToNext()) {
                cal.timeInMillis = c.getLong(1)
                val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                val raw = Math.abs(m - nowMin)
                val diff = minOf(raw, 1440 - raw)
                var w = if (diff <= 60) 1.0 else if (diff <= 120) 0.5 else 0.0
                if (w > 0.0) {
                    if (isWeekend(cal) == nowWeekend) w *= 1.5
                    val id = c.getLong(0)
                    out[id] = (out[id] ?: 0.0) + w
                }
            }
        }
        return out
    }

    private fun isWeekend(cal: Calendar): Boolean {
        val d = cal.get(Calendar.DAY_OF_WEEK)
        return d == Calendar.SATURDAY || d == Calendar.SUNDAY
    }

    fun picks(all: List<Act>, cur: Act?, n: Int, scores: Map<Long, Double>, smart: Boolean): List<Act> =
        LogRules.picks(all, cur, n, scores, smart)
}

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("now", Context.MODE_PRIVATE)

    fun lockNotification(ctx: Context): Boolean = p(ctx).getBoolean("notif", true)
    fun setLockNotification(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("notif", on).apply()

    fun smartOrder(ctx: Context): Boolean = p(ctx).getBoolean("smart", true)
    fun setSmartOrder(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("smart", on).apply()

    fun stepMinutes(ctx: Context): Int = p(ctx).getInt("tap_again", 0)
    fun setStepMinutes(ctx: Context, v: Int) = p(ctx).edit().putInt("tap_again", v).apply()

    fun lockButtons(ctx: Context): Int = p(ctx).getInt("lock_buttons", 5)
    fun setLockButtons(ctx: Context, v: Int) = p(ctx).edit().putInt("lock_buttons", v).apply()
}
