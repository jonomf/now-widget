package com.jono.nowwidget.core

/**
 * Which calendar is which. The calendar store's row ids can be reused (a calendar removed and
 * another added, an account re-added), so every calendar you pick is kept with its identity too:
 * account, account type and Google's sync id (or, for a calendar with none, its name). A pick is
 * only used when the row still matches, or when exactly one other row does.
 */
data class CalIdentity(val account: String, val accountType: String, val syncId: String?, val name: String) {
    /** Stable for maps: the sync id when there is one, else the name. */
    val key: String get() = accountType + SEP + account + SEP + if (syncId != null) "id:$syncId" else "name:$name"

    companion object {
        const val SEP = "\u001F"
    }
}

/** A calendar you picked: its row id then, and what it is. */
data class CalRef(val id: Long, val identity: CalIdentity) {
    fun encode(): String = listOf(
        "v1", id.toString(), clean(identity.accountType), clean(identity.account),
        if (identity.syncId == null) "0" else "1", clean(identity.syncId ?: ""), clean(identity.name),
    ).joinToString(CalIdentity.SEP)

    companion object {
        private fun clean(s: String) = s.replace(CalIdentity.SEP, " ").replace(SEP_PAIR, " ").replace('\n', ' ')

        /** Separates an area from its log calendar in the per-area setting. */
        const val SEP_PAIR = "\u001E"

        fun decode(s: String?): CalRef? {
            if (s.isNullOrEmpty()) return null
            val p = s.split(CalIdentity.SEP)
            if (p.size != 7 || p[0] != "v1") return null
            val id = p[1].toLongOrNull() ?: return null
            return CalRef(id, CalIdentity(p[3], p[2], if (p[4] == "1") p[5] else null, p[6]))
        }
    }
}

/** One of the phone's calendars, as the calendar store lists it. */
data class CalRow(
    val id: Long,
    val identity: CalIdentity,
    val color: Int,
    val access: Int,
    val visible: Boolean,
    val synced: Boolean,
) {
    val name: String get() = identity.name
    val account: String get() = identity.account
    val accountType: String get() = identity.accountType

    /** Contributor access (500) or more: the app can add events. */
    val writable: Boolean get() = access >= 500

    /** A calendar only on this phone ("LOCAL"): events in it never reach Google. */
    val local: Boolean get() = accountType == "LOCAL"

    val label: String get() = name.ifBlank { "Calendar $id" }

    fun ref(): CalRef = CalRef(id, identity)
}

object CalendarMatch {
    fun same(a: CalIdentity, b: CalIdentity): Boolean =
        a.account == b.account && a.accountType == b.accountType &&
            if (a.syncId != null && b.syncId != null) a.syncId == b.syncId else a.name == b.name

    /** The row [ref] means now: its own row if that still matches, else the one other row that does; null when none or several do. */
    fun resolve(ref: CalRef?, rows: List<CalRow>): CalRow? {
        if (ref == null) return null
        rows.firstOrNull { it.id == ref.id }?.let { if (same(it.identity, ref.identity)) return it }
        return rows.filter { same(it.identity, ref.identity) }.singleOrNull()
    }
}

/** The calendar settings, resolved against the calendars on the phone right now. */
class ResolvedSetup(
    val rows: Map<Long, CalRow>,
    val perArea: Boolean,
    /** The main log calendar; -1 when it isn't resolved. */
    val defaultLog: Long,
    /** Every log calendar the settings name (that could be found). */
    val chosen: Set<Long>,
    /** The ones the app may write to. Empty while there's a [problem]. */
    val logCalendars: Set<Long>,
    private val areas: Map<String, Long?>,
    val areaLogs: Map<Long, Long>,
    /** Why nothing may be written now; null when all is well. */
    val problem: String?,
    /** Where planned events are read from: mapped areas, never a log calendar (hidden ones too). */
    val areaCalendars: Set<Long>,
    /** Mapped area calendars this phone doesn't sync, so their planned events can't be read. */
    val unsyncedAreas: List<CalRow>,
) {
    fun areaOf(group: String): Long? = areas[group.trim()]

    /** Where a group's events go; null when nowhere may be written. */
    fun target(group: String): Long? {
        val area = areaOf(group)
        val t = if (perArea && area != null) areaLogs[area] ?: defaultLog else defaultLog
        return t.takeIf { it in logCalendars }
    }
}

