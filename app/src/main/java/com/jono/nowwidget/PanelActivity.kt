package com.jono.nowwidget

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ScrollView
import com.jono.nowwidget.core.FlowStep
import com.jono.nowwidget.core.FreeWrite
import com.jono.nowwidget.core.FlowTap
import com.jono.nowwidget.core.LinkKind
import java.util.Calendar
import kotlin.math.min

/**
 * The expand panel: tap a small widget and this floats over the home screen. It shows what you're
 * doing in sections (Doing, At, With, Feeling; see [PanelBody]) that scroll, or, while the check-in
 * flow runs, the flow itself (the widget's own RemoteViews, at full size). Tap outside to close.
 */
class PanelActivity : Activity() {
    private lateinit var card: FrameLayout
    /** A ＋ was tapped: the flow step's or the link kind's picker opens once the panel shows. */
    private var pendingFind: String? = null
    private var pendingAdd: String? = null
    private var pendingSetTime = false
    private val redraw: () -> Unit = { runOnUiThread { onChanged() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = resources.displayMetrics
        val dp = dm.density
        val width = min(dm.widthPixels - (32 * dp).toInt(), (420 * dp).toInt())
        val height = min(dm.heightPixels - (160 * dp).toInt(), (560 * dp).toInt())

        val root = FrameLayout(this).apply { setOnClickListener { finish() } }
        card = FrameLayout(this).apply { isClickable = true } // taps inside don't close it
        root.addView(card, FrameLayout.LayoutParams(width, height, Gravity.CENTER))
        setContentView(root)
        if (savedInstanceState == null) {
            pendingFind = intent?.getStringExtra(EXTRA_FIND)
            pendingAdd = intent?.getStringExtra(EXTRA_ADD)
            pendingSetTime = intent?.getBooleanExtra(EXTRA_SET_TIME, false) == true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingFind = intent.getStringExtra(EXTRA_FIND)
        pendingAdd = intent.getStringExtra(EXTRA_ADD)
        pendingSetTime = intent.getBooleanExtra(EXTRA_SET_TIME, false)
    }

    override fun onResume() {
        super.onResume()
        WidgetRenderer.listeners.add(redraw)
        if (!render()) {
            finish()
            return
        }
        val find = pendingFind
        val add = pendingAdd
        val setTime = pendingSetTime
        pendingFind = null
        pendingAdd = null
        pendingSetTime = false
        if (find != null) find(find) else if (add != null) add(add) else if (setTime) setTime()
    }

    /**
     * "Set time…": when you really started. A time later than now means yesterday (just after
     * midnight). Same rules as the chips (never past now, never deleting the entry before); the
     * confirm names what it cuts and by how much.
     */
    private fun setTime() {
        val cur = Store.current(this) ?: return
        val c = Calendar.getInstance().apply { timeInMillis = cur.start }
        TimePickerDialog(this, { _, h, m ->
            val now = System.currentTimeMillis()
            val pick = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, h)
                set(Calendar.MINUTE, m)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (pick.timeInMillis > now) pick.add(Calendar.DAY_OF_MONTH, -1)
            val shift = Store.previewShiftTo(this, pick.timeInMillis, now) ?: return@TimePickerDialog
            if (!shift.changed) return@TimePickerDialog
            val acts = Store.allActs(this).associateBy { it.id }
            val act = acts[cur.activityId]
            val prevAct = shift.prevActivityId?.let { acts[it] }
            val what = FreeWrite.name(cur.title, act)
            val cut = when {
                shift.prevIdle || prevAct == null -> ""
                shift.clamped -> " It stops at the start of " + prevAct.emoji + " " + prevAct.name + ", which is kept as one minute."
                shift.cuts -> " This cuts " + Fmt.dur(shift.cutMs) + " from " + prevAct.emoji + " " + prevAct.name + "."
                else -> " " + prevAct.name + " gets " + Fmt.dur(shift.newStart - shift.oldStart) + " back."
            }
            AlertDialog.Builder(this)
                .setMessage("Start " + what + " at " + Fmt.time(this, shift.newStart) + "?" + cut)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Set") { _, _ ->
                    StartControls.apply(this, shift)
                    WidgetRenderer.refresh(this)
                }
                .show()
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), DateFormat.is24HourFormat(this)).show()
    }

    /**
     * A people / places / contexts row's ＋: find any one (vault notes and aliases too) or add a
     * new name, and put it on what you're doing now: a person joins, a place or context replaces.
     */
    private fun add(kind: String) {
        if (kind !in LinkKind.ALL) return
        pickLink("Find or add a " + kindName(kind), pickKinds(kind), kind) { id ->
            val cur = Store.current(this) ?: return@pickLink
            when (kind) {
                LinkKind.PERSON -> if (id !in cur.links) Store.toggleLink(this, id)
                LinkKind.PLACE -> Store.setPlace(this, cur.id, id)
                else -> Store.setContext(this, cur.id, id)
            }
            WidgetRenderer.refresh(this)
        }
    }

    /**
     * The flow's "＋": the type-ahead picker over every person or place (vault notes and
     * aliases too). A pick is the same as tapping its chip (a place is chosen and the flow moves
     * on; a person joins the selection), and once it's on an entry it's a chip from then on.
     */
    private fun find(stepName: String) {
        val step = FlowStep.values().firstOrNull { it.name == stepName } ?: return
        val kind = when (step) {
            FlowStep.WHERE -> LinkKind.PLACE
            FlowStep.WHO -> LinkKind.PERSON
            FlowStep.FEELING -> return
        }
        val title = if (kind == LinkKind.PLACE) "Where are you?" else "Who are you with?"
        pickLink(title, pickKinds(kind), kind) { id ->
            CheckIn.tap(this, FlowTap.Pick(id), step)
            WidgetRenderer.refresh(this) // redraws this panel too
        }
    }

    override fun onPause() {
        WidgetRenderer.listeners.remove(redraw)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        finish() // opening the app from the panel closes it
    }

    /** Something changed: the flow, or the sections (which stay open: they're for setting things). */
    private fun onChanged() = show(WidgetRenderer.snapshot(this))

    private fun render(): Boolean {
        show(WidgetRenderer.snapshot(this))
        return true
    }

    companion object {
        /** A flow step's name: its ＋ was tapped. */
        const val EXTRA_FIND = "find"
        /** A link kind: a people / places / contexts row's ＋ was tapped. */
        const val EXTRA_ADD = "add"
        /** The Started row's "Set time…". */
        const val EXTRA_SET_TIME = "set_time"
    }

    private var scroll: ScrollView? = null

    private fun show(s: Snapshot) {
        if (isFinishing) return
        val y = scroll?.scrollY ?: 0
        card.removeAllViews()
        if (s.flow != null) {
            scroll = null
            card.addView(WidgetRenderer.buildPanel(this, s).apply(this, card))
            return
        }
        // The sections: ordinary views, so the panel can scroll when it's taller than the screen.
        val sv = ScrollView(this).apply { setBackgroundResource(R.drawable.widget_bg) }
        sv.addView(PanelBody(this, s, { setTime() }, { add(it) }).view)
        card.addView(sv)
        scroll = sv
        sv.post { sv.scrollTo(0, y) }
    }

}
