package com.jono.nowwidget

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jono.nowwidget.core.Act
import com.jono.nowwidget.core.CheckInFlow
import com.jono.nowwidget.core.DayRecap
import com.jono.nowwidget.core.DiaryDay
import com.jono.nowwidget.core.Entry
import com.jono.nowwidget.core.FillPart
import com.jono.nowwidget.core.FreeWrite
import com.jono.nowwidget.core.IDLE
import com.jono.nowwidget.core.Link
import com.jono.nowwidget.core.LinkKind
import com.jono.nowwidget.core.Mood
import com.jono.nowwidget.core.ObsidianDay
import com.jono.nowwidget.core.Recap
import com.jono.nowwidget.core.RecapRow
import com.jono.nowwidget.core.Together
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The evening recap: one diary day as a compact list, with gaps (nothing logged) and unlabelled
 * entries (✏️ Other with no words, or no place) marked. Tap one to fill it in: an activity ranked
 * by what you usually do at that time, then optional At and With chips, then Save. "How was today?"
 * saves a mood for the day. Every change goes through the Store (so the exports and the hub see it),
 * on a background thread; the last change can be undone for 10 seconds.
 */
class RecapActivity : Activity() {
    private lateinit var headLine: TextView
    private lateinit var dayChips: WrapLayout
    private lateinit var summary: TextView
    private lateinit var list: LinearLayout
    private lateinit var undoBar: LinearLayout
    private lateinit var undoText: TextView

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val tz: TimeZone = TimeZone.getDefault()

    /** The diary day shown (its 4am start). */
    private var day = 0L
    private var data: Data? = null
    private var loadGen = 0

    /** The row that's open for filling in: "g<from>" for a gap, "u<entry id>" for an unlabelled entry. */
    private var openKey: String? = null
    private var filler: Filler? = null

    // What's chosen in the open filler.
    private var selAct: Long? = null
    private var selTitle: String? = null
    private var selPlace: Long? = null
    private val selPeople = LinkedHashSet<Long>()
    private var part = FillPart.WHOLE
    /** Links picked with ＋ that weren't in the ranked lists. */
    private val extraLinks = ArrayList<Link>()

    private var undo: UndoStep? = null
    private val hideUndo = Runnable { clearUndo() }
    private var moodNote: EditText? = null
    private var busy = false

    /** Everything the screen needs for a day, read off the main thread. */
    private class Data(
        val recap: DayRecap,
        val acts: Map<Long, Act>,
        val links: Map<Long, Link>,
        val places: List<Link>,
        val people: List<Link>,
        val ranks: Map<Long, Double>,
        val moods: List<Mood>,
    )

    /** What one open row needs: activities ranked for its time, and the defaults from before it. */
    private class Filler(
        val key: String,
        val acts: List<Act>,
        val defaultPlace: Long?,
        val defaultPeople: List<Long>,
        val contextId: Long?,
    )

    /**
     * One step of undoing a save. [wrote] is the entry as the save left it: if it isn't like that any
     * more (the hub or the widget changed it since), the op is skipped rather than undoing over it.
     */
    sealed class UndoOp {
        /** Put an entry back as it was ([old]). */
        data class Restore(val old: Entry, val wrote: Entry) : UndoOp()
        /** Delete an entry the recap made. */
        data class Remove(val wrote: Entry) : UndoOp()
        /** Add back an idle entry the fill removed (it gets a new id). */
        data class ReAdd(val old: Entry) : UndoOp()
        data class RemoveMoment(val momentId: Long) : UndoOp()
    }

    class UndoStep(val label: String, val ops: List<UndoOp>)

