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
import com.jono.nowwidget.core.KeywordRule

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
    @Volatile private var cals: List<CalInfo>? = null
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
            if (on && Prefs.calLog(a) <= 0L) {
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
                "The app only ever changes or deletes events it made itself, in your log calendars. Edits you make in Google Calendar aren't read back."
        ))
        writeNow.text = "Write changes now"
        writeNow.setOnClickListener { CalendarExport.writeNow(a) }
        setup.addView(writeNow)

        setup.addView(a.label("Check it works"))
        testButton.text = "Test calendar"
        testButton.setOnClickListener { CalendarExport.test(a) }
        deleteTest.text = "Delete test event"
        deleteTest.setOnClickListener { CalendarExport.deleteTest(a) }
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

    private fun cal(id: Long): CalInfo? = cals?.firstOrNull { it.id == id }

    private fun name(id: Long): String = cal(id)?.label ?: if (cals == null) "…" else "a calendar that's gone"

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
        if (Prefs.calOn(a)) {
            val last = Prefs.calLastExport(a)
            lines.add(if (last == 0L) "Writing to the calendar. Nothing written yet." else "Last written: " + Fmt.day(last) + ", " + Fmt.time(a, last))
        } else {
            lines.add("Not writing to the calendar.")
        }
        if (CalendarExport.busy.isNotBlank()) lines.add(CalendarExport.busy)
        if (loadError.isNotBlank()) lines.add("Couldn't read your calendars: $loadError")
        val problem = Prefs.calError(a)
        if (problem.isNotBlank()) lines.add("Problem: $problem")
        val info = Prefs.calInfo(a)
        if (info.isNotBlank()) lines.add(info)
        status.text = lines.joinToString("\n")

        logButton.text = if (log > 0L) "Log calendar: " + name(log) else "Choose the log calendar"
        perArea.isChecked = Prefs.calPerArea(a)
        export.isChecked = Prefs.calOn(a)
        val idle = CalendarExport.busy.isBlank()
        writeNow.isEnabled = idle && CalendarExport.configured(a)
        writeNow.visibility = if (Prefs.calOn(a)) View.VISIBLE else View.GONE
        testButton.isEnabled = idle && log > 0L
        deleteTest.isEnabled = idle
        val test = Prefs.calTest(a)
        testResult.text = if (test.isBlank()) "Writes one 5-minute event ending now into the log calendar and says what happened." else "Last test: $test"
        nudge.isChecked = Prefs.calNudge(a)

        drawAreas()
        drawOld()
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
        groups().forEach { g ->
            val area = map[g]
            val label = (g.ifEmpty { "Ungrouped" }) + " → " + if (area == null) (if (g.isEmpty()) "no color" else "no area") else name(area)
            areas.addView(colorRow(label, area?.let { cal(it)?.color }) { pickArea(g) })
        }
        if (per) {
            map.values.filterNotNull().distinct().forEach { area ->
                val l = logs[area]
                areas.addView(colorRow(
                    name(area) + " events go to " + (if (l == null) name(Prefs.calLog(a)) + " (the log calendar)" else name(l)),
                    cal(l ?: Prefs.calLog(a))?.color,
                ) { pickAreaLog(area) })
            }
        }
    }

    private fun drawOld() {
        old.removeAllViews()
        val logs = (listOf(Prefs.calLog(a)) + if (Prefs.calPerArea(a)) Prefs.calAreaLogs(a).values else emptyList()).toSet()
        CalendarExport.oldCalendars(a, logs).forEach { (id, n) ->
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
    private fun pickCalendar(title: String, only: (CalInfo) -> Boolean, none: String?, onPick: (CalInfo?) -> Unit) {
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

    private fun pickLog() {
        pickCalendar("Log calendar", { it.writable }, null) { c ->
            if (c == null) return@pickCalendar
            if (c.id == Prefs.calLog(a)) return@pickCalendar
            fun use() {
                Prefs.setCalLog(a, c.id)
                settingsChanged()
            }
            val areaIds = Store.groupAreas(a).values.filterNotNull()
            when {
                c.local -> AlertDialog.Builder(a)
                    .setMessage("${c.label} is only on this phone, so its events never reach Google. Use it anyway?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Use it") { _, _ -> use() }
                    .show()
                c.id in areaIds -> AlertDialog.Builder(a)
                    .setMessage("${c.label} is one of your areas. The log usually goes into a calendar of its own, like Log. Use it anyway?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Use it") { _, _ -> use() }
                    .show()
                else -> use()
            }
        }
    }

    private fun pickArea(group: String) {
        val label = group.ifEmpty { "Ungrouped activities" }
        pickCalendar(label, { true }, if (group.isEmpty()) "No color" else "No area") { c ->
            Store.setGroupArea(a, group, c?.id)
            settingsChanged()
        }
    }

    private fun pickAreaLog(area: Long) {
        pickCalendar("Log calendar for " + name(area), { it.writable }, "The main log calendar") { c ->
            Prefs.setCalAreaLog(a, area, c?.id)
            settingsChanged()
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
                    "Log events include your notes and moods, so anyone you share the log calendar with can see them. Nothing goes anywhere else: the app has no internet access, Google's own sync uploads the events."
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
