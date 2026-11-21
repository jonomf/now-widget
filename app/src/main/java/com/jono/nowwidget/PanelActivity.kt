package com.jono.nowwidget

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
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
    }

    override fun onResume() {
        super.onResume()
        openedOn = Store.current(this)?.activityId
        WidgetRenderer.listeners.add(redraw)
        if (!render()) finish()
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

    private fun show(s: Snapshot) {
        if (isFinishing) return
        if (s.flow != null) flowSeen = true
        card.removeAllViews()
        card.addView(WidgetRenderer.buildPanel(this, s).apply(this, card))
    }
}
