package com.jono.nowwidget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.icu.text.BreakIterator
import android.text.format.DateFormat
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.Date

object Fmt {
    fun startOfDay(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** Next midnight, by the calendar, so days with a clock change come out right. */
    fun addDays(dayStart: Long, days: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = dayStart
        c.add(Calendar.DAY_OF_YEAR, days)
        return c.timeInMillis
    }

    fun dur(ms: Long): String {
        val m = ms / 60_000L
        return when {
            m < 1L -> "<1m"
            m < 60L -> "${m}m"
            m % 60L == 0L -> "${m / 60L}h"
            else -> "${m / 60L}h ${m % 60L}m"
        }
    }

    fun time(ctx: Context, ms: Long): String = DateFormat.getTimeFormat(ctx).format(Date(ms))

    fun day(ms: Long): String = DateFormat.format("EEE, MMM d", Date(ms)).toString()

    fun since(ctx: Context, start: Long): String {
        val today = startOfDay(System.currentTimeMillis())
        val prefix = if (start < today) DateFormat.format("EEE ", Date(start)).toString() else ""
        return "since " + prefix + time(ctx, start)
    }

    /** Just the first emoji of whatever was typed, including multi-part ones. */
    fun firstGrapheme(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return ""
        val it = BreakIterator.getCharacterInstance()
        it.setText(t)
        val end = it.next()
        return if (end == BreakIterator.DONE) t else t.substring(0, end)
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.heading(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 18f
    setTypeface(typeface, Typeface.BOLD)
    setPadding(0, dp(24), 0, dp(6))
}

fun Context.note(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 13f
    alpha = 0.7f
    setPadding(0, dp(4), 0, dp(8))
}

/** "🎨 Hobby ......... 2h 10m", with a bar showing its share. */
fun Context.statRow(label: String, ms: Long, total: Long, color: Int): View {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(4))
    }
    val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    top.addView(
        TextView(this).apply {
            text = label
            textSize = 15f
            maxLines = 1
        },
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
    )
    top.addView(TextView(this).apply {
        text = Fmt.dur(ms)
        textSize = 15f
    })
    box.addView(top)

    val frac = if (total > 0L) (ms.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    bar.addView(
        View(this).apply { setBackgroundColor(color) },
        LinearLayout.LayoutParams(0, dp(4), frac),
    )
    bar.addView(View(this), LinearLayout.LayoutParams(0, dp(4), 1f - frac))
    box.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply {
        topMargin = dp(2)
    })
    return box
}

/** A day drawn left to right, midnight to midnight, in each activity's color. */
class RibbonView(ctx: Context) : View(ctx) {
    private val paint = Paint()
    private var spans: List<Span> = emptyList()
    private var colors: Map<Long, Int> = emptyMap()
    private var from = 0L
    private var to = 1L

    fun set(spans: List<Span>, colors: Map<Long, Int>, from: Long, to: Long) {
        this.spans = spans
        this.colors = colors
        this.from = from
        this.to = to
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val range = (to - from).toFloat()
        paint.color = Color.argb(40, 128, 128, 128)
        canvas.drawRect(0f, 0f, w, h, paint)
        spans.forEach { s ->
            paint.color = colors[s.entry.activityId] ?: Color.GRAY
            val a = w * (s.from - from).toFloat() / range
            val b = w * (s.to - from).toFloat() / range
            canvas.drawRect(a, 0f, b, h, paint)
        }
    }
}
