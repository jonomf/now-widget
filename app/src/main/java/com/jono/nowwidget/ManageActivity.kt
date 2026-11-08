package com.jono.nowwidget

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Add, edit, reorder and remove activities or people. */
class ManageActivity : Activity() {
    private lateinit var list: LinearLayout
    private val isActivities: Boolean get() = intent.getStringExtra(EXTRA_MODE) != MODE_PEOPLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = if (isActivities) "Activities" else "People"
            textSize = 24f
        })
        col.addView(
            note(
                if (isActivities) "Tap one to edit it. Your order sets where its button goes."
                else "On larger widgets and the expanded notification, tap someone to mark that you're with them."
            )
        )
        col.addView(Button(this).apply {
            text = if (isActivities) "Add activity" else "Add person"
            setOnClickListener { if (isActivities) editAct(null) else editPerson(null) }
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        setContentView(ScrollView(this).apply { addView(col) })
        draw()
    }

    private fun draw() {
        list.removeAllViews()
        if (isActivities) {
            val items = Store.allActs(this).filter { !it.archived }
            val ids = items.map { it.id }
            items.forEachIndexed { i, a ->
                val label = a.emoji + " " + a.name + if (a.show) "" else " (no button)"
                list.addView(row(label, a.group, a.color, i, ids, "activity") { editAct(a) })
            }
        } else {
            val items = Store.allPeople(this).filter { !it.archived }
            val ids = items.map { it.id }
            items.forEachIndexed { i, p ->
                list.addView(row(p.badge + " " + p.name, "", null, i, ids, "person") { editPerson(p) })
            }
        }
    }

    private fun row(
        label: String,
        sub: String,
        color: Int?,
        index: Int,
        ids: List<Long>,
        table: String,
        onTap: () -> Unit,
    ): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (color != null) {
            r.addView(View(this).apply { background = dot(color, dp(12)) }, LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                marginEnd = dp(10)
            })
        }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { onTap() }
            addView(TextView(this@ManageActivity).apply {
                this.text = label
                textSize = 16f
            })
            if (sub.isNotBlank()) {
                addView(TextView(this@ManageActivity).apply {
                    this.text = sub
                    textSize = 12f
                    alpha = 0.7f
                })
            }
        }
        r.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(moveButton("▲", index > 0) { swap(ids, index, index - 1, table) })
        r.addView(moveButton("▼", index < ids.size - 1) { swap(ids, index, index + 1, table) })
        return r
    }

    private fun moveButton(label: String, enabled: Boolean, onTap: () -> Unit) = Button(this).apply {
        text = label
        minWidth = dp(44)
        minimumWidth = dp(44)
        isEnabled = enabled
        setOnClickListener { onTap() }
    }

    private fun swap(ids: List<Long>, a: Int, b: Int, table: String) {
        val next = ids.toMutableList()
        next[a] = ids[b]
        next[b] = ids[a]
        Store.setOrder(this, table, next)
        changed()
    }

    private fun changed() {
        WidgetRenderer.refresh(this)
        draw()
    }

    private fun dot(color: Int, size: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setSize(size, size)
    }

    private fun field(hintText: String, value: String) = EditText(this).apply {
        hint = hintText
        setText(value)
        setSingleLine(true)
    }

    private fun editAct(a: Act?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = field("Name", a?.name ?: "")
        val emoji = field("Emoji", a?.emoji ?: "")
        val group = field("Group (like Work or Play)", a?.group ?: "")
        val show = CheckBox(this).apply {
            text = "Show a button for this"
            isChecked = a?.show ?: true
        }
        box.addView(name)
        box.addView(emoji)
        box.addView(group)
        box.addView(show)

        var color = a?.color ?: PALETTE[Store.allActs(this).size % PALETTE.size]
        val swatches = ArrayList<Pair<View, Int>>()
        fun mark() = swatches.forEach { (v, c) -> v.alpha = if (c == color) 1f else 0.35f }
        val grid = GridLayout(this).apply {
            columnCount = 6
            setPadding(0, dp(8), 0, dp(8))
        }
        PALETTE.forEach { c ->
            val v = View(this).apply {
                background = dot(c, dp(32))
                setOnClickListener {
                    color = c
                    mark()
                }
            }
            grid.addView(v, GridLayout.LayoutParams().apply {
                width = dp(32)
                height = dp(32)
                setMargins(dp(5), dp(5), dp(5), dp(5))
            })
            swatches.add(v to c)
        }
        mark()
        box.addView(grid)

        val b = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isNotEmpty()) {
                    val e = Fmt.firstGrapheme(emoji.text.toString()).ifBlank { n.take(1).uppercase() }
                    Store.saveAct(this, a?.id, n, e, color, group.text.toString().trim(), show.isChecked)
                    changed()
                }
            }
        if (a != null) {
            b.setNeutralButton("Remove") { _, _ ->
                Store.archive(this, "activity", a.id)
                changed()
            }
        }
        b.show()
    }

    private fun editPerson(p: Person?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = field("Name", p?.name ?: "")
        val emoji = field("Emoji (optional)", p?.emoji ?: "")
        box.addView(name)
        box.addView(emoji)

        val b = AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isNotEmpty()) {
                    Store.savePerson(this, p?.id, n, Fmt.firstGrapheme(emoji.text.toString()))
                    changed()
                }
            }
        if (p != null) {
            b.setNeutralButton("Remove") { _, _ ->
                Store.archive(this, "person", p.id)
                changed()
            }
        }
        b.show()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_ACTIVITIES = "activities"
        const val MODE_PEOPLE = "people"
    }
}
