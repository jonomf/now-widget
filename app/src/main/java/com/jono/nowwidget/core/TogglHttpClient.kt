package com.jono.nowwidget.core

/** Nothing could have gone out: no network, no DNS, the connection wasn't made. */
class TogglNotSent(message: String) : Exception(message)

/** The request was (at least partly) written and no reply came back, so it may have been made. */
class TogglMaybeSent(message: String) : Exception(message)

/**
 * The wire. The real one (UrlTransport) is HttpURLConnection with the token in the Authorization
 * header and a fixed-length body, so the stack can never silently send a POST twice; tests use a
 * fake. Throws [TogglNotSent] or [TogglMaybeSent]; any HTTP status is a reply, not an exception.
 */
interface TogglTransport {
    fun send(method: String, path: String, body: String?): TogglReply
}

/** What the client keeps between runs. The real one is Prefs; tests use a map. */
interface TogglClientStore {
    /** Taken around the request count, so two clients never overwrite each other's. */
    val lock: Any

    fun budget(): String?

    /** Written for good before the call returns: the count must survive the app being killed right after a request. */
    fun saveBudget(v: String)

    fun projects(ws: Long): Map<String, Long>
    fun addProjects(ws: Long, add: Map<String, Long>)
    fun forgetProject(ws: Long, key: String)
    fun listed(ws: Long): Boolean
    fun setListed(ws: Long, on: Boolean)

    /** Toggl won't make projects on this workspace's plan. */
    fun noProjects(ws: Long): Boolean
    fun setNoProjects(ws: Long)

    /** Toggl refused this activity's project name: not tried again until "Send now". */
    fun refused(ws: Long, key: String): Boolean
    fun addRefused(ws: Long, key: String)
}

/**
 * Toggl Track's API v9 on top of a [TogglTransport]. Every request is counted in the [RequestBudget]
 * before it goes (25 an hour, 1.1 s apart, and none while Toggl's own headers or a quota reply say
 * the limit is near): a request that would go over throws [TogglStop] instead of being sent.
 * Background threads only. [warnings] hears about things worth telling the user.
 */
