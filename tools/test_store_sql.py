#!/usr/bin/env python3
"""Runs the Store's own SQL (core/StoreSql.kt, read straight out of the Kotlin) against a
database made by core/Schema.kt (the current version), to check the behaviours that could lose or duplicate data:

- deleting an entry detaches its notes and feelings instead of deleting them;
- context is sticky: a new entry picks up the last non-idle entry's context, across idle gaps;
- Daylio import dedupe: re-importing adds nothing, a second check-in in the same minute and mood
  with a different note is kept, and a feeling logged in Now that was exported and imported
  back isn't duplicated;
- a failure part way through an import rolls everything back;
- the Obsidian export's dirty days and content hashes, and which days a change reaches;
- the vault index cache and how link uses are counted.

The import loop mirrors Store.importDaylio step for step; the key rules mirror Daylio.key/altKey/
decide (those are unit-tested in Kotlin too). Run: python3 tools/test_store_sql.py
"""
import os, re, sqlite3, sys, zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import test_migration as tm  # noqa: E402  (reuses its Schema.kt reader)

STORE_SQL_KT = os.path.join(HERE, '..', 'app/src/main/java/com/jono/nowwidget/core/StoreSql.kt')
failures = []


def check(cond, msg):
    if not cond:
        failures.append(msg)
        print('FAIL:', msg)


def store_sql():
    src = open(STORE_SQL_KT, encoding='utf-8').read()
    block = re.search(r'// BEGIN STORE_SQL\n(.*?)// END STORE_SQL', src, re.S).group(1)
    out = {}
    for name, lit in re.findall(r'const val (\w+) = "((?:[^"\\$]|\\.)*)"', block):
        assert '\\' not in lit, name
        out[name] = lit
    assert len(out) >= 20, out
    return out


SQL = store_sql()


def fresh_v2():
    db = sqlite3.connect(':memory:', isolation_level=None)
    db.execute('BEGIN')
    for s in tm.statements('create'):
        db.execute(s)
    db.execute('COMMIT')
    return db


def one(db, sql, args=()):
    r = db.execute(sql, args).fetchone()
    return r[0] if r else None


# --- Deleting an entry ---

def test_delete_detaches():
    db = fresh_v2()
    db.execute("INSERT INTO link (id, title, kind) VALUES (1, 'Sam', 'person')")
    db.execute('INSERT INTO entry (id, activity_id, start_ms) VALUES (10, 1, 1000)')
    db.execute('INSERT INTO entry_link (entry_id, link_id) VALUES (10, 1)')
    db.execute("INSERT INTO moment (id, ts, kind, text, mood_id, entry_id) VALUES (5, 1500, 'feeling', 'tired', 2, 10)")
    db.execute("INSERT INTO moment (id, ts, kind, text, entry_id) VALUES (6, 1600, 'note', 'a note', 10)")
    db.execute('INSERT INTO moment_tag (moment_id, tag_id) VALUES (5, 3)')
    db.execute('BEGIN')
    for k in ('DETACH_MOMENTS', 'DELETE_ENTRY_LINKS', 'DELETE_ENTRY'):
        db.execute(SQL[k], (10,))
    db.execute('COMMIT')
    check(one(db, 'SELECT COUNT(*) FROM entry') == 0, 'entry deleted')
    check(one(db, 'SELECT COUNT(*) FROM entry_link') == 0, 'its links deleted')
    check(db.execute('SELECT id, ts, text, entry_id FROM moment ORDER BY id').fetchall() ==
          [(5, 1500, 'tired', None), (6, 1600, 'a note', None)], 'moments kept, detached, unchanged')
    check(one(db, 'SELECT COUNT(*) FROM moment_tag') == 1, 'moment tags kept')


# --- Sticky context ---

def test_sticky_context():
    db = fresh_v2()
    check(one(db, SQL['LAST_CONTEXT']) is None, 'no entries: no context')
    db.execute('INSERT INTO entry (id, activity_id, start_ms, context_id) VALUES (1, 3, 100, 7)')
    db.execute('INSERT INTO entry (id, activity_id, start_ms, context_id) VALUES (2, 0, 200, NULL)')  # idle gap
    check(one(db, SQL['LAST_CONTEXT']) == 7, 'context carries over an idle gap')
    db.execute('INSERT INTO entry (id, activity_id, start_ms, context_id) VALUES (3, 4, 300, 7)')
    check(one(db, SQL['CONTEXT_BEFORE'], (300, 300, 3)) == 7, 'context inherited by the latest entry')
    db.execute('INSERT INTO entry (id, activity_id, start_ms, context_id) VALUES (4, 5, 400, NULL)')  # cleared
    check(one(db, SQL['LAST_CONTEXT']) is None, 'a cleared context stays cleared')


# --- Check-in flow: defaults and ranking ---