object CalendarSetup {
    /**
     * [log]: the main log calendar. [areaRefs]: group to its area calendar (null: none). [areaLogRefs]:
     * with one log calendar per area, area identity key to its log calendar; entries for areas no
     * group maps to are ignored.
     */
    fun resolve(
        rows: List<CalRow>,
        log: CalRef?,
        perArea: Boolean,
        areaRefs: Map<String, CalRef?>,
        areaLogRefs: Map<String, CalRef>,
    ): ResolvedSetup {
        val byId = rows.associateBy { it.id }
        val problems = ArrayList<String>()
        val logRow = CalendarMatch.resolve(log, rows)
        if (log == null) problems.add("No log calendar chosen.")
        else if (logRow == null) problems.add("Pick the log calendar again (\"${log.identity.name}\" isn't on the phone as it was).")

        val areas = HashMap<String, Long?>()
        areaRefs.forEach { (g, ref) ->
            if (ref == null) {
                areas[g] = null
                return@forEach
            }
            val row = CalendarMatch.resolve(ref, rows)
            if (row == null) problems.add("Pick the area for " + g.ifEmpty { "Ungrouped" } + " again (\"${ref.identity.name}\" isn't on the phone as it was).")
            areas[g] = row?.id
        }

        val areaLogs = HashMap<Long, Long>()
        if (perArea) {
            areaRefs.values.filterNotNull().distinctBy { it.identity.key }.forEach { areaRef ->
                val areaId = CalendarMatch.resolve(areaRef, rows)?.id ?: return@forEach
                val logRef = areaLogRefs[areaRef.identity.key] ?: return@forEach
                val row = CalendarMatch.resolve(logRef, rows)
                if (row == null) problems.add("Pick the log calendar for ${areaRef.identity.name} again.")
                else areaLogs[areaId] = row.id
            }
        }

        val chosen = (listOfNotNull(logRow?.id) + areaLogs.values).toSet()
        chosen.mapNotNull { byId[it] }.forEach { c ->
            when {
                !c.writable -> problems.add("The app can't add events to ${c.label}.")
                !c.synced || !c.visible -> problems.add("${c.label} isn't synced and shown on this phone (Google Calendar app → Settings), so nothing was written.")
            }
        }
        val problem = problems.firstOrNull()
        val mappedAreas = areas.values.filterNotNull().filter { it !in chosen }.toSet()
        return ResolvedSetup(
            rows = byId,
            perArea = perArea,
            defaultLog = logRow?.id ?: -1L,
            chosen = chosen,
            logCalendars = if (problem == null) chosen else emptySet(),
            areas = areas,
            areaLogs = areaLogs,
            problem = problem,
            areaCalendars = mappedAreas,
            unsyncedAreas = mappedAreas.mapNotNull { byId[it] }.filter { !it.synced },
        )
    }

    /** The per-area setting with only the areas some group still maps to. */
    fun prune(areaLogRefs: Map<String, CalRef>, areaRefs: Map<String, CalRef?>): Map<String, CalRef> {
        val mapped = areaRefs.values.filterNotNull().map { it.identity.key }.toSet()
        return areaLogRefs.filterKeys { it in mapped }
    }

    fun encodeAreaLogs(m: Map<String, CalRef>): String =
        m.entries.joinToString("\n") { it.key + CalRef.SEP_PAIR + it.value.encode() }

    fun decodeAreaLogs(s: String?): Map<String, CalRef> =
        (s ?: "").split('\n').mapNotNull { line ->
            val i = line.indexOf(CalRef.SEP_PAIR)
            if (i <= 0) return@mapNotNull null
            val ref = CalRef.decode(line.substring(i + 1)) ?: return@mapNotNull null
            line.substring(0, i) to ref
        }.toMap()
}
