#!/usr/bin/env python3
"""Proves the database upgrades (v1 → v3 and v2 → v3) off the phone, with python's sqlite3.

- Builds a v1 database from the exact DDL build 6's Store.kt ran (commit 79494ee), and a v2
  database from the exact DDL Phase 1 shipped (commit ab008e3), both pasted below.
- Fills them with realistic rows.
- Applies the upgrade SQL read straight out of core/Schema.kt (not retyped), the way
  SQLiteOpenHelper does: in one transaction that also sets user_version. Which lists run for
  which old version is checked against Store.kt's onUpgrade.
- Checks that nothing was lost or changed, that the result matches a fresh install,
  that a second open does nothing, and that a failure half way leaves the old database exactly as it was.

Run: python3 tools/test_migration.py   (exit code 0 = pass)
"""
import os, re, sqlite3, sys

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
SCHEMA_KT = os.path.join(REPO, 'app/src/main/java/com/jono/nowwidget/core/Schema.kt')

LIT = r'"((?:[^"\\$]|\\.)*)"'
failures = []


def check(cond, msg):
    if not cond:
        failures.append(msg)
        print('FAIL:', msg)


# --- Reading the SQL out of the Kotlin sources ---

# The five statements build 6 (commit 79494ee, Store.kt onCreate) ran, copied verbatim. This is
# the source of truth for what a v1 database on the phone looks like.
V1_DDL = [
    "CREATE TABLE activity (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
    "emoji TEXT NOT NULL, color INTEGER NOT NULL, pos INTEGER NOT NULL, "
    "show_btn INTEGER NOT NULL DEFAULT 1, grp TEXT NOT NULL DEFAULT '', "
    "archived INTEGER NOT NULL DEFAULT 0)",
    "CREATE TABLE person (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
    "emoji TEXT NOT NULL DEFAULT '', pos INTEGER NOT NULL, "
    "archived INTEGER NOT NULL DEFAULT 0)",
    "CREATE TABLE entry (id INTEGER PRIMARY KEY AUTOINCREMENT, "
    "activity_id INTEGER NOT NULL, start_ms INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '')",
    "CREATE INDEX entry_start ON entry (start_ms)",
    "CREATE TABLE entry_person (entry_id INTEGER NOT NULL, person_id INTEGER NOT NULL, "
    "PRIMARY KEY (entry_id, person_id))",
]


