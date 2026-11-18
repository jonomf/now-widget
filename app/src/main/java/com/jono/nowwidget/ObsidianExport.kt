package com.jono.nowwidget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import com.jono.nowwidget.core.DayData
import com.jono.nowwidget.core.DayMoment
import com.jono.nowwidget.core.DaySpan
import com.jono.nowwidget.core.DiaryDay
import com.jono.nowwidget.core.LinkKind
import com.jono.nowwidget.core.ObsidianDay
import java.io.IOException
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
    private const val TARGET_FIRST_LINE = ObsidianDay.FIRST_LINE
    private const val SCAN_EVERY_MS = 60L * 60L * 1000L
    private const val SCHEDULE_GAP_MS = 5_000L
    private val DUPLICATE = Regex("^(\\d{4}-\\d{1,2}-\\d{1,2}) \\(now\\) \\(\\d+\\)\\.md$")
    private val NOW_NOTE = Regex("^(\\d{4}-\\d{1,2}-\\d{1,2}) \\(now\\)(?: \\(\\d+\\))?\\.md$")

    private val lock = Any()
    private val openRunning = AtomicBoolean(false)
    @Volatile private var lastScheduled = 0L

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

    private fun writeDirty(ctx: Context) {
        val keys = Store.dirtyDays(ctx)
        if (keys.isEmpty()) return
        write(ctx, keys, force = false)
    }

    // --- Writing ---

    private class Lookups(ctx: Context) {
        val acts = Store.allActs(ctx).associateBy { it.id }
        val links = Store.allLinks(ctx).associateBy { it.id }
        val moods = Store.allMoods(ctx).associateBy { it.id }
        val tags = Store.allTags(ctx).associateBy { it.id }
        val runningId: Long? = Store.current(ctx)?.id
    }

    private fun dayData(ctx: Context, lk: Lookups, dayStart: Long, now: Long): DayData {
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
        return DayData(y, m, d, daySpans, dayMoments, TimeZone.getDefault())
    }

    private fun parseKey(key: String): Triple<Int, Int, Int>? {
        val p = key.split("-")
        if (p.size != 3) return null
        val y = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val d = p[2].toIntOrNull() ?: return null
        return Triple(y, m, d)
    }

    /**
     * Writes the notes for these day keys. Without [force], a day whose text is unchanged since it
     * was last written is left alone; with it (the buttons), the file is checked and recreated if
     * it's gone. Days are cleared from the dirty list before they're rendered, so a change that
     * lands while this runs keeps its day dirty. A failed day is marked dirty again.
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
        val errors = ArrayList<String>()
        var written = 0
        var unchanged = 0
        var skipped = 0

        // The folder is listed once, and only if something needs writing.
        var listing: Map<String, List<VaultFile>>? = null
        fun notes(): Map<String, List<VaultFile>> {
            listing?.let { return it }
            val all = VaultAccess.list(ctx, tree, root).filter { !it.isDir && NOW_NOTE.matches(it.name) }.groupBy { it.name }
            listing = all
            return all
        }

        val dupes = LinkedHashSet<String>()
        for (key in keys.distinct().sortedBy { sortKey(it) }) {
            val ymd = parseKey(key)
            if (ymd == null) {
                Store.clearDirty(ctx, key)
                skipped++
                continue
            }
            val dayStart = DiaryDay.startOf(ymd.first, ymd.second, ymd.third)
            if (dayStart > now) {
                Store.clearDirty(ctx, key)
                skipped++
                continue
            }
            Store.clearDirty(ctx, key)
            try {
                val data = dayData(ctx, lk, dayStart, now)
                val stored = Store.exportHash(ctx, key)
                if (ObsidianDay.onlyImported(data) && !includeImported) {
                    skipped++
                    continue
                }
                if (!ObsidianDay.hasContent(data) && stored == null) {
                    skipped++
                    continue
                }
                val text = ObsidianDay.render(data)
                val hash = ObsidianDay.hash(text)
                if (!force && stored == hash) {
                    unchanged++
                    continue
                }
                val name = ObsidianDay.fileName(ymd.first, ymd.second, ymd.third)
                val same = notes()[name].orEmpty()
                if (same.size > 1) dupes.add("$name exists ${same.size} times")
                notes().keys.filter { k -> DUPLICATE.matchEntire(k)?.groupValues?.get(1) == key }
                    .forEach { dupes.add("$it is a duplicate") }
                val docId: String
                if (same.isNotEmpty()) {
                    docId = same[0].docId
                    val head = VaultAccess.readHead(ctx, tree, docId, 256).removePrefix("﻿")
                    val first = head.lineSequence().firstOrNull()?.trim() ?: ""
                    if (head.isNotBlank() && first != TARGET_FIRST_LINE) {
                        errors.add("$name isn't one of this app's notes (its first line isn't $TARGET_FIRST_LINE), so it was left alone")
                        continue
                    }
                } else {
                    docId = VaultAccess.create(ctx, tree, root, name)
                }
                VaultAccess.write(ctx, tree, docId, text)
                Store.saveExportHash(ctx, key, docId, hash)
                written++
            } catch (e: SecurityException) {
                Store.markKeyDirty(ctx, key)
                errors.add(describe(e))
                break
            } catch (e: Exception) {
                Store.markKeyDirty(ctx, key)
                errors.add("$key: " + (e.message ?: e.javaClass.simpleName))
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
        Thread {
            try {
                ObsidianExport.runJob(app)
            } catch (e: Throwable) {
                Prefs.setVaultError(app, e.message ?: e.javaClass.simpleName)
            }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 4201
        private const val DELAY_MS = 60_000L
        private const val LATEST_MS = 5L * 60_000L

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
