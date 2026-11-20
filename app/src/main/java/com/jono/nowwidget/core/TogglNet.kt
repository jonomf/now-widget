package com.jono.nowwidget.core

/**
 * Talking to Toggl, without the sockets: what a reply means, how many requests may go, and when to
 * try again. Toggl's free plan allows 30 requests an hour per user (the /me endpoints too), a
 * request over the limit gets HTTP 402, and bursts get 429, so everything here is about sending few.
 */

enum class StopKind {
    /** The hourly limit is used up (or nearly). Wait for it to reset. */
    QUOTA,
    /** Too fast (HTTP 429). */
    RATE,
    /** No connection, no DNS, a timeout before any answer, or a write whose answer was lost. */
    NETWORK,
    /** Toggl had a problem (5xx). */
    SERVER,
    /** The token or the workspace isn't accepted. Waiting won't help. */
    AUTH,
    /** Toggl says this needs a paid plan. Waiting won't help. */
    PLAN,
}

/** The whole run has to stop here. [waitMs] is Toggl's own advice, 0 when it gave none. */
open class TogglStop(val kind: StopKind, message: String, val waitMs: Long = 0L) : Exception(message) {
    /** Waiting won't fix it: nothing is retried until the user asks (or the token or workspace changes). */
    val fatal: Boolean get() = kind == StopKind.AUTH || kind == StopKind.PLAN
}

/** A write went out and no answer came back: it may or may not have been made. */
class TogglUnknown(message: String, waitMs: Long = 0L) : TogglStop(StopKind.NETWORK, message, waitMs)

/** Toggl says the entry isn't there (HTTP 404). */
class TogglGone(message: String = "not found") : Exception(message)

/** Toggl refused this one request (400, 409, 422...); other entries may still go. */
class TogglRejected(val code: Int, message: String) : Exception(message)

/** One reply's status, body and Toggl's quota headers (null when missing). */
data class TogglReply(val code: Int, val body: String, val quotaRemaining: Int? = null, val resetsInSec: Long? = null)

object TogglHttp {
    /** A 402 without any sign of the hourly limit is the plan, not the quota. */
    private val QUOTA_WORDS = Regex("(?i)hourly|limit|quota|reset|too many")
    private val SECONDS = Regex("(\\d+)\\s*second")
    private const val DEFAULT_QUOTA_WAIT_S = 600L

    sealed class Outcome {
        object Ok : Outcome()
        object Gone : Outcome()
        data class Stop(val stop: TogglStop) : Outcome()
        data class Rejected(val code: Int, val why: String) : Outcome()
    }

    /** Toggl's own words for what went wrong, one short line. */
    fun message(body: String): String {
        val t = body.trim()
        val text = if (t.startsWith("\"") && t.endsWith("\"") && t.length >= 2) t.substring(1, t.length - 1) else t
        return TogglDay.truncate(text.replace(Regex("\\s+"), " "), 160)
    }

    /**
     * What a reply means. [projectCreate]: the call that makes a project, where 402 and 403 mean
     * "not on your plan" (the entries then go without a project) rather than a bad token.
     * [entryLevel]: an update or delete of one entry, where 403 means "not this one" (a refusal for
     * that request) and not a bad token.
     */
    fun classify(r: TogglReply, projectCreate: Boolean = false, entryLevel: Boolean = false): Outcome {
        val c = r.code
        if (c in 200..299) return Outcome.Ok
        val why = message(r.body)
        return when {
            c == 404 -> Outcome.Gone
            c == 401 -> Outcome.Stop(TogglStop(StopKind.AUTH, "Toggl didn't accept the API token" + suffix(why)))
            c == 402 -> {
                val resets = r.resetsInSec ?: SECONDS.find(r.body)?.groupValues?.get(1)?.toLongOrNull()
                val quota = (r.quotaRemaining != null && r.quotaRemaining <= 0) || r.resetsInSec != null || QUOTA_WORDS.containsMatchIn(r.body)
                if (quota) Outcome.Stop(TogglStop(StopKind.QUOTA, "Toggl's hourly request limit is used up", (resets ?: DEFAULT_QUOTA_WAIT_S) * 1000L + 2_000L))
                else Outcome.Stop(TogglStop(StopKind.PLAN, "Toggl says this needs a paid plan" + suffix(why)))
            }
            c == 403 && entryLevel -> Outcome.Rejected(403, "Toggl wouldn't allow it (403)" + suffix(why))
            c == 403 && projectCreate -> Outcome.Stop(TogglStop(StopKind.PLAN, "Toggl won't let this plan make projects" + suffix(why)))
            c == 403 -> Outcome.Stop(TogglStop(StopKind.AUTH, "Toggl refused access (403)" + suffix(why)))
            c == 429 -> Outcome.Stop(TogglStop(StopKind.RATE, "Toggl says too many requests too fast", (r.resetsInSec ?: 60L) * 1000L))
            c >= 500 -> Outcome.Stop(TogglStop(StopKind.SERVER, "Toggl had a problem (HTTP $c)"))
            else -> Outcome.Rejected(c, "HTTP $c" + suffix(why))
        }
    }

    private fun suffix(why: String) = if (why.isBlank()) "" else ": $why"
}

/** Which of Toggl's limits a request counts against (they're separate counters). */
object TogglBucket {
    const val ME = "me"
    const val WORKSPACE = "ws"
}

/**
 * How many requests the app may send. A request is counted when it's sent; at most [limit] in any
 * [windowMs], spaced at least [spacingMs] apart; and a bucket is closed after a quota reply, or when
 * Toggl's own X-Toggl-Quota-Remaining header says [SAFETY] or fewer are left, until the reset it
 * names. Kept as text between runs ([encode]/[decode]) so the count survives the app being closed.
 */
