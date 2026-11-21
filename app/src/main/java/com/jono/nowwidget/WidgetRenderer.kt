package com.jono.nowwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.jono.nowwidget.core.Act
import com.jono.nowwidget.core.CheckInFlow
import com.jono.nowwidget.core.Entry
import com.jono.nowwidget.core.FlowCell
import com.jono.nowwidget.core.FlowControl
import com.jono.nowwidget.core.FlowOption
import com.jono.nowwidget.core.FlowState
import com.jono.nowwidget.core.FlowStep
import com.jono.nowwidget.core.FlowView
import com.jono.nowwidget.core.Link
import com.jono.nowwidget.core.LinkKind
import com.jono.nowwidget.core.LogRules
import com.jono.nowwidget.core.PlannedNudge
import java.util.concurrent.CopyOnWriteArraySet

/** Everything a redraw needs, loaded once and shared by every layout and the notification. */
class Snapshot(
    val now: Long,
    val cur: Entry?,
    val act: Act?,
    val buttons: List<Act>,
    val people: List<Link>,
    val places: List<Link>,
    /** Where you are now, if you said. */
    val place: Link?,
    val contexts: List<Link>,
    /** What the current activity is part of (a trip, an event); sticky from one entry to the next. */
    val context: Link?,
    /** The current activity has a note or feeling on it. */
    val curHasMoments: Boolean,
    val scores: Map<Long, Double>,
    val smart: Boolean,
    val today: List<Pair<Act, Long>>,
    val todayTotal: Long,
    val sinceText: String,
    val idleSince: Long?,
    /** What a planned event happening now suggests: it goes on the first button. */
    val nudge: Act? = null,
    /** The check-in flow after a switch (where → who → feeling), while it runs. */
    val flow: FlowState? = null,
    /** The current step's chips, best first (all of them; each size takes what fits). */
    val flowOptions: List<FlowOption> = emptyList(),
) {
    /** The buttons for [n] slots: the usual picks, with the planned one first. */
    fun picks(n: Int): List<Act> = LogRules.withNudge(Store.picks(buttons, act, n, scores, smart), nudge, act, n)
}

object WidgetRenderer {

    private const val PEOPLE_NONE = 0
    private const val PEOPLE_INLINE = 1
    private const val PEOPLE_ROW = 2

    /**
     * Every layout and the size (dp) it's designed for. Android shows the layout whose size fits
     * inside the widget and is closest to it. Typical 4-column grid: 1 cell ≈ 80dp wide, 2 ≈ 180,
     * 3 ≈ 270, 4 ≈ 360; rows ≈ 70–100dp. The app's Your widgets section lists the real sizes.
     */
    private enum class Kind(
        val layout: Int,
        val size: SizeF,
        val label: String,
        val cols: Int = 0,
        val rows: Int = 0,
        val headDp: Float = 0f,
        val peopleMode: Int = PEOPLE_NONE,
        val peopleMax: Int = 0,
        val lines: Int = 0,
        val expands: Boolean = true,
        /** The head line has room to say where you are. */
        val placeInHead: Boolean = true,
        val placesMax: Int = 0,
        /** The head line has room to say what context you're in. */
        val contextInHead: Boolean = false,
    ) {
        TILE(R.layout.widget_tile, SizeF(50f, 50f), "now only", placeInHead = false),
        STRIP_S(R.layout.widget_strip, SizeF(150f, 50f), "now + 4 buttons", cols = 2, rows = 2, headDp = 96f,
            placeInHead = false),
        STRIP(R.layout.widget_strip, SizeF(220f, 50f), "now + 8 buttons", cols = 4, rows = 2, headDp = 108f,
            placeInHead = false),
        STRIP_L(R.layout.widget_strip, SizeF(320f, 50f), "now + 12 buttons", cols = 6, rows = 2, headDp = 122f,
            placeInHead = false),
        SQUARE(R.layout.widget_block, SizeF(150f, 150f), "now, split, 6 buttons", cols = 3, rows = 2),
        LARGE(R.layout.widget_block, SizeF(220f, 150f), "now, split, people, 10 buttons",
            cols = 5, rows = 2, peopleMode = PEOPLE_INLINE, peopleMax = 3),
        LARGE_W(R.layout.widget_block, SizeF(320f, 150f), "now, split, people, 14 buttons",
            cols = 7, rows = 2, peopleMode = PEOPLE_INLINE, peopleMax = 6),
        TALL(R.layout.widget_block, SizeF(150f, 250f), "now, split, people, 12 buttons",
            cols = 3, rows = 4, peopleMode = PEOPLE_ROW, peopleMax = 4),
        TALL_L(R.layout.widget_block, SizeF(150f, 340f), "now, split, people, 12 buttons, today",
            cols = 3, rows = 4, peopleMode = PEOPLE_ROW, peopleMax = 4, lines = 5),
        MID(R.layout.widget_block, SizeF(220f, 250f), "now, split, people, 15 buttons, today",
            cols = 5, rows = 3, peopleMode = PEOPLE_INLINE, peopleMax = 3, lines = 3),
        XL(R.layout.widget_block, SizeF(320f, 250f), "now, split, people, 21 buttons, today",
            cols = 7, rows = 3, peopleMode = PEOPLE_INLINE, peopleMax = 6, lines = 3),
        XL_TALL(R.layout.widget_block, SizeF(320f, 340f), "everything",
            cols = 7, rows = 4, peopleMode = PEOPLE_INLINE, peopleMax = 6, lines = 6, expands = false, placesMax = 6,
            contextInHead = true),
    }

