package com.jono.nowwidget

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.jono.nowwidget.core.LinkKind

/**
 * The 📝 button: a check-in box over the home screen with the keyboard already up. A mood face,
 * feeling tags, where and what context, and a note (type or tap the keyboard's mic). Any one of
 * them alone is fine. Works while nothing is on, too: then it's a moment of its own.
 */
class NoteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cur = Store.current(this)
        val act = cur?.let { c -> Store.allActs(this).firstOrNull { it.id == c.activityId } }
        val links = Store.allLinks(this)
        val curPlace = cur?.links?.firstOrNull { id -> links.any { it.id == id && it.kind == LinkKind.PLACE } }

        val where = LinkRow(this, LinkKind.PLACE, "Where", curPlace)
        val context = LinkRow(this, LinkKind.CONTEXT, "Context", cur?.contextId)
        val form = CheckInForm(this, null, listOf(where.view, context.view))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        // What's already noted on this activity, newest last.
        if (cur != null) {
            val earlier = Store.momentsOf(this, cur.id).takeLast(3)
            if (earlier.isNotEmpty()) {
                val moods = Store.allMoods(this).associateBy { it.id }
                val tags = Store.allTags(this).associateBy { it.id }
                col.addView(note(earlier.joinToString("\n") { m ->
                    listOf(
                        Fmt.time(this, m.ts),
                        moodFace(m.moodId?.let { moods[it] }),
                        m.tags.mapNotNull { tags[it]?.name }.joinToString(", "),
                        m.text.replace('\n', ' '),
                    ).filter { it.isNotBlank() }.joinToString(" ")
                }))
            }
        }
        col.addView(form.view)

        val b = AlertDialog.Builder(this)
            .setTitle("📝 " + (act?.let { it.emoji + " " + it.name } ?: "Check in"))
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Save") { _, _ -> save(form, where, context) }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { finish() }

        val d = b.create()
        d.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        d.show()
        form.text.requestFocus()
    }

    private fun save(form: CheckInForm, where: LinkRow, context: LinkRow) {
        // Read "now" again: something may have changed while the box was open.
        val cur = Store.current(this)
        var changed = false
        if (form.hasContent()) {
            Store.addMoment(
                this,
                ts = System.currentTimeMillis(),
                title = "",
                text = form.text.text.toString().trim(),
                moodId = form.moodId,
                tags = form.tags,
                entryId = cur?.id,
            )
            changed = true
        }
        if (where.touched || context.touched) {
            if (cur == null) {
                Toast.makeText(this, "Nothing's on, so where and context weren't saved", Toast.LENGTH_SHORT).show()
            } else {
                if (where.touched) Store.setPlace(this, cur.id, where.selected)
                if (context.touched) Store.setContext(this, cur.id, context.selected)
                changed = true
            }
        }
        if (changed) WidgetRenderer.refresh(this)
    }
}
