package com.jono.nowwidget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.icu.text.BreakIterator
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.jono.nowwidget.core.DiaryDay
import com.jono.nowwidget.core.Mood
import com.jono.nowwidget.core.Span
import com.jono.nowwidget.core.formatDuration
import com.jono.nowwidget.core.levelFace
import java.util.Date

object Fmt {
    /** Start of today's diary day (4am to 4am). */
    fun today(): Long = DiaryDay.start(System.currentTimeMillis())

    fun dur(ms: Long): String = formatDuration(ms)

    fun time(ctx: Context, ms: Long): String = DateFormat.getTimeFormat(ctx).format(Date(ms))

    /** "Mon, Nov 16", with the year when it isn't this year. */
    fun day(ms: Long): String {
        val thisYear = DateFormat.format("yyyy", Date()).toString()
        val f = if (DateFormat.format("yyyy", Date(ms)).toString() == thisYear) "EEE, MMM d" else "EEE, MMM d, yyyy"
        return DateFormat.format(f, Date(ms)).toString()
    }

    /** "Jan 4, 2019", for dates that may be years ago. */
    fun date(ms: Long): String = DateFormat.format("MMM d, yyyy", Date(ms)).toString()

    fun since(ctx: Context, start: Long): String {
        val today = today()
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

/** A diary day drawn left to right, 4am to 4am, in each activity's color. */
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

fun moodFace(m: Mood?): String = if (m == null) "" else m.emoji.ifBlank { levelFace(m.level) }

/** A rounded, tappable label that shows whether it's chosen. */
fun Context.chip(label: String, on: Boolean, onTap: (TextView) -> Unit): TextView = TextView(this).apply {
    text = label
    textSize = 15f
    gravity = Gravity.CENTER
    setSingleLine(true)
    setPadding(dp(12), dp(7), dp(12), dp(7))
    setChipOn(this, on)
    setOnClickListener { onTap(this) }
}

fun Context.setChipOn(v: View, on: Boolean) {
    val accent = getColor(android.R.color.system_accent1_500)
    val line = getColor(android.R.color.system_neutral2_400)
    v.background = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        if (on) {
            setColor((accent and 0x00FFFFFF) or (0x66 shl 24))
            setStroke(dp(1), accent)
        } else {
            setColor(0x00000000)
            setStroke(dp(1), (line and 0x00FFFFFF) or (0x88 shl 24))
        }
    }
    v.isSelected = on
}

/** An extra activity ("+ Family"): a lighter highlight than [setChipOn]'s, so the primary stands out. */
fun Context.setChipLight(v: View) {
    val accent = getColor(android.R.color.system_accent1_500)
    v.background = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor((accent and 0x00FFFFFF) or (0x22 shl 24))
        setStroke(dp(1), (accent and 0x00FFFFFF) or (0x99 shl 24))
    }
    v.isSelected = true
}

/** Lays its children out left to right, wrapping onto new lines, like a row of chips. */
class WrapLayout(ctx: Context) : ViewGroup(ctx) {
    private val gap = ctx.dp(6)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val maxW = if (mode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
        else MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val childSpec = MeasureSpec.makeMeasureSpec(
            if (maxW == Int.MAX_VALUE) 0 else maxW,
            if (maxW == Int.MAX_VALUE) MeasureSpec.UNSPECIFIED else MeasureSpec.AT_MOST,
        )
        var x = 0
        var y = 0
        var rowH = 0
        var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            c.measure(childSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0
                y += rowH + gap
                rowH = 0
            }
            x += c.measuredWidth + gap
            widest = maxOf(widest, x - gap)
            rowH = maxOf(rowH, c.measuredHeight)
        }
        val w = if (mode == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec)
        else minOf(widest, maxW) + paddingLeft + paddingRight
        setMeasuredDimension(w, y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0
                y += rowH + gap
                rowH = 0
            }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

/** A small bold label above a group of chips. */
fun Context.label(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 13f
    alpha = 0.7f
    setTypeface(typeface, Typeface.BOLD)
    setPadding(0, dp(12), 0, dp(4))
}
