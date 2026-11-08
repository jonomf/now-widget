package com.jono.nowwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.util.concurrent.CopyOnWriteArraySet

/** Everything a redraw needs, loaded once and shared by every layout and the notification. */
class Snapshot(
    val now: Long,
    val cur: Entry?,
    val act: Act?,
    val buttons: List<Act>,
    val people: List<Person>,
    val scores: Map<Long, Double>,
    val smart: Boolean,
    val today: List<Pair<Act, Long>>,
    val todayTotal: Long,
    val sinceText: String,
)

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
    ) {
        TILE(R.layout.widget_tile, SizeF(50f, 50f), "now only"),
        STRIP_S(R.layout.widget_strip, SizeF(150f, 50f), "now + 2 buttons", cols = 2, rows = 1, headDp = 92f),
        STRIP(R.layout.widget_strip, SizeF(220f, 50f), "now + 4 buttons", cols = 4, rows = 1, headDp = 108f),
        STRIP_L(R.layout.widget_strip, SizeF(320f, 50f), "now + 6 buttons", cols = 6, rows = 1, headDp = 122f),
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
            cols = 7, rows = 4, peopleMode = PEOPLE_INLINE, peopleMax = 6, lines = 6, expands = false),
    }

    /** Not a widget size: the floating panel that small widgets open. */
    private val PANEL_COLS = 5
    private val PANEL_ROWS = 6
    private val PANEL_LINES = 6

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
        val spans = Store.spans(ctx, Fmt.startOfDay(now), now)
        val totals = HashMap<Long, Long>()
        spans.forEach { totals[it.entry.activityId] = (totals[it.entry.activityId] ?: 0L) + it.ms }
        val today = totals.entries
            .mapNotNull { (id, ms) -> byId[id]?.let { it to ms } }
            .sortedByDescending { it.second }
        return Snapshot(
            now = now,
            cur = cur,
            act = cur?.let { byId[it.activityId] },
            buttons = acts.filter { it.show && !it.archived },
            people = Store.allPeople(ctx).filter { !it.archived },
            scores = Store.scores(ctx, now),
            smart = Prefs.smartOrder(ctx),
            today = today,
            todayTotal = today.sumOf { it.second },
            sinceText = cur?.let { Fmt.since(ctx, it.start) } ?: "",
        )
    }

    private fun build(ctx: Context, s: Snapshot): RemoteViews {
        val bar = barBitmap(s)
        return RemoteViews(Kind.values().associate { it.size to fill(ctx, it, s, bar) })
    }

    /** The floating panel: every button, people and today's breakdown. */
    fun buildPanel(ctx: Context): RemoteViews {
        val s = snapshot(ctx)
        val rv = RemoteViews(ctx.packageName, R.layout.widget_block)
        head(ctx, rv, s, showSince = true, withTotal = true)
        rv.setOnClickPendingIntent(R.id.head, openApp(ctx))
        bar(rv, barBitmap(s))
        people(ctx, rv, R.id.people_row, s, 5, names = true)
        grid(ctx, rv, R.id.grid, R.layout.row, R.layout.slot_big, s, PANEL_COLS, PANEL_ROWS)
        breakdown(ctx, rv, s, PANEL_LINES)
        return rv
    }

    private fun fill(ctx: Context, k: Kind, s: Snapshot, barBmp: Bitmap?): RemoteViews {
        val rv = RemoteViews(ctx.packageName, k.layout)
        val isBlock = k.layout == R.layout.widget_block
        head(ctx, rv, s, showSince = k != Kind.TILE, withTotal = isBlock)
        rv.setOnClickPendingIntent(R.id.head, if (k.expands) openPanel(ctx) else openApp(ctx))

        if (k.layout == R.layout.widget_strip) {
            rv.setViewLayoutWidth(R.id.head, k.headDp, TypedValue.COMPLEX_UNIT_DIP)
            Store.picks(s.buttons, s.act, k.cols, s.scores, s.smart).forEach { a ->
                rv.addView(R.id.slots, slot(ctx, R.layout.slot, a, a.id == s.act?.id))
            }
        }
        if (isBlock) {
            bar(rv, barBmp)
            when (k.peopleMode) {
                PEOPLE_INLINE -> people(ctx, rv, R.id.people_inline, s, k.peopleMax, names = false)
                PEOPLE_ROW -> people(ctx, rv, R.id.people_row, s, k.peopleMax, names = false)
            }
            grid(ctx, rv, R.id.grid, R.layout.row, R.layout.slot, s, k.cols, k.rows)
            if (k.lines > 0) breakdown(ctx, rv, s, k.lines)
        }
        return rv
    }

    private fun head(ctx: Context, rv: RemoteViews, s: Snapshot, showSince: Boolean, withTotal: Boolean) {
        val cur = s.cur
        val act = s.act
        rv.setTextViewText(R.id.cur_emoji, act?.emoji ?: "👆")
        rv.setTextViewText(R.id.cur_name, act?.name ?: "What's up now?")
        if (cur != null) {
            rv.setViewVisibility(R.id.cur_elapsed, View.VISIBLE)
            rv.setChronometer(R.id.cur_elapsed, SystemClock.elapsedRealtime() - (s.now - cur.start), null, true)
        } else {
            rv.setViewVisibility(R.id.cur_elapsed, View.GONE)
        }
        if (showSince) rv.setTextViewText(R.id.cur_since, sinceLine(s, withTotal))
    }

    /** Most changeable first, so a narrow widget clips the least useful part. */
    private fun sinceLine(s: Snapshot, withTotal: Boolean): String {
        if (s.cur == null) return "Tap a button to start"
        val parts = ArrayList<String>()
        val names = s.people.filter { it.id in s.cur.people }.map { it.name }
        if (names.isNotEmpty()) parts.add(names.joinToString(", "))
        parts.add(s.sinceText)
        if (withTotal && s.todayTotal > 0) parts.add(Fmt.dur(s.todayTotal) + " today")
        return parts.joinToString(" · ")
    }

    private fun bar(rv: RemoteViews, bmp: Bitmap?) {
        if (bmp == null) return
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
        val picks = Store.picks(s.buttons, s.act, cols * rows, s.scores, s.smart)
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

    fun people(ctx: Context, rv: RemoteViews, container: Int, s: Snapshot, max: Int, names: Boolean) {
        if (s.people.isEmpty() || s.cur == null || max <= 0) return
        rv.setViewVisibility(container, View.VISIBLE)
        // People you're with stay visible even if there are more people than room.
        val current = s.cur.people
        s.people.sortedByDescending { it.id in current }.take(max).forEach { p ->
            val chip = RemoteViews(ctx.packageName, R.layout.chip)
            val text = if (names) {
                if (p.emoji.isBlank()) p.name else p.emoji + " " + p.name
            } else {
                p.badge
            }
            chip.setTextViewText(R.id.chip, text)
            chip.setInt(R.id.chip, "setBackgroundResource",
                if (p.id in current) R.drawable.slot_on else R.drawable.slot_off)
            chip.setOnClickPendingIntent(R.id.chip, NowWidget.personIntent(ctx, p.id))
            rv.addView(container, chip)
        }
    }

    private fun breakdown(ctx: Context, rv: RemoteViews, s: Snapshot, lines: Int) {
        if (s.today.isEmpty()) return
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
