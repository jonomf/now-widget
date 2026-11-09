package com.jono.nowwidget

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

data class Act(
    val id: Long,
    val name: String,
    val emoji: String,
    val color: Int,
    val pos: Int,
    val show: Boolean,
    val group: String,
    val archived: Boolean,
)

data class Person(
    val id: Long,
    val name: String,
    val emoji: String,
    val pos: Int,
    val archived: Boolean,
) {
    val badge: String get() = emoji.ifBlank { name.take(1).uppercase() }
}

data class Entry(
    val id: Long,
    val activityId: Long,
    val start: Long,
    val note: String,
    val people: Set<Long> = emptySet(),
)

/** An entry clipped to the time range that was asked for. */
data class Span(val entry: Entry, val from: Long, val to: Long) {
    val ms: Long get() = to - from
}

val PALETTE: IntArray = intArrayOf(
    0xFF5B8DEF.toInt(), 0xFFE5735C.toInt(), 0xFF4DB37E.toInt(), 0xFFE6B13D.toInt(),
    0xFF9B6FD8.toInt(), 0xFF3FB6C6.toInt(), 0xFFE36FA5.toInt(), 0xFF8AA63C.toInt(),
    0xFFC98A4B.toInt(), 0xFF6C7BC4.toInt(), 0xFF4F9D9A.toInt(), 0xFF9A9A9A.toInt(),
)

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

private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "now.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE activity (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
                "emoji TEXT NOT NULL, color INTEGER NOT NULL, pos INTEGER NOT NULL, " +
                "show_btn INTEGER NOT NULL DEFAULT 1, grp TEXT NOT NULL DEFAULT '', " +
                "archived INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE person (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
                "emoji TEXT NOT NULL DEFAULT '', pos INTEGER NOT NULL, " +
                "archived INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE entry (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "activity_id INTEGER NOT NULL, start_ms INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE INDEX entry_start ON entry (start_ms)")
        db.execSQL(
            "CREATE TABLE entry_person (entry_id INTEGER NOT NULL, person_id INTEGER NOT NULL, " +
                "PRIMARY KEY (entry_id, person_id))"
        )
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

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

object Store {
    /** An entry with this activity means "doing nothing". It's left out of all stats. */
    private const val IDLE = 0L
    private const val GRACE_MS = 60_000L
    private const val DOUBLE_TAP_MS = 700L
    private const val DAY_MS = 86_400_000L

    private var helper: Helper? = null
    private var lastTapAct = -1L
    private var lastTapAt = 0L

    private fun db(ctx: Context): SQLiteDatabase {
        val h = helper ?: Helper(ctx.applicationContext).also { helper = it }
        return h.writableDatabase
    }

    // --- Activities and people ---

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