def test_flow_defaults_and_uses():
    db = fresh_v2()
    db.execute("INSERT INTO link (id, title, kind) VALUES (1, 'Loft', 'place'), (2, 'Park', 'place'), (3, 'Sam', 'person'), (4, 'Kim', 'person'), (5, 'Trip', 'context')")
    check(one(db, SQL['LAST_PLACE_BEFORE'], (100, 100, 1)) is None, 'no entries: no last place')
    db.execute('INSERT INTO entry (id, activity_id, start_ms) VALUES (1, 3, 100)')
    db.execute('INSERT INTO entry_link (entry_id, link_id) VALUES (1, 1), (1, 3)')
    db.execute('INSERT INTO entry (id, activity_id, start_ms) VALUES (2, 4, 200)')
    db.execute('INSERT INTO entry_link (entry_id, link_id) VALUES (2, 4), (2, 5)')  # people and a non-place link, no place
    db.execute('INSERT INTO entry (id, activity_id, start_ms) VALUES (3, 0, 300)')   # idle
    db.execute('INSERT INTO entry (id, activity_id, start_ms) VALUES (4, 5, 400)')   # the new one
    db.execute('INSERT INTO entry_link (entry_id, link_id) VALUES (4, 2)')
    check(one(db, SQL['LAST_PLACE_BEFORE'], (400, 400, 4)) == 1, 'last place skips entries with no place and the new entry itself')
    check(one(db, SQL['PREV_NON_IDLE'], (400, 400, 4)) == 2, 'previous non-idle entry skips the idle gap')
    # Same start, lower id comes before.
    db.execute('INSERT INTO entry (id, activity_id, start_ms) VALUES (5, 6, 400)')
    check(one(db, SQL['PREV_NON_IDLE'], (400, 400, 5)) == 4, 'same start: the lower id is before')
    check(one(db, SQL['LAST_PLACE_BEFORE'], (400, 400, 5)) == 2, 'same start: its place counts')
    uses = sorted(db.execute(SQL['LINK_USES_SINCE'], (150, 4)).fetchall())
    check(uses == [(4, 200), (5, 200)], 'uses since a time, without the excluded entry: %r' % uses)
    uses = sorted(db.execute(SQL['LINK_USES_SINCE'], (0, 0)).fetchall())
    check(len(uses) == 5, 'every use counts once')


# --- Free-written titles ---

def test_titles():
    db = fresh_v2()
    db.execute("INSERT INTO entry (id, activity_id, start_ms, title) VALUES (1, 5, 100, 'fixing the bike'), (2, 3, 200, NULL), (3, 5, 300, ''), (4, 4, 400, 'Fixing the bike')")
    got = db.execute(SQL['TITLES_RECENT']).fetchall()
    check(got == [('Fixing the bike', 400, 4, 4), ('fixing the bike', 100, 5, 1)], 'titles newest first, blanks and NULLs left out: %r' % got)
    db.execute(SQL['ENTRY_TO_ACTIVITY'], (9, 4))
    check(db.execute('SELECT activity_id, title FROM entry WHERE id = 4').fetchone() == (9, None), 'make an activity: entry moved, title cleared')
    check(db.execute('SELECT activity_id, title FROM entry WHERE id = 1').fetchone() == (5, 'fixing the bike'), 'other entries untouched')


# --- Mention index (schema v5) ---

def test_mentions():
    db = fresh_v2()
    db.execute(SQL['DAY_NOTE_UPSERT'], ('n1', '2025-4-9.md', 5, 1000))
    db.execute(SQL['DAY_NOTE_UPSERT'], ('n2', '2025-4-10.md', 6, 2000))
    db.execute(SQL['MENTION_ADD'], ('n1', 'the loft', 'The Loft', 1000, 1, 0))
    db.execute(SQL['MENTION_ADD'], ('n1', 'mom', 'Mom', 1000, 0, 1))
    db.execute(SQL['MENTION_ADD'], ('n2', 'the loft', 'The loft', 2000, 1, 0))
    check(one(db, SQL['DAY_NOTE_COUNT']) == 2, 'two daily notes known')
    check(sorted(db.execute(SQL['DAY_NOTES']).fetchall()) == [('n1', '2025-4-9.md', 5), ('n2', '2025-4-10.md', 6)], 'day note cache')
    # A changed note: its mentions are replaced, the other note's stay.
    db.execute(SQL['MENTIONS_OF_NOTE_CLEAR'], ('n1',))
    db.execute(SQL['MENTION_ADD'], ('n1', 'mom', 'Mom', 1000, 0, 1))
    db.execute(SQL['DAY_NOTE_UPSERT'], ('n1', '2025-4-9.md', 7, 1000))
    got = sorted(db.execute(SQL['MENTIONS_ALL']).fetchall())
    check(got == [('n1', 'mom', 'Mom', 1000, 0, 1), ('n2', 'the loft', 'The loft', 2000, 1, 0)], 'mentions of a re-read note replaced: %r' % got)
    # A deleted note takes its mentions with it.
    db.execute(SQL['MENTIONS_OF_NOTE_CLEAR'], ('n2',))
    db.execute(SQL['DAY_NOTE_DELETE'], ('n2',))
    check(one(db, 'SELECT COUNT(*) FROM mention WHERE doc_id = ?', ('n2',)) == 0 and one(db, SQL['DAY_NOTE_COUNT']) == 1, 'deleted note gone')
    # Scores are rewritten as a whole.
    db.execute(SQL['VAULT_RANK_SET'], (1, 2.5))
    db.execute(SQL['VAULT_RANK_CLEAR'])
    db.execute(SQL['VAULT_RANK_SET'], (2, 1.5))
    check(db.execute(SQL['VAULT_RANKS']).fetchall() == [(2, 1.5)], 'vault ranks replaced')
    # Sources: a mention-made link, and the once-only kind change of a scan-made one.
    db.execute(SQL['LINK_SOURCE_MENTIONS'], (5,))
    db.execute(SQL['LINK_SOURCE_ADD'], (6,))
    db.execute(SQL['LINK_KIND_FROM_MENTIONS'], (6,))
    db.execute(SQL['LINK_KIND_FROM_MENTIONS'], (5,))  # not a scan-made one: unchanged
    check(sorted(db.execute(SQL['LINK_SOURCES']).fetchall()) == [(5, 'mentions'), (6, 'vault-kind')], 'link sources')


