package com.jono.nowwidget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import com.jono.nowwidget.core.FlowStep
import com.jono.nowwidget.core.FlowTap
import com.jono.nowwidget.core.LinkKind
import kotlin.math.min

/**
 * The expand panel: tap a small widget and this floats over the home screen with every button.
 * Picking a different activity closes it, unless that starts the check-in flow (where / who /
 * feeling): then the panel shows the flow and closes when it's done. Tap outside to close.
 */
class PanelActivity : Activity() {
    private lateinit var card: FrameLayout
    private var openedOn: Long? = null
    /** The flow showed here: when it ends, the panel's job is done. */
    private var flowSeen = false
    /** "🔍 Find…" was tapped for this step: open the picker once the panel shows. */
    private var pendingFind: String? = null
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
        if (savedInstanceState == null) pendingFind = intent?.getStringExtra(EXTRA_FIND)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingFind = intent.getStringExtra(EXTRA_FIND)
    }

    override fun onResume() {
        super.onResume()
        openedOn = Store.current(this)?.activityId
        WidgetRenderer.listeners.add(redraw)
        if (!render()) {
            finish()
            return
        }
        val find = pendingFind
        pendingFind = null
        if (find != null) find(find)
    }

    /**
     * The flow's "🔍 Find…": the type-ahead picker over every person or place (vault notes and
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

    private fun onChanged() {
        val s = WidgetRenderer.snapshot(this)
        if (s.flow != null) {
            openedOn = s.cur?.activityId
            show(s)
            return
        }
        if (s.cur?.activityId != openedOn || flowSeen) finish() else show(s)
    }

    /** False when there's nothing to show any more (the flow it was opened for has ended). */
    private fun render(): Boolean {
        val s = WidgetRenderer.snapshot(this)
        if (s.flow == null && flowSeen) return false
        show(s)
        return true
    }

    companion object {
        const val EXTRA_FIND = "find"
    }

    private fun show(s: Snapshot) {
        if (isFinishing) return
        if (s.flow != null) flowSeen = true
        card.removeAllViews()
        card.addView(WidgetRenderer.buildPanel(this, s).apply(this, card))
    }
}
