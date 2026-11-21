package com.jono.nowwidget

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jono.nowwidget.core.CheckInNav
import com.jono.nowwidget.core.DiaryDay
import com.jono.nowwidget.core.LinkKind
import com.jono.nowwidget.core.Moment

/**
 * The 📝 button: a check-in box over the home screen with the keyboard already up. A mood face,
 * feeling tags, where and what context, and a note (type or tap the keyboard's mic). Any one of
 * them alone is fine. Works while nothing is on, too: then it's a moment of its own.
 *
 * It opens on today's latest check-in to edit (Save updates it); Clear or › past the newest starts
 * a new one, and ‹ › step through today's. Unsaved edits are saved before stepping or clearing.
 */
class NoteActivity : Activity() {
    private lateinit var form: CheckInForm
    private lateinit var where: LinkRow
    private lateinit var context: LinkRow
    private lateinit var dialog: AlertDialog
    private lateinit var header: TextView
    private lateinit var earlier: TextView
    private lateinit var prevButton: TextView
    private lateinit var nextButton: TextView
    private lateinit var deleteButton: TextView

    /** Today's moments, oldest first, and the one in the form (null = a new check-in). */
    private var moments: List<Moment> = emptyList()
    private var loaded: Moment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The check-in flow's 📝: the flow ends on the widget, and the box starts a new check-in
        // for what you just switched to.
        val fromFlow = intent?.getBooleanExtra(EXTRA_FROM_FLOW, false) == true
        if (fromFlow && savedInstanceState == null) {
            CheckIn.end(this)
            WidgetRenderer.refresh(this)
        }
        val cur = Store.current(this)
        val act = cur?.let { c -> Store.allActs(this).firstOrNull { it.id == c.activityId } }
        val links = Store.allLinks(this)
        val curPlace = cur?.links?.firstOrNull { id -> links.any { it.id == id && it.kind == LinkKind.PLACE } }

        val s = savedInstanceState
        val saved = s != null && s.containsKey(KEY_LOADED)
        where = if (saved && s!!.getBoolean(KEY_WHERE_TOUCHED, false)) {
            LinkRow(this, LinkKind.PLACE, "Where", s.getLong(KEY_WHERE, -1L).takeIf { it >= 0 }, true)
        } else LinkRow(this, LinkKind.PLACE, "Where", curPlace)
        context = if (saved && s!!.getBoolean(KEY_CONTEXT_TOUCHED, false)) {
            LinkRow(this, LinkKind.CONTEXT, "Context", s.getLong(KEY_CONTEXT, -1L).takeIf { it >= 0 }, true)
        } else LinkRow(this, LinkKind.CONTEXT, "Context", cur?.contextId)
        form = CheckInForm(this, null, listOf(where.view, context.view), pinFaces = true)

