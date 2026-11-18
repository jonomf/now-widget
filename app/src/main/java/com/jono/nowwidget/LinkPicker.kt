package com.jono.nowwidget

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jono.nowwidget.core.LinkIndex
import com.jono.nowwidget.core.LinkKind
import com.jono.nowwidget.core.Suggestion

/** What type-ahead rows say about a suggestion: the alias it matched, and its kind. */
fun suggestionText(s: Suggestion): String {
    val alias = s.alias
    return if (alias == null) s.link.title else "${s.link.title}  (also: $alias)"
}

/**
 * Type-ahead over the link table (vault titles and aliases), most used first. Fills [rows] whenever
 * the text in [input] changes. Choosing an alias gives the note's own title. [onChosen] gets the link id.
 */
class LinkSuggester(
    private val a: Activity,
    private val input: EditText,
    private val rows: LinearLayout,
    private val kinds: Set<String>,
    private val limit: Int,
    private val onChosen: (Long) -> Unit,
) {
    private val index: LinkIndex = Store.linkIndex(a)

    init {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                redraw()
            }
        })
        redraw()
    }

    /** The link the typed text means exactly (a title or an alias), or null. */
    fun exact(): Long? = index.resolve(input.text.toString(), kinds)?.id

    fun redraw() {
        rows.removeAllViews()
        index.suggest(input.text.toString(), kinds, limit).forEach { s ->
            rows.addView(TextView(a).apply {
                text = suggestionText(s)
                textSize = 16f
                setPadding(a.dp(4), a.dp(10), a.dp(4), a.dp(10))
                setOnClickListener { onChosen(s.link.id) }
            })
        }
    }
}

/**
 * A small dialog to pick a person, place or context by typing: matches from the vault come up as you
 * type, an alias means its note's own title, and a name with no note is still fine (it becomes a link
 * with no note yet; nothing is ever created in the vault). [onPicked] gets the link's id.
 */
fun Activity.pickLink(title: String, kinds: Set<String>, makeKind: String, onPicked: (Long) -> Unit) {
    val input = EditText(this).apply {
        hint = "Type a name"
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
    val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(input)
        addView(ScrollView(this@pickLink).apply { addView(rows) })
    }
    var dialog: AlertDialog? = null
    fun done(id: Long) {
        Store.reviveLink(this, id)
        dialog?.dismiss()
        onPicked(id)
    }
    val suggester = LinkSuggester(this, input, rows, kinds, 6) { done(it) }

    fun addTyped() {
        val name = input.text.toString().trim()
        if (name.isEmpty()) return
        done(suggester.exact() ?: Store.findOrAddLink(this, makeKind, name))
    }

    val d = AlertDialog.Builder(this)
        .setTitle(title)
        .setView(box)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Add") { _, _ -> addTyped() }
        .create()
    dialog = d
    d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    d.show()
    input.requestFocus()
}

/** The kinds the picker offers for a row: contexts may be any vault note, the rest only their own kind. */
fun pickKinds(kind: String): Set<String> =
    if (kind == LinkKind.CONTEXT) setOf(LinkKind.CONTEXT, LinkKind.OTHER) else setOf(kind)
