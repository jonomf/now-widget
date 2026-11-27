package com.jono.nowwidget

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.text.format.DateFormat
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import com.jono.nowwidget.core.Recap
import java.util.Calendar

/** Settings → "Evening recap": on/off, time, minimum gap, whether no place counts, and "Show recap now". */
class RecapSection(private val a: Activity) {
    val view: LinearLayout = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val time = Button(a)
    private val gap = Button(a)

    init {
        view.addView(a.heading("Evening recap"))
        view.addView(a.note(
            "Once a day, a silent notification when today has gaps (nothing logged), entries to label or no mood yet. " +
                "Tap it to fill them in. It shows counts only, never what you did or who with. " +
                "To make it buzz, change the \"Evening recap\" notification in Android's settings."
        ))
        view.addView(switch("Evening recap", Prefs.recapOn(a)) { on ->
            Prefs.setRecapOn(a, on)
            RecapNotice.schedule(a, fresh = true)
            if (!on) RecapNotice.cancel(a)
        })
        time.setOnClickListener { pickTime() }
        view.addView(time)
        gap.setOnClickListener {
            val c = Recap.GAP_CHOICES
            val now = Prefs.recapSettings(a).minGapMinutes
            Prefs.setRecapMinGap(a, c[(c.indexOf(now) + 1) % c.size])
            refresh()
        }
        view.addView(gap)
        view.addView(switch("Count entries without a place", Prefs.recapSettings(a).countNoPlace) { on ->
            Prefs.setRecapNoPlace(a, on)
        })
        view.addView(a.note("Entries of 30 minutes or more with no place count as unlabelled."))
        view.addView(Button(a).apply {
            text = "Show recap now"
            setOnClickListener { showNow() }
        })
        view.addView(Button(a).apply {
            text = "Open recap"
            setOnClickListener { a.startActivity(Intent(a, RecapActivity::class.java)) }
        })
        refresh()
    }

    fun refresh() {
        val m = Prefs.recapTime(a)
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, m / 60)
            set(Calendar.MINUTE, m % 60)
        }
        time.text = "Time: " + Fmt.time(a, c.timeInMillis)
        gap.text = "Minimum gap: " + Prefs.recapSettings(a).minGapMinutes + " min"
    }

    private fun pickTime() {
        val m = Prefs.recapTime(a)
        TimePickerDialog(a, { _, h, min ->
            Prefs.setRecapTime(a, h * 60 + min)
            RecapNotice.schedule(a, fresh = true)
            refresh()
        }, m / 60, m % 60, DateFormat.is24HourFormat(a)).show()
    }

    /** The notification now, if today has something to fix. */
    private fun showNow() {
        val app = a.applicationContext
        Thread {
            val outcome = try {
                RecapNotice.check(app)
            } catch (e: RuntimeException) {
                null
            }
            a.runOnUiThread {
                val msg = when (outcome) {
                    RecapNotice.Outcome.SHOWN -> "Recap shown"
                    RecapNotice.Outcome.NOTHING_TO_FIX -> "Nothing to fix today"
                    RecapNotice.Outcome.BLOCKED -> "Notifications are off for Now. Turn them on in App info."
                    null -> "Couldn't make the recap"
                }
                Toast.makeText(a, msg, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun switch(label: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(a).apply {
        text = label
        textSize = 16f
        isChecked = on
        setPadding(0, a.dp(10), 0, a.dp(10))
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }
}
