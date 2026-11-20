package com.jono.nowwidget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.jono.nowwidget.core.CalRef
import com.jono.nowwidget.core.CalRow
import com.jono.nowwidget.core.CalendarMatch
import com.jono.nowwidget.core.CalendarSetup
import com.jono.nowwidget.core.KeywordRule
import com.jono.nowwidget.core.ResolvedSetup

/**
 * The "Calendar" part of the settings screen: calendar access (explained before Android asks),
 * the log calendar, which of your calendars each group is (its area), the test event, turning the
 * export on, removing log events from an old log calendar, and the planned-event keyword rules.
 * Calendar reads happen on a background thread; this only draws.
 */
class CalendarSection(private val a: Activity) {
    val view: LinearLayout = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val status = TextView(a).apply {
        textSize = 14f
        setPadding(0, a.dp(4), 0, a.dp(8))
    }
    private val access = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val setup = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val logButton = Button(a)
    private val perArea = Switch(a)
    private val areas = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val export = Switch(a)
    private val writeNow = Button(a)
    private val testButton = Button(a)
    private val deleteTest = Button(a)
    private val testResult = TextView(a).apply {
        textSize = 13f
        setTextIsSelectable(true)
        setPadding(0, a.dp(4), 0, a.dp(8))
    }
    private val old = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val nudge = Switch(a)
    private val rules = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    /** Your calendars, read in the background; null until the first read. */
    @Volatile private var cals: List<CalRow>? = null
    @Volatile private var loadError = ""
    private var loading = false
    private var drawing = false

    init {
        view.addView(a.heading("Calendar"))
        view.addView(a.note(
            "Writes what you did into a log calendar, one event per stretch of 5 minutes or more, colored by area, and reads your planned events. " +
                "First make a calendar called Log on calendar.google.com, and check it's synced on this phone (Google Calendar app → Settings)."
        ))
        view.addView(status)
        view.addView(access)
        view.addView(setup)

        logButton.setOnClickListener { pickLog() }
        setup.addView(logButton)
        perArea.text = "One log calendar per area"
        perArea.textSize = 16f
        perArea.setPadding(0, a.dp(10), 0, a.dp(10))
        perArea.setOnCheckedChangeListener { _, on ->
            if (drawing) return@setOnCheckedChangeListener
            Prefs.setCalPerArea(a, on)
            settingsChanged()
        }
        setup.addView(perArea)
        setup.addView(a.note("Off: one log calendar, each event in the Google color nearest its area's calendar. On: each area gets its own log calendar."))
        setup.addView(a.label("Areas: which of your calendars each group is"))
        setup.addView(areas)

        export.text = "Write the log into the calendar"
        export.textSize = 16f
        export.setPadding(0, a.dp(10), 0, a.dp(10))
        export.setOnCheckedChangeListener { _, on ->
            if (drawing) return@setOnCheckedChangeListener
            if (on && Prefs.calLog(a) == null) {
                Toast.makeText(a, "Choose a log calendar first", Toast.LENGTH_SHORT).show()
                refresh()
                return@setOnCheckedChangeListener
            }
            Prefs.setCalOn(a, on)
            Prefs.setCalError(a, "")
            if (on) CalendarExport.start(a)
            refresh()
        }
        setup.addView(export)
        setup.addView(a.note(
            "Events hold your notes and moods. Anyone you share the log calendar with can read them. " +
                "The app only ever changes or deletes events it made itself, in your log calendars. Edits you make in Google Calendar aren't read back. " +
                "Deleting a log event in Google Calendar doesn't stick, because the log on this phone is the source: to remove one, delete the entry in Now's timeline."
        ))
        writeNow.text = "Write changes now"
        writeNow.setOnClickListener { CalendarExport.writeNow(a) }
        setup.addView(writeNow)

        setup.addView(a.label("Check it works"))
        testButton.text = "Test calendar"
        testButton.setOnClickListener { CalendarExport.test(a) }
        deleteTest.text = "Delete test events"
        deleteTest.setOnClickListener { CalendarExport.deleteTests(a) }
        setup.addView(testButton)
        setup.addView(deleteTest)
        setup.addView(testResult)
        setup.addView(old)

        setup.addView(a.label("Planned events"))
        nudge.text = "What's planned now gets the first button"
        nudge.textSize = 16f
        nudge.setPadding(0, a.dp(10), 0, a.dp(10))
        nudge.setOnCheckedChangeListener { _, on ->
            if (drawing) return@setOnCheckedChangeListener
            Prefs.setCalNudge(a, on)
            PlannedCache.clear()
            WidgetRenderer.refresh(a)
        }
        setup.addView(nudge)
        setup.addView(a.note("While an event in one of your area calendars is on, its activity moves to the first widget button: the first rule below whose words are in the event's title, or else the area's most used activity."))
        setup.addView(rules)
        setup.addView(Button(a).apply {
            text = "Add a rule"
            setOnClickListener { editRule(null) }
        })

        setup.addView(a.label("Your calendars"))
        setup.addView(list)
        refresh()
    }