        reload()
        loaded = if (saved) {
            val id = s!!.getLong(KEY_LOADED, -1L)
            moments.firstOrNull { it.id == id }
        } else if (fromFlow) {
            null
        } else {
            moments.getOrNull(CheckInNav.openAt(moments.size))
        }
        form.load(loaded)
        if (saved) {
            // What was typed or picked but not saved yet.
            form.set(
                s!!.getLong(KEY_MOOD, -1L).takeIf { it >= 0 },
                s.getLongArray(KEY_TAGS)?.toList() ?: emptyList(),
                s.getString(KEY_TEXT) ?: "",
            )
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        // What's already noted on this activity, newest last.
        earlier = note("")
        col.addView(earlier)
        col.addView(form.view)

        header = TextView(this).apply {
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setSingleLine(true)
        }
        prevButton = chip("‹", false) { step(-1) }
        nextButton = chip("›", false) { step(1) }
        deleteButton = chip("Delete", false) { confirmDelete() }
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(prevButton)
            addView(header, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(nextButton)
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(chip("Clear", false) { clear() })
            addView(deleteButton)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(navRow)
            addView(actionRow)
        }

        // The header and faces stay above the scrolling part, so the keyboard doesn't push them out of view.
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top)
            addView(form.faceView.apply { setPadding(dp(20), dp(8), dp(20), 0) })
            addView(ScrollView(this@NoteActivity).apply { addView(col) })
        }
        updateUi()

        val b = AlertDialog.Builder(this)
            .setTitle("📝 " + (act?.let { it.emoji + " " + it.name } ?: "Check in"))
            .setView(outer)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { finish() }

        dialog = b.create()
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.show()
        // Save may need to ask first ("Delete this check-in?"), so it can't close the box by itself.
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { save() }
        form.text.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_LOADED, loaded?.id ?: -1L)
        outState.putLong(KEY_MOOD, form.moodId ?: -1L)
        outState.putLongArray(KEY_TAGS, form.tags.toLongArray())
        outState.putString(KEY_TEXT, form.text.text.toString())
        outState.putLong(KEY_WHERE, where.selected ?: -1L)
        outState.putBoolean(KEY_WHERE_TOUCHED, where.touched)
        outState.putLong(KEY_CONTEXT, context.selected ?: -1L)
        outState.putBoolean(KEY_CONTEXT_TOUCHED, context.touched)
    }

    override fun onDestroy() {
        // A recreated box (rotation) must not finish the new one when the old one goes away.
        if (::dialog.isInitialized) {
            dialog.setOnDismissListener(null)
            dialog.dismiss()
        }
        super.onDestroy()
    }

    private fun reload() {
        val day = DiaryDay.start(System.currentTimeMillis())
        moments = CheckInNav.order(Store.moments(this, day, DiaryDay.add(day, 1)))
    }

    private fun position(): Int = loaded?.let { l -> moments.indexOfFirst { it.id == l.id }.takeIf { it >= 0 } } ?: moments.size

    private fun updateUi() {
        header.text = CheckInNav.header(loaded, loaded?.let { Fmt.time(this, it.ts) } ?: "")
        val pos = position()
        enable(prevButton, CheckInNav.prev(pos, moments.size) != null)
        enable(nextButton, CheckInNav.next(pos, moments.size) != null)
        deleteButton.visibility = if (loaded != null) View.VISIBLE else View.GONE
        val cur = Store.current(this)
        val text = if (cur == null) "" else {
            val moods = Store.allMoods(this).associateBy { it.id }
            val tags = Store.allTags(this).associateBy { it.id }
            Store.momentsOf(this, cur.id).takeLast(3).joinToString("\n") { m ->
                listOf(
                    Fmt.time(this, m.ts),
                    moodFace(m.moodId?.let { moods[it] }),
                    m.tags.mapNotNull { tags[it]?.name }.joinToString(", "),
                    m.text.replace('\n', ' '),
                ).filter { it.isNotBlank() }.joinToString(" ")
            }
        }
        earlier.text = text
        earlier.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun enable(v: TextView, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.35f
    }

    /** Saves edits that haven't been saved yet (a new check-in goes on the current entry, now). */
    private fun commit() {
        val l = loaded
        val text = form.text.text.toString().trim()
        when (CheckInNav.saveAction(l, form.moodId, form.tags, text, stepping = true)) {
            CheckInNav.Save.CREATE -> {
                Store.addMoment(this, System.currentTimeMillis(), "", text, form.moodId, form.tags, Store.current(this)?.id)
                WidgetRenderer.refresh(this)
            }
            CheckInNav.Save.UPDATE -> {
                Store.updateMoment(this, l!!, l.ts, text, form.moodId, form.tags)
                WidgetRenderer.refresh(this)
            }
            else -> {}
        }
    }

    /** Shows [target] (null = a new check-in), found again by id since saving may have changed the list. */
    private fun show(target: Moment?) {
        reload()
        loaded = target?.let { t -> moments.firstOrNull { it.id == t.id } }
        form.load(loaded)
        updateUi()
        form.text.requestFocus()
    }

    private fun step(delta: Int) {
        val to = (if (delta < 0) CheckInNav.prev(position(), moments.size) else CheckInNav.next(position(), moments.size)) ?: return
        val target = moments.getOrNull(to) // null past the newest: the new check-in
        commit()
        show(target)
    }

    private fun clear() {
        commit()
        show(null)
    }

    private fun confirmDelete() {
        val l = loaded ?: return
        AlertDialog.Builder(this)
            .setMessage("Delete this check-in?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                Store.deleteMoment(this, l.id)
                WidgetRenderer.refresh(this)
                reload()
                show(moments.getOrNull(CheckInNav.openAt(moments.size)))
            }
            .show()
    }

    private fun save() {
        // Read "now" again: something may have changed while the box was open.
        val cur = Store.current(this)
        var changed = false
        if (where.touched || context.touched) {
            if (cur == null) {
                Toast.makeText(this, "Nothing's on, so where and context weren't saved", Toast.LENGTH_SHORT).show()
            } else {
                if (where.touched) Store.setPlace(this, cur.id, where.selected)
                if (context.touched) Store.setContext(this, cur.id, context.selected)
                changed = true
            }
        }
        val l = loaded
        val text = form.text.text.toString().trim()
        when (CheckInNav.saveAction(l, form.moodId, form.tags, text, stepping = false)) {
            CheckInNav.Save.CREATE -> {
                Store.addMoment(this, System.currentTimeMillis(), "", text, form.moodId, form.tags, cur?.id)
                changed = true
            }
            CheckInNav.Save.UPDATE -> {
                Store.updateMoment(this, l!!, l.ts, text, form.moodId, form.tags)
                changed = true
            }
            CheckInNav.Save.ASK_DELETE -> {
                if (changed) WidgetRenderer.refresh(this)
                AlertDialog.Builder(this)
                    .setMessage("Delete this check-in?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        Store.deleteMoment(this, l!!.id)
                        WidgetRenderer.refresh(this)
                        dialog.dismiss()
                    }
                    .show()
                return
            }
            CheckInNav.Save.NONE -> {}
        }
        if (changed) WidgetRenderer.refresh(this)
        dialog.dismiss()
    }

    companion object {
        const val EXTRA_FROM_FLOW = "from_flow"
        private const val KEY_LOADED = "loaded"
        private const val KEY_MOOD = "mood"
        private const val KEY_TAGS = "tags"
        private const val KEY_TEXT = "text"
        private const val KEY_WHERE = "where"
        private const val KEY_WHERE_TOUCHED = "whereTouched"
        private const val KEY_CONTEXT = "context"
        private const val KEY_CONTEXT_TOUCHED = "contextTouched"
    }
}
