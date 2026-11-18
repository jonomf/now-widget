package com.jono.nowwidget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.jono.nowwidget.core.CalRecord
import com.jono.nowwidget.core.CalendarDay
import com.jono.nowwidget.core.CalendarOwnership
import com.jono.nowwidget.core.CalendarPlan
import com.jono.nowwidget.core.DesiredEvent
import com.jono.nowwidget.core.DiaryDay
import com.jono.nowwidget.core.EventColors
import com.jono.nowwidget.core.ExportProtocol
import com.jono.nowwidget.core.ObsidianDay
import com.jono.nowwidget.core.SeenEvent
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The calendar settings as one run sees them, read once. [logCalendars] are the only calendars the
 * app ever writes to; null [problem] means they all exist and can be written.
 */
class CalSetup(ctx: Context) {
    val calendars: Map<Long, CalInfo> = CalendarAccess.calendars(ctx).associateBy { it.id }
    val areas: Map<String, Long?> = Store.groupAreas(ctx)
    val perArea = Prefs.calPerArea(ctx)
    val defaultLog = Prefs.calLog(ctx)
    val areaLogs: Map<Long, Long> = if (perArea) Prefs.calAreaLogs(ctx) else emptyMap()

    /** Every log calendar the settings name. */
    val chosen: Set<Long> = (listOf(defaultLog) + areaLogs.values).filter { it > 0L }.toSet()
    val logCalendars: Set<Long> = chosen.filter { calendars[it]?.writable == true }.toSet()

    val problem: String? = when {
        defaultLog <= 0L -> "No log calendar chosen."
        chosen.any { it !in calendars } -> "A log calendar isn't on the phone any more (or isn't synced). Check the Calendar settings."
        chosen.any { it !in logCalendars } -> "The app can't add events to " + chosen.filter { it !in logCalendars }
            .joinToString(", ") { calendars[it]?.label ?: "?" } + "."
        else -> null
    }

    /** The calendars planned events are read from: the areas, never a log calendar. */
    val areaCalendars: Set<Long> = areas.values.filterNotNull().filter { it !in chosen && it in calendars }.toSet()

    fun areaOf(group: String): Long? = areas[group.trim()]

    /** Where a group's events go. */
    fun target(group: String): Long? {
        val area = areaOf(group)
        val t = if (perArea && area != null) areaLogs[area] ?: defaultLog else defaultLog
        return t.takeIf { it in logCalendars }
    }

    private val colorCache = HashMap<String, List<Pair<String, Int>>>()

