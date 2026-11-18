package com.jono.nowwidget.core

/** A planned event from one of the area calendars (read-only). [calendarId] is its area. */
data class PlannedEvent(val title: String, val calendarId: Long, val from: Long, val to: Long)

/** A stretch of what actually happened, with the area its activity's group maps to (null: none). */
data class AreaSpan(val area: Long?, val from: Long, val to: Long)

data class AreaTotal(val area: Long?, val plannedMs: Long, val actualMs: Long)

data class Comparison(
    /** Areas with something planned or done, most planned first; the "no area" bucket (null) last. */
    val areas: List<AreaTotal>,
    /** Minutes with at least one planned event (overlaps count once). */
    val plannedMs: Long,
    /** Planned minutes in which what you were doing was in the area of a planned event at that time. */
    val matchedMs: Long,
) {
    /** 0.0 to 1.0; null when nothing was planned. */
    val match: Double? get() = if (plannedMs > 0L) matchedMs.toDouble() / plannedMs.toDouble() else null
}

/** Planned vs actual for one day, per area. Pure, so it's unit-tested. */
object PlannedVsActual {
    fun compare(planned: List<PlannedEvent>, actual: List<AreaSpan>, from: Long, to: Long): Comparison {
        val p = planned.map { Triple(it.calendarId, maxOf(it.from, from), minOf(it.to, to)) }.filter { it.third > it.second }
        val a = actual.map { Triple(it.area, maxOf(it.from, from), minOf(it.to, to)) }.filter { it.third > it.second }
        val cuts = java.util.TreeSet<Long>()
        p.forEach { cuts.add(it.second); cuts.add(it.third) }
        a.forEach { cuts.add(it.second); cuts.add(it.third) }
        val plannedBy = LinkedHashMap<Long?, Long>()
        val actualBy = LinkedHashMap<Long?, Long>()
        var plannedMs = 0L
        var matchedMs = 0L
        val points = cuts.toList()
        for (i in 0 until points.size - 1) {
            val s = points[i]
            val e = points[i + 1]
            val len = e - s
            val planAreas = p.filter { it.second <= s && it.third >= e }.map { it.first }.toSet()
            val doing = a.filter { it.second <= s && it.third >= e }.map { it.first }.toSet()
            planAreas.forEach { plannedBy[it] = (plannedBy[it] ?: 0L) + len }
            doing.forEach { actualBy[it] = (actualBy[it] ?: 0L) + len }
            if (planAreas.isNotEmpty()) {
                plannedMs += len
                if (doing.any { it != null && it in planAreas }) matchedMs += len
            }
        }
        val areas = (plannedBy.keys + actualBy.keys).distinct().map { AreaTotal(it, plannedBy[it] ?: 0L, actualBy[it] ?: 0L) }
            .sortedWith(compareBy<AreaTotal>({ it.area == null }, { -it.plannedMs }, { -it.actualMs }))
        return Comparison(areas, plannedMs, matchedMs)
    }
}

/** "Event title contains [keyword]" means [activityId]. */
data class KeywordRule(val keyword: String, val activityId: Long)

/** Which activity a planned event that's happening now suggests, for the widget's first button. */
object PlannedNudge {
    /** Events going on at [now]: the one that started last first, then the shorter one. */
    fun happeningNow(planned: List<PlannedEvent>, now: Long): List<PlannedEvent> =
        planned.filter { it.from <= now && now < it.to }
            .sortedWith(compareBy<PlannedEvent>({ -it.from }, { it.to - it.from }, { it.title }))

    /**
     * The first rule (in your order) whose keyword the title contains, ignoring case; else the
     * area's most-used activity ([fallback], by calendar id). Only activities in [usable] count.
     */
    fun activityFor(e: PlannedEvent, rules: List<KeywordRule>, fallback: Map<Long, Long>, usable: Set<Long>): Long? {
        rules.forEach { r ->
            val k = r.keyword.trim()
            if (k.isNotEmpty() && e.title.contains(k, ignoreCase = true) && r.activityId in usable) return r.activityId
        }
        return fallback[e.calendarId]?.takeIf { it in usable }
    }

    fun pick(now: Long, planned: List<PlannedEvent>, rules: List<KeywordRule>, fallback: Map<Long, Long>, usable: Set<Long>): Long? =
        happeningNow(planned, now).firstNotNullOfOrNull { activityFor(it, rules, fallback, usable) }

    /** Per area, the activity with the most uses ([uses]: activity to count); ties go to the lower id. */
    fun mostUsed(uses: Map<Long, Int>, areaOf: Map<Long, Long?>): Map<Long, Long> {
        val out = HashMap<Long, Pair<Long, Int>>()
        uses.entries.sortedBy { it.key }.forEach { (act, n) ->
            val area = areaOf[act] ?: return@forEach
            val best = out[area]
            if (best == null || n > best.second) out[area] = act to n
        }
        return out.mapValues { it.value.first }
    }

    /** Rules as stored: one per line, `activityId<TAB>keyword`. */
    fun encode(rules: List<KeywordRule>): String =
        rules.filter { it.keyword.isNotBlank() }.joinToString("\n") { r ->
            r.activityId.toString() + "\t" + r.keyword.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
        }

    fun decode(text: String?): List<KeywordRule> =
        (text ?: "").split('\n').mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val id = line.substring(0, tab).toLongOrNull() ?: return@mapNotNull null
            val k = line.substring(tab + 1).trim()
            if (k.isEmpty()) null else KeywordRule(k, id)
        }
}
