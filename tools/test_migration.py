#!/usr/bin/env python3
"""Proves the v1 → v2 database upgrade off the phone, with python's sqlite3.

- Builds a v1 database from the exact DDL in build 6's Store.kt (git commit 79494ee).
- Fills it with realistic rows.
- Applies the upgrade SQL read straight out of core/Schema.kt (not retyped), the way
  SQLiteOpenHelper does: in one transaction that also sets user_version.
- Checks that nothing was lost or changed, that the result matches a fresh v2 install,
  that a second open does nothing, and that a failure half way leaves v1 exactly as it was.

Run: python3 tools/test_migration.py   (exit code 0 = pass)
"""
import os, re, sqlite3, subprocess, sys

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
SCHEMA_KT = os.path.join(REPO, 'app/src/main/java/com/jono/nowwidget/core/Schema.kt')
V1_COMMIT = '79494ee'
V1_STORE = 'app/src/main/java/com/jono/nowwidget/Store.kt'

LIT = r'"((?:[^"\\$]|\\.)*)"'
failures = []


def check(cond, msg):
    if not cond:
        failures.append(msg)
        print('FAIL:', msg)


# --- Reading the SQL out of the Kotlin sources ---

def v1_ddl():
    src = subprocess.run(['git', '-C', REPO, 'show', f'{V1_COMMIT}:{V1_STORE}'],
                         check=True, capture_output=True, text=True).stdout
    on_create = src[src.index('override fun onCreate'):src.index('SEEDS.forEachIndexed')]
    out = []
    for m in re.finditer(r'db\.execSQL\(\s*((?:' + LIT + r'\s*\+?\s*)+)\)', on_create):
        out.append(''.join(re.findall(LIT, m.group(1))))
    assert len(out) == 5, out
    return out


def schema_list(name):
    src = open(SCHEMA_KT, encoding='utf-8').read()
    m = re.search(r'// BEGIN ' + name + r'\n(.*?)// END ' + name + r'\n', src, re.S)
    assert m, 'no list ' + name
    out = []
    for line in m.group(1).splitlines():
        line = line.strip()
        if not line.startswith('"'):
            continue
        lits = re.findall(LIT, line)
        assert len(lits) == 1 and line.endswith('",'), 'one plain literal per line: ' + line
        assert '\\' not in lits[0] and '$' not in lits[0], 'no escapes or templates: ' + line
        out.append(lits[0])
    assert out, 'empty list ' + name
    return out


def kotlin_function_lists(fn):
    """Which lists Schema.create() / upgradeFrom1() join, in order, so the test follows the code."""
    src = open(SCHEMA_KT, encoding='utf-8').read()
    m = re.search(r'fun ' + fn + r'\(\): List<String> = ([A-Z0-9_ +]+)\n', src)
    assert m, fn
    return [p.strip() for p in m.group(1).split('+')]


def statements(fn):
    return [s for name in kotlin_function_lists(fn) for s in schema_list(name)]


# --- A v1 database with realistic rows ---

def make_v1(path):
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path, isolation_level=None)
    for s in v1_ddl():
        db.execute(s)
    acts = [('Sleep', '😴', 'Body', 0), ('Work', '💼', 'Work', 0), ('Hobby', '🎨', 'Play', 0),
            ('Old thing', '🗑️', 'Life', 1), ('Other', '✳️', 'Life', 0)]
    for i, (n, e, g, arch) in enumerate(acts):
        db.execute('INSERT INTO activity (name, emoji, color, pos, grp, archived) VALUES (?,?,?,?,?,?)',
                   (n, e, -10777105 + i, i, g, arch))
    db.execute("UPDATE activity SET show_btn = 0 WHERE name = 'Other'")
    people = [('Sam', ''), ('Dulce', '🌸'), ('Mom', '👩'), ('Gone', '')]
    for i, (n, e) in enumerate(people):
        db.execute('INSERT INTO person (name, emoji, pos, archived) VALUES (?,?,?,?)', (n, e, i, 1 if n == 'Gone' else 0))
    # A gap in person ids, as if one had been deleted by hand.
    db.execute("INSERT INTO person (id, name, emoji, pos) VALUES (9, 'Ninth', '', 9)")
    t = 1_731_000_000_000
    notes = [
        '', 'Simple note', 'Line one\nLine two\nLine three', 'He said "hi", then left; it\'s fine',
        '  padded both sides  ', ' \n\t ', '🎉 emoji note', '', 'Comma, separated, words', '',
        'Note on an archived activity', 'x' * 2000,
    ]
    acts_cycle = [1, 2, 3, 0, 2, 1, 3, 0, 4, 2, 4, 5]
    for i, (a, n) in enumerate(zip(acts_cycle, notes)):
        db.execute('INSERT INTO entry (activity_id, start_ms, note) VALUES (?,?,?)', (a, t + i * 1_800_000, n))
    # Two entries at the same start time, as moveBack can leave.
    db.execute("INSERT INTO entry (activity_id, start_ms, note) VALUES (2, ?, 'same time')", (t + 5 * 1_800_000,))
    for e, p in [(2, 1), (2, 2), (3, 1), (5, 3), (7, 9), (9, 4), (12, 2)]:
        db.execute('INSERT INTO entry_person (entry_id, person_id) VALUES (?,?)', (e, p))
    db.execute('PRAGMA user_version = 1')
    return db