# Version 2 as Phase 1 shipped it (commit ab008e3: Schema.kt BASE + NEW_IN_V2 + SEED_MOODS), verbatim.
V2_DDL = [
    "CREATE TABLE activity (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, emoji TEXT NOT NULL, color INTEGER NOT NULL, pos INTEGER NOT NULL, show_btn INTEGER NOT NULL DEFAULT 1, grp TEXT NOT NULL DEFAULT '', archived INTEGER NOT NULL DEFAULT 0)",
    "CREATE TABLE person (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, emoji TEXT NOT NULL DEFAULT '', pos INTEGER NOT NULL, archived INTEGER NOT NULL DEFAULT 0)",
    "CREATE TABLE entry (id INTEGER PRIMARY KEY AUTOINCREMENT, activity_id INTEGER NOT NULL, start_ms INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '', context_id INTEGER)",
    'CREATE INDEX entry_start ON entry (start_ms)',
    'CREATE TABLE entry_person (entry_id INTEGER NOT NULL, person_id INTEGER NOT NULL, PRIMARY KEY (entry_id, person_id))',
    "CREATE TABLE link (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'person', emoji TEXT NOT NULL DEFAULT '', pos INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0)",
    'CREATE INDEX link_kind ON link (kind, pos)',
    'CREATE TABLE entry_link (entry_id INTEGER NOT NULL, link_id INTEGER NOT NULL, PRIMARY KEY (entry_id, link_id))',
    'CREATE INDEX entry_link_link ON entry_link (link_id)',
    "CREATE TABLE mood (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, level INTEGER NOT NULL, emoji TEXT NOT NULL DEFAULT '', pos INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0)",
    "CREATE TABLE moment (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL DEFAULT '', text TEXT NOT NULL DEFAULT '', mood_id INTEGER, entry_id INTEGER, source_key TEXT)",
    'CREATE INDEX moment_ts ON moment (ts)',
    'CREATE INDEX moment_entry ON moment (entry_id)',
    'CREATE UNIQUE INDEX moment_source ON moment (source_key)',
    "CREATE TABLE feeling_tag (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, emoji TEXT NOT NULL DEFAULT '', pos INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0)",
    'CREATE TABLE moment_tag (moment_id INTEGER NOT NULL, tag_id INTEGER NOT NULL, PRIMARY KEY (moment_id, tag_id))',
    'CREATE INDEX moment_tag_tag ON moment_tag (tag_id)',
    'CREATE TABLE moment_ref (moment_id INTEGER NOT NULL, kind TEXT NOT NULL, ref_id INTEGER NOT NULL, PRIMARY KEY (moment_id, kind, ref_id))',
    "CREATE TABLE group_calendar (grp TEXT PRIMARY KEY NOT NULL, calendar TEXT NOT NULL DEFAULT '')",
    "CREATE TABLE export_state (target TEXT NOT NULL, local_key TEXT NOT NULL, remote_id TEXT NOT NULL DEFAULT '', content_hash TEXT NOT NULL DEFAULT '', synced_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (target, local_key))",
    'CREATE TABLE export_day (target TEXT NOT NULL, day TEXT NOT NULL, dirty INTEGER NOT NULL DEFAULT 1, PRIMARY KEY (target, day))',
    "INSERT INTO mood (name, level, emoji, pos, archived) VALUES ('rad', 1, '😄', 0, 0)",
    "INSERT INTO mood (name, level, emoji, pos, archived) VALUES ('good', 2, '🙂', 1, 0)",
    "INSERT INTO mood (name, level, emoji, pos, archived) VALUES ('meh', 3, '😐', 2, 0)",
    "INSERT INTO mood (name, level, emoji, pos, archived) VALUES ('bad', 4, '🙁', 3, 0)",
    "INSERT INTO mood (name, level, emoji, pos, archived) VALUES ('awful', 5, '😫', 4, 0)",
]


def v1_ddl():
    return V1_DDL


def schema_version():
    src = open(SCHEMA_KT, encoding='utf-8').read()
    return int(re.search(r'const val VERSION = (\d+)', src).group(1))


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


def upgrade_statements(old_version):
    """What Store.kt's onUpgrade runs for a database at old_version: the same gates, same order."""
    store = open(os.path.join(REPO, 'app/src/main/java/com/jono/nowwidget/Store.kt'), encoding='utf-8').read()
    gates = re.findall(r'if \(oldVersion < (\d+)\) Schema\.(\w+)\(\)\.forEach', store)
    assert gates == [('2', 'upgradeFrom1'), ('3', 'upgradeFrom2')], 'onUpgrade in Store.kt changed: ' + str(gates)
    out = []
    for below, fn in gates:
        if old_version < int(below):
            out += statements(fn)
    return out


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
    """Like SQLiteOpenHelper with the current version: one transaction around onUpgrade and the version bump."""
    version = schema_version()
    v = db.execute('PRAGMA user_version').fetchone()[0]
    if v == version:
        return 'nothing to do'
    assert v in (1, 2), v
    db.execute('BEGIN')
    try:
        for i, s in enumerate(upgrade_statements(v)):
            if fail_after is not None and i == fail_after:
                raise RuntimeError('simulated crash')
            db.execute(s)
        db.execute(f'PRAGMA user_version = {version}')
        db.execute('COMMIT')
    except Exception:
        db.execute('ROLLBACK')
        raise
    return 'upgraded'


