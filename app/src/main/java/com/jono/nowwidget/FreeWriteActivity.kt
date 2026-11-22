package com.jono.nowwidget

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jono.nowwidget.core.FreeWrite

/**
 * The activity buttons' ＋: a quick box over the home screen, keyboard up (its mic works), to say
 * what you're doing in your own words. As you type it suggests your activities and recent
 * free-written titles. An activity is the same as its button; a title, or anything typed then Go,
 * starts a new entry with those words as its title (its category is picked for it, see
 * core/FreeWrite.kt). Either way the where / who / feeling flow follows on the widget.
 */
class FreeWriteActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var rows: LinearLayout
    private lateinit var dialog: AlertDialog
    private var recent: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recent = FreeWrite.recent(Store.titleUses(this))
        input = EditText(this).apply {
            hint = "What are you doing?"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setImeOptions(EditorInfo.IME_ACTION_GO)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) {
                    go(text.toString())
                    true
                } else false
            }
        }
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = suggest()
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
            addView(ScrollView(this@FreeWriteActivity).apply { addView(rows) })
        }
        suggest()
        dialog = AlertDialog.Builder(this)
            .setTitle("＋ What are you doing?")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Go", null)
            .setOnDismissListener { finish() }
            .create()
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { go(input.text.toString()) }
        input.requestFocus()
    }

    override fun onDestroy() {
        if (::dialog.isInitialized) {
            dialog.setOnDismissListener(null)
            dialog.dismiss()
        }
        super.onDestroy()
    }

    private fun suggest() {
        rows.removeAllViews()
        FreeWrite.suggest(input.text.toString(), Store.allActs(this), recent).forEach { s ->
            val (label, onTap) = when (s) {
                is FreeWrite.Suggestion.Activity -> (s.act.emoji + " " + s.act.name) to { tapActivity(s.act.id) }
                is FreeWrite.Suggestion.Title -> ("✏️ " + s.title) to { go(s.title) }
            }
            rows.addView(TextView(this).apply {
                text = label
                textSize = 16f
                setSingleLine(true)
                setPadding(dp(4), dp(12), dp(4), dp(12))
                setOnClickListener { onTap() }
            })
        }
    }

    /** A suggested activity: exactly its button. */
    private fun tapActivity(id: Long) {
        Store.tap(this, id)?.let { CheckIn.start(this, it) }
        done()
    }

    /** Go, or a recent title: a switch to the typed words. */
    private fun go(text: String) {
        if (FreeWrite.clean(text) == null) return
        Store.startTitled(this, text)?.let { CheckIn.start(this, it) }
        done()
    }

    private fun done() {
        WidgetRenderer.refresh(this)
        dialog.dismiss()
    }
}
