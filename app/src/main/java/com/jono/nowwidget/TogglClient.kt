package com.jono.nowwidget

import android.content.Context
import com.jono.nowwidget.core.Json
import com.jono.nowwidget.core.RequestBudget
import com.jono.nowwidget.core.StopKind
import com.jono.nowwidget.core.TogglApi
import com.jono.nowwidget.core.TogglBucket
import com.jono.nowwidget.core.TogglGone
import com.jono.nowwidget.core.TogglHttp
import com.jono.nowwidget.core.TogglJson
import com.jono.nowwidget.core.TogglRejected
import com.jono.nowwidget.core.TogglReply
import com.jono.nowwidget.core.TogglSeen
import com.jono.nowwidget.core.TogglStop
import com.jono.nowwidget.core.TogglUnknown
import com.jono.nowwidget.core.TogglWant
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * Toggl Track's API v9 over plain HttpURLConnection. Background threads only. Every request is
 * counted in the shared [RequestBudget] before it goes (at most 25 an hour, 1.1 seconds apart, and
 * none while Toggl's own headers or a quota reply say the limit is near), and a request that would
 * go over throws [TogglStop] instead of being sent. The token is only ever put in the
 * Authorization header; it is never logged and never part of a message.
 *
 * [warnings] hears about things worth telling the user (a plan without projects).
 */
class TogglClient(
    private val ctx: Context,
    private val token: String,
    private val warnings: MutableList<String> = ArrayList(),
) : TogglApi {
    private val missingProjects = HashSet<String>()

    companion object {
        const val BASE = "https://api.track.toggl.com/api/v9"
        private const val CONNECT_MS = 15_000
        private const val READ_MS = 30_000
        private const val MAX_BODY = 4 * 1024 * 1024
        private const val PAGE = 200
        private const val MAX_PAGES = 5

        /** One at a time across clients, so the count in the preferences is never overwritten. */
        private val LOCK = Any()
    }

    private fun basic(): String = "Basic " + Base64.getEncoder().encodeToString("$token:api_token".toByteArray(Charsets.UTF_8))

    private fun read(s: InputStream?): String {
        if (s == null) return ""
        s.use {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                if (out.size() + n > MAX_BODY) break
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8")
        }
    }

    /**
     * Sends one request and returns the body of a 2xx reply. Throws [TogglStop] (the budget, a
     * quota reply, no connection, a bad token...), [TogglGone] for 404, [TogglRejected] for the
     * other refusals. [createsEntry]: a POST that makes an entry, whose lost answer is [TogglUnknown].
     */
    private fun call(method: String, path: String, body: String?, bucket: String, createsEntry: Boolean = false, projectCreate: Boolean = false): String {
        synchronized(LOCK) {
            val b = RequestBudget.decode(Prefs.togglBudget(ctx))
            val now = System.currentTimeMillis()
            val wait = b.waitMs(bucket, now)
            if (wait > 0L) throw TogglStop(StopKind.QUOTA, "Toggl's hourly request limit is used up (the app keeps to ${RequestBudget.LIMIT} an hour)", wait)
            val pace = b.paceMs(now)
            if (pace > 0L) Thread.sleep(pace)
            b.record(System.currentTimeMillis())
            Prefs.setTogglBudget(ctx, b.encode())
        }
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = CONNECT_MS
            conn.readTimeout = READ_MS
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("Authorization", basic())
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            val bytes = body?.toByteArray(Charsets.UTF_8)
            if (bytes != null) conn.doOutput = true
            try {
                conn.connect()
            } catch (e: IOException) {
                // Nothing was sent.
                throw TogglStop(StopKind.NETWORK, "Couldn't reach Toggl (" + e.javaClass.simpleName + ")")
            }
            val code: Int
            val text: String
            try {
                if (bytes != null) conn.outputStream.use { it.write(bytes) }
                code = conn.responseCode
                text = read(if (code >= 400) conn.errorStream else conn.inputStream)
            } catch (e: IOException) {
                val why = "Toggl didn't answer (" + e.javaClass.simpleName + ")"
                if (createsEntry) throw TogglUnknown(why)
                throw TogglStop(StopKind.NETWORK, why)
            }
            val reply = TogglReply(
                code, text,
                conn.getHeaderField("X-Toggl-Quota-Remaining")?.trim()?.toIntOrNull(),
                conn.getHeaderField("X-Toggl-Quota-Resets-In")?.trim()?.toLongOrNull(),
            )
            val outcome = TogglHttp.classify(reply, projectCreate)
            val quotaHit = outcome is TogglHttp.Outcome.Stop && outcome.stop.kind == StopKind.QUOTA
            synchronized(LOCK) {
                val b = RequestBudget.decode(Prefs.togglBudget(ctx))
                b.onReply(bucket, System.currentTimeMillis(), reply.quotaRemaining, reply.resetsInSec, quotaHit)
                Prefs.setTogglBudget(ctx, b.encode())
            }
            return when (outcome) {
                is TogglHttp.Outcome.Ok -> text
                is TogglHttp.Outcome.Gone -> throw TogglGone()
                is TogglHttp.Outcome.Rejected -> throw TogglRejected(outcome.code, outcome.why)
                is TogglHttp.Outcome.Stop -> {
                    // A POST that got a 5xx may still have made the entry.
                    if (createsEntry && outcome.stop.kind == StopKind.SERVER) throw TogglUnknown(outcome.stop.message ?: "Toggl had a problem")
                    throw outcome.stop
                }
            }
        } finally {
            conn.disconnect()
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
        val pid = projectId(w)
        val text = call("POST", "/workspaces/${w.workspaceId}/time_entries", TogglJson.body(w, pid, tags), TogglBucket.WORKSPACE, createsEntry = true)
        // The entry exists now: an answer without its id is recognised next run, like a lost one.
        return idOf(text) ?: throw TogglUnknown("Toggl's answer had no id")
    }

    override fun update(workspaceId: Long, entryId: Long, w: TogglWant, tags: Boolean) {
        val pid = projectId(w)
        call("PUT", "/workspaces/$workspaceId/time_entries/$entryId", TogglJson.body(w, pid, tags), TogglBucket.WORKSPACE)
    }

    override fun delete(workspaceId: Long, entryId: Long) {
        call("DELETE", "/workspaces/$workspaceId/time_entries/$entryId", null, TogglBucket.WORKSPACE)
    }

    override fun entriesBetween(from: Long, to: Long): List<TogglSeen> {
        fun q(ms: Long) = URLEncoder.encode(TogglJson.iso(Math.floorDiv(ms, 1000L)), "UTF-8")
        val text = call("GET", "/me/time_entries?start_date=${q(from)}&end_date=${q(to)}", null, TogglBucket.ME)
        return try {
            TogglJson.seen(text)
        } catch (e: Exception) {
            throw TogglStop(StopKind.SERVER, "Toggl's list of entries couldn't be read")
        }
    }

    // --- Setup ---

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
     * The project for an entry: remembered by name, else found in the workspace's project list
     * (read once), else made. Null (no project) when Toggl won't make projects on the plan or
     * refuses the name; the entry's description starts with the activity anyway.
     */
    private fun projectId(w: TogglWant): Long? {
        val name = w.project.trim()
        if (name.isEmpty()) return null
        val ws = w.workspaceId
        if (Prefs.togglNoProjects(ctx, ws)) return null
        val key = name.lowercase()
        Prefs.togglProjects(ctx, ws)[key]?.let { return it }
        if (key in missingProjects) return null
        return try {
            if (!Prefs.togglProjectsListed(ctx, ws)) {
                listProjects(ws)
                Prefs.togglProjects(ctx, ws)[key]?.let { return it }
            }
            val text = call("POST", "/workspaces/$ws/projects", TogglJson.projectBody(name), TogglBucket.WORKSPACE, projectCreate = true)
            val id = idOf(text)
            if (id == null) {
                missingProjects.add(key)
                null
            } else {
                Prefs.addTogglProjects(ctx, ws, mapOf(key to id))
                id
            }
        } catch (e: TogglStop) {
            if (e.kind != StopKind.PLAN) throw e
            Prefs.setTogglNoProjects(ctx, ws)
            warnings.add("This Toggl plan won't make projects, so entries are sent without one (the activity is first in the description).")
            null
        } catch (e: TogglRejected) {
            missingProjects.add(key)
            warnings.add("Toggl wouldn't make a project called \"$name\" (${e.message}), so those entries have no project.")
            null
        }
    }

    private fun listProjects(ws: Long) {
        val found = HashMap<String, Long>()
        var page = 1
        while (page <= MAX_PAGES) {
            val text = call("GET", "/workspaces/$ws/projects?per_page=$PAGE&page=$page", null, TogglBucket.WORKSPACE)
            val arr = parseList(text) ?: break
            arr.forEach { e ->
                val m = e as? Map<*, *> ?: return@forEach
                val id = (m["id"] as? Number)?.toLong() ?: return@forEach
                val n = (m["name"] as? String)?.trim()?.lowercase() ?: return@forEach
                found[n] = id
            }
            if (arr.size < PAGE) break
            page++
        }
        if (found.isNotEmpty()) Prefs.addTogglProjects(ctx, ws, found)
        Prefs.setTogglProjectsListed(ctx, ws)
    }
}