    private fun granted() = CalendarAccess.granted(a)

    /** Reads your calendars again, in the background, then redraws. */
    fun reload() {
        if (!granted() || loading) {
            refresh()
            return
        }
        loading = true
        Thread {
            val (found, err) = try {
                CalendarAccess.calendars(a) to ""
            } catch (e: Exception) {
                null to (e.message ?: e.javaClass.simpleName)
            }
            a.runOnUiThread {
                loading = false
                // A failed read still ends "reading", so drawing doesn't ask again and again.
                cals = found ?: cals ?: emptyList()
                loadError = err
                refresh()
            }
        }.start()
    }

    private fun cal(id: Long): CalRow? = cals?.firstOrNull { it.id == id }

    private fun name(id: Long): String = cal(id)?.label ?: if (cals == null) "…" else "a calendar that's gone"

    /** A picked calendar by its identity: its name now, or its old name and a note when it can't be found. */
    private fun name(ref: CalRef): String {
        val all = cals ?: return ref.identity.name.ifBlank { "…" }
        return CalendarMatch.resolve(ref, all)?.label ?: (ref.identity.name + " (not found: pick it again)")
    }

    private fun color(ref: CalRef?): Int? = ref?.let { r -> cals?.let { CalendarMatch.resolve(r, it)?.color } }

    /** The settings against the calendars read last (pure; no calendar reads here). */
    private fun resolved(): ResolvedSetup? {
        val all = cals ?: return null
        return CalendarSetup.resolve(all, Prefs.calLog(a), Prefs.calPerArea(a), Store.groupAreas(a), Prefs.calAreaLogs(a))
    }

    /** Redraws everything. Call on the main thread. */
    fun refresh() {
        drawing = true
        try {
            draw()
        } finally {
            drawing = false
        }
    }

