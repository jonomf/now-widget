package com.jono.nowwidget

import android.app.Activity
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * The "Hub" part of the settings screen: connecting to the Now hub (a Google Sheet with a small
 * script, shared with the Windows Now widget), turning syncing on, and its status. The network
 * calls run in [HubSync] / [HubClient] on background threads; this only draws.
 */
class HubSection(private val a: Activity) {
    val view: LinearLayout = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val status = TextView(a).apply {
        textSize = 14f
        setTextIsSelectable(true)
        setPadding(0, a.dp(4), 0, a.dp(8))
    }
    private val message = TextView(a).apply {
        textSize = 14f
        setPadding(0, a.dp(4), 0, a.dp(8))
    }
    private val url = EditText(a).apply {
        setSingleLine(true)
        hint = "Web app URL (ends in /exec)"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }
    private val key = EditText(a).apply {
        setSingleLine(true)
        hint = "Hub key"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    private val connect = Button(a)
    private val on = Switch(a)
    private val syncNow = Button(a)
    private val disconnect = Button(a)
    private var drawing = false

    init {
        view.addView(a.heading("Hub (sync with your PC)"))
        view.addView(a.note(
            "Keeps this phone and the Now widget on your Windows PC on one log. Both are equal: a tap on either shows on the other " +
                "at its next sync. The hub is a Google Sheet in your own Google account with a small script (set it up once: " +
                "the now-widget repo's hub/Code.gs has the steps). When syncing is on, your whole log goes to that sheet: " +
                "activities, people and places, notes and feelings. Anyone you share the sheet with can read it. " +
                "This phone syncs a few seconds after each change, when you open the app, and every 15 minutes."
        ))
        view.addView(status)
        view.addView(message)
        view.addView(url)
        view.addView(key)
        connect.text = "Connect"
        connect.setOnClickListener { connect() }
        view.addView(connect)

        on.text = "Sync with the hub"
        on.textSize = 16f
        on.setPadding(0, a.dp(10), 0, a.dp(10))
        on.setOnCheckedChangeListener { _, v ->
            if (drawing) return@setOnCheckedChangeListener
            HubPrefs.setOn(a, v)
            if (v) {
                HubSync.start(a)
                HubSync.schedulePoll(a)
            } else {
                HubSync.schedulePoll(a)
            }
            refresh()
        }
        view.addView(on)
        syncNow.text = "Sync now"
        syncNow.setOnClickListener { HubSync.syncNow(a) }
        view.addView(syncNow)
        disconnect.text = "Disconnect from the hub"
        disconnect.setOnClickListener {
            HubPrefs.clearHub(a)
            Thread { HubSync.forget(a); a.runOnUiThread { refresh() } }.start()
            HubSync.schedulePoll(a)
        }
        view.addView(disconnect)
        refresh()
    }

    private fun connect() {
        val u = url.text.toString().trim()
        val k = key.text.toString().trim()
        if (u.isEmpty() || k.isEmpty()) {
            message.text = "Paste both the web app URL and the key."
            return
        }
        connect.isEnabled = false
        message.text = "Checking…"
        val dev = HubPrefs.device(a)
        Thread {
            val problem = try {
                HubClient.post(u, com.jono.nowwidget.core.HubJson.write(mapOf("key" to k, "dev" to dev, "push" to emptyList<Any>(), "since" to 0L, "limit" to 1L)))
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            a.runOnUiThread {
                connect.isEnabled = true
                if (problem != null) {
                    message.text = problem
                } else {
                    val otherHub = HubPrefs.url(a).let { it != null && it != u }
                    HubPrefs.setHub(a, u, k)
                    if (otherHub) Thread { HubSync.forget(a) }.start()
                    url.setText("")
                    key.setText("")
                    message.text = "Connected. Turn on syncing to send your log."
                    refresh()
                }
            }
        }.start()
    }

    fun refresh() {
        drawing = true
        val connected = HubPrefs.url(a) != null && HubPrefs.key(a) != null
        status.text = HubSync.status(a).joinToString("\n")
        url.hint = if (connected) "New web app URL (to change hubs)" else "Web app URL (ends in /exec)"
        on.isEnabled = connected
        on.isChecked = HubPrefs.on(a)
        syncNow.isEnabled = connected && HubPrefs.on(a) && !HubSync.busy
        disconnect.isEnabled = connected
        drawing = false
    }
}
