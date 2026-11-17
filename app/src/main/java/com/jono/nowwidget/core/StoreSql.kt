package com.jono.nowwidget.core

/**
 * The Store's SQL for the things that can lose or duplicate data, kept here so
 * tools/test_store_sql.py can run the very same statements against SQLite.
 *
 * Same rules as Schema.kt: one plain string literal per line, no templates, no escapes.
 * Placeholders are `?`, bound in order.
 */
object StoreSql {
    // BEGIN STORE_SQL
    /** Deleting an entry keeps its notes and feelings: they become moments of their own. */
    const val DETACH_MOMENTS = "UPDATE moment SET entry_id = NULL WHERE entry_id = ?"
    const val DELETE_ENTRY_LINKS = "DELETE FROM entry_link WHERE entry_id = ?"
    const val DELETE_ENTRY = "DELETE FROM entry WHERE id = ?"

    /** Context is sticky: the context of the last non-idle entry. */
    const val LAST_CONTEXT = "SELECT context_id FROM entry WHERE activity_id <> 0 ORDER BY start_ms DESC, id DESC LIMIT 1"

    /** The context of the last non-idle entry before one (start, start, id). */
    const val CONTEXT_BEFORE = "SELECT context_id FROM entry WHERE activity_id <> 0 AND (start_ms < ? OR (start_ms = ? AND id < ?)) ORDER BY start_ms DESC, id DESC LIMIT 1"

    /** Import dedupe: the note of the moment with this key, if any. */
    const val NOTE_AT_KEY = "SELECT text FROM moment WHERE source_key = ? LIMIT 1"
    const val KEY_TAKEN = "SELECT 1 FROM moment WHERE source_key = ? LIMIT 1"

    /** A feeling logged in Now (not imported) with this mood in this minute (mood, from, to). */
    const val NATIVE_FEELING_AT = "SELECT 1 FROM moment WHERE source_key IS NULL AND kind = 'feeling' AND mood_id = ? AND ts >= ? AND ts < ? LIMIT 1"

    /** An imported check-in (ts, title, text, mood, key). */
    const val INSERT_IMPORTED = "INSERT INTO moment (ts, kind, title, text, mood_id, entry_id, source_key) VALUES (?, 'feeling', ?, ?, ?, NULL, ?)"
    const val INSERT_MOMENT_TAG = "INSERT INTO moment_tag (moment_id, tag_id) VALUES (?, ?)"
    const val INSERT_MOMENT_REF = "INSERT OR IGNORE INTO moment_ref (moment_id, kind, ref_id) VALUES (?, ?, ?)"
    // END STORE_SQL
}
