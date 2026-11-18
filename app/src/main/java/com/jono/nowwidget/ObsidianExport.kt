package com.jono.nowwidget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import com.jono.nowwidget.core.DayData
import com.jono.nowwidget.core.DayLedger
import com.jono.nowwidget.core.ExportProtocol
import com.jono.nowwidget.core.NoteFile
import com.jono.nowwidget.core.NoteFolder
import com.jono.nowwidget.core.WriteTarget
import com.jono.nowwidget.core.DayMoment
import com.jono.nowwidget.core.DaySpan
import com.jono.nowwidget.core.DiaryDay
import com.jono.nowwidget.core.LinkKind
import com.jono.nowwidget.core.ObsidianDay
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/** What a write run did. */
class WriteReport(val written: Int, val unchanged: Int, val skipped: Int, val errors: List<String>)

/**
 * Writes the `YYYY-M-D (now).md` notes into the vault: one per diary day, regenerated whole, only
 * when its text changed, and only ever the app's own notes. The phone's log is the only source;
 * nothing is read back. All of it runs on background threads.
 */
object ObsidianExport {
    private const val SCAN_EVERY_MS = 60L * 60L * 1000L
    private const val SCHEDULE_GAP_MS = 5_000L
    private const val MAX_PASSES = 20
    private val NOW_NOTE = Regex("^(\\d{4}-\\d{1,2}-\\d{1,2}) \\(now\\)(?: \\(\\d+\\))?\\.md$")

    private val lock = Any()
    private val openRunning = AtomicBoolean(false)
    @Volatile private var lastScheduled = 0L

    /** True while a write run is going through the dirty days (the job, the app-open run, the buttons). */
    private val writing = AtomicBoolean(false)

    /** Each day the last run tried, with its change counter then, so a failed day isn't retried in the same run. */
    @Volatile private var lastAttempted: Map<String, Int> = emptyMap()

    /** Screens that show the export's status redraw when this fires. */
    val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** What's running right now, for the settings screen; blank when nothing is. */
    @Volatile var busy: String = ""
        private set

    fun configured(ctx: Context): Boolean = Prefs.vaultTree(ctx) != null && Prefs.vaultRoot(ctx) != null

    private fun notifyListeners() = listeners.forEach { it() }

    /**
     * The Store calls this whenever it marked days dirty: a job runs about a minute after the last
     * change and writes every dirty day. Never throws: a scheduling problem must not break a save.
     */
    fun changed(ctx: Context) {
        try {
            if (!configured(ctx)) return
            // A run in progress looks at the dirty days again before it ends, and a job that's running
            // would be cancelled by scheduling its own id: neither needs another schedule.
            if (writing.get() || ExportJob.active) return
            val now = System.currentTimeMillis()
            if (now - lastScheduled < SCHEDULE_GAP_MS) return
            lastScheduled = now
            ExportJob.schedule(ctx.applicationContext)
        } catch (e: Exception) {
            // The days are marked in the database; the next app open writes them.
        }
    }

    // --- Running things in the background ---

    /** Runs [block] on its own thread, one at a time, recording any failure as the last error. */
    private fun task(ctx: Context, label: String, block: () -> Unit) {
        val app = ctx.applicationContext
        Thread {
            synchronized(lock) {
                busy = label
                notifyListeners()
                try {
                    block()
                } catch (e: Throwable) {
                    Prefs.setVaultError(app, describe(e))
                } finally {
                    busy = ""
                    notifyListeners()
                }
            }
        }.start()
    }

    private fun describe(e: Throwable): String = when (e) {
        is SecurityException -> "Android no longer lets this app use the vault folder. Choose it again."
        else -> (e.message ?: e.javaClass.simpleName)
    }

    /** When the app opens: scan the vault if it's been an hour, then write what changed (and today). */
    fun onAppOpen(ctx: Context) {
        if (!configured(ctx) || !openRunning.compareAndSet(false, true)) return
        task(ctx, "Checking the vault…") {
            try {
                val app = ctx.applicationContext
                if (System.currentTimeMillis() - Prefs.vaultLastScan(app) > SCAN_EVERY_MS) scanNow(app, deep = false)
                Store.markTodayDirty(app)
                writeDirty(app)
            } finally {
                openRunning.set(false)
            }
        }
    }

