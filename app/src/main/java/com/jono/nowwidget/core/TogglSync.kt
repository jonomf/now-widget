package com.jono.nowwidget.core

/**
 * Toggl as the export sees it. The real one is [TogglHttpClient] (counted against the hourly
 * budget); tests use a fake. Every call may throw [TogglStop] (the run must stop), [TogglGone]
 * (404), or [TogglRejected] (Toggl refused this one request). A create that gets no answer throws
 * [TogglUnknown].
 */
interface TogglApi {
    /** Creates the entry (in [TogglWant.workspaceId], its project made if need be) and returns its id. [tags] false leaves tags out. */
    fun create(w: TogglWant, tags: Boolean): Long

    fun update(workspaceId: Long, entryId: Long, w: TogglWant, tags: Boolean)
    fun delete(workspaceId: Long, entryId: Long)

    /** The user's entries created or changed since [sinceSec] (unix seconds), deleted ones included. One request against the /me quota. */
    fun entriesSince(sinceSec: Long): List<TogglSeen>
}

/** What the export remembers. The real one is the Store (export_state and export_day). */
interface TogglLedger {
    fun gen(day: String): Int
    fun clearIfUnchanged(day: String, gen: Int): Boolean

    /** This day's records, pending ones included. */
    fun records(day: String): List<TogglRecord>

    /** Keys of this day's entries that were deleted in Toggl (or refused changes): never written again. */
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

    /** Some day was left dirty on purpose (an entry was adopted and is acted on next run): run again soon. */
    var again = false

    /** Keys of pending records that couldn't be settled (several candidates, or the lookup was refused). */
    val unresolved = LinkedHashSet<String>()
    val requestsWorked: Int get() = created + updated + deleted
}

/** What to do to one day's entries. */
object TogglPlan {
    /** How many minutes before a pending record was saved an entry may have been made and still count as its entry. */
    const val SLACK_MS = 5L * 60_000L

    sealed class Action {
        /** Saves a pending record first, then POSTs, then records the id. */
        data class Create(val want: TogglWant) : Action()
        data class Update(val record: TogglRecord, val want: TogglWant) : Action()

        /**
         * A pending record's entry was found in Toggl: only record it. Whatever follows (an update,
         * a delete) is decided from the record on a later run. [settled]: nothing more to do for the day.
         */
        data class Adopt(val entryId: Long, val record: TogglRecord, val settled: Boolean) : Action()

        /** If it fails, no create for the same key follows. */
        data class Delete(val record: TogglRecord) : Action()

        /** A pending record whose entry was never made: only the record goes. */
        data class Forget(val record: TogglRecord) : Action()

        /** Several entries match (or the lookup failed): nothing is created, updated or deleted for this key. */
        data class Unresolved(val record: TogglRecord) : Action()
        data class Keep(val record: TogglRecord) : Action()
    }

    /**
     * Does this entry look like what a pending record's POST made? Every one of: not deleted, the
     * workspace, the start second, the duration, the description (by hash), and (if it was sent with
     * tags) the `now` tag.
     */
    fun matches(r: TogglRecord, s: TogglSeen): Boolean =
        !s.deleted && s.workspaceId == r.workspaceId && s.startSec == r.pendingStart && s.durationSec == r.pendingDur &&
            r.pendingDesc.isNotEmpty() && TogglDay.descHash(s.description) == r.pendingDesc &&
            (!r.pendingTags || s.tags.any { it == TogglDay.TAG_NOW })