    /** The day changed under the recap (a sync, the widget): it's reloaded instead of saving over it. */
    private class Stale : RuntimeException()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val now = System.currentTimeMillis()
        day = DiaryDay.start(now)
        val asked = intent.getLongExtra(EXTRA_DAY, -1L)
        if (asked >= 0L) day = DiaryDay.start(minOf(asked, now))

        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        headLine = heading("Recap").apply { setPadding(0, 0, 0, dp(6)) }
        col.addView(headLine)
        dayChips = WrapLayout(this)
        col.addView(dayChips)
        summary = note("")
        col.addView(summary)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)

        undoText = TextView(this).apply {
            textSize = 14f
            setSingleLine(true)
        }
        undoBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, dp(6), pad, dp(10))
            visibility = View.GONE
            addView(undoText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@RecapActivity).apply {
                text = "Undo"
                setOnClickListener { doUndo() }
            })
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(undoBar)
        setContentView(root)
        // He's looking at it: the notification has done its job.
        RecapNotice.cancel(this)
    }

    /** The widget, the hub or another screen changed the log: show it as it is now. */
    private val changed: () -> Unit = { runOnUiThread { if (!isFinishing && !isDestroyed) reload() } }

    override fun onResume() {
        super.onResume()
        WidgetRenderer.listeners.add(changed)
        reload()
    }

    override fun onPause() {
        WidgetRenderer.listeners.remove(changed)
        super.onPause()
    }

    override fun onDestroy() {
        undoBar.removeCallbacks(hideUndo)
        // Lets a save that's under way finish; nothing new starts.
        worker.shutdown()
        super.onDestroy()
    }

    // --- Threads ---

    /** Runs [work] on the recap's one worker thread (reads and writes in order), then [done] on the main thread. */
    private fun <T> bg(work: () -> T, done: (T) -> Unit) {
        if (worker.isShutdown) return
        worker.execute {
            val r = runCatching(work)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                r.onSuccess(done).onFailure { e ->
                    if (e is Stale) {
                        Toast.makeText(this, "That changed meanwhile. Here it is again.", Toast.LENGTH_LONG).show()
                        openKey = null
                        reload()
                    } else {
                        Toast.makeText(this, "Couldn't do that: " + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                    }
                    busy = false
                }
            }
        }
    }

    private fun reload() {
        val gen = ++loadGen
        val d = day
        bg({ load(d) }) { if (gen == loadGen) {
            data = it
            if (filler?.key != openKey) filler = null
            draw()
        } }
    }

    private fun load(d: Long): Data {
        val now = System.currentTimeMillis()
        val links = Store.allLinks(this)
        val ranks = Store.linkRanks(this, now)
        return Data(
            recap = RecapNotice.recap(this, d, now),
            acts = Store.allActs(this).associateBy { it.id },
            links = links.associateBy { it.id },
            places = CheckIn.flowLinks(links, LinkKind.PLACE),
            people = CheckIn.flowLinks(links, LinkKind.PERSON),
            ranks = ranks,
            moods = Store.allMoods(this).filter { !it.archived }.sortedWith(compareBy({ it.level }, { it.pos })),
        )
    }

    /** After a save: the widget and the list. */
    private fun saved(step: UndoStep?) {
        busy = false
        openKey = null
        filler = null
        if (step != null) showUndo(step)
        reload()
    }

    private fun save(work: () -> UndoStep?) {
        if (busy) return
        busy = true
        bg({
            val step = work()
            WidgetRenderer.refresh(this)
            step
        }) { saved(it) }
    }

    // --- Drawing ---

    private fun t(ms: Long) = ObsidianDay.time(ms, tz)
    private fun range(from: Long, to: Long) = t(from) + "–" + t(to)

    private fun draw() {
        val d = data ?: return
        val r = d.recap
        val now = System.currentTimeMillis()
        val base = DiaryDay.start(now)
        headLine.text = "Recap · " + Fmt.day(day)
        dayChips.removeAllViews()
        listOf(base to "Today", DiaryDay.add(base, -1) to "Yesterday").forEach { (start, word) ->
            dayChips.addView(chip(word + " · " + Fmt.day(start), start == day) {
                if (start != day) {
                    day = start
                    openKey = null
                    filler = null
                    reload()
                }
            })
        }
        summary.text = when {
            !r.logged && r.rows.isEmpty() -> "Nothing logged this day."
            r.nothingToFix -> "Nothing to fix."
            else -> Recap.summary(r, "").let { s -> if (r.gaps.isEmpty() && r.unlabelled.isEmpty()) s else "$s. Tap one to fill it in." }
        }

        list.removeAllViews()
        r.rows.forEach { row ->
            when (row) {
                is RecapRow.Logged -> list.addView(loggedRow(row.entry, row.from, row.to, d))
                is RecapRow.Gap -> {
                    val key = "g" + row.from
                    list.addView(markedRow(range(row.from, row.to) + " · nothing logged", Fmt.dur(row.to - row.from), key), rowParams())
                    if (openKey == key) list.addView(gapFiller(row, d))
                }
                is RecapRow.Unlabelled -> {
                    val key = "u" + row.entry.id
                    val reasons = listOfNotNull("no words".takeIf { row.noTitle }, "no place".takeIf { row.noPlace }).joinToString(", ")
                    list.addView(markedRow(range(row.from, row.to) + " " + entryName(row.entry, d), reasons, key), rowParams())
                    if (openKey == key) list.addView(unlabelledFiller(row, d))
                }
            }
        }
        if (r.moodMissing && (r.logged || day == base)) moodRow(d)
    }

    private fun entryName(e: Entry, d: Data): String {
        val a = d.acts[e.activityId] ?: return FreeWrite.name(e.title, null)
        return Together.label(FreeWrite.name(e.title, a), a.emoji, e.extras.mapNotNull { d.acts[it] }, emoji = true)
    }

    private fun loggedRow(e: Entry, from: Long, to: Long, d: Data): TextView {
        val place = e.links.mapNotNull { d.links[it] }.filter { it.kind == LinkKind.PLACE }.joinToString(", ") { it.title }
        val people = e.links.mapNotNull { d.links[it] }.filter { it.kind == LinkKind.PERSON }.joinToString(", ") { it.title }
        val parts = listOf(range(from, to) + " " + entryName(e, d), place, people).filter { it.isNotBlank() }
        return TextView(this).apply {
            text = parts.joinToString(" · ")
            textSize = 14f
            alpha = 0.75f
            setSingleLine(true)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
    }

    /** A gap or unlabelled row: highlighted, tap to open or close its filler. */
    private fun markedRow(text: String, detail: String, key: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setChipLight(this)
        addView(TextView(this@RecapActivity).apply {
            this.text = text
            textSize = 15f
            setSingleLine(true)
        })
        if (detail.isNotBlank()) addView(TextView(this@RecapActivity).apply {
            this.text = detail
            textSize = 12f
            alpha = 0.7f
        })
        setOnClickListener { toggle(key) }
    }

    private fun rowParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(4)
        bottomMargin = dp(4)
    }

    private fun toggle(key: String) {
        if (openKey == key) {
            openKey = null
            filler = null
            draw()
            return
        }
        openKey = key
        filler = null
        selAct = null
        selTitle = null
        selPlace = null
        selPeople.clear()
        part = FillPart.WHOLE
        extraLinks.clear()
        draw()
        val d = data ?: return
        val row = d.recap.rows.firstOrNull { rowKey(it) == key } ?: return
        bg({ loadFiller(key, row, d) }) { f ->
            if (openKey != key) return@bg
            filler = f
            if (row is RecapRow.Gap) {
                selPlace = f.defaultPlace
                selPeople.addAll(f.defaultPeople)
            }
            draw()
        }
    }

    private fun rowKey(row: RecapRow): String? = when (row) {
        is RecapRow.Gap -> "g" + row.from
        is RecapRow.Unlabelled -> "u" + row.entry.id
        is RecapRow.Logged -> null
    }

    /** Activities ranked for the row's start time (weekday or weekend, as the buttons do), and the defaults before it. */
    private fun loadFiller(key: String, row: RecapRow, d: Data): Filler {
        val at = row.from
        val acts = Recap.rankActs(d.acts.values.toList(), Store.scores(this, at))
        // The entry the row is about, or a stand-in at the gap's start, for "the one before".
        val probe = when (row) {
            is RecapRow.Unlabelled -> row.entry
            is RecapRow.Gap -> row.headIdle?.let { Store.entryById(this, it) } ?: Entry(Long.MAX_VALUE, IDLE, at)
            is RecapRow.Logged -> row.entry
        }
        val usablePlaces = d.places.map { it.id }.toSet()
        val usablePeople = d.people.map { it.id }.toSet()
        return Filler(
            key = key,
            acts = acts,
            defaultPlace = CheckInFlow.defaultPlace(null, Store.lastPlaceBefore(this, probe), usablePlaces),
            defaultPeople = CheckInFlow.defaultPeople(emptySet(), Store.previousPeople(this, probe), usablePeople),
            contextId = Store.contextBeforeEntry(this, probe),
        )
    }

    private fun box(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(2), dp(8), dp(12))
    }

    private fun wrapIn(parent: LinearLayout): WrapLayout = WrapLayout(this).also { parent.addView(it) }

    /** Activity chips, ranked, with ＋ Write at the end. A typed title shows as its own chip. */
    private fun actChips(parent: LinearLayout, f: Filler) {
        val g = wrapIn(parent)
        val title = selTitle
        if (title != null) {
            g.addView(chip("✏️ $title", true) {
                selTitle = null
                selAct = null
                draw()
            })
        }
        f.acts.take(MAX_ACTS).forEach { a ->
            g.addView(chip(a.emoji + " " + a.name, title == null && selAct == a.id) {
                selAct = if (selAct == a.id && selTitle == null) null else a.id
                selTitle = null
                draw()
            })
        }
        g.addView(chip("＋ Write", false) { write() })
    }

    /** The free-write box, for this filler only: nothing starts; the words become the filler's title. */
    private fun write() {
        val input = EditText(this).apply {
            hint = "What were you doing?"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(selTitle ?: "")
        }
        val frame = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val key = openKey
        val dlg = AlertDialog.Builder(this)
            .setTitle("＋ What were you doing?")
            .setView(frame)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ ->
                val text = input.text.toString()
                if (FreeWrite.clean(text) == null) return@setPositiveButton
                bg({ Store.freeWriteCategory(this, text) }) { res ->
                    if (res == null || openKey != key) return@bg
                    selAct = res.first
                    selTitle = res.second
                    // An activity's own name is just that activity.
                    if (res.second == null) selTitle = null
                    draw()
                }
            }
            .create()
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        input.requestFocus()
    }

    /** At or With chips: chosen first, then the ranking (defaults first), then ＋. */
    private fun linkChips(parent: LinearLayout, d: Data, kind: String, first: List<Long>, max: Int, on: (Long) -> Boolean, tap: (Long) -> Unit) {
        val all = (if (kind == LinkKind.PLACE) d.places else d.people) + extraLinks.filter { it.kind == kind }
        val ranked = CheckInFlow.candidates(all.distinctBy { it.id }, d.ranks, first)
        val chosen = ranked.filter { on(it.id) }
        val shown = (chosen + ranked.filter { !on(it.id) }.take(maxOf(0, max - chosen.size))).distinctBy { it.id }
        val g = wrapIn(parent)
        shown.forEach { l -> g.addView(chip(l.label, on(l.id)) { tap(l.id) }) }
        g.addView(chip("＋", false) {
            pickLink(if (kind == LinkKind.PLACE) "Find or add a place" else "Find or add a person", pickKinds(kind), kind) { id ->
                val l = Store.allLinks(this).firstOrNull { it.id == id }
                if (l != null && extraLinks.none { it.id == id }) extraLinks.add(l)
                tap(id)
            }
        })
    }

    private fun sub(parent: LinearLayout, text: String) = parent.addView(label(text))

    private fun gapFiller(gap: RecapRow.Gap, d: Data): View {
        val b = box()
        val f = filler
        if (f == null || f.key != openKey) {
            b.addView(note("…"))
            return b
        }
        sub(b, "Doing")
        actChips(b, f)
        sub(b, "At")
        linkChips(b, d, LinkKind.PLACE, listOfNotNull(f.defaultPlace), MAX_PLACES, { it == selPlace }) { id ->
            selPlace = if (selPlace == id) null else id
            draw()
        }
        sub(b, "With")
        linkChips(b, d, LinkKind.PERSON, f.defaultPeople, MAX_PEOPLE, { it in selPeople }) { id ->
            if (!selPeople.remove(id)) selPeople.add(id)
            draw()
        }
        if (Recap.canSplit(gap)) {
            sub(b, "Part of it")
            val mid = Recap.middle(gap)
            val g = wrapIn(b)
            listOf(
                FillPart.WHOLE to "All " + range(gap.from, gap.to),
                FillPart.FIRST_HALF to "First half " + range(gap.from, mid),
                FillPart.LAST_HALF to "Last half " + range(mid, gap.to),
            ).forEach { (p, text) ->
                g.addView(chip(text, part == p) {
                    part = p
                    draw()
                }.apply { textSize = 13f })
            }
        }
        b.addView(Button(this).apply {
            text = "Save"
            isEnabled = selAct != null
            setOnClickListener {
                val act = selAct ?: return@setOnClickListener
                val title = selTitle
                val links = selPeople.toSet() + listOfNotNull(selPlace)
                val p = part
                save { fillGap(gap, p, act, title, links, f.contextId) }
            }
        })
        return b
    }

    /**
     * Fills [part] of [gap] ([Recap.plan]): its idle entry becomes [act], or a new entry starts in it;
     * an idle entry closes it where the fill ends, so nothing outside it changes. Checks first that the
     * gap is still as it was drawn.
     */
    private fun fillGap(gap: RecapRow.Gap, part: FillPart, act: Long, title: String?, links: Set<Long>, contextId: Long?): UndoStep =
        // One transaction: the check that the gap is as drawn, and every write. A failure part way rolls it all back.
        Store.inTx(this) {
            val inside = Store.entriesStarting(this, gap.from, gap.to)
            if (inside.any { it.activityId != IDLE }) throw Stale()
            val plan = Recap.plan(gap, part)
            val ops = ArrayList<UndoOp>()
            val changeId = plan.changeIdle
            if (changeId != null) {
                val old = Store.entryById(this, changeId) ?: throw Stale()
                if (old.activityId != IDLE || old.start != gap.from) throw Stale()
                Store.updateEntry(this, old.id, act, old.start, links, contextId, title, emptySet())
                ops.add(UndoOp.Restore(old, Store.entryById(this, old.id) ?: throw Stale()))
            } else if (inside.any { it.start == gap.from } && plan.insertAt == gap.from) {
                throw Stale()
            }
            // The gap's other idle entries in the filled part: they'd cut the fill short.
            plan.delete.forEach { id ->
                val old = inside.firstOrNull { it.id == id } ?: throw Stale()
                Store.deleteEntry(this, id)
                ops.add(UndoOp.ReAdd(old))
            }
            plan.insertAt?.let { at ->
                val id = Store.addEntry(this, act, at, links, contextId, title)
                ops.add(UndoOp.Remove(Store.entryById(this, id) ?: throw Stale()))
            }
            plan.idleAt?.let { at ->
                if (Store.entriesStarting(this, at, at + 1).isEmpty()) {
                    val id = Store.addEntry(this, IDLE, at, emptySet(), null)
                    ops.add(UndoOp.Remove(Store.entryById(this, id) ?: throw Stale()))
                }
            }
            val end = plan.idleAt ?: gap.to
            val from = plan.insertAt ?: gap.from
            UndoStep("Filled " + range(from, end), ops)
        }

    private fun unlabelledFiller(row: RecapRow.Unlabelled, d: Data): View {
        val b = box()
        val f = filler
        if (f == null || f.key != openKey) {
            b.addView(note("…"))
            return b
        }
        if (row.noTitle) {
            sub(b, "What was it?")
            actChips(b, f)
        }
        if (row.noPlace) {
            sub(b, "At")
            linkChips(b, d, LinkKind.PLACE, listOfNotNull(f.defaultPlace), MAX_PLACES, { it == selPlace }) { id ->
                selPlace = if (selPlace == id) null else id
                draw()
            }
        }
        b.addView(Button(this).apply {
            text = "Save"
            isEnabled = (row.noTitle && selAct != null) || (row.noPlace && selPlace != null)
            setOnClickListener {
                val act = selAct.takeIf { row.noTitle }
                val title = selTitle.takeIf { row.noTitle }
                val place = selPlace.takeIf { row.noPlace }
                val placeIds = d.places.map { it.id }.toSet() + extraLinks.filter { it.kind == LinkKind.PLACE }.map { it.id }
                save { applyLabel(row, act, title, place, placeIds) }
            }
        })
        return b
    }

    /** Gives an unlabelled entry its category or words, and/or its place (one transaction with its check). */
    private fun applyLabel(row: RecapRow.Unlabelled, act: Long?, title: String?, place: Long?, placeIds: Set<Long>): UndoStep? =
        Store.inTx(this) {
            val old = Store.entryById(this, row.entry.id) ?: throw Stale()
            if (old.start != row.entry.start || old.activityId != row.entry.activityId) throw Stale()
            val links = if (place != null) old.links.filter { it !in placeIds }.toSet() + place else old.links
            val newAct = act ?: old.activityId
            val newTitle = if (act != null) title else old.title
            if (newAct == old.activityId && newTitle == old.title && links == old.links) {
                null
            } else {
                Store.updateEntry(this, old.id, newAct, old.start, links, old.contextId, newTitle, old.extras)
                val wrote = Store.entryById(this, old.id) ?: throw Stale()
                UndoStep("Saved " + range(row.from, row.to), listOf(UndoOp.Restore(old, wrote)))
            }
        }

    // --- How was today? ---

    private fun moodRow(d: Data) {
        if (d.moods.isEmpty()) return
        list.addView(label("How was today?").apply { textSize = 15f })
        val keep = moodNote?.text?.toString() ?: ""
        val noteField = EditText(this).apply {
            hint = "A few words (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(keep)
        }
        moodNote = noteField
        val faces = WrapLayout(this)
        val at = d.recap.moodAt
        val forDay = day
        d.moods.forEach { m ->
            faces.addView(chip(moodFace(m), false) {
                val text = noteField.text.toString().trim()
                save {
                    Store.inTx(this) {
                        // Only if the day still has no feeling (the widget may have added one meanwhile).
                        val r = RecapNotice.recap(this, forDay)
                        if (!r.moodMissing) throw Stale()
                        val id = Store.addMoment(this, at, "", text, m.id, emptyList(), null)
                        UndoStep("Saved " + moodFace(m) + " for the day", listOf(UndoOp.RemoveMoment(id)))
                    }
                }
                noteField.setText("")
            }.apply {
                contentDescription = m.name
                textSize = 22f
            })
        }
        list.addView(faces)
        list.addView(noteField)
    }

    // --- Undo ---

    private fun showUndo(step: UndoStep) {
        undo = step
        undoText.text = step.label
        undoBar.visibility = View.VISIBLE
        undoBar.removeCallbacks(hideUndo)
        undoBar.postDelayed(hideUndo, UNDO_MS)
    }

    private fun clearUndo() {
        undo = null
        undoBar.visibility = View.GONE
    }

    private fun doUndo() {
        val step = undo ?: return
        if (busy) return
        clearUndo()
        undoBar.removeCallbacks(hideUndo)
        save {
            var skipped = 0
            // One transaction; each op only if what it undoes is still as the save left it.
            Store.inTx(this) {
                step.ops.asReversed().forEach { op ->
                    val done = when (op) {
                        is UndoOp.Remove -> (Store.entryById(this, op.wrote.id) == op.wrote).also { same ->
                            if (same) Store.deleteEntry(this, op.wrote.id)
                        }
                        is UndoOp.Restore -> (Store.entryById(this, op.old.id) == op.wrote).also { same ->
                            if (same) Store.updateEntry(this, op.old.id, op.old.activityId, op.old.start, op.old.links, op.old.contextId, op.old.title, op.old.extras)
                        }
                        // Back in place unless something else starts there now.
                        is UndoOp.ReAdd -> Store.entriesStarting(this, op.old.start, op.old.start + 1).isEmpty().also { free ->
                            if (free) Store.addEntry(this, op.old.activityId, op.old.start, op.old.links, op.old.contextId, op.old.title, op.old.extras)
                        }
                        is UndoOp.RemoveMoment -> Store.momentExists(this, op.momentId).also { there ->
                            if (there) Store.deleteMoment(this, op.momentId)
                        }
                    }
                    if (!done) skipped++
                }
            }
            if (skipped > 0) runOnUiThread {
                Toast.makeText(this, "Part of it had changed since, so that part was left as it is.", Toast.LENGTH_LONG).show()
            }
            null
        }
    }

    companion object {
        const val EXTRA_DAY = "day"
        private const val UNDO_MS = 10_000L
        private const val MAX_ACTS = 29
        private const val MAX_PLACES = 10
        private const val MAX_PEOPLE = 12
    }
}
