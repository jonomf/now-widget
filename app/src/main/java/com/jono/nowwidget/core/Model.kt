package com.jono.nowwidget.core

// Plain data, no Android imports, so JVM unit tests can use it.

/** An entry with this activity means "doing nothing". It's left out of all stats. */
const val IDLE = 0L

data class Act(
    val id: Long,
    val name: String,
    val emoji: String,
    val color: Int,
    val pos: Int,
    val show: Boolean,
    val group: String,
    val archived: Boolean,
)

/** A person, place or context: something an entry is linked to. Later filled from the vault. */
data class Link(
    val id: Long,
    val title: String,
    val kind: String,
    val emoji: String,
    val pos: Int,
    val archived: Boolean,
    /** Kept on the widget chips and in the check-in box whatever else is true. */
    val pinned: Boolean = false,
    /** A note in the vault has this title (or this link was matched to one). */
    val inVault: Boolean = false,
    /** The vault scan made this link; you didn't. Those stay off the chips until pinned or used. */
    val fromVault: Boolean = false,
    /** How many entries use it, as a person, a place or a context. */
    val uses: Int = 0,
) {
    /** Hand-made links are always chips; ones the vault scan made only once you pin or use them. */
    val chip: Boolean get() = !fromVault || pinned || uses > 0
    val badge: String get() = emoji.ifBlank { title.take(1).uppercase() }
    val label: String get() = if (emoji.isBlank()) title else "$emoji $title"
}

object LinkKind {
    const val PERSON = "person"
    const val PLACE = "place"
    const val CONTEXT = "context"
    const val OTHER = "other"
    val ALL = listOf(PERSON, PLACE, CONTEXT)
}

data class Entry(
    val id: Long,
    val activityId: Long,
    val start: Long,
    val links: Set<Long> = emptySet(),
    val contextId: Long? = null,
)

/** An entry clipped to the time range that was asked for. */
data class Span(val entry: Entry, val from: Long, val to: Long) {
    val ms: Long get() = to - from
}

object MomentKind {
    const val NOTE = "note"
    const val FEELING = "feeling"
    const val TRACKER = "tracker"
}

/** Something noted at a point in time: a note or a feeling. It may belong to an entry, or not. */
data class Moment(
    val id: Long,
    val ts: Long,
    val kind: String,
    val title: String,
    val text: String,
    val moodId: Long?,
    val entryId: Long?,
    val tags: List<Long> = emptyList(),
)

/** Level 1 is the best, 5 the worst, as in Daylio. Several moods may share a level. */
data class Mood(
    val id: Long,
    val name: String,
    val level: Int,
    val emoji: String,
    val pos: Int,
    val archived: Boolean,
)

data class FeelingTag(
    val id: Long,
    val name: String,
    val emoji: String,
    val pos: Int,
    val archived: Boolean,
) {
    val label: String get() = if (emoji.isBlank()) name else "$emoji $name"
}

val PALETTE: IntArray = intArrayOf(
    0xFF5B8DEF.toInt(), 0xFFE5735C.toInt(), 0xFF4DB37E.toInt(), 0xFFE6B13D.toInt(),
    0xFF9B6FD8.toInt(), 0xFF3FB6C6.toInt(), 0xFFE36FA5.toInt(), 0xFF8AA63C.toInt(),
    0xFFC98A4B.toInt(), 0xFF6C7BC4.toInt(), 0xFF4F9D9A.toInt(), 0xFF9A9A9A.toInt(),
)

/** The face for a mood level, used when a mood has no emoji of its own. */
fun levelFace(level: Int): String = when (level) {
    1 -> "😄"
    2 -> "🙂"
    3 -> "😐"
    4 -> "🙁"
    else -> "😫"
}