    fun colorsFor(ctx: Context, calendarId: Long): List<Pair<String, Int>> {
        val cal = calendars[calendarId] ?: return emptyList()
        return colorCache.getOrPut(cal.accountType + "\u0000" + cal.account) {
            try {
                CalendarAccess.eventColors(ctx, cal.account, cal.accountType)
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** The event color nearest the group's area calendar color, from the target calendar's account; null for none. */
    fun colorKey(ctx: Context, group: String, target: Long): String? {
        val area = areaOf(group) ?: return null
        val color = calendars[area]?.color ?: return null
        return EventColors.nearest(color, colorsFor(ctx, target))
    }
}

/**
 * Writes the log into the calendar: one event per finished run of five minutes or more, on the
 * phone's calendar store, which Google's sync uploads. Same protocol as the Obsidian export (dirty
 * days with change counters, a debounced job, nothing cleared until written). Never updates or
 * deletes an event that [CalendarOwnership] doesn't say is the app's own. All on background threads.
 */
object CalendarExport {
    private const val SCHEDULE_GAP_MS = 5_000L
    private const val MAX_PASSES = 20
    private const val HOUR = 3_600_000L

    private val lock = Any()
    private val writing = AtomicBoolean(false)
    @Volatile private var lastScheduled = 0L
    @Volatile private var lastAttempted: Map<String, Int> = emptyMap()

    val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** What's running right now, for the settings screen; blank when nothing is. */
    @Volatile var busy: String = ""
        private set

    fun configured(ctx: Context): Boolean = Prefs.calOn(ctx) && Prefs.calLog(ctx) > 0L && CalendarAccess.granted(ctx)

    private fun notifyListeners() = listeners.forEach { it() }

    /** The Store calls this when it marked days dirty. Never throws. */
    fun changed(ctx: Context) {
        try {
            if (!configured(ctx)) return
            if (writing.get() || CalendarJob.active) return
            val now = System.currentTimeMillis()
            if (now - lastScheduled < SCHEDULE_GAP_MS) return
            lastScheduled = now
            CalendarJob.schedule(ctx.applicationContext)
        } catch (e: Exception) {
            // The days are marked in the database; the next app open writes them.
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is SecurityException -> "Calendar access is off. Turn it on in the Calendar settings."
        else -> (e.message ?: e.javaClass.simpleName)
    }

    /** Runs [block] on its own thread, one at a time, recording any failure. */
    fun task(ctx: Context, label: String, block: (Context) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            synchronized(lock) {
                busy = label
                notifyListeners()
                try {
                    block(app)
                } catch (e: Throwable) {
                    Prefs.setCalError(app, describe(e))
                } finally {
                    busy = ""
                    notifyListeners()
                }
            }
        }.start()
    }

    fun onAppOpen(ctx: Context) {
        if (!configured(ctx)) return
        task(ctx, "Updating the calendar…") { app ->
            Store.markDaysDirty(app, Store.CALENDAR, listOf(Fmt.today()))
            writeDirty(app)
        }
    }

    /** Turning the export on: every day with something logged is written (imported-only days are skipped). */
    fun start(ctx: Context) = task(ctx, "Writing all days…") { app ->
        Store.markDaysDirty(app, Store.CALENDAR, Store.activeDays(app, includeImported = false))
        writeDirty(app)
    }

    fun writeNow(ctx: Context) = task(ctx, "Writing…") { app -> writeDirty(app) }

    fun runJob(ctx: Context) {
        synchronized(lock) {
            if (!configured(ctx)) return
            busy = "Writing events…"
            notifyListeners()
            try {
                writeDirty(ctx)
            } catch (e: Throwable) {
                Prefs.setCalError(ctx, describe(e))
            } finally {
                busy = ""
                notifyListeners()
            }
        }
    }

    fun scheduleIfPending(ctx: Context) {
        try {
            if (configured(ctx) && pendingNow(ctx)) CalendarJob.schedule(ctx.applicationContext)
        } catch (e: Exception) {
            // The days stay dirty; the next app open writes them.
        }
    }

    private fun pendingNow(ctx: Context): Boolean =
        ExportProtocol.pending(Store.dirtyDays(ctx, Store.CALENDAR), lastAttempted).isNotEmpty()

    private fun writeDirty(ctx: Context) {
        if (!configured(ctx)) return
        writing.set(true)
        val attempted = HashMap<String, Int>()
        try {
            var pass = 0
            while (pass++ < MAX_PASSES) {
                val dirty = Store.dirtyDays(ctx, Store.CALENDAR)
                val keys = ExportProtocol.pending(dirty, attempted)
                if (keys.isEmpty()) break
                keys.forEach { attempted[it] = dirty.getValue(it) }
                lastAttempted = HashMap(attempted)
                if (!write(ctx, keys)) break
            }
        } finally {
            lastAttempted = HashMap(attempted)
            writing.set(false)
        }
        if (!CalendarJob.active && pendingNow(ctx)) CalendarJob.schedule(ctx.applicationContext)
    }

    /** Writes these days. False when the whole run had to stop (no access, a log calendar missing). */
    private fun write(ctx: Context, keys: List<String>): Boolean {
        val setup = CalSetup(ctx)
        val problem = setup.problem
        if (problem != null) {
            Prefs.setCalError(ctx, problem)
            notifyListeners()
            return false
        }
        val now = System.currentTimeMillis()
        val lk = ObsidianExport.Lookups(ctx)
        val tz = TimeZone.getDefault().id
        val errors = ArrayList<String>()
        var changed = 0
        for (key in keys.distinct()) {
            val ymd = ObsidianExport.parseKey(key)
            val dayStart = ymd?.let { DiaryDay.startOf(it.first, it.second, it.third) }
            if (ymd == null || dayStart == null || dayStart > now) {
                Store.clearDirtyIf(ctx, key, Store.dayGen(ctx, key, Store.CALENDAR), Store.CALENDAR)
                continue
            }
            try {
                val n = writeDay(ctx, setup, lk, key, dayStart, now, tz, errors)
                changed += n
            } catch (e: SecurityException) {
                errors.add(describe(e))
                break
            } catch (e: Exception) {
                errors.add(key + ": " + describe(e))
            }
        }
        if (changed > 0) Prefs.setCalLastExport(ctx, now)
        Prefs.setCalError(ctx, errors.take(3).joinToString("\n") + if (errors.size > 3) "\n…and ${errors.size - 3} more" else "")
        notifyListeners()
        return true
    }

    /** One day: read the change counter, work out the events, apply the plan, clear only if all of it worked. */
    private fun writeDay(
        ctx: Context,
        setup: CalSetup,
        lk: ObsidianExport.Lookups,
        key: String,
        dayStart: Long,
        now: Long,
        tz: String,
        errors: MutableList<String>,
    ): Int {
        val gen = Store.dayGen(ctx, key, Store.CALENDAR)
        val data = ObsidianExport.dayData(ctx, lk, dayStart, now)
        // Days with only Daylio check-ins have no spans, so they never get events.
        val desired = CalendarDay.events(data).mapNotNull { e ->
            val target = setup.target(e.group) ?: return@mapNotNull null
            DesiredEvent(e.key, target, e.title, e.description, e.from, e.to, setup.colorKey(ctx, e.group, target), tz)
        }
        val records = Store.calRecords(ctx, key)
        if (desired.isEmpty() && records.isEmpty()) {
            Store.clearDirtyIf(ctx, key, gen, Store.CALENDAR)
            return 0
        }
        val seen = CalendarAccess.read(ctx, records.map { it.eventId })
        val byKey = records.associateBy { it.key }
        // Only look for unrecorded events of ours when some event has no record that holds up.
        val unplaced = desired.filter { d ->
            val r = byKey[d.key]
            r == null || r.calendarId != d.calendarId ||
                CalendarOwnership.check(r, seen[r.eventId], setup.logCalendars) != CalendarOwnership.Verdict.OURS
        }
        val found = HashMap<String, SeenEvent>()
        val dayEnd = DiaryDay.add(dayStart, 1)
        unplaced.map { it.calendarId }.distinct().forEach { cal ->
            CalendarAccess.findMarked(ctx, cal, dayStart - HOUR, dayEnd + HOUR).forEach { s ->
                val k = CalendarDay.markerOf(s.description)
                if (k != null && k !in found) found[k] = s
            }
        }
        val actions = CalendarPlan.plan(desired, records, seen, found, setup.logCalendars)
        var ok = true
        var n = 0
        actions.forEach { a ->
            try {
                when (a) {
                    is CalendarPlan.Action.Insert -> {
                        // A crash between these two leaves an event with no record; its marker lets the next run adopt it.
                        val (id, written) = withColorFallback(a.event, errors, key) { CalendarAccess.insert(ctx, it) }
                        Store.saveCalRecord(ctx, CalRecord(a.event.key, a.event.calendarId, id, written.hash))
                        n++
                    }
                    is CalendarPlan.Action.Update -> {
                        val (_, written) = withColorFallback(a.event, errors, key) { CalendarAccess.update(ctx, a.eventId, it) }
                        Store.saveCalRecord(ctx, CalRecord(a.event.key, a.event.calendarId, a.eventId, written.hash))
                        n++
                    }
                    is CalendarPlan.Action.Adopt -> {
                        // Record first (with no hash): if the update is cut short, the next run updates it again.
                        Store.saveCalRecord(ctx, CalRecord(a.event.key, a.event.calendarId, a.eventId, ""))
                        val (_, written) = withColorFallback(a.event, errors, key) { CalendarAccess.update(ctx, a.eventId, it) }
                        Store.saveCalRecord(ctx, CalRecord(a.event.key, a.event.calendarId, a.eventId, written.hash))
                        n++
                    }
                    is CalendarPlan.Action.Delete -> {
                        CalendarAccess.delete(ctx, a.record.eventId)
                        Store.dropCalRecord(ctx, a.record.key)
                        n++
                    }
                    is CalendarPlan.Action.Forget -> Store.dropCalRecord(ctx, a.record.key)
                    is CalendarPlan.Action.Leave -> Store.leaveCalRecord(ctx, a.record)
                    is CalendarPlan.Action.Keep -> Unit
                }
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                ok = false
                errors.add(key + ": " + describe(e))
            }
        }
        if (ok) Store.clearDirtyIf(ctx, key, gen, Store.CALENDAR)
        return n
    }

    /**
     * Android refuses a color key the account doesn't have (IllegalArgumentException). Then the
     * event is written without a color, and that's what gets recorded, so it's tried again next time.
     */
    private fun <T> withColorFallback(d: DesiredEvent, errors: MutableList<String>, key: String, write: (DesiredEvent) -> T): Pair<T, DesiredEvent> {
        if (d.colorKey == null) return write(d) to d
        return try {
            write(d) to d
        } catch (e: IllegalArgumentException) {
            val plain = d.copy(colorKey = null)
            val r = write(plain)
            errors.add("$key: color ${d.colorKey} was refused, so an event has no color (" + describe(e) + ")")
            r to plain
        }
    }

    // --- Test event ---

    private const val TEST_MS = 5L * 60_000L

    /**
     * Writes one 5-minute test event ending now into the log calendar, with a color key, reads it
     * back and says in plain words what happened. Its record lets "Delete test event" remove it.
     */
    fun test(ctx: Context) = task(ctx, "Testing the calendar…") { app ->
        val out = ArrayList<String>()
        val setup = CalSetup(app)
        val cal = setup.defaultLog
        val info = setup.calendars[cal]
        if (cal <= 0L || info == null) {
            Prefs.setCalTest(app, Fmt.day(System.currentTimeMillis()) + ": no log calendar chosen.")
            return@task
        }
        if (!info.writable) {
            Prefs.setCalTest(app, "The app can't add events to ${info.label}.")
            return@task
        }
        val colors = setup.colorsFor(app, cal)
        val areaColor = setup.areas.values.filterNotNull().firstNotNullOfOrNull { setup.calendars[it]?.color }
        // An area's color if there is one; otherwise the color least like the calendar's own, so it shows.
        val colorKey = when {
            colors.isEmpty() -> null
            areaColor != null -> EventColors.nearest(areaColor, colors)
            else -> colors.maxByOrNull { EventColors.distance(it.second, info.color) }?.first
        }
        val end = System.currentTimeMillis() / 60_000L * 60_000L
        val key = "test/$end"
        val d = DesiredEvent(
            key, cal, "Now: test event",
            "A test from the Now app. Delete it from the app (Calendar → Delete test event) or here.\n\n" + CalendarDay.marker(key),
            end - TEST_MS, end, colorKey, TimeZone.getDefault().id,
        )
        val id = CalendarAccess.insert(app, d)
        Store.saveTestRecord(app, CalRecord(key, cal, id, ""))
        val back = CalendarAccess.readBack(app, id)
        out.add("Inserted event $id into ${info.label} (${info.account}).")
        if (info.local) out.add("That calendar is only on this phone, so it will never reach Google.")
        if (!info.synced) out.add("That calendar isn't set to sync on this phone, so it may not reach Google.")
        out.add(
            if (colors.isEmpty()) "The account has no event colors, so the event has no color of its own."
            else "The account has ${colors.size} event colors. Used color key $colorKey."
        )
        when {
            back == null -> out.add("Reading it back found nothing.")
            back.deleted -> out.add("Reading it back: it's marked deleted.")
            else -> out.add(
                "Read back: \"${back.title}\" in calendar ${back.calendarId}, color key " +
                    (back.colorKey ?: "none") + (back.color?.let { ", color " + String.format("#%06X", it and 0xFFFFFF) } ?: "") + "."
            )
        }
        out.add("In a minute, check calendar.google.com for \"Now: test event\" ending at " + Fmt.time(app, end) + ", and that it has the color.")
        Prefs.setCalTest(app, Fmt.day(end) + ", " + Fmt.time(app, end) + "\n" + out.joinToString("\n"))
    }

    fun deleteTest(ctx: Context) = task(ctx, "Deleting the test event…") { app ->
        val r = Store.testRecord(app)
        if (r == null) {
            Prefs.setCalTest(app, "No test event to delete.")
            return@task
        }
        val setup = CalSetup(app)
        val seen = CalendarAccess.read(app, listOf(r.eventId))[r.eventId]
        val msg = when (val v = CalendarOwnership.check(r, seen, setup.logCalendars)) {
            CalendarOwnership.Verdict.OURS -> {
                CalendarAccess.delete(app, r.eventId)
                Store.saveTestRecord(app, null)
                "Test event deleted."
            }
            CalendarOwnership.Verdict.GONE -> {
                Store.saveTestRecord(app, null)
                "The test event was already gone."
            }
            else -> "Left the test event alone: " + why(v) + " Delete it in Google Calendar if you like."
        }
        Prefs.setCalTest(app, msg)
    }

    fun why(v: CalendarOwnership.Verdict): String = when (v) {
        CalendarOwnership.Verdict.OURS -> "it's the app's own."
        CalendarOwnership.Verdict.NO_RECORD -> "the app has no record of it."
        CalendarOwnership.Verdict.GONE -> "it's gone."
        CalendarOwnership.Verdict.ID_MISMATCH -> "its id now belongs to another event."
        CalendarOwnership.Verdict.WRONG_CALENDAR -> "it's in a different calendar now."
        CalendarOwnership.Verdict.NOT_LOG_CALENDAR -> "its calendar isn't a log calendar any more."
        CalendarOwnership.Verdict.NO_MARKER -> "its description was changed (the now-log line is gone)."
        CalendarOwnership.Verdict.OTHER_MARKER -> "its now-log line is for another event."
    }

    // --- Old log calendars ---

    /** Calendars the app wrote log events to that aren't log calendars now, with how many it recorded there. */
    fun oldCalendars(ctx: Context, logCalendars: Set<Long>): Map<Long, Int> =
        (Store.allCalRecords(ctx) + Store.leftCalRecords(ctx)).filter { it.calendarId !in logCalendars }
            .groupingBy { it.calendarId }.eachCount()

    /**
     * Deletes the app's own events from a calendar that is no longer a log calendar. Each one must
     * pass [CalendarOwnership.check] with that calendar allowed (the app wrote there when it was a
     * log calendar, and the record says so); anything else is left alone and only its record goes.
     */
    fun removeFrom(ctx: Context, calendarId: Long, label: String) = task(ctx, "Removing log events…") { app ->
        val setup = CalSetup(app)
        if (calendarId in setup.chosen) {
            Prefs.setCalInfo(app, "$label is a log calendar now, so nothing was removed.")
            return@task
        }
        val current = Store.allCalRecords(app).filter { it.calendarId == calendarId }
        val left = Store.leftCalRecords(app).filter { it.calendarId == calendarId }
        val seen = CalendarAccess.read(app, (current + left).map { it.eventId })
        var removed = 0
        var alone = 0
        fun one(r: CalRecord, drop: () -> Unit) {
            when (CalendarOwnership.check(r, seen[r.eventId], setOf(calendarId))) {
                CalendarOwnership.Verdict.OURS -> {
                    CalendarAccess.delete(app, r.eventId)
                    removed++
                }
                CalendarOwnership.Verdict.GONE -> Unit
                else -> alone++
            }
            drop()
        }
        current.forEach { r -> one(r) { Store.dropCalRecord(app, r.key) } }
        left.forEach { r -> one(r) { Store.dropLeftRecord(app, r) } }
        // Days whose records went are written again into the log calendar.
        Store.markDaysDirty(app, Store.CALENDAR, current.mapNotNull { r ->
            ObsidianExport.parseKey(CalendarDay.dayOf(r.key))?.let { DiaryDay.startOf(it.first, it.second, it.third) }
        }.distinct())
        Prefs.setCalInfo(
            app,
            "Removed $removed event" + (if (removed == 1) "" else "s") + " from $label." +
                if (alone > 0) " Left $alone alone (changed by hand or not the app's)." else "",
        )
        if (current.isNotEmpty()) writeDirty(app)
    }
}

/**
 * Planned events for the widget's nudge, read off the main thread and kept for a few minutes. The
 * widget asks when it redraws; a stale cache is refreshed in the background, which redraws again
 * only if the answer changed. No alarms of its own.
 */
object PlannedCache {
    private const val FRESH_MS = 10L * 60_000L
    private const val AHEAD_MS = 12L * 3_600_000L

    @Volatile private var events: List<com.jono.nowwidget.core.PlannedEvent> = emptyList()
    @Volatile private var fetchedAt = 0L
    private val fetching = AtomicBoolean(false)

    fun clear() {
        events = emptyList()
        fetchedAt = 0L
    }

    /** What's cached now (maybe empty); starts a refresh when it's old. Cheap: safe on the main thread. */
    fun current(ctx: Context, now: Long): List<com.jono.nowwidget.core.PlannedEvent> {
        if (now - fetchedAt > FRESH_MS || now < fetchedAt) refresh(ctx, now)
        return events
    }

    private fun refresh(ctx: Context, now: Long) {
        if (!fetching.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread {
            try {
                val next = try {
                    if (!Prefs.calNudge(app) || !CalendarAccess.granted(app)) emptyList()
                    else {
                        val setup = CalSetup(app)
                        CalendarAccess.planned(app, setup.areaCalendars, now - AHEAD_MS, now + AHEAD_MS)
                    }
                } catch (e: Exception) {
                    emptyList()
                }
                val before = events
                events = next
                fetchedAt = System.currentTimeMillis()
                if (next != before) WidgetRenderer.refresh(app)
            } catch (e: Throwable) {
                fetchedAt = System.currentTimeMillis()
            } finally {
                fetching.set(false)
            }
        }.start()
    }
}

/** The calendar export's debounce, like [ExportJob]. */
class CalendarJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        active = true
        Thread {
            try {
                CalendarExport.runJob(app)
            } catch (e: Throwable) {
                Prefs.setCalError(app, e.message ?: e.javaClass.simpleName)
            }
            jobFinished(params, false)
            active = false
            CalendarExport.scheduleIfPending(app)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 4202
        private const val DELAY_MS = 60_000L
        private const val LATEST_MS = 5L * 60_000L

        @Volatile var active = false

        fun schedule(ctx: Context) {
            val scheduler = ctx.getSystemService(JobScheduler::class.java) ?: return
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, CalendarJob::class.java))
                .setMinimumLatency(DELAY_MS)
                .setOverrideDeadline(DELAY_MS + LATEST_MS)
                .build()
            scheduler.schedule(info)
        }
    }
}