def make_v2(path):
    """A version 2 database as Phase 1 shipped it, with every v2 table holding rows."""
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path, isolation_level=None)
    db.execute('BEGIN')
    for s in V2_DDL:
        db.execute(s)
    db.execute('COMMIT')
    for i, (n, e, g) in enumerate([('Sleep', '😴', 'Body'), ('Work', '💼', 'Work'), ('Hobby', '🎨', 'Play'), ('Eat', '🍽️', 'Body')]):
        db.execute('INSERT INTO activity (name, emoji, color, pos, grp) VALUES (?,?,?,?,?)', (n, e, -1000 - i, i, g))
    links = [('Sam', 'person', '', 0, 1, 0), ('Dulce', 'person', '🌸', 1, 0, 0), ('The Loft', 'place', '', 2, 0, 0),
             ('Tartine', 'place', '', 3, 0, 1), ('2025-4 Trip Home', 'context', '✈️', 4, 1, 0), ('Notes', 'other', '', 5, 0, 0)]
    for t, k, e, p, pin, arch in links:
        db.execute('INSERT INTO link (title, kind, emoji, pos, pinned, archived) VALUES (?,?,?,?,?,?)', (t, k, e, p, pin, arch))
    db.execute("INSERT INTO link (id, title, kind, pos) VALUES (40, 'Gap id', 'person', 40)")
    t0 = 1_731_000_000_000
    for i in range(8):
        db.execute('INSERT INTO entry (activity_id, start_ms, note, context_id) VALUES (?,?,?,?)',
                   ((i % 4) + 1, t0 + i * 1_800_000, 'old note %d' % i if i % 3 == 0 else '', 5 if i > 3 else None))
    for e, l in [(1, 1), (1, 3), (2, 2), (5, 1), (6, 4), (8, 40)]:
        db.execute('INSERT INTO entry_link (entry_id, link_id) VALUES (?,?)', (e, l))
    db.execute("INSERT INTO feeling_tag (name, emoji, pos) VALUES ('tired', '', 0), ('calm', '🍃', 1)")
    db.execute("INSERT INTO mood (name, level, emoji, pos) VALUES ('Up and Down', 3, '🎢', 5)")
    for i, (kind, mood, entry, key) in enumerate([('note', None, 1, None), ('feeling', 2, 1, None), ('feeling', 6, None, 'daylio:2020-01-04 09:30:good'),
                                                  ('note', None, None, None), ('feeling', 1, 3, None)]):
        db.execute('INSERT INTO moment (ts, kind, title, text, mood_id, entry_id, source_key) VALUES (?,?,?,?,?,?,?)',
                   (t0 + i * 600_000, kind, 'T%d' % i if i == 2 else '', 'moment text %d\nline two' % i, mood, entry, key))
    db.execute('INSERT INTO moment_tag (moment_id, tag_id) VALUES (2,1), (2,2), (3,1)')
    db.execute("INSERT INTO moment_ref (moment_id, kind, ref_id) VALUES (3, 'link', 1), (3, 'activity', 2)")
    db.execute("INSERT INTO group_calendar (grp, calendar) VALUES ('Work', 'Work cal')")
    db.execute("INSERT INTO export_state (target, local_key, remote_id, content_hash, synced_at) VALUES ('obsidian', '2025-4-9', 'doc1', 'abc', 5)")
    db.execute("INSERT INTO export_day (target, day, dirty) VALUES ('obsidian', '2025-4-9', 1), ('obsidian', '2025-4-8', 0)")
    db.execute('PRAGMA user_version = 2')
    return db


NEW_V3_TABLES = ('vault_note', 'link_source')


def check_fresh_equals(db, label):
    """The upgraded schema equals a fresh install, and AUTOINCREMENT survived on every table that asked."""
    tmp = os.path.dirname(db.execute('PRAGMA database_list').fetchone()[2])
    fresh_path = tmp + '/fresh-%s.db' % label
    if os.path.exists(fresh_path):
        os.remove(fresh_path)
    fresh = sqlite3.connect(fresh_path, isolation_level=None)
    fresh.execute('BEGIN')
    for s in statements('create'):
        fresh.execute(s)
    fresh.execute(f'PRAGMA user_version = {schema_version()}')
    fresh.execute('COMMIT')
    a, b = schema_shape(db), schema_shape(fresh)
    for k in sorted(set(a) | set(b)):
        check(a.get(k) == b.get(k), f'{label}: schema differs at {k}: upgraded={a.get(k)} fresh={b.get(k)}')
    check(db.execute('PRAGMA user_version').fetchone()[0] == fresh.execute('PRAGMA user_version').fetchone()[0], f'{label}: user_version equals a fresh install')

    def auto_tables(stmts):
        return {re.match(r'CREATE TABLE (\w+)', x).group(1) for x in stmts
                if x.startswith('CREATE TABLE') and 'AUTOINCREMENT' in x}
    want = auto_tables(V1_DDL) | auto_tables(statements('create'))
    check({'activity', 'person', 'entry', 'link', 'mood', 'moment', 'feeling_tag'} <= want, f'AUTOINCREMENT tables: {want}')
    for name, conn in (('upgraded', db), ('fresh', fresh)):
        for t in sorted(want):
            sql = conn.execute("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", (t,)).fetchone()
            check(sql is not None and 'AUTOINCREMENT' in sql[0], f'{label}: {name}: {t} lost AUTOINCREMENT')
    check(rows(fresh, 'SELECT name, level, emoji, pos FROM mood ORDER BY id')[:5] ==
          rows(db, 'SELECT name, level, emoji, pos FROM mood ORDER BY id')[:5], f'{label}: same mood seed both ways')
    return fresh