# --- Merging a no-note link into its note's link ---

def test_link_merge():
    db = fresh_v2()
    db.execute("INSERT INTO link (id, title, kind) VALUES (1, 'Mom', 'person'), (2, 'Mom', 'person'), (3, 'The Loft', 'place')")
    # Entry 10 had only the no-note Mom; entry 11 had both (a duplicate after the merge); entry 12 only the note's.
    db.execute("INSERT INTO entry (id, activity_id, start_ms, context_id) VALUES (10, 1, 100, 1), (11, 1, 200, NULL), (12, 1, 300, NULL)")
    db.execute("INSERT INTO entry_link (entry_id, link_id) VALUES (10, 1), (10, 3), (11, 1), (11, 2), (12, 2)")
    db.execute("INSERT INTO moment (id, ts, kind) VALUES (50, 1, 'feeling')")
    db.execute("INSERT INTO moment_ref (moment_id, kind, ref_id) VALUES (50, 'link', 1), (50, 'link', 2), (50, 'activity', 1)")
    db.execute("INSERT INTO vault_note (doc_id, name, modified, title, kind, aliases, link_id) VALUES ('j/Mom.md', 'Mom.md', 1, 'Mom', 'person', '', 2)")
    db.execute("INSERT INTO link_source (link_id, source) VALUES (1, 'mentions'), (2, 'vault')")
    db.execute("INSERT INTO vault_rank (link_id, score) VALUES (1, 3.0), (2, 1.0)")
    keep, drop = 1, 2  # the mention-made one has more entries, so it keeps its id
    db.execute('BEGIN')
    for name, a in (('MERGE_ENTRY_LINKS', (keep, drop)), ('MERGE_ENTRY_LINKS_LEFT', (drop,)), ('MERGE_CONTEXTS', (keep, drop)),
                    ('MERGE_MOMENT_REFS', (keep, drop)), ('MERGE_MOMENT_REFS_LEFT', (drop,)), ('MERGE_VAULT_NOTES', (keep, drop)),
                    ('MERGE_LINK_NOTE', (keep, drop)), ('MERGE_LINK_NOTE_LEFT', (drop,)), ('MERGE_SOURCE_LEFT', (drop,)),
                    ('MERGE_RANK_LEFT', (drop,)), ('MERGE_LINK_DELETE', (drop,)), ('LINK_SOURCE_TO_VAULT', (keep,))):
        db.execute(SQL[name], a)
    db.execute('COMMIT')
    got = sorted(db.execute('SELECT entry_id, link_id FROM entry_link').fetchall())
    check(got == [(10, 1), (10, 3), (11, 1), (12, 1)], 'every entry keeps its person and place, once: %r' % got)
    check(one(db, 'SELECT context_id FROM entry WHERE id = 10') == 1, 'context kept')
    check(sorted(db.execute('SELECT kind, ref_id FROM moment_ref').fetchall()) == [('activity', 1), ('link', 1)], 'moment refs merged')
    check(one(db, "SELECT link_id FROM vault_note WHERE doc_id = 'j/Mom.md'") == 1, 'the note follows the kept link')
    check(db.execute('SELECT link_id, source FROM link_source').fetchall() == [(1, 'vault')], 'kept link is a vault link now')
    check(db.execute('SELECT link_id FROM vault_rank').fetchall() == [(1,)], 'dropped rank gone')
    check(one(db, 'SELECT COUNT(*) FROM link WHERE id = 2') == 0, 'dropped link gone')
    check(db.execute(SQL['NOTES_PER_LINK']).fetchall() == [(1, 1)], 'notes per link')


# --- Extra activities (schema v6) ---

def test_extras():
    db = fresh_v2()
    db.execute('INSERT INTO entry (id, activity_id, start_ms) VALUES (1, 1, 100), (2, 3, 200)')
    for args in ((1, 2), (1, 2), (1, 4), (2, 2)):
        db.execute(SQL['EXTRA_ADD'], args)
    got = sorted(db.execute(SQL['EXTRAS_FOR'] + '(1, 2)').fetchall())
    check(got == [(1, 2), (1, 4), (2, 2)], 'extras once each, read for several entries: %r' % got)
    db.execute(SQL['EXTRA_REMOVE'], (1, 4))
    check(sorted(db.execute(SQL['EXTRAS_FOR'] + '(1)').fetchall()) == [(1, 2)], 'one extra removed')
    # Deleting an entry takes its extras; another entry's stay.
    db.execute('BEGIN')
    for k in ('DETACH_MOMENTS', 'DELETE_ENTRY_LINKS', 'DELETE_ENTRY_EXTRAS', 'DELETE_ENTRY'):
        db.execute(SQL[k], (1,))
    db.execute('COMMIT')
    check(db.execute('SELECT entry_id, activity_id FROM entry_activity').fetchall() == [(2, 2)], 'extras go with their entry')


# --- Daylio import ---

PREDEF = {1: 'rad', 2: 'good', 3: 'meh', 4: 'bad', 5: 'awful'}


def key(e):
    return 'daylio:%04d-%02d-%02d %02d:%02d:%s' % (e['y'], e['mo'], e['d'], e['h'], e['mi'], e['mood'].strip().lower())


def alt_key(e):
    return key(e) + ':%08x' % (zlib.crc32(e['note'].encode('utf-8')) & 0xffffffff)


