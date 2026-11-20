package com.jono.nowwidget.core

/**
 * Toggl as the export sees it. The real one is TogglClient (HTTP, counted against the hourly
 * budget); tests use a fake. Every call may throw [TogglStop] (the run must stop), [TogglGone]
 * (404), or [TogglRejected] (Toggl refused this one request). A create that gets no answer throws
 * [TogglUnknown].
 */
interface TogglApi {
    /** Creates the entry (in [TogglWant.workspaceId], its project made if need be) and returns its id. [tags] false leaves tags out. */
    fun create(w: TogglWant, tags: Boolean): Long

    fun update(workspaceId: Long, entryId: Long, w: TogglWant, tags: Boolean)
    fun delete(workspaceId: Long, entryId: Long)

    /** The user's entries that started in [from, to) (ms). One request against the /me quota. */
    fun entriesBetween(from: Long, to: Long): List<TogglSeen>
}

/** What the export remembers. The real one is the Store (export_state and export_day). */
interface TogglLedger {
    fun gen(day: String): Int
    fun clearIfUnchanged(day: String, gen: Int): Boolean

    /** This day's records, pending ones included. */
    fun records(day: String): List<TogglRecord>

    /** Keys of this day's entries that were deleted in Toggl: never written again. */
    fun released(day: String): Set<String>
    fun save(r: TogglRecord)
    fun drop(key: String)
    fun release(r: TogglRecord, why: String)
}

/** What a run did so far; kept even when the run is stopped part way. */
class TogglStats {
    var created = 0
    var updated = 0
    var deleted = 0
    val requestsWorked: Int get() = created + updated + deleted
}

/** What to do to one day's entries. */
object TogglPlan {
    sealed class Action {
        /** Saves a pending record first, then POSTs, then records the id. */
        data class Create(val want: TogglWant) : Action()
        data class Update(val record: TogglRecord, val want: TogglWant) : Action()
        /** A pending record's entry was found in Toggl: record it, and PUT only if what was sent differs. */
        data class Adopt(val entryId: Long, val record: TogglRecord, val want: TogglWant, val needsUpdate: Boolean) : Action()
        /** If it fails, no create for the same key follows. */
        data class Delete(val record: TogglRecord) : Action()
        /** A pending record whose entry was never made (or isn't wanted): only the record goes. */
        data class Forget(val record: TogglRecord) : Action()
        data class Keep(val record: TogglRecord) : Action()
    }

    /** Does this entry look like what a pending record's POST would have made? */
    fun matches(r: TogglRecord, s: TogglSeen): Boolean =
        s.workspaceId == r.workspaceId && s.startSec == r.pendingStart && s.durationSec == r.pendingDur &&
            (!r.pendingTags || s.tags.any { it == TogglDay.TAG_NOW })

    /**
     * Brings one day in line. [desired]: what the log wants. [recorded]: this app's records for the
     * day, pending ones included. [released]: keys deleted in Toggl, left alone for good. [seen]: the
     * day's entries as Toggl lists them, used only to recognise the entry a pending record's POST
     * made (empty when no record is pending). Only entries with a record are ever updated or deleted.
     * Deletes come first, so a changed key never shows twice for long.
     */
    fun plan(desired: List<TogglWant>, recorded: List<TogglRecord>, released: Set<String>, seen: List<TogglSeen>): List<Action> {
        val out = ArrayList<Action>()
        val records = recorded.associateBy { it.key }
        val usable = desired.filter { it.key !in released }.distinctBy { it.key }
        val wanted = usable.map { it.key }.toSet()
        val claimed = recorded.filter { !it.pending }.map { it.entryId }.toSet()

        fun twin(r: TogglRecord): TogglSeen? =
            seen.filter { it.id !in claimed && matches(r, it) }.minByOrNull { it.id }

        recorded.filter { it.key !in wanted && it.key !in released }.forEach { r ->
            if (r.pending) {
                val t = twin(r)
                out.add(if (t != null) Action.Delete(r.copy(entryId = t.id)) else Action.Forget(r))
            } else {
                out.add(Action.Delete(r))
            }
        }
        usable.forEach { d ->
            val r = records[d.key]
            when {
                r == null -> out.add(Action.Create(d))
                r.pending -> {
                    val t = twin(r)
                    when {
                        t != null && r.workspaceId == d.workspaceId -> out.add(Action.Adopt(t.id, r, d, r.hash != d.hash))
                        t != null -> {
                            out.add(Action.Delete(r.copy(entryId = t.id)))
                            out.add(Action.Create(d))
                        }
                        else -> out.add(Action.Create(d))
                    }
                }
                r.workspaceId != d.workspaceId -> {
                    out.add(Action.Delete(r))
                    out.add(Action.Create(d))
                }
                r.hash != d.hash -> out.add(Action.Update(r, d))
                else -> out.add(Action.Keep(r))
            }
        }
        return out
    }
}

