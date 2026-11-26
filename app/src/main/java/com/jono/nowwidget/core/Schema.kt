package com.jono.nowwidget.core

/**
 * The database layout as plain SQL, so it can be tested off the phone (tools/test_migration.py
 * reads these lists straight out of this file).
 *
 * Rules for this file, because the test parses it: one statement per string literal, one literal
 * per line, no string templates, no escapes, no concatenation. Lists sit between BEGIN/END markers.
 *
 * Version 2 is additive: the v1 tables `person`, `entry_person` and the column `entry.note` stay as
 * they were (copied out, then no longer used), so nothing a v1 install wrote is ever rewritten.
 * Version 3 is three new tables for the Obsidian vault index and changes nothing that exists.
 * Version 4 adds one nullable column, `entry.title` (a free-written activity's own words); every
 * existing row keeps NULL, which means "just its activity".
 * Version 5 is three new tables for ranking people and places by his dated daily notes, and
 * changes nothing that exists.
 * Version 6 is one new table, `entry_activity`: an entry's extra activities ("Eat + Family"); the
 * primary stays entry.activity_id.
 */
object Schema {
    const val VERSION = 6

    /**
     * The version 1 tables as version 2 has them: build 6's DDL, plus entry.context_id at the end
     * (where ALTER TABLE puts it on an upgrade). person, entry_person and entry.note are legacy.
     */
    // BEGIN BASE
    val BASE: List<String> = listOf(
        "CREATE TABLE activity (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, emoji TEXT NOT NULL, color INTEGER NOT NULL, pos INTEGER NOT NULL, show_btn INTEGER NOT NULL DEFAULT 1, grp TEXT NOT NULL DEFAULT '', archived INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE person (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, emoji TEXT NOT NULL DEFAULT '', pos INTEGER NOT NULL, archived INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE entry (id INTEGER PRIMARY KEY AUTOINCREMENT, activity_id INTEGER NOT NULL, start_ms INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '', context_id INTEGER)",
        "CREATE INDEX entry_start ON entry (start_ms)",
        "CREATE TABLE entry_person (entry_id INTEGER NOT NULL, person_id INTEGER NOT NULL, PRIMARY KEY (entry_id, person_id))",
    )
    // END BASE

    /** Tables new in version 2. Used as-is by both a fresh install and the upgrade. */
    // BEGIN NEW_IN_V2
    val NEW_IN_V2: List<String> = listOf(
        "CREATE TABLE link (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'person', emoji TEXT NOT NULL DEFAULT '', pos INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0)",
        "CREATE INDEX link_kind ON link (kind, pos)",
        "CREATE TABLE entry_link (entry_id INTEGER NOT NULL, link_id INTEGER NOT NULL, PRIMARY KEY (entry_id, link_id))",
        "CREATE INDEX entry_link_link ON entry_link (link_id)",
        "CREATE TABLE mood (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, level INTEGER NOT NULL, emoji TEXT NOT NULL DEFAULT '', pos INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE moment (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL DEFAULT '', text TEXT NOT NULL DEFAULT '', mood_id INTEGER, entry_id INTEGER, source_key TEXT)",
        "CREATE INDEX moment_ts ON moment (ts)",
        "CREATE INDEX moment_entry ON moment (entry_id)",
        "CREATE UNIQUE INDEX moment_source ON moment (source_key)",
        "CREATE TABLE feeling_tag (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, emoji TEXT NOT NULL DEFAULT '', pos INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE moment_tag (moment_id INTEGER NOT NULL, tag_id INTEGER NOT NULL, PRIMARY KEY (moment_id, tag_id))",
        "CREATE INDEX moment_tag_tag ON moment_tag (tag_id)",
        "CREATE TABLE moment_ref (moment_id INTEGER NOT NULL, kind TEXT NOT NULL, ref_id INTEGER NOT NULL, PRIMARY KEY (moment_id, kind, ref_id))",
        "CREATE TABLE group_calendar (grp TEXT PRIMARY KEY NOT NULL, calendar TEXT NOT NULL DEFAULT '')",
        "CREATE TABLE export_state (target TEXT NOT NULL, local_key TEXT NOT NULL, remote_id TEXT NOT NULL DEFAULT '', content_hash TEXT NOT NULL DEFAULT '', synced_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (target, local_key))",
        "CREATE TABLE export_day (target TEXT NOT NULL, day TEXT NOT NULL, dirty INTEGER NOT NULL DEFAULT 1, PRIMARY KEY (target, day))",
    )
    // END NEW_IN_V2

    /**
     * Tables new in version 3. `vault_note` is a cache of the vault's root notes (one row per file,
     * [link_id] the link it was matched with); `link_source` marks the links the vault scan created,
     * so the owner's hand-made links are never touched and scan-made ones stay off the widget chips
     * until they're pinned or used. `link_note` binds a link to the one note that has it as an alias
     * (its title is on the link, so the note can be written as `[[Note Title|Link title]]`).
     */
    // BEGIN NEW_IN_V3
    val NEW_IN_V3: List<String> = listOf(
        "CREATE TABLE vault_note (doc_id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, modified INTEGER NOT NULL DEFAULT 0, title TEXT NOT NULL, kind TEXT NOT NULL, aliases TEXT NOT NULL DEFAULT '', link_id INTEGER)",
        "CREATE INDEX vault_note_link ON vault_note (link_id)",
        "CREATE TABLE link_source (link_id INTEGER PRIMARY KEY NOT NULL, source TEXT NOT NULL)",
        "CREATE TABLE link_note (link_id INTEGER PRIMARY KEY NOT NULL, doc_id TEXT NOT NULL, note_title TEXT NOT NULL)",
        "CREATE INDEX link_note_doc ON link_note (doc_id)",
    )
    // END NEW_IN_V3

