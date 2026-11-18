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
    check(sorted(r[0] for r in db.execute(SQL['DIRTY_DAYS'], ('obsidian',))) == ['2025-4-10', '2025-4-9'], 'both dirty')
    db.execute(SQL['CLEAR_DAY_DIRTY'], ('obsidian', '2025-4-9'))
    check([r[0] for r in db.execute(SQL['DIRTY_DAYS'], ('obsidian',))] == ['2025-4-10'], 'cleared one')
    db.execute(SQL['MARK_DAY_DIRTY'], ('obsidian', '2025-4-9'))  # a change after the clear dirties it again
    check(len(db.execute(SQL['DIRTY_DAYS'], ('obsidian',)).fetchall()) == 2, 'a change after the write marks it dirty again')

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
    db.execute(SQL['CLEAR_DAY_DIRTY'], ('obsidian', '2025-4-9'))
    db.execute(SQL['CLEAR_DAY_DIRTY'], ('obsidian', '2025-4-10'))
    db.execute(SQL['MARK_WRITTEN_DIRTY'], ('obsidian', 'obsidian'))
    check(sorted(r[0] for r in db.execute(SQL['DIRTY_DAYS'], ('obsidian',))) == ['2025-1-1', '2025-4-9'],
          'only days that have a written note become dirty')
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
    check(len(db.execute(SQL['VAULT_CACHE']).fetchall()) == 3, 'three cached notes')
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


def main():
    test_delete_detaches()
    test_sticky_context()
    test_import()
    test_export_days()
    test_change_reach()
    test_vault_index()
    print(f'{len(failures)} failures' if failures else
          f'PASS: {len(SQL)} statements from StoreSql.kt; delete detaches moments; sticky context; '
          'import dedupe (re-import, same-minute notes, native feelings); rollback on failure; '
          'export dirty days and hashes; change reach; vault cache')
    sys.exit(1 if failures else 0)


if __name__ == '__main__':
    main()