def decide(e, note_at_key, alt_taken, native):
    if native:
        return None
    if note_at_key is None:
        return key(e)
    if note_at_key == e['note']:
        return None
    if alt_taken:
        return None
    return alt_key(e)


def ts_of(e):
    return ((((e['y'] * 13 + e['mo']) * 32 + e['d']) * 24 + e['h']) * 60 + e['mi']) * 60_000  # monotonic stand-in


def import_entries(db, entries, mood_ids, tag_ids, fail_after=None):
    """Store.importDaylio's loop, in one transaction. Returns (added, already_there)."""
    added = there = 0
    db.execute('BEGIN')
    try:
        for i, e in enumerate(entries):
            if fail_after is not None and i == fail_after:
                raise RuntimeError('simulated failure')
            mood_id = mood_ids[e['mood']]
            ts = ts_of(e)
            note_at_key = one(db, SQL['NOTE_AT_KEY'], (key(e),))
            alt_taken = note_at_key is not None and note_at_key != e['note'] and \
                one(db, SQL['KEY_TAKEN'], (alt_key(e),)) is not None
            native = one(db, SQL['NATIVE_FEELING_AT'], (mood_id, ts, ts + 60_000)) is not None
            k = decide(e, note_at_key, alt_taken, native)
            if k is None:
                there += 1
                continue
            cur = db.execute(SQL['INSERT_IMPORTED'], (ts, e.get('title', ''), e['note'], mood_id, k))
            mid = cur.lastrowid
            for t in dict.fromkeys(e.get('tags', [])):
                db.execute(SQL['INSERT_MOMENT_TAG'], (mid, tag_ids[t]))
            for (kind, ref) in e.get('refs', []):
                db.execute(SQL['INSERT_MOMENT_REF'], (mid, kind, ref))
            added += 1
        db.execute('COMMIT')
    except Exception:
        db.execute('ROLLBACK')
        raise
    return added, there


def E(mi, mood, note, tags=(), refs=()):
    return {'y': 2020, 'mo': 1, 'd': 4, 'h': 9, 'mi': mi, 'mood': mood, 'note': note, 'tags': list(tags), 'refs': list(refs)}


def snapshot(db):
    return [db.execute(f'SELECT * FROM {t} ORDER BY rowid').fetchall() for t in ('moment', 'moment_tag', 'moment_ref')]


def test_import():
    db = fresh_v2()
    moods = {n: one(db, 'SELECT id FROM mood WHERE name = ?', (n,)) for n in PREDEF.values()}
    db.execute("INSERT INTO feeling_tag (id, name) VALUES (1, 'tired'), (2, 'calm')")
    tags = {'tired': 1, 'calm': 2}
    backup_2021 = [
        E(30, 'rad', 'first', ['tired', 'tired']),
        E(30, 'rad', 'second, same minute and mood', ['calm']),  # kept under its own key
        E(30, 'rad', 'first', ['tired']),                         # a true repeat inside the file
        E(31, 'good', 'next', refs=[('activity', 5), ('activity', 5)]),
    ]
    check(import_entries(db, backup_2021, moods, tags) == (3, 1), 'first import adds 3, skips the in-file repeat')
    check(one(db, 'SELECT COUNT(*) FROM moment') == 3, '3 moments')
    check(one(db, "SELECT COUNT(*) FROM moment WHERE source_key LIKE 'daylio:2020-01-04 09:30:rad:%'") == 1,
          'the same-minute check-in has a suffixed key')
    check(one(db, 'SELECT COUNT(*) FROM moment_tag') == 2 and one(db, 'SELECT COUNT(*) FROM moment_ref') == 1,
          'tags and refs written once each')
    before = snapshot(db)

    # The same backup again, and an older overlapping one in a different order: nothing new.
    check(import_entries(db, backup_2021, moods, tags) == (0, 4), 're-import adds nothing')
    check(import_entries(db, list(reversed(backup_2021[:2])), moods, tags) == (0, 2), 'overlapping import adds nothing')
    check(snapshot(db) == before, 'database unchanged by re-imports')

    # A feeling logged in Now, exported as Daylio CSV and imported back: matched by minute and mood.
    native_ts = ts_of(E(45, 'meh', '')) + 20_000  # 20 s into the minute
    db.execute("INSERT INTO moment (ts, kind, text, mood_id) VALUES (?, 'feeling', 'logged in Now', ?)", (native_ts, moods['meh']))
    check(import_entries(db, [E(45, 'meh', 'logged in Now')], moods, tags) == (0, 1), 'native feeling not duplicated')
    check(import_entries(db, [E(45, 'bad', 'other mood')], moods, tags) == (1, 0), 'a different mood in that minute is new')
    before = snapshot(db)

    # A crash part way through, or a failing insert, leaves nothing behind.
    later = [E(50 + i, 'good', f'later {i}', ['calm']) for i in range(5)]
    try:
        import_entries(db, later, moods, tags, fail_after=3)
        check(False, 'simulated failure should raise')
    except RuntimeError:
        pass
    check(snapshot(db) == before, 'failed import rolled back completely')
    bad = [E(56, 'good', 'ok'), E(57, 'good', 'unknown tag', ['missing'])]
    try:
        import_entries(db, bad, moods, tags)
        check(False, 'a failing insert should raise')
    except KeyError:
        pass
    check(snapshot(db) == before, 'import with a failing row rolled back completely')
    db.execute('BEGIN')
    try:
        db.execute(SQL['INSERT_IMPORTED'], (1, '', 'x', moods['rad'], key(backup_2021[0])))
        check(False, 'a duplicate key must fail, not be ignored')
    except sqlite3.IntegrityError:
        pass
    db.execute('ROLLBACK')
    check(snapshot(db) == before, 'duplicate key insert rolled back')


