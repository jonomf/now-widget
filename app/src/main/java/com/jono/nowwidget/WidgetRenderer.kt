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
import android.view.Gravity
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
import com.jono.nowwidget.core.FreeWrite
import com.jono.nowwidget.core.OptionType
import com.jono.nowwidget.core.Link
import com.jono.nowwidget.core.LinkKind
import com.jono.nowwidget.core.LogRules
import com.jono.nowwidget.core.Mentions
import com.jono.nowwidget.core.PlannedNudge
import com.jono.nowwidget.core.StartShift
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
    /** Start time: the entry before the current one (idle counts), and what each chip would do. */
    val prev: Entry? = null,
    val prevAct: Act? = null,
    val offsets: List<Int> = emptyList(),
    val previews: Map<Int, StartShift.Shift> = emptyMap(),
    val askOverMin: Int = 0,
    val armed: StartShift.Armed? = null,
    /** The adjustment that can still be undone, and the head line's note about it. */
    val undo: StartShift.Undo? = null,
    val shiftNote: String? = null,
    val prevLength: String? = null,
    val startInFlow: Boolean = true,
) {
    /** What you're doing, by name: a free-written title, else the activity's name. */
    val name: String get() = FreeWrite.name(cur?.title, act)

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
        /** One-row strips in the check-in flow: head width, chips (cols × rows), controls column width. */
        val flowHeadDp: Float = 0f,
        val flowCols: Int = 0,
        val flowRows: Int = 0,
        val flowCtlDp: Float = 0f,
    ) {
        TILE(R.layout.widget_tile, SizeF(50f, 50f), "now only", placeInHead = false),
        STRIP_S(R.layout.widget_strip, SizeF(150f, 50f), "now + 4 buttons", cols = 2, rows = 2, headDp = 96f,
            placeInHead = false, flowHeadDp = 56f, flowCols = 2, flowRows = 1, flowCtlDp = 56f),
        STRIP(R.layout.widget_strip, SizeF(220f, 50f), "now + 8 buttons", cols = 4, rows = 2, headDp = 108f,
            placeInHead = false, flowHeadDp = 84f, flowCols = 2, flowRows = 2, flowCtlDp = 68f),
        STRIP_L(R.layout.widget_strip, SizeF(320f, 50f), "now + 12 buttons", cols = 6, rows = 2, headDp = 122f,
            placeInHead = false, flowHeadDp = 100f, flowCols = 3, flowRows = 2, flowCtlDp = 84f),
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

    /** The panel's "give time back" chip. */
    private const val START_LATER = 5
    /** The Started row's sizes: the label, a chip (with its ✂), chips per panel row. */
    private const val START_LABEL_DP = 60f
    private const val START_CHIP_DP = 52f
    private const val PANEL_START_CHIPS = 5

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
        // People and places everywhere: Now use ×3 plus the vault's mentions, then name (the flow's
        // own entry doesn't count, so chips keep their order while it runs).
        val ranks = Store.linkRanks(ctx, now, except = flow?.entryId ?: -1L)
        fun ranked(kind: String) = Mentions.ranked(links.filter { it.kind == kind && !it.archived && it.chip }, ranks, { it.id }, { it.title })
        val prev = cur?.let { Store.currentAndBefore(ctx)?.second }
        val prevAct = prev?.let { p -> byId[p.activityId] }
        val offsets = Prefs.startOffsets(ctx)
        val previews = if (cur == null) emptyMap() else (offsets + START_LATER).distinct()
            .associateWith { StartShift.by(cur, prev, it, now) }
        val undo = StartControls.liveUndo(ctx, cur, now)
        val prevName = prevAct?.let { FreeWrite.name(prev.title, it) }
        return Snapshot(
            now = now,
            cur = cur,
            act = cur?.let { byId[it.activityId] },
            buttons = buttons,
            // Links the vault scan made are chips only once pinned or used; yours always are.
            people = ranked(LinkKind.PERSON),
            places = ranked(LinkKind.PLACE),
            place = cur?.let { c -> links.firstOrNull { it.kind == LinkKind.PLACE && it.id in c.links } },
            contexts = ranked(LinkKind.CONTEXT),
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
            flowOptions = if (flow == null) emptyList() else flowOptions(ctx, flow, links, ranks),
            prev = prev,
            prevAct = prevAct,
            offsets = offsets,
            previews = previews,
            askOverMin = Prefs.askOverMin(ctx),
            armed = Prefs.shiftArmed(ctx),
            undo = undo,
            shiftNote = undo?.let { StartShift.feedback(it, prevName, prevAct?.emoji ?: "") },
            prevLength = undo?.let { StartShift.prevLength(it, prevName) },
            startInFlow = Prefs.startInFlow(ctx),
        )
    }

    private fun flowOptions(ctx: Context, flow: FlowState, links: List<Link>, scores: Map<Long, Double>): List<FlowOption> {
        return CheckInFlow.options(
            flow,
            CheckIn.flowLinks(links, LinkKind.PLACE),
            CheckIn.flowLinks(links, LinkKind.PERSON),
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
        startedRow(ctx, rv, R.id.started_box, s, StartedMode.PANEL)
        val flow = s.flow
        if (flow != null) {
            // The whole step at full size: this is where the 1×1 and the small strips send you.
            val view = CheckInFlow.block(flow, s.name, s.flowOptions, PANEL_COLS, PANEL_ROWS)
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
        // Block sizes: the elapsed timer opens the panel, where the Started row is.
        if (isBlock) rv.setOnClickPendingIntent(R.id.cur_elapsed, openPanel(ctx))
        val flow = s.flow
        if (k == Kind.TILE) tile(ctx, rv, s)
        if (flow != null && k != Kind.TILE) {
            fillFlow(ctx, k, rv, s, flow, barBmp)
            return rv
        }

        if (k.layout == R.layout.widget_strip) {
            hideColumn(rv)
            rv.setViewLayoutWidth(R.id.head, k.headDp, TypedValue.COMPLEX_UNIT_DIP)
            grid(ctx, rv, R.id.slots, R.layout.row, R.layout.slot_compact, s, k.cols, k.rows)
        }
        if (isBlock) {
            // Sizes share this layout and Android may reapply one onto another's views, so every
            // optional part is set either way.
            startedRow(ctx, rv, R.id.started_box, s, StartedMode.NONE)
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
     * The 1×1: in the flow the top half shows the question (tap: the panel, with the whole step)
     * and the whole lower half is one button that accepts the default ("📍 Loft?").
     */
    private fun tile(ctx: Context, rv: RemoteViews, s: Snapshot) {
        val flow = s.flow
        if (flow == null) {
            rv.setViewVisibility(R.id.flow_hit, View.GONE)
            rv.setInt(R.id.head, "setGravity", Gravity.CENTER)
            return
        }
        val t = CheckInFlow.tile(flow, s.flowOptions)
        rv.setInt(R.id.head, "setGravity", Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        rv.setTextViewText(R.id.cur_name, t.question)
        rv.setViewVisibility(R.id.cur_elapsed, View.GONE) // room for the button
        rv.setViewVisibility(R.id.flow_hit, View.VISIBLE)
        rv.setTextViewText(R.id.flow_chip, t.chip)
        rv.setOnClickPendingIntent(R.id.flow_hit_area, controlIntent(ctx, flow.step, t.control))
    }

    /** Outside the flow the strips' controls column is hidden. */
    private fun hideColumn(rv: RemoteViews) = rv.setViewVisibility(R.id.flow_controls, View.GONE)

    /** A strip or block size while the flow runs: the head asks, the button area holds the step. */
    private fun fillFlow(ctx: Context, k: Kind, rv: RemoteViews, s: Snapshot, flow: FlowState, barBmp: Bitmap?) {
        val name = s.name
        if (k.layout == R.layout.widget_strip) {
            // Chips in the middle, the controls in their own full-height column at the right edge.
            val view = CheckInFlow.strip(flow, name, s.flowOptions, k.flowCols, k.flowRows)
            rv.setViewLayoutWidth(R.id.head, k.flowHeadDp, TypedValue.COMPLEX_UNIT_DIP)
            rv.setTextViewText(R.id.cur_name, view.head)
            rv.setTextViewText(R.id.cur_since, stepText(view.steps))
            flowGrid(ctx, rv, R.id.slots, R.layout.row, R.layout.slot_compact, view.copy(steps = null), withControls = false)
            column(ctx, rv, flow.step, k.flowCtlDp)
            return
        }
        val view = CheckInFlow.block(flow, name, s.flowOptions, k.cols, k.rows)
        rv.setTextViewText(R.id.cur_name, view.head)
        // "Started" chips at the top of the first step shown (not a step of its own).
        startedRow(ctx, rv, R.id.started_box, s,
            if (s.startInFlow && flow.step == flow.steps.firstOrNull()) StartedMode.FLOW else StartedMode.NONE, k.size.width)
        bar(rv, barBmp)
        // The step has the room; every optional part is still set, since sizes share this layout.
        clear(rv, R.id.people_inline)
        clear(rv, R.id.people_row)
        clear(rv, R.id.places_row)
        clear(rv, R.id.context_row)
        flowGrid(ctx, rv, R.id.grid, R.layout.row, R.layout.slot, view)
        breakdown(ctx, rv, s, 0)
    }

    /** The strips' controls column: the big top button and the one or two below it. */
    private fun column(ctx: Context, rv: RemoteViews, step: FlowStep, widthDp: Float) {
        val col = CheckInFlow.column(step)
        rv.setViewVisibility(R.id.flow_controls, View.VISIBLE)
        rv.setViewLayoutWidth(R.id.flow_controls, widthDp, TypedValue.COMPLEX_UNIT_DIP)
        bindButton(ctx, rv, R.id.flow_top, step, col.top)
        val ids = listOf(R.id.flow_bottom1, R.id.flow_bottom2)
        ids.forEachIndexed { i, id ->
            val c = col.bottom.getOrNull(i)
            if (c == null) rv.setViewVisibility(id, View.GONE) else bindButton(ctx, rv, id, step, c)
        }
    }

    /** A control: a real button, filled when it's Next. */
    private fun bindButton(ctx: Context, rv: RemoteViews, id: Int, step: FlowStep, c: FlowControl) {
        rv.setViewVisibility(id, View.VISIBLE)
        rv.setTextViewText(id, c.label)
        rv.setInt(id, "setBackgroundResource", if (CheckInFlow.primary(c)) R.drawable.slot_on else R.drawable.slot_off)
        rv.setOnClickPendingIntent(id, controlIntent(ctx, step, c))
    }

    /** One control for a row of equal buttons. */
    fun flowButton(ctx: Context, step: FlowStep, c: FlowControl): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.flow_btn)
        bindButton(ctx, v, R.id.flow_btn, step, c)
        return v
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

    /**
     * Fills [container] with the step line (if [view] has one), the rows of chips and, with
     * [withControls], the controls as one full-width row of equal buttons at least 44dp tall.
     */
    fun flowGrid(
        ctx: Context,
        rv: RemoteViews,
        container: Int,
        rowLayout: Int,
        slotLayout: Int,
        view: FlowView,
        withControls: Boolean = true,
    ) {
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
        if (withControls && view.controls.isNotEmpty()) {
            val row = RemoteViews(ctx.packageName, R.layout.flow_ctl_row)
            view.controls.forEach { row.addView(R.id.row, flowButton(ctx, view.step, it)) }
            rv.addView(container, row)
        }
    }

    /** Adds one row's chips to [row] (a horizontal container). */
    fun flowRow(ctx: Context, rv: RemoteViews, row: Int, slotLayout: Int, step: FlowStep, cells: List<FlowCell>) {
        cells.forEach { rv.addView(row, flowCell(ctx, slotLayout, step, it)) }
    }

    private fun flowCell(ctx: Context, layout: Int, step: FlowStep, cell: FlowCell): RemoteViews {
        val v = RemoteViews(ctx.packageName, layout)
        val o = cell.option
        if (o == null) {
            v.setViewVisibility(R.id.slot_root, View.INVISIBLE)
            return v
        }
        v.setViewVisibility(R.id.slot_root, View.VISIBLE)
        v.setTextViewText(R.id.slot_emoji, o.emoji)
        v.setViewVisibility(R.id.slot_emoji, if (o.emoji.isBlank()) View.GONE else View.VISIBLE)
        v.setTextViewText(R.id.slot_label, o.label)
        v.setViewVisibility(R.id.slot_label, if (o.label.isBlank()) View.GONE else View.VISIBLE)
        v.setInt(R.id.slot_root, "setBackgroundResource", if (o.on) R.drawable.slot_on else R.drawable.slot_off)
        v.setOnClickPendingIntent(R.id.slot_root, optionIntent(ctx, step, o))
        return v
    }

    /** Links and faces go to the widget; Find… opens the picker in the panel; the hint opens the app. */
    private fun optionIntent(ctx: Context, step: FlowStep, o: FlowOption): PendingIntent = when (o.type) {
        OptionType.LINK -> NowWidget.flowIntent(ctx, step, "pick", o.id)
        OptionType.MOOD -> NowWidget.flowIntent(ctx, step, "mood", o.id)
        OptionType.PLUS -> openFind(ctx, step)
        OptionType.HINT -> openApp(ctx)
    }

    /** A flow button: 📝 opens the check-in box (and ends the flow there); the rest go to the widget. */
    fun controlIntent(ctx: Context, step: FlowStep, c: FlowControl): PendingIntent =
        if (c == FlowControl.NOTE) openNote(ctx, fromFlow = true) else NowWidget.flowIntent(ctx, step, c.key, 0)

    /** The flow's "＋": the panel, with the type-ahead picker for the step's kind open over it. */
    private fun openFind(ctx: Context, step: FlowStep): PendingIntent = PendingIntent.getActivity(
        ctx, 6 + step.ordinal,
        Intent(ctx, PanelActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(PanelActivity.EXTRA_FIND, step.name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    enum class StartedMode { NONE, FLOW, PANEL, NOTIF }

    /**
     * The start-time row in [container]: a label, then a chip per offset ("−30 ✂", "−60 ⇤",
     * "−30 ⚠"), and per mode: the flow adds "Now ✓" (the default: no change, or undo), the panel
     * adds ＋5, Undo while it's there, and "Set time…". Hidden for [StartedMode.NONE] or when nothing is on.
     */
    fun startedRow(ctx: Context, rv: RemoteViews, container: Int, s: Snapshot, mode: StartedMode, widthDp: Float = 340f) {
        rv.removeAllViews(container)
        val cur = s.cur
        if (mode == StartedMode.NONE || cur == null) {
            rv.setViewVisibility(container, View.GONE)
            return
        }
        rv.setViewVisibility(container, View.VISIBLE)
        // Each chip: its label, whether it's highlighted, and what it does.
        val chips = ArrayList<Triple<String, Boolean, PendingIntent>>()
        val offsets = if (mode == StartedMode.PANEL) s.offsets + START_LATER else s.offsets
        offsets.distinct().forEach { m ->
            val p = s.previews[m] ?: return@forEach
            if (!p.changed && m > 0) return@forEach // nothing to give back
            val armed = StartShift.isArmed(s.armed, cur.id, m, s.now)
            chips.add(Triple(StartShift.chipLabel(m, p, s.askOverMin, armed), armed, NowWidget.shiftIntent(ctx, cur.id, m.toString())))
        }
        val undo = s.undo
        if (mode == StartedMode.PANEL) {
            if (undo != null) chips.add(Triple("Undo", false, NowWidget.shiftIntent(ctx, cur.id, "undo")))
            chips.add(Triple("Set time…", false, openSetTime(ctx)))
        }

        fun row(label: String?, items: List<Triple<String, Boolean, PendingIntent>>) {
            val r = RemoteViews(ctx.packageName, R.layout.started_row)
            r.setTextViewText(R.id.started_label, label ?: "")
            r.setViewVisibility(R.id.started_label, if (label == null) View.GONE else View.VISIBLE)
            items.forEach { (text, on, intent) ->
                val chip = RemoteViews(ctx.packageName, R.layout.chip)
                chip.setTextViewText(R.id.chip, text)
                chip.setInt(R.id.chip, "setBackgroundResource", if (on) R.drawable.slot_on else R.drawable.slot_off)
                chip.setOnClickPendingIntent(R.id.chip, intent)
                r.addView(R.id.started_row, chip)
            }
            rv.addView(container, r)
        }

        if (mode == StartedMode.PANEL) {
            // The panel has room: the time on its own line, then the chips in rows that never clip.
            row("Started " + Fmt.time(ctx, cur.start) + " (" + Fmt.dur(s.now - cur.start) + " ago)" +
                (s.prevLength?.let { " · $it" } ?: ""), emptyList())
            chips.chunked(PANEL_START_CHIPS).forEach { row(null, it) }
            return
        }
        // One line: as many offsets as fit beside "Started" (and "Now ✓" in the flow), nearest first.
        val now = if (mode == StartedMode.FLOW) Triple("Now ✓", undo == null, NowWidget.shiftIntent(ctx, cur.id, "now")) else null
        val room = maxOf(1, ((widthDp - START_LABEL_DP) / START_CHIP_DP).toInt() - (if (now != null) 1 else 0))
        row("Started", chips.take(room) + listOfNotNull(now))
    }

    /** The panel's "Set time…": the panel again, with the time picker open. */
    private fun openSetTime(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 14,
        Intent(ctx, PanelActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(PanelActivity.EXTRA_SET_TIME, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

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
        // A free-written entry goes by its own words; its category's emoji stays beside them.
        rv.setTextViewText(R.id.cur_name, if (cur == null) "Nothing" else s.name)
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
        // A free-written title's category, in the smaller line.
        if (cur.title != null) s.act?.let { parts.add(it.name) }
        val names = s.people.filter { it.id in cur.links }.map { it.title }
        if (names.isNotEmpty()) parts.add(names.joinToString(", "))
        val place = s.place
        if (withPlace && place != null) parts.add(if (place.emoji.isBlank()) "at " + place.title else place.label)
        val context = s.context
        if (withContext && context != null) parts.add(if (context.emoji.isBlank()) "in " + context.title else context.label)
        // Just after a start adjustment: "since 2:15p · ✂ 15m from 🍽️ Eat", for a minute.
        parts.add(s.shiftNote?.let { s.sinceText + " · " + it } ?: s.sinceText)
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
        // ＋ is always the last button: after the activities, or in the last cell when they don't all fit.
        val picks = s.picks(CheckInFlow.itemsBeforePlus(Int.MAX_VALUE, cols * rows))
        val cells: List<Act?> = picks + listOf<Act?>(null)
        cells.chunked(cols).forEach { chunk ->
            val row = RemoteViews(ctx.packageName, rowLayout)
            chunk.forEach { a ->
                row.addView(R.id.row, if (a == null) plusSlot(ctx, slotLayout) else slot(ctx, slotLayout, a, a.id == s.act?.id))
            }
            repeat(cols - chunk.size) {
                val filler = RemoteViews(ctx.packageName, slotLayout)
                filler.setViewVisibility(R.id.slot_root, View.INVISIBLE)
                row.addView(R.id.row, filler)
            }
            rv.addView(container, row)
        }
    }

    /** The activity buttons' ＋: say what you're doing in your own words. */
    private fun plusSlot(ctx: Context, layout: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, layout)
        v.setViewVisibility(R.id.slot_root, View.VISIBLE)
        v.setTextViewText(R.id.slot_emoji, CheckInFlow.PLUS)
        v.setViewVisibility(R.id.slot_emoji, View.VISIBLE)
        v.setTextViewText(R.id.slot_label, "Write")
        v.setViewVisibility(R.id.slot_label, View.VISIBLE)
        v.setInt(R.id.slot_root, "setBackgroundResource", R.drawable.slot_off)
        v.setOnClickPendingIntent(R.id.slot_root, openFreeWrite(ctx))
        return v
    }

    fun slot(ctx: Context, layout: Int, a: Act, on: Boolean): RemoteViews {
        val v = RemoteViews(ctx.packageName, layout)
        v.setViewVisibility(R.id.slot_root, View.VISIBLE)
        v.setTextViewText(R.id.slot_emoji, a.emoji)
        v.setViewVisibility(R.id.slot_emoji, View.VISIBLE)
        v.setTextViewText(R.id.slot_label, a.name)
        v.setViewVisibility(R.id.slot_label, View.VISIBLE)
        v.setInt(R.id.slot_root, "setBackgroundResource", if (on) R.drawable.slot_on else R.drawable.slot_off)
        v.setOnClickPendingIntent(R.id.slot_root, NowWidget.tapIntent(ctx, a.id))
        return v
    }

    private fun clear(rv: RemoteViews, container: Int) {
        rv.removeAllViews(container)
        rv.setViewVisibility(container, View.GONE)
    }

    fun people(ctx: Context, rv: RemoteViews, container: Int, s: Snapshot, max: Int, names: Boolean) =
        chips(ctx, rv, container, s, s.people, max, names, LinkKind.PERSON)

    /** Where you are: tap a place to say you're there, tap it again to clear. */
    private fun places(ctx: Context, rv: RemoteViews, container: Int, s: Snapshot, max: Int) =
        chips(ctx, rv, container, s, s.places, max, names = true, kind = LinkKind.PLACE)

    /** What this is part of (a trip, an event): tap to set it on what you're doing now, again to clear. */
    private fun contexts(ctx: Context, rv: RemoteViews, container: Int, s: Snapshot, max: Int) =
        chips(ctx, rv, container, s, s.contexts, max, names = true, kind = LinkKind.CONTEXT) { l, cur -> l.id == cur.contextId }

    private fun chips(
        ctx: Context,
        rv: RemoteViews,
        container: Int,
        s: Snapshot,
        items: List<Link>,
        max: Int,
        names: Boolean,
        kind: String,
        isOn: (Link, Entry) -> Boolean = { l, cur -> l.id in cur.links },
    ) {
        rv.removeAllViews(container)
        val cur = s.cur
        if (cur == null || max <= 0) {
            rv.setViewVisibility(container, View.GONE)
            return
        }
        rv.setViewVisibility(container, View.VISIBLE)
        // The ones you've marked stay visible even if there are more than there's room for; ＋ is
        // always last and always fits (it takes the last place when they don't all fit).
        val fit = CheckInFlow.itemsBeforePlus(items.size, max)
        items.sortedByDescending { isOn(it, cur) }.take(fit).forEach { p ->
            val chip = RemoteViews(ctx.packageName, R.layout.chip)
            chip.setTextViewText(R.id.chip, if (names) p.label else p.badge)
            chip.setInt(R.id.chip, "setBackgroundResource",
                if (isOn(p, cur)) R.drawable.slot_on else R.drawable.slot_off)
            chip.setOnClickPendingIntent(R.id.chip, NowWidget.linkIntent(ctx, p.id))
            rv.addView(container, chip)
        }
        val plus = RemoteViews(ctx.packageName, R.layout.chip)
        plus.setTextViewText(R.id.chip, CheckInFlow.PLUS)
        plus.setInt(R.id.chip, "setBackgroundResource", R.drawable.slot_off)
        plus.setOnClickPendingIntent(R.id.chip, openAdd(ctx, kind))
        rv.addView(container, plus)
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

    /** The activity buttons' ＋. */
    private fun openFreeWrite(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 9,
        Intent(ctx, FreeWriteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** A people / places / contexts row's ＋: the panel with that kind's picker open. */
    private fun openAdd(ctx: Context, kind: String): PendingIntent = PendingIntent.getActivity(
        ctx, 10 + LinkKind.ALL.indexOf(kind),
        Intent(ctx, PanelActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(PanelActivity.EXTRA_ADD, kind),
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
