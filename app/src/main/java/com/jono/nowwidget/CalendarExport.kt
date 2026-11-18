package com.jono.nowwidget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.jono.nowwidget.core.CalIdentity
import com.jono.nowwidget.core.CalLedger
import com.jono.nowwidget.core.CalRecord
import com.jono.nowwidget.core.CalRef
import com.jono.nowwidget.core.CalRow
import com.jono.nowwidget.core.CalendarDay
import com.jono.nowwidget.core.CalendarOwnership
import com.jono.nowwidget.core.CalendarPlan
import com.jono.nowwidget.core.CalendarSetup
import com.jono.nowwidget.core.CalendarSync
import com.jono.nowwidget.core.DesiredEvent
import com.jono.nowwidget.core.DiaryDay
import com.jono.nowwidget.core.EventColors
import com.jono.nowwidget.core.ExportProtocol
import com.jono.nowwidget.core.PlannedEvent
import com.jono.nowwidget.core.ResolvedSetup
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The calendar settings as one run sees them, resolved against the calendars on the phone by
 * identity (see [CalendarSetup]). Reads the calendar store: background threads only.
 */
class CalSetup(ctx: Context) {
    val list: List<CalRow> = CalendarAccess.calendars(ctx)
    val areaRefs: Map<String, CalRef?> = Store.groupAreas(ctx)
    val r: ResolvedSetup = CalendarSetup.resolve(list, Prefs.calLog(ctx), Prefs.calPerArea(ctx), areaRefs, Prefs.calAreaLogs(ctx))
    private val refused = HashSet(Prefs.calRefusedColors(ctx))

    val calendars: Map<Long, CalRow> get() = r.rows
    val problem: String? get() = r.problem
    val logCalendars: Set<Long> get() = r.logCalendars
    val chosen: Set<Long> get() = r.chosen
    val defaultLog: Long get() = r.defaultLog
    val areaCalendars: Set<Long> get() = r.areaCalendars

    fun areaOf(group: String): Long? = r.areaOf(group)
    fun target(group: String): Long? = r.target(group)

    fun accountKey(calendarId: Long): String? = calendars[calendarId]?.let { it.accountType + CalIdentity.SEP + it.account }

    private val colorCache = HashMap<String, List<Pair<String, Int>>>()

