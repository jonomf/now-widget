# Now hub: one log, every device equal

The phone app and the Windows Now widget each keep a full copy of the log and work offline. They
share it through the **hub**: a Google Sheet with a small Apps Script web app (`Code.gs`). Neither
device outranks the other; the hub is just where changes meet. Obsidian, Calendar and (if it ever
comes back) Toggl are outputs made from the log, never sources.

## Records

Everything in the log is a **record**, a JSON object. Each has:

| field | meaning |
|---|---|
| `k` | kind: `act`, `link`, `mood`, `tag`, `entry`, `moment` |
| `id` | global id, never reused: `<device>:<letter>:<local id or uuid>` (letters `a l m t e n`) |
| `v` | version: milliseconds when this version was made; a newer `v` wins |
| `dev` | the device that made this version |
| `del` | `true` for a deletion (then only `k id v dev del` are set) |

`<device>` is `ph-xxxxxx` (the phone app, a random suffix per install) or `pc-xxxxxx` (the Windows
widget). The phone uses its database ids (`ph-3f9a1c:e:812`), so it needs no id table for its own
records; a record from another device gets a local row, mapped by its global id.

Fields by kind (ids of other records are global ids; the "doing nothing" activity is `"idle"`):

- `act`: `name emoji color pos show group archived` (`color` is ARGB as a signed 32-bit int)
- `link`: `title kind emoji pos pinned archived` (`kind`: `person place context other`)
- `mood`: `name level emoji pos archived` (level 1 best … 5 worst, as in Daylio)
- `tag`: `name emoji pos archived` (feeling words)
- `entry`: `act start links ctx title extras`. An entry runs until the next one starts.
  `start` ms; `links` [ids]; `ctx` id or null; `title` free-written words or null; `extras` [act ids]
- `moment`: `ts kind title text mood entry tags refs src`. `kind` `note`/`feeling`; `mood`, `entry`
  id or null; `tags` [ids]; `refs` [[kind, id]] (`activity`/`link`); `src` an import's dedupe key or null

## Merging

For each `id`, the version with the highest `v` wins (ties: the higher `dev` string). A device
applies a record only if it's newer than what it has. Because every device keeps the winner of each
id, they all end up with the same log, whatever order changes arrive in. Concurrent edits to the
same record: the later one wins. Different records never conflict (two devices logging at the same
time just interleave by start).

## The hub API

One endpoint: `POST <web app url>` with a JSON body. Google answers a POST with a redirect; follow
it with a GET to read the answer.

```json
{ "key": "…", "dev": "pc-1a2b3c", "push": [ records… ], "since": 120, "limit": 500 }
```

The hub appends the pushed records to the `log` sheet (one row each; a row's number is its `seq`),
then answers with the rows after `since`:

```json
{ "ok": true, "seq": 245, "last": 245, "more": false, "changes": [ records…, each with "seq" ] }
```

Store `seq` as the next `since`; ask again while `more` is true. Changes include your own pushes:
skip records whose `dev` is yours. A wrong key answers `{ "ok": false, "error": "wrong key" }`.

The sheet is append-only. Never sort or delete its rows: `seq` is the row position.

## Sync order

Push first, then pull, in the same request: local edits get their `v` when they're pushed, so a
remote version made before that loses to the local edit.