    /**
     * Version 4: the free-written title of an entry. A fresh install adds it the same way, so the
     * column sits at the same place (after context_id) either way.
     */
    // BEGIN NEW_IN_V4
    val NEW_IN_V4: List<String> = listOf(
        "ALTER TABLE entry ADD COLUMN title TEXT",
    )
    // END NEW_IN_V4

    /**
     * Version 5. `day_note` is a cache of the dated daily notes read (one row per file, so only new
     * or changed ones are read again); `mention` is what each one says about each link target (by
     * its lowercased name: `spelled` as written, `at_count` / `w_count` 1 when it came after "At" /
     * in a "w" clause); `vault_rank` is the resulting mention score per link, worked out at each scan.
     */
    // BEGIN NEW_IN_V5
    val NEW_IN_V5: List<String> = listOf(
        "CREATE TABLE day_note (doc_id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, modified INTEGER NOT NULL DEFAULT 0, day INTEGER NOT NULL)",
        "CREATE TABLE mention (doc_id TEXT NOT NULL, target TEXT NOT NULL, spelled TEXT NOT NULL, day INTEGER NOT NULL, at_count INTEGER NOT NULL DEFAULT 0, w_count INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (doc_id, target))",
        "CREATE INDEX mention_target ON mention (target)",
        "CREATE TABLE vault_rank (link_id INTEGER PRIMARY KEY NOT NULL, score REAL NOT NULL DEFAULT 0)",
    )
    // END NEW_IN_V5

    /** Version 6: extra activities on an entry, besides its primary (they overlap: tags, not a split). */
    // BEGIN NEW_IN_V6
    val NEW_IN_V6: List<String> = listOf(
        "CREATE TABLE entry_activity (entry_id INTEGER NOT NULL, activity_id INTEGER NOT NULL, PRIMARY KEY (entry_id, activity_id))",
        "CREATE INDEX entry_activity_act ON entry_activity (activity_id)",
    )
    // END NEW_IN_V6

    /** Daylio's five default moods. Level 1 is the best. */
    // BEGIN SEED_MOODS
    val SEED_MOODS: List<String> = listOf(
        "INSERT INTO mood (name, level, emoji, pos, archived) VALUES ('rad', 1, '😄', 0, 0)",
        "INSERT INTO mood (name, level, emoji, pos, archived) VALUES ('good', 2, '🙂', 1, 0)",
        "INSERT INTO mood (name, level, emoji, pos, archived) VALUES ('meh', 3, '😐', 2, 0)",
        "INSERT INTO mood (name, level, emoji, pos, archived) VALUES ('bad', 4, '🙁', 3, 0)",
        "INSERT INTO mood (name, level, emoji, pos, archived) VALUES ('awful', 5, '😫', 4, 0)",
    )
    // END SEED_MOODS

    /**
     * Version 1 to 2, after [NEW_IN_V2] and [SEED_MOODS]. People keep their ids as person links,
     * so entry_link can copy entry_person row for row. Each entry's note becomes one note moment
     * at the entry's start; the note column keeps its text.
     */
    // BEGIN UPGRADE_1_2
    val UPGRADE_1_2: List<String> = listOf(
        "ALTER TABLE entry ADD COLUMN context_id INTEGER",
        "INSERT INTO link (id, title, kind, emoji, pos, pinned, archived) SELECT id, name, 'person', emoji, pos, 0, archived FROM person ORDER BY id",
        "INSERT INTO entry_link (entry_id, link_id) SELECT entry_id, person_id FROM entry_person",
        "INSERT INTO moment (ts, kind, title, text, mood_id, entry_id, source_key) SELECT start_ms, 'note', '', note, NULL, id, NULL FROM entry WHERE trim(note, ' ' || char(9) || char(10) || char(13)) <> '' ORDER BY start_ms, id",
    )
    // END UPGRADE_1_2

    /** Everything a fresh install runs (activities are seeded separately, with colors). */
    fun create(): List<String> = BASE + NEW_IN_V2 + NEW_IN_V3 + NEW_IN_V4 + NEW_IN_V5 + NEW_IN_V6 + SEED_MOODS

    /** Version 1 to 2, in order. The helper runs this, then [upgradeFrom2], inside one transaction. */
    fun upgradeFrom1(): List<String> = NEW_IN_V2 + SEED_MOODS + UPGRADE_1_2

    /** Version 2 to 3. */
    fun upgradeFrom2(): List<String> = NEW_IN_V3

    /** Version 3 to 4. */
    fun upgradeFrom3(): List<String> = NEW_IN_V4

    /** Version 4 to 5. */
    fun upgradeFrom4(): List<String> = NEW_IN_V5

    /** Version 5 to 6. */
    fun upgradeFrom5(): List<String> = NEW_IN_V6
}