    /** The account's event colors, minus any key it refused before. */
    fun colorsFor(ctx: Context, calendarId: Long): List<Pair<String, Int>> {
        val cal = calendars[calendarId] ?: return emptyList()
        val acct = accountKey(calendarId) ?: return emptyList()
        val all = colorCache.getOrPut(acct) {
            try {
                CalendarAccess.eventColors(ctx, cal.account, cal.accountType)
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
        return all.filter { (acct + CalRef.SEP_PAIR + it.first) !in refused }
    }

    /** The event color nearest the group's area calendar color, from the target calendar's account; null for none. */
    fun colorKey(ctx: Context, group: String, target: Long): String? {
        val area = areaOf(group) ?: return null
        val color = calendars[area]?.color ?: return null
        return EventColors.nearest(color, colorsFor(ctx, target))
    }

    /** Remembers a refused key so it isn't tried again (this run or later ones). */
    fun refuse(ctx: Context, d: DesiredEvent) {
        val acct = accountKey(d.calendarId) ?: return
        val k = d.colorKey ?: return
        refused.add(acct + CalRef.SEP_PAIR + k)
        Prefs.addCalRefusedColor(ctx, acct, k)
    }
}

/** The Store's calendar records as [CalendarSync] sees them. */
class StoreCalLedger(private val ctx: Context) : CalLedger {
    override fun gen(day: String) = Store.dayGen(ctx, day, Store.CALENDAR)
    override fun clearIfUnchanged(day: String, gen: Int) = Store.clearDirtyIf(ctx, day, gen, Store.CALENDAR)
    override fun records(day: String) = Store.calRecords(ctx, day)
    override fun released(day: String) = Store.releasedKeys(ctx, day)
    override fun save(r: CalRecord) = Store.saveCalRecord(ctx, r)
    override fun drop(key: String) = Store.dropCalRecord(ctx, key)
    override fun leave(r: CalRecord) = Store.leaveCalRecord(ctx, r)
    override fun release(r: CalRecord, why: CalendarOwnership.Verdict) = Store.releaseCalRecord(ctx, r, why.name)
}

/**
 * Writes the log into the calendar: one event per finished run of five minutes or more, on the
 * phone's calendar store, which Google's sync uploads. Same protocol as the Obsidian export (dirty
 * days with change counters, a debounced job, nothing cleared until written). The decisions are in
 * [CalendarSync] and [CalendarPlan]; nothing here updates or deletes an event that
 * [CalendarOwnership] doesn't say is the app's own. All on background threads.
 */
object CalendarExport {
    private const val SCHEDULE_GAP_MS = 5_000L
    private const val MAX_PASSES = 20

    private val lock = Any()
    private val writing = AtomicBoolean(false)
    @Volatile private var lastScheduled = 0L
    @Volatile private var lastAttempted: Map<String, Int> = emptyMap()

    val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** What's running right now, for the settings screen; blank when nothing is. */
    @Volatile var busy: String = ""
        private set

    fun configured(ctx: Context): Boolean = Prefs.calOn(ctx) && Prefs.calLog(ctx) != null && CalendarAccess.granted(ctx)

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

    /** Turning the export on: every day with something logged is written (imported-only days have no events). */
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
        var recheck = false
        try {
            var pass = 0
            while (pass++ < MAX_PASSES) {
                val dirty = Store.dirtyDays(ctx, Store.CALENDAR)
                val keys = ExportProtocol.pending(dirty, attempted)
                if (keys.isEmpty()) break
                keys.forEach { attempted[it] = dirty.getValue(it) }
                lastAttempted = HashMap(attempted)
                val r = write(ctx, keys) ?: break
                if (r) recheck = true
            }
        } finally {
            lastAttempted = HashMap(attempted)
            writing.set(false)
        }
        if (!CalendarJob.active && pendingNow(ctx)) CalendarJob.schedule(ctx.applicationContext)
        // An event read as gone is looked at again after the wait, before anything replaces it.
        if (recheck) CalendarJob.scheduleRecheck(ctx.applicationContext, CalendarPlan.GONE_WAIT_MS + 60_000L)
    }

    /**
     * Writes these days. Null when the whole run had to stop (a log calendar missing, unsynced or
     * swapped: nothing is written then); otherwise whether some day is waiting on a gone event.
     */
    private fun write(ctx: Context, keys: List<String>): Boolean? {
        val setup = CalSetup(ctx)
        val problem = setup.problem
        if (problem != null) {
            Prefs.setCalError(ctx, problem)
            notifyListeners()
            return null
        }
        val now = System.currentTimeMillis()
        val lk = ObsidianExport.Lookups(ctx)
        val tz = TimeZone.getDefault().id
        val provider = RealCalProvider(ctx)
        val ledger = StoreCalLedger(ctx)
        val errors = ArrayList<String>()
        var changed = 0
        var waiting = false
        for (key in keys.distinct()) {
            val ymd = ObsidianExport.parseKey(key)
            val dayStart = ymd?.let { DiaryDay.startOf(it.first, it.second, it.third) }
            if (ymd == null || dayStart == null || dayStart > now) {
                Store.clearDirtyIf(ctx, key, Store.dayGen(ctx, key, Store.CALENDAR), Store.CALENDAR)
                continue
            }
            try {
                val data = ObsidianExport.dayData(ctx, lk, dayStart, now)
                // Days with only Daylio check-ins have no spans, so they never get events.
                val desired = CalendarDay.events(data).mapNotNull { e ->
                    val target = setup.target(e.group) ?: return@mapNotNull null
                    DesiredEvent(e.key, target, e.title, e.description, e.from, e.to, setup.colorKey(ctx, e.group, target), tz)
                }
                val r = CalendarSync.writeDay(provider, ledger, key, dayStart, DiaryDay.add(dayStart, 1), desired, setup.logCalendars, now, errors) { d ->
                    setup.refuse(ctx, d)
                    errors.add("$key: the calendar refused color ${d.colorKey}, so that event has no color")
                }
                changed += r.changed
                if (r.actions.any { it is CalendarPlan.Action.Suspect || (it is CalendarPlan.Action.Keep && it.record.suspectSince != null) }) waiting = true
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
        return waiting
    }

    // --- Test events ---

    private const val TEST_MS = 5L * 60_000L

    /**
     * Writes one 5-minute test event ending now into the log calendar, with a color key, reads it
     * back and says in plain words what happened. Every test event is recorded, so "Delete test
     * events" can remove them all.
     */
    fun test(ctx: Context) = task(ctx, "Testing the calendar…") { app ->
        val out = ArrayList<String>()
        val setup = CalSetup(app)
        val cal = setup.defaultLog
        val info = setup.calendars[cal]
        if (info == null) {
            Prefs.setCalTest(app, setup.problem ?: "No log calendar chosen.")
            return@task
        }
        if (!info.writable) {
            Prefs.setCalTest(app, "The app can't add events to ${info.label}.")
            return@task
        }
        val colors = setup.colorsFor(app, cal)
        val areaColor = setup.areaCalendars.firstNotNullOfOrNull { setup.calendars[it]?.color }
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
            "A test from the Now app. Delete it from the app (Calendar → Delete test events) or here.\n\n" + CalendarDay.marker(key),
            end - TEST_MS, end, colorKey, TimeZone.getDefault().id,
        )
        val (id, written) = CalendarSync.withColorFallback(d, { setup.refuse(app, it) }) { CalendarAccess.insert(app, it) }
        Store.addTestRecord(app, CalRecord(key, cal, id, ""))
        val back = CalendarAccess.readBack(app, id)
        out.add("Inserted event $id into ${info.label} (${info.account}).")
        if (info.local) out.add("That calendar is only on this phone, so it will never reach Google.")
        if (!info.synced) out.add("That calendar isn't set to sync on this phone, so it won't reach Google.")
        out.add(
            when {
                colors.isEmpty() -> "The account has no event colors, so the event has no color of its own."
                written.colorKey == null -> "The account lists ${colors.size} event colors, but refused key $colorKey, so the event has no color."
                else -> "The account has ${colors.size} event colors. Used color key $colorKey."
            }
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

    fun deleteTests(ctx: Context) = task(ctx, "Deleting test events…") { app ->
        val tests = Store.testRecords(app)
        if (tests.isEmpty()) {
            Prefs.setCalTest(app, "No test events to delete.")
            return@task
        }
        val setup = CalSetup(app)
        val r = CalendarSync.deleteTests(RealCalProvider(app), tests, setup.chosen) { Store.dropTestRecord(app, it.key) }
        val parts = ArrayList<String>()
        parts.add("Deleted ${r.deleted} test event" + (if (r.deleted == 1) "" else "s") + ".")
        if (r.gone > 0) parts.add("${r.gone} already gone.")
        if (r.leftAlone.isNotEmpty()) {
            parts.add("Left ${r.leftAlone.size} alone: " + r.leftAlone.distinct().joinToString(" ") { why(it) } + " Delete them in Google Calendar if you like.")
        }
        Prefs.setCalTest(app, parts.joinToString(" "))
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

    /**
     * Calendars the app wrote log events to that aren't log calendars now, with how many it recorded
     * there. Empty while the settings have a problem (then it can't tell which calendars are current).
     */
    fun oldCalendars(ctx: Context, setup: ResolvedSetup?): Map<Long, Int> {
        if (setup == null || setup.problem != null) return emptyMap()
        return (Store.allCalRecords(ctx) + Store.leftCalRecords(ctx)).filter { it.calendarId !in setup.chosen }
            .groupingBy { it.calendarId }.eachCount()
    }

    /** Deletes the app's own events from a calendar that's no longer a log calendar (see [CalendarSync.removeFrom]). */
    fun removeFrom(ctx: Context, calendarId: Long, label: String) = task(ctx, "Removing log events…") { app ->
        val setup = CalSetup(app)
        if (setup.problem != null || calendarId in setup.chosen) {
            Prefs.setCalInfo(app, setup.problem ?: "$label is a log calendar now, so nothing was removed.")
            return@task
        }
        val p = RealCalProvider(app)
        val dropped = ArrayList<CalRecord>()
        val a = CalendarSync.removeFrom(p, Store.allCalRecords(app), calendarId) {
            Store.dropCalRecord(app, it.key)
            dropped.add(it)
        }
        val b = CalendarSync.removeFrom(p, Store.leftCalRecords(app), calendarId) { Store.dropLeftRecord(app, it) }
        // Days whose records went are written again into the log calendar.
        Store.markDaysDirty(app, Store.CALENDAR, dropped.mapNotNull { r ->
            ObsidianExport.parseKey(CalendarDay.dayOf(r.key))?.let { DiaryDay.startOf(it.first, it.second, it.third) }
        }.distinct())
        val removed = a.removed + b.removed
        val alone = a.leftAlone + b.leftAlone
        val failed = a.failed + b.failed
        Prefs.setCalInfo(
            app,
            "Removed $removed event" + (if (removed == 1) "" else "s") + " from $label." +
                (if (alone > 0) " Left $alone alone (changed by hand or not the app's)." else "") +
                (if (failed > 0) " $failed couldn't be deleted; try again." else ""),
        )
        if (dropped.isNotEmpty()) writeDirty(app)
    }
}

/**
 * Planned events for the widget's nudge, read off the main thread and kept for a few minutes. The
 * widget asks when it redraws; a stale cache is refreshed in the background, which redraws again
 * only if the answer changed. After each refresh a one-off job is set for the next start or end of
 * a planned event, so the first button changes on time (no alarms).
 */
object PlannedCache {
    private const val FRESH_MS = 10L * 60_000L
    private const val AHEAD_MS = 12L * 3_600_000L

    @Volatile private var events: List<PlannedEvent> = emptyList()
    /** Group to its area calendar id, resolved with the events (the widget can't read the calendar store itself). */
    @Volatile var areas: Map<String, Long?> = emptyMap()
        private set
    @Volatile private var fetchedAt = 0L
    private val fetching = AtomicBoolean(false)

    fun clear() {
        events = emptyList()
        fetchedAt = 0L
    }

    /** What's cached now (maybe empty); starts a refresh when it's old. Cheap: safe on the main thread. */
    fun current(ctx: Context, now: Long): List<PlannedEvent> {
        if (now - fetchedAt > FRESH_MS || now < fetchedAt) refresh(ctx, now)
        return events
    }

    /** Sets the redraw job for the next start or end of a cached planned event. */
    fun scheduleNext(ctx: Context) {
        try {
            val now = System.currentTimeMillis()
            val next = CalendarSync.nextBoundary(events, now) ?: return
            NudgeJob.schedule(ctx.applicationContext, next - now)
        } catch (e: Exception) {
            // The widget's own refresh still catches up.
        }
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
                        areas = setup.areaRefs.keys.associateWith { setup.areaOf(it) }
                        CalendarAccess.planned(app, setup.areaCalendars, now - AHEAD_MS, now + AHEAD_MS)
                    }
                } catch (e: Exception) {
                    emptyList()
                }
                val before = events
                events = next
                fetchedAt = System.currentTimeMillis()
                scheduleNext(app)
                if (next != before) WidgetRenderer.refresh(app)
            } catch (e: Throwable) {
                fetchedAt = System.currentTimeMillis()
            } finally {
                fetching.set(false)
            }
        }.start()
    }
}

/** Redraws the widget when a planned event starts or ends, so its activity moves to the first button on time. */
class NudgeJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread {
            try {
                WidgetRenderer.refresh(app)
                PlannedCache.scheduleNext(app)
            } catch (e: Throwable) {
                // Nothing to record: the next redraw catches up.
            }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 4203
        private const val SLACK_MS = 60_000L

