package com.jono.nowwidget.core

/**
 * The expand panel's sections: Doing, At, With, Feeling. No Android in here.
 */
object PanelSections {
    enum class Section { DOING, AT, WITH, FEELING }

    /**
     * Which sections show. While the check-in flow runs, the panel is the flow (no sections). With
     * something on: all four. When idle: Doing (to start something) and Feeling (a loose check-in);
     * At and With belong to an activity, so they're hidden.
     */
    fun visible(idle: Boolean, flowRunning: Boolean): List<Section> = when {
        flowRunning -> emptyList()
        idle -> listOf(Section.DOING, Section.FEELING)
        else -> Section.values().toList()
    }

    /**
     * The check-in the Feeling section shows and adds tags to: the latest moment (by time, then id)
     * on the current entry [entryId]; when idle (null), the latest loose one since [idleSince]
     * (a check-in from before the gap isn't this one). Notes count; imported ones don't.
     */
    fun latestCheckIn(moments: List<Moment>, entryId: Long?, idleSince: Long?, imported: Set<Long> = emptySet()): Moment? =
        moments.filter { it.id !in imported }
            .filter { m -> if (entryId != null) m.entryId == entryId else m.entryId == null && (idleSince == null || m.ts >= idleSince) }
            .maxWithOrNull(compareBy<Moment>({ it.ts }, { it.id }))

    /** The tags a check-in toggles, most used first, at most [max]; any already on it stay in. */
    fun topTags(tags: List<FeelingTag>, uses: Map<Long, Int>, on: Collection<Long>, max: Int = 8): List<FeelingTag> {
        val live = tags.filter { !it.archived || it.id in on }
            .sortedWith(compareByDescending<FeelingTag> { uses[it.id] ?: 0 }.thenBy { it.pos }.thenBy { it.id })
        val shown = live.take(max).toMutableList()
        live.drop(max).filter { it.id in on }.forEach { shown.add(it) }
        return shown
    }

    /** Tapping tag [tag]: the check-in's tags with it switched. */
    fun toggle(tags: List<Long>, tag: Long): List<Long> = if (tag in tags) tags - tag else tags + tag
}
