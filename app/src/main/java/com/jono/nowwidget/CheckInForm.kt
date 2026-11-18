package com.jono.nowwidget

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.jono.nowwidget.core.Moment

/**
 * How you feel and what's on your mind: a row of mood faces (tap again to clear), feeling tags
 * (most used first, "+" adds one), any [extras] (where, context), then the note.
 * Used by the 📝 box and by the timeline's moment editor.
 *
 * With [pinFaces], the faces go in [faceView] instead of [view], so the caller can keep them
 * above the scrolling part (where the keyboard can't push them away).
 */
class CheckInForm(
    private val a: Activity,
    initial: Moment?,
    extras: List<View> = emptyList(),
    pinFaces: Boolean = false,
) {
    val view: LinearLayout = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    val faceView: LinearLayout = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    var moodId: Long? = initial?.moodId
        private set
    val tags: MutableSet<Long> = initial?.tags?.toMutableSet() ?: LinkedHashSet()
    val text: EditText

    private val faces = ArrayList<Pair<Long, View>>()
    private val moodName = TextView(a).apply {
        textSize = 13f
        alpha = 0.7f
    }
    private val tagWrap = WrapLayout(a)

    init {
        val moods = Store.allMoods(a).filter { !it.archived || it.id == moodId }.sortedWith(compareBy({ it.level }, { it.pos }))
        // When two moods share a level (an imported custom mood, say), their faces can look the
        // same, so every face gets its name under it.
        val named = moods.groupBy { it.level }.any { it.value.size > 1 }
        val faceRow = WrapLayout(a)
        moods.forEach { m ->
            val cell = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(4))
                contentDescription = m.name
                addView(TextView(a).apply {
                    text = moodFace(m)
                    textSize = 28f
                    gravity = Gravity.CENTER
                })
                if (named) {
                    addView(TextView(a).apply {
                        text = m.name
                        textSize = 11f
                        gravity = Gravity.CENTER
                        setSingleLine(true)
                    })
                }
                setOnClickListener {
                    moodId = if (moodId == m.id) null else m.id
                    markFaces()
                }
                setOnLongClickListener {
                    Toast.makeText(a, m.name, Toast.LENGTH_SHORT).show()
                    true
                }
            }
            faces.add(m.id to cell)
            faceRow.addView(cell)
        }
        val faceHolder = if (pinFaces) faceView else view
        faceHolder.addView(faceRow)
        faceHolder.addView(moodName)
        markFaces()

        view.addView(a.label("Feeling"))
        view.addView(tagWrap)
        drawTags()

        extras.forEach { view.addView(it) }

        view.addView(a.label("Note"))
        text = EditText(a).apply {
            hint = "Type or tap the mic"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 8
            setText(initial?.text ?: "")
        }
        view.addView(text)
    }

    /** True when there's anything to save as a moment. */
    fun hasContent(): Boolean = moodId != null || tags.isNotEmpty() || text.text.toString().isNotBlank()

    private fun markFaces() {
        faces.forEach { (id, v) ->
            a.setChipOn(v, id == moodId)
            v.alpha = if (moodId == null || id == moodId) 1f else 0.45f
        }
        val m = Store.allMoods(a).firstOrNull { it.id == moodId }
        moodName.text = m?.name ?: "How do you feel?"
    }

    private fun drawTags() {
        tagWrap.removeAllViews()
        val uses = Store.tagUses(a)
        Store.allTags(a)
            .filter { !it.archived || it.id in tags }
            .sortedWith(compareByDescending<com.jono.nowwidget.core.FeelingTag> { uses[it.id] ?: 0 }.thenBy { it.pos })
            .forEach { t ->
                tagWrap.addView(a.chip(t.label, t.id in tags) { v ->
                    if (!tags.remove(t.id)) tags.add(t.id)
                    a.setChipOn(v, t.id in tags)
                })
            }
        tagWrap.addView(a.chip("+", false) {
            askName(a, "New feeling") { name ->
                tags.add(Store.findOrAddTag(a, name))
                drawTags()
            }
        })
    }
}

/**
 * One choice from the links of [kind] (where, or context), shown as chips; tap again to clear.
 * Only links you made, pinned or have used are chips, so a big vault doesn't flood the row; "+"
 * finds any other one by typing (vault titles and aliases) or adds a new name.
 * [touched] says whether you changed it.
 */
class LinkRow(private val a: Activity, private val kind: String, title: String, selected: Long?) {
    val view: LinearLayout = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    var selected: Long? = selected
        private set
    var touched = false
        private set
    private val wrap = WrapLayout(a)

    init {
        view.addView(a.label(title))
        view.addView(wrap)
        draw()
    }

    private fun draw() {
        wrap.removeAllViews()
        Store.allLinks(a).filter { it.id == selected || (it.kind == kind && it.chip && !it.archived) }.forEach { l ->
            wrap.addView(a.chip(l.label, l.id == selected) {
                selected = if (selected == l.id) null else l.id
                touched = true
                draw()
            })
        }
        wrap.addView(a.chip("+", false) {
            a.pickLink("Find or add a " + kindName(kind), pickKinds(kind), kind) { id ->
                selected = id
                touched = true
                draw()
            }
        })
    }
}

fun kindName(kind: String): String = when (kind) {
    "person" -> "person"
    "place" -> "place"
    "context" -> "context"
    else -> "link"
}

/** A one-line prompt for a name. */
fun askName(a: Activity, title: String, onName: (String) -> Unit) {
    val input = EditText(a).apply {
        hint = "Name"
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
    val box = FrameLayout(a).apply {
        setPadding(a.dp(20), a.dp(8), a.dp(20), 0)
        addView(input)
    }
    AlertDialog.Builder(a)
        .setTitle(title)
        .setView(box)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Add") { _, _ ->
            val n = input.text.toString().trim()
            if (n.isNotEmpty()) onName(n)
        }
        .show()
}
