package com.jono.nowwidget

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.jono.nowwidget.core.DiaryDay
import com.jono.nowwidget.core.Entry
import com.jono.nowwidget.core.FeelingTag
import com.jono.nowwidget.core.LinkKind
import com.jono.nowwidget.core.Moment
import com.jono.nowwidget.core.Mood
import com.jono.nowwidget.core.Span
import java.util.Calendar

/**
 * One diary day (4am to 4am) at a time: what happened, with notes and feelings under the
 * activity they were noted on, and everything editable.
 */
class TimelineActivity : Activity() {
    private lateinit var dayLabel: TextView
    private lateinit var ribbon: RibbonView
    private lateinit var list: LinearLayout
    private var day = Fmt.today()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nav.addView(Button(this).apply {
            text = "◀"
            setOnClickListener { go(-1) }
        })
        // Tap the date to jump to any day.
        dayLabel = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { pickDay() }
        }
        nav.addView(dayLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(Button(this).apply {
            text = "▶"
            setOnClickListener { go(1) }
        })
        col.addView(nav)

        val jumps = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        jumps.addView(Button(this).apply {
            text = "First"
            setOnClickListener { Store.firstAndLast(this@TimelineActivity)?.let { jump(it.first) } }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        jumps.addView(Button(this).apply {
            text = "Pick a day"
            setOnClickListener { pickDay() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        jumps.addView(Button(this).apply {
            text = "Today"
            setOnClickListener { jump(System.currentTimeMillis()) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(jumps)

        ribbon = RibbonView(this)
        col.addView(
            ribbon,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(18)).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            },
        )
        col.addView(Button(this).apply {
            text = "Add something I forgot"
            setOnClickListener { edit(null) }
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        setContentView(ScrollView(this).apply { addView(col) })

        val asked = intent.getLongExtra(EXTRA_DAY, -1L)
        if (asked >= 0L) day = DiaryDay.start(minOf(asked, System.currentTimeMillis()))
    }

    /** Opens the diary day containing [ms]. */
    private fun jump(ms: Long) {
        day = DiaryDay.start(minOf(ms, System.currentTimeMillis()))
        draw()
    }

    private fun pickDay() {
        val c = Calendar.getInstance()
        c.timeInMillis = day
        val d = DatePickerDialog(
            this,
            { _, y, m, dom ->
                val pick = Calendar.getInstance()
                pick.clear()
                pick.set(y, m, dom, 12, 0, 0)
                jump(pick.timeInMillis)
            },
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH),
            c.get(Calendar.DAY_OF_MONTH),
        )
        d.datePicker.maxDate = System.currentTimeMillis()
        d.show()
    }

    override fun onResume() {
        super.onResume()
        draw()
    }

    private fun go(delta: Int) {
        val next = DiaryDay.add(day, delta)
        if (next <= System.currentTimeMillis()) {
            day = next
            draw()
        }
    }

    /** Newest first: each span with its moments under it, and moments on their own at their time. */
    private fun draw() {
        val end = DiaryDay.add(day, 1)
        val spans = Store.spans(this, day, end)
        val moments = Store.moments(this, day, end)
        val acts = Store.allActs(this).associateBy { it.id }
        val links = Store.allLinks(this).associateBy { it.id }
        val moods = Store.allMoods(this).associateBy { it.id }
        val tags = Store.allTags(this).associateBy { it.id }
        dayLabel.text = Fmt.day(day)
        ribbon.set(spans, acts.mapValues { it.value.color }, day, end)

        val shown = spans.map { it.entry.id }.toSet()
        val byEntry = moments.filter { it.entryId != null && it.entryId in shown }.groupBy { it.entryId }
        val loose = moments.filter { it.entryId == null || it.entryId !in shown }

        // Spans and loose moments, merged newest first by time.
        val items = spans.map { it.from to (it as Any) } + loose.map { it.ts to (it as Any) }
        list.removeAllViews()
        if (items.isEmpty()) list.addView(note("Nothing logged this day."))
        items.sortedByDescending { it.first }.forEach { (_, item) ->
            when (item) {
                is Span -> {
                    list.addView(spanRow(item, acts, links))
                    byEntry[item.entry.id].orEmpty().reversed().forEach { m ->
                        list.addView(momentRow(m, moods, tags, indent = true))
                    }
                }
                is Moment -> list.addView(momentRow(item, moods, tags, indent = false))
            }
        }
    }

    private fun spanRow(
        s: Span,
        acts: Map<Long, com.jono.nowwidget.core.Act>,
        links: Map<Long, com.jono.nowwidget.core.Link>,
    ): LinearLayout {
        val a = acts[s.entry.activityId]
        val linked = s.entry.links.mapNotNull { links[it] }
        val who = linked.filter { it.kind == LinkKind.PERSON }.joinToString(", ") { it.title }
        val where = linked.filter { it.kind == LinkKind.PLACE }.joinToString(", ") { "at " + it.title }
        val context = s.entry.contextId?.let { links[it]?.title }?.let { "in $it" } ?: ""
        val details = listOf(
            Fmt.time(this, s.from) + "–" + Fmt.time(this, s.to),
            Fmt.dur(s.ms),
            who,
            where,
            context,
        ).filter { it.isNotBlank() }.joinToString(" · ")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener { edit(s.entry) }
            addView(TextView(this@TimelineActivity).apply {
                text = if (a != null) a.emoji + " " + a.name else "Removed"
                textSize = 16f
            })
            addView(TextView(this@TimelineActivity).apply {
                text = details
                textSize = 13f
                alpha = 0.7f
            })
        }
    }

    private fun momentRow(m: Moment, moods: Map<Long, Mood>, tags: Map<Long, FeelingTag>, indent: Boolean): TextView {
        val parts = listOf(
            Fmt.time(this, m.ts),
            moodFace(m.moodId?.let { moods[it] }),
            m.tags.mapNotNull { tags[it]?.name }.joinToString(", "),
        ).filter { it.isNotBlank() }.joinToString(" ")
        val body = listOf(m.title, m.text).filter { it.isNotBlank() }.joinToString(" — ")
        return TextView(this).apply {
            text = if (body.isBlank()) parts else "$parts  $body"
            textSize = 14f
            maxLines = 6
            setPadding(if (indent) dp(20) else 0, dp(4), 0, dp(4))
            setOnClickListener { editMoment(m) }
        }
    }

    /** A time picked on this diary day: hours before 4am belong to its night. */
    private fun pickTime(start: Long, onPicked: (Long) -> Unit) {
        val c = Calendar.getInstance()
        c.timeInMillis = start
        TimePickerDialog(
            this,
            { _, h, m -> onPicked(minOf(DiaryDay.at(day, h, m), System.currentTimeMillis())) },
            c.get(Calendar.HOUR_OF_DAY),
            c.get(Calendar.MINUTE),
            DateFormat.is24HourFormat(this),
        ).show()
    }

    private fun edit(entry: Entry?) {
        val isToday = day == Fmt.today()
        val acts = Store.allActs(this).filter { !it.archived || it.id == entry?.activityId }
        val allLinks = Store.allLinks(this)
        // Links you made, pinned or have used are listed; any other one (a vault note) is found by typing.
        fun pickable(kind: String) = allLinks.filter {
            it.kind == kind && ((!it.archived && it.chip) || (entry != null && it.id in entry.links))
        }
        val people = pickable(LinkKind.PERSON)
        val places = pickable(LinkKind.PLACE)
        val contexts = allLinks.filter {
            it.kind == LinkKind.CONTEXT && ((!it.archived && it.chip) || it.id == entry?.contextId)
        }
        if (acts.isEmpty()) return

        var start = entry?.start ?: if (isToday) System.currentTimeMillis() - 30 * 60_000L else day + 8 * 3_600_000L

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@TimelineActivity,
                android.R.layout.simple_spinner_dropdown_item,
                acts.map { it.emoji + " " + it.name },
            )
            val at = acts.indexOfFirst { it.id == entry?.activityId }
            if (at >= 0) setSelection(at)
        }
        box.addView(spinner)

        val timeButton = Button(this).apply { text = "Started " + Fmt.time(this@TimelineActivity, start) }
        timeButton.setOnClickListener {
            pickTime(start) { t ->
                start = t
                timeButton.text = "Started " + Fmt.time(this, start)
            }
        }
        box.addView(timeButton)

        val checks = ArrayList<Pair<Long, CheckBox>>()
        fun addChecks(title: String, items: List<com.jono.nowwidget.core.Link>, kind: String) {
            box.addView(label(title))
            val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(holder)
            fun addOne(l: com.jono.nowwidget.core.Link, on: Boolean) {
                val cb = CheckBox(this).apply {
                    text = l.badge + " " + l.title
                    isChecked = on
                }
                holder.addView(cb)
                checks.add(l.id to cb)
            }
            items.forEach { addOne(it, entry != null && it.id in entry.links) }
            box.addView(Button(this).apply {
                text = "Find a " + kindName(kind) + "…"
                setOnClickListener {
                    this@TimelineActivity.pickLink("Find or add a " + kindName(kind), setOf(kind), kind) { id ->
                        val have = checks.firstOrNull { it.first == id }
                        if (have != null) {
                            have.second.isChecked = true
                        } else {
                            Store.allLinks(this@TimelineActivity).firstOrNull { it.id == id }?.let { addOne(it, true) }
                        }
                    }
                }
            })
        }
        addChecks("With", people, LinkKind.PERSON)
        addChecks("Where", places, LinkKind.PLACE)

        val contextItems = ArrayList(contexts)
        val contextSpinner = Spinner(this)
        fun showContexts(selectId: Long?) {
            contextSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("None") + contextItems.map { it.label },
            )
            contextSpinner.setSelection(contextItems.indexOfFirst { it.id == selectId } + 1)
        }
        box.addView(label("Context"))
        showContexts(entry?.contextId)
        box.addView(contextSpinner)
        box.addView(Button(this).apply {
            text = "Find a context…"
            setOnClickListener {
                this@TimelineActivity.pickLink("Find or add a context", pickKinds(LinkKind.CONTEXT), LinkKind.CONTEXT) { id ->
                    if (contextItems.none { it.id == id }) {
                        Store.allLinks(this@TimelineActivity).firstOrNull { it.id == id }?.let { contextItems.add(it) }
                    }
                    showContexts(id)
                }
            }
        })

        val b = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val act = acts[spinner.selectedItemPosition].id
                val chosen = checks.filter { it.second.isChecked }.map { it.first }.toSet()
                val ctxId = contextItems.getOrNull(contextSpinner.selectedItemPosition - 1)?.id
                if (entry == null) {
                    Store.addEntry(this, act, start, chosen, ctxId)
                } else {
                    Store.updateEntry(this, entry.id, act, start, chosen, ctxId)
                }
                changed()
            }
        if (entry != null) {
            b.setNeutralButton("Delete") { _, _ ->
                Store.deleteEntry(this, entry.id)
                changed()
            }
        }
        b.show()
    }

    private fun editMoment(m: Moment) {
        var ts = m.ts
        val timeButton = Button(this).apply { text = "At " + Fmt.time(this@TimelineActivity, ts) }
        timeButton.setOnClickListener {
            pickTime(ts) { t ->
                ts = t
                timeButton.text = "At " + Fmt.time(this, ts)
            }
        }
        val form = CheckInForm(this, m)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(timeButton)
            addView(form.view)
        }
        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                if (form.hasContent() || m.title.isNotBlank()) {
                    Store.updateMoment(this, m, ts, form.text.text.toString().trim(), form.moodId, form.tags)
                } else {
                    Store.deleteMoment(this, m.id)
                }
                changed()
            }
            .setNeutralButton("Delete") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("Delete this?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        Store.deleteMoment(this, m.id)
                        changed()
                    }
                    .show()
            }
            .show()
    }

    private fun changed() {
        WidgetRenderer.refresh(this)
        draw()
    }

    companion object {
        /** Open on the diary day containing this time (ms). */
        const val EXTRA_DAY = "day"
    }
}