# --- Obsidian export ---

def test_export_days():
    db = fresh_v2()
    for d in ('2025-4-9', '2025-4-10'):
        db.execute(SQL['MARK_DAY_DIRTY'], ('obsidian', d))
    db.execute(SQL['MARK_DAY_DIRTY'], ('obsidian', '2025-4-9'))  # again: still one row
    check(one(db, "SELECT COUNT(*) FROM export_day WHERE target = 'obsidian'") == 2, 'marking a day twice keeps one row')
    dirty = dict(db.execute(SQL['DIRTY_DAYS'], ('obsidian',)).fetchall())
    check(dirty == {'2025-4-10': 1, '2025-4-9': 2}, f'both dirty, with their change counters: {dirty}')
    check(one(db, SQL['DAY_GEN'], ('obsidian', '2025-4-9')) == 2, 'the counter is readable')
    check(one(db, SQL['DAY_GEN'], ('obsidian', '2099-1-1')) is None, 'a day never marked has no counter')

    # The write protocol's clear: only if the day wasn't marked again since the counter was read.
    gen = one(db, SQL['DAY_GEN'], ('obsidian', '2025-4-9'))
    db.execute(SQL['MARK_DAY_DIRTY'], ('obsidian', '2025-4-9'))  # a change lands during the write
    db.execute(SQL['CLEAR_DAY_DIRTY'], ('obsidian', '2025-4-9', gen))
    check(one(db, SQL['DAY_GEN'], ('obsidian', '2025-4-9')) == 3, 'a change during the write keeps the day dirty')
    db.execute(SQL['CLEAR_DAY_DIRTY'], ('obsidian', '2025-4-9', 3))
    check([r[0] for r in db.execute(SQL['DIRTY_DAYS'], ('obsidian',))] == ['2025-4-10'], 'cleared one')
    check(one(db, SQL['DAY_GEN'], ('obsidian', '2025-4-9')) == 0, 'a cleared day reads as 0')
    db.execute(SQL['MARK_DAY_DIRTY'], ('obsidian', '2025-4-9'))  # a change after the clear dirties it again
    check(len(db.execute(SQL['DIRTY_DAYS'], ('obsidian',)).fetchall()) == 2, 'a change after the write marks it dirty again')
    check(one(db, SQL['DAY_GEN'], ('obsidian', '2025-4-9')) == 1, 'and starts a new count')
    # A blanked hash keeps the document id (an interrupted write is recognised later).
    check(one(db, SQL['REMOTE_OF_DAY'], ('obsidian', '2025-4-9')) is None, 'no document id before the first write')

    # Hashes: saved per target and day, replaced on a new write.
    check(one(db, SQL['HASH_OF_DAY'], ('obsidian', '2025-4-9')) is None, 'no hash before the first write')
    db.execute(SQL['SAVE_HASH'], ('obsidian', '2025-4-9', 'doc1', 'h1', 10))
    db.execute(SQL['SAVE_HASH'], ('obsidian', '2025-4-9', 'doc1', 'h2', 20))
    db.execute(SQL['SAVE_HASH'], ('calendar', '2025-4-9', 'evt', 'other', 30))
    check(one(db, SQL['HASH_OF_DAY'], ('obsidian', '2025-4-9')) == 'h2', 'hash replaced')
    check(one(db, SQL['HASH_OF_DAY'], ('calendar', '2025-4-9')) == 'other', 'targets are separate')

    # A rename dirties only the days already written, for this target only.
    db.execute("INSERT INTO export_day (target, day, dirty) VALUES ('obsidian', '2025-1-1', 0), ('calendar', '2025-4-9', 0)")
    db.execute(SQL['SAVE_HASH'], ('obsidian', '2025-1-1', 'doc0', 'h0', 5))
    db.execute(SQL['SAVE_HASH'], ('obsidian', '2025-4-9', 'doc1', '', 25))  # blanked, as the protocol does
    check(one(db, SQL['REMOTE_OF_DAY'], ('obsidian', '2025-4-9')) == 'doc1' and one(db, SQL['HASH_OF_DAY'], ('obsidian', '2025-4-9')) == '',
          'a blanked hash keeps the document id')
    db.execute(SQL['CLEAR_DAY_DIRTY'], ('obsidian', '2025-4-9', 1))
    db.execute(SQL['CLEAR_DAY_DIRTY'], ('obsidian', '2025-4-10', 1))
    db.execute(SQL['MARK_WRITTEN_BUMP'], ('obsidian', 'obsidian'))
    db.execute(SQL['MARK_WRITTEN_ADD'], ('obsidian', 'obsidian'))
    check(sorted(r[0] for r in db.execute(SQL['DIRTY_DAYS'], ('obsidian',))) == ['2025-1-1', '2025-4-9'],
          'only days that have a written note become dirty')
    # A day already dirty keeps counting (a write in flight can't clear it).
    g = one(db, SQL['DAY_GEN'], ('obsidian', '2025-1-1'))
    db.execute(SQL['MARK_WRITTEN_BUMP'], ('obsidian', 'obsidian'))
    db.execute(SQL['MARK_WRITTEN_ADD'], ('obsidian', 'obsidian'))
    check(one(db, SQL['DAY_GEN'], ('obsidian', '2025-1-1')) == g + 1, 'a rename during a write bumps the counter')
    check(one(db, "SELECT dirty FROM export_day WHERE target = 'calendar'") == 0, "another target's days are left alone")


