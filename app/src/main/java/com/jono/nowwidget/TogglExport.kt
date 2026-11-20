package com.jono.nowwidget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.jono.nowwidget.core.DiaryDay
import com.jono.nowwidget.core.ExportProtocol
import com.jono.nowwidget.core.CalendarDay
import com.jono.nowwidget.core.RequestBudget
import com.jono.nowwidget.core.StopKind
import com.jono.nowwidget.core.TogglBackoff
import com.jono.nowwidget.core.TogglBucket
import com.jono.nowwidget.core.TogglDay
import com.jono.nowwidget.core.TogglLedger
import com.jono.nowwidget.core.TogglRecord
import com.jono.nowwidget.core.TogglStats
import com.jono.nowwidget.core.TogglStop
import com.jono.nowwidget.core.TogglSync
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/** The Store's Toggl records as [TogglSync] sees them. */
class StoreTogglLedger(private val ctx: Context) : TogglLedger {
    override fun gen(day: String) = Store.dayGen(ctx, day, Store.TOGGL)
    override fun clearIfUnchanged(day: String, gen: Int) = Store.clearDirtyIf(ctx, day, gen, Store.TOGGL)
    override fun records(day: String) = Store.togglRecords(ctx, day)
    override fun released(day: String) = Store.togglReleasedKeys(ctx, day)
    override fun save(r: TogglRecord) = Store.saveTogglRecord(ctx, r)
    override fun drop(key: String) = Store.dropTogglRecord(ctx, key)
    override fun release(r: TogglRecord, why: String) = Store.releaseTogglRecord(ctx, r, why)
}

/**
 * Sends the log to Toggl Track: one time entry per finished run of a minute or more. Same protocol
 * as the other exporters (dirty days with change counters, a debounced job, nothing cleared until
 * sent) with one addition: Toggl's free plan allows 30 requests an hour, so a run sends what the
 * budget allows (oldest days first), stops at the limit, and sets the next attempt for when it
 * resets (or backs off after errors). The decisions are in [TogglSync] and [TogglPlan]; nothing here
 * updates or deletes an entry the app has no record of. Network on background threads only.
 */
object TogglExport {
    private const val DEBOUNCE_MS = 60_000L
    private const val SCHEDULE_GAP_MS = 5_000L
    private const val MAX_PASSES = 20

    /** A run stops cleanly after this long; what's left stays dirty for the next one. */
    private const val MAX_RUN_MS = 8L * 60_000L

    private val lock = Any()
    private val writing = AtomicBoolean(false)
    @Volatile private var lastScheduled = 0L
    @Volatile private var lastAttempted: Map<String, Int> = emptyMap()

    val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** What's running right now, for the settings screen; blank when nothing is. */
    @Volatile var busy: String = ""
        private set

    fun configured(ctx: Context): Boolean =
        Prefs.togglOn(ctx) && Prefs.togglToken(ctx) != null && Prefs.togglWorkspace(ctx) != 0L

    private fun notifyListeners() = listeners.forEach { it() }

    /** The Store calls this when it marked days dirty. Never throws. */
    fun changed(ctx: Context) {
        try {
            if (!configured(ctx) && !Prefs.togglRemoving(ctx)) return
            if (writing.get() || TogglJob.active) return
            val now = System.currentTimeMillis()
            if (now - lastScheduled < SCHEDULE_GAP_MS) return
            lastScheduled = now
            schedule(ctx.applicationContext)
        } catch (e: Exception) {
            // The days are marked in the database; the next app open sends them.
        }
    }

    /** Sets the job for a minute from now, or for the next attempt time when that is later (a back-off or a full quota). */
    fun schedule(ctx: Context) {
        val now = System.currentTimeMillis()
        TogglJob.schedule(ctx, maxOf(DEBOUNCE_MS, Prefs.togglNextAt(ctx) - now))
    }

    /** Sets the next job if something is waiting. Never from inside a running job: its id would be rescheduled under it (the job's end does this). */
    fun scheduleIfPending(ctx: Context) {
        try {
            if (TogglJob.active) return
            if (Prefs.togglFatal(ctx).isNotBlank()) return
            // A back-off or a full quota leaves the next attempt time set; a run that went through clears it.
            val retry = Prefs.togglNextAt(ctx) > 0L
            val more = moreWaiting
            moreWaiting = false
            if (Prefs.togglRemoving(ctx) || (configured(ctx) && (retry || more || pendingNow(ctx)))) schedule(ctx.applicationContext)
        } catch (e: Exception) {
            // The days stay dirty; the next app open sends them.
        }
    }

