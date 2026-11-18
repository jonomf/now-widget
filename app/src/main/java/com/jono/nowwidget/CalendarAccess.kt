package com.jono.nowwidget

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import com.jono.nowwidget.core.CalIdentity
import com.jono.nowwidget.core.CalProvider
import com.jono.nowwidget.core.CalRow
import com.jono.nowwidget.core.DesiredEvent
import com.jono.nowwidget.core.PlannedEvent
import com.jono.nowwidget.core.SeenEvent

/** What the test event looked like when it was read back. */
class ReadBack(val calendarId: Long, val title: String, val colorKey: String?, val color: Int?, val deleted: Boolean)

/**
 * Every call into Android's calendar store. All of these block: call them off the main thread.
 * They throw on provider errors (SecurityException when access is off); the callers record those.
 */
object CalendarAccess {
    fun granted(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun Cursor.str(i: Int): String = if (isNull(i)) "" else (getString(i) ?: "")

    fun calendars(ctx: Context): List<CalRow> {
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS,
            CalendarContract.Calendars._SYNC_ID,
        )
        val out = ArrayList<CalRow>()
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    CalRow(
                        c.getLong(0),
                        CalIdentity(c.str(2), c.str(3), if (c.isNull(8)) null else c.str(8).ifEmpty { null }, c.str(1)),
                        if (c.isNull(4)) 0xFF9A9A9A.toInt() else c.getInt(4),
                        if (c.isNull(5)) 0 else c.getInt(5),
                        !c.isNull(6) && c.getInt(6) != 0,
                        !c.isNull(7) && c.getInt(7) != 0,
                    )
                )
            }
        }
        return out.sortedWith(compareBy({ it.account }, { it.label }))
    }

    /** The account's event colors (Google has 11) as (key, ARGB). Empty when it has none. */
    fun eventColors(ctx: Context, account: String, accountType: String): List<Pair<String, Int>> {
        val proj = arrayOf(CalendarContract.Colors.COLOR_KEY, CalendarContract.Colors.COLOR)
        val sel = CalendarContract.Colors.ACCOUNT_NAME + " = ? AND " + CalendarContract.Colors.ACCOUNT_TYPE + " = ? AND " +
            CalendarContract.Colors.COLOR_TYPE + " = " + CalendarContract.Colors.TYPE_EVENT
        val out = ArrayList<Pair<String, Int>>()
        ctx.contentResolver.query(CalendarContract.Colors.CONTENT_URI, proj, sel, arrayOf(account, accountType), null)?.use { c ->
            while (c.moveToNext()) {
                if (!c.isNull(0) && !c.isNull(1)) out.add(c.str(0) to c.getInt(1))
            }
        }
        // Google's keys are "1" to "11": keep them in that order so a tie always picks the same one.
        return out.sortedWith(compareBy({ it.first.toIntOrNull() ?: Int.MAX_VALUE }, { it.first }))
    }

    private val SEEN = arrayOf(
        CalendarContract.Events._ID,
        CalendarContract.Events.CALENDAR_ID,
        CalendarContract.Events.DESCRIPTION,
        CalendarContract.Events.DELETED,
        CalendarContract.Events.DTSTART,
        CalendarContract.Events.DTEND,
    )

    private fun seen(c: Cursor) = SeenEvent(
        c.getLong(0), c.getLong(1), c.str(2), !c.isNull(3) && c.getInt(3) != 0,
        if (c.isNull(4)) 0L else c.getLong(4), if (c.isNull(5)) 0L else c.getLong(5),
    )

    /** These events as they are now, by id. An id that isn't there is gone. */
    fun read(ctx: Context, ids: Collection<Long>): Map<Long, SeenEvent> {
        val out = HashMap<Long, SeenEvent>()
        ids.distinct().chunked(200).forEach { chunk ->
            val sel = CalendarContract.Events._ID + " IN (" + chunk.joinToString(",") + ")"
            ctx.contentResolver.query(CalendarContract.Events.CONTENT_URI, SEEN, sel, null, null)?.use { c ->
                while (c.moveToNext()) seen(c).let { out[it.eventId] = it }
            }
        }
        return out
    }

    /**
     * Events in one calendar starting in [from, to) whose description mentions a marker, lowest id
     * first. Only used to find the app's own events that have no record (see CalendarOwnership.mayAdopt).
     */
    fun findMarked(ctx: Context, calendarId: Long, from: Long, to: Long): List<SeenEvent> {
        // Numbers go into the selection as text the app made itself; only the LIKE pattern is an argument.
        val sel = CalendarContract.Events.CALENDAR_ID + " = " + calendarId + " AND " +
            CalendarContract.Events.DELETED + " = 0 AND " +
            CalendarContract.Events.DTSTART + " >= " + from + " AND " + CalendarContract.Events.DTSTART + " < " + to +
            " AND " + CalendarContract.Events.DESCRIPTION + " LIKE ?"
        val out = ArrayList<SeenEvent>()
        ctx.contentResolver.query(CalendarContract.Events.CONTENT_URI, SEEN, sel, arrayOf("%now-log:%"), CalendarContract.Events._ID)?.use { c ->
            while (c.moveToNext()) out.add(seen(c))
        }
        return out.sortedBy { it.eventId }
    }

    private fun values(d: DesiredEvent, forUpdate: Boolean) = ContentValues().apply {
        put(CalendarContract.Events.TITLE, d.title)
        put(CalendarContract.Events.DESCRIPTION, d.description)
        put(CalendarContract.Events.DTSTART, d.from)
        put(CalendarContract.Events.DTEND, d.to)
        put(CalendarContract.Events.EVENT_TIMEZONE, d.timeZone)
        if (d.colorKey != null) {
            put(CalendarContract.Events.EVENT_COLOR_KEY, d.colorKey)
        } else if (forUpdate) {
            putNull(CalendarContract.Events.EVENT_COLOR_KEY)
            putNull(CalendarContract.Events.EVENT_COLOR)
        }
        if (!forUpdate) {
            put(CalendarContract.Events.CALENDAR_ID, d.calendarId)
            // A log of what happened shouldn't mark you busy.
            put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE)
        }
    }

    /** Adds the event and returns its id. */
    fun insert(ctx: Context, d: DesiredEvent): Long {
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values(d, forUpdate = false))
            ?: throw IllegalStateException("The calendar didn't take the event")
        return ContentUris.parseId(uri)
    }

    /** Only call this for an event CalendarOwnership says is the app's own. */
    fun update(ctx: Context, eventId: Long, d: DesiredEvent) {
        val n = ctx.contentResolver.update(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), values(d, forUpdate = true), null, null,
        )
        if (n < 1) throw IllegalStateException("Event $eventId wasn't there to update")
    }

    /** Only call this for an event CalendarOwnership says is the app's own. */
    fun delete(ctx: Context, eventId: Long) {
        ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null)
    }

    fun readBack(ctx: Context, eventId: Long): ReadBack? {
        val proj = arrayOf(
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.EVENT_COLOR_KEY,
            CalendarContract.Events.EVENT_COLOR,
            CalendarContract.Events.DELETED,
        )
        ctx.contentResolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), proj, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                return ReadBack(
                    c.getLong(0), c.str(1), if (c.isNull(2)) null else c.str(2),
                    if (c.isNull(3)) null else c.getInt(3), !c.isNull(4) && c.getInt(4) != 0,
                )
            }
        }
        return null
    }

    /**
     * Planned events in [from, to) from these calendars, read-only. Recurring events come in as
     * their instances. All-day, cancelled and declined events are left out. Read with our own
     * selection (not Instances.query, which only returns calendars shown in the calendar app), so
     * a mapped calendar you hid still counts.
     */
    fun planned(ctx: Context, calendars: Set<Long>, from: Long, to: Long): List<PlannedEvent> {
        if (calendars.isEmpty()) return emptyList()
        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.STATUS,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val out = ArrayList<PlannedEvent>()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, from)
        ContentUris.appendId(b, to)
        val sel = CalendarContract.Instances.CALENDAR_ID + " IN (" + calendars.joinToString(",") + ")"
        ctx.contentResolver.query(b.build(), proj, sel, null, CalendarContract.Instances.BEGIN)?.use { c ->
            while (c.moveToNext()) {
                val cal = c.getLong(1)
                if (cal !in calendars) continue
                if (!c.isNull(4) && c.getInt(4) != 0) continue
                if (!c.isNull(5) && c.getInt(5) == CalendarContract.Events.STATUS_CANCELED) continue
                if (!c.isNull(6) && c.getInt(6) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                val begin = c.getLong(2)
                val end = c.getLong(3)
                if (end > begin) out.add(PlannedEvent(c.str(0), cal, begin, end))
            }
        }
        return out.sortedBy { it.from }
    }
}

/** [CalendarAccess] for the core export logic. */
class RealCalProvider(private val ctx: Context) : CalProvider {
    override fun read(ids: Collection<Long>) = CalendarAccess.read(ctx, ids)
    override fun findMarked(calendarId: Long, from: Long, to: Long) = CalendarAccess.findMarked(ctx, calendarId, from, to)
    override fun insert(d: DesiredEvent) = CalendarAccess.insert(ctx, d)
    override fun update(eventId: Long, d: DesiredEvent) = CalendarAccess.update(ctx, eventId, d)
    override fun delete(eventId: Long) = CalendarAccess.delete(ctx, eventId)
}
