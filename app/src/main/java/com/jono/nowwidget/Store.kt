package com.jono.nowwidget

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jono.nowwidget.core.Act
import com.jono.nowwidget.core.BindLink
import com.jono.nowwidget.core.BindNote
import com.jono.nowwidget.core.CalRecord
import com.jono.nowwidget.core.CalRecordCodec
import com.jono.nowwidget.core.CalRef
import com.jono.nowwidget.core.CalendarSetup
import com.jono.nowwidget.core.Daylio
import com.jono.nowwidget.core.DaylioData
import com.jono.nowwidget.core.DiaryDay
import com.jono.nowwidget.core.Entry
import com.jono.nowwidget.core.FeelingTag
import com.jono.nowwidget.core.IDLE
import com.jono.nowwidget.core.KeywordRule
import com.jono.nowwidget.core.PlannedNudge
import com.jono.nowwidget.core.DirtyDays
import com.jono.nowwidget.core.Link
import com.jono.nowwidget.core.LinkBinding
import com.jono.nowwidget.core.LinkEntry
import com.jono.nowwidget.core.LinkIndex
import com.jono.nowwidget.core.LinkKind
import com.jono.nowwidget.core.LogRules
import com.jono.nowwidget.core.Moment
import com.jono.nowwidget.core.MomentKind
import com.jono.nowwidget.core.Mood
import com.jono.nowwidget.core.ObsidianDay
import com.jono.nowwidget.core.PALETTE
import com.jono.nowwidget.core.Schema
import com.jono.nowwidget.core.StoreSql
import com.jono.nowwidget.core.Span
import com.jono.nowwidget.core.TagTarget
import com.jono.nowwidget.core.TogglRecord
import com.jono.nowwidget.core.TogglRecordCodec
import com.jono.nowwidget.core.VaultNote
import com.jono.nowwidget.core.VaultNotes
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
    /** WAL: the export's background reads never wait behind a write, and the first vault scan's chunks are cheap. */
    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        Schema.create().forEach { db.execSQL(it) }
        SEEDS.forEachIndexed { i, s ->
            db.insertOrThrow("activity", null, ContentValues().apply {
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
        if (oldVersion < 3) Schema.upgradeFrom2().forEach { db.execSQL(it) }
    }
}

/** What an import did. [firstTs]/[lastTs] span the check-ins it added (null if none). */
data class ImportResult(
    val added: Int,
    val alreadyThere: Int,
    val skipped: Int,
    val firstTs: Long?,
    val lastTs: Long?,
)

/** A vault note as the scan read it, ready to merge into the link table. */
class VaultUpsert(val docId: String, val name: String, val modified: Long, val note: VaultNote)

/** What the scan already knows about a vault file. */
class VaultCacheRow(val docId: String, val name: String, val modified: Long, val kind: String, val linkId: Long?, val title: String)

data class VaultMerge(val created: Int, val matched: Int, val removed: Int)

object Store {
    private const val DOUBLE_TAP_MS = 700L
    private const val OBSIDIAN = "obsidian"
    const val CALENDAR = "calendar"
    /** Records of events left in a calendar that's no longer a log calendar (for "Remove log events from …"). */
    const val CALENDAR_LEFT = "calendar-left"
    private const val CALENDAR_TEST = "calendar-test"
    /** Records of events edited outside Now: never touched or replaced again (key stays taken). */
    private const val CALENDAR_RELEASED = "calendar-released"

    /** Toggl: records of entries sent (export_state), and of entries he deleted in Toggl (never made again). */
    const val TOGGL = "toggl"
    private const val TOGGL_RELEASED = "toggl-released"

    /** Every export target a change marks dirty: one place marks them all. */
    private val TARGETS = listOf(OBSIDIAN, CALENDAR, TOGGL)

    /** A change reaching further than this many diary days isn't marked past it; "Write all days" covers the rest. */
    private const val MAX_DIRTY_DAYS = 62
    private const val DAY_MS = 86_400_000L

    /** Notes written per transaction by the vault scan. */
    private const val VAULT_CHUNK = 200

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
            db(ctx).insertOrThrow("activity", null, v)
        } else {
            db(ctx).update("activity", v, "id=?", args(id))
            markWrittenDirty(ctx)
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
        // Links are written in your order, so a new order changes the notes already written.
        if (table == "link") markWrittenDirty(ctx)
    }

    // --- Links: people, places, contexts ---

    /** Every link, including removed ones, in your order, with whether the vault has a note for it and how much it's used. */
    fun allLinks(ctx: Context): List<Link> {
        val d = db(ctx)
        val uses = HashMap<Long, Int>()
        d.rawQuery(StoreSql.LINK_USES, null).use { c ->
            while (c.moveToNext()) uses[c.getLong(0)] = c.getInt(1)
        }
        d.rawQuery(StoreSql.CONTEXT_USES, null).use { c ->
            while (c.moveToNext()) uses[c.getLong(0)] = (uses[c.getLong(0)] ?: 0) + c.getInt(1)
        }
        val inVault = HashSet<Long>()
        d.rawQuery("SELECT DISTINCT link_id FROM vault_note WHERE link_id IS NOT NULL UNION SELECT link_id FROM link_note", null).use { c ->
            while (c.moveToNext()) inVault.add(c.getLong(0))
        }
        val fromVault = HashSet<Long>()
        d.rawQuery("SELECT link_id FROM link_source", null).use { c ->
            while (c.moveToNext()) fromVault.add(c.getLong(0))
        }
        val out = ArrayList<Link>()
        d.rawQuery("SELECT id, title, kind, emoji, pos, archived, pinned FROM link ORDER BY pos, id", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out.add(
                    Link(
                        id, c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getInt(5) != 0,
                        pinned = c.getInt(6) != 0, inVault = id in inVault, fromVault = id in fromVault,
                        uses = uses[id] ?: 0,
                    )
                )
            }
        }
        return out
    }

    fun links(ctx: Context, kind: String): List<Link> = allLinks(ctx).filter { it.kind == kind && !it.archived }

    /** [pinned] null leaves it as it is (a new link starts unpinned). */
    fun saveLink(ctx: Context, id: Long?, title: String, kind: String, emoji: String, pinned: Boolean? = null): Long {
        val v = ContentValues().apply {
            put("title", title)
            put("kind", kind)
            put("emoji", emoji)
            if (pinned != null) put("pinned", if (pinned) 1 else 0)
        }
        return if (id == null) {
            v.put("pos", nextPos(ctx, "link"))
            db(ctx).insertOrThrow("link", null, v)
        } else {
            db(ctx).update("link", v, "id=?", args(id))
            markWrittenDirty(ctx)
            id
        }
    }

    /** A link from the picker or the add screen: brought back if removed, and pinned when [pin]. */
    fun reviveLink(ctx: Context, id: Long, pin: Boolean = false) {
        val v = ContentValues().apply {
            put("archived", 0)
            if (pin) put("pinned", 1)
        }
        db(ctx).update("link", v, "id=?", args(id))
    }

    /**
     * Adds a link from the Add screen. A name that's an alias of a vault note means that note's link;
     * a name you already have comes back if it was removed. Either way it's pinned, so it shows.
     */
    fun addLink(ctx: Context, title: String, kind: String, emoji: String): Long {
        val t = title.trim()
        val id = linkIndex(ctx).resolve(t, setOf(kind))?.id ?: findOrAddLink(ctx, kind, t)
        val v = ContentValues().apply {
            put("archived", 0)
            put("pinned", 1)
            if (emoji.isNotBlank()) put("emoji", emoji)
        }
        db(ctx).update("link", v, "id=?", args(id))
        return id
    }

    /** Type-ahead over every link: titles, and the aliases their vault notes carry. */
    fun linkIndex(ctx: Context): LinkIndex {
        val aliases = vaultAliases(ctx)
        return LinkIndex(allLinks(ctx).map {
            LinkEntry(it.id, it.title, it.kind, aliases[it.id].orEmpty(), it.uses, it.archived, it.pinned)
        })
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
            markWrittenDirty(ctx)
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
            d.insertOrThrow("mood", null, v)
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
            db(ctx).insertOrThrow("feeling_tag", null, v)
        } else {
            db(ctx).update("feeling_tag", v, "id=?", args(id))
            markWrittenDirty(ctx)
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

    private fun insert(ctx: Context, activityId: Long, start: Long, contextId: Long? = null): Long {
        val id = db(ctx).insertOrThrow("entry", null, ContentValues().apply {
            put("activity_id", activityId)
            put("start_ms", start)
            if (contextId != null) put("context_id", contextId) else putNull("context_id")
        })
        dirtyAround(ctx, start)
        return id
    }

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
            inheritedContextId = cur?.let { contextBefore(ctx, it) },
        )
        when (action) {
            is LogRules.Tap.Start ->
                insert(ctx, action.activityId, action.at, LogRules.contextForNew(action.activityId, lastContext(ctx)))
            is LogRules.Tap.MoveBack -> if (cur != null) moveBack(ctx, cur, action.byMs)
            is LogRules.Tap.Undo -> if (cur != null) deleteEntry(ctx, cur.id)
            is LogRules.Tap.Replace -> if (cur != null) {
                // One transaction: the mistake goes, and its replacement keeps its people,
                // place and context.
                tx(ctx) {
                    deleteEntry(ctx, cur.id)
                    val at = action.startAt
                    if (at != null) {
                        val id = insert(ctx, activityId, at, cur.contextId)
                        writeLinks(ctx, id, cur.links)
                    }
                }
            }
        }
    }

    private fun contextQuery(ctx: Context, sql: String, a: Array<String>?): Long? =
        db(ctx).rawQuery(sql, a).use { c -> if (c.moveToFirst()) c.longOrNull(0) else null }

    /** The context a new entry picks up: that of the last non-idle entry. */
    private fun lastContext(ctx: Context): Long? = contextQuery(ctx, StoreSql.LAST_CONTEXT, null)

    private fun contextBefore(ctx: Context, e: Entry): Long? =
        contextQuery(ctx, StoreSql.CONTEXT_BEFORE, args(e.start, e.start, e.id))

    private fun moveBack(ctx: Context, cur: Entry, byMs: Long) {
        val prev = before(ctx, cur)
        val floor = if (prev != null) prev.start + 60_000L else 0L
        val start = maxOf(cur.start - byMs, floor)
        if (start < cur.start) setStart(ctx, cur.id, start)
    }

    private fun setStart(ctx: Context, id: Long, start: Long) {
        val old = startOf(ctx, id)
        val v = ContentValues().apply { put("start_ms", start) }
        db(ctx).update("entry", v, "id=?", args(id))
        if (old != null) dirtyAround(ctx, old)
        dirtyAround(ctx, start)
    }

    /**
     * A chip on the widget: mark or unmark a person for what you're doing now. A place replaces
     * any other place, since you're in one place at a time.
     */
    fun toggleLink(ctx: Context, linkId: Long) {
        val cur = current(ctx) ?: return
        val link = allLinks(ctx).firstOrNull { it.id == linkId } ?: return
        if (link.kind == LinkKind.CONTEXT) {
            setContext(ctx, cur.id, if (cur.contextId == linkId) null else linkId)
            return
        }
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
        dirtyAround(ctx, cur.start)
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
        startOf(ctx, entryId)?.let { dirtyAround(ctx, it) }
    }

    fun setContext(ctx: Context, entryId: Long, contextId: Long?) {
        val v = ContentValues().apply { if (contextId != null) put("context_id", contextId) else putNull("context_id") }
        db(ctx).update("entry", v, "id=?", args(entryId))
        startOf(ctx, entryId)?.let { dirtyAround(ctx, it) }
    }

    fun addEntry(ctx: Context, activityId: Long, start: Long, links: Set<Long>, contextId: Long?) {
        tx(ctx) {
            val id = insert(ctx, activityId, start, contextId)
            writeLinks(ctx, id, links)
        }
    }

    fun updateEntry(ctx: Context, id: Long, activityId: Long, start: Long, links: Set<Long>, contextId: Long?) {
        tx(ctx) { d ->
            val old = startOf(ctx, id)
            val v = ContentValues().apply {
                put("activity_id", activityId)
                put("start_ms", start)
                if (contextId != null) put("context_id", contextId) else putNull("context_id")
            }
            d.update("entry", v, "id=?", args(id))
            writeLinks(ctx, id, links)
            // Both the old place in the day and the new one: a move across days marks both.
            if (old != null) dirtyAround(ctx, old)
            dirtyAround(ctx, start)
        }
    }

    private fun writeLinks(ctx: Context, entryId: Long, links: Set<Long>) {
        val d = db(ctx)
        d.delete("entry_link", "entry_id=?", args(entryId))
        links.forEach { l ->
            d.insertOrThrow("entry_link", null, ContentValues().apply {
                put("entry_id", entryId)
                put("link_id", l)
            })
        }
    }

    /** Its notes and feelings stay, as moments of their own at the same time. */
    fun deleteEntry(ctx: Context, id: Long) {
        tx(ctx) { d ->
            val old = startOf(ctx, id)
            d.execSQL(StoreSql.DETACH_MOMENTS, arrayOf<Any>(id))
            d.execSQL(StoreSql.DELETE_ENTRY_LINKS, arrayOf<Any>(id))
            d.execSQL(StoreSql.DELETE_ENTRY, arrayOf<Any>(id))
            if (old != null) dirtyAround(ctx, old)
        }
    }

    private fun startOf(ctx: Context, entryId: Long): Long? =
        db(ctx).rawQuery("SELECT start_ms FROM entry WHERE id = ?", args(entryId)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
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
        val id = d.insertOrThrow("moment", null, v)
        writeTags(d, id, tags)
        dirtyDay(ctx, ts)
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
            dirtyDay(ctx, m.ts)
            dirtyDay(ctx, ts)
        }
    }

    private fun writeTags(d: SQLiteDatabase, momentId: Long, tags: Collection<Long>) {
        tags.distinct().forEach { t ->
            d.insertOrThrow("moment_tag", null, ContentValues().apply {
                put("moment_id", momentId)
                put("tag_id", t)
            })
        }
    }

    fun deleteMoment(ctx: Context, id: Long) {
        tx(ctx) { d ->
            d.rawQuery("SELECT ts FROM moment WHERE id = ?", args(id)).use { c ->
                if (c.moveToFirst()) dirtyDay(ctx, c.getLong(0))
            }
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
        var firstTs: Long? = null
        var lastTs: Long? = null
        val cal = Calendar.getInstance()
        val insertMoment = d.compileStatement(StoreSql.INSERT_IMPORTED)
        val importedDays = LinkedHashSet<Long>()
        data.entries.forEachIndexed { i, e ->
            if (i % 100 == 0) progress(i)
            val mood = data.mood(e.moodId)
            val moodId = moodIds[e.moodId]
            if (mood == null || moodId == null) {
                skipped++
                return@forEachIndexed
            }
            cal.clear()
            cal.set(e.year, e.month - 1, e.day, e.hour, e.minute, 0)
            val ts = cal.timeInMillis
            val plain = Daylio.key(e, mood.name)
            val noteAtKey = d.rawQuery(StoreSql.NOTE_AT_KEY, arrayOf(plain)).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
            val altTaken = noteAtKey != null && noteAtKey != e.note &&
                d.rawQuery(StoreSql.KEY_TAKEN, arrayOf(Daylio.altKey(e, mood.name))).use { it.moveToFirst() }
            val native = d.rawQuery(StoreSql.NATIVE_FEELING_AT, args(moodId, ts, ts + 60_000L)).use { it.moveToFirst() }
            val key = when (val dec = Daylio.decide(e, mood.name, noteAtKey, altTaken, native)) {
                is Daylio.Decision.Skip -> {
                    there++
                    return@forEachIndexed
                }
                is Daylio.Decision.Insert -> dec.key
            }
            insertMoment.clearBindings()
            insertMoment.bindLong(1, ts)
            insertMoment.bindString(2, e.title)
            insertMoment.bindString(3, e.note)
            insertMoment.bindLong(4, moodId)
            insertMoment.bindString(5, key)
            val id = insertMoment.executeInsert()
            if (id == -1L) throw SQLException("Couldn't save a check-in")
            e.tagIds.distinct().mapNotNull { resolve(it) }.distinct().forEach { (kind, refId) ->
                if (kind == "tag") {
                    d.execSQL(StoreSql.INSERT_MOMENT_TAG, arrayOf<Any>(id, refId))
                } else {
                    d.execSQL(StoreSql.INSERT_MOMENT_REF, arrayOf<Any>(id, kind, refId))
                }
            }
            added++
            importedDays.add(DiaryDay.start(ts))
            firstTs = minOf(firstTs ?: ts, ts)
            lastTs = maxOf(lastTs ?: ts, ts)
        }
        progress(data.entries.size)
        importedDays.forEach { markDay(d, it) }
        if (importedDays.isNotEmpty()) exportersChanged(ctx)
        ImportResult(added, there, skipped, firstTs, lastTs)
    }

    private fun findOrAddAct(ctx: Context, name: String): Long {
        val all = allActs(ctx)
        val found = all.filter { it.name.equals(name.trim(), ignoreCase = true) }.minByOrNull { if (it.archived) 1 else 0 }
        if (found != null) return found.id
        // A new activity from an import gets no button until you choose to show one.
        return saveAct(ctx, null, name.trim(), name.trim().take(1).uppercase(Locale.ROOT),
            PALETTE[all.size % PALETTE.size], "", false)
    }

    /** The earliest and latest thing logged (entry or moment), or null when there's nothing. */
    fun firstAndLast(ctx: Context): Pair<Long, Long>? =
        db(ctx).rawQuery(
            "SELECT MIN(t), MAX(t) FROM (SELECT start_ms AS t FROM entry UNION ALL SELECT ts AS t FROM moment)",
            null,
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) to c.getLong(1) else null }

    // --- Obsidian export: which days changed ---

    /** Marks the day dirty for every export target (Obsidian, Calendar and Toggl). */
    private fun markDay(d: SQLiteDatabase, dayStart: Long) {
        val (y, m, day) = DiaryDay.date(dayStart)
        TARGETS.forEach { d.execSQL(StoreSql.MARK_DAY_DIRTY, arrayOf<Any>(it, ObsidianDay.key(y, m, day))) }
    }

    /** Tells every exporter that days were marked. Never throws. */
    private fun exportersChanged(ctx: Context) {
        ObsidianExport.changed(ctx)
        CalendarExport.changed(ctx)
        TogglExport.changed(ctx)
    }

    /** A change to one moment touches the diary day it sits in. */
    private fun dirtyDay(ctx: Context, ts: Long) {
        markDay(db(ctx), DiaryDay.start(ts))
        exportersChanged(ctx)
    }

    /**
     * Every change to an entry goes through here (insert, move, delete, links, context). The change
     * at [ts] can reach every day from where the entry before it started to where the next one
     * starts (or today), since an entry runs until the next begins.
     */
    private fun dirtyAround(ctx: Context, ts: Long) {
        val d = db(ctx)
        val prev = d.rawQuery(StoreSql.PREV_ENTRY_START, args(ts)).use { c -> if (c.moveToFirst()) c.longOrNull(0) else null }
        val next = d.rawQuery(StoreSql.NEXT_ENTRY_START, args(ts)).use { c -> if (c.moveToFirst()) c.longOrNull(0) else null }
        val last = DiaryDay.start(maxOf(next ?: System.currentTimeMillis(), ts))
        val first = DiaryDay.start(minOf(prev ?: ts, ts))
        DirtyDays.reach(DiaryDay.start(ts), first, last, MAX_DIRTY_DAYS).forEach { markDay(d, it) }
        exportersChanged(ctx)
    }

    /** A rename or regroup can change every note and event already written. */
    private fun markWrittenDirty(ctx: Context) {
        tx(ctx) { d ->
            d.execSQL(StoreSql.MARK_WRITTEN_BUMP, arrayOf<Any>(OBSIDIAN, OBSIDIAN))
            d.execSQL(StoreSql.MARK_WRITTEN_ADD, arrayOf<Any>(OBSIDIAN, OBSIDIAN))
            d.execSQL(StoreSql.CAL_MARK_WRITTEN_BUMP, arrayOf<Any>(CALENDAR, CALENDAR))
            d.execSQL(StoreSql.CAL_MARK_WRITTEN_ADD, arrayOf<Any>(CALENDAR, CALENDAR))
            d.execSQL(StoreSql.CAL_MARK_WRITTEN_BUMP, arrayOf<Any>(TOGGL, TOGGL))
            d.execSQL(StoreSql.CAL_MARK_WRITTEN_ADD, arrayOf<Any>(TOGGL, TOGGL))
        }
        exportersChanged(ctx)
    }

    /** The Toggl description options changed: every day with sent entries is looked at again (only entries whose text changed are updated). */
    fun markTogglWrittenDirty(ctx: Context) {
        tx(ctx) { d ->
            d.execSQL(StoreSql.CAL_MARK_WRITTEN_BUMP, arrayOf<Any>(TOGGL, TOGGL))
            d.execSQL(StoreSql.CAL_MARK_WRITTEN_ADD, arrayOf<Any>(TOGGL, TOGGL))
        }
        TogglExport.changed(ctx)
    }

    /** A calendar setting changed (log calendar, areas, colors): every day with events is redone. */
    fun markCalendarWrittenDirty(ctx: Context) {
        tx(ctx) { d ->
            d.execSQL(StoreSql.CAL_MARK_WRITTEN_BUMP, arrayOf<Any>(CALENDAR, CALENDAR))
            d.execSQL(StoreSql.CAL_MARK_WRITTEN_ADD, arrayOf<Any>(CALENDAR, CALENDAR))
        }
        CalendarExport.changed(ctx)
    }

    /** Marks today's diary day dirty (its running activity and totals move with the clock). */
    fun markTodayDirty(ctx: Context) {
        markDay(db(ctx), DiaryDay.start(System.currentTimeMillis()))
    }

    fun markDayDirty(ctx: Context, dayStart: Long) {
        markDay(db(ctx), dayStart)
    }

    /** Marks these days dirty for one target only (turning the calendar export on). */
    fun markDaysDirty(ctx: Context, target: String, dayStarts: List<Long>) {
        tx(ctx) { d ->
            dayStarts.forEach { dayStart ->
                val (y, m, day) = DiaryDay.date(dayStart)
                d.execSQL(StoreSql.MARK_DAY_DIRTY, arrayOf<Any>(target, ObsidianDay.key(y, m, day)))
            }
        }
    }

    /** Day keys (`2025-4-9`) waiting to be written, each with its change counter. */
    fun dirtyDays(ctx: Context, target: String = OBSIDIAN): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        db(ctx).rawQuery(StoreSql.DIRTY_DAYS, args(target)).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
        }
        return out
    }

    /** The day's change counter: 0 when it's clean. */
    fun dayGen(ctx: Context, key: String, target: String = OBSIDIAN): Int =
        db(ctx).rawQuery(StoreSql.DAY_GEN, args(target, key)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Clears the dirty mark only if the day hasn't been marked again since it was at [gen]. */
    fun clearDirtyIf(ctx: Context, key: String, gen: Int, target: String = OBSIDIAN): Boolean {
        val d = db(ctx)
        d.execSQL(StoreSql.CLEAR_DAY_DIRTY, arrayOf<Any>(target, key, gen))
        return dayGen(ctx, key, target) == 0
    }

    // --- Calendar export records (export_state, target 'calendar') ---

    private fun remote(calendarId: Long, eventId: Long) = "$calendarId:$eventId"

    private fun parseRemote(remote: String): Pair<Long, Long>? {
        val parts = remote.split(':')
        val cal = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val ev = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return cal to ev
    }

    /** Rows of (local_key, remote_id, content_hash). [keyFromHash]: the key is in the hash column (left and test records). */
    private fun readRecords(c: Cursor, keyFromHash: Boolean): List<CalRecord> {
        val out = ArrayList<CalRecord>()
        while (c.moveToNext()) {
            val (cal, ev) = parseRemote(c.getString(1)) ?: continue
            out.add(
                if (keyFromHash) CalRecord(c.getString(2), cal, ev, "")
                else CalRecordCodec.read(c.getString(0), cal, ev, c.getString(2))
            )
        }
        return out
    }

    private fun dayRange(dayKey: String) = arrayOf("$dayKey/", "${dayKey}0")

    /** The app's records of the events it wrote for one diary day (`2025-4-9`), pending and suspect ones included. */
    fun calRecords(ctx: Context, dayKey: String): List<CalRecord> =
        db(ctx).rawQuery(StoreSql.CAL_RECORDS_OF_DAY, arrayOf(CALENDAR) + dayRange(dayKey)).use { readRecords(it, false) }

    /** Keys of one day's events that were edited outside Now. */
    fun releasedKeys(ctx: Context, dayKey: String): Set<String> =
        db(ctx).rawQuery(StoreSql.CAL_RECORDS_OF_DAY, arrayOf(CALENDAR_RELEASED) + dayRange(dayKey)).use { c ->
            val out = HashSet<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }

    fun releasedCount(ctx: Context): Int =
        db(ctx).rawQuery("SELECT COUNT(*) FROM export_state WHERE target = ?", args(CALENDAR_RELEASED)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /** Every current record (not the pending ones). */
    fun allCalRecords(ctx: Context): List<CalRecord> =
        db(ctx).rawQuery(StoreSql.CAL_RECORDS_ALL, args(CALENDAR)).use { readRecords(it, false) }.filter { !it.pending }

    fun leftCalRecords(ctx: Context): List<CalRecord> =
        db(ctx).rawQuery(StoreSql.CAL_RECORDS_ALL, args(CALENDAR_LEFT)).use { readRecords(it, true) }

    fun saveCalRecord(ctx: Context, r: CalRecord) {
        db(ctx).execSQL(StoreSql.SAVE_HASH, arrayOf<Any>(CALENDAR, r.key, remote(r.calendarId, r.eventId), CalRecordCodec.hashField(r), System.currentTimeMillis()))
    }

    fun dropCalRecord(ctx: Context, key: String) {
        db(ctx).execSQL(StoreSql.CAL_RECORD_DELETE, arrayOf<Any>(CALENDAR, key))
    }

    /** The event stays where it is; its record moves aside so "Remove log events from …" can still find it. */
    fun leaveCalRecord(ctx: Context, r: CalRecord) {
        tx(ctx) { d ->
            val id = remote(r.calendarId, r.eventId)
            d.execSQL(StoreSql.SAVE_HASH, arrayOf<Any>(CALENDAR_LEFT, id, id, r.key, System.currentTimeMillis()))
            d.execSQL(StoreSql.CAL_RECORD_DELETE, arrayOf<Any>(CALENDAR, r.key))
        }
    }

    /** Edited outside Now: the record moves to the released list, which keeps its key from being written again. */
    fun releaseCalRecord(ctx: Context, r: CalRecord, why: String) {
        tx(ctx) { d ->
            d.execSQL(StoreSql.SAVE_HASH, arrayOf<Any>(CALENDAR_RELEASED, r.key, remote(r.calendarId, r.eventId), why, System.currentTimeMillis()))
            d.execSQL(StoreSql.CAL_RECORD_DELETE, arrayOf<Any>(CALENDAR, r.key))
        }
    }

    fun dropLeftRecord(ctx: Context, r: CalRecord) {
        db(ctx).execSQL(StoreSql.CAL_RECORD_DELETE, arrayOf<Any>(CALENDAR_LEFT, remote(r.calendarId, r.eventId)))
    }

    /** Every "Test calendar" event recorded (each one's key is the marker it was written with). */
    fun testRecords(ctx: Context): List<CalRecord> =
        db(ctx).rawQuery(StoreSql.CAL_RECORDS_ALL, args(CALENDAR_TEST)).use { readRecords(it, true) }

    fun addTestRecord(ctx: Context, r: CalRecord) {
        db(ctx).execSQL(StoreSql.SAVE_HASH, arrayOf<Any>(CALENDAR_TEST, r.key, remote(r.calendarId, r.eventId), r.key, System.currentTimeMillis()))
    }

    fun dropTestRecord(ctx: Context, key: String) {
        db(ctx).execSQL(StoreSql.CAL_RECORD_DELETE, arrayOf<Any>(CALENDAR_TEST, key))
    }

    // --- Toggl export records (export_state, target 'toggl'; remote_id `workspaceId:entryId`, entry 0 while pending) ---

    private fun readTogglRecords(c: Cursor): List<TogglRecord> {
        val out = ArrayList<TogglRecord>()
        while (c.moveToNext()) {
            val (ws, id) = parseRemote(c.getString(1)) ?: continue
            out.add(TogglRecordCodec.read(c.getString(0), ws, id, c.getString(2)))
        }
        return out
    }

    /** The app's records of the Toggl entries it sent for one diary day (`2025-4-9`), pending ones included. */
    fun togglRecords(ctx: Context, dayKey: String): List<TogglRecord> =
        db(ctx).rawQuery(StoreSql.CAL_RECORDS_OF_DAY, arrayOf(TOGGL) + dayRange(dayKey)).use { readTogglRecords(it) }

    /** Keys of one day's entries that were deleted in Toggl. */
    fun togglReleasedKeys(ctx: Context, dayKey: String): Set<String> =
        db(ctx).rawQuery(StoreSql.CAL_RECORDS_OF_DAY, arrayOf(TOGGL_RELEASED) + dayRange(dayKey)).use { c ->
            val out = HashSet<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }

    /** Every record, pending ones included. */
    fun allTogglRecords(ctx: Context): List<TogglRecord> =
        db(ctx).rawQuery(StoreSql.CAL_RECORDS_ALL, args(TOGGL)).use { readTogglRecords(it) }

    fun saveTogglRecord(ctx: Context, r: TogglRecord) {
        db(ctx).execSQL(StoreSql.SAVE_HASH, arrayOf<Any>(TOGGL, r.key, "${r.workspaceId}:${r.entryId}", TogglRecordCodec.hashField(r), System.currentTimeMillis()))
    }

    fun dropTogglRecord(ctx: Context, key: String) {
        db(ctx).execSQL(StoreSql.CAL_RECORD_DELETE, arrayOf<Any>(TOGGL, key))
    }

    /** Deleted in Toggl: the record moves to the released list, which keeps its key from being sent again. */
    fun releaseTogglRecord(ctx: Context, r: TogglRecord, why: String) {
        tx(ctx) { d ->
            d.execSQL(StoreSql.SAVE_HASH, arrayOf<Any>(TOGGL_RELEASED, r.key, "${r.workspaceId}:${r.entryId}", why, System.currentTimeMillis()))
            d.execSQL(StoreSql.CAL_RECORD_DELETE, arrayOf<Any>(TOGGL, r.key))
        }
    }

    /** Entries recorded as sent (a pending record, entry 0, isn't one). */
    fun togglSentCount(ctx: Context): Int =
        db(ctx).rawQuery(StoreSql.TOGGL_SENT_COUNT, args(TOGGL)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun togglReleasedCount(ctx: Context): Int =
        db(ctx).rawQuery("SELECT COUNT(*) FROM export_state WHERE target = ?", args(TOGGL_RELEASED)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    // --- Areas: which of your calendars each group maps to ---

    /** Group ('' for ungrouped) to the calendar it maps to, with its identity; null means no area (no color). */
    fun groupAreas(ctx: Context): Map<String, CalRef?> {
        val out = HashMap<String, CalRef?>()
        db(ctx).rawQuery(StoreSql.GROUP_AREAS, null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = CalRef.decode(c.getString(1))
        }
        return out
    }

    fun setGroupArea(ctx: Context, group: String, ref: CalRef?) {
        db(ctx).execSQL(StoreSql.GROUP_AREA_SET, arrayOf<Any>(group, ref?.encode() ?: ""))
        // Per-area log calendars of areas nothing maps to any more are dropped.
        Prefs.setCalAreaLogs(ctx, CalendarSetup.prune(Prefs.calAreaLogs(ctx), groupAreas(ctx)))
        markCalendarWrittenDirty(ctx)
    }

    /** How often each activity was started in the last 8 weeks. */
    fun actUses(ctx: Context, now: Long): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        db(ctx).rawQuery(StoreSql.ACT_USES, args(now - 56 * DAY_MS)).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getInt(1)
        }
        return out
    }

    fun exportHash(ctx: Context, key: String): String? =
        db(ctx).rawQuery(StoreSql.HASH_OF_DAY, args(OBSIDIAN, key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun exportDocId(ctx: Context, key: String): String? =
        db(ctx).rawQuery(StoreSql.REMOTE_OF_DAY, args(OBSIDIAN, key)).use { c -> if (c.moveToFirst()) c.getString(0).ifEmpty { null } else null }

    fun saveExportHash(ctx: Context, key: String, remoteId: String, hash: String) {
        db(ctx).execSQL(StoreSql.SAVE_HASH, arrayOf<Any>(OBSIDIAN, key, remoteId, hash, System.currentTimeMillis()))
    }

    /** Forget what was written (a different vault, or none): the next write is a full one. */
    fun clearExportState(ctx: Context) {
        db(ctx).execSQL("DELETE FROM export_state WHERE target = ?", arrayOf<Any>(OBSIDIAN))
    }

    /** Ids of the moments in [from, to) that came from a Daylio import. */
    fun importedMomentIds(ctx: Context, from: Long, to: Long): Set<Long> {
        val out = HashSet<Long>()
        db(ctx).rawQuery(
            "SELECT id FROM moment WHERE source_key IS NOT NULL AND ts >= ? AND ts < ?", args(from, to),
        ).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        return out
    }

    /** The start of every diary day with something in it, oldest first. Imported check-ins count only when asked. */
    fun activeDays(ctx: Context, includeImported: Boolean): List<Long> {
        val days = java.util.TreeSet<Long>()
        spans(ctx, 0L, System.currentTimeMillis()).forEach { s ->
            var day = DiaryDay.start(s.from)
            val last = DiaryDay.start(maxOf(s.from, s.to - 1))
            var n = 0
            while (day <= last && n < 400) {
                days.add(day)
                day = DiaryDay.add(day, 1)
                n++
            }
        }
        db(ctx).rawQuery("SELECT ts, source_key IS NOT NULL FROM moment", null).use { c ->
            while (c.moveToNext()) {
                if (includeImported || c.getInt(1) == 0) days.add(DiaryDay.start(c.getLong(0)))
            }
        }
        return days.toList()
    }

    // --- The vault index ---

    fun vaultCache(ctx: Context): Map<String, VaultCacheRow> {
        val out = HashMap<String, VaultCacheRow>()
        db(ctx).rawQuery(StoreSql.VAULT_CACHE, null).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = VaultCacheRow(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.longOrNull(4), c.getString(5))
            }
        }
        return out
    }

    fun vaultCount(ctx: Context): Int =
        db(ctx).rawQuery("SELECT COUNT(*) FROM vault_note", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Aliases from the vault notes, by the link each note matched. */
    fun vaultAliases(ctx: Context): Map<Long, List<String>> {
        val out = HashMap<Long, MutableList<String>>()
        db(ctx).rawQuery(StoreSql.VAULT_ALIASES, null).use { c ->
            while (c.moveToNext()) out.getOrPut(c.getLong(0)) { ArrayList() }.addAll(VaultNotes.unpackAliases(c.getString(1)))
        }
        return out
    }

    /** Forget the cache (and with it which links have notes). The links themselves stay. */
    fun clearVaultCache(ctx: Context) {
        db(ctx).execSQL("DELETE FROM vault_note")
        db(ctx).execSQL(StoreSql.LINK_NOTES_CLEAR)
    }

    /** The titles of the notes that links are bound to by alias, by link id. */
    fun boundNotes(ctx: Context): Map<Long, String> {
        val out = HashMap<Long, String>()
        db(ctx).rawQuery(StoreSql.LINK_NOTES, null).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getString(2)
        }
        return out
    }

    /**
     * Merges what a scan found into the link table, a chunk of notes per transaction (the first scan
     * has thousands). Per note, [LinkBinding.plan] decides: a note renamed in the vault (same document
     * id, new name) renames the link that carried its old title; a title you already have (any case)
     * is matched to that link and the link is left exactly as it is; otherwise a new link is made and
     * marked as made by the vault. Then links that are one note's alias are bound to it. Links are
     * never archived or deleted here, whatever happened to the note. Written notes that this could
     * change (a retitle, a kind change, a new or changed binding) are marked dirty.
     */
    fun applyVaultScan(ctx: Context, upserts: List<VaultUpsert>, removed: Collection<String>): VaultMerge {
        val cache = HashMap(vaultCache(ctx))
        val links = allLinks(ctx)
        val entries = ArrayList<LinkEntry>()
        links.forEach { l -> entries.add(LinkEntry(l.id, l.title, l.kind, emptyList(), l.uses, l.archived, l.pinned)) }
        val scanMade = links.filter { it.fromVault }.associateBy { it.id }
        var pos = nextPos(ctx, "link")
        var created = 0
        var matched = 0
        var changed = false

        upserts.chunked(VAULT_CHUNK).forEach { chunk ->
            tx(ctx) { d ->
                chunk.forEach { u ->
                    val n = u.note
                    val old = cache[u.docId]
                    val plan = LinkBinding.plan(BindNote(u.docId, n.title, n.aliases), n.kind, old?.title, old?.linkId, entries)
                    val linkId: Long
                    when (plan) {
                        is LinkBinding.Plan.Create -> {
                            linkId = d.insertOrThrow("link", null, ContentValues().apply {
                                put("title", n.title)
                                put("kind", n.kind)
                                put("emoji", "")
                                put("pos", pos++)
                                put("pinned", 0)
                                put("archived", 0)
                            })
                            d.execSQL(StoreSql.LINK_SOURCE_ADD, arrayOf<Any>(linkId))
                            entries.add(LinkEntry(linkId, n.title, n.kind, emptyList(), 0, false, false))
                            created++
                        }
                        is LinkBinding.Plan.Rename -> {
                            linkId = plan.linkId
                            d.execSQL(StoreSql.VAULT_RETITLE, arrayOf<Any>(plan.newTitle, linkId))
                            val i = entries.indexOfFirst { it.id == linkId }
                            entries[i] = entries[i].copy(title = plan.newTitle)
                            changed = true
                            matched++
                        }
                        is LinkBinding.Plan.Match -> {
                            linkId = plan.linkId
                            matched++
                        }
                    }
                    if (plan !is LinkBinding.Plan.Create) {
                        // A scan-made link follows its note's tag while you haven't changed the kind yourself.
                        val made = scanMade[linkId]
                        if (old != null && made != null && old.kind != n.kind && made.kind == old.kind) {
                            d.update("link", ContentValues().apply { put("kind", n.kind) }, "id=?", args(linkId))
                            val i = entries.indexOfFirst { it.id == linkId }
                            entries[i] = entries[i].copy(kind = n.kind)
                            changed = true
                        }
                    }
                    d.execSQL(
                        StoreSql.VAULT_UPSERT,
                        arrayOf<Any>(u.docId, u.name, u.modified, n.title, n.kind, VaultNotes.packAliases(n.aliases), linkId),
                    )
                    cache[u.docId] = VaultCacheRow(u.docId, u.name, u.modified, n.kind, linkId, n.title)
                }
            }
        }
        removed.chunked(VAULT_CHUNK).forEach { chunk ->
            tx(ctx) { d -> chunk.forEach { d.execSQL(StoreSql.VAULT_DELETE, arrayOf<Any>(it)) } }
        }

        if (rebindAliases(ctx, entries, scanMade.keys)) changed = true
        if (changed) markWrittenDirty(ctx)
        return VaultMerge(created, matched, removed.size)
    }

    /** Re-binds links to the notes that have them as an alias. True when the bindings changed. */
    private fun rebindAliases(ctx: Context, entries: List<LinkEntry>, scanMade: Set<Long>): Boolean {
        val notes = ArrayList<BindNote>()
        val titles = HashMap<String, String>()
        db(ctx).rawQuery(StoreSql.VAULT_NOTES, null).use { c ->
            while (c.moveToNext()) {
                notes.add(BindNote(c.getString(0), c.getString(1), VaultNotes.unpackAliases(c.getString(2))))
                titles[c.getString(0)] = c.getString(1)
            }
        }
        val want = LinkBinding.aliasBindings(entries.map { BindLink(it.id, it.title, it.archived, it.id in scanMade) }, notes)
            .mapValues { (_, doc) -> doc to titles.getValue(doc) }
        val now = HashMap<Long, Pair<String, String>>()
        db(ctx).rawQuery(StoreSql.LINK_NOTES, null).use { c ->
            while (c.moveToNext()) now[c.getLong(0)] = c.getString(1) to c.getString(2)
        }
        if (want == now) return false
        tx(ctx) { d ->
            d.execSQL(StoreSql.LINK_NOTES_CLEAR)
            want.forEach { (linkId, b) -> d.execSQL(StoreSql.LINK_NOTE_SET, arrayOf<Any>(linkId, b.first, b.second)) }
        }
        return true
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

    // --- Obsidian vault ---

    /** The folder you picked (a document tree URI), the vault's root folder inside it, and its name. */
    fun vaultTree(ctx: Context): String? = p(ctx).getString("vault_tree", null)
    fun vaultRoot(ctx: Context): String? = p(ctx).getString("vault_root", null)
    fun vaultName(ctx: Context): String = p(ctx).getString("vault_name", "") ?: ""

    fun setVault(ctx: Context, tree: String?, root: String?, name: String) = p(ctx).edit()
        .putString("vault_tree", tree).putString("vault_root", root).putString("vault_name", name).apply()

    fun vaultLastExport(ctx: Context): Long = p(ctx).getLong("vault_last_export", 0L)
    fun setVaultLastExport(ctx: Context, v: Long) = p(ctx).edit().putLong("vault_last_export", v).apply()

    /** What went wrong the last time, or blank. */
    fun vaultError(ctx: Context): String = p(ctx).getString("vault_error", "") ?: ""
    fun setVaultError(ctx: Context, v: String) = p(ctx).edit().putString("vault_error", v).apply()

    /** What went wrong the last time the vault was scanned, or blank. */
    fun vaultScanError(ctx: Context): String = p(ctx).getString("vault_scan_error", "") ?: ""
    fun setVaultScanError(ctx: Context, v: String) = p(ctx).edit().putString("vault_scan_error", v).apply()

    fun vaultLastScan(ctx: Context): Long = p(ctx).getLong("vault_last_scan", 0L)
    fun vaultScanText(ctx: Context): String = p(ctx).getString("vault_scan_text", "") ?: ""
    fun setVaultScan(ctx: Context, at: Long, text: String) = p(ctx).edit()
        .putLong("vault_last_scan", at).putString("vault_scan_text", text).apply()

    /** Days made only of Daylio-imported check-ins are written too (off by default). */
    fun exportImported(ctx: Context): Boolean = p(ctx).getBoolean("export_imported", false)
    fun setExportImported(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("export_imported", on).apply()

    // --- Calendar ---

    /** Writing the log into the calendar: off until you turn it on. */
    fun calOn(ctx: Context): Boolean = p(ctx).getBoolean("cal_on", false)
    fun setCalOn(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("cal_on", on).apply()

    /** The log calendar (with one per area, the one for anything that has no area of its own), with its identity. */
    fun calLog(ctx: Context): CalRef? = CalRef.decode(p(ctx).getString("cal_log_ref", null))
    fun setCalLog(ctx: Context, ref: CalRef?) = p(ctx).edit().putString("cal_log_ref", ref?.encode()).apply()

    /** One log calendar per area instead of one for everything. */
    fun calPerArea(ctx: Context): Boolean = p(ctx).getBoolean("cal_per_area", false)
    fun setCalPerArea(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("cal_per_area", on).apply()

    /** With one log calendar per area: area identity key to its log calendar. */
    fun calAreaLogs(ctx: Context): Map<String, CalRef> = CalendarSetup.decodeAreaLogs(p(ctx).getString("cal_area_logs_v2", ""))
    fun setCalAreaLogs(ctx: Context, m: Map<String, CalRef>) =
        p(ctx).edit().putString("cal_area_logs_v2", CalendarSetup.encodeAreaLogs(m)).apply()

    /** Color keys an account's calendar refused, as "account key<RS>color key": never tried again. */
    fun calRefusedColors(ctx: Context): Set<String> = p(ctx).getStringSet("cal_refused_colors", emptySet())?.toSet() ?: emptySet()
    fun addCalRefusedColor(ctx: Context, accountKey: String, colorKey: String) {
        val next = calRefusedColors(ctx) + (accountKey + CalRef.SEP_PAIR + colorKey)
        p(ctx).edit().putStringSet("cal_refused_colors", next).apply()
    }

    /** Planned events move their activity to the first button. */
    fun calNudge(ctx: Context): Boolean = p(ctx).getBoolean("cal_nudge", true)
    fun setCalNudge(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("cal_nudge", on).apply()

    fun calRules(ctx: Context): List<KeywordRule> = PlannedNudge.decode(p(ctx).getString("cal_rules", ""))
    fun setCalRules(ctx: Context, rules: List<KeywordRule>) = p(ctx).edit().putString("cal_rules", PlannedNudge.encode(rules)).apply()

    fun calError(ctx: Context): String = p(ctx).getString("cal_error", "") ?: ""
    fun setCalError(ctx: Context, v: String) = p(ctx).edit().putString("cal_error", v).apply()

    fun calLastExport(ctx: Context): Long = p(ctx).getLong("cal_last_export", 0L)
    fun setCalLastExport(ctx: Context, v: Long) = p(ctx).edit().putLong("cal_last_export", v).apply()

    /** The outcome of the last "Remove log events from …", in words. */
    fun calInfo(ctx: Context): String = p(ctx).getString("cal_info", "") ?: ""
    fun setCalInfo(ctx: Context, v: String) = p(ctx).edit().putString("cal_info", v).apply()

    /** What the last "Test calendar" found, in words. */
    fun calTest(ctx: Context): String = p(ctx).getString("cal_test", "") ?: ""
    fun setCalTest(ctx: Context, v: String) = p(ctx).edit().putString("cal_test", v).apply()

    /** Calendar access was asked for at least once (a denial after that needs App info). */
    fun calAsked(ctx: Context): Boolean = p(ctx).getBoolean("cal_asked", false)
    fun setCalAsked(ctx: Context) = p(ctx).edit().putBoolean("cal_asked", true).apply()

    // --- Toggl ---

    /** The API token, in this app's private storage (the app has allowBackup off). Never shown whole, never logged. */
    fun togglToken(ctx: Context): String? = p(ctx).getString("toggl_token", null)?.takeIf { it.isNotBlank() }
    fun setTogglToken(ctx: Context, v: String?) = p(ctx).edit().putString("toggl_token", v).apply()

    /** The workspace entries go to (0: none chosen), and its name. */
    fun togglWorkspace(ctx: Context): Long = p(ctx).getLong("toggl_ws", 0L)
    fun togglWorkspaceName(ctx: Context): String = p(ctx).getString("toggl_ws_name", "") ?: ""
    fun setTogglWorkspace(ctx: Context, id: Long, name: String) = p(ctx).edit().putLong("toggl_ws", id).putString("toggl_ws_name", name).apply()

    /** Sending to Toggl: off until you turn it on. */
    fun togglOn(ctx: Context): Boolean = p(ctx).getBoolean("toggl_on", false)
    fun setTogglOn(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("toggl_on", on).apply()

    /** Moods and feelings in descriptions: off by default. Notes: on by default. */
    fun togglCheckIns(ctx: Context): Boolean = p(ctx).getBoolean("toggl_checkins", false)
    fun setTogglCheckIns(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("toggl_checkins", on).apply()
    fun togglNotes(ctx: Context): Boolean = p(ctx).getBoolean("toggl_notes", true)
    fun setTogglNotes(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("toggl_notes", on).apply()

    /** The first diary day to send (its start, ms); 0 sends everything; -1 is not chosen yet (the day it is turned on). */
    fun togglFrom(ctx: Context): Long = p(ctx).getLong("toggl_from", -1L)
    fun setTogglFrom(ctx: Context, v: Long) = p(ctx).edit().putLong("toggl_from", v).apply()

    fun togglLastSync(ctx: Context): Long = p(ctx).getLong("toggl_last", 0L)
    fun setTogglLastSync(ctx: Context, v: Long) = p(ctx).edit().putLong("toggl_last", v).apply()

    /** What went wrong the last time, or blank. */
    fun togglError(ctx: Context): String = p(ctx).getString("toggl_error", "") ?: ""
    fun setTogglError(ctx: Context, v: String) = p(ctx).edit().putString("toggl_error", v).apply()

    /** Notices from the last run (tags Toggl refused, a plan without projects), or blank. */
    fun togglInfo(ctx: Context): String = p(ctx).getString("toggl_info", "") ?: ""
    fun setTogglInfo(ctx: Context, v: String) = p(ctx).edit().putString("toggl_info", v).apply()

    /** Not before this time (ms) is the next attempt; 0 for none. Kept so a restart doesn't forget a back-off. */
    fun togglNextAt(ctx: Context): Long = p(ctx).getLong("toggl_next", 0L)
    fun setTogglNextAt(ctx: Context, v: Long) = p(ctx).edit().putLong("toggl_next", v).apply()

    /** Failed runs in a row (the quota doesn't count), for the back-off. */
    fun togglFailures(ctx: Context): Int = p(ctx).getInt("toggl_failures", 0)
    fun setTogglFailures(ctx: Context, v: Int) = p(ctx).edit().putInt("toggl_failures", v).apply()

    /** A problem waiting won't fix (token refused, plan): nothing is retried until you ask. Blank when none. */
    fun togglFatal(ctx: Context): String = p(ctx).getString("toggl_fatal", "") ?: ""
    fun setTogglFatal(ctx: Context, v: String) = p(ctx).edit().putString("toggl_fatal", v).apply()

    /** The request count and Toggl's quota words, as [com.jono.nowwidget.core.RequestBudget] writes them. */
    fun togglBudget(ctx: Context): String? = p(ctx).getString("toggl_budget", null)
    fun setTogglBudget(ctx: Context, v: String) = p(ctx).edit().putString("toggl_budget", v).apply()

    /** "Remove sent entries" is under way (it carries on within the quota), and how many it has removed so far. */
    fun togglRemoving(ctx: Context): Boolean = p(ctx).getBoolean("toggl_removing", false)
    fun setTogglRemoving(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("toggl_removing", v).apply()
    fun togglRemoved(ctx: Context): Int = p(ctx).getInt("toggl_removed", 0)
    fun setTogglRemoved(ctx: Context, v: Int) = p(ctx).edit().putInt("toggl_removed", v).apply()

    /** Projects known in a workspace, lowercase name to id (one `workspace<TAB>id<TAB>name` line each). */
    fun togglProjects(ctx: Context, ws: Long): Map<String, Long> {
        val out = HashMap<String, Long>()
        (p(ctx).getString("toggl_projects", "") ?: "").lines().forEach { line ->
            val f = line.split('\t')
            if (f.size == 3 && f[0] == ws.toString()) f[1].toLongOrNull()?.let { out[f[2]] = it }
        }
        return out
    }

    fun addTogglProjects(ctx: Context, ws: Long, add: Map<String, Long>) {
        val keep = (p(ctx).getString("toggl_projects", "") ?: "").lines().filter { l ->
            val f = l.split('\t')
            f.size == 3 && !(f[0] == ws.toString() && f[2] in add)
        }
        val lines = keep + add.map { (name, id) -> "$ws\t$id\t" + name.replace('\t', ' ').replace('\n', ' ') }
        p(ctx).edit().putString("toggl_projects", lines.joinToString("\n")).apply()
    }

    /** The workspace's projects were listed once, so a missing name is created, not searched for again. */
    fun togglProjectsListed(ctx: Context, ws: Long): Boolean = ws.toString() in (p(ctx).getStringSet("toggl_listed", emptySet()) ?: emptySet())
    fun setTogglProjectsListed(ctx: Context, ws: Long) =
        p(ctx).edit().putStringSet("toggl_listed", (p(ctx).getStringSet("toggl_listed", emptySet()) ?: emptySet()) + ws.toString()).apply()

    /** Toggl wouldn't make projects on this workspace's plan: entries go without one. */
    fun togglNoProjects(ctx: Context, ws: Long): Boolean = ws.toString() in (p(ctx).getStringSet("toggl_noproj", emptySet()) ?: emptySet())
    fun setTogglNoProjects(ctx: Context, ws: Long) =
        p(ctx).edit().putStringSet("toggl_noproj", (p(ctx).getStringSet("toggl_noproj", emptySet()) ?: emptySet()) + ws.toString()).apply()
}