class TogglHttpClient(
    private val transport: TogglTransport,
    private val store: TogglClientStore,
    private val now: () -> Long,
    private val sleep: (Long) -> Unit,
    private val warnings: MutableList<String> = ArrayList(),
) : TogglApi {
    companion object {
        private const val PAGE = 200
        private const val MAX_PAGES = 5
    }

    /**
     * Sends one request and returns the body of a 2xx reply. Throws [TogglStop] (the budget, a quota
     * reply, no connection, a bad token...), [TogglGone] for 404, [TogglRejected] for the other
     * refusals. [createsEntry]: a POST that makes an entry, whose lost answer is [TogglUnknown], and
     * so is a 5xx. [entryLevel]: update or delete of one entry (403 is then a refusal of that one).
     */
    private fun call(
        method: String, path: String, body: String?, bucket: String,
        createsEntry: Boolean = false, projectCreate: Boolean = false, entryLevel: Boolean = false,
    ): String {
        synchronized(store.lock) {
            val b = RequestBudget.decode(store.budget())
            val t = now()
            val wait = b.waitMs(bucket, t)
            if (wait > 0L) throw TogglStop(StopKind.QUOTA, "Toggl's hourly request limit is used up (the app keeps to ${RequestBudget.LIMIT} an hour)", wait)
            val pace = b.paceMs(t)
            if (pace > 0L) sleep(pace)
            b.record(now())
            store.saveBudget(b.encode())
        }
        val reply = try {
            transport.send(method, path, body)
        } catch (e: TogglNotSent) {
            throw TogglStop(StopKind.NETWORK, e.message ?: "Couldn't reach Toggl")
        } catch (e: TogglMaybeSent) {
            val why = e.message ?: "Toggl didn't answer"
            if (createsEntry) throw TogglUnknown(why)
            throw TogglStop(StopKind.NETWORK, why)
        }
        val outcome = TogglHttp.classify(reply, projectCreate, entryLevel)
        val quotaHit = outcome is TogglHttp.Outcome.Stop && outcome.stop.kind == StopKind.QUOTA
        synchronized(store.lock) {
            val b = RequestBudget.decode(store.budget())
            b.onReply(bucket, now(), reply.quotaRemaining, reply.resetsInSec, quotaHit)
            store.saveBudget(b.encode())
        }
        return when (outcome) {
            is TogglHttp.Outcome.Ok -> reply.body
            is TogglHttp.Outcome.Gone -> throw TogglGone()
            is TogglHttp.Outcome.Rejected -> throw TogglRejected(outcome.code, outcome.why)
            is TogglHttp.Outcome.Stop -> {
                // A POST that got a 5xx may still have made the entry.
                if (createsEntry && outcome.stop.kind == StopKind.SERVER) throw TogglUnknown(outcome.stop.message ?: "Toggl had a problem")
                throw outcome.stop
            }
        }
    }

    private fun parseList(text: String): List<*>? = try {
        Json.parse(text) as? List<*>
    } catch (e: Exception) {
        null
    }

    private fun idOf(text: String): Long? = try {
        ((Json.parse(text) as? Map<*, *>)?.get("id") as? Number)?.toLong()
    } catch (e: Exception) {
        null
    }

    // --- Entries ---

    override fun create(w: TogglWant, tags: Boolean): Long {
        val text = withProject(w) { pid ->
            call("POST", "/workspaces/${w.workspaceId}/time_entries", TogglJson.body(w, pid, tags), TogglBucket.WORKSPACE, createsEntry = true)
        }
        // The entry exists now: an answer without its id is recognised next run, like a lost one.
        return idOf(text) ?: throw TogglUnknown("Toggl's answer had no id")
    }

    override fun update(workspaceId: Long, entryId: Long, w: TogglWant, tags: Boolean) {
        withProject(w) { pid ->
            call("PUT", "/workspaces/$workspaceId/time_entries/$entryId", TogglJson.body(w, pid, tags), TogglBucket.WORKSPACE, entryLevel = true)
        }
    }

    override fun delete(workspaceId: Long, entryId: Long) {
        call("DELETE", "/workspaces/$workspaceId/time_entries/$entryId", null, TogglBucket.WORKSPACE, entryLevel = true)
    }

    override fun entriesSince(sinceSec: Long): List<TogglSeen> {
        val text = call("GET", "/me/time_entries?since=" + maxOf(0L, sinceSec), null, TogglBucket.ME)
        return try {
            TogglJson.seen(text)
        } catch (e: Exception) {
            throw TogglStop(StopKind.SERVER, "Toggl's list of entries couldn't be read")
        }
    }

    // --- Setup ---

    /** The user's id: tells one Toggl account from another. One request against the /me quota. */
    fun me(): Long {
        val text = call("GET", "/me", null, TogglBucket.ME)
        return idOf(text) ?: throw TogglStop(StopKind.SERVER, "Toggl's answer about the account couldn't be read")
    }

    /** The user's workspaces as (id, name). One request against the /me quota; a bad token ends as a fatal [TogglStop]. */
    fun workspaces(): List<Pair<Long, String>> {
        val text = call("GET", "/me/workspaces", null, TogglBucket.ME)
        val arr = parseList(text) ?: throw TogglStop(StopKind.SERVER, "Toggl's list of workspaces couldn't be read")
        return arr.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val id = (m["id"] as? Number)?.toLong() ?: return@mapNotNull null
            id to ((m["name"] as? String)?.ifBlank { null } ?: "Workspace $id")
        }
    }

    // --- Projects: the activity's name, made once and remembered ---

    /**
     * Sends with the entry's project. A 400 that names the project, or any 400 on a request that had
     * a project id (unless it is plainly about tags), means the remembered id may be stale: it's
     * forgotten, the workspace's list is read again, and the request is sent ONCE more.
     */
    private fun <T> withProject(w: TogglWant, send: (Long?) -> T): T {
        val pid = projectId(w)
        try {
            return send(pid)
        } catch (e: TogglRejected) {
            if (e.code != 400) throw e
            val m = (e.message ?: "").lowercase()
            val aboutProject = "project" in m
            val aboutTags = "tag" in m
            if (!(aboutProject || (pid != null && !aboutTags))) throw e
            store.forgetProject(w.workspaceId, w.project.trim().lowercase())
            store.setListed(w.workspaceId, false)
            val fresh = projectId(w)
            if (fresh == pid) throw e
            return send(fresh)
        }
    }

    /**
     * The project for an entry: remembered by name, else found in the workspace's project list
     * (read once), else made. Null (no project) when Toggl won't make projects on the plan or
     * refuses the name; the entry's description starts with the activity anyway.
     */
    private fun projectId(w: TogglWant): Long? {
        val name = w.project.trim()
        if (name.isEmpty()) return null
        val ws = w.workspaceId
        if (store.noProjects(ws)) return null
        val key = name.lowercase()
        store.projects(ws)[key]?.let { return it }
        if (store.refused(ws, key)) return null
        return try {
            if (!store.listed(ws)) {
                listProjects(ws)
                store.projects(ws)[key]?.let { return it }
            }
            val text = call("POST", "/workspaces/$ws/projects", TogglJson.projectBody(name), TogglBucket.WORKSPACE, projectCreate = true)
            val id = idOf(text)
            if (id == null) {
                store.addRefused(ws, key)
                null
            } else {
                store.addProjects(ws, mapOf(key to id))
                id
            }
        } catch (e: TogglStop) {
            if (e.kind != StopKind.PLAN) throw e
            store.setNoProjects(ws)
            warnings.add("This Toggl plan won't make projects, so entries are sent without one (the activity is first in the description).")
            null
        } catch (e: TogglRejected) {
            store.addRefused(ws, key)
            warnings.add("Toggl wouldn't make a project called \"$name\" (${e.message}), so those entries have no project.")
            null
        }
    }

    /** Reads the workspace's projects into the store. A list that can't be read is not "listed". */
    private fun listProjects(ws: Long) {
        val found = HashMap<String, Long>()
        var page = 1
        while (page <= MAX_PAGES) {
            val text = call("GET", "/workspaces/$ws/projects?per_page=$PAGE&page=$page", null, TogglBucket.WORKSPACE)
            val arr = parseList(text) ?: throw TogglStop(StopKind.SERVER, "Toggl's list of projects couldn't be read")
            arr.forEach { e ->
                val m = e as? Map<*, *> ?: return@forEach
                val id = (m["id"] as? Number)?.toLong() ?: return@forEach
                val n = (m["name"] as? String)?.trim()?.lowercase() ?: return@forEach
                found[n] = id
            }
            if (arr.size < PAGE) break
            page++
        }
        if (found.isNotEmpty()) store.addProjects(ws, found)
        store.setListed(ws, true)
    }
}