    private fun pendingNow(ctx: Context): Boolean =
        ExportProtocol.pending(Store.dirtyDays(ctx, Store.TOGGL), lastAttempted).isNotEmpty()

    /** Runs [block] on its own thread, one at a time, recording any failure. */
    private fun task(ctx: Context, label: String, block: (Context) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            synchronized(lock) {
                busy = label
                notifyListeners()
                try {
                    block(app)
                } catch (e: Throwable) {
                    Prefs.setTogglError(app, e.message ?: e.javaClass.simpleName)
                } finally {
                    busy = ""
                    notifyListeners()
                }
            }
        }.start()
    }

    fun onAppOpen(ctx: Context) {
        if (!configured(ctx) && !Prefs.togglRemoving(ctx)) return
        if (Prefs.togglFatal(ctx).isNotBlank()) return
        task(ctx, "Sending to Toggl…") { app ->
            if (!Prefs.togglRemoving(app)) Store.markDaysDirty(app, Store.TOGGL, listOf(Fmt.today()))
            run(app, manual = false)
        }
    }

    /** Turning sending on: the days from the chosen date with something logged are looked at (imported-only days have no entries). */
    fun start(ctx: Context) = task(ctx, "Sending to Toggl…") { app ->
        Prefs.setTogglRemoving(app, false)
        Prefs.setTogglFatal(app, "")
        Prefs.setTogglError(app, "")
        Prefs.setTogglNextAt(app, 0L)
        Prefs.setTogglFailures(app, 0)
        Prefs.clearTogglProjectRefusals(app)
        Store.markDaysDirty(app, Store.TOGGL, daysToSend(app))
        run(app, manual = true)
    }

    /** "Send now": tries again right away, even after a back-off or a refused token; the hourly budget still holds. */
    fun sendNow(ctx: Context) = task(ctx, "Sending to Toggl…") { app ->
        Prefs.clearTogglProjectRefusals(app)
        if (Prefs.togglRemoving(app) || configured(app)) run(app, manual = true)
    }

    /** The "Send past days" choice changed: the days it now covers are looked at. */
    fun rangeChanged(ctx: Context) {
        if (!configured(ctx)) return
        task(ctx, "Sending to Toggl…") { app ->
            Store.markDaysDirty(app, Store.TOGGL, daysToSend(app))
            run(app, manual = false)
        }
    }

    fun stop(ctx: Context) {
        Prefs.setTogglOn(ctx, false)
        try {
            ctx.getSystemService(JobScheduler::class.java)?.cancel(TogglJob.JOB_ID)
        } catch (e: Exception) {
            // The job finds sending off and does nothing.
        }
        notifyListeners()
    }

    /** The first day to send: the chosen date, 0 for everything, or today when none was chosen yet. */
    private fun fromMs(ctx: Context): Long = Prefs.togglFrom(ctx).let { if (it < 0L) Fmt.today() else it }

    /** Start of every diary day with something logged, from the chosen date. */
    private fun daysToSend(ctx: Context): List<Long> {
        val from = fromMs(ctx)
        return Store.activeDays(ctx, includeImported = false).filter { from <= 0L || it >= from }
    }

    /** How many entries and days sending would cover, for the question before turning it on. */
    fun estimate(ctx: Context, onDone: (days: Int, entries: Int) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var days = 0
            var entries = 0
            try {
                val lk = ObsidianExport.Lookups(app)
                val now = System.currentTimeMillis()
                daysToSend(app).forEach { start ->
                    val n = TogglDay.entries(ObsidianExport.dayData(app, lk, start, now), 1L, true, false).size
                    if (n > 0) {
                        days++
                        entries += n
                    }
                }
            } catch (e: Exception) {
                // An estimate is only a guide.
            }
            onDone(days, entries)
        }.start()
    }

    /** Called from the job. */
    fun runJob(ctx: Context) {
        synchronized(lock) {
            if (!configured(ctx) && !Prefs.togglRemoving(ctx)) return
            busy = "Sending to Toggl…"
            notifyListeners()
            try {
                run(ctx, manual = false)
            } catch (e: Throwable) {
                Prefs.setTogglError(ctx, e.message ?: e.javaClass.simpleName)
            } finally {
                busy = ""
                notifyListeners()
            }
        }
    }

    private fun run(ctx: Context, manual: Boolean) {
        val now = System.currentTimeMillis()
        if (manual) {
            Prefs.setTogglFatal(ctx, "")
            Prefs.setTogglNextAt(ctx, 0L)
        } else {
            if (Prefs.togglFatal(ctx).isNotBlank()) return
            if (now < Prefs.togglNextAt(ctx)) {
                scheduleIfPending(ctx)
                return
            }
        }
        if (Prefs.togglRemoving(ctx)) removeStep(ctx) else if (configured(ctx)) sendStep(ctx)
    }

    private fun describe(e: Throwable): String = e.message ?: e.javaClass.simpleName

    /** Records what a stop means for the next attempt, in the preferences; returns the status words. */
    private fun afterStop(ctx: Context, stop: TogglStop, now: Long): String {
        val failures = Prefs.togglFailures(ctx) + (if (stop.kind == StopKind.QUOTA) 0 else 1)
        val delay = TogglBackoff.delayMs(stop, failures)
        if (delay == null) {
            Prefs.setTogglFatal(ctx, stop.message ?: stop.kind.name)
            Prefs.setTogglNextAt(ctx, 0L)
        } else {
            Prefs.setTogglFailures(ctx, failures)
            Prefs.setTogglNextAt(ctx, now + delay)
        }
        return stop.message ?: stop.kind.name
    }

    private fun sendStep(ctx: Context) {
        val token = Prefs.togglToken(ctx) ?: return
        val ws = Prefs.togglWorkspace(ctx)
        val warnings = ArrayList<String>()
        val errors = ArrayList<String>()
        val stats = TogglStats()
        val client = TogglClient.make(ctx, token, warnings)
        val ledger = StoreTogglLedger(ctx)
        val lk = ObsidianExport.Lookups(ctx)
        val notes = Prefs.togglNotes(ctx)
        val checkIns = Prefs.togglCheckIns(ctx)
        val from = fromMs(ctx)
        val began = System.currentTimeMillis()
        val now = began
        val todayStart = DiaryDay.start(now)
        val todayKey = dayKey(todayStart)
        val yesterdayKey = dayKey(DiaryDay.add(todayStart, -1))
        var stop: TogglStop? = null
        var tooLong = false
        val attempted = HashMap<String, Int>()
        writing.set(true)
        try {
            var pass = 0
            outer@ while (pass++ < MAX_PASSES) {
                val dirty = Store.dirtyDays(ctx, Store.TOGGL)
                // Re-sorted every pass: today, then yesterday, then the backlog oldest first.
                val keys = TogglSync.priorityOrder(ExportProtocol.pending(dirty, attempted), todayKey, yesterdayKey)
                if (keys.isEmpty()) break
                lastAttempted = HashMap(attempted)
                for (key in keys) {
                    if (System.currentTimeMillis() - began > MAX_RUN_MS) {
                        tooLong = true
                        break@outer
                    }
                    attempted[key] = dirty.getValue(key)
                    val ymd = ObsidianExport.parseKey(key)
                    val dayStart = ymd?.let { DiaryDay.startOf(it.first, it.second, it.third) }
                    if (ymd == null || dayStart == null || dayStart > now) {
                        Store.clearDirtyIf(ctx, key, dirty.getValue(key), Store.TOGGL)
                        continue
                    }
                    // Before "Send past days": nothing new is sent, but entries already sent keep being kept up to date.
                    val before = from > 0L && dayStart < from
                    if (before && ledger.records(key).isEmpty()) {
                        Store.clearDirtyIf(ctx, key, dirty.getValue(key), Store.TOGGL)
                        continue
                    }
                    try {
                        val data = ObsidianExport.dayData(ctx, lk, dayStart, now)
                        val desired = TogglDay.entries(data, ws, notes, checkIns)
                        TogglSync.writeDay(client, ledger, key, dayStart, DiaryDay.add(dayStart, 1), desired, stats, errors, warnings, System.currentTimeMillis(), allowNew = !before)
                        noteUnresolved(ctx, key, stats)
                    } catch (e: TogglStop) {
                        stop = e
                        break@outer
                    } catch (e: Exception) {
                        errors.add(key + ": " + describe(e))
                    }
                }
            }
        } finally {
            lastAttempted = HashMap(attempted)
            writing.set(false)
        }
        if (stats.requestsWorked > 0) Prefs.setTogglLastSync(ctx, System.currentTimeMillis())
        Prefs.setTogglInfo(ctx, warnings.distinct().take(3).joinToString("\n"))
        val done = System.currentTimeMillis()
        val problem = when {
            stop != null -> afterStop(ctx, stop, done)
            errors.isNotEmpty() -> {
                val failures = Prefs.togglFailures(ctx) + 1
                Prefs.setTogglFailures(ctx, failures)
                Prefs.setTogglNextAt(ctx, done + TogglBackoff.exponential(failures))
                errors.take(3).joinToString("\n") + if (errors.size > 3) "\n…and ${errors.size - 3} more" else ""
            }
            else -> {
                Prefs.setTogglFailures(ctx, 0)
                Prefs.setTogglNextAt(ctx, 0L)
                ""
            }
        }
        Prefs.setTogglError(ctx, problem)
        notifyListeners()
        // Inside the job, the job's end sets the next one (see scheduleIfPending).
        if (!TogglJob.active && Prefs.togglFatal(ctx).isBlank() &&
            (stop != null || errors.isNotEmpty() || tooLong || stats.again || pendingNow(ctx))
        ) schedule(ctx.applicationContext)
        if (tooLong || stats.again) moreWaiting = true
    }

    /** True after a run that left work on purpose (it ran long, or adopted an entry to act on next time): the job's end schedules the next run. */
    @Volatile private var moreWaiting = false

    private fun dayKey(dayStart: Long): String {
        val (y, m, d) = DiaryDay.date(dayStart)
        return com.jono.nowwidget.core.ObsidianDay.key(y, m, d)
    }

    /** The unresolved entries of this day as the run found them (and none of its older ones that are settled now). */
    private fun noteUnresolved(ctx: Context, day: String, stats: TogglStats) {
        val mine = stats.unresolved.filter { CalendarDay.dayOf(it) == day }.toSet()
        val others = Prefs.togglUnresolved(ctx).filter { CalendarDay.dayOf(it) != day }.toSet()
        if (mine.isNotEmpty() || others.size != Prefs.togglUnresolved(ctx).size) Prefs.setTogglUnresolved(ctx, others + mine)
    }

    // --- Removing what was sent ---

    /** The count of entries recorded as sent (pending ones not counted). */
    fun sentCount(ctx: Context): Int = Store.togglSentCount(ctx)

    /**
     * "Remove sent entries from Toggl": sending is turned off and every recorded entry is deleted,
     * by its recorded workspace and id, within the hourly budget; it carries on in later runs.
     */
    fun removeSent(ctx: Context) {
        stop(ctx)
        Prefs.setTogglRemoving(ctx, true)
        Prefs.setTogglRemoved(ctx, 0)
        Prefs.setTogglFatal(ctx, "")
        Prefs.setTogglError(ctx, "")
        Prefs.setTogglNextAt(ctx, 0L)
        Prefs.setTogglFailures(ctx, 0)
        task(ctx, "Removing from Toggl…") { app -> run(app, manual = true) }
    }

    private fun removeStep(ctx: Context) {
        val token = Prefs.togglToken(ctx)
        if (token == null) {
            Prefs.setTogglRemoving(ctx, false)
            Prefs.setTogglError(ctx, "There's no API token, so the sent entries couldn't be removed. Add the token again to remove them.")
            notifyListeners()
            return
        }
        val client = TogglClient.make(ctx, token)
        val began = System.currentTimeMillis()
        val r = TogglSync.removeAll(client, Store.allTogglRecords(ctx), { Store.dropTogglRecord(ctx, it.key) }, { System.currentTimeMillis() - began < MAX_RUN_MS })
        val total = Prefs.togglRemoved(ctx) + r.removed + r.gone
        Prefs.setTogglRemoved(ctx, total)
        val stop = r.stop
        val now = System.currentTimeMillis()
        if (stop != null) {
            Prefs.setTogglError(ctx, afterStop(ctx, stop, now))
            if (!TogglJob.active && Prefs.togglFatal(ctx).isBlank()) schedule(ctx.applicationContext)
        } else if (r.paused) {
            // Ran long: the rest goes in the next run.
            moreWaiting = true
            if (!TogglJob.active) schedule(ctx.applicationContext)
        } else {
            Prefs.setTogglRemoving(ctx, false)
            Prefs.setTogglFailures(ctx, 0)
            Prefs.setTogglNextAt(ctx, 0L)
            Prefs.setTogglError(
                ctx,
                (if (r.failed > 0) "${r.failed} entr" + (if (r.failed == 1) "y" else "ies") + " couldn't be deleted. " else "") +
                    (if (r.unresolved > 0) "${r.unresolved} sent while the connection dropped may still be in Toggl; delete ${if (r.unresolved == 1) "it" else "them"} there (tag now). " else ""),
            )
            Prefs.setTogglInfo(ctx, "Removed $total entr" + (if (total == 1) "y" else "ies") + " from Toggl.")
        }
        notifyListeners()
    }

    // --- Setup calls (a token check and the workspace list) ---

    /** The account a token belongs to: its /me id and workspaces. */
    class Account(val userId: Long, val workspaces: List<Pair<Long, String>>)

    /**
     * Checks [token] with Toggl (the account id and the workspaces: two requests against the /me
     * quota) on a background thread; [onDone] hears the account or a plain-words problem (from any
     * thread). It doesn't wait for a running export: the request count is shared safely.
     */
    fun fetchAccount(ctx: Context, token: String, onDone: (Account?, String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var account: Account? = null
            var problem = ""
            try {
                val client = TogglClient.make(app, token)
                val id = client.me()
                account = Account(id, client.workspaces())
            } catch (e: TogglStop) {
                problem = when (e.kind) {
                    StopKind.AUTH -> "Toggl didn't accept that token. Copy it again from Profile settings."
                    else -> e.message ?: e.kind.name
                }
            } catch (e: Exception) {
                problem = describe(e)
            }
            onDone(account, problem)
        }.start()
    }

    // --- Status ---

    /** Plain lines for the settings screen. */
    fun status(ctx: Context): List<String> {
        val now = System.currentTimeMillis()
        val lines = ArrayList<String>()
        val sent = sentCount(ctx)
        val pendingDays = Store.dirtyDays(ctx, Store.TOGGL).keys.count { k ->
            val ymd = ObsidianExport.parseKey(k)
            val s = ymd?.let { DiaryDay.startOf(it.first, it.second, it.third) }
            s != null && s <= now && !(fromMs(ctx) > 0L && s < fromMs(ctx))
        }
        if (Prefs.togglRemoving(ctx)) {
            lines.add("Removing sent entries from Toggl: $sent left.")
        } else if (Prefs.togglOn(ctx)) {
            val last = Prefs.togglLastSync(ctx)
            lines.add(if (last == 0L) "Sending to Toggl. Nothing sent yet." else "Last sent: " + Fmt.day(last) + ", " + Fmt.time(ctx, last))
        } else {
            lines.add("Not sending to Toggl.")
        }
        lines.add("Entries sent: $sent" + if (pendingDays > 0 && Prefs.togglOn(ctx)) ". Days waiting to be sent: $pendingDays." else ".")
        val budget = RequestBudget.decode(Prefs.togglBudget(ctx))
        lines.add("Requests left this hour: " + budget.left(TogglBucket.WORKSPACE, now) + " (the app keeps to ${RequestBudget.LIMIT} an hour).")
        val next = Prefs.togglNextAt(ctx)
        if (next > now && Prefs.togglFatal(ctx).isBlank()) lines.add("Next attempt: " + Fmt.time(ctx, next))
        if (busy.isNotBlank()) lines.add(busy)
        val fatal = Prefs.togglFatal(ctx)
        if (fatal.isNotBlank()) lines.add("Stopped: $fatal Tap Send now to try again.")
        val problem = Prefs.togglError(ctx)
        if (problem.isNotBlank() && problem != fatal) lines.add("Last problem: $problem")
        val info = Prefs.togglInfo(ctx)
        if (info.isNotBlank()) lines.add(info)
        val unresolved = Prefs.togglUnresolved(ctx)
        if (unresolved.isNotEmpty()) {
            lines.add(
                "${unresolved.size} entr" + (if (unresolved.size == 1) "y" else "ies") + " may be duplicated in Toggl; check " +
                    TogglSync.oldestFirst(unresolved.map { CalendarDay.dayOf(it) }.toSet()).take(3).joinToString(", ") + ". They aren't changed or deleted by the app."
            )
        }
        val released = Store.togglReleasedCount(ctx)
        if (released > 0) {
            lines.add("$released entr" + (if (released == 1) "y was" else "ies were") + " deleted in Toggl and " + (if (released == 1) "isn't" else "aren't") + " sent again.")
        }
        return lines
    }
}

/**
 * The Toggl export's job: a minute after the last change, or at the next attempt time after a full
 * quota or an error. Needs a network (any). The schedule is persisted, and the attempt time is also
 * kept in the preferences, so a restart carries on.
 */
class TogglJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        active = true
        Thread {
            try {
                TogglExport.runJob(app)
            } catch (e: Throwable) {
                Prefs.setTogglError(app, e.message ?: e.javaClass.simpleName)
            }
            jobFinished(params, false)
            active = false
            TogglExport.scheduleIfPending(app)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        const val JOB_ID = 4205

        @Volatile var active = false

        fun schedule(ctx: Context, delay: Long) {
            val scheduler = ctx.getSystemService(JobScheduler::class.java) ?: return
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, TogglJob::class.java))
                .setMinimumLatency(delay)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            scheduler.schedule(info)
        }
    }
}
