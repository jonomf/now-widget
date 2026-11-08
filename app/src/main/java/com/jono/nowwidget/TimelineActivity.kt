package com.jono.nowwidget

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Calendar

/** One day at a time: what happened, with every entry editable. */
class TimelineActivity : Activity() {
    private lateinit var dayLabel: TextView
    private lateinit var ribbon: RibbonView
    private lateinit var list: LinearLayout
    private var day = Fmt.startOfDay(System.currentTimeMillis())

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
        dayLabel = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
        }
        nav.addView(dayLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(Button(this).apply {
            text = "▶"
            setOnClickListener { go(1) }
        })
        col.addView(nav)

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
    }

    override fun onResume() {
        super.onResume()
        draw()
    }

    private fun go(delta: Int) {
        val next = Fmt.addDays(day, delta)
        if (next <= System.currentTimeMillis()) {
            day = next
            draw()
        }
    }

    private fun draw() {
        val end = Fmt.addDays(day, 1)
        val spans = Store.spans(this, day, end)
        val acts = Store.allActs(this).associateBy { it.id }
        val people = Store.allPeople(this).associateBy { it.id }
        dayLabel.text = Fmt.day(day)
        ribbon.set(spans, acts.mapValues { it.value.color }, day, end)

        list.removeAllViews()
        if (spans.isEmpty()) list.addView(note("Nothing logged this day."))
        spans.reversed().forEach { s ->
            val a = acts[s.entry.activityId]
            val who = s.entry.people.mapNotNull { people[it]?.name }.joinToString(", ")
            val details = listOf(
                Fmt.time(this, s.from) + "–" + Fmt.time(this, s.to),
                Fmt.dur(s.ms),
                who,
                s.entry.note,
            ).filter { it.isNotBlank() }.joinToString(" · ")
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
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
            })
        }
    }

    private fun edit(entry: Entry?) {
        val isToday = day == Fmt.startOfDay(System.currentTimeMillis())
        val acts = Store.allActs(this).filter { !it.archived || it.id == entry?.activityId }
        val people = Store.allPeople(this).filter { !it.archived || (entry != null && it.id in entry.people) }
        if (acts.isEmpty()) return

        var start = entry?.start ?: if (isToday) System.currentTimeMillis() - 30 * 60_000L else day + 12 * 3_600_000L

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
            val c = Calendar.getInstance()
            c.timeInMillis = start
            TimePickerDialog(
                this,
                { _, h, m ->
                    c.set(Calendar.HOUR_OF_DAY, h)
                    c.set(Calendar.MINUTE, m)
                    c.set(Calendar.SECOND, 0)
                    c.set(Calendar.MILLISECOND, 0)
                    start = minOf(c.timeInMillis, System.currentTimeMillis())
                    timeButton.text = "Started " + Fmt.time(this, start)
                },
                c.get(Calendar.HOUR_OF_DAY),
                c.get(Calendar.MINUTE),
                DateFormat.is24HourFormat(this),
            ).show()
        }
        box.addView(timeButton)

        val checks = people.map { p ->
            CheckBox(this).apply {
                text = p.badge + " " + p.name
                isChecked = entry != null && p.id in entry.people
            }.also { box.addView(it) }
        }

        val noteField = EditText(this).apply {
            hint = "Note"
            setText(entry?.note ?: "")
        }
        box.addView(noteField)

        val b = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val act = acts[spinner.selectedItemPosition].id
                val chosen = people.filterIndexed { i, _ -> checks[i].isChecked }.map { it.id }.toSet()
                val text = noteField.text.toString().trim()
                if (entry == null) {
                    Store.addEntry(this, act, start, text, chosen)
                } else {
                    Store.updateEntry(this, entry.id, act, start, text, chosen)
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

    private fun changed() {
        WidgetRenderer.refresh(this)
        draw()
    }
}