    private fun draw() {
        val on = granted()
        access.removeAllViews()
        setup.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) {
            status.text = "Calendar access is off."
            access.addView(button("Allow calendar access") { explain() })
            if (Prefs.calAsked(a)) {
                access.addView(a.note("If Android doesn't ask again, turn on Calendar in App info → Permissions."))
                access.addView(button("Open app info") {
                    a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName)))
                })
            }
            return
        }
        if (cals == null && !loading) {
            reload()
        }

        val lines = ArrayList<String>()
        val log = Prefs.calLog(a)
        val res = resolved()
        if (Prefs.calOn(a)) {
            val last = Prefs.calLastExport(a)
            lines.add(if (last == 0L) "Writing to the calendar. Nothing written yet." else "Last written: " + Fmt.day(last) + ", " + Fmt.time(a, last))
        } else {
            lines.add("Not writing to the calendar.")
        }
        if (CalendarExport.busy.isNotBlank()) lines.add(CalendarExport.busy)
        if (loadError.isNotBlank()) lines.add("Couldn't read your calendars: $loadError")
        // The settings' own problem (a calendar gone, swapped or unsynced) first: it stops all writing.
        val setupProblem = res?.problem?.takeIf { log != null }
        if (setupProblem != null) lines.add("Problem: $setupProblem")
        val problem = Prefs.calError(a)
        if (problem.isNotBlank() && problem != setupProblem) lines.add("Problem: $problem")
        res?.unsyncedAreas?.forEach { lines.add(it.label + " isn't synced on this phone, so its planned events can't be read.") }
        val released = Store.releasedCount(a)
        if (released > 0) {
            lines.add("$released log event" + (if (released == 1) " was" else "s were") + " edited outside Now and " +
                (if (released == 1) "is" else "are") + " no longer updated.")
        }
        val info = Prefs.calInfo(a)
        if (info.isNotBlank()) lines.add(info)
        status.text = lines.joinToString("\n")

        logButton.text = if (log != null) "Log calendar: " + name(log) else "Choose the log calendar"
        perArea.isChecked = Prefs.calPerArea(a)
        export.isChecked = Prefs.calOn(a)
        val idle = CalendarExport.busy.isBlank()
        writeNow.isEnabled = idle && CalendarExport.configured(a)
        writeNow.visibility = if (Prefs.calOn(a)) View.VISIBLE else View.GONE
        testButton.isEnabled = idle && log != null
        deleteTest.isEnabled = idle
        val test = Prefs.calTest(a)
        testResult.text = if (test.isBlank()) "Writes one 5-minute event ending now into the log calendar and says what happened." else "Last test: $test"
        nudge.isChecked = Prefs.calNudge(a)

        drawAreas()
        drawOld(res)
        drawRules()
        drawList()
    }

    private fun groups(): List<String> {
        val acts = Store.allActs(a).filter { !it.archived }
        return acts.map { it.group.trim() }.filter { it.isNotEmpty() }.distinct().sorted() + ""
    }

    private fun drawAreas() {
        areas.removeAllViews()
        val map = Store.groupAreas(a)
        val per = Prefs.calPerArea(a)
        val logs = Prefs.calAreaLogs(a)
        val main = Prefs.calLog(a)
        groups().forEach { g ->
            val area = map[g]
            val label = (g.ifEmpty { "Ungrouped" }) + " → " + if (area == null) (if (g.isEmpty()) "no color" else "no area") else name(area)
            areas.addView(colorRow(label, color(area)) { pickArea(g) })
        }
        if (per) {
            map.values.filterNotNull().distinctBy { it.identity.key }.forEach { area ->
                val l = logs[area.identity.key]
                val mainName = if (main == null) "no log calendar yet" else name(main) + " (the log calendar)"
                areas.addView(colorRow(
                    name(area) + " events go to " + (if (l == null) mainName else name(l)),
                    color(l ?: main),
                ) { pickAreaLog(area) })
            }
        }
    }

    private fun drawOld(res: ResolvedSetup?) {
        old.removeAllViews()
        CalendarExport.oldCalendars(a, res).forEach { (id, n) ->
            val label = name(id)
            old.addView(button("Remove log events from $label") { confirmRemove(id, label, n) })
        }
    }

    private fun drawRules() {
        rules.removeAllViews()
        val acts = Store.allActs(a).associateBy { it.id }
        val list = Prefs.calRules(a)
        if (list.isEmpty()) rules.addView(a.note("No rules yet. Example: \"gym\" → 🏃 Move."))
        list.forEachIndexed { i, r ->
            val act = acts[r.activityId]
            rules.addView(TextView(a).apply {
                text = "\"" + r.keyword + "\" → " + (act?.let { it.emoji + " " + it.name } ?: "removed activity")
                textSize = 15f
                setPadding(0, a.dp(8), 0, a.dp(8))
                setOnClickListener { editRule(i) }
            })
        }
    }

    private fun drawList() {
        list.removeAllViews()
        val all = cals
        if (all == null) {
            list.addView(a.note("Reading your calendars…"))
            return
        }
        if (all.isEmpty()) list.addView(a.note("No calendars on this phone."))
        all.forEach { c ->
            val bits = listOf(
                c.account,
                if (c.writable) "can write" else "read only",
                if (c.local) "this phone only" else "",
                if (!c.synced) "not synced" else "",
            ).filter { it.isNotBlank() }.joinToString(" · ")
            list.addView(colorRow(c.label + "\n" + bits, c.color, null))
        }
    }

    private fun button(label: String, onTap: () -> Unit) = Button(a).apply {
        text = label
        setOnClickListener { onTap() }
    }

    /** A dot in [color] (none when null) and a label; tappable when [onTap] is given. */
    private fun colorRow(label: String, color: Int?, onTap: (() -> Unit)?): View {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, a.dp(8), 0, a.dp(8))
            if (onTap != null) setOnClickListener { onTap() }
        }
        row.addView(TextView(a).apply {
            text = if (color == null) "○" else "●"
            textSize = 18f
            if (color != null) setTextColor(color)
            setPadding(0, 0, a.dp(10), 0)
        })
        row.addView(TextView(a).apply {
            text = label
            textSize = 15f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** A list of calendars to pick from, with their dots. [none] adds a first row that picks null. */
    private fun pickCalendar(title: String, only: (CalRow) -> Boolean, none: String?, onPick: (CalRow?) -> Unit) {
        val all = cals
        if (all == null) {
            Toast.makeText(a, "Still reading your calendars", Toast.LENGTH_SHORT).show()
            reload()
            return
        }
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(20), a.dp(8), a.dp(20), 0)
        }
        val dialog = AlertDialog.Builder(a)
            .setTitle(title)
            .setView(ScrollView(a).apply { addView(col) })
            .setNegativeButton("Cancel", null)
            .create()
        if (none != null) col.addView(colorRow(none, null) {
            dialog.dismiss()
            onPick(null)
        })
        val usable = all.filter(only)
        if (usable.isEmpty()) col.addView(a.note("None of your calendars can be used here."))
        usable.forEach { c ->
            col.addView(colorRow(c.label + "\n" + c.account, c.color) {
                dialog.dismiss()
                onPick(c)
            })
        }
        dialog.show()
    }

    /**
     * Every log calendar pick (the main one and per area) goes through here: the shared-calendar
     * caution always, and a warning when it's phone-only, not synced, or one of your areas.
     */
    private fun confirmLog(c: CalRow, use: () -> Unit) {
        val areaKeys = Store.groupAreas(a).values.filterNotNull().map { it.identity.key }.toSet()
        val warnings = ArrayList<String>()
        if (c.local) warnings.add("${c.label} is only on this phone, so its events never reach Google.")
        if (!c.synced || !c.visible) warnings.add("${c.label} isn't synced and shown on this phone, so nothing will be written until it is (Google Calendar app → Settings).")
        if (c.identity.key in areaKeys) warnings.add("${c.label} is one of your areas. The log usually goes into a calendar of its own, like Log.")
        warnings.add("Log events hold your notes and moods: anyone you share ${c.label} with can read them.")
        AlertDialog.Builder(a)
            .setTitle("Log into ${c.label}?")
            .setMessage(warnings.joinToString("\n\n"))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use it") { _, _ -> use() }
            .show()
    }

    private fun pickLog() {
        pickCalendar("Log calendar", { it.writable }, null) { c ->
            if (c == null) return@pickCalendar
            confirmLog(c) {
                Prefs.setCalLog(a, c.ref())
                settingsChanged()
            }
        }
    }

    private fun pickArea(group: String) {
        val label = group.ifEmpty { "Ungrouped activities" }
        pickCalendar(label, { true }, if (group.isEmpty()) "No color" else "No area") { c ->
            Store.setGroupArea(a, group, c?.ref())
            settingsChanged()
        }
    }

    private fun pickAreaLog(area: CalRef) {
        fun set(ref: CalRef?) {
            val m = HashMap(Prefs.calAreaLogs(a))
            if (ref == null) m.remove(area.identity.key) else m[area.identity.key] = ref
            Prefs.setCalAreaLogs(a, CalendarSetup.prune(m, Store.groupAreas(a)))
            settingsChanged()
        }
        pickCalendar("Log calendar for " + name(area), { it.writable }, "The main log calendar") { c ->
            if (c == null) set(null) else confirmLog(c) { set(c.ref()) }
        }
    }

    /** Log calendar, areas or layout changed: every day already written is redone (old calendars are left alone). */
    private fun settingsChanged() {
        Store.markCalendarWrittenDirty(a)
        Prefs.setCalInfo(a, "")
        PlannedCache.clear()
        refresh()
        WidgetRenderer.refresh(a)
    }

    private fun confirmRemove(calendarId: Long, label: String, n: Int) {
        AlertDialog.Builder(a)
            .setTitle("Remove log events?")
            .setMessage(
                "Delete the $n log event" + (if (n == 1) "" else "s") + " this app wrote to $label? " +
                    "Only events the app made and you haven't changed are deleted. Nothing else in $label is touched."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ -> CalendarExport.removeFrom(a, calendarId, label) }
            .show()
    }

    private fun editRule(index: Int?) {
        val all = Prefs.calRules(a)
        val current = index?.let { all.getOrNull(it) }
        val acts = Store.allActs(a).filter { !it.archived || it.id == current?.activityId }
        if (acts.isEmpty()) return
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(20), a.dp(8), a.dp(20), 0)
        }
        box.addView(a.label("When the event's title has"))
        val words = EditText(a).apply {
            setSingleLine(true)
            hint = "gym"
            setText(current?.keyword ?: "")
        }
        box.addView(words)
        box.addView(a.label("the first button is"))
        val spinner = Spinner(a).apply {
            adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, acts.map { it.emoji + " " + it.name })
            val at = acts.indexOfFirst { it.id == current?.activityId }
            if (at >= 0) setSelection(at)
        }
        box.addView(spinner)
        val b = AlertDialog.Builder(a)
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val k = words.text.toString().trim()
                if (k.isEmpty()) return@setPositiveButton
                val r = KeywordRule(k, acts[spinner.selectedItemPosition].id)
                val next = all.toMutableList()
                if (index != null && index < next.size) next[index] = r else next.add(r)
                Prefs.setCalRules(a, next)
                refresh()
                WidgetRenderer.refresh(a)
            }
        if (index != null) {
            b.setNeutralButton("Delete") { _, _ ->
                Prefs.setCalRules(a, all.filterIndexed { i, _ -> i != index })
                refresh()
                WidgetRenderer.refresh(a)
            }
        }
        b.show()
    }

    /** Says what Android is about to ask and why, then asks. */
    private fun explain() {
        AlertDialog.Builder(a)
            .setTitle("Calendar access")
            .setMessage(
                "Android will ask to let Now see and change your calendar events. " +
                    "Now uses that to write your log as events in a calendar you pick, and to read your planned events to compare and to suggest a button. " +
                    "It only ever changes or deletes events it made itself, in the log calendars you pick. " +
                    "Log events include your notes and moods, so anyone you share the log calendar with can see them. Nothing goes anywhere else: the calendar export itself uses no internet, Google's own sync uploads the events."
            )
            .setNegativeButton("Not now", null)
            .setPositiveButton("Continue") { _, _ ->
                Prefs.setCalAsked(a)
                a.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), REQUEST_CALENDAR)
            }
            .show()
    }

    /** True when the result was this section's. */
    fun onPermission(code: Int, results: IntArray): Boolean {
        if (code != REQUEST_CALENDAR) return false
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            Prefs.setCalError(a, "")
            PlannedCache.clear()
            reload()
            WidgetRenderer.refresh(a)
        } else {
            refresh()
        }
        return true
    }

    companion object {
        const val REQUEST_CALENDAR = 7
    }
}