/** The export's decisions for one day, end to end, without Android. */
object TogglSync {
    private const val HOUR = 3_600_000L

    /** Oldest diary day first (keys are unpadded `2026-11-8`, so they don't sort as text). */
    fun oldestFirst(keys: Collection<String>): List<String> = keys.sortedWith(compareBy({ part(it, 0) }, { part(it, 1) }, { part(it, 2) }))

    private fun part(key: String, i: Int): Int = key.split('-').getOrNull(i)?.toIntOrNull() ?: Int.MAX_VALUE

    /**
     * Writes one day: read the change counter, recognise a pending record's entry (one listing, only
     * when some record is pending), plan, apply in order, and clear the day only when every action
     * worked and nothing changed it meanwhile. A [TogglStop] ends the whole run and leaves the day
     * dirty; every record is saved as it goes, so the next run carries on from there. A key's old
     * entry that can't be deleted stops the create for that key. Tags Toggl refuses are retried once
     * without tags, with a warning.
     */
    fun writeDay(
        api: TogglApi,
        ledger: TogglLedger,
        day: String,
        dayStart: Long,
        dayEnd: Long,
        desired: List<TogglWant>,
        stats: TogglStats,
        errors: MutableList<String>,
        warnings: MutableList<String>,
    ): Boolean {
        val gen = ledger.gen(day)
        val records = ledger.records(day)
        val released = ledger.released(day)
        if (desired.isEmpty() && records.isEmpty()) return ledger.clearIfUnchanged(day, gen)
        val seen = if (records.any { it.pending }) api.entriesBetween(dayStart - HOUR, dayEnd + HOUR) else emptyList()
        val actions = TogglPlan.plan(desired, records, released, seen)
        val failedDeletes = HashSet<String>()
        var ok = true
        actions.forEach { a ->
            try {
                when (a) {
                    is TogglPlan.Action.Create -> {
                        if (a.want.key in failedDeletes) return@forEach
                        create(api, ledger, a.want, stats, warnings)
                    }
                    is TogglPlan.Action.Update -> {
                        withTagFallback(a.want, warnings) { tags -> api.update(a.record.workspaceId, a.record.entryId, a.want, tags) }
                        ledger.save(TogglRecord(a.want.key, a.record.workspaceId, a.record.entryId, a.want.hash))
                        stats.updated++
                    }
                    is TogglPlan.Action.Adopt -> {
                        // Record first, with no hash: if the update is cut short, the next run updates it again.
                        ledger.save(TogglRecord(a.want.key, a.record.workspaceId, a.entryId, if (a.needsUpdate) "" else a.want.hash))
                        if (a.needsUpdate) {
                            withTagFallback(a.want, warnings) { tags -> api.update(a.record.workspaceId, a.entryId, a.want, tags) }
                            ledger.save(TogglRecord(a.want.key, a.record.workspaceId, a.entryId, a.want.hash))
                            stats.updated++
                        }
                    }
                    is TogglPlan.Action.Delete -> {
                        try {
                            api.delete(a.record.workspaceId, a.record.entryId)
                            stats.deleted++
                        } catch (e: TogglGone) {
                            // Already gone from Toggl: nothing left to delete.
                        } catch (e: TogglStop) {
                            throw e
                        } catch (e: Exception) {
                            failedDeletes.add(a.record.key)
                            throw e
                        }
                        ledger.drop(a.record.key)
                    }
                    is TogglPlan.Action.Forget -> ledger.drop(a.record.key)
                    is TogglPlan.Action.Keep -> Unit
                }
            } catch (e: TogglStop) {
                throw e
            } catch (e: TogglGone) {
                // An update of an entry that's gone from Toggl: he deleted it there. Never made again.
                if (a is TogglPlan.Action.Update) ledger.release(a.record, "deleted in Toggl")
                else if (a is TogglPlan.Action.Adopt) ledger.release(TogglRecord(a.want.key, a.record.workspaceId, a.entryId, ""), "deleted in Toggl")
                else {
                    ok = false
                    errors.add(day + ": " + (e.message ?: "not found"))
                }
            } catch (e: Exception) {
                ok = false
                errors.add(day + ": " + (e.message ?: e.javaClass.simpleName))
            }
        }
        return ok && ledger.clearIfUnchanged(day, gen)
    }

