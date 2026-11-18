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

    /** Obsidian export: a diary day is dirty after anything changes in it (target, day). */
    const val MARK_DAY_DIRTY = "INSERT OR REPLACE INTO export_day (target, day, dirty) VALUES (?, ?, 1)"
    const val CLEAR_DAY_DIRTY = "UPDATE export_day SET dirty = 0 WHERE target = ? AND day = ?"
    const val DIRTY_DAYS = "SELECT day FROM export_day WHERE target = ? AND dirty = 1"

    /** A rename or regroup can change every note already written: all of them become dirty (target, target). */
    const val MARK_WRITTEN_DIRTY = "INSERT OR REPLACE INTO export_day (target, day, dirty) SELECT ?, local_key, 1 FROM export_state WHERE target = ?"
    const val HASH_OF_DAY = "SELECT content_hash FROM export_state WHERE target = ? AND local_key = ?"
    const val SAVE_HASH = "INSERT OR REPLACE INTO export_state (target, local_key, remote_id, content_hash, synced_at) VALUES (?, ?, ?, ?, ?)"

    /** The entry just before and just after a time (ts, ts): which days a change at ts can reach. */
    const val PREV_ENTRY_START = "SELECT MAX(start_ms) FROM entry WHERE start_ms < ?"
    const val NEXT_ENTRY_START = "SELECT MIN(start_ms) FROM entry WHERE start_ms > ?"

    /** Vault index: the cache, and how scan-made links are marked. */
    const val VAULT_CACHE = "SELECT doc_id, name, modified, kind, link_id FROM vault_note"
    const val VAULT_UPSERT = "INSERT OR REPLACE INTO vault_note (doc_id, name, modified, title, kind, aliases, link_id) VALUES (?, ?, ?, ?, ?, ?, ?)"
    const val VAULT_DELETE = "DELETE FROM vault_note WHERE doc_id = ?"
    const val VAULT_ALIASES = "SELECT link_id, aliases FROM vault_note WHERE link_id IS NOT NULL AND aliases <> ''"
    const val LINK_SOURCE_ADD = "INSERT OR IGNORE INTO link_source (link_id, source) VALUES (?, 'vault')"
    const val LINK_USES = "SELECT link_id, COUNT(*) FROM entry_link GROUP BY link_id"
    const val CONTEXT_USES = "SELECT context_id, COUNT(*) FROM entry WHERE context_id IS NOT NULL GROUP BY context_id"
    // END STORE_SQL
}
