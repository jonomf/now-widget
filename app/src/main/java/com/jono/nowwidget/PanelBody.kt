package com.jono.nowwidget

import android.app.Activity
import android.content.Intent
import android.widget.LinearLayout
import android.widget.TextView
import com.jono.nowwidget.core.FreeWrite
import com.jono.nowwidget.core.LinkKind
import com.jono.nowwidget.core.Link
import com.jono.nowwidget.core.Moment
import com.jono.nowwidget.core.PanelSections
import com.jono.nowwidget.core.PanelSections.Section
import com.jono.nowwidget.core.StartShift

/**
 * The expand panel's sections, top to bottom, as ordinary views (so they scroll):
 * - **Doing**: what's on (emoji, title, its category), how long and since when, the Started row
 *   (offsets, ＋5, Undo, Set time…), and the activity buttons with ＋ Write at the end.
 * - **At**: the place (highlighted), ranked places, ＋; then the context ("in …") the same way.
 * - **With**: the people on it (highlighted), ranked people, ＋.
 * - **Feeling**: the latest check-in, the mood faces (a tap saves a feeling), the top feeling tags
 *   for that check-in, and 📝 for the full box.
 * Every change goes through the Store and redraws the widget, which redraws this.
 */
class PanelBody(
    private val a: Activity,
    private val s: Snapshot,
    private val setTime: () -> Unit,
    private val addLink: (String) -> Unit,
) {
    val view: LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(a.dp(16), a.dp(4), a.dp(16), a.dp(16))
    }

    init {
        PanelSections.visible(idle = s.cur == null, flowRunning = s.flow != null).forEach { sec ->
            when (sec) {
                Section.DOING -> doing()
                Section.AT -> at()
                Section.WITH -> with()
                Section.FEELING -> feeling()
            }
        }
    }

    private fun changed() = WidgetRenderer.refresh(a)

    private fun wrap(): WrapLayout = WrapLayout(a).also { view.addView(it) }

    private fun line(text: String, size: Float, alpha: Float = 1f) = TextView(a).apply {
        this.text = text
        textSize = size
        this.alpha = alpha
    }.also { view.addView(it) }

    // --- Doing ---

    private fun doing() {
        view.addView(a.label("Doing"))
        val cur = s.cur
        val act = s.act
        if (cur == null) {
            line("⭕ Nothing", 20f)
            if (s.sinceText.isNotBlank()) line(s.sinceText, 13f, 0.7f)
        } else {
            line((act?.emoji ?: "") + " " + s.name, 20f)
            val parts = listOfNotNull(
                if (cur.title != null) act?.name else null,
                Fmt.dur(s.now - cur.start),
                s.sinceText,
                s.shiftNote,
            )
            line(parts.joinToString(" · "), 13f, 0.7f)
            started(cur.id)
        }
        val grid = wrap()
        s.picks(29).forEach { b ->
            grid.addView(a.chip(b.emoji + " " + b.name, b.id == s.act?.id) {
                Store.tap(a, b.id)?.let { CheckIn.start(a, it) }
                changed()
            })
        }
        grid.addView(a.chip("＋ Write", false) {
            a.startActivity(Intent(a, FreeWriteActivity::class.java))
        })
    }

    /** "Started 2:30p (45m ago) · Eat now 25m", then −5 −15 … ＋5, Undo, Set time…. */
    private fun started(entryId: Long) {
        val cur = s.cur ?: return
        line("Started " + Fmt.time(a, cur.start) + " (" + Fmt.dur(s.now - cur.start) + " ago)" +
            (s.prevLength?.let { " · $it" } ?: ""), 13f, 0.7f)
        val row = wrap()
        (s.offsets + 5).distinct().forEach { m ->
            val p = s.previews[m] ?: return@forEach
            if (!p.changed && m > 0) return@forEach
            val armed = StartShift.isArmed(s.armed, entryId, m, s.now)
            row.addView(a.chip(StartShift.chipLabel(m, p, s.askOverMin, armed), armed) {
                StartControls.tap(a, entryId, m)
                changed()
            })
        }
        if (s.undo != null) row.addView(a.chip("Undo", false) {
            StartControls.undo(a, entryId)
            changed()
        })
        row.addView(a.chip("Set time…", false) { setTime() })
    }

    // --- At and With ---

    /** The ones on the entry first (highlighted), then the ranked rest, then ＋. */
    private fun linkChips(items: List<Link>, on: (Link) -> Boolean, kind: String, max: Int) {
        val row = wrap()
        val shown = (items.filter(on) + items.filter { !on(it) }).distinctBy { it.id }.take(max)
        shown.forEach { l ->
            row.addView(a.chip(l.label, on(l)) {
                Store.toggleLink(a, l.id)
                changed()
            })
        }
        row.addView(a.chip("＋", false) { addLink(kind) })
    }

    private fun at() {
        val cur = s.cur ?: return
        view.addView(a.label("At"))
        val places = listOfNotNull(s.place) + s.places
        linkChips(places, { it.id in cur.links }, LinkKind.PLACE, 10)
        // Context is "where" too: a trip, an event.
        val contexts = listOfNotNull(s.context) + s.contexts
        line("In", 12f, 0.6f)
        linkChips(contexts, { it.id == cur.contextId }, LinkKind.CONTEXT, 6)
    }

    private fun with() {
        val cur = s.cur ?: return
        view.addView(a.label("With"))
        linkChips(s.people, { it.id in cur.links }, LinkKind.PERSON, 12)
    }

    // --- Feeling ---

    private fun feeling() {
        view.addView(a.label("Feeling"))
        val cur = s.cur
        val day = com.jono.nowwidget.core.DiaryDay.start(s.now)
        val from = minOf(cur?.start ?: s.idleSince ?: day, day)
        val moments = if (cur != null) Store.momentsOf(a, cur.id) else Store.moments(a, from, s.now + 1)
        val latest: Moment? = PanelSections.latestCheckIn(moments, cur?.id, s.idleSince)
        val moods = Store.allMoods(a)
        val tags = Store.allTags(a)
        if (latest != null) {
            val m = moods.firstOrNull { it.id == latest.moodId }
            val parts = listOf(
                Fmt.time(a, latest.ts),
                if (m != null) moodFace(m) + " " + m.name else "",
                latest.tags.mapNotNull { id -> tags.firstOrNull { it.id == id }?.name }.joinToString(", "),
            ).filter { it.isNotBlank() }
            line(parts.joinToString(" · "), 14f)
        }
        // A face saves a new feeling on what's on now (or a loose one when nothing is), and is then the latest.
        val faces = wrap()
        moods.filter { !it.archived }.sortedWith(compareBy({ it.level }, { it.pos })).forEach { mood ->
            faces.addView(a.chip(moodFace(mood), latest?.moodId == mood.id) {
                Store.addMoment(a, System.currentTimeMillis(), "", "", mood.id, emptyList(), cur?.id)
                changed()
            }.apply { contentDescription = mood.name; textSize = 22f })
        }
        // Tags for that check-in; with none yet, a tag starts one.
        val on = latest?.tags.orEmpty()
        val tagRow = wrap()
        PanelSections.topTags(tags, Store.tagUses(a), on).forEach { t ->
            tagRow.addView(a.chip(t.label, t.id in on) {
                if (latest == null) {
                    Store.addMoment(a, System.currentTimeMillis(), "", "", null, listOf(t.id), cur?.id)
                } else {
                    Store.updateMoment(a, latest, latest.ts, latest.text, latest.moodId, PanelSections.toggle(latest.tags, t.id))
                }
                changed()
            })
        }
        tagRow.addView(a.chip("📝", false) {
            a.startActivity(Intent(a, NoteActivity::class.java))
        })
    }
}