    fun rescan(ctx: Context) = task(ctx, "Scanning the vault…") { scanNow(ctx.applicationContext, deep = true) }

    fun writeToday(ctx: Context) = task(ctx, "Writing today…") {
        val app = ctx.applicationContext
        write(app, listOf(keyOf(Fmt.today())), force = true)
    }

    fun writeAll(ctx: Context) = task(ctx, "Writing all days…") {
        val app = ctx.applicationContext
        val days = Store.activeDays(app, Prefs.exportImported(app))
        write(app, days.map { keyOf(it) }, force = true)
    }

    /** The first scan after choosing a folder: index it, then write whatever is already dirty. */
    fun startUp(ctx: Context) = task(ctx, "Scanning the vault…") {
        val app = ctx.applicationContext
        scanNow(app, deep = true)
        writeDirty(app)
    }

    /** The debounced job. */
    fun runJob(ctx: Context) {
        synchronized(lock) {
            if (!configured(ctx)) return
            busy = "Writing notes…"
            notifyListeners()
            try {
                writeDirty(ctx)
            } catch (e: Throwable) {
                Prefs.setVaultError(ctx, describe(e))
            } finally {
                busy = ""
                notifyListeners()
            }
        }
    }

    /** After a job has finished: a change that came in right at the end still gets its own job. */
    fun scheduleIfPending(ctx: Context) {
        try {
            if (configured(ctx) && pendingNow(ctx)) ExportJob.schedule(ctx.applicationContext)
        } catch (e: Exception) {
            // The days stay dirty; the next app open writes them.
        }
    }

    private fun pendingNow(ctx: Context): Boolean =
        ExportProtocol.pending(Store.dirtyDays(ctx), lastAttempted).isNotEmpty()

    private fun keyOf(dayStart: Long): String {
        val (y, m, d) = DiaryDay.date(dayStart)
        return ObsidianDay.key(y, m, d)
    }

    private fun scanNow(ctx: Context, deep: Boolean) {
        try {
            val r = VaultIndex.scan(ctx, deep) { text ->
                busy = text
                notifyListeners()
            }
            Prefs.setVaultScan(ctx, System.currentTimeMillis(), r.text())
            Prefs.setVaultScanError(ctx, "")
        } catch (e: Throwable) {
            Prefs.setVaultScanError(ctx, describe(e))
        }
        notifyListeners()
    }

    /**
     * Writes every dirty day, then looks at the dirty days again, until nothing is left that wasn't
     * just tried: a change made while this ran is picked up here instead of by another schedule.
     */
    private fun writeDirty(ctx: Context) {
        writing.set(true)
        val attempted = HashMap<String, Int>()
        try {
            var pass = 0
            while (pass++ < MAX_PASSES) {
                val dirty = Store.dirtyDays(ctx)
                val keys = ExportProtocol.pending(dirty, attempted)
                if (keys.isEmpty()) break
                keys.forEach { attempted[it] = dirty.getValue(it) }
                lastAttempted = HashMap(attempted)
                write(ctx, keys, force = false)
            }
        } finally {
            lastAttempted = HashMap(attempted)
            writing.set(false)
        }
        // Not from inside the job: scheduling a running job's own id would cancel it (see ExportJob).
        if (!ExportJob.active && pendingNow(ctx)) ExportJob.schedule(ctx.applicationContext)
    }

    // --- Writing ---

    /** Names for ids, loaded once per run. The calendar export uses it too. */
    class Lookups(ctx: Context) {
        val acts = Store.allActs(ctx).associateBy { it.id }
        val links = Store.allLinks(ctx).associateBy { it.id }
        val moods = Store.allMoods(ctx).associateBy { it.id }
        val tags = Store.allTags(ctx).associateBy { it.id }
        val runningId: Long? = Store.current(ctx)?.id

        /** Link title to the title of the note it's bound to by alias. */
        val notes: Map<String, String> = Store.boundNotes(ctx).mapNotNull { (id, note) ->
            links[id]?.let { it.title to note }
        }.toMap()
    }