    fun allPeople(ctx: Context): List<Person> {
        val out = ArrayList<Person>()
        db(ctx).rawQuery(
            "SELECT id, name, emoji, pos, archived FROM person ORDER BY pos, id",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Person(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4) != 0))
            }
        }
        return out
    }

    fun saveAct(ctx: Context, id: Long?, name: String, emoji: String, color: Int, group: String, show: Boolean) {
        val v = ContentValues().apply {
            put("name", name)
            put("emoji", emoji)
            put("color", color)
            put("grp", group)
            put("show_btn", if (show) 1 else 0)
        }
        if (id == null) {
            v.put("pos", nextPos(ctx, "activity"))
            db(ctx).insert("activity", null, v)
        } else {
            db(ctx).update("activity", v, "id=?", arrayOf(id.toString()))
        }
    }

    fun savePerson(ctx: Context, id: Long?, name: String, emoji: String) {
        val v = ContentValues().apply {
            put("name", name)
            put("emoji", emoji)
        }
        if (id == null) {
            v.put("pos", nextPos(ctx, "person"))
            db(ctx).insert("person", null, v)
        } else {
            db(ctx).update("person", v, "id=?", arrayOf(id.toString()))
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
        db(ctx).update(table, v, "id=?", arrayOf(id.toString()))
    }

    fun setOrder(ctx: Context, table: String, ids: List<Long>) {
        val d = db(ctx)
        d.beginTransaction()
        try {
            ids.forEachIndexed { i, id ->
                val v = ContentValues().apply { put("pos", i) }
                d.update(table, v, "id=?", arrayOf(id.toString()))
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    // --- The log ---

    /** What you're doing now; null when nothing is on. */
    fun current(ctx: Context): Entry? = latest(ctx)?.takeIf { it.activityId != IDLE }

    /** When you turned everything off; null if something is on. */
    fun idleSince(ctx: Context): Long? = latest(ctx)?.takeIf { it.activityId == IDLE }?.start

    private fun latest(ctx: Context): Entry? {
        val e = db(ctx).rawQuery(
            "SELECT id, activity_id, start_ms, note FROM entry ORDER BY start_ms DESC, id DESC LIMIT 1",
            null,
        ).use { c ->
            if (c.moveToFirst()) Entry(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3)) else null
        } ?: return null
        return e.copy(people = peopleFor(ctx, listOf(e.id))[e.id] ?: emptySet())
    }

    private fun before(ctx: Context, e: Entry): Entry? =
        db(ctx).rawQuery(
            "SELECT id, activity_id, start_ms, note FROM entry " +
                "WHERE (start_ms < ? OR (start_ms = ? AND id < ?)) ORDER BY start_ms DESC, id DESC LIMIT 1",
            arrayOf(e.start.toString(), e.start.toString(), e.id.toString()),
        ).use { c ->
            if (c.moveToFirst()) Entry(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3)) else null
        }

    private fun insert(ctx: Context, activityId: Long, start: Long, note: String = ""): Long =
        db(ctx).insert("entry", null, ContentValues().apply {
            put("activity_id", activityId)
            put("start_ms", start)
            put("note", note)
        })

    /**
     * The one thing the buttons do.
     * - A different activity: it starts now, and that ends the last one.
     * - Within a minute of the last switch: that was a mistake, so replace it instead of logging it.
     * - The current activity again: turn it off, or move its start back if that's the setting.
     */
    fun tap(ctx: Context, activityId: Long) {
        val now = System.currentTimeMillis()
        if (activityId == lastTapAct && now - lastTapAt < DOUBLE_TAP_MS) return
        lastTapAct = activityId
        lastTapAt = now

        val cur = latest(ctx)
        if (cur == null) {
            insert(ctx, activityId, now)
            return
        }
        if (cur.activityId == activityId) {
            val step = Prefs.stepMinutes(ctx)
            when {
                step > 0 -> moveBack(ctx, cur, step * 60_000L)
                // Started by mistake a moment ago: undo it, so whatever came before carries on.
                now - cur.start < GRACE_MS && cur.note.isBlank() -> deleteEntry(ctx, cur.id)
                else -> insert(ctx, IDLE, now)
            }
            return
        }
        // An entry you've already added a note to wasn't a mis-tap, so it's never replaced.
        if (now - cur.start < GRACE_MS && cur.note.isBlank()) {
            val prev = before(ctx, cur)
            deleteEntry(ctx, cur.id)
            if (prev?.activityId != activityId) insert(ctx, activityId, cur.start)
            return
        }
        insert(ctx, activityId, now)
    }

    private fun moveBack(ctx: Context, cur: Entry, byMs: Long) {
        val prev = before(ctx, cur)
        val floor = if (prev != null) prev.start + 60_000L else 0L
        val start = maxOf(cur.start - byMs, floor)
        if (start < cur.start) setStart(ctx, cur.id, start)
    }

    private fun setStart(ctx: Context, id: Long, start: Long) {
        val v = ContentValues().apply { put("start_ms", start) }
        db(ctx).update("entry", v, "id=?", arrayOf(id.toString()))
    }

    /** Mark or unmark that you're with someone during the current activity. */
    fun togglePerson(ctx: Context, personId: Long) {
        val cur = current(ctx) ?: return
        val d = db(ctx)
        if (personId in cur.people) {
            d.delete("entry_person", "entry_id=? AND person_id=?", arrayOf(cur.id.toString(), personId.toString()))
        } else {
            d.insert("entry_person", null, ContentValues().apply {
                put("entry_id", cur.id)
                put("person_id", personId)
            })
        }
    }

    /** Adds to whatever notes the entry already has, one per line. */
    fun appendNote(ctx: Context, entryId: Long, text: String) {
        db(ctx).execSQL(
            "UPDATE entry SET note = CASE WHEN note = '' THEN ? ELSE note || char(10) || ? END WHERE id = ?",
            arrayOf<Any>(text, text, entryId),
        )
    }

    fun addEntry(ctx: Context, activityId: Long, start: Long, note: String, people: Set<Long>) {
        val id = insert(ctx, activityId, start, note)
        writePeople(ctx, id, people)
    }

    fun updateEntry(ctx: Context, id: Long, activityId: Long, start: Long, note: String, people: Set<Long>) {
        val v = ContentValues().apply {
            put("activity_id", activityId)
            put("start_ms", start)
            put("note", note)
        }
        db(ctx).update("entry", v, "id=?", arrayOf(id.toString()))
        writePeople(ctx, id, people)
    }

    private fun writePeople(ctx: Context, entryId: Long, people: Set<Long>) {
        val d = db(ctx)
        d.delete("entry_person", "entry_id=?", arrayOf(entryId.toString()))
        people.forEach { p ->
            d.insert("entry_person", null, ContentValues().apply {
                put("entry_id", entryId)
                put("person_id", p)
            })
        }
    }

    fun deleteEntry(ctx: Context, id: Long) {
        val d = db(ctx)
        d.delete("entry_person", "entry_id=?", arrayOf(id.toString()))
        d.delete("entry", "id=?", arrayOf(id.toString()))
    }

    private fun peopleFor(ctx: Context, ids: List<Long>): Map<Long, Set<Long>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, MutableSet<Long>>()
        db(ctx).rawQuery(
            "SELECT entry_id, person_id FROM entry_person WHERE entry_id IN (${ids.joinToString(",")})",
            null,
        ).use { c ->
            while (c.moveToNext()) out.getOrPut(c.getLong(0)) { HashSet() }.add(c.getLong(1))
        }
        return out
    }

    /** Everything that happened in [from, to), oldest first. An entry runs until the next one starts. */
    fun spans(ctx: Context, from: Long, to: Long): List<Span> {
        val now = System.currentTimeMillis()
        val rows = ArrayList<Entry>()
        db(ctx).rawQuery(
            "SELECT id, activity_id, start_ms, note FROM entry " +
                "WHERE start_ms >= (SELECT COALESCE(MAX(start_ms), 0) FROM entry WHERE start_ms <= ?) " +
                "AND start_ms < ? ORDER BY start_ms, id",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            while (c.moveToNext()) rows.add(Entry(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3)))
        }
        val people = peopleFor(ctx, rows.map { it.id })
        val limit = minOf(to, now)
        val out = ArrayList<Span>()
        rows.forEachIndexed { i, e ->
            val end = if (i + 1 < rows.size) rows[i + 1].start else limit
            val a = maxOf(from, e.start)
            val b = minOf(end, limit)
            if (b > a && e.activityId != IDLE) out.add(Span(e.copy(people = people[e.id] ?: emptySet()), a, b))
        }
        return out
    }

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
            arrayOf((now - 56 * DAY_MS).toString()),
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

    /**
     * Which activities get the buttons when they don't all fit. The current one is always there,
     * so you can tap it again. Picks stay in your own order, so buttons move as little as possible.
     */
    fun picks(all: List<Act>, cur: Act?, n: Int, scores: Map<Long, Double>, smart: Boolean): List<Act> {
        val pool = if (cur != null && all.none { it.id == cur.id }) all + cur else all
        if (pool.size <= n) return pool.sortedBy { it.pos }
        val others = pool.filter { it.id != cur?.id }
        val ranked = if (smart) {
            others.sortedWith(compareByDescending<Act> { scores[it.id] ?: 0.0 }.thenBy { it.pos })
        } else {
            others.sortedBy { it.pos }
        }
        val room = if (cur != null) n - 1 else n
        return (ranked.take(room) + listOfNotNull(cur)).sortedBy { it.pos }
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
}
