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
import com.jono.nowwidget.core.CheckInFlow
import com.jono.nowwidget.core.Daylio
import com.jono.nowwidget.core.DaylioData
import com.jono.nowwidget.core.DiaryDay
import com.jono.nowwidget.core.Entry
import com.jono.nowwidget.core.FeelingTag
import com.jono.nowwidget.core.FlowSettings
import com.jono.nowwidget.core.FreeWrite
import com.jono.nowwidget.core.IDLE
import com.jono.nowwidget.core.KeywordRule
import com.jono.nowwidget.core.PlannedNudge
import com.jono.nowwidget.core.DirtyDays
import com.jono.nowwidget.core.Link
import com.jono.nowwidget.core.LinkBinding
import com.jono.nowwidget.core.LinkEntry
import com.jono.nowwidget.core.LinkIndex
import com.jono.nowwidget.core.LinkKind
import com.jono.nowwidget.core.LinkMerge
import com.jono.nowwidget.core.LogRules
import com.jono.nowwidget.core.Moment
import com.jono.nowwidget.core.MomentKind
import com.jono.nowwidget.core.Mentions
import com.jono.nowwidget.core.Mood
import com.jono.nowwidget.core.ObsidianDay
import com.jono.nowwidget.core.PALETTE
import com.jono.nowwidget.core.Recap
import com.jono.nowwidget.core.RecapSettings
import com.jono.nowwidget.core.Schema
import com.jono.nowwidget.core.StartShift
import com.jono.nowwidget.core.StoreSql
import com.jono.nowwidget.core.Span
import com.jono.nowwidget.core.TagTarget
import com.jono.nowwidget.core.Together
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
        if (oldVersion < 4) Schema.upgradeFrom3().forEach { db.execSQL(it) }
        if (oldVersion < 5) Schema.upgradeFrom4().forEach { db.execSQL(it) }
        if (oldVersion < 6) Schema.upgradeFrom5().forEach { db.execSQL(it) }
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
/** [movedFrom]: the document id the same note had before it moved to another folder. */
class VaultUpsert(val docId: String, val name: String, val modified: Long, val note: VaultNote, val movedFrom: String? = null)

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
    private const val TOGGL_OLD = "toggl-old"
    private const val TOGGL_OLD_RELEASED = "toggl-old-released"

    /** The Now hub's records (export_state) and dirty days (export_day). */
    const val HUB = "hub"

    /** Every export target a change marks dirty: one place marks them all. */
    private val TARGETS = listOf(OBSIDIAN, CALENDAR, TOGGL, HUB)

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
        }, linkRanks(ctx))
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

    private const val ENTRY_COLS = "id, activity_id, start_ms, context_id, title"

    private fun readEntry(c: Cursor) = Entry(
        c.getLong(0), c.getLong(1), c.getLong(2), contextId = c.longOrNull(3),
        title = if (c.isNull(4)) null else FreeWrite.clean(c.getString(4)),
    )

    private fun latest(ctx: Context): Entry? {
        val e = db(ctx).rawQuery(
            "SELECT $ENTRY_COLS FROM entry ORDER BY start_ms DESC, id DESC LIMIT 1",
            null,
        ).use { c -> if (c.moveToFirst()) readEntry(c) else null } ?: return null
        return e.copy(links = linksFor(ctx, listOf(e.id))[e.id] ?: emptySet(), extras = extrasFor(ctx, listOf(e.id))[e.id] ?: emptySet())
    }

    private fun before(ctx: Context, e: Entry): Entry? =
        db(ctx).rawQuery(
            "SELECT $ENTRY_COLS FROM entry " +
                "WHERE (start_ms < ? OR (start_ms = ? AND id < ?)) ORDER BY start_ms DESC, id DESC LIMIT 1",
            args(e.start, e.start, e.id),
        ).use { c -> if (c.moveToFirst()) readEntry(c) else null }

    private fun insert(ctx: Context, activityId: Long, start: Long, contextId: Long? = null, title: String? = null): Long {
        val t = FreeWrite.clean(title)
        val id = db(ctx).insertOrThrow("entry", null, ContentValues().apply {
            put("activity_id", activityId)
            put("start_ms", start)
            if (contextId != null) put("context_id", contextId) else putNull("context_id")
            if (t != null) put("title", t) else putNull("title")
        })
        dirtyAround(ctx, start)
        return id
    }

    /**
     * The one thing the buttons do. The rules are in [LogRules.tap]. Returns the new entry's id
     * when the tap switched to a different activity (that starts the check-in flow); null for
     * turning off, undoing and the other taps.
     */
    fun tap(ctx: Context, activityId: Long): Long? {
        val now = System.currentTimeMillis()
        if (activityId == lastTapAct && now - lastTapAt < DOUBLE_TAP_MS) return null
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
            // What the check-in flow put on it by itself isn't something you added.
            inheritedLinks = cur?.let { inheritedLinks(ctx, it) } ?: emptySet(),
        )
        return when (action) {
            is LogRules.Tap.Start -> {
                val id = insert(ctx, action.activityId, action.at, LogRules.contextForNew(action.activityId, lastContext(ctx)))
                id.takeIf { action.activityId != IDLE }
            }
            is LogRules.Tap.MoveBack -> {
                if (cur != null) moveBack(ctx, cur, action.byMs)
                null
            }
            is LogRules.Tap.Undo -> {
                if (cur != null) deleteEntry(ctx, cur.id)
                null
            }
            is LogRules.Tap.Replace -> if (cur == null) null else {
                // One transaction: the mistake goes, and its replacement keeps its people,
                // place and context.
                tx(ctx) {
                    deleteEntry(ctx, cur.id)
                    val at = action.startAt
                    if (at != null) {
                        val id = insert(ctx, activityId, at, cur.contextId)
                        writeLinks(ctx, id, cur.links)
                        // Its extras stay with it too (the new primary isn't one of them).
                        writeExtras(ctx, id, cur.extras - activityId)
                        id.takeIf { activityId != IDLE }
                    } else null
                }
            }
        }
    }

    // --- Free-written activities (the rules are in core/FreeWrite.kt) ---

    /** Every free-written title, newest first, with its category. */
    fun titleUses(ctx: Context): List<FreeWrite.Used> {
        val out = ArrayList<FreeWrite.Used>()
        db(ctx).rawQuery(StoreSql.TITLES_RECENT, null).use { c ->
            while (c.moveToNext()) out.add(FreeWrite.Used(c.getString(0), c.getLong(1), c.getLong(2)))
        }
        return out
    }

    /** The built-in "✏️ Other": an activity called Other if there is one, else a new one off the buttons. */
    fun otherActivity(ctx: Context): Long {
        allActs(ctx).firstOrNull { !it.archived && it.name.trim().equals(FreeWrite.OTHER_NAME, ignoreCase = true) }?.let { return it.id }
        return saveAct(ctx, null, FreeWrite.OTHER_NAME, FreeWrite.OTHER_EMOJI, PALETTE[PALETTE.size - 1], "", show = false)
    }

    /**
     * The quick box's Go: switch to what he typed. Just an activity's name is that activity's
     * button. Anything else starts a new entry with the words as its title and a category chosen
     * by [FreeWrite.category]. Returns the new entry's id (that starts the check-in flow), or null
     * when nothing changed (it's what's on already).
     */
    fun startTitled(ctx: Context, text: String): Long? {
        val t = FreeWrite.clean(text) ?: return null
        val acts = allActs(ctx)
        val cur = latest(ctx)?.takeIf { it.activityId != IDLE }
        FreeWrite.exactActivity(t, acts)?.let { a ->
            if (cur != null && cur.activityId == a.id && cur.title == null) return null
            return tap(ctx, a.id)
        }
        if (cur != null && FreeWrite.same(cur.title, t)) return null
        val uses = titleUses(ctx)
        val cat = FreeWrite.category(t, acts, FreeWrite.lastActivityFor(t, uses), Prefs.calRules(ctx), NO_OTHER)
            .let { if (it == NO_OTHER) otherActivity(ctx) else it }
        lastTapAct = -1L
        return insert(ctx, cat, System.currentTimeMillis(), LogRules.contextForNew(cat, lastContext(ctx)), t)
    }

    private const val NO_OTHER = -1L

    /**
     * What free-written [text] would be, without starting anything (the recap's ＋): an activity's
     * name is that activity with no title; anything else is the words as a title, in the category
     * [startTitled] would pick. Null for blank text.
     */
    fun freeWriteCategory(ctx: Context, text: String): Pair<Long, String?>? {
        val t = FreeWrite.clean(text) ?: return null
        val acts = allActs(ctx)
        FreeWrite.exactActivity(t, acts)?.let { return it.id to null }
        val cat = FreeWrite.category(t, acts, FreeWrite.lastActivityFor(t, titleUses(ctx)), Prefs.calRules(ctx), NO_OTHER)
            .let { if (it == NO_OTHER) otherActivity(ctx) else it }
        return cat to t
    }

    /** The ✏️ Other activity if there is one (never makes it, unlike [otherActivity]). */
    fun otherActivityIfAny(ctx: Context): Long? =
        allActs(ctx).firstOrNull { !it.archived && it.name.trim().equals(FreeWrite.OTHER_NAME, ignoreCase = true) }?.id

    /** How many entries have this title (any case). */
    fun titleCount(ctx: Context, title: String): Int = titleUses(ctx).count { FreeWrite.same(it.title, title) }

    /**
     * "Make this an activity": a new activity named [title] in [like]'s group, with its emoji and
     * color, on the buttons; every entry with that title becomes a plain entry of it. Returns its id.
     */
    fun makeActivity(ctx: Context, title: String, like: Act?): Long {
        val t = FreeWrite.clean(title) ?: return like?.id ?: otherActivity(ctx)
        val starts = ArrayList<Long>()
        val id = tx(ctx) { d ->
            val newId = saveAct(ctx, null, t, like?.emoji ?: "", like?.color ?: PALETTE[0], like?.group ?: "", show = true)
            val ids = ArrayList<Long>()
            d.rawQuery("SELECT id, start_ms, title FROM entry WHERE title IS NOT NULL", null).use { c ->
                while (c.moveToNext()) {
                    if (FreeWrite.same(c.getString(2), t)) {
                        ids.add(c.getLong(0))
                        starts.add(c.getLong(1))
                    }
                }
            }
            ids.forEach { d.execSQL(StoreSql.ENTRY_TO_ACTIVITY, arrayOf<Any>(newId, it)) }
            newId
        }
        starts.distinctBy { DiaryDay.start(it) }.forEach { dirtyAround(ctx, it) }
        return id
    }

    // --- Ranking people and places by his daily notes (the rules are in core/Mentions.kt) ---

    /** The dated notes read so far: doc id to (name, modified). */
    fun dayNoteCache(ctx: Context): Map<String, Pair<String, Long>> {
        val out = HashMap<String, Pair<String, Long>>()
        db(ctx).rawQuery(StoreSql.DAY_NOTES, null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1) to c.getLong(2)
        }
        return out
    }

    fun dayNoteCount(ctx: Context): Int =
        db(ctx).rawQuery(StoreSql.DAY_NOTE_COUNT, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** One dated note as read: its file, its diary day and what it mentions. */
    class DayNoteRead(val docId: String, val name: String, val modified: Long, val day: Long, val mentions: Map<String, Mentions.NoteMention>)

    /** Replaces the mentions of the notes [read] and drops those of the notes [gone], in chunks. */
    fun applyDayNotes(ctx: Context, read: List<DayNoteRead>, gone: Collection<String>) {
        read.chunked(VAULT_CHUNK).forEach { chunk ->
            tx(ctx) { d ->
                chunk.forEach { n ->
                    d.execSQL(StoreSql.MENTIONS_OF_NOTE_CLEAR, arrayOf<Any>(n.docId))
                    n.mentions.forEach { (key, m) ->
                        d.execSQL(StoreSql.MENTION_ADD, arrayOf<Any>(n.docId, key, m.spelled, n.day, if (m.at) 1 else 0, if (m.with) 1 else 0))
                    }
                    d.execSQL(StoreSql.DAY_NOTE_UPSERT, arrayOf<Any>(n.docId, n.name, n.modified, n.day))
                }
            }
        }
        gone.chunked(VAULT_CHUNK).forEach { chunk ->
            tx(ctx) { d ->
                chunk.forEach {
                    d.execSQL(StoreSql.MENTIONS_OF_NOTE_CLEAR, arrayOf<Any>(it))
                    d.execSQL(StoreSql.DAY_NOTE_DELETE, arrayOf<Any>(it))
                }
            }
        }
    }

    /** What re-ranking did. */
    class RankResult(val ranked: Int, val created: Int, val kinded: Int)

    /**
     * Works the mention score of every link out again (it decays with time, so at each scan):
     * targets that are links (by title or alias, any case) get theirs, plus [Mentions.OPEN_BONUS]
     * when the note was opened lately ([lastOpen]); unresolved ones that clearly are people or
     * places become links marked as made by the mentions (no note: they stay plain `[[Name]]`); a
     * scan-made link of an untagged note gets its kind from the mentions, once.
     */
    fun applyRanking(ctx: Context, now: Long, lastOpen: Set<String>): RankResult {
        val d = db(ctx)
        val rows = ArrayList<Mentions.Row>()
        d.rawQuery(StoreSql.MENTIONS_ALL, null).use { c ->
            while (c.moveToNext()) rows.add(Mentions.Row(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4) != 0, c.getInt(5) != 0))
        }
        val targets = Mentions.targets(rows, now)
        val links = allLinks(ctx)
        val sources = HashMap<Long, String>()
        d.rawQuery(StoreSql.LINK_SOURCES, null).use { c -> while (c.moveToNext()) sources[c.getLong(0)] = c.getString(1) }
        val noteKind = HashMap<Long, String>()
        vaultCache(ctx).values.forEach { r -> r.linkId?.let { noteKind[it] = r.kind } }
        val aliases = vaultAliases(ctx)
        val index = LinkIndex(links.map { LinkEntry(it.id, it.title, it.kind, aliases[it.id].orEmpty(), it.uses, it.archived, it.pinned) })
        val allKinds = setOf(LinkKind.PERSON, LinkKind.PLACE, LinkKind.CONTEXT, LinkKind.OTHER)
        val score = HashMap<Long, Double>()
        var created = 0
        var kinded = 0
        var pos = nextPos(ctx, "link")
        tx(ctx) { tdb ->
            targets.values.forEach { t ->
                val bonus = if (t.key in lastOpen) Mentions.OPEN_BONUS else 0.0
                val link = index.resolve(t.title, allKinds)
                if (link != null) {
                    score[link.id] = (score[link.id] ?: 0.0) + t.score + bonus
                    // 21H: a note, but untagged. Its mentions say place.
                    if (link.kind == LinkKind.OTHER && sources[link.id] == "vault" && noteKind[link.id] != LinkKind.PERSON &&
                        noteKind[link.id] != LinkKind.PLACE) {
                        Mentions.promote(t)?.let { k ->
                            tdb.update("link", ContentValues().apply { put("kind", k) }, "id=?", args(link.id))
                            tdb.execSQL(StoreSql.LINK_KIND_FROM_MENTIONS, arrayOf<Any>(link.id))
                            kinded++
                        }
                    }
                    return@forEach
                }
                val kind = Mentions.promote(t) ?: return@forEach
                val id = tdb.insertOrThrow("link", null, ContentValues().apply {
                    put("title", t.title)
                    put("kind", kind)
                    put("emoji", "")
                    put("pos", pos++)
                    put("pinned", 0)
                    put("archived", 0)
                })
                tdb.execSQL(StoreSql.LINK_SOURCE_MENTIONS, arrayOf<Any>(id))
                score[id] = t.score + bonus
                created++
            }
            // A recently opened note that no daily note mentions still gets its bonus.
            lastOpen.filter { it !in targets }.forEach { k ->
                index.resolve(k, allKinds)?.let { score[it.id] = (score[it.id] ?: 0.0) + Mentions.OPEN_BONUS }
            }
            tdb.execSQL(StoreSql.VAULT_RANK_CLEAR)
            score.forEach { (id, v) -> tdb.execSQL(StoreSql.VAULT_RANK_SET, arrayOf<Any>(id, v)) }
        }
        if (kinded > 0) markWrittenDirty(ctx)
        return RankResult(score.size, created, kinded)
    }

    /** How many vault notes each link follows; above 1 is "2 notes named 21H". */
    fun notesPerLink(ctx: Context): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        db(ctx).rawQuery(StoreSql.NOTES_PER_LINK, null).use { c -> while (c.moveToNext()) out[c.getLong(0)] = c.getInt(1) }
        return out
    }

    /**
     * People and places the mentions pass made with no note, that now have one (the vault is read
     * in all its folders): relabelled, or merged into the note's link ([LinkMerge.plan]). Every
     * entry keeps its people, places and context. Returns how many were merged.
     */
    fun mergeMentionLinks(ctx: Context): Int {
        val links = allLinks(ctx)
        val sources = linkSources(ctx)
        if (sources.values.none { it == "mentions" }) return 0
        val noted = HashSet<Long>()
        vaultCache(ctx).values.forEach { r -> r.linkId?.let { noted.add(it) } }
        noted.addAll(boundNotes(ctx).keys)
        val aliases = vaultAliases(ctx)
        val steps = LinkMerge.plan(links.map {
            LinkMerge.Cand(it.id, it.title, it.kind, it.uses, sources[it.id] == "mentions", it.id in noted, aliases[it.id].orEmpty(), it.archived)
        })
        var merged = 0
        tx(ctx) { d ->
            steps.forEach { st ->
                when (st) {
                    is LinkMerge.Step.Relabel -> d.execSQL(StoreSql.LINK_SOURCE_TO_VAULT, arrayOf<Any>(st.id))
                    is LinkMerge.Step.Merge -> {
                        mergeLinkRows(d, st.keep, st.drop)
                        // The kept one is a note's link now, whichever it was.
                        d.execSQL(StoreSql.LINK_SOURCE_TO_VAULT, arrayOf<Any>(st.keep))
                        merged++
                    }
                }
            }
        }
        if (merged > 0) markWrittenDirty(ctx)
        return merged
    }

    /**
     * A scan-made link of an untagged note (21H at the root) that also has a tagged note of the same
     * name (`_jonoco/Fam/21H.md`) takes the tagged one's kind: the typed note is the one it follows.
     */
    fun adoptNoteKinds(ctx: Context): Int {
        val sources = linkSources(ctx)
        val byLink = HashMap<Long, MutableSet<String>>()
        vaultCache(ctx).values.forEach { r -> r.linkId?.let { byLink.getOrPut(it) { HashSet() }.add(r.kind) } }
        var n = 0
        tx(ctx) { d ->
            allLinks(ctx).filter { it.kind == LinkKind.OTHER && sources[it.id] == "vault" }.forEach { l ->
                val kinds = byLink[l.id].orEmpty() - LinkKind.OTHER
                val k = listOf(LinkKind.PERSON, LinkKind.PLACE, LinkKind.CONTEXT).firstOrNull { it in kinds } ?: return@forEach
                d.update("link", ContentValues().apply { put("kind", k) }, "id=?", args(l.id))
                n++
            }
        }
        if (n > 0) markWrittenDirty(ctx)
        return n
    }

    /** Moves every use of [drop] to [keep] and removes [drop]. */
    private fun mergeLinkRows(d: SQLiteDatabase, keep: Long, drop: Long) {
        val kd = arrayOf<Any>(keep, drop)
        val dd = arrayOf<Any>(drop)
        d.execSQL(StoreSql.MERGE_ENTRY_LINKS, kd)
        d.execSQL(StoreSql.MERGE_ENTRY_LINKS_LEFT, dd)
        d.execSQL(StoreSql.MERGE_CONTEXTS, kd)
        d.execSQL(StoreSql.MERGE_MOMENT_REFS, kd)
        d.execSQL(StoreSql.MERGE_MOMENT_REFS_LEFT, dd)
        d.execSQL(StoreSql.MERGE_VAULT_NOTES, kd)
        d.execSQL(StoreSql.MERGE_LINK_NOTE, kd)
        d.execSQL(StoreSql.MERGE_LINK_NOTE_LEFT, dd)
        d.execSQL(StoreSql.MERGE_SOURCE_LEFT, dd)
        d.execSQL(StoreSql.MERGE_RANK_LEFT, dd)
        d.execSQL(StoreSql.MERGE_LINK_DELETE, dd)
    }

    /** Where each link came from: "vault", "vault-kind", "mentions"; links you made aren't in it. */
    fun linkSources(ctx: Context): Map<Long, String> {
        val out = HashMap<Long, String>()
        db(ctx).rawQuery(StoreSql.LINK_SOURCES, null).use { c -> while (c.moveToNext()) out[c.getLong(0)] = c.getString(1) }
        return out
    }

    /** The vault's mention score per link (empty until the first scan with mentions). */
    fun vaultRanks(ctx: Context): Map<Long, Double> {
        val out = HashMap<Long, Double>()
        db(ctx).rawQuery(StoreSql.VAULT_RANKS, null).use { c -> while (c.moveToNext()) out[c.getLong(0)] = c.getDouble(1) }
        return out
    }

    /**
     * The one ranking for every people and places list: Now use (recency-weighted, ×3) plus the
     * vault's mentions. [except]: an entry whose links don't count (the check-in flow's own).
     */
    fun linkRanks(ctx: Context, now: Long = System.currentTimeMillis(), except: Long = -1L): Map<Long, Double> =
        Mentions.combined(CheckInFlow.scores(linkUses(ctx, now - CheckInFlow.HISTORY_MS, except), now), vaultRanks(ctx))

    /** [items] best first by [linkRanks], then by name. */
    fun rankLinks(ctx: Context, items: List<Link>, ranks: Map<Long, Double> = linkRanks(ctx)): List<Link> =
        Mentions.ranked(items, ranks, { it.id }, { it.title })

    // --- The check-in flow's reads (the rules are in core/CheckInFlow.kt) ---

    /** The place of the latest entry before [e] that has one. */
    fun lastPlaceBefore(ctx: Context, e: Entry): Long? =
        contextQuery(ctx, StoreSql.LAST_PLACE_BEFORE, args(e.start, e.start, e.id))

    /** The people on the last non-idle entry before [e], in your order of links. */
    fun previousPeople(ctx: Context, e: Entry): List<Long> {
        val prev = contextQuery(ctx, StoreSql.PREV_NON_IDLE, args(e.start, e.start, e.id)) ?: return emptyList()
        val people = allLinks(ctx).filter { it.kind == LinkKind.PERSON }
        val on = linksFor(ctx, listOf(prev))[prev] ?: emptySet()
        return people.filter { it.id in on }.map { it.id }
    }

    /** The place and people the flow would carry over to [e] by itself. */
    private fun inheritedLinks(ctx: Context, e: Entry): Set<Long> {
        if (e.links.isEmpty()) return emptySet()
        return (listOfNotNull(lastPlaceBefore(ctx, e)) + previousPeople(ctx, e)).toSet()
    }

    /** Link uses since [since], leaving out entry [except] (the one the flow is about). */
    fun linkUses(ctx: Context, since: Long, except: Long): List<CheckInFlow.Use> {
        val out = ArrayList<CheckInFlow.Use>()
        db(ctx).rawQuery(StoreSql.LINK_USES_SINCE, args(since, except)).use { c ->
            while (c.moveToNext()) out.add(CheckInFlow.Use(c.getLong(0), c.getLong(1)))
        }
        return out
    }

    private fun contextQuery(ctx: Context, sql: String, a: Array<String>?): Long? =
        db(ctx).rawQuery(sql, a).use { c -> if (c.moveToFirst()) c.longOrNull(0) else null }

    /** The context a new entry picks up: that of the last non-idle entry. */
    private fun lastContext(ctx: Context): Long? = contextQuery(ctx, StoreSql.LAST_CONTEXT, null)

    /** The context of the last non-idle entry before [e] (what a new entry there would pick up). */
    fun contextBeforeEntry(ctx: Context, e: Entry): Long? = contextBefore(ctx, e)

    private fun contextBefore(ctx: Context, e: Entry): Long? =
        contextQuery(ctx, StoreSql.CONTEXT_BEFORE, args(e.start, e.start, e.id))

    private fun moveBack(ctx: Context, cur: Entry, byMs: Long) {
        val prev = before(ctx, cur)
        val floor = if (prev != null) prev.start + 60_000L else 0L
        val start = maxOf(cur.start - byMs, floor)
        if (start < cur.start) setStart(ctx, cur.id, start)
    }

    // --- Moving the current start (the rules are in core/StartShift.kt) ---

    /** The current entry and the one before it (idle counts), or null when nothing is on. */
    fun currentAndBefore(ctx: Context): Pair<Entry, Entry?>? {
        val cur = current(ctx) ?: return null
        return cur to before(ctx, cur)
    }

    /** What moving the current start by [minutes] would do (nothing is changed). */
    fun previewShift(ctx: Context, minutes: Int, now: Long = System.currentTimeMillis()): StartShift.Shift? =
        currentAndBefore(ctx)?.let { (cur, prev) -> StartShift.by(cur, prev, minutes, now) }

    /** What moving the current start to [target] would do (nothing is changed). */
    fun previewShiftTo(ctx: Context, target: Long, now: Long = System.currentTimeMillis()): StartShift.Shift? =
        currentAndBefore(ctx)?.let { (cur, prev) -> StartShift.to(cur, prev, target, now) }

    /**
     * Applies [s] if the current entry is still the one it was worked out for, with the same start.
     * The entry before is never touched (its end is this start); moments keep their times. Every
     * diary day between the old and new start is marked dirty for all exports. Returns whether it applied.
     */
    fun applyShift(ctx: Context, s: StartShift.Shift): Boolean {
        val cur = current(ctx) ?: return false
        if (cur.id != s.entryId || cur.start != s.oldStart || !s.changed) return false
        setStart(ctx, cur.id, s.newStart)
        val d = db(ctx)
        StartShift.daysTouched(s.oldStart, s.newStart).forEach { markDay(d, it) }
        exportersChanged(ctx)
        lastTapAct = -1L
        return true
    }

    /** Puts the start back where an adjustment found it, if that adjustment is still the last change. */
    fun undoShift(ctx: Context, u: StartShift.Undo, now: Long = System.currentTimeMillis()): Boolean {
        val cur = current(ctx)
        if (!StartShift.undoLive(u, cur, now) || cur == null) return false
        val prev = before(ctx, cur)
        // The same rules: the old start was valid, but don't trust it blindly.
        val back = StartShift.to(cur, prev, u.oldStart, now)
        if (!back.changed) return false
        setStart(ctx, cur.id, back.newStart)
        val d = db(ctx)
        StartShift.daysTouched(u.newStart, back.newStart).forEach { markDay(d, it) }
        exportersChanged(ctx)
        return true
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

    /** Sets who an entry was with: exactly [people] (person links); places and other links stay. */
    fun setPeople(ctx: Context, entryId: Long, people: Set<Long>) {
        tx(ctx) { d ->
            d.execSQL(
                "DELETE FROM entry_link WHERE entry_id = ? AND link_id IN (SELECT id FROM link WHERE kind = ?)",
                arrayOf<Any>(entryId, LinkKind.PERSON),
            )
            people.forEach { p ->
                d.insertWithOnConflict("entry_link", null, ContentValues().apply {
                    put("entry_id", entryId)
                    put("link_id", p)
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

    /** Returns the new entry's id. */
    fun addEntry(ctx: Context, activityId: Long, start: Long, links: Set<Long>, contextId: Long?, title: String? = null, extras: Set<Long> = emptySet()): Long =
        tx(ctx) {
            val id = insert(ctx, activityId, start, contextId, title)
            writeLinks(ctx, id, links)
            writeExtras(ctx, id, extras - activityId)
            id
        }

    /** [title] null or blank makes it a plain entry of its activity again. Marks every export dirty, like any edit. */
    fun updateEntry(
        ctx: Context, id: Long, activityId: Long, start: Long, links: Set<Long>, contextId: Long?, title: String? = null,
        extras: Set<Long>? = null,
    ) {
        tx(ctx) { d ->
            val old = startOf(ctx, id)
            val t = FreeWrite.clean(title)
            val v = ContentValues().apply {
                put("activity_id", activityId)
                put("start_ms", start)
                if (contextId != null) put("context_id", contextId) else putNull("context_id")
                if (t != null) put("title", t) else putNull("title")
            }
            d.update("entry", v, "id=?", args(id))
            writeLinks(ctx, id, links)
            // null leaves them; the primary is never also an extra.
            val ex = extras ?: (extrasFor(ctx, listOf(id))[id] ?: emptySet())
            writeExtras(ctx, id, ex - activityId)
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
            d.execSQL(StoreSql.DELETE_ENTRY_EXTRAS, arrayOf<Any>(id))
            d.execSQL(StoreSql.DELETE_ENTRY, arrayOf<Any>(id))
            if (old != null) dirtyAround(ctx, old)
        }
    }

    private fun startOf(ctx: Context, entryId: Long): Long? =
        db(ctx).rawQuery("SELECT start_ms FROM entry WHERE id = ?", args(entryId)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    /** Each entry's extra activities ("Eat + Family"), in chunks so a long history stays within SQLite's limits. */
    private fun extrasFor(ctx: Context, ids: List<Long>): Map<Long, Set<Long>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, MutableSet<Long>>()
        ids.chunked(500).forEach { chunk ->
            db(ctx).rawQuery(StoreSql.EXTRAS_FOR + "(" + chunk.joinToString(",") + ")", null).use { c ->
                while (c.moveToNext()) out.getOrPut(c.getLong(0)) { HashSet() }.add(c.getLong(1))
            }
        }
        return out
    }

    /**
     * Add mode in the panel: [act] on or off as an extra of entry [entryId] ([Together.toggle]; the
     * primary stays). Marks the day dirty for every export.
     */
    fun toggleExtra(ctx: Context, entryId: Long, act: Long) {
        val e = (latest(ctx)?.takeIf { it.id == entryId }) ?: return
        val next = Together.toggle(e, act)
        if (next == e.extras) return
        setExtras(ctx, entryId, next)
    }

    /** Sets entry [entryId]'s extras to exactly [extras] (its primary is never one). */
    fun setExtras(ctx: Context, entryId: Long, extras: Set<Long>) {
        val primary = db(ctx).rawQuery("SELECT activity_id FROM entry WHERE id = ?", args(entryId)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        } ?: return
        tx(ctx) { writeExtras(ctx, entryId, extras - primary) }
        startOf(ctx, entryId)?.let { dirtyAround(ctx, it) }
    }

    private fun writeExtras(ctx: Context, entryId: Long, extras: Set<Long>) {
        val d = db(ctx)
        d.execSQL(StoreSql.DELETE_ENTRY_EXTRAS, arrayOf<Any>(entryId))
        extras.filter { it != IDLE }.forEach { d.execSQL(StoreSql.EXTRA_ADD, arrayOf<Any>(entryId, it)) }
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

    /**
     * The entries behind [spans] for [from, to): the one running at [from], then every one that starts
     * before [to], oldest first, with links and extras. Idle ones are kept (the recap needs them).
     */
    fun dayEntries(ctx: Context, from: Long, to: Long): List<Entry> {
        val rows = ArrayList<Entry>()
        db(ctx).rawQuery(
            "SELECT $ENTRY_COLS FROM entry " +
                "WHERE start_ms >= (SELECT COALESCE(MAX(start_ms), 0) FROM entry WHERE start_ms <= ?) " +
                "AND start_ms < ? ORDER BY start_ms, id",
            args(from, to),
        ).use { c ->
            while (c.moveToNext()) rows.add(readEntry(c))
        }
        return withLinksAndExtras(ctx, rows)
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
        val extras = extrasFor(ctx, rows.map { it.id })
        return LogRules.spans(rows.map { it.copy(links = links[it.id] ?: emptySet(), extras = extras[it.id] ?: emptySet()) }, from, to, System.currentTimeMillis())
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
        HubSync.changed(ctx)
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

    /** A different Toggl account, and the old entries are given up on: every Toggl record goes, with no calls to Toggl. */
    fun forgetTogglRecords(ctx: Context) {
        tx(ctx) { d ->
            d.execSQL(StoreSql.DELETE_TARGET, arrayOf<Any>(TOGGL))
            d.execSQL(StoreSql.DELETE_TARGET, arrayOf<Any>(TOGGL_RELEASED))
        }
        Prefs.setTogglUnresolved(ctx, emptySet())
    }

    /** A different Toggl account, and the old entries stay in the old account: their records move aside, never used again by the new token. */
    fun shelveTogglRecords(ctx: Context) {
        tx(ctx) { d ->
            d.execSQL(StoreSql.RETARGET, arrayOf<Any>(TOGGL_OLD, TOGGL))
            d.execSQL(StoreSql.RETARGET, arrayOf<Any>(TOGGL_OLD_RELEASED, TOGGL_RELEASED))
        }
        Prefs.setTogglUnresolved(ctx, emptySet())
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
                    // A note moved between folders has a new id: it's still the same note.
                    val old = cache[u.docId] ?: u.movedFrom?.let { cache[it] }
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

    // --- The Now hub (hub/PROTOCOL.md; the sync itself is in HubSync.kt) ---
    //
    // export_state, target 'hub': one row per record the hub knows about. local_key is the record's
    // global id; remote_id is "<letter>:<local row id>:<diary day key>" (the day only for entries and
    // moments); content_hash is the hash last sent or applied; synced_at holds its version.

    /** What the sync remembers about one record. [localId] 0 and [hash] "-" for a deleted one. */
    class HubRow(val gid: String, val letter: Char, val localId: Long, val day: String, val hash: String, val v: Long)

    fun hubRows(ctx: Context): Map<String, HubRow> {
        val out = HashMap<String, HubRow>()
        db(ctx).rawQuery("SELECT local_key, remote_id, content_hash, synced_at FROM export_state WHERE target = ?", args(HUB)).use { c ->
            while (c.moveToNext()) {
                val parts = c.getString(1).split(':')
                val letter = parts.getOrNull(0)?.firstOrNull() ?: continue
                val local = parts.getOrNull(1)?.toLongOrNull() ?: continue
                out[c.getString(0)] = HubRow(c.getString(0), letter, local, parts.getOrNull(2) ?: "", c.getString(2), c.getLong(3))
            }
        }
        return out
    }

    fun saveHubRows(ctx: Context, rows: Collection<HubRow>) {
        if (rows.isEmpty()) return
        tx(ctx) { d ->
            rows.forEach { r ->
                d.execSQL(
                    "INSERT OR REPLACE INTO export_state (target, local_key, remote_id, content_hash, synced_at) VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any>(HUB, r.gid, "${r.letter}:${r.localId}:${r.day}", r.hash, r.v),
                )
            }
        }
    }

    /** Forgets everything the sync knew (a different hub): the next run sends the whole log again. */
    fun forgetHub(ctx: Context) {
        tx(ctx) { d ->
            d.execSQL("DELETE FROM export_state WHERE target = ?", arrayOf<Any>(HUB))
            d.execSQL("DELETE FROM export_day WHERE target = ?", arrayOf<Any>(HUB))
        }
    }

    /** Entries that start in [from, to), oldest first, with their links and extras. */
    fun entriesStarting(ctx: Context, from: Long, to: Long): List<Entry> {
        val rows = ArrayList<Entry>()
        db(ctx).rawQuery("SELECT $ENTRY_COLS FROM entry WHERE start_ms >= ? AND start_ms < ? ORDER BY start_ms, id", args(from, to)).use { c ->
            while (c.moveToNext()) rows.add(readEntry(c))
        }
        return withLinksAndExtras(ctx, rows)
    }

    fun entryById(ctx: Context, id: Long): Entry? {
        val e = db(ctx).rawQuery("SELECT $ENTRY_COLS FROM entry WHERE id = ?", args(id)).use { c -> if (c.moveToFirst()) readEntry(c) else null }
            ?: return null
        return withLinksAndExtras(ctx, listOf(e)).first()
    }

    private fun withLinksAndExtras(ctx: Context, rows: List<Entry>): List<Entry> {
        if (rows.isEmpty()) return rows
        val ids = rows.map { it.id }
        val links = HashMap<Long, Set<Long>>()
        ids.chunked(500).forEach { links.putAll(linksFor(ctx, it)) }
        val extras = extrasFor(ctx, ids)
        return rows.map { it.copy(links = links[it.id] ?: emptySet(), extras = extras[it.id] ?: emptySet()) }
    }

    /** A moment with what the hub also carries: its import key and its references, as (kind, id). */
    class HubMoment(val m: Moment, val src: String?, val refs: List<Pair<String, Long>>)

    fun hubMoments(ctx: Context, from: Long, to: Long): List<HubMoment> =
        withSrcAndRefs(ctx, readMoments(ctx, "ts >= ? AND ts < ?", args(from, to)))

    fun hubMomentById(ctx: Context, id: Long): HubMoment? =
        withSrcAndRefs(ctx, readMoments(ctx, "id = ?", args(id))).firstOrNull()

    private fun withSrcAndRefs(ctx: Context, ms: List<Moment>): List<HubMoment> {
        if (ms.isEmpty()) return emptyList()
        val src = HashMap<Long, String>()
        val refs = HashMap<Long, MutableList<Pair<String, Long>>>()
        ms.chunked(500).forEach { chunk ->
            val inList = chunk.joinToString(",") { it.id.toString() }
            db(ctx).rawQuery("SELECT id, source_key FROM moment WHERE source_key IS NOT NULL AND id IN ($inList)", null).use { c ->
                while (c.moveToNext()) src[c.getLong(0)] = c.getString(1)
            }
            db(ctx).rawQuery("SELECT moment_id, kind, ref_id FROM moment_ref WHERE moment_id IN ($inList)", null).use { c ->
                while (c.moveToNext()) refs.getOrPut(c.getLong(0)) { ArrayList() }.add(c.getString(1) to c.getLong(2))
            }
        }
        return ms.map { HubMoment(it, src[it.id], refs[it.id] ?: emptyList()) }
    }

    /**
     * An entry from another device: updates row [id] if it exists, otherwise makes a new one.
     * Returns the row id. Marks the days dirty for every export, like any edit.
     */
    fun hubPutEntry(
        ctx: Context, id: Long?, activityId: Long, start: Long, links: Set<Long>, contextId: Long?, title: String?, extras: Set<Long>,
    ): Long {
        if (id != null && startOf(ctx, id) != null) {
            updateEntry(ctx, id, activityId, start, links, contextId, title, extras)
            return id
        }
        return tx(ctx) {
            val nid = insert(ctx, activityId, start, contextId, title)
            writeLinks(ctx, nid, links)
            writeExtras(ctx, nid, extras - activityId)
            nid
        }
    }

    fun hubDeleteEntry(ctx: Context, id: Long) {
        if (startOf(ctx, id) != null) deleteEntry(ctx, id)
    }

    /** A note or feeling from another device: updates row [id] if it exists, otherwise makes one. Returns the row id. */
    fun hubPutMoment(
        ctx: Context, id: Long?, ts: Long, kind: String, title: String, text: String, moodId: Long?, entryId: Long?,
        tags: Collection<Long>, refs: Collection<Pair<String, Long>>, src: String?,
    ): Long = tx(ctx) { d ->
        var row = id?.let { i -> d.rawQuery("SELECT ts FROM moment WHERE id = ?", args(i)).use { c -> if (c.moveToFirst()) i to c.getLong(0) else null } }
        if (row == null && src != null) {
            row = d.rawQuery("SELECT id, ts FROM moment WHERE source_key = ?", arrayOf(src)).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null }
        }
        val v = ContentValues().apply {
            put("ts", ts)
            put("kind", if (kind == MomentKind.FEELING || kind == MomentKind.NOTE || kind == MomentKind.TRACKER) kind else MomentKind.NOTE)
            put("title", title)
            put("text", text)
            if (moodId != null) put("mood_id", moodId) else putNull("mood_id")
            if (entryId != null) put("entry_id", entryId) else putNull("entry_id")
            if (src != null) put("source_key", src) else putNull("source_key")
        }
        val mid = if (row != null) {
            d.update("moment", v, "id=?", args(row.first))
            dirtyDay(ctx, row.second)
            row.first
        } else {
            d.insertOrThrow("moment", null, v)
        }
        d.delete("moment_tag", "moment_id=?", args(mid))
        writeTags(d, mid, tags)
        d.delete("moment_ref", "moment_id=?", args(mid))
        refs.distinct().forEach { (k, r) -> d.execSQL(StoreSql.INSERT_MOMENT_REF, arrayOf<Any>(mid, k, r)) }
        dirtyDay(ctx, ts)
        mid
    }

    fun hubDeleteMoment(ctx: Context, id: Long) {
        val there = db(ctx).rawQuery("SELECT 1 FROM moment WHERE id = ?", args(id)).use { it.moveToFirst() }
        if (there) deleteMoment(ctx, id)
    }

    /** Every diary day with an entry or a moment, for the first send of a whole log. */
    fun hubAllDays(ctx: Context): List<Long> {
        val days = java.util.TreeSet<Long>()
        db(ctx).rawQuery("SELECT start_ms FROM entry UNION ALL SELECT ts FROM moment", null).use { c ->
            while (c.moveToNext()) days.add(DiaryDay.start(c.getLong(0)))
        }
        return days.toList()
    }
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

    /** The check-in flow after a switch: which steps it asks, and whether skipping keeps the defaults. */
    fun flowSettings(ctx: Context): FlowSettings = p(ctx).let {
        FlowSettings(
            where = it.getBoolean("flow_where", true),
            who = it.getBoolean("flow_who", true),
            feeling = it.getBoolean("flow_feeling", true),
            keepDefaults = it.getBoolean("flow_keep", true),
        )
    }
    fun setFlowSetting(ctx: Context, key: String, on: Boolean) = p(ctx).edit().putBoolean(key, on).apply()

    /** Start time: the offset chips (minutes), whether the flow shows them, and when to ask before cutting. */
    fun startOffsets(ctx: Context): List<Int> =
        p(ctx).getString("start_offsets", null)?.let { StartShift.parseOffsets(it) }?.takeIf { it.isNotEmpty() }
            ?: StartShift.DEFAULT_OFFSETS
    fun setStartOffsets(ctx: Context, v: List<Int>) = p(ctx).edit().putString("start_offsets", StartShift.offsetsText(v)).apply()
    fun startInFlow(ctx: Context): Boolean = p(ctx).getBoolean("start_in_flow", true)
    fun setStartInFlow(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("start_in_flow", on).apply()
    fun askOverMin(ctx: Context): Int = p(ctx).getInt("start_ask_over", 0)
    fun setAskOverMin(ctx: Context, v: Int) = p(ctx).edit().putInt("start_ask_over", v).apply()

    /** The last start adjustment (for Undo and the head line's note) and a ⚠ chip waiting for its second tap. */
    fun shiftUndo(ctx: Context): StartShift.Undo? = StartShift.Undo.decode(p(ctx).getString("start_undo", null))
    fun setShiftUndo(ctx: Context, u: StartShift.Undo?) = p(ctx).edit().putString("start_undo", u?.encode()).commit().let { }
    fun shiftArmed(ctx: Context): StartShift.Armed? = StartShift.Armed.decode(p(ctx).getString("start_armed", null))
    fun setShiftArmed(ctx: Context, a: StartShift.Armed?) = p(ctx).edit().putString("start_armed", a?.encode()).commit().let { }

    /** The running check-in flow (core FlowState.encode), kept across process deaths. */
    fun flowState(ctx: Context): String? = p(ctx).getString("flow_state", null)
    fun setFlowState(ctx: Context, v: String?) {
        // commit: the next tap may come in a new process right after this one dies.
        if (v == null) p(ctx).edit().remove("flow_state").commit() else p(ctx).edit().putString("flow_state", v).commit()
    }

    // --- Evening recap ---

    fun recapOn(ctx: Context): Boolean = p(ctx).getBoolean("recap_on", true)
    fun setRecapOn(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("recap_on", on).apply()
    /** Minutes after midnight (default 9:30pm). */
    fun recapTime(ctx: Context): Int = p(ctx).getInt("recap_time", Recap.DEFAULT_TIME)
    fun setRecapTime(ctx: Context, v: Int) = p(ctx).edit().putInt("recap_time", v).apply()
    fun recapSettings(ctx: Context): RecapSettings = RecapSettings(
        minGapMinutes = p(ctx).getInt("recap_min_gap", 30),
        countNoPlace = p(ctx).getBoolean("recap_no_place", true),
    )
    fun setRecapMinGap(ctx: Context, v: Int) = p(ctx).edit().putInt("recap_min_gap", v).apply()
    fun setRecapNoPlace(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("recap_no_place", on).apply()
    /** When the scheduled recap run is due, and when one last ran (ms), so a missed run catches up. */
    fun recapDue(ctx: Context): Long = p(ctx).getLong("recap_due", 0L)
    fun setRecapDue(ctx: Context, v: Long) = p(ctx).edit().putLong("recap_due", v).apply()
    fun recapLastRun(ctx: Context): Long = p(ctx).getLong("recap_last_run", 0L)
    fun setRecapLastRun(ctx: Context, v: Long) = p(ctx).edit().putLong("recap_last_run", v).commit().let { }

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

    /** The last scan's whole-vault counts: notes indexed and folders walked. */
    fun indexCounts(ctx: Context): Pair<Int, Int> = p(ctx).getInt("index_notes", 0) to p(ctx).getInt("index_folders", 0)
    fun setIndexCounts(ctx: Context, notes: Int, folders: Int) = p(ctx).edit().putInt("index_notes", notes).putInt("index_folders", folders).apply()

    /** When the daily notes were last read for the people and places ranking. */
    fun rankLastRead(ctx: Context): Long = p(ctx).getLong("rank_last_read", 0L)
    fun setRankLastRead(ctx: Context, v: Long) = p(ctx).edit().putLong("rank_last_read", v).apply()
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
    fun togglToken(ctx: Context): String? = secret(ctx).getString("toggl_token", null)?.takeIf { it.isNotBlank() }
    fun setTogglToken(ctx: Context, v: String?) = secret(ctx).edit().putString("toggl_token", v).commit().let { }

    /** The token has a preferences file of its own, which data_extraction_rules.xml keeps out of device transfers. */
    private fun secret(ctx: Context) = ctx.getSharedPreferences("toggl_secret", Context.MODE_PRIVATE)

    /** Whose Toggl account the token is (the /me id; 0 when not known), to tell a new account from the same one. */
    fun togglUserId(ctx: Context): Long = p(ctx).getLong("toggl_user", 0L)
    fun setTogglUserId(ctx: Context, v: Long) = p(ctx).edit().putLong("toggl_user", v).apply()

    /** Keys of sent entries that couldn't be settled (several matches, or the lookup was refused), for the status line. */
    fun togglUnresolved(ctx: Context): Set<String> = p(ctx).getStringSet("toggl_unresolved", emptySet())?.toSet() ?: emptySet()
    fun setTogglUnresolved(ctx: Context, v: Set<String>) = p(ctx).edit().putStringSet("toggl_unresolved", v).apply()

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
    fun setTogglBudget(ctx: Context, v: String) = p(ctx).edit().putString("toggl_budget", v).commit().let { }

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
    fun setTogglProjectsListed(ctx: Context, ws: Long, on: Boolean) {
        val now = p(ctx).getStringSet("toggl_listed", emptySet()) ?: emptySet()
        p(ctx).edit().putStringSet("toggl_listed", if (on) now + ws.toString() else now - ws.toString()).apply()
    }

    fun forgetTogglProject(ctx: Context, ws: Long, key: String) {
        val keep = (p(ctx).getString("toggl_projects", "") ?: "").lines().filter { l ->
            val f = l.split('\t')
            f.size == 3 && !(f[0] == ws.toString() && f[2] == key)
        }
        p(ctx).edit().putString("toggl_projects", keep.joinToString("\n")).apply()
    }

    /** Project names Toggl refused, as `workspace<TAB>lowercase name`: not tried again until "Send now". */
    fun togglRefusedProject(ctx: Context, ws: Long, key: String): Boolean = "$ws\t$key" in (p(ctx).getStringSet("toggl_refused", emptySet()) ?: emptySet())
    fun addTogglRefusedProject(ctx: Context, ws: Long, key: String) =
        p(ctx).edit().putStringSet("toggl_refused", (p(ctx).getStringSet("toggl_refused", emptySet()) ?: emptySet()) + "$ws\t$key").apply()

    /** "Send now" gives refused project names, and a plan that wouldn't make projects, another try. */
    fun clearTogglProjectRefusals(ctx: Context) =
        p(ctx).edit().remove("toggl_refused").remove("toggl_noproj").apply()

    /** Toggl wouldn't make projects on this workspace's plan: entries go without one. */
    fun togglNoProjects(ctx: Context, ws: Long): Boolean = ws.toString() in (p(ctx).getStringSet("toggl_noproj", emptySet()) ?: emptySet())
    fun setTogglNoProjects(ctx: Context, ws: Long) =
        p(ctx).edit().putStringSet("toggl_noproj", (p(ctx).getStringSet("toggl_noproj", emptySet()) ?: emptySet()) + ws.toString()).apply()
}