    /** Everything one diary day's note (or calendar events) is made from. */
    fun dayData(ctx: Context, lk: Lookups, dayStart: Long, now: Long): DayData {
        val end = DiaryDay.add(dayStart, 1)
        val spans = Store.spans(ctx, dayStart, end)
        val moments = Store.moments(ctx, dayStart, end)
        val imported = Store.importedMomentIds(ctx, dayStart, end)
        val isToday = now >= dayStart && now < end
        val (y, m, d) = DiaryDay.date(dayStart)
        val daySpans = spans.mapNotNull { s ->
            val act = lk.acts[s.entry.activityId] ?: return@mapNotNull null
            val linked = s.entry.links.mapNotNull { lk.links[it] }.sortedWith(compareBy({ it.pos }, { it.id }))
            DaySpan(
                entryId = s.entry.id,
                activityId = act.id,
                activity = act.name,
                emoji = act.emoji,
                group = act.group,
                from = s.from,
                to = s.to,
                people = linked.filter { it.kind == LinkKind.PERSON }.map { it.title },
                places = linked.filter { it.kind == LinkKind.PLACE }.map { it.title },
                context = s.entry.contextId?.let { lk.links[it]?.title },
                running = isToday && lk.runningId == s.entry.id,
            )
        }
        val dayMoments = moments.map { mo ->
            val mood = mo.moodId?.let { lk.moods[it] }
            DayMoment(
                ts = mo.ts,
                kind = mo.kind,
                entryId = mo.entryId,
                mood = mood?.name ?: "",
                moodLevel = mood?.level,
                moodEmoji = mood?.emoji ?: "",
                tags = mo.tags.mapNotNull { lk.tags[it] }.sortedWith(compareBy({ it.pos }, { it.id })).map { it.name },
                title = mo.title,
                text = mo.text,
                imported = mo.id in imported,
            )
        }
        return DayData(y, m, d, daySpans, dayMoments, TimeZone.getDefault(), lk.notes)
    }

    fun parseKey(key: String): Triple<Int, Int, Int>? {
        val p = key.split("-")
        if (p.size != 3) return null
        val y = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val d = p[2].toIntOrNull() ?: return null
        return Triple(y, m, d)
    }

    /** The real vault folder, for [ExportProtocol]. */
    private class RealFolder(val ctx: Context, val tree: Uri, val root: String) : NoteFolder {
        override fun files(): List<NoteFile> =
            VaultAccess.list(ctx, tree, root).filter { !it.isDir }.map { NoteFile(it.docId, it.name) }

        override fun head(docId: String): String = VaultAccess.readHead(ctx, tree, docId, 256)
        override fun create(name: String): String = VaultAccess.create(ctx, tree, root, name)
        override fun write(docId: String, text: String) = VaultAccess.write(ctx, tree, docId, text)
    }

    /** The Store's export tables as [ExportProtocol] sees them. */
    private class StoreLedger(val ctx: Context) : DayLedger {
        override fun gen(key: String): Int = Store.dayGen(ctx, key)
        override fun hash(key: String): String? = Store.exportHash(ctx, key)
        override fun docId(key: String): String? = Store.exportDocId(ctx, key)
        override fun saveHash(key: String, docId: String, hash: String) = Store.saveExportHash(ctx, key, docId, hash)
        override fun clearIfUnchanged(key: String, gen: Int): Boolean = Store.clearDirtyIf(ctx, key, gen)
    }