class RequestBudget(
    private val times: ArrayList<Long> = ArrayList(),
    private val blocked: HashMap<String, Long> = HashMap(),
    /** Per bucket: requests left as of the last reply, and until when that number holds. */
    private val remaining: HashMap<String, Pair<Int, Long>> = HashMap(),
) {
    companion object {
        const val LIMIT = 25
        const val WINDOW_MS = 3_600_000L
        const val SPACING_MS = 1_100L
        const val SAFETY = 5

        fun decode(s: String?): RequestBudget {
            val b = RequestBudget()
            if (s.isNullOrBlank()) return b
            s.split('|').forEach { part ->
                val k = part.substringBefore(':')
                val v = part.substringAfter(':', "")
                when (k) {
                    "t" -> v.split(',').mapNotNullTo(b.times) { it.toLongOrNull() }
                    "b" -> v.split(',').forEach { e -> e.split('=').let { if (it.size == 2) it[1].toLongOrNull()?.let { t -> b.blocked[it[0]] = t } } }
                    "r" -> v.split(',').forEach { e ->
                        val p = e.split('=')
                        if (p.size == 2) {
                            val q = p[1].split('@')
                            val n = q.getOrNull(0)?.toIntOrNull()
                            val until = q.getOrNull(1)?.toLongOrNull()
                            if (n != null && until != null) b.remaining[p[0]] = n to until
                        }
                    }
                }
            }
            return b
        }
    }

    fun encode(): String =
        "t:" + times.joinToString(",") + "|b:" + blocked.entries.joinToString(",") { it.key + "=" + it.value } +
            "|r:" + remaining.entries.joinToString(",") { it.key + "=" + it.value.first + "@" + it.value.second }

    private fun prune(now: Long) {
        // A clock that went backwards leaves later times in place: the count stays, it isn't cleared.
        times.removeAll { it <= now - WINDOW_MS }
        blocked.entries.removeAll { it.value <= now }
        remaining.entries.removeAll { it.value.second <= now }
    }

    /** Requests sent in the last hour. */
    fun used(now: Long): Int {
        prune(now)
        return times.size
    }

    /** How long until a request on [bucket] may go (not counting spacing): 0 when it may go now. */
    fun waitMs(bucket: String, now: Long): Long {
        prune(now)
        var w = 0L
        // Never more than an hour (and a second), whatever the clock did.
        blocked[bucket]?.let { w = maxOf(w, minOf(it - now, WINDOW_MS + 1_000L)) }
        if (times.size >= LIMIT) w = maxOf(w, minOf(times.min() + WINDOW_MS - now, WINDOW_MS) + 1_000L)
        return w
    }

    /** How long to wait so this request is [SPACING_MS] after the last one. */
    fun paceMs(now: Long): Long {
        val last = times.maxOrNull() ?: return 0L
        return maxOf(0L, last + SPACING_MS - now)
    }

    /** A request is being sent now. */
    fun record(now: Long) {
        prune(now)
        times.add(now)
    }

    /**
     * Learns from a reply. [quotaHit]: it was a 402 about the limit. The bucket closes until Toggl's
     * reset when it's hit or [SAFETY] or fewer requests remain.
     */
    fun onReply(bucket: String, now: Long, quotaRemaining: Int?, resetsInSec: Long?, quotaHit: Boolean) {
        val resetMs = (resetsInSec ?: 3_600L) * 1000L + 1_000L
        if (quotaRemaining != null) remaining[bucket] = quotaRemaining to now + resetMs
        if (quotaHit || (quotaRemaining != null && quotaRemaining <= SAFETY)) {
            blocked[bucket] = maxOf(blocked[bucket] ?: 0L, now + (if (quotaHit && resetsInSec == null) 600_000L else resetMs))
        }
    }

    /** Requests left this hour as far as the app knows: our own count and Toggl's last word, whichever is lower. */
    fun left(bucket: String, now: Long): Int {
        prune(now)
        val own = maxOf(0, LIMIT - times.size)
        val theirs = remaining[bucket]?.first
        return if (theirs == null) own else minOf(own, maxOf(0, theirs - SAFETY))
    }

    /** When the oldest counted request ages out of the hour, or null when none is counted. */
    fun freesAt(now: Long): Long? {
        prune(now)
        return times.minOrNull()?.plus(WINDOW_MS)
    }
}

object TogglBackoff {
    const val FIRST_MS = 60_000L
    const val MAX_MS = 3_600_000L

    /** No connection (or a lost answer): try again after this, the job also waits for a network. */
    const val OFFLINE_MS = 120_000L

    /** 1, 2, 4... minutes, up to an hour, for the [failures]th failure in a row (1 or more). */
    fun exponential(failures: Int): Long {
        val n = (failures - 1).coerceIn(0, 12)
        return minOf(MAX_MS, FIRST_MS shl n)
    }

    /**
     * How long to wait after a stop; null when waiting won't help ([TogglStop.fatal]). A full quota
     * waits for the reset Toggl named (at least a minute); no connection waits a fixed two minutes
     * (the job needs a network anyway); 429 and 5xx back off exponentially, but never less than
     * Toggl asked for. [failures] counts stops in a row that weren't about the quota.
     */
    fun delayMs(stop: TogglStop, failures: Int): Long? = when {
        stop.fatal -> null
        stop.kind == StopKind.QUOTA -> maxOf(stop.waitMs, FIRST_MS)
        stop.kind == StopKind.NETWORK -> maxOf(stop.waitMs, OFFLINE_MS)
        else -> maxOf(stop.waitMs, exponential(maxOf(1, failures)))
    }
}