def check_new_tables_work(db, label):
    """The v3 tables take the rows the vault scan writes (same shapes as StoreSql.VAULT_UPSERT)."""
    for t in NEW_V3_TABLES:
        check(rows(db, f'SELECT COUNT(*) FROM {t}') == [(0,)], f'{label}: {t} starts empty')
    db.execute("INSERT OR REPLACE INTO vault_note (doc_id, name, modified, title, kind, aliases, link_id) VALUES ('d1', 'Sam.md', 5, 'Sam', 'person', 'Sammy\nS', 1)")
    db.execute("INSERT OR REPLACE INTO vault_note (doc_id, name, modified, title, kind, aliases, link_id) VALUES ('d1', 'Sam.md', 6, 'Sam', 'person', '', NULL)")
    check(rows(db, 'SELECT COUNT(*), MAX(modified) FROM vault_note') == [(1, 6)], f'{label}: vault_note upsert replaces by doc_id')
    db.execute("INSERT OR IGNORE INTO link_source (link_id, source) VALUES (1, 'vault')")
    db.execute("INSERT OR IGNORE INTO link_source (link_id, source) VALUES (1, 'vault')")
    check(rows(db, 'SELECT COUNT(*) FROM link_source') == [(1,)], f'{label}: link_source marks a link once')
    db.execute('DELETE FROM vault_note')
    db.execute('DELETE FROM link_source')


def check_crash_safe(make, label, up_len):
    path = '/tmp/now-migration-test/crash-%s.db' % label
    db = make(path)
    before = dump(db)
    for k in range(1, up_len):
        try:
            open_helper(db, fail_after=k)
            check(False, f'{label}: simulated crash did not raise')
        except RuntimeError:
            pass
        check(dump(db) == before, f'{label}: crash after {k} statements changed the old database')
    check(open_helper(db) == 'upgraded', f'{label}: upgrade after a crash')
    db.close()


