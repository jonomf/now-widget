package com.jono.nowwidget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.jono.nowwidget.core.DiaryDay
import com.jono.nowwidget.core.Entry
import com.jono.nowwidget.core.HubJson
import com.jono.nowwidget.core.HubRecords
import com.jono.nowwidget.core.IDLE
import com.jono.nowwidget.core.Json
import com.jono.nowwidget.core.ObsidianDay
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/** The hub's settings. The key has its own preferences file, kept out of backups and device transfers. */
object HubPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("now", Context.MODE_PRIVATE)
    private fun secret(ctx: Context) = ctx.getSharedPreferences("hub_secret", Context.MODE_PRIVATE)

    fun url(ctx: Context): String? = p(ctx).getString("hub_url", null)?.takeIf { it.isNotBlank() }
    fun key(ctx: Context): String? = secret(ctx).getString("key", null)?.takeIf { it.isNotBlank() }
    fun setHub(ctx: Context, url: String, key: String) {
        p(ctx).edit().putString("hub_url", url).apply()
        secret(ctx).edit().putString("key", key).commit()
    }
    fun clearHub(ctx: Context) {
        p(ctx).edit().remove("hub_url").remove("hub_on").remove("hub_cursor").apply()
        secret(ctx).edit().remove("key").commit()
    }

    fun on(ctx: Context): Boolean = p(ctx).getBoolean("hub_on", false)
    fun setOn(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("hub_on", v).apply()

    /** This install's device name in the log, made once: ph-xxxxxx. A reinstall is a new device. */
    fun device(ctx: Context): String {
        p(ctx).getString("hub_device", null)?.let { return it }
        val d = "ph-" + java.util.UUID.randomUUID().toString().replace("-", "").take(6)
        p(ctx).edit().putString("hub_device", d).commit()
        return d
    }

    fun cursor(ctx: Context): Long = p(ctx).getLong("hub_cursor", 0L)
    fun setCursor(ctx: Context, v: Long) = p(ctx).edit().putLong("hub_cursor", v).apply()

    fun lastSync(ctx: Context): Long = p(ctx).getLong("hub_last", 0L)
    fun lastResult(ctx: Context): String = p(ctx).getString("hub_result", "") ?: ""
    fun error(ctx: Context): String = p(ctx).getString("hub_error", "") ?: ""
    fun setResult(ctx: Context, at: Long, result: String) =
        p(ctx).edit().putLong("hub_last", at).putString("hub_result", result).putString("hub_error", "").apply()
    fun setError(ctx: Context, e: String) = p(ctx).edit().putString("hub_error", e).apply()
}

class HubException(msg: String) : Exception(msg)

/** One POST to the hub's web app. Google answers with a redirect to the result, which is read with a GET. */
object HubClient {
    private const val CONNECT_MS = 15_000
    private const val READ_MS = 60_000