    /** Not a widget size: the floating panel that small widgets open. */
    private val PANEL_COLS = 5
    private val PANEL_ROWS = 6
    private val PANEL_LINES = 6
    private val PANEL_PLACES = 5
    private val PANEL_CONTEXTS = 4

    /** In-app screens that mirror the data and redraw with it. */
    val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** Call after anything changes: redraws every widget, the notification and open screens. */
    fun refresh(ctx: Context) {
        val s = snapshot(ctx)
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, NowWidget::class.java))
        if (ids.isNotEmpty()) mgr.updateAppWidget(ids, build(ctx, s))
        Notifier.update(ctx, s)
        listeners.forEach { it() }
    }

    fun snapshot(ctx: Context): Snapshot {
        val now = System.currentTimeMillis()
        val acts = Store.allActs(ctx)
        val byId = acts.associateBy { it.id }
        val cur = Store.current(ctx)
        val idle = Store.idleSince(ctx)
        val links = Store.allLinks(ctx)
        val spans = Store.spans(ctx, Fmt.today(), now)
        val totals = HashMap<Long, Long>()
        spans.forEach { totals[it.entry.activityId] = (totals[it.entry.activityId] ?: 0L) + it.ms }
        val today = totals.entries
            .mapNotNull { (id, ms) -> byId[id]?.let { it to ms } }
            .sortedByDescending { it.second }
        val buttons = acts.filter { it.show && !it.archived }
        val flow = CheckIn.current(ctx, cur, links, now)
        return Snapshot(
            now = now,
            cur = cur,
            act = cur?.let { byId[it.activityId] },
            buttons = buttons,
            // Links the vault scan made are chips only once pinned or used; yours always are.
            people = links.filter { it.kind == LinkKind.PERSON && !it.archived && it.chip },
            places = links.filter { it.kind == LinkKind.PLACE && !it.archived && it.chip },
            place = cur?.let { c -> links.firstOrNull { it.kind == LinkKind.PLACE && it.id in c.links } },
            contexts = links.filter { it.kind == LinkKind.CONTEXT && !it.archived && it.chip },
            context = cur?.contextId?.let { id -> links.firstOrNull { it.id == id } },
            curHasMoments = cur != null && Store.hasMoments(ctx, cur.id),
            scores = Store.scores(ctx, now),
            smart = Prefs.smartOrder(ctx),
            today = today,
            todayTotal = today.sumOf { it.second },
            sinceText = (cur?.start ?: idle)?.let { Fmt.since(ctx, it) } ?: "",
            idleSince = idle,
            nudge = nudge(ctx, now, buttons, byId),
            flow = flow,
            flowOptions = if (flow == null) emptyList() else flowOptions(ctx, flow, links, now),
        )
    }

    private fun flowOptions(ctx: Context, flow: FlowState, links: List<Link>, now: Long): List<FlowOption> {
        val scores = if (flow.step == FlowStep.FEELING) emptyMap()
        else CheckInFlow.scores(Store.linkUses(ctx, now - CheckInFlow.HISTORY_MS, flow.entryId), now)
        return CheckInFlow.options(
            flow,
            CheckIn.chipLinks(links, LinkKind.PLACE),
            CheckIn.chipLinks(links, LinkKind.PERSON),
            Store.allMoods(ctx),
            scores,
        ) { moodFace(it) }
    }

    /**
     * The activity a planned event that's on now suggests, from the cached planned events (read in
     * the background; never blocks a redraw). Null when nudges are off or nothing fits.
     */
    private fun nudge(ctx: Context, now: Long, buttons: List<Act>, byId: Map<Long, Act>): Act? {
        return try {
            if (!Prefs.calNudge(ctx)) return null
            val planned = PlannedCache.current(ctx, now)
            if (PlannedNudge.happeningNow(planned, now).isEmpty()) return null
            val usable = buttons.map { it.id }.toSet()
            val areas = PlannedCache.areas
            val areaOf = byId.mapValues { (_, a) -> areas[a.group.trim()] }
            val fallback = PlannedNudge.mostUsed(Store.actUses(ctx, now).filterKeys { it in usable }, areaOf)
            PlannedNudge.pick(now, planned, Prefs.calRules(ctx), fallback, usable)?.let { byId[it] }
        } catch (e: Exception) {
            null
        }
    }

    private fun build(ctx: Context, s: Snapshot): RemoteViews {
        val bar = barBitmap(s)
        return RemoteViews(Kind.values().associate { it.size to fill(ctx, it, s, bar) })
    }

    /** The floating panel: every button, people, places and today's breakdown. */
    fun buildPanel(ctx: Context): RemoteViews = buildPanel(ctx, snapshot(ctx))

    fun buildPanel(ctx: Context, s: Snapshot): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_block)
        head(ctx, rv, s, showSince = true, withTotal = true, withPlace = true, withContext = true)
        rv.setOnClickPendingIntent(R.id.head, openApp(ctx))
        bar(rv, barBitmap(s))
        val flow = s.flow
        if (flow != null) {
            // The whole step at full size: this is where the 1×1 and the small strips send you.
            val view = CheckInFlow.block(flow, s.act?.name ?: "", s.flowOptions, PANEL_COLS, PANEL_ROWS)
            rv.setTextViewText(R.id.cur_name, view.head)
            clear(rv, R.id.people_inline)
            clear(rv, R.id.people_row)
            clear(rv, R.id.places_row)
            clear(rv, R.id.context_row)
            flowGrid(ctx, rv, R.id.grid, R.layout.row, R.layout.slot_big, view)
            breakdown(ctx, rv, s, 0)
            return rv
        }
        clear(rv, R.id.people_inline)
        people(ctx, rv, R.id.people_row, s, 5, names = true)
        places(ctx, rv, R.id.places_row, s, PANEL_PLACES)
        contexts(ctx, rv, R.id.context_row, s, PANEL_CONTEXTS)
        grid(ctx, rv, R.id.grid, R.layout.row, R.layout.slot_big, s, PANEL_COLS, PANEL_ROWS)
        breakdown(ctx, rv, s, PANEL_LINES)
        return rv
    }

    private fun fill(ctx: Context, k: Kind, s: Snapshot, barBmp: Bitmap?): RemoteViews {
        val rv = RemoteViews(ctx.packageName, k.layout)
        val isBlock = k.layout == R.layout.widget_block
        head(ctx, rv, s, showSince = k != Kind.TILE, withTotal = isBlock, withPlace = k.placeInHead,
            withContext = k.contextInHead)
        rv.setOnClickPendingIntent(R.id.head, if (k.expands) openPanel(ctx) else openApp(ctx))
        val flow = s.flow
        if (k == Kind.TILE) tile(ctx, rv, s)
        if (flow != null && k != Kind.TILE) {
            fillFlow(ctx, k, rv, s, flow, barBmp)
            return rv
        }

        if (k.layout == R.layout.widget_strip) {
            rv.setViewLayoutWidth(R.id.head, k.headDp, TypedValue.COMPLEX_UNIT_DIP)
            grid(ctx, rv, R.id.slots, R.layout.row, R.layout.slot_compact, s, k.cols, k.rows)
        }
        if (isBlock) {
            // Sizes share this layout and Android may reapply one onto another's views, so every
            // optional part is set either way.
            bar(rv, barBmp)
            if (k.peopleMode == PEOPLE_INLINE) people(ctx, rv, R.id.people_inline, s, k.peopleMax, names = false)
            else clear(rv, R.id.people_inline)
            if (k.peopleMode == PEOPLE_ROW) people(ctx, rv, R.id.people_row, s, k.peopleMax, names = false)
            else clear(rv, R.id.people_row)
            places(ctx, rv, R.id.places_row, s, k.placesMax)
            clear(rv, R.id.context_row) // context chips are only in the expand panel
            grid(ctx, rv, R.id.grid, R.layout.row, R.layout.slot, s, k.cols, k.rows)
            breakdown(ctx, rv, s, k.lines)
        }
        return rv
    }

    /**
     * The 1×1: in the flow it shows the question and one chip that accepts the default ("📍 Loft?");
     * the question opens the panel with the whole step. Otherwise the chip is hidden.
     */
    private fun tile(ctx: Context, rv: RemoteViews, s: Snapshot) {
        val flow = s.flow
        if (flow == null) {
            rv.setViewVisibility(R.id.flow_chip, View.GONE)
            return
        }
        val t = CheckInFlow.tile(flow, s.flowOptions)
        rv.setTextViewText(R.id.cur_name, t.question)
        rv.setViewVisibility(R.id.cur_elapsed, View.GONE) // room for the chip
        rv.setViewVisibility(R.id.flow_chip, View.VISIBLE)
        rv.setTextViewText(R.id.flow_chip, t.chip)
        rv.setOnClickPendingIntent(R.id.flow_chip, controlIntent(ctx, flow.step, t.control))
    }

    /** A strip or block size while the flow runs: the head asks, the button area holds the step. */
    private fun fillFlow(ctx: Context, k: Kind, rv: RemoteViews, s: Snapshot, flow: FlowState, barBmp: Bitmap?) {
        val name = s.act?.name ?: ""
        if (k.layout == R.layout.widget_strip) {
            val view = CheckInFlow.strip(flow, name, s.flowOptions, k.cols)
            rv.setViewLayoutWidth(R.id.head, k.headDp, TypedValue.COMPLEX_UNIT_DIP)
            rv.setTextViewText(R.id.cur_name, view.head)
            rv.setTextViewText(R.id.cur_since, stepText(view.steps))
            flowGrid(ctx, rv, R.id.slots, R.layout.row, R.layout.slot_compact, view.copy(steps = null))
            return
        }
        val view = CheckInFlow.block(flow, name, s.flowOptions, k.cols, k.rows)
        rv.setTextViewText(R.id.cur_name, view.head)
        bar(rv, barBmp)
        // The step has the room; every optional part is still set, since sizes share this layout.
        clear(rv, R.id.people_inline)
        clear(rv, R.id.people_row)
        clear(rv, R.id.places_row)
        clear(rv, R.id.context_row)
        flowGrid(ctx, rv, R.id.grid, R.layout.row, R.layout.slot, view)
        breakdown(ctx, rv, s, 0)
    }

    /** "Where · Who · Feeling" with the current step in bold. */
    private fun stepText(steps: List<Pair<FlowStep, Boolean>>?): CharSequence {
        val b = SpannableStringBuilder()
        steps.orEmpty().forEachIndexed { i, (step, now) ->
            if (i > 0) b.append(" · ")
            val from = b.length
            b.append(step.title)
            if (now) b.setSpan(StyleSpan(Typeface.BOLD), from, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return b
    }

    /** Fills [container] with the step line (if [view] has one) and the flow's rows of cells. */
    fun flowGrid(ctx: Context, rv: RemoteViews, container: Int, rowLayout: Int, slotLayout: Int, view: FlowView) {
        rv.removeAllViews(container)
        rv.setViewVisibility(container, View.VISIBLE)
        if (view.steps != null) {
            val line = RemoteViews(ctx.packageName, R.layout.flow_steps)
            line.setTextViewText(R.id.flow_steps, stepText(view.steps))
            rv.addView(container, line)
        }
        view.rows.forEach { cells ->
            val row = RemoteViews(ctx.packageName, rowLayout)
            flowRow(ctx, row, R.id.row, slotLayout, view.step, cells)
            rv.addView(container, row)
        }
    }

    /** Adds one row's cells to [row] (a horizontal container). */
    fun flowRow(ctx: Context, rv: RemoteViews, row: Int, slotLayout: Int, step: FlowStep, cells: List<FlowCell>) {
        cells.forEach { rv.addView(row, flowCell(ctx, slotLayout, step, it)) }
    }

    private fun flowCell(ctx: Context, layout: Int, step: FlowStep, cell: FlowCell): RemoteViews {
        val v = RemoteViews(ctx.packageName, layout)
        val o = cell.option
        val c = cell.control
        when {
            o != null -> {
                v.setViewVisibility(R.id.slot_root, View.VISIBLE)
                v.setTextViewText(R.id.slot_emoji, o.emoji)
                v.setViewVisibility(R.id.slot_emoji, if (o.emoji.isBlank()) View.GONE else View.VISIBLE)
                v.setTextViewText(R.id.slot_label, o.label)
                v.setViewVisibility(R.id.slot_label, if (o.label.isBlank()) View.GONE else View.VISIBLE)
                v.setInt(R.id.slot_root, "setBackgroundResource", if (o.on) R.drawable.slot_on else R.drawable.slot_off)
                v.setOnClickPendingIntent(R.id.slot_root, optionIntent(ctx, step, o))
            }
            c != null -> {
                v.setViewVisibility(R.id.slot_root, View.VISIBLE)
                val note = c == FlowControl.NOTE
                v.setTextViewText(R.id.slot_emoji, if (note) c.label else "")
                v.setViewVisibility(R.id.slot_emoji, if (note) View.VISIBLE else View.GONE)
                v.setTextViewText(R.id.slot_label, if (note) "Note" else c.label)
                v.setViewVisibility(R.id.slot_label, View.VISIBLE)
                v.setInt(R.id.slot_root, "setBackgroundResource", R.drawable.slot_off)
                v.setOnClickPendingIntent(R.id.slot_root, controlIntent(ctx, step, c))
            }
            else -> v.setViewVisibility(R.id.slot_root, View.INVISIBLE)
        }
        return v
    }

    /** On the feeling step the chips are mood faces (mood ids); before it, links. */
    private fun optionIntent(ctx: Context, step: FlowStep, o: FlowOption): PendingIntent =
        NowWidget.flowIntent(ctx, step, if (step == FlowStep.FEELING) "mood" else "pick", o.id)

    /** A flow button: 📝 opens the check-in box (and ends the flow there); the rest go to the widget. */
    fun controlIntent(ctx: Context, step: FlowStep, c: FlowControl): PendingIntent =
        if (c == FlowControl.NOTE) openNote(ctx, fromFlow = true) else NowWidget.flowIntent(ctx, step, c.key, 0)

    private fun head(
        ctx: Context,
        rv: RemoteViews,
        s: Snapshot,
        showSince: Boolean,
        withTotal: Boolean,
        withPlace: Boolean,
        withContext: Boolean = false,
    ) {
        val cur = s.cur
        val act = s.act
        // A soft glow whenever nothing is on, as a nudge to pick something.
        rv.setInt(R.id.root, "setBackgroundResource",
            if (cur == null) R.drawable.widget_bg_idle else R.drawable.widget_bg)
        rv.setTextViewText(R.id.cur_emoji, act?.emoji ?: "⭕")
        rv.setTextViewText(R.id.cur_name, act?.name ?: "Nothing")
        val since = cur?.start ?: s.idleSince
        if (since != null) {
            rv.setViewVisibility(R.id.cur_elapsed, View.VISIBLE)
            rv.setChronometer(R.id.cur_elapsed, SystemClock.elapsedRealtime() - (s.now - since), null, true)
        } else {
            rv.setViewVisibility(R.id.cur_elapsed, View.GONE)
        }
        if (showSince) rv.setTextViewText(R.id.cur_since, sinceLine(s, withTotal, withPlace, withContext))

        // The note button lights up once the current activity has a note or feeling.
        rv.setInt(R.id.btn_note, "setBackgroundResource",
            if (s.curHasMoments) R.drawable.slot_on else R.drawable.slot_off)
        rv.setOnClickPendingIntent(R.id.btn_note, openNote(ctx))

        ClaudeLink.bind(ctx, rv, R.id.claude, room = showSince)  // not on the 1×1 tile
        val updater = if (UpdateStatus.available(ctx)) UpdateStatus.updaterIntent(ctx) else null
        rv.setViewVisibility(R.id.update_symbol, if (updater != null) View.VISIBLE else View.GONE)
        if (updater != null) {
            rv.setOnClickPendingIntent(R.id.update_symbol, PendingIntent.getActivity(
                ctx, 4, updater, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
        }
    }

    /** Most changeable first, so a narrow widget clips the least useful part. */
    private fun sinceLine(s: Snapshot, withTotal: Boolean, withPlace: Boolean, withContext: Boolean): String {
        val cur: Entry = s.cur ?: return s.sinceText.ifBlank { "Tap a button to start" }
        val parts = ArrayList<String>()
        val names = s.people.filter { it.id in cur.links }.map { it.title }
        if (names.isNotEmpty()) parts.add(names.joinToString(", "))
        val place = s.place
        if (withPlace && place != null) parts.add(if (place.emoji.isBlank()) "at " + place.title else place.label)
        val context = s.context
        if (withContext && context != null) parts.add(if (context.emoji.isBlank()) "in " + context.title else context.label)
        parts.add(s.sinceText)
        if (withTotal && s.todayTotal > 0) parts.add(Fmt.dur(s.todayTotal) + " today")
        return parts.joinToString(" · ")
    }

    private fun bar(rv: RemoteViews, bmp: Bitmap?) {
        if (bmp == null) {
            rv.setViewVisibility(R.id.bar, View.GONE)
            return
        }
        rv.setViewVisibility(R.id.bar, View.VISIBLE)
        rv.setImageViewBitmap(R.id.bar, bmp)
    }

    private fun barBitmap(s: Snapshot): Bitmap? {
        if (s.todayTotal <= 0L) return null
        val w = 360
        val h = 6
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        var x = 0f
        s.today.forEach { (a, ms) ->
            paint.color = a.color
            val seg = w.toFloat() * ms.toFloat() / s.todayTotal.toFloat()
            canvas.drawRect(x, 0f, x + seg, h.toFloat(), paint)
            x += seg
        }
        return bmp
    }

    /** Fills [container] with rows of buttons. Rows grow to share whatever height there is. */
    fun grid(
        ctx: Context,
        rv: RemoteViews,
        container: Int,
        rowLayout: Int,
        slotLayout: Int,
        s: Snapshot,
        cols: Int,
        rows: Int,
    ) {
        // Android can reapply an update onto the views already showing, so always clear first.
        rv.removeAllViews(container)
        val picks = s.picks(cols * rows)
        picks.chunked(cols).forEach { chunk ->
            val row = RemoteViews(ctx.packageName, rowLayout)
            chunk.forEach { a -> row.addView(R.id.row, slot(ctx, slotLayout, a, a.id == s.act?.id)) }
            repeat(cols - chunk.size) {
                val filler = RemoteViews(ctx.packageName, slotLayout)
                filler.setViewVisibility(R.id.slot_root, View.INVISIBLE)
                row.addView(R.id.row, filler)
            }
            rv.addView(container, row)
        }
    }

    fun slot(ctx: Context, layout: Int, a: Act, on: Boolean): RemoteViews {
        val v = RemoteViews(ctx.packageName, layout)
        v.setTextViewText(R.id.slot_emoji, a.emoji)
        v.setTextViewText(R.id.slot_label, a.name)
        v.setInt(R.id.slot_root, "setBackgroundResource", if (on) R.drawable.slot_on else R.drawable.slot_off)
        v.setOnClickPendingIntent(R.id.slot_root, NowWidget.tapIntent(ctx, a.id))
        return v
    }

    private fun clear(rv: RemoteViews, container: Int) {
        rv.removeAllViews(container)
        rv.setViewVisibility(container, View.GONE)
    }

    fun people(ctx: Context, rv: RemoteViews, container: Int, s: Snapshot, max: Int, names: Boolean) =
        chips(ctx, rv, container, s, s.people, max, names)

    /** Where you are: tap a place to say you're there, tap it again to clear. */
    private fun places(ctx: Context, rv: RemoteViews, container: Int, s: Snapshot, max: Int) =
        chips(ctx, rv, container, s, s.places, max, names = true)

    /** What this is part of (a trip, an event): tap to set it on what you're doing now, again to clear. */
    private fun contexts(ctx: Context, rv: RemoteViews, container: Int, s: Snapshot, max: Int) =
        chips(ctx, rv, container, s, s.contexts, max, names = true) { l, cur -> l.id == cur.contextId }

    private fun chips(
        ctx: Context,
        rv: RemoteViews,
        container: Int,
        s: Snapshot,
        items: List<Link>,
        max: Int,
        names: Boolean,
        isOn: (Link, Entry) -> Boolean = { l, cur -> l.id in cur.links },
    ) {
        rv.removeAllViews(container)
        val cur = s.cur
        if (items.isEmpty() || cur == null || max <= 0) {
            rv.setViewVisibility(container, View.GONE)
            return
        }
        rv.setViewVisibility(container, View.VISIBLE)
        // The ones you've marked stay visible even if there are more than there's room for.
        items.sortedByDescending { isOn(it, cur) }.take(max).forEach { p ->
            val chip = RemoteViews(ctx.packageName, R.layout.chip)
            chip.setTextViewText(R.id.chip, if (names) p.label else p.badge)
            chip.setInt(R.id.chip, "setBackgroundResource",
                if (isOn(p, cur)) R.drawable.slot_on else R.drawable.slot_off)
            chip.setOnClickPendingIntent(R.id.chip, NowWidget.linkIntent(ctx, p.id))
            rv.addView(container, chip)
        }
    }

    private fun breakdown(ctx: Context, rv: RemoteViews, s: Snapshot, lines: Int) {
        rv.removeAllViews(R.id.breakdown)
        if (s.today.isEmpty() || lines <= 0) {
            rv.setViewVisibility(R.id.breakdown, View.GONE)
            return
        }
        rv.setViewVisibility(R.id.breakdown, View.VISIBLE)
        s.today.take(lines).forEach { (a, ms) ->
            val row = RemoteViews(ctx.packageName, R.layout.breakdown_row)
            row.setTextViewText(R.id.b_name, a.emoji + " " + a.name)
            row.setTextViewText(R.id.b_time, Fmt.dur(ms))
            row.setOnClickPendingIntent(R.id.b_root, openApp(ctx))
            rv.addView(R.id.breakdown, row)
        }
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 1,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openPanel(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 2,
        Intent(ctx, PanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** [fromFlow]: the flow's 📝, which ends the flow and opens a new check-in for the new activity. */
    private fun openNote(ctx: Context, fromFlow: Boolean = false): PendingIntent = PendingIntent.getActivity(
        ctx, if (fromFlow) 5 else 3,
        Intent(ctx, NoteActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(NoteActivity.EXTRA_FROM_FLOW, fromFlow),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Same rule Android uses to pick a layout, so the app can say which one each widget gets. */
    private fun bestFit(w: Float, h: Float): Kind {
        val fitting = Kind.values().filter { it.size.width <= w && it.size.height <= h }
        return fitting.minByOrNull {
            val dw = w - it.size.width
            val dh = h - it.size.height
            dw * dw + dh * dh
        } ?: Kind.TILE
    }

    fun describeSizes(ctx: Context): String {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, NowWidget::class.java))
        if (ids.isEmpty()) return "No widget on your home screen yet."
        return ids.joinToString("\n") { id ->
            val o = mgr.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            "• ${w}×${h} dp → ${bestFit(w.toFloat(), h.toFloat()).label}"
        }
    }
}