def rows(db, sql):
    return db.execute(sql).fetchall()


def dump(db):
    """Every table's rows plus the schema, for exact before/after comparisons."""
    out = {'user_version': db.execute('PRAGMA user_version').fetchone()[0]}
    for (name, sql) in rows(db, "SELECT name, sql FROM sqlite_master ORDER BY type, name"):
        out['schema:' + name] = sql
        if sql and sql.startswith('CREATE TABLE'):
            out['rows:' + name] = rows(db, f'SELECT * FROM "{name}" ORDER BY rowid')
    return out


def schema_shape(db):
    """The schema as SQLite sees it, independent of how the CREATE text was written."""
    shape = {}
    for (name, typ) in rows(db, "SELECT name, type FROM sqlite_master WHERE type IN ('table','index') ORDER BY name"):
        if typ == 'table':
            shape['table:' + name] = rows(db, f'PRAGMA table_info("{name}")')
            shape['fk:' + name] = rows(db, f'PRAGMA foreign_key_list("{name}")')
            shape['indexes:' + name] = sorted(rows(db, f'PRAGMA index_list("{name}")'), key=lambda r: r[1])
        else:
            shape['index:' + name] = rows(db, f'PRAGMA index_xinfo("{name}")')
    return shape


# --- What SQLiteOpenHelper does on open ---

def open_helper(db, fail_after=None):
    """Like SQLiteOpenHelper with version 2: one transaction around onUpgrade and the version bump."""
    v = db.execute('PRAGMA user_version').fetchone()[0]
    if v == 2:
        return 'nothing to do'
    assert v == 1, v
    db.execute('BEGIN')
    try:
        for i, s in enumerate(statements('upgradeFrom1')):
            if fail_after is not None and i == fail_after:
                raise RuntimeError('simulated crash')
            db.execute(s)
        db.execute('PRAGMA user_version = 2')
        db.execute('COMMIT')
    except Exception:
        db.execute('ROLLBACK')
        raise
    return 'upgraded'