        fun schedule(ctx: Context, inMs: Long) {
            val scheduler = ctx.getSystemService(JobScheduler::class.java) ?: return
            val delay = maxOf(0L, inMs) + 5_000L
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, NudgeJob::class.java))
                .setMinimumLatency(delay)
                .setOverrideDeadline(delay + SLACK_MS)
                .build()
            scheduler.schedule(info)
        }
    }
}

/** The calendar export's debounce, like [ExportJob]; a second id is the later re-check of a gone event. */
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
        private const val RECHECK_ID = 4204
        private const val DELAY_MS = 60_000L
        private const val LATEST_MS = 5L * 60_000L

        @Volatile var active = false

        private fun schedule(ctx: Context, id: Int, delay: Long) {
            val scheduler = ctx.getSystemService(JobScheduler::class.java) ?: return
            val info = JobInfo.Builder(id, ComponentName(ctx, CalendarJob::class.java))
                .setMinimumLatency(delay)
                .setOverrideDeadline(delay + LATEST_MS)
                .build()
            scheduler.schedule(info)
        }

        fun schedule(ctx: Context) = schedule(ctx, JOB_ID, DELAY_MS)

        /** A run after [delay], to look again at events read as gone. */
        fun scheduleRecheck(ctx: Context, delay: Long) = schedule(ctx, RECHECK_ID, delay)
    }
}
