# Now

An Android widget and lock screen notification for saying what you're doing right now, with one tap. There are no timers: each tap ends whatever came before it.

Install from [the latest release](https://github.com/jonomf/now-widget/releases/latest).

- Tap a different activity to switch to it.
- Tap the wrong one? Tap the right one within a minute and the mistake is replaced.
- Tap the current activity again to turn it off. The widget glows softly while nothing is on. A setting can make this tap move the start back instead.
- Larger widgets and the expanded notification let you mark who you're with; the biggest size and the expand panel also show places.
- The 📝 button is a check-in: a mood face, feeling words, where you are, a context (like a trip), and a note (use the keyboard's mic to dictate). Any one of them alone is fine, and it works while nothing is on.
- Diary days run from 4am to 4am, for today's totals, stats and the timeline.
- The timeline shows notes and feelings under the activity they were noted on; everything is editable.
- Import a Daylio backup (`.daylio`, Android or iOS) or Daylio CSV: you choose where each Daylio activity goes (a feeling, a Now activity, a person or a place). Each check-in becomes one feeling at its time. Importing the same or an overlapping backup again adds nothing. Export feelings back out as Daylio CSV.

- **Obsidian:** pick your vault folder once (Android's folder picker, no permission needed) and the app writes one note per diary day, `YYYY-M-D (now).md`, in the vault root, in your own log style (`#log-now`, an `At [[place]] w [[people]] in [[trip]]` line, one bullet per stretch, notes and feelings under it, a totals list). It only writes those notes, only when a day's text changed (about a minute after you change something, and when the app opens), and never reads them back. The vault's root notes are indexed (read-only, hourly and on "Rescan vault notes") so people, places and trips autocomplete from your notes and their aliases; names with no note just become plain links, and no note is ever created for them. A link that is one note's alias is written as `[[Note Title|Link title]]`, and a note renamed in Obsidian renames its link. A day stays marked as changed until its note was fully written, so a write cut short is redone. Add the line shown in Settings to your `_templates/index template` yourself. If Obsidian keeps the vault where the picker can't see it, move it to Documents or pick its parent folder.

- **Calendar:** after you allow calendar access (the app explains first), pick a log calendar (make one called Log on calendar.google.com and sync it on the phone) and map each activity group to one of your calendars, its area. With the export on, each finished stretch of 5 minutes or more (back-to-back stretches of the same activity and links merged) becomes an event like `🎨 Hobby · Sam, Dulce @ Tartine`, with that stretch's notes and check-ins in the description and a last line `now-log:<key>`. It goes into the log calendar in the Google event color nearest its area's calendar color, or into a log calendar per area (a setting). Google's own sync uploads it. Edits in the app update the event; nothing is read back. The app only ever changes or deletes an event it wrote itself: it must have its own record of the event, the event must still carry its `now-log` line, and it must be in a log calendar. Change the log calendar and old events stay put until you tap "Remove log events from …". "Test calendar" writes one 5-minute test event and reports what the phone did with it. The timeline's "Planned" toggle shows the day's planned events from your area calendars against what you did, per area, with a match figure; while a planned event is on, its activity (by your keyword rules, or the area's most used activity) moves to the first widget button. Log events include notes and moods: anyone you share the log calendar with can read them.

The app has no internet permission. The log stays on the phone unless you turn on the calendar export, which hands events to Google's sync. Moods, feelings and notes never show on the lock screen. Export to CSV from the app.

## Development

- `python3 tools/check_layouts.py`: every view id the code uses exists in its layouts.
- `python3 tools/test_migration.py`: upgrades a build-6 (schema v1) and a Phase 1 (schema v2) database to v3 with the SQL in `core/Schema.kt` and checks nothing is lost.
- `python3 tools/test_store_sql.py`: runs the Store's own SQL (`core/StoreSql.kt`) for deletes, sticky context, import dedupe and rollback, the dirty-day counters, calendar export records and the vault cache.
- `tools/run_core_tests.sh`: the unit tests for `core/` (pure Kotlin) without the Android SDK; CI runs them with `gradle :app:testDebugUnitTest`.
- `python3 tools/daylio_decode.py --summary file.daylio`: decode a Daylio backup. Never commit a real backup; the test fixtures are synthetic (`tools/make_daylio_fixtures.py`).