def main():
    tmp = '/tmp/now-migration-test'
    os.makedirs(tmp, exist_ok=True)
    up = statements('upgradeFrom1')
    print(f'v1 DDL: {len(v1_ddl())} statements from {V1_COMMIT}; upgrade: {len(up)} statements from Schema.kt')

    # 1. A crash half way through leaves the v1 database exactly as it was.
    db = make_v1(tmp + '/crash.db')
    before = dump(db)
    for k in range(1, len(up)):
        try:
            open_helper(db, fail_after=k)
            check(False, 'simulated crash did not raise')
        except RuntimeError:
            pass
        check(dump(db) == before, f'crash after {k} statements changed the v1 database')
    check(open_helper(db) == 'upgraded', 'upgrade after a crash')
    db.close()

    # 2. The real upgrade.
    db = make_v1(tmp + '/v1.db')
    old = {t: rows(db, f'SELECT * FROM {t} ORDER BY rowid') for t in ('activity', 'person', 'entry', 'entry_person')}
    entries_with_notes = rows(db, "SELECT id, start_ms, note FROM entry WHERE trim(note, ' ' || char(9) || char(10) || char(13)) <> '' ORDER BY id")
    check(open_helper(db) == 'upgraded', 'upgrade ran')
    check(db.execute('PRAGMA user_version').fetchone()[0] == 2, 'user_version is 2')

    # Old tables untouched (entry only gains the new, empty context_id column).
    check(rows(db, 'SELECT * FROM activity ORDER BY rowid') == old['activity'], 'activity unchanged')
    check(rows(db, 'SELECT * FROM person ORDER BY rowid') == old['person'], 'person unchanged')
    check(rows(db, 'SELECT * FROM entry_person ORDER BY rowid') == old['entry_person'], 'entry_person unchanged')
    check(rows(db, 'SELECT id, activity_id, start_ms, note FROM entry ORDER BY rowid') == old['entry'], 'entry rows and notes unchanged')
    check(rows(db, 'SELECT COUNT(*) FROM entry WHERE context_id IS NOT NULL') == [(0,)], 'context_id starts empty')

    # People became person links with the same ids, names, emoji, order and archived flag.
    links = rows(db, 'SELECT id, title, kind, emoji, pos, pinned, archived FROM link ORDER BY id')
    check(links == [(i, n, 'person', e, p, 0, a) for (i, n, e, p, a) in old['person']], 'person → link, same ids')
    check(sorted(rows(db, 'SELECT entry_id, link_id FROM entry_link')) == sorted(old['entry_person']), 'entry_person → entry_link')

    # Every note became exactly one note moment, whole text, at its entry's start.
    moments = rows(db, 'SELECT entry_id, ts, kind, title, text, mood_id, source_key FROM moment ORDER BY entry_id')
    check(len(moments) == len(entries_with_notes), f'{len(moments)} moments for {len(entries_with_notes)} notes')
    check(moments == [(i, s, 'note', '', n, None, None) for (i, s, n) in entries_with_notes], 'moments match notes exactly')
    check(rows(db, 'SELECT COUNT(*) FROM (SELECT entry_id FROM moment GROUP BY entry_id HAVING COUNT(*) > 1)') == [(0,)], 'one moment per entry')
    blank = rows(db, "SELECT COUNT(*) FROM moment m JOIN entry e ON e.id = m.entry_id WHERE e.note = ' ' || char(10) || char(9) || ' '")
    check(blank == [(0,)], 'a whitespace-only note makes no moment')

    # Moods seeded, other new tables empty.
    check(rows(db, 'SELECT name, level, pos, archived FROM mood ORDER BY id') ==
          [('rad', 1, 0, 0), ('good', 2, 1, 0), ('meh', 3, 2, 0), ('bad', 4, 3, 0), ('awful', 5, 4, 0)], 'moods seeded')
    for t in ('feeling_tag', 'moment_tag', 'moment_ref', 'group_calendar', 'export_state', 'export_day'):
        check(rows(db, f'SELECT COUNT(*) FROM {t}') == [(0,)], t + ' empty')

    # A new link gets a fresh id, never one a person already has.
    db.execute("INSERT INTO link (title, kind) VALUES ('The Loft', 'place')")
    new_id = db.execute('SELECT MAX(id) FROM link').fetchone()[0]
    check(new_id > max(p[0] for p in old['person']), 'new link ids come after migrated ones')
    db.execute('DELETE FROM link WHERE id = ?', (new_id,))

    # 3. Opening again does nothing; running the upgrade SQL again by force fails and changes nothing.
    after = dump(db)
    check(open_helper(db) == 'nothing to do', 'second open is a no-op')
    check(dump(db) == after, 'second open changed nothing')
    db.execute('BEGIN')
    try:
        for s in up:
            db.execute(s)
        check(False, 'forced second upgrade should fail')
        db.execute('ROLLBACK')
    except sqlite3.OperationalError:
        db.execute('ROLLBACK')
    check(dump(db) == after, 'a forced second upgrade rolled back cleanly (no duplicates)')

    # 4. The upgraded schema equals a fresh v2 install.
    fresh_path = tmp + '/fresh.db'
    if os.path.exists(fresh_path):
        os.remove(fresh_path)
    fresh = sqlite3.connect(fresh_path, isolation_level=None)
    fresh.execute('BEGIN')
    for s in statements('create'):
        fresh.execute(s)
    fresh.execute('PRAGMA user_version = 2')
    fresh.execute('COMMIT')
    a, b = schema_shape(db), schema_shape(fresh)
    for k in sorted(set(a) | set(b)):
        check(a.get(k) == b.get(k), f'schema differs at {k}: upgraded={a.get(k)} fresh={b.get(k)}')
    check(rows(fresh, 'SELECT name, level, emoji, pos FROM mood ORDER BY id') ==
          rows(db, 'SELECT name, level, emoji, pos FROM mood ORDER BY id'), 'same mood seed both ways')

    # 5. The unique source key: NULLs repeat freely, real keys can't.
    db.execute("INSERT INTO moment (ts, kind, source_key) VALUES (1, 'feeling', 'daylio:2020-01-04 09:30:rad')")
    db.execute("INSERT OR IGNORE INTO moment (ts, kind, source_key) VALUES (1, 'feeling', 'daylio:2020-01-04 09:30:rad')")
    check(rows(db, "SELECT COUNT(*) FROM moment WHERE source_key IS NOT NULL") == [(1,)], 'source_key dedupes')
    db.close()
    fresh.close()

    print(f'{len(failures)} failures' if failures else
          f'PASS: {len(old["entry"])} entries, {len(old["person"])} people, {len(entries_with_notes)} notes → moments; '
          f'schema equals a fresh install; crash-safe at every step; second open is a no-op')
    sys.exit(1 if failures else 0)


if __name__ == '__main__':
    main()
