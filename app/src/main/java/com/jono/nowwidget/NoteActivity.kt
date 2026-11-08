package com.jono.nowwidget

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast

/**
 * The 📝 button: a small box over the home screen with the keyboard already up, so you can type
 * or tap the keyboard's mic. The note goes on what you're doing now.
 */
class NoteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cur = Store.current(this)
        if (cur == null) {
            Toast.makeText(this, "Tap what you're doing first", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val act = Store.allActs(this).firstOrNull { it.id == cur.activityId }

        val input = EditText(this).apply {
            hint = "Type or tap the mic"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            maxLines = 8
        }
        val p = dp(20)
        val box = FrameLayout(this).apply {
            setPadding(p, p / 2, p, 0)
            addView(input)
        }
        val b = AlertDialog.Builder(this)
            .setTitle("📝 " + (act?.let { it.emoji + " " + it.name } ?: "Note"))
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    Store.appendNote(this, cur.id, text)
                    WidgetRenderer.refresh(this)
                }
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { finish() }
        if (cur.note.isNotBlank()) b.setMessage(cur.note)

        val d = b.create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        d.show()
        input.requestFocus()
    }
}
