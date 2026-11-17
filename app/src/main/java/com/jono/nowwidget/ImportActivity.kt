package com.jono.nowwidget

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.jono.nowwidget.core.Daylio
import com.jono.nowwidget.core.DaylioData
import com.jono.nowwidget.core.LinkKind
import com.jono.nowwidget.core.TagTarget
import java.util.Locale

/**
 * Import from Daylio: pick a backup (.daylio) or Daylio CSV with the system file picker (no
 * permission needed), choose where each Daylio activity goes, then import. Each check-in becomes
 * one feeling at its own time; nothing becomes a time span. Importing again adds nothing.
 */
class ImportActivity : Activity() {
    private lateinit var col: LinearLayout
    private lateinit var status: TextView
    private val rows = ArrayList<Pair<Long, Spinner>>()
    private var choices: List<Pair<String, (String) -> TagTarget>> = emptyList()
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(20)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = "Import from Daylio"
            textSize = 24f
        })
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
            text = "Pick a Daylio backup (.daylio) or a Daylio CSV export."
        }
        col.addView(status)
        setContentView(ScrollView(this).apply { addView(col) })

        if (savedInstanceState == null) {
            pick()
        } else {
            col.addView(Button(this).apply {
                text = "Pick a file"
                setOnClickListener { pick() }
            })
        }
    }

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        startActivityForResult(i, REQUEST_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            finish()
            return
        }
        status.text = "Reading…"
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Couldn't open the file")
                val parsed = Daylio.read(bytes)
                runOnUiThread { showMapping(parsed) }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Couldn't read that file: " + (e.message ?: e.javaClass.simpleName)
                    col.addView(Button(this).apply {
                        text = "Pick another file"
                        setOnClickListener { pick() }
                    })
                }
            }
        }.start()
    }

    private fun showMapping(data: DaylioData) {
        if (isFinishing) return
        if (data.entries.isEmpty()) {
            status.text = "No check-ins in that file."
            return
        }
        val acts = Store.allActs(this)
        val links = Store.allLinks(this)
        val moods = Store.allMoods(this)

        val first = data.entries.minWithOrNull(compareBy({ it.year }, { it.month }, { it.day }))
        val last = data.entries.maxWithOrNull(compareBy({ it.year }, { it.month }, { it.day }))
        fun d(e: com.jono.nowwidget.core.DaylioEntry?) =
            if (e == null) "" else String.format(Locale.ROOT, "%04d-%02d-%02d", e.year, e.month, e.day)
        status.text = "${data.entries.size} check-ins, ${d(first)} to ${d(last)}." +
            if (data.skipped > 0) " ${data.skipped} couldn't be read and will be skipped." else ""

        col.addView(heading("Moods"))
        col.addView(note(data.moods.joinToString(", ") { m ->
            val known = moods.any { it.name.equals(m.name, ignoreCase = true) }
            if (known) m.name else m.name + " (new, level ${m.level ?: 3})"
        }))

        // Every choice a tag can have; the function turns the tag's name into a target.
        val liveActs = acts.filter { !it.archived }
        val liveLinks = links.filter { !it.archived && (it.kind == LinkKind.PERSON || it.kind == LinkKind.PLACE) }
        val list = ArrayList<Pair<String, (String) -> TagTarget>>()
        fun choice(label: String, f: (String) -> TagTarget) {
            list.add(Pair(label, f))
        }
        choice("Feeling") { TagTarget.Feeling }
        choice("Skip") { TagTarget.Skip }
        choice("New activity") { n -> TagTarget.ToActivity(null, n) }
        choice("New person") { n -> TagTarget.ToLink(null, LinkKind.PERSON, n) }
        choice("New place") { n -> TagTarget.ToLink(null, LinkKind.PLACE, n) }
        liveActs.forEach { a -> choice("Activity: ${a.emoji} ${a.name}") { TagTarget.ToActivity(a.id, a.name) } }
        liveLinks.forEach { l ->
            val kindLabel = if (l.kind == LinkKind.PLACE) "Place" else "Person"
            choice("$kindLabel: ${l.title}") { TagTarget.ToLink(l.id, l.kind, l.title) }
        }
        choices = list
        val labels = list.map { it.first }

        col.addView(heading("Daylio activities"))
        col.addView(note("Most of yours are what you were doing or who with. Send each one to a feeling, a Now activity, a person or a place. Ones sent to an activity, person or place are kept on the check-in; they never become time spans."))
        val uses = data.uses()
        data.tags.sortedByDescending { uses[it.id] ?: 0 }.forEach { t ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(this).apply {
                text = t.name + "  ·  " + (uses[t.id] ?: 0)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            })
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@ImportActivity, android.R.layout.simple_spinner_dropdown_item, labels)
                setSelection(defaultChoice(Daylio.guess(t.name, liveActs, liveLinks)))
            }
            row.addView(spinner)
            col.addView(row)
            rows.add(t.id to spinner)
        }

        val go = Button(this).apply { text = "Import ${data.entries.size} check-ins" }
        go.setOnClickListener {
            if (busy) return@setOnClickListener
            busy = true
            go.isEnabled = false
            rows.forEach { it.second.isEnabled = false }
            runImport(data)
        }
        col.addView(go)
    }

    /** Where Daylio.guess's answer sits in the choices list. */
    private fun defaultChoice(g: TagTarget): Int = choices.indexOfFirst { it.second("") == g }.coerceAtLeast(0)

    private fun runImport(data: DaylioData) {
        val names = data.tags.associate { it.id to it.name }
        val targets = HashMap<Long, TagTarget>()
        rows.forEach { (id, spinner) ->
            val choice = choices.getOrNull(spinner.selectedItemPosition) ?: choices[0]
            targets[id] = choice.second(names[id] ?: "")
        }
        val total = data.entries.size
        status.text = "Importing $total check-ins…"
        Thread {
            try {
                val r = Store.importDaylio(this, data, targets) { done ->
                    runOnUiThread { status.text = "Importing… $done of $total" }
                }
                runOnUiThread {
                    status.text = "Added ${r.added} check-ins." +
                        (if (r.alreadyThere > 0) " ${r.alreadyThere} were already here." else "") +
                        (if (r.skipped > 0) " Skipped ${r.skipped}." else "")
                    WidgetRenderer.refresh(this)
                    col.addView(Button(this).apply {
                        text = "Done"
                        setOnClickListener { finish() }
                    })
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Import failed, nothing was added: " + (e.message ?: e.javaClass.simpleName)
                }
            }
        }.start()
    }

    companion object {
        private const val REQUEST_PICK = 5
    }
}