def test_change_reach():
    db = fresh_v2()
    for i, ms in enumerate((100, 200, 300)):
        db.execute('INSERT INTO entry (id, activity_id, start_ms) VALUES (?, 1, ?)', (i + 1, ms))
    check(one(db, SQL['PREV_ENTRY_START'], (200,)) == 100 and one(db, SQL['NEXT_ENTRY_START'], (200,)) == 300,
          'the neighbours of the middle entry')
    check(one(db, SQL['PREV_ENTRY_START'], (100,)) is None, 'nothing before the first')
    check(one(db, SQL['NEXT_ENTRY_START'], (300,)) is None, 'nothing after the last: the range runs to now')
    db.execute('INSERT INTO entry (id, activity_id, start_ms) VALUES (9, 1, 200)')  # same start time
    check(one(db, SQL['PREV_ENTRY_START'], (200,)) == 100 and one(db, SQL['NEXT_ENTRY_START'], (200,)) == 300,
          'an entry at the same start does not narrow the range')


# --- Calendar export records ---

def test_calendar_records():
    db = fresh_v2()
    save = SQL['SAVE_HASH']
    for key, ev in (('2026-11-1/e5', 1), ('2026-11-1/e9', 2), ('2026-11-10/e3', 3), ('2026-11-18/e7', 4), ('2026-11-1', 99)):
        db.execute(save, ('calendar', key, f'10:{ev}', 'h', 1))
    db.execute(save, ('obsidian', '2026-11-1', 'doc', 'h', 1))
    db.execute(save, ('calendar-left', '10:50', '10:50', '2026-11-1/e1', 1))
    rows = lambda day: sorted(r[0] for r in db.execute(SQL['CAL_RECORDS_OF_DAY'], ('calendar', day + '/', day + '0')))
    check(rows('2026-11-1') == ['2026-11-1/e5', '2026-11-1/e9'], f"a day's records only, not 11-10 or 11-18: {rows('2026-11-1')}")
    check(rows('2026-11-10') == ['2026-11-10/e3'], '11-10 alone')
    check(rows('2026-11-18') == ['2026-11-18/e7'], '11-18 alone')
    check(rows('2026-11-2') == [], 'a day with none')
    check(len(db.execute(SQL['CAL_RECORDS_ALL'], ('calendar',)).fetchall()) == 5, 'all of one target')
    check(db.execute(SQL['CAL_RECORDS_ALL'], ('calendar-left',)).fetchall() == [('10:50', '10:50', '2026-11-1/e1')],
          'left records are their own target')

    # A settings change marks only days with calendar records dirty, for the calendar only.
    db.execute("INSERT INTO export_day (target, day, dirty) VALUES ('calendar', '2026-11-1', 2), ('obsidian', '2026-11-10', 0)")
    db.execute(SQL['CAL_MARK_WRITTEN_BUMP'], ('calendar', 'calendar'))
    db.execute(SQL['CAL_MARK_WRITTEN_ADD'], ('calendar', 'calendar'))
    dirty = dict(db.execute(SQL['DIRTY_DAYS'], ('calendar',)).fetchall())
    check(dirty == {'2026-11-1': 3, '2026-11-10': 1, '2026-11-18': 1}, f'days with events dirty, a dirty one bumped once: {dirty}')
    check(one(db, "SELECT dirty FROM export_day WHERE target = 'obsidian'") == 0, 'Obsidian days untouched')

    db.execute(SQL['CAL_RECORD_DELETE'], ('calendar', '2026-11-1/e5'))
    check(rows('2026-11-1') == ['2026-11-1/e9'], 'one record dropped')
    check(one(db, "SELECT COUNT(*) FROM export_state WHERE target = 'calendar-left'") == 1, 'the left record stays')