    /**
     * Writes the notes for these day keys through [ExportProtocol.writeDay]: a day stays dirty until
     * its file was written and its hash saved, and only if nothing changed it meanwhile; its stored
     * hash is blanked before the file is opened, so a write cut short is redone. Without [force], a
     * day whose text is unchanged since it was last written is left alone; with it (the buttons), the
     * file is checked and recreated if it's gone. Only the app's own notes are ever opened for writing.
     */
    fun write(ctx: Context, keys: List<String>, force: Boolean): WriteReport {
        val tree = Uri.parse(Prefs.vaultTree(ctx) ?: return report(ctx, 0, 0, 0, listOf("No vault folder is chosen")))
        val root = Prefs.vaultRoot(ctx) ?: return report(ctx, 0, 0, 0, listOf("No vault folder is chosen"))
        val held = ctx.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        if (!held) {
            return report(ctx, 0, 0, 0, listOf("The folder permission is gone. Choose the vault folder again."))
        }

        val now = System.currentTimeMillis()
        val lk = Lookups(ctx)
        val includeImported = Prefs.exportImported(ctx)
        val ledger = StoreLedger(ctx)
        val folder = RealFolder(ctx, tree, root)
        val errors = ArrayList<String>()
        var written = 0
        var unchanged = 0
        var skipped = 0

        // The folder is listed once, and only if something needs writing.
        var listing: List<NoteFile>? = null
        fun notes(): List<NoteFile> {
            listing?.let { return it }
            val all = folder.files().filter { NOW_NOTE.matches(it.name) }
            listing = all
            return all
        }

        val dupes = LinkedHashSet<String>()
        for (key in keys.distinct().sortedBy { sortKey(it) }) {
            val ymd = parseKey(key)
            val dayStart = if (ymd == null) null else DiaryDay.startOf(ymd.first, ymd.second, ymd.third)
            if (ymd == null || dayStart == null || dayStart > now) {
                Store.clearDirtyIf(ctx, key, Store.dayGen(ctx, key))
                skipped++
                continue
            }
            val name = ObsidianDay.fileName(ymd.first, ymd.second, ymd.third)
            val result = try {
                ExportProtocol.writeDay(ledger, folder, ::notes, key, name, force) {
                    val data = dayData(ctx, lk, dayStart, now)
                    when {
                        ObsidianDay.onlyImported(data) && !includeImported -> null
                        !ObsidianDay.hasContent(data) && ledger.hash(key) == null -> null
                        else -> ObsidianDay.render(data)
                    }
                }
            } catch (e: SecurityException) {
                errors.add(describe(e))
                break
            }
            when (result) {
                ExportProtocol.Result.Written -> {
                    written++
                    val same = notes().count { it.name == name }
                    if (same > 1) dupes.add("$name exists $same times")
                    WriteTarget.copiesOf(key, notes()).forEach { dupes.add("$it is a duplicate") }
                }
                ExportProtocol.Result.Unchanged -> unchanged++
                ExportProtocol.Result.Skipped -> skipped++
                is ExportProtocol.Result.Refused -> errors.add(result.why)
                is ExportProtocol.Result.Failed -> errors.add("$key: " + result.why)
            }
        }
        errors.addAll(dupes.map { "Duplicate: $it" })
        return report(ctx, written, unchanged, skipped, errors)
    }

    private fun sortKey(key: String): Long {
        val p = parseKey(key) ?: return Long.MAX_VALUE
        return p.first * 10_000L + p.second * 100L + p.third
    }

    /** Records the outcome where the settings screen shows it. */
    private fun report(ctx: Context, written: Int, unchanged: Int, skipped: Int, errors: List<String>): WriteReport {
        if (written > 0) Prefs.setVaultLastExport(ctx, System.currentTimeMillis())
        Prefs.setVaultError(ctx, errors.take(3).joinToString("\n") + if (errors.size > 3) "\n…and ${errors.size - 3} more" else "")
        notifyListeners()
        return WriteReport(written, unchanged, skipped, errors)
    }
}

/**
 * The debounce: scheduled about a minute after the last change, it writes every dirty day.
 * Plain framework JobScheduler (no WorkManager); the manifest guards the service with BIND_JOB_SERVICE.
 */
class ExportJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        active = true
        Thread {
            try {
                ObsidianExport.runJob(app)
            } catch (e: Throwable) {
                Prefs.setVaultError(app, e.message ?: e.javaClass.simpleName)
            }
            jobFinished(params, false)
            // Only now: scheduling this job's own id while it runs would cancel it.
            active = false
            ObsidianExport.scheduleIfPending(app)
        }.start()
        return true
    }

    /** Android stopped the job (conditions changed): ask for it again. A half-done day is redone, see ExportProtocol. */
    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 4201
        private const val DELAY_MS = 60_000L
        private const val LATEST_MS = 5L * 60_000L

        /** True while the job runs, so a change doesn't schedule over it. */
        @Volatile var active = false

        /** Replaces any waiting job, so it always runs a minute after the last change. */
        fun schedule(ctx: Context) {
            val scheduler = ctx.getSystemService(JobScheduler::class.java) ?: return
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, ExportJob::class.java))
                .setMinimumLatency(DELAY_MS)
                .setOverrideDeadline(DELAY_MS + LATEST_MS)
                .build()
            scheduler.schedule(info)
        }
    }
}
