package com.jono.nowwidget

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.jono.nowwidget.core.Act
import com.jono.nowwidget.core.FeelingTag
import com.jono.nowwidget.core.Link
import com.jono.nowwidget.core.LinkKind
import com.jono.nowwidget.core.Mood
import com.jono.nowwidget.core.PALETTE

/** Add, edit, reorder and remove activities, links (people, places, contexts), moods or feelings. */
class ManageActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var kindTabs: LinearLayout
    private lateinit var addButton: Button
    private lateinit var hint: TextView
    private val mode: String get() = intent.getStringExtra(EXTRA_MODE) ?: MODE_ACTIVITIES
    private var kind = LinkKind.PERSON

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = when (mode) {
                MODE_LINKS -> "People, places and contexts"
                MODE_MOODS -> "Moods"
                MODE_TAGS -> "Feelings"
                else -> "Activities"
            }
            textSize = 24f
        })
        kindTabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (mode == MODE_LINKS) {
            listOf(LinkKind.PERSON to "People", LinkKind.PLACE to "Places", LinkKind.CONTEXT to "Contexts").forEach { (k, label) ->
                kindTabs.addView(Button(this).apply {
                    text = label
                    setOnClickListener {
                        kind = k
                        draw()
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            col.addView(kindTabs)
        }
        hint = note("")
        col.addView(hint)
        addButton = Button(this).apply {
            setOnClickListener {
                when (mode) {
                    MODE_LINKS -> editLink(null)
                    MODE_MOODS -> editMood(null)
                    MODE_TAGS -> editTag(null)
                    else -> editAct(null)
                }
            }
        }
        col.addView(addButton)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        setContentView(ScrollView(this).apply { addView(col) })
        draw()
    }

    private fun draw() {
        list.removeAllViews()
        when (mode) {
            MODE_LINKS -> {
                val kinds = listOf(LinkKind.PERSON, LinkKind.PLACE, LinkKind.CONTEXT)
                for (i in 0 until kindTabs.childCount) kindTabs.getChildAt(i).alpha = if (kinds[i] == kind) 1f else 0.55f
                hint.text = when (kind) {
                    LinkKind.PLACE -> "Where you are. Pick one in the 📝 box, the timeline, or on the biggest widget and the expanded panel."
                    LinkKind.CONTEXT -> "What a stretch of time is part of, like a trip or an event. It carries on to each new activity until you clear it. Set it in the 📝 box, the expand panel or the timeline."
                    else -> "On larger widgets and the expanded notification, tap someone to mark that you're with them."
                }
                addButton.text = "Add " + kindName(kind)
                val items = Store.allLinks(this).filter { it.kind == kind && !it.archived }
                val ids = items.map { it.id }
                val vault = ObsidianExport.configured(this)
                items.forEachIndexed { i, l ->
                    val sub = listOfNotNull(
                        if (vault) (if (l.inVault) "note in your vault" else "no note yet") else null,
                        if (l.fromVault && !l.chip) "not on the widget" else null,
                    ).joinToString(" · ")
                    list.addView(row(l.badge + " " + l.title, sub, null, i, ids, "link") { editLink(l) })
                }
            }
            MODE_MOODS -> {
                hint.text = "The faces in the 📝 box, in this order. Level 1 is the best, 5 the worst; several moods can share a level."
                addButton.text = "Add mood"
                val items = Store.allMoods(this).filter { !it.archived }
                val ids = items.map { it.id }
                items.forEachIndexed { i, m ->
                    list.addView(row(moodFace(m) + " " + m.name, "Level ${m.level}", null, i, ids, "mood") { editMood(m) })
                }
            }
            MODE_TAGS -> {
                hint.text = "Words for how you feel. The 📝 box shows the ones you use most first; \"+\" there adds one too."
                addButton.text = "Add feeling"
                val uses = Store.tagUses(this)
                val items = Store.allTags(this).filter { !it.archived }
                val ids = items.map { it.id }
                items.forEachIndexed { i, t ->
                    val n = uses[t.id] ?: 0
                    list.addView(row(t.label, if (n == 0) "" else "Used $n times", null, i, ids, "feeling_tag") { editTag(t) })
                }
            }
            else -> {
                hint.text = "Tap one to edit it. Your order sets where its button goes."
                addButton.text = "Add activity"
                val items = Store.allActs(this).filter { !it.archived }
                val ids = items.map { it.id }
                items.forEachIndexed { i, a ->
                    val label = a.emoji + " " + a.name + if (a.show) "" else " (no button)"
                    list.addView(row(label, a.group, a.color, i, ids, "activity") { editAct(a) })
                }
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

    private fun dialogBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
    }

    private fun editAct(a: Act?) {
        val box = dialogBox()
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

    private fun editLink(l: Link?) {
        val box = dialogBox()
        val name = field("Name (as in your notes)", l?.title ?: "")
        val emoji = field("Emoji (optional)", l?.emoji ?: "")
        box.addView(name)
        box.addView(emoji)
        val kinds = LinkKind.ALL
        val kindSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ManageActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Person", "Place", "Context"),
            )
            setSelection(kinds.indexOf(l?.kind ?: kind).coerceAtLeast(0))
        }
        box.addView(kindSpinner)
        if (l == null) {
            // A new one: matches from the vault come up as you type; choosing one fills in its title and kind.
            val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(rows)
            LinkSuggester(this, name, rows, kinds.toSet(), 5) { id ->
                Store.allLinks(this).firstOrNull { it.id == id }?.let { found ->
                    name.setText(found.title)
                    name.setSelection(found.title.length)
                    kindSpinner.setSelection(kinds.indexOf(found.kind).coerceAtLeast(0))
                }
            }
        }
        // A link the vault made stays off the widget until you use it; this keeps it there.
        val pinned = CheckBox(this).apply {
            text = "Always show on the widget and in the 📝 box"
            isChecked = l?.pinned ?: true
        }
        if (l != null && l.fromVault) box.addView(pinned)

        val b = AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isNotEmpty()) {
                    val k = kinds.getOrElse(kindSpinner.selectedItemPosition) { kind }
                    val e = Fmt.firstGrapheme(emoji.text.toString())
                    if (l == null) {
                        Store.addLink(this, n, k, e)
                    } else {
                        Store.saveLink(this, l.id, n, k, e, if (l.fromVault) pinned.isChecked else null)
                    }
                    changed()
                }
            }
        if (l != null) {
            b.setNeutralButton("Remove") { _, _ ->
                Store.archive(this, "link", l.id)
                changed()
            }
        }
        b.show()
    }

    private fun editMood(m: Mood?) {
        val box = dialogBox()
        val name = field("Name", m?.name ?: "")
        val emoji = field("Face (emoji)", m?.emoji ?: "")
        box.addView(name)
        box.addView(emoji)
        box.addView(label("Level"))
        val levels = listOf("1 · best", "2", "3", "4", "5 · worst")
        val levelSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@ManageActivity, android.R.layout.simple_spinner_dropdown_item, levels)
            setSelection(((m?.level ?: 3) - 1).coerceIn(0, 4))
        }
        box.addView(levelSpinner)

        val b = AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isNotEmpty()) {
                    Store.saveMood(this, m?.id, n, Fmt.firstGrapheme(emoji.text.toString()), levelSpinner.selectedItemPosition + 1)
                    changed()
                }
            }
        if (m != null) {
            b.setNeutralButton("Remove") { _, _ ->
                Store.archive(this, "mood", m.id)
                changed()
            }
        }
        b.show()
    }

    private fun editTag(t: FeelingTag?) {
        val box = dialogBox()
        val name = field("Name", t?.name ?: "")
        val emoji = field("Emoji (optional)", t?.emoji ?: "")
        box.addView(name)
        box.addView(emoji)

        val b = AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isNotEmpty()) {
                    Store.saveTag(this, t?.id, n, Fmt.firstGrapheme(emoji.text.toString()))
                    changed()
                }
            }
        if (t != null) {
            b.setNeutralButton("Remove") { _, _ ->
                Store.archive(this, "feeling_tag", t.id)
                changed()
            }
        }
        b.show()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_ACTIVITIES = "activities"
        const val MODE_LINKS = "links"
        const val MODE_MOODS = "moods"
        const val MODE_TAGS = "tags"
    }
}