def main():
    tmp = '/tmp/now-migration-test'
    os.makedirs(tmp, exist_ok=True)
    version = schema_version()
    check(version == 3, f'Schema.VERSION is {version}')
    up1 = upgrade_statements(1)
    up2 = upgrade_statements(2)
    check(up2 == statements('upgradeFrom2') and up1 == statements('upgradeFrom1') + statements('upgradeFrom2'),
          'v1 runs both upgrade lists in order, v2 only the second')
    print(f'v1 DDL: {len(v1_ddl())} statements (build 6); v2 DDL: {len(V2_DDL)}; upgrade from v1: {len(up1)} statements; from v2: {len(up2)}')

    # === From v1 (build 6) ===

    # 1. A crash half way through leaves the v1 database exactly as it was.
    check_crash_safe(make_v1, 'v1', len(up1))

    # 2. The real upgrade.
    db = make_v1(tmp + '/v1.db')
    old = {t: rows(db, f'SELECT * FROM {t} ORDER BY rowid') for t in ('activity', 'person', 'entry', 'entry_person')}
    entries_with_notes = rows(db, "SELECT id, start_ms, note FROM entry WHERE trim(note, ' ' || char(9) || char(10) || char(13)) <> '' ORDER BY id")
    check(open_helper(db) == 'upgraded', 'upgrade ran')
    check(db.execute('PRAGMA user_version').fetchone()[0] == version, f'user_version is {version}')

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
        for s in up1:
            db.execute(s)
        check(False, 'forced second upgrade should fail')
        db.execute('ROLLBACK')
    except sqlite3.OperationalError:
        db.execute('ROLLBACK')
    check(dump(db) == after, 'a forced second upgrade rolled back cleanly (no duplicates)')

    # 4. The upgraded schema equals a fresh install.
    fresh = check_fresh_equals(db, 'v1→v3')
    v1 = make_v1(tmp + '/v1-auto.db')
    for t in ('activity', 'person', 'entry'):
        sql = v1.execute("SELECT sql FROM sqlite_master WHERE name=?", (t,)).fetchone()[0]
        check('AUTOINCREMENT' in sql, f'v1: {t} has no AUTOINCREMENT')
    v1.close()

    # 5. The unique source key: NULLs repeat freely, real keys can't.
    db.execute("INSERT INTO moment (ts, kind, source_key) VALUES (1, 'feeling', 'daylio:2020-01-04 09:30:rad')")
    db.execute("INSERT OR IGNORE INTO moment (ts, kind, source_key) VALUES (1, 'feeling', 'daylio:2020-01-04 09:30:rad')")
    check(rows(db, "SELECT COUNT(*) FROM moment WHERE source_key IS NOT NULL") == [(1,)], 'source_key dedupes')
    check_new_tables_work(db, 'v1→v3')
    db.close()
    fresh.close()

    # === From v2 (Phase 1) ===

    check_crash_safe(make_v2, 'v2', len(up2))
    db = make_v2(tmp + '/v2.db')
    check(rows(db, 'PRAGMA user_version') == [(2,)], 'the v2 fixture is version 2')
    before = dump(db)
    n_tables_before = len([k for k in before if k.startswith('rows:')])
    check(open_helper(db) == 'upgraded', 'v2 upgrade ran')
    check(db.execute('PRAGMA user_version').fetchone()[0] == version, f'v2: user_version is {version}')
    after = dump(db)
    # Every v2 table has exactly the rows and schema it had; only the new tables and user_version differ.
    for k, v in before.items():
        if k == 'user_version':
            continue
        check(after.get(k) == v, f'v2 upgrade changed {k}')
    added = sorted(set(after) - set(before))
    check(added == sorted(['schema:vault_note', 'rows:vault_note', 'schema:vault_note_link', 'schema:sqlite_autoindex_vault_note_1',
                           'schema:link_source', 'rows:link_source']),
          f'v2 upgrade added exactly the new tables and index: {added}')
    check(n_tables_before > 10, 'the v2 fixture filled the tables')
    for t in ('link', 'entry', 'entry_link', 'moment', 'moment_tag', 'moment_ref', 'feeling_tag', 'mood', 'export_state', 'export_day', 'group_calendar'):
        check(len(after['rows:' + t]) > 0, f'v2 fixture had rows in {t}')
    check_new_tables_work(db, 'v2→v3')
    after2 = dump(db)
    check(open_helper(db) == 'nothing to do', 'v2: second open is a no-op')
    check(dump(db) == after2, 'v2: second open changed nothing')
    db.execute('BEGIN')
    try:
        for s in up2:
            db.execute(s)
        check(False, 'v2: forced second upgrade should fail')
        db.execute('ROLLBACK')
    except sqlite3.OperationalError:
        db.execute('ROLLBACK')
    check(dump(db) == after2, 'v2: a forced second upgrade rolled back cleanly')
    fresh2 = check_fresh_equals(db, 'v2→v3')
    # The dirty flag and hash the export keeps survive, so Phase 2 doesn't rewrite what it already wrote.
    check(rows(db, "SELECT local_key, content_hash FROM export_state") == [('2025-4-9', 'abc')], 'v2: export_state kept')
    check(rows(db, "SELECT day, dirty FROM export_day ORDER BY day") == [('2025-4-8', 0), ('2025-4-9', 1)], 'v2: export_day kept')
    db.close()
    fresh2.close()

    print(f'{len(failures)} failures' if failures else
          f'PASS: v1 → v{version} ({len(old["entry"])} entries, {len(old["person"])} people, {len(entries_with_notes)} notes → moments) '
          f'and v2 → v{version} (every v2 table unchanged); both equal a fresh install; crash-safe at every step; second open is a no-op')
    sys.exit(1 if failures else 0)


if __name__ == '__main__':
    main()
