package com.jono.nowwidget.core

/** The log's rules that don't need the database: spans, button picks and what a tap does. */
object LogRules {
    const val GRACE_MS = 60_000L

    /**
     * Turns entries (oldest first, starting with the one running at [from]) into what happened in
     * [from, to). An entry runs until the next one starts; the last one runs until [now].
     * Idle entries are left out.
     */
    fun spans(rows: List<Entry>, from: Long, to: Long, now: Long): List<Span> {
        val limit = minOf(to, now)
        val out = ArrayList<Span>()
        rows.forEachIndexed { i, e ->
            val end = if (i + 1 < rows.size) rows[i + 1].start else limit
            val a = maxOf(from, e.start)
            val b = minOf(end, limit)
            if (b > a && e.activityId != IDLE) out.add(Span(e, a, b))
        }
        return out
    }

    /**
     * Which activities get the buttons when they don't all fit. The current one is always there,
     * so you can tap it again. Picks stay in your own order, so buttons move as little as possible.
     */
    fun picks(all: List<Act>, cur: Act?, n: Int, scores: Map<Long, Double>, smart: Boolean): List<Act> {
        val pool = if (cur != null && all.none { it.id == cur.id }) all + cur else all
        if (pool.size <= n) return pool.sortedBy { it.pos }
        val others = pool.filter { it.id != cur?.id }
        val ranked = if (smart) {
            others.sortedWith(compareByDescending<Act> { scores[it.id] ?: 0.0 }.thenBy { it.pos })
        } else {
            others.sortedBy { it.pos }
        }
        val room = if (cur != null) n - 1 else n
        return (ranked.take(room) + listOfNotNull(cur)).sortedBy { it.pos }
    }

    /**
     * A planned event suggests [nudged]: it goes to the first button. If it wasn't among [picks],
     * it takes the place of the last pick that isn't the current activity, so there are never more
     * than [n]. Nothing changes when it's what you're doing already or there's no room.
     */
    fun withNudge(picks: List<Act>, nudged: Act?, cur: Act?, n: Int): List<Act> {
        if (nudged == null || n <= 0 || nudged.id == cur?.id) return picks
        val rest = picks.filter { it.id != nudged.id }.toMutableList()
        if (rest.size == picks.size) {
            // Not there yet: make room.
            while (rest.size >= n) {
                val drop = rest.indexOfLast { it.id != cur?.id }
                if (drop < 0) return picks
                rest.removeAt(drop)
            }
        }
        return listOf(nudged) + rest
    }

    /** What a tap on an activity button does. */
    sealed class Tap {
        /** Start [activityId] at [at]; that ends whatever came before. */
        data class Start(val activityId: Long, val at: Long) : Tap()

        /** Move the current entry's start back (the "tap again" setting). */
        data class MoveBack(val byMs: Long) : Tap()

        /** Started by mistake a moment ago: delete it, so whatever came before carries on. */
        object Undo : Tap()

        /**
         * Switched by mistake a moment ago: delete the latest entry, and start the tapped
         * activity at its start, unless that's what was running before it anyway.
         */
        data class Replace(val startAt: Long?) : Tap()
    }

    /**
     * True when the latest entry carries something you added by hand: a person or place, or a
     * context other than the one it inherited. [inheritedContextId] is the context of the last
     * non-idle entry before it (what a new entry picks up on its own); [inheritedLinks] are the
     * place and people the check-in flow carried over on its own (the last place, the people
     * before), which don't count either.
     */
    fun marked(latest: Entry, inheritedContextId: Long?, inheritedLinks: Set<Long> = emptySet()): Boolean =
        (latest.links - inheritedLinks).isNotEmpty() || (latest.contextId != null && latest.contextId != inheritedContextId)

    /**
     * - A different activity: it starts now, and that ends the last one.
     * - Within a minute of the last switch: that was a mistake, so replace it instead of logging it.
     *   The replacement keeps the mistaken entry's start, people, place and context.
     *   An entry that has a note or feeling on it was no mistake, so it's never replaced.
     * - The current activity again: turn it off, or move its start back if that's the setting.
     *   Within a minute it's undone instead, unless you'd already added a note, feeling, person,
     *   place or context to it.
     */
    fun tap(
        latest: Entry?,
        latestHasMoments: Boolean,
        before: Entry?,
        activityId: Long,
        now: Long,
        stepMinutes: Int,
        inheritedContextId: Long? = null,
        inheritedLinks: Set<Long> = emptySet(),
    ): Tap {
        if (latest == null) return Tap.Start(activityId, now)
        val fresh = now - latest.start < GRACE_MS && !latestHasMoments
        val isMarked = marked(latest, inheritedContextId, inheritedLinks)
        // A free-written entry ("fixing the bike", category Other) isn't its category's button: tapping
        // that button is a switch to the plain activity, not "the same again".
        if (latest.activityId == activityId && latest.title == null) {
            return when {
                stepMinutes > 0 -> Tap.MoveBack(stepMinutes * 60_000L)
                fresh && !isMarked -> Tap.Undo
                else -> Tap.Start(IDLE, now)
            }
        }
        if (fresh) {
            // Only drop back to the entry before when nothing you added would be lost.
            val keepGoing = before?.activityId == activityId && before.title == null && !isMarked
            return Tap.Replace(if (keepGoing) null else latest.start)
        }
        return Tap.Start(activityId, now)
    }

    /**
     * Context is sticky: a new entry carries the context of the last non-idle entry, even across
     * an idle gap, until you clear it. Idle entries never carry one.
     */
    fun contextForNew(activityId: Long, lastNonIdleContextId: Long?): Long? =
        if (activityId == IDLE) null else lastNonIdleContextId
}