    /**
     * Brings one day in line. [desired]: what the log wants. [recorded]: this app's records for the
     * day, pending ones included. [released]: keys left alone for good. [seen]: entries Toggl listed
     * since the oldest pending record, used only to recognise the entry a pending record's POST made;
     * null when that lookup was refused (every pending record is then unresolved). With
     * [allowNew] false, runs with no record are not sent (days before "Send past days" keep only
     * what they have). Only entries with a record are ever updated or deleted, and an entry that was
     * just matched to a pending record is only recorded, never acted on in the same run.
     */
    fun plan(desired: List<TogglWant>, recorded: List<TogglRecord>, released: Set<String>, seen: List<TogglSeen>?, allowNew: Boolean = true): List<Action> {
        val out = ArrayList<Action>()
        val records = recorded.associateBy { it.key }
        val usable = desired.filter { it.key !in released }.distinctBy { it.key }
        val wanted = usable.map { it.key }.toSet()
        val claimed = recorded.filter { !it.pending }.map { it.entryId }.toSet()

        /** null: can't tell (no lookup, or several candidates); empty: none; one: the entry. */
        fun candidates(r: TogglRecord): List<TogglSeen>? =
            if (seen == null) null else seen.filter { it.id !in claimed && matches(r, it) }.let { if (it.size > 1) null else it }

        recorded.filter { it.key !in wanted && it.key !in released }.forEach { r ->
            if (!r.pending) {
                out.add(Action.Delete(r))
                return@forEach
            }
            val c = candidates(r)
            out.add(when {
                c == null -> Action.Unresolved(r)
                c.isEmpty() -> Action.Forget(r)
                else -> Action.Adopt(c[0].id, r, false)
            })
        }
        usable.forEach { d ->
            val r = records[d.key]
            when {
                r == null -> if (allowNew) out.add(Action.Create(d))
                r.pending -> {
                    val c = candidates(r)
                    when {
                        c == null -> out.add(Action.Unresolved(r))
                        c.isEmpty() -> out.add(Action.Create(d))
                        else -> out.add(Action.Adopt(c[0].id, r, r.workspaceId == d.workspaceId && r.hash == d.hash))
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
    /** Oldest diary day first (keys are unpadded `2026-11-8`, so they don't sort as text). */
    fun oldestFirst(keys: Collection<String>): List<String> = keys.sortedWith(compareBy({ part(it, 0) }, { part(it, 1) }, { part(it, 2) }))

    /** Today first, then yesterday, then the backlog oldest first. */
    fun priorityOrder(keys: Collection<String>, today: String, yesterday: String): List<String> {
        val set = keys.toSet()
        return listOfNotNull(today.takeIf { it in set }, yesterday.takeIf { it in set && it != today }) +
            oldestFirst(set.filter { it != today && it != yesterday })
    }

    private fun part(key: String, i: Int): Int = key.split('-').getOrNull(i)?.toIntOrNull() ?: Int.MAX_VALUE

    /**
     * Writes one day: read the change counter, recognise a pending record's entry (one listing of
     * what changed since it was saved, only when some record is pending), plan, apply in order, and
     * return true (cleared) only when every action worked, nothing was left for a later run, and
     * nothing changed the day meanwhile. A [TogglStop] ends the whole run and leaves the day dirty;
     * every record is saved as it goes, so the next run carries on from there. A key's old entry that
     * can't be deleted stops the create for that key. A 400 about tags is retried once without
     * tags, with a warning. A 403 on an update or delete of one entry releases that record.
     * [now] is the clock, for the time a pending record is saved at.
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
        now: Long,
        allowNew: Boolean = true,
    ): Boolean {
        val gen = ledger.gen(day)
        val records = ledger.records(day)
        val released = ledger.released(day)
        if (desired.isEmpty() && records.isEmpty()) return ledger.clearIfUnchanged(day, gen)
        var seen: List<TogglSeen>? = emptyList()
        val pending = records.filter { it.pending }
        if (pending.isNotEmpty()) {
            val since = maxOf(0L, pending.minOf { it.pendingSavedAt } - TogglPlan.SLACK_MS) / 1000L
            seen = try {
                api.entriesSince(since)
            } catch (e: TogglRejected) {
                // The lookup itself was refused: the rest of the day carries on, those records stay unresolved.
                warnings.add("Toggl wouldn't list entries to check $day (${e.message}); ${pending.size} sent while the connection dropped stay unchecked.")
                null
            }
        }
        val actions = TogglPlan.plan(desired, records, released, seen, allowNew)
        val failedDeletes = HashSet<String>()
        var ok = true
        var left = false
        actions.forEach { a ->
            try {
                when (a) {
                    is TogglPlan.Action.Create -> {
                        if (a.want.key in failedDeletes) return@forEach
                        create(api, ledger, a.want, stats, warnings, now)
                    }
                    is TogglPlan.Action.Update -> {
                        withTagFallback(a.want, warnings) { tags -> api.update(a.record.workspaceId, a.record.entryId, a.want, tags) }
                        ledger.save(TogglRecord(a.want.key, a.record.workspaceId, a.record.entryId, a.want.hash))
                        stats.updated++
                    }
                    is TogglPlan.Action.Adopt -> {
                        ledger.save(TogglRecord(a.record.key, a.record.workspaceId, a.entryId, a.record.hash))
                        if (!a.settled) {
                            left = true
                            stats.again = true
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
                    is TogglPlan.Action.Unresolved -> stats.unresolved.add(a.record.key)
                    is TogglPlan.Action.Keep -> Unit
                }
            } catch (e: TogglStop) {
                throw e
            } catch (e: TogglGone) {
                // An update of an entry that's gone from Toggl: he deleted it there. Never made again.
                if (a is TogglPlan.Action.Update) ledger.release(a.record, "deleted in Toggl")
                else {
                    ok = false
                    errors.add(day + ": " + (e.message ?: "not found"))
                }
            } catch (e: TogglRejected) {
                val r = when {
                    e.code != 403 -> null
                    a is TogglPlan.Action.Update -> a.record
                    a is TogglPlan.Action.Delete -> a.record
                    else -> null
                }
                if (r != null) {
                    ledger.release(r, "Toggl refused changes (403)")
                    warnings.add("Toggl wouldn't let the app change the entry for ${r.key}, so it is left alone from now on.")
                } else {
                    ok = false
                    errors.add(day + ": " + (e.message ?: e.javaClass.simpleName))
                }
            } catch (e: Exception) {
                ok = false
                errors.add(day + ": " + (e.message ?: e.javaClass.simpleName))
            }
        }
        return ok && !left && ledger.clearIfUnchanged(day, gen)
    }

    private fun create(api: TogglApi, ledger: TogglLedger, w: TogglWant, stats: TogglStats, warnings: MutableList<String>, now: Long) {
        // Pending first: if the POST happens but its id isn't saved, the next run recognises it by
        // start, duration, description and tag, among entries made since this time.
        fun pending(tags: Boolean) = TogglRecord(w.key, w.workspaceId, 0L, w.hash, w.startSec, w.durationSec, tags, now, TogglDay.descHash(w.description))
        ledger.save(pending(true))
        val id = try {
            withTagFallback(w, warnings) { tags ->
                if (!tags) ledger.save(pending(false))
                api.create(w, tags)
            }
        } catch (e: TogglUnknown) {
            throw e
        } catch (e: TogglStop) {
            // Stopped before the request went out, or answered with a refusal: no entry was made.
            ledger.drop(w.key)
            throw e
        } catch (e: TogglRejected) {
            ledger.drop(w.key)
            throw e
        }
        // Anything else (the database failing after the POST, say) leaves the pending record, which
        // the next run settles by looking the entry up.
        ledger.save(TogglRecord(w.key, w.workspaceId, id, w.hash))
        stats.created++
    }

    /**
     * Calls [send] with tags; when Toggl refuses it with a 400 that is about the tags, and there
     * were tags, calls it once more without them and says so in [warnings].
     */
    private fun <T> withTagFallback(w: TogglWant, warnings: MutableList<String>, send: (Boolean) -> T): T {
        try {
            return send(true)
        } catch (e: TogglRejected) {
            if (e.code != 400 || w.tags.isEmpty() || !(e.message ?: "").contains("tag", ignoreCase = true)) throw e
            warnings.add("Toggl wouldn't take the tags for ${w.key}, so it was sent without them.")
            return send(false)
        }
    }

    /** What "Remove sent entries from Toggl" did so far. */
    data class Removal(val removed: Int, val gone: Int, val failed: Int, val unresolved: Int, val stop: TogglStop?, val paused: Boolean = false)

    /**
     * Deletes the entries the app made, oldest first, each by its recorded workspace and id; nothing
     * else is ever listed or touched. A record is [drop]ped once its entry is deleted or found gone.
     * A pending record (a POST whose answer never came) is left alone and counted as unresolved. A
     * [TogglStop] ends the pass, and so does [keepGoing] returning false (a time limit); what's left
     * stays recorded for the next one.
     */
    fun removeAll(api: TogglApi, records: List<TogglRecord>, drop: (TogglRecord) -> Unit, keepGoing: () -> Boolean = { true }): Removal {
        var removed = 0
        var gone = 0
        var failed = 0
        val unresolved = records.count { it.pending }
        val byDay = oldestFirst(records.filter { !it.pending }.map { CalendarDay.dayOf(it.key) }.toSet())
        val todo = byDay.flatMap { d -> records.filter { !it.pending && CalendarDay.dayOf(it.key) == d } }
        try {
            todo.forEach { r ->
                if (!keepGoing()) return Removal(removed, gone, failed, unresolved, null, paused = true)
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