def test_toggl_records():
    db = fresh_v2()
    save = SQL['SAVE_HASH']
    for key, remote, h in (('2026-11-1/e5', '77:3649001', 'h'), ('2026-11-1/e9', '77:0', 'pending:100:60:t:h'),
                           ('2026-11-10/e3', '88:3649002', 'h'), ('2026-11-1/e1', '77:3649003', 'why')):
        db.execute(save, ('toggl' if key != '2026-11-1/e1' else 'toggl-released', key, remote, h, 1))
    db.execute(save, ('calendar', '2026-11-1/e5', '10:4', 'h', 1))
    rows = lambda target, day: sorted(r[0] for r in db.execute(SQL['CAL_RECORDS_OF_DAY'], (target, day + '/', day + '0')))
    check(rows('toggl', '2026-11-1') == ['2026-11-1/e5', '2026-11-1/e9'], "a day's Toggl records, the pending one too")
    check(rows('toggl-released', '2026-11-1') == ['2026-11-1/e1'], 'released keys are their own target')
    check(rows('toggl', '2026-11-10') == ['2026-11-10/e3'], '11-10 is not part of 11-1')
    check(one(db, SQL['TOGGL_SENT_COUNT'], ('toggl',)) == 2, 'sent entries are counted without the pending one')
    check(one(db, SQL['TOGGL_SENT_COUNT'], ('calendar',)) == 1, 'and per target')
    pend = db.execute(SQL['CAL_RECORDS_ALL'], ('toggl',)).fetchall()
    check(('2026-11-1/e9', '77:0', 'pending:100:60:t:h') in pend, 'the pending details come back whole')

    # Renaming an activity marks only days with Toggl records, for Toggl only.
    db.execute("INSERT INTO export_day (target, day, dirty) VALUES ('toggl', '2026-11-1', 2), ('calendar', '2026-11-20', 0)")
    db.execute(SQL['CAL_MARK_WRITTEN_BUMP'], ('toggl', 'toggl'))
    db.execute(SQL['CAL_MARK_WRITTEN_ADD'], ('toggl', 'toggl'))
    dirty = dict(db.execute(SQL['DIRTY_DAYS'], ('toggl',)).fetchall())
    check(dirty == {'2026-11-1': 3, '2026-11-10': 1}, f'days with entries dirty, a dirty one bumped once: {dirty}')
    check(one(db, "SELECT dirty FROM export_day WHERE target = 'calendar'") == 0, 'calendar days untouched')

    # A different account: records go for good, or move aside under other targets, and a second shelving replaces the first.
    db.execute(save, ('toggl-released', '2026-11-1/e1', '77:5', 'x', 1))
    db.execute(SQL['RETARGET'], ('toggl-old', 'toggl'))
    db.execute(SQL['RETARGET'], ('toggl-old-released', 'toggl-released'))
    check(one(db, "SELECT COUNT(*) FROM export_state WHERE target = 'toggl'") == 0, 'nothing left under toggl')
    check(one(db, "SELECT COUNT(*) FROM export_state WHERE target = 'toggl-old'") == 3, 'the records are kept, aside')
    check(one(db, "SELECT COUNT(*) FROM export_state WHERE target = 'calendar'") == 1, 'calendar records untouched')
    db.execute(save, ('toggl', '2026-11-1/e5', '99:7', 'h', 2))
    db.execute(SQL['RETARGET'], ('toggl-old', 'toggl'))
    check(one(db, SQL['HASH_OF_DAY'], ('toggl-old', '2026-11-1/e5')) == 'h', 'a second shelving replaces a key rather than failing')
    db.execute(SQL['DELETE_TARGET'], ('toggl-old',))
    check(one(db, "SELECT COUNT(*) FROM export_state WHERE target LIKE 'toggl%'") == 1, 'forgetting removes only that target (the released one stays here)')

    # The central mark covers a third target the same way.
    for t in ('obsidian', 'calendar', 'toggl'):
        db.execute(SQL['MARK_DAY_DIRTY'], (t, '2026-12-1'))
        db.execute(SQL['MARK_DAY_DIRTY'], (t, '2026-12-1'))
    check(dict(db.execute(SQL['DIRTY_DAYS'], ('toggl',)).fetchall()).get('2026-12-1') == 2, 'a change counter per target')


def test_group_areas_and_uses():
    db = fresh_v2()
    db.execute(SQL['GROUP_AREA_SET'], ('Work', '12'))
    db.execute(SQL['GROUP_AREA_SET'], ('Work', '13'))
    db.execute(SQL['GROUP_AREA_SET'], ('', ''))
    check(dict(db.execute(SQL['GROUP_AREAS']).fetchall()) == {'Work': '13', '': ''}, 'one area per group, replaced')
    for i, (act, ms) in enumerate(((1, 100), (1, 200), (2, 300), (0, 400), (3, 50))):
        db.execute('INSERT INTO entry (id, activity_id, start_ms) VALUES (?, ?, ?)', (i + 1, act, ms))
    check(dict(db.execute(SQL['ACT_USES'], (100,)).fetchall()) == {1: 2, 2: 1}, 'uses since a time, idle left out')


# --- Vault index ---

def test_vault_index():
    db = fresh_v2()
    db.execute("INSERT INTO link (id, title, kind) VALUES (1, 'Sam', 'person'), (2, 'The Loft', 'place'), (3, 'Trip', 'context')")
    db.execute("INSERT INTO entry (id, activity_id, start_ms, context_id) VALUES (1, 1, 10, 3), (2, 1, 20, 3), (3, 1, 30, NULL)")
    db.execute("INSERT INTO entry_link (entry_id, link_id) VALUES (1, 1), (2, 1), (3, 2)")
    check(dict(db.execute(SQL['LINK_USES']).fetchall()) == {1: 2, 2: 1}, 'uses as person or place')
    check(dict(db.execute(SQL['CONTEXT_USES']).fetchall()) == {3: 2}, 'uses as context')

    up = ('doc1', 'Sam.md', 5, 'Sam', 'person', 'Sammy\nS', 1)
    db.execute(SQL['VAULT_UPSERT'], up)
    db.execute(SQL['VAULT_UPSERT'], ('doc2', 'Tartine.md', 7, 'Tartine', 'place', '', 2))
    db.execute(SQL['VAULT_UPSERT'], ('doc3', 'Loose.md', 8, 'Loose', 'other', 'Alias', None))
    cache = {r[0]: r for r in db.execute(SQL['VAULT_CACHE']).fetchall()}
    check(len(cache) == 3 and cache['doc1'][5] == 'Sam' and cache['doc3'][4] is None, 'three cached notes, with their titles')
    db.execute(SQL['VAULT_UPSERT'], ('doc1', 'Sam.md', 6, 'Sam', 'person', 'Sammy\nS\nSamuel', 1))
    check(one(db, "SELECT COUNT(*) FROM vault_note") == 3 and one(db, "SELECT modified FROM vault_note WHERE doc_id = 'doc1'") == 6,
          'a changed note replaces its row')
    check(dict(db.execute(SQL['VAULT_ALIASES']).fetchall()) == {1: 'Sammy\nS\nSamuel'},
          'aliases come back by matched link, notes with no link or no alias left out')
    db.execute(SQL['VAULT_DELETE'], ('doc2',))
    check(one(db, "SELECT COUNT(*) FROM vault_note") == 2, 'a deleted note leaves the cache')
    check(one(db, "SELECT COUNT(*) FROM link") == 3, 'and never deletes a link')
    db.execute(SQL['LINK_SOURCE_ADD'], (2,))
    db.execute(SQL['LINK_SOURCE_ADD'], (2,))
    check(one(db, "SELECT COUNT(*) FROM link_source WHERE source = 'vault'") == 1, 'a link is marked as scan-made once')


