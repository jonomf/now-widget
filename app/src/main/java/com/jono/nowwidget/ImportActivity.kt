package com.jono.nowwidget

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.OpenableColumns
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
    /** Everything below the status line: the mapping and results. Cleared for each new file. */
    private lateinit var body: LinearLayout
    /** One line of the mapping screen: a tag, its spinner and the choices that spinner has (a "Find…" pick adds to them). */
    private class Row(
        val id: Long,
        val spinner: Spinner,
        val choices: MutableList<Pair<String, (String) -> TagTarget>>,
        val adapter: ArrayAdapter<String>,
    )

    private val rows = ArrayList<Row>()
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
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body)
        setContentView(ScrollView(this).apply { addView(col) })

        if (savedInstanceState == null) {
            pick()
        } else {
            body.addView(Button(this).apply {
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
        body.removeAllViews()
        rows.clear()
        Thread {
            try {
                // Refuse a huge file from its reported size before reading any of it.
                val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                }
                if (size != null && size > MAX_BYTES) throw TooBig()
                val bytes = contentResolver.openInputStream(uri)?.use { readCapped(it) }
                    ?: throw IllegalArgumentException("Couldn't open the file")
                val parsed = Daylio.read(bytes)
                runOnUiThread { showMapping(parsed) }
            } catch (e: Throwable) {
                val why = when (e) {
                    is TooBig -> "it's over 20 MB, which is far bigger than any Daylio backup."
                    is OutOfMemoryError -> "it's too big to read on this phone."
                    else -> e.message ?: e.javaClass.simpleName
                }
                runOnUiThread {
                    status.text = "Couldn't read that file: $why"
                    body.addView(Button(this).apply {
                        text = "Pick another file"
                        setOnClickListener { pick() }
                    })
                }
            }
        }.start()
    }

    private class TooBig : Exception()

    /** Reads at most [MAX_BYTES]; anything bigger is refused before it fills memory. */
    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw TooBig()
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun showMapping(data: DaylioData) {
        if (isFinishing) return
        // A second file starts from a clean slate, so two files' choices never mix.
        body.removeAllViews()
        rows.clear()
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

        body.addView(heading("Moods"))
        body.addView(note(data.moods.joinToString(", ") { m ->
            val known = moods.any { it.name.equals(m.name, ignoreCase = true) }
            if (known) m.name else m.name + " (new, level ${m.level ?: 3})"
        }))

        // The choices every tag has. Only links that are chips are listed (the vault's thousands of notes
        // aren't); "Find…" searches all of them, and a link the guess picks is added to its own row.
        val liveActs = acts.filter { !it.archived }
        val liveLinks = Store.rankLinks(this, links.filter { !it.archived && (it.kind == LinkKind.PERSON || it.kind == LinkKind.PLACE) })
        val base = ArrayList<Pair<String, (String) -> TagTarget>>()
        fun choice(label: String, f: (String) -> TagTarget) {
            base.add(Pair(label, f))
        }
        fun linkChoice(l: com.jono.nowwidget.core.Link) {
            val kindLabel = if (l.kind == LinkKind.PLACE) "Place" else "Person"
            choice("$kindLabel: ${l.title}") { TagTarget.ToLink(l.id, l.kind, l.title) }
        }
        choice("Feeling") { TagTarget.Feeling }
        choice("Skip") { TagTarget.Skip }
        choice("New activity") { n -> TagTarget.ToActivity(null, n) }
        choice("New person") { n -> TagTarget.ToLink(null, LinkKind.PERSON, n) }
        choice("New place") { n -> TagTarget.ToLink(null, LinkKind.PLACE, n) }
        liveActs.forEach { a -> choice("Activity: ${a.emoji} ${a.name}") { TagTarget.ToActivity(a.id, a.name) } }
        liveLinks.filter { it.chip }.forEach { linkChoice(it) }
        choice(FIND) { TagTarget.Skip }

        body.addView(heading("Daylio activities"))
        body.addView(note("Most of yours are what you were doing or who with. Send each one to a feeling, a Now activity, a person or a place (\"Find…\" searches all your people and places). Ones sent to an activity, person or place are kept on the check-in; they never become time spans."))
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
            val mine = ArrayList(base)
            val guess = Daylio.guess(t.name, liveActs, liveLinks)
            var at = mine.indexOfFirst { it.second("") == guess }
            if (at < 0 && guess is TagTarget.ToLink && guess.id != null) {
                liveLinks.firstOrNull { it.id == guess.id }?.let { l ->
                    val kindLabel = if (l.kind == LinkKind.PLACE) "Place" else "Person"
                    mine.add(mine.size - 1, Pair<String, (String) -> TagTarget>("$kindLabel: ${l.title}") { _ -> TagTarget.ToLink(l.id, l.kind, l.title) })
                    at = mine.size - 2
                }
            }
            val adapter = ArrayAdapter(this@ImportActivity, android.R.layout.simple_spinner_dropdown_item, mine.map { it.first }.toMutableList())
            val spinner = Spinner(this)
            spinner.adapter = adapter
            spinner.setSelection(at.coerceAtLeast(0))
            val line = Row(t.id, spinner, mine, adapter)
            var last = spinner.selectedItemPosition
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    if (mine[pos].first != FIND) {
                        last = pos
                        return
                    }
                    spinner.setSelection(last)
                    pickLink("Find a person or place", setOf(LinkKind.PERSON, LinkKind.PLACE), LinkKind.PERSON) { linkId ->
                        val l = Store.allLinks(this@ImportActivity).firstOrNull { it.id == linkId } ?: return@pickLink
                        val kindLabel = if (l.kind == LinkKind.PLACE) "Place" else "Person"
                        val label = "$kindLabel: ${l.title}"
                        var i = mine.indexOfFirst { it.first == label }
                        if (i < 0) {
                            mine.add(mine.size - 1, Pair<String, (String) -> TagTarget>(label) { _ -> TagTarget.ToLink(l.id, l.kind, l.title) })
                            adapter.insert(label, mine.size - 2)
                            i = mine.size - 2
                        }
                        last = i
                        spinner.setSelection(i)
                    }
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
            row.addView(spinner)
            body.addView(row)
            rows.add(line)
        }

        val go = Button(this).apply { text = "Import ${data.entries.size} check-ins" }
        go.setOnClickListener {
            if (busy) return@setOnClickListener
            busy = true
            go.isEnabled = false
            rows.forEach { it.spinner.isEnabled = false }
            runImport(data)
        }
        body.addView(go)
    }

    private fun runImport(data: DaylioData) {
        val names = data.tags.associate { it.id to it.name }
        val targets = HashMap<Long, TagTarget>()
        rows.forEach { r ->
            val choice = r.choices.getOrNull(r.spinner.selectedItemPosition)?.takeIf { it.first != FIND } ?: r.choices[0]
            targets[r.id] = choice.second(names[r.id] ?: "")
        }
        val total = data.entries.size
        status.text = "Importing $total check-ins…"
        Thread {
            try {
                val r = Store.importDaylio(this, data, targets) { done ->
                    runOnUiThread { status.text = "Importing… $done of $total" }
                }
                runOnUiThread {
                    val first = r.firstTs
                    val last = r.lastTs
                    status.text = "Added ${r.added} check-ins" +
                        (if (first != null && last != null) ", from ${Fmt.date(first)} to ${Fmt.date(last)}." else ".") +
                        (if (r.alreadyThere > 0) " ${r.alreadyThere} were already here." else "") +
                        (if (r.skipped > 0) " Skipped ${r.skipped}." else "")
                    WidgetRenderer.refresh(this)
                    if (first != null) {
                        body.addView(Button(this).apply {
                            text = "Open the timeline at ${Fmt.date(first)}"
                            setOnClickListener {
                                startActivity(
                                    Intent(this@ImportActivity, TimelineActivity::class.java)
                                        .putExtra(TimelineActivity.EXTRA_DAY, first)
                                )
                            }
                        })
                    }
                    body.addView(Button(this).apply {
                        text = "Done"
                        setOnClickListener { finish() }
                    })
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    status.text = "Import failed, nothing was added: " + (e.message ?: e.javaClass.simpleName)
                }
            }
        }.start()
    }

    companion object {
        private const val REQUEST_PICK = 5
        private const val FIND = "Find…"
        private const val MAX_BYTES = 20L * 1024 * 1024
    }
}
