package com.jono.nowwidget.core

/**
 * The 📝 box's editing logic, kept pure so it's unit-tested. A "position" is an index into the
 * day's moments (oldest first); a position equal to the list size is the blank new check-in.
 */
object CheckInNav {
    enum class Save { NONE, CREATE, UPDATE, ASK_DELETE }

    /** Oldest first, so ‹ and › follow the day. */
    fun order(moments: List<Moment>): List<Moment> = moments.sortedWith(compareBy({ it.ts }, { it.id }))

    /** The newest moment, or the blank new check-in (position 0 = size) when there is none. */
    fun openAt(size: Int): Int = if (size > 0) size - 1 else 0

    /** The position before [pos] in a list of [size], or null at the oldest. */
    fun prev(pos: Int, size: Int): Int? = if (pos <= 0) null else minOf(pos, size) - 1

    /** The position after [pos]; past the newest it's the blank new check-in; null from there. */
    fun next(pos: Int, size: Int): Int? = if (pos >= size) null else pos + 1

    fun isEmpty(moodId: Long?, tags: Collection<Long>, text: String): Boolean =
        moodId == null && tags.isEmpty() && text.isBlank()

    /** Whether the form differs from what was loaded ([loaded] null = a new, blank check-in). */
    fun changed(loaded: Moment?, moodId: Long?, tags: Collection<Long>, text: String): Boolean =
        if (loaded == null) !isEmpty(moodId, tags, text)
        else moodId != loaded.moodId || tags.toSet() != loaded.tags.toSet() || text.trim() != loaded.text.trim()

    /**
     * What saving does. A loaded moment is updated, never duplicated; emptied out it asks to be
     * deleted (unless it has an imported title to keep). When [stepping] (‹, › or Clear) nothing
     * is deleted without asking, so an emptied moment is left as it was.
     */
    fun saveAction(loaded: Moment?, moodId: Long?, tags: Collection<Long>, text: String, stepping: Boolean): Save {
        if (!changed(loaded, moodId, tags, text)) return Save.NONE
        if (loaded == null) return Save.CREATE
        if (isEmpty(moodId, tags, text) && loaded.title.isBlank()) return if (stepping) Save.NONE else Save.ASK_DELETE
        return Save.UPDATE
    }

    /** The header line: which check-in is shown. [time] is its formatted time. */
    fun header(loaded: Moment?, time: String): String = if (loaded == null) "New check-in" else "Editing $time check-in"
}