def test_alias_and_rename_sql():
    db = fresh_v2()
    db.execute("INSERT INTO link (id, title, kind) VALUES (1, 'Tae', 'person'), (2, 'Old Cafe', 'place')")
    db.execute(SQL['VAULT_UPSERT'], ('d1', 'Taehyun Kim.md', 1, 'Taehyun Kim', 'person', 'Tae\nT', 3))
    db.execute(SQL['LINK_NOTE_SET'], (1, 'd1', 'Taehyun Kim'))
    check(db.execute(SQL['LINK_NOTES']).fetchall() == [(1, 'd1', 'Taehyun Kim')], 'a link is bound to its note')
    db.execute(SQL['LINK_NOTE_SET'], (1, 'd1', 'Tae Kim'))  # the note was renamed
    check(db.execute(SQL['LINK_NOTES']).fetchall() == [(1, 'd1', 'Tae Kim')], 'a binding is replaced, not doubled')
    check(len(db.execute(SQL['VAULT_NOTES']).fetchall()) == 1, 'notes come back for re-binding')
    db.execute(SQL['LINK_NOTES_CLEAR'])
    check(db.execute(SQL['LINK_NOTES']).fetchall() == [], 'bindings clear')
    db.execute(SQL['VAULT_UPSERT'], ('d2', 'Old Cafe.md', 1, 'Old Cafe', 'place', '', 2))
    db.execute(SQL['VAULT_UPSERT'], ('d2', 'New Cafe.md', 2, 'New Cafe', 'place', '', 2))  # same document, new name
    db.execute(SQL['VAULT_RETITLE'], ('New Cafe', 2))
    check(one(db, 'SELECT title FROM link WHERE id = 2') == 'New Cafe' and one(db, 'SELECT COUNT(*) FROM link') == 2,
          'a renamed note retitles its link and makes no new one')
    check(one(db, "SELECT COUNT(*) FROM vault_note WHERE doc_id = 'd2'") == 1, 'and keeps one row per document')


def test_v2_links_stay_chips_after_a_scan():
    """Hand-made people and places from a v2 database aren't touched or marked by the vault merge (mirrors Store.applyVaultScan)."""
    import tempfile
    tmp = tempfile.mkdtemp()
    db = tm.make_v2(tmp + '/v2.db')
    check(tm.open_helper(db) == 'upgraded', 'v2 upgrade ran')
    before = db.execute('SELECT id, title, kind, emoji, pos, pinned, archived FROM link ORDER BY id').fetchall()
    chips = lambda: sorted(r[0] for r in db.execute('SELECT id FROM link WHERE id NOT IN (SELECT link_id FROM link_source)'))
    check(chips() == sorted(r[0] for r in before), 'every v2 link is a chip before a scan')
    by_title = {r[1].lower(): r[0] for r in before}
    # The scan finds notes for Sam, The Loft and Dulce (matched: the link is used as is) and one new note.
    for doc, title, kind in (('a', 'Sam', 'person'), ('b', 'The Loft', 'place'), ('c', 'Dulce', 'person')):
        db.execute(SQL['VAULT_UPSERT'], (doc, title + '.md', 1, title, kind, '', by_title[title.lower()]))
    db.execute("INSERT INTO link (title, kind) VALUES ('Newcomer', 'person')")
    new_id = one(db, "SELECT id FROM link WHERE title = 'Newcomer'")
    db.execute(SQL['LINK_SOURCE_ADD'], (new_id,))
    after = db.execute('SELECT id, title, kind, emoji, pos, pinned, archived FROM link WHERE id <> ? ORDER BY id', (new_id,)).fetchall()
    check(after == before, 'matching notes leave the hand-made links exactly as they were')
    check(new_id not in chips() and all(r[0] in chips() for r in before), 'only the scan-made link is off the chips')


def main():
    test_delete_detaches()
    test_sticky_context()
    test_flow_defaults_and_uses()
    test_titles()
    test_mentions()
    test_link_merge()
    test_extras()
    test_import()
    test_export_days()
    test_change_reach()
    test_calendar_records()
    test_toggl_records()
    test_group_areas_and_uses()
    test_vault_index()
    test_alias_and_rename_sql()
    test_v2_links_stay_chips_after_a_scan()
    print(f'{len(failures)} failures' if failures else
          f'PASS: {len(SQL)} statements from StoreSql.kt; delete detaches moments; sticky context; check-in flow defaults and uses; free-written titles; mention index; link merge; extra activities; '
          'import dedupe (re-import, same-minute notes, native feelings); rollback on failure; '
          'export dirty days, change counters and hashes; change reach; calendar and Toggl records by day; areas; vault cache; alias binding and rename; v2 links stay chips')
    sys.exit(1 if failures else 0)


if __name__ == '__main__':
    main()