    fun post(url: String, body: String): Map<String, Any?> {
        var conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_MS
            conn.readTimeout = READ_MS
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            var code = conn.responseCode
            var hops = 0
            while (code in 300..399 && hops < 5) {
                val next = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(URL(url), next).openConnection() as HttpURLConnection
                conn.connectTimeout = CONNECT_MS
                conn.readTimeout = READ_MS
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                code = conn.responseCode
                hops++
            }
            val text = (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code >= 400) throw HubException("The hub answered HTTP $code")
            val parsed = try {
                Json.parse(text)
            } catch (e: IllegalArgumentException) {
                null
            }
            @Suppress("UNCHECKED_CAST")
            val res = parsed as? Map<String, Any?>
                ?: throw HubException("That URL didn't answer like the hub: it should end in /exec, with access set to Anyone")
            if (res["ok"] != true) {
                val err = res["error"] as? String
                throw HubException(if (err == "wrong key") "The hub didn't accept the key" else "Hub: ${err ?: "unknown error"}")
            }
            return res
        } catch (e: IOException) {
            throw HubException("Couldn't reach the hub (${e.javaClass.simpleName})")
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Keeps this phone's log and the hub's in step (hub/PROTOCOL.md): sends what changed here, takes in
 * what other devices (the Windows Now widget) changed. Both are equal; for each record the newest
 * version wins. What changed here is found the way the exports find it: the dirty days of target
 * 'hub' for entries and moments, and a full look at activities, links, moods and feeling words
 * (small tables). Runs on a background thread, one at a time; a run is a few requests.
 */
object HubSync {
    private const val BATCH = 200
    private const val MAX_REQUESTS = 60
    private const val DEBOUNCE_MS = 3_000L
    private const val POLL_MS = 15 * 60_000L

    val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var busy = false
        private set
    private val lock = Any()

    /** Set by a run: go again after this many ms (0: no need). Scheduled only once the run is over. */
    @Volatile private var again = 0L

    /** Schedules the follow-up run a run asked for. Never from inside a running job (that would stop it). */
    fun scheduleAgain(ctx: Context) {
        val delay = again
        again = 0L
        if (delay > 0L && configured(ctx)) HubJob.schedule(ctx.applicationContext, delay)
    }

    fun configured(ctx: Context): Boolean = HubPrefs.on(ctx) && HubPrefs.url(ctx) != null && HubPrefs.key(ctx) != null

    private fun notifyListeners() = listeners.forEach { it() }

    /** The Store calls this when it marked days dirty. Never throws. */
    fun changed(ctx: Context) {
        try {
            if (!configured(ctx) || busy || HubJob.active) return
            HubJob.schedule(ctx.applicationContext, DEBOUNCE_MS)
        } catch (e: Exception) {
            // The days stay marked; the next sync sends them.
        }
    }

    fun onAppOpen(ctx: Context) {
        if (configured(ctx)) syncNow(ctx)
    }

    /** Turning it on: the whole log is looked at once, so the hub gets all of it. */
    fun start(ctx: Context) {
        task(ctx) { app ->
            Store.markDaysDirty(app, Store.HUB, Store.hubAllDays(app))
            run(app)
        }
    }

    fun syncNow(ctx: Context) = task(ctx) { run(it) }

    /** A different hub: forget what the old one was sent and taken from it. */
    fun forget(ctx: Context) {
        Store.forgetHub(ctx)
        HubPrefs.setCursor(ctx, 0L)
    }

    fun runJob(ctx: Context) {
        synchronized(lock) { runGuarded(ctx) }
    }

    private fun task(ctx: Context, block: (Context) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            synchronized(lock) {
                busy = true
                notifyListeners()
                try {
                    block(app)
                } catch (e: Throwable) {
                    HubPrefs.setError(app, e.message ?: e.javaClass.simpleName)
                } finally {
                    busy = false
                    notifyListeners()
                }
            }
            if (!HubJob.active) scheduleAgain(app)
        }.start()
    }

    private fun runGuarded(ctx: Context) {
        busy = true
        notifyListeners()
        try {
            run(ctx)
        } catch (e: Throwable) {
            HubPrefs.setError(ctx, e.message ?: e.javaClass.simpleName)
        } finally {
            busy = false
            notifyListeners()
        }
    }

    private class Out(val gid: String, val letter: Char, val localId: Long, val day: String, val rec: Map<String, Any?>, val hash: String, val deleted: Boolean)

    private fun dayKey(ms: Long): String {
        val (y, m, d) = DiaryDay.date(ms)
        return ObsidianDay.key(y, m, d)
    }

    private fun dayStartOf(key: String): Long? {
        val p = key.split('-').mapNotNull { it.toIntOrNull() }
        return if (p.size == 3) DiaryDay.startOf(p[0], p[1], p[2]) else null
    }

    /** Maps between local rows and global ids for one run. */
    private class Ids(val dev: String, val rows: MutableMap<String, Store.HubRow>) {
        val reverse = HashMap<String, String>().apply {
            rows.values.forEach { if (it.localId != 0L) put("${it.letter}:${it.localId}", it.gid) }
        }

        fun gid(letter: Char, id: Long): String =
            if (letter == HubRecords.ACT && id == IDLE) HubRecords.IDLE else reverse["$letter:$id"] ?: HubRecords.ownId(dev, letter, id)

        /** The local row for a global id: a mapped row first, then one of this install's own ids. */
        fun local(letter: Char, gid: String): Long? {
            if (letter == HubRecords.ACT && gid == HubRecords.IDLE) return IDLE
            rows[gid]?.let { r -> return if (r.localId != 0L && r.letter == letter) r.localId else null }
            return HubRecords.ownLocal(dev, letter, gid)
        }

        fun remember(r: Store.HubRow) {
            rows[r.gid] = r
            if (r.localId != 0L) reverse["${r.letter}:${r.localId}"] = r.gid
        }
    }

    private fun entryRec(ids: Ids, gid: String, e: Entry): Map<String, Any?> = HubRecords.entry(
        gid, e.start, ids.gid(HubRecords.ACT, e.activityId), e.links.map { ids.gid(HubRecords.LINK, it) },
        e.contextId?.let { ids.gid(HubRecords.LINK, it) }, e.title, e.extras.map { ids.gid(HubRecords.ACT, it) },
    )

    private fun momentRec(ids: Ids, gid: String, h: Store.HubMoment): Map<String, Any?> = HubRecords.moment(
        gid, h.m.ts, h.m.kind, h.m.title, h.m.text, h.m.moodId?.let { ids.gid(HubRecords.MOOD, it) },
        h.m.entryId?.let { ids.gid(HubRecords.ENTRY, it) }, h.m.tags.map { ids.gid(HubRecords.TAG, it) },
        h.refs.map { (k, id) -> k to ids.gid(if (k == "activity") HubRecords.ACT else HubRecords.LINK, id) }, h.src,
    )

    private fun run(ctx: Context) {
        if (!configured(ctx)) return
        val url = HubPrefs.url(ctx) ?: return
        val key = HubPrefs.key(ctx) ?: return
        val dev = HubPrefs.device(ctx)
        val ids = Ids(dev, HashMap(Store.hubRows(ctx)))
        val out = ArrayList<Out>()

        fun consider(letter: Char, localId: Long, day: String, gid: String, rec: Map<String, Any?>) {
            val h = HubRecords.hash(rec)
            if (ids.rows[gid]?.hash != h) out.add(Out(gid, letter, localId, day, rec, h, false))
        }
        fun tombstone(r: Store.HubRow) {
            if (r.hash != "-") out.add(Out(r.gid, r.letter, 0L, r.day, HubRecords.tombstone(r.gid, HubRecords.kindOf(r.letter)), "-", true))
        }

        // 1. What changed here: the small tables, whole.
        val acts = Store.allActs(ctx)
        acts.forEach { a -> ids.gid(HubRecords.ACT, a.id).let { consider(HubRecords.ACT, a.id, "", it, HubRecords.act(it, a)) } }
        val links = Store.allLinks(ctx)
        links.filter { it.chip }.forEach { l -> ids.gid(HubRecords.LINK, l.id).let { consider(HubRecords.LINK, l.id, "", it, HubRecords.link(it, l)) } }
        val moods = Store.allMoods(ctx)
        moods.forEach { m -> ids.gid(HubRecords.MOOD, m.id).let { consider(HubRecords.MOOD, m.id, "", it, HubRecords.mood(it, m)) } }
        val tags = Store.allTags(ctx)
        tags.forEach { t -> ids.gid(HubRecords.TAG, t.id).let { consider(HubRecords.TAG, t.id, "", it, HubRecords.tag(it, t)) } }
        val present = mapOf(
            HubRecords.ACT to acts.map { it.id }.toSet(), HubRecords.LINK to links.map { it.id }.toSet(),
            HubRecords.MOOD to moods.map { it.id }.toSet(), HubRecords.TAG to tags.map { it.id }.toSet(),
        )
        ids.rows.values.filter { it.letter in present.keys && it.localId != 0L && it.localId !in present.getValue(it.letter) }.forEach { tombstone(it) }

        // ...and the dirty days' entries and moments.
        val dirty = Store.dirtyDays(ctx, Store.HUB)
        for ((key, _) in dirty) {
            val from = dayStartOf(key) ?: continue
            val to = DiaryDay.add(from, 1)
            val seen = HashSet<String>()
            Store.entriesStarting(ctx, from, to).forEach { e ->
                val gid = ids.gid(HubRecords.ENTRY, e.id)
                seen.add(gid)
                consider(HubRecords.ENTRY, e.id, key, gid, entryRec(ids, gid, e))
            }
            Store.hubMoments(ctx, from, to).forEach { h ->
                val gid = ids.gid(HubRecords.MOMENT, h.m.id)
                seen.add(gid)
                consider(HubRecords.MOMENT, h.m.id, key, gid, momentRec(ids, gid, h))
            }
            // Gone from this day: moved to another one, or deleted.
            ids.rows.values.filter { it.day == key && it.localId != 0L && it.gid !in seen && (it.letter == HubRecords.ENTRY || it.letter == HubRecords.MOMENT) }
                .forEach { r ->
                    if (r.letter == HubRecords.ENTRY) {
                        val e = Store.entryById(ctx, r.localId)
                        if (e != null) consider(HubRecords.ENTRY, e.id, dayKey(e.start), r.gid, entryRec(ids, r.gid, e)) else tombstone(r)
                    } else {
                        val h = Store.hubMomentById(ctx, r.localId)
                        if (h != null) consider(HubRecords.MOMENT, h.m.id, dayKey(h.m.ts), r.gid, momentRec(ids, r.gid, h)) else tombstone(r)
                    }
                }
        }

        // 2. Send it, a batch at a time, taking in what's new from the hub as we go.
        var cursor = HubPrefs.cursor(ctx)
        val incoming = ArrayList<Map<String, Any?>>()
        var sent = 0
        var requests = 0
        var more: Boolean
        do {
            val batch = out.subList(sent, minOf(sent + BATCH, out.size))
            val now = System.currentTimeMillis()
            val versions = batch.map { maxOf(now, (ids.rows[it.gid]?.v ?: 0L) + 1) }
            val push = batch.mapIndexed { i, o -> o.rec + mapOf("v" to versions[i], "dev" to dev) }
            val res = HubClient.post(url, HubJson.write(mapOf("key" to key, "dev" to dev, "push" to push, "since" to cursor, "limit" to 500L)))
            val saved = batch.mapIndexed { i, o -> Store.HubRow(o.gid, o.letter, o.localId, o.day, o.hash, versions[i]) }
            Store.saveHubRows(ctx, saved)
            saved.forEach { ids.remember(it) }
            sent += batch.size
            (res["changes"] as? List<*>)?.forEach { c -> @Suppress("UNCHECKED_CAST") (c as? Map<String, Any?>)?.let { incoming.add(it) } }
            cursor = HubRecords.long(res, "seq") ?: cursor
            more = res["more"] == true
            requests++
        } while ((sent < out.size || more) && requests < MAX_REQUESTS)

        // 3. Take in what other devices changed.
        val (applied, skipped) = apply(ctx, ids, incoming)
        HubPrefs.setCursor(ctx, cursor)

        // 4. The days that were sent in full are clean (unless marked again meanwhile).
        if (sent >= out.size) dirty.forEach { (k, g) -> Store.clearDirtyIf(ctx, k, g, Store.HUB) }

        val parts = ArrayList<String>()
        parts.add(if (sent == 0) "nothing to send" else "sent $sent")
        parts.add(if (applied == 0) "nothing new from other devices" else "took in $applied from other devices")
        if (skipped > 0) parts.add("$skipped not taken in (made on another install, or they point at something this phone doesn't have)")
        if (sent < out.size || more) parts.add("more to do next time")
        HubPrefs.setResult(ctx, System.currentTimeMillis(), parts.joinToString(", "))
        if (applied > 0) WidgetRenderer.refresh(ctx)
        // More waiting, or changed while this ran (taps during a run don't schedule one): go again soon.
        // Days dirtied only by taking changes in settle on that next run, which finds nothing to send.
        again = when {
            sent < out.size || more -> 10_000L
            Store.dirtyDays(ctx, Store.HUB).isNotEmpty() -> DEBOUNCE_MS
            else -> 0L
        }
    }

    /** Applies the newest version of each record from other devices. Returns (applied, skipped). */
    private fun apply(ctx: Context, ids: Ids, incoming: List<Map<String, Any?>>): Pair<Int, Int> {
        val newest = LinkedHashMap<String, Map<String, Any?>>()
        for (r in incoming) {
            if (HubRecords.str(r, "dev") == ids.dev) continue
            val id = HubRecords.str(r, "id") ?: continue
            val have = newest[id]
            if (have == null || HubRecords.wins(HubRecords.long(r, "v") ?: 0L, HubRecords.str(r, "dev") ?: "", HubRecords.long(have, "v") ?: 0L, HubRecords.str(have, "dev") ?: "")) newest[id] = r
        }
        val order = listOf("act", "link", "mood", "tag", "entry", "moment")
        var applied = 0
        var skipped = 0
        for (r in newest.values.sortedBy { order.indexOf(HubRecords.str(it, "k")).let { i -> if (i < 0) 99 else i } }) {
            val gid = HubRecords.str(r, "id") ?: continue
            val letter = HubRecords.letterOf(HubRecords.str(r, "k") ?: "") ?: continue
            val v = HubRecords.long(r, "v") ?: 0L
            val have = ids.rows[gid]
            // What this phone has is as new or newer (it was sent or taken in already).
            if (have != null && v <= have.v) continue
            val ok = try {
                when (letter) {
                    HubRecords.ENTRY -> applyEntry(ctx, ids, gid, v, r)
                    HubRecords.MOMENT -> applyMoment(ctx, ids, gid, v, r)
                    // Activities, people and places, moods and feeling words are made on the phone; another
                    // device's versions of them aren't taken in yet.
                    else -> false
                }
            } catch (e: Exception) {
                HubPrefs.setError(ctx, "Couldn't take in ${HubRecords.str(r, "k")}: ${e.message ?: e.javaClass.simpleName}")
                false
            }
            if (ok) applied++ else skipped++
        }
        return applied to skipped
    }

    private fun applyEntry(ctx: Context, ids: Ids, gid: String, v: Long, r: Map<String, Any?>): Boolean {
        val local = ids.local(HubRecords.ENTRY, gid)
        if (HubRecords.bool(r, "del")) {
            if (local != null) Store.hubDeleteEntry(ctx, local)
            val row = Store.HubRow(gid, HubRecords.ENTRY, 0L, ids.rows[gid]?.day ?: "", "-", v)
            Store.saveHubRows(ctx, listOf(row))
            ids.remember(row)
            return true
        }
        val start = HubRecords.long(r, "start") ?: return false
        val act = ids.local(HubRecords.ACT, HubRecords.str(r, "act") ?: HubRecords.IDLE) ?: return false
        val links = HubRecords.ids(r, "links").mapNotNull { ids.local(HubRecords.LINK, it) }.toSet()
        val context = HubRecords.str(r, "ctx")?.let { ids.local(HubRecords.LINK, it) }
        val extras = HubRecords.ids(r, "extras").mapNotNull { ids.local(HubRecords.ACT, it) }.toSet()
        val id = Store.hubPutEntry(ctx, local, act, start, links, context, HubRecords.str(r, "title"), extras)
        // Remember it as this phone would send it back, so taking it in isn't sent again as a change.
        ids.remember(Store.HubRow(gid, HubRecords.ENTRY, id, dayKey(start), "", v))
        val e = Store.entryById(ctx, id) ?: return false
        val row = Store.HubRow(gid, HubRecords.ENTRY, id, dayKey(e.start), HubRecords.hash(entryRec(ids, gid, e)), v)
        Store.saveHubRows(ctx, listOf(row))
        ids.remember(row)
        return true
    }

    private fun applyMoment(ctx: Context, ids: Ids, gid: String, v: Long, r: Map<String, Any?>): Boolean {
        val local = ids.local(HubRecords.MOMENT, gid)
        if (HubRecords.bool(r, "del")) {
            if (local != null) Store.hubDeleteMoment(ctx, local)
            val row = Store.HubRow(gid, HubRecords.MOMENT, 0L, ids.rows[gid]?.day ?: "", "-", v)
            Store.saveHubRows(ctx, listOf(row))
            ids.remember(row)
            return true
        }
        val ts = HubRecords.long(r, "ts") ?: return false
        val mood = HubRecords.str(r, "mood")?.let { ids.local(HubRecords.MOOD, it) }
        val entry = HubRecords.str(r, "entry")?.let { ids.local(HubRecords.ENTRY, it) }
        val tags = HubRecords.ids(r, "tags").mapNotNull { ids.local(HubRecords.TAG, it) }
        val refs = HubRecords.refs(r).mapNotNull { (k, id) ->
            ids.local(if (k == "activity") HubRecords.ACT else HubRecords.LINK, id)?.let { k to it }
        }
        val id = Store.hubPutMoment(
            ctx, local, ts, HubRecords.str(r, "kind") ?: "note", HubRecords.str(r, "title") ?: "", HubRecords.str(r, "text") ?: "",
            mood, entry, tags, refs, HubRecords.str(r, "src"),
        )
        ids.remember(Store.HubRow(gid, HubRecords.MOMENT, id, dayKey(ts), "", v))
        val h = Store.hubMomentById(ctx, id) ?: return false
        val row = Store.HubRow(gid, HubRecords.MOMENT, id, dayKey(h.m.ts), HubRecords.hash(momentRec(ids, gid, h)), v)
        Store.saveHubRows(ctx, listOf(row))
        ids.remember(row)
        return true
    }

    fun status(ctx: Context): List<String> {
        val out = ArrayList<String>()
        if (HubPrefs.url(ctx) == null || HubPrefs.key(ctx) == null) {
            out.add("Not connected.")
            return out
        }
        out.add(if (HubPrefs.on(ctx)) "Syncing is on. This phone is ${HubPrefs.device(ctx)}." else "Syncing is off.")
        val last = HubPrefs.lastSync(ctx)
        if (last > 0L) out.add("Last sync: ${Fmt.day(last)} ${Fmt.time(ctx, last)}: ${HubPrefs.lastResult(ctx)}")
        val waiting = Store.dirtyDays(ctx, Store.HUB).size
        if (HubPrefs.on(ctx) && waiting > 0) out.add("Days waiting to sync: $waiting")
        val err = HubPrefs.error(ctx)
        if (err.isNotBlank()) out.add("Last problem: $err")
        if (busy) out.add("Syncing…")
        return out
    }

    /** Keeps the phone up to date with other devices' changes while the app isn't open. */
    fun schedulePoll(ctx: Context) {
        val scheduler = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (!configured(ctx)) {
            scheduler.cancel(HubJob.POLL_ID)
            return
        }
        if (scheduler.getPendingJob(HubJob.POLL_ID) != null) return
        scheduler.schedule(
            JobInfo.Builder(HubJob.POLL_ID, ComponentName(ctx, HubJob::class.java))
                .setPeriodic(POLL_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
        )
    }
}

/** The hub sync's job: a few seconds after a change, and every 15 minutes to take in other devices' changes. Needs a network. */
class HubJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread {
            active = true
            try {
                HubSync.runJob(app)
            } finally {
                jobFinished(params, false)
                active = false
                HubSync.scheduleAgain(app)
                HubSync.schedulePoll(app)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        const val JOB_ID = 4207
        const val POLL_ID = 4208

        @Volatile var active = false

        fun schedule(ctx: Context, delay: Long) {
            val scheduler = ctx.getSystemService(JobScheduler::class.java) ?: return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, HubJob::class.java))
                    .setMinimumLatency(delay)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
            )
        }
    }
}