    private fun create(api: TogglApi, ledger: TogglLedger, w: TogglWant, stats: TogglStats, warnings: MutableList<String>) {
        // Pending first: if the POST happens but its id isn't saved, the next run recognises it by start and duration.
        ledger.save(TogglRecord(w.key, w.workspaceId, 0L, w.hash, w.startSec, w.durationSec, true))
        try {
            val id = withTagFallback(w, warnings) { tags ->
                if (!tags) ledger.save(TogglRecord(w.key, w.workspaceId, 0L, w.hash, w.startSec, w.durationSec, false))
                api.create(w, tags)
            }
            ledger.save(TogglRecord(w.key, w.workspaceId, id, w.hash))
            stats.created++
        } catch (e: TogglUnknown) {
            throw e
        } catch (e: TogglStop) {
            // Nothing was sent that could have made it: no pending record.
            ledger.drop(w.key)
            throw e
        } catch (e: Exception) {
            ledger.drop(w.key)
            throw e
        }
    }

    /**
     * Calls [send] with tags; when Toggl refuses it (HTTP 400, not a stop) and there were tags,
     * calls it once more without them and says so in [warnings].
     */
    private fun <T> withTagFallback(w: TogglWant, warnings: MutableList<String>, send: (Boolean) -> T): T {
        try {
            return send(true)
        } catch (e: TogglRejected) {
            if (e.code != 400 || w.tags.isEmpty()) throw e
            warnings.add("Toggl wouldn't take the tags for ${w.key}, so it was sent without them.")
            return send(false)
        }
    }

    /** What "Remove sent entries from Toggl" did so far. */
    data class Removal(val removed: Int, val gone: Int, val failed: Int, val unresolved: Int, val stop: TogglStop?)

    /**
     * Deletes the entries the app made, oldest first, each by its recorded workspace and id; nothing
     * else is ever listed or touched. A record is [drop]ped once its entry is deleted or found gone.
     * A pending record (a POST whose answer never came) is left alone and counted as unresolved. A
     * [TogglStop] ends the pass; what's left stays recorded for the next one.
     */
    fun removeAll(api: TogglApi, records: List<TogglRecord>, drop: (TogglRecord) -> Unit): Removal {
        var removed = 0
        var gone = 0
        var failed = 0
        val unresolved = records.count { it.pending }
        val byDay = oldestFirst(records.filter { !it.pending }.map { CalendarDay.dayOf(it.key) }.toSet())
        val todo = byDay.flatMap { d -> records.filter { !it.pending && CalendarDay.dayOf(it.key) == d } }
        try {
            todo.forEach { r ->
                try {
                    api.delete(r.workspaceId, r.entryId)
                    removed++
                } catch (e: TogglGone) {
                    gone++
                } catch (e: TogglStop) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    return@forEach
                }
                drop(r)
            }
        } catch (e: TogglStop) {
            return Removal(removed, gone, failed, unresolved, e)
        }
        return Removal(removed, gone, failed, unresolved, null)
    }
}
