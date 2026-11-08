package com.jono.nowwidget

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import kotlin.math.min

/**
 * The expand panel: tap a small widget and this floats over the home screen with every button.
 * Picking a different activity closes it. Tap outside to close.
 */
class PanelActivity : Activity() {
    private lateinit var card: FrameLayout
    private var openedOn: Long? = null
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
        render()
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
        if (Store.current(this)?.activityId != openedOn) finish() else render()
    }

    private fun render() {
        if (isFinishing) return
        card.removeAllViews()
        card.addView(WidgetRenderer.buildPanel(this).apply(this, card))
    }
}
