package com.jono.nowwidget

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.jono.nowwidget.core.DiaryDay

/**
 * The "Toggl" part of the settings screen: what is sent and where, the API token, the workspace,
 * what goes in descriptions, how far back to send, turning it on, and removing what was sent.
 * The network calls run in [TogglExport] on background threads; this only draws.
 */
class TogglSection(private val a: Activity) {
    val view: LinearLayout = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val status = TextView(a).apply {
        textSize = 14f
        setPadding(0, a.dp(4), 0, a.dp(8))
    }
    private val message = TextView(a).apply {
        textSize = 14f
        setPadding(0, a.dp(4), 0, a.dp(8))
    }
    private val tokenBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val tokenInput = EditText(a).apply {
        setSingleLine(true)
        hint = "Paste your API token"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    private val saveToken = Button(a)
    private val tokenLine = TextView(a).apply {
        textSize = 15f
        setPadding(0, a.dp(8), 0, a.dp(4))
    }
    private val removeToken = Button(a)
    private val workspace = Button(a)
    private val range = Button(a)
    private val notes = Switch(a)
    private val checkIns = Switch(a)
    private val send = Switch(a)
    private val sendNow = Button(a)
    private val removeSent = Button(a)
    private var drawing = false
    private var checking = false

    init {
        view.addView(a.heading("Toggl"))
        view.addView(a.note(
            "Sends your log to Toggl Track as time entries. This is the one thing in the app that uses the internet, and only for this: " +
                "each finished stretch of a minute or more goes to Toggl's servers with its activity, start and end, " +
                "people, place and trip as tags, and your notes as the description. Anyone with access to your Toggl account can read it. " +
                "Nothing else is sent anywhere."
        ))
        view.addView(status)
        view.addView(message)

        view.addView(tokenBox)
        tokenBox.addView(tokenInput)
        saveToken.text = "Save token"
        saveToken.setOnClickListener { saveToken() }
        tokenBox.addView(saveToken)
        tokenBox.addView(TextView(a).apply {
            text = "Find it in Toggl → Profile settings → API token"
            textSize = 14f
            setTextColor(a.getColor(android.R.color.system_accent1_500))
            setPadding(0, a.dp(8), 0, a.dp(8))
            setOnClickListener { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROFILE_URL))) }
        })
        view.addView(tokenLine)
        removeToken.text = "Remove token"
        removeToken.setOnClickListener { confirmRemoveToken() }
        view.addView(removeToken)

        workspace.setOnClickListener { chooseWorkspace() }
        view.addView(workspace)
        range.setOnClickListener { chooseRange() }
        view.addView(range)
        view.addView(a.note("Entries are made for days from that date. A long history is sent a few at a time, because Toggl's free plan allows only about 30 requests an hour."))

        notes.text = "Include notes in descriptions"
        notes.textSize = 16f
        notes.setPadding(0, a.dp(10), 0, a.dp(10))
        notes.setOnCheckedChangeListener { _, on ->
            if (drawing) return@setOnCheckedChangeListener
            Prefs.setTogglNotes(a, on)
            descriptionsChanged()
        }
        view.addView(notes)
        checkIns.text = "Include check-ins (moods and feelings) in descriptions"
        checkIns.textSize = 16f
        checkIns.setPadding(0, a.dp(10), 0, a.dp(10))
        checkIns.setOnCheckedChangeListener { _, on ->
            if (drawing) return@setOnCheckedChangeListener
            Prefs.setTogglCheckIns(a, on)
            descriptionsChanged()
        }
        view.addView(checkIns)
        view.addView(a.note("Changing these updates the entries already sent, a few at a time."))

        send.text = "Send my log to Toggl"
        send.textSize = 16f
        send.setPadding(0, a.dp(10), 0, a.dp(10))
        send.setOnCheckedChangeListener { _, on ->
            if (drawing) return@setOnCheckedChangeListener
            if (on) turnOn() else {
                TogglExport.stop(a)
                refresh()
            }
        }
        view.addView(send)
        view.addView(a.note(
            "Each activity becomes a Toggl project (made when first needed), and every entry is tagged now. " +
                "The app only ever changes or deletes entries it made itself. Edits you make in Toggl aren't read back, and an entry you delete in Toggl isn't made again."
        ))
        sendNow.text = "Send now"
        sendNow.setOnClickListener { TogglExport.sendNow(a) }
        view.addView(sendNow)
        removeSent.setOnClickListener { confirmRemoveSent() }
        view.addView(removeSent)
        refresh()
    }

    private fun button(label: String, onTap: () -> Unit) = Button(a).apply {
        text = label
        setOnClickListener { onTap() }
    }

    private fun mask(token: String): String = "••••" + token.takeLast(4)

    /** Redraws everything. Call on the main thread. */
    fun refresh() {
        drawing = true
        try {
            draw()
        } finally {
            drawing = false
        }
    }

    private fun draw() {
        val token = Prefs.togglToken(a)
        val idle = TogglExport.busy.isBlank() && !checking
        status.text = TogglExport.status(a).joinToString("\n")
        tokenBox.visibility = if (token == null) View.VISIBLE else View.GONE
        saveToken.isEnabled = idle
        tokenLine.visibility = if (token == null) View.GONE else View.VISIBLE
        tokenLine.text = if (token == null) "" else "API token saved (" + mask(token) + ")"
        removeToken.visibility = if (token == null) View.GONE else View.VISIBLE
        workspace.visibility = if (token == null) View.GONE else View.VISIBLE
        val ws = Prefs.togglWorkspace(a)
        workspace.text = if (ws == 0L) "Choose workspace" else "Workspace: " + Prefs.togglWorkspaceName(a)
        workspace.isEnabled = idle
        range.text = "Send past days: " + rangeLabel()
        notes.isChecked = Prefs.togglNotes(a)
        checkIns.isChecked = Prefs.togglCheckIns(a)
        send.isChecked = Prefs.togglOn(a)
        sendNow.visibility = if (Prefs.togglOn(a) || Prefs.togglRemoving(a)) View.VISIBLE else View.GONE
        sendNow.isEnabled = TogglExport.busy.isBlank()
        val sent = TogglExport.sentCount(a)
        removeSent.visibility = if (sent > 0 || Prefs.togglRemoving(a)) View.VISIBLE else View.GONE
        removeSent.text = if (Prefs.togglRemoving(a)) "Removing sent entries… ($sent left)" else "Remove sent entries from Toggl ($sent)"
        removeSent.isEnabled = !Prefs.togglRemoving(a) && TogglExport.busy.isBlank()
    }

    private fun rangeLabel(): String {
        val from = Prefs.togglFrom(a)
        return when {
            from < 0L -> "from the day you turn it on"
            from == 0L -> "everything logged in Now"
            else -> "from " + Fmt.day(from)
        }
    }

    // --- Token and workspace ---

    private fun saveToken() {
        val token = tokenInput.text.toString().trim()
        if (token.isEmpty()) {
            Toast.makeText(a, "Paste the token first", Toast.LENGTH_SHORT).show()
            return
        }
        message.text = "Checking the token with Toggl…"
        checking = true
        refresh()
        TogglExport.fetchAccount(a, token) { account, problem ->
            a.runOnUiThread {
                checking = false
                if (account == null) {
                    message.text = problem
                    refresh()
                    return@runOnUiThread
                }
                val old = Prefs.togglUserId(a)
                if (old != 0L && old != account.userId) {
                    askAboutOldAccount(token, account)
                } else {
                    keepToken(token, account)
                }
                refresh()
            }
        }
    }

    /** A token for a different Toggl account: what happens to the entries sent to the old one is asked, not assumed. */
    private fun askAboutOldAccount(token: String, account: TogglExport.Account) {
        val n = TogglExport.sentCount(a)
        if (n == 0) {
            Store.forgetTogglRecords(a)
            switchAccount(token, account)
            return
        }
        AlertDialog.Builder(a)
            .setTitle("Forget the $n entr" + (if (n == 1) "y" else "ies") + " sent to the old account?")
            .setMessage(
                "This token is for a different Toggl account. The entries already sent stay in the old account either way; the app makes no calls to delete them. " +
                    "Forget them, or keep them aside (never touched with the new token)? Sending is turned off until you choose a workspace and turn it on."
            )
            .setPositiveButton("Forget them") { _, _ ->
                Store.forgetTogglRecords(a)
                switchAccount(token, account)
                refresh()
            }
            .setNegativeButton("Keep them aside") { _, _ ->
                Store.shelveTogglRecords(a)
                switchAccount(token, account)
                refresh()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun switchAccount(token: String, account: TogglExport.Account) {
        TogglExport.stop(a)
        Prefs.setTogglWorkspace(a, 0L, "")
        keepToken(token, account)
    }

    /** Only a token Toggl accepted is kept, with the account it belongs to. */
    private fun keepToken(token: String, account: TogglExport.Account) {
        Prefs.setTogglToken(a, token)
        Prefs.setTogglUserId(a, account.userId)
        tokenInput.setText("")
        Prefs.setTogglFatal(a, "")
        Prefs.setTogglError(a, "")
        message.text = ""
        val list = account.workspaces
        when {
            list.isEmpty() -> message.text = "The token works, but Toggl lists no workspaces for it."
            list.size == 1 -> setWorkspace(list[0].first, list[0].second)
            else -> pickWorkspace(list)
        }
        refresh()
    }

    private fun chooseWorkspace() {
        val token = Prefs.togglToken(a) ?: return
        message.text = "Asking Toggl for your workspaces…"
        checking = true
        refresh()
        TogglExport.fetchAccount(a, token) { account, problem ->
            a.runOnUiThread {
                checking = false
                message.text = if (account == null) problem else ""
                if (account != null && account.workspaces.isNotEmpty()) pickWorkspace(account.workspaces)
                refresh()
            }
        }
    }

    private fun pickWorkspace(list: List<Pair<Long, String>>) {
        AlertDialog.Builder(a)
            .setTitle("Send entries to which workspace?")
            .setItems(list.map { it.second }.toTypedArray()) { _, which -> setWorkspace(list[which].first, list[which].second) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setWorkspace(id: Long, name: String) {
        val old = Prefs.togglWorkspace(a)
        val sent = TogglExport.sentCount(a)
        if (old != 0L && old != id && sent > 0) {
            AlertDialog.Builder(a)
                .setTitle("Change workspace?")
                .setMessage(
                    "The $sent entr" + (if (sent == 1) "y" else "ies") + " already sent are in ${Prefs.togglWorkspaceName(a)}. " +
                        "If you change, the app deletes them there and sends them to $name, a few at a time within Toggl's hourly limit."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Change") { _, _ -> applyWorkspace(id, name) }
                .show()
        } else {
            applyWorkspace(id, name)
        }
    }

    private fun applyWorkspace(id: Long, name: String) {
        val changed = Prefs.togglWorkspace(a) != id
        Prefs.setTogglWorkspace(a, id, name)
        if (changed) {
            Prefs.setTogglFatal(a, "")
            if (Prefs.togglOn(a)) Store.markTogglWrittenDirty(a)
        }
        refresh()
    }

    private fun confirmRemoveToken() {
        AlertDialog.Builder(a)
            .setTitle("Remove the token?")
            .setMessage("Sending stops. Entries already in Toggl stay there. To delete them, tap Remove sent entries first.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                TogglExport.stop(a)
                Prefs.setTogglToken(a, null)
                Prefs.setTogglWorkspace(a, 0L, "")
                Prefs.setTogglFatal(a, "")
                Prefs.setTogglError(a, "")
                message.text = ""
                refresh()
            }
            .show()
    }

    // --- Range, descriptions, on and off ---

    private fun chooseRange() {
        val today = Fmt.today()
        val labels = arrayOf("From today (" + Fmt.day(today) + ")", "Last 7 days", "Last 30 days", "Everything logged in Now")
        val starts = longArrayOf(today, DiaryDay.add(today, -6), DiaryDay.add(today, -29), 0L)
        AlertDialog.Builder(a)
            .setTitle("Send past days")
            .setItems(labels) { _, which ->
                Prefs.setTogglFrom(a, starts[which])
                if (Prefs.togglOn(a)) TogglExport.rangeChanged(a)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun descriptionsChanged() {
        if (Prefs.togglOn(a)) Store.markTogglWrittenDirty(a)
        refresh()
    }

    private fun turnOn() {
        if (Prefs.togglToken(a) == null || Prefs.togglWorkspace(a) == 0L) {
            Toast.makeText(a, "Save the token and choose a workspace first", Toast.LENGTH_SHORT).show()
            refresh()
            return
        }
        if (Prefs.togglFrom(a) < 0L) Prefs.setTogglFrom(a, Fmt.today())
        message.text = "Counting what would be sent…"
        TogglExport.estimate(a) { days, entries ->
            a.runOnUiThread {
                message.text = ""
                val hours = (entries + RATE - 1) / RATE
                val text = if (entries == 0) "Nothing to send yet. New stretches are sent as they finish."
                else "About $entries entr" + (if (entries == 1) "y" else "ies") + " over $days day" + (if (days == 1) "" else "s") + " (" + rangeLabel() + "). " +
                    "Toggl's free plan allows about 30 requests an hour, so this takes about " + (if (hours <= 1) "an hour" else "$hours hours") +
                    ", sent in the background while the phone is online."
                AlertDialog.Builder(a)
                    .setTitle("Send your log to Toggl?")
                    .setMessage(text + "\n\nIt goes to Toggl's servers, workspace " + Prefs.togglWorkspaceName(a) + ".")
                    .setNegativeButton("Cancel") { _, _ -> refresh() }
                    .setOnDismissListener { refresh() }
                    .setPositiveButton("Send") { _, _ ->
                        Prefs.setTogglOn(a, true)
                        TogglExport.start(a)
                        refresh()
                    }
                    .show()
            }
        }
    }

    private fun confirmRemoveSent() {
        val n = TogglExport.sentCount(a)
        AlertDialog.Builder(a)
            .setTitle("Remove sent entries?")
            .setMessage(
                "Delete the $n entr" + (if (n == 1) "y" else "ies") + " this app sent from Toggl? Only entries the app made are deleted, from its own list of them; nothing else in your Toggl is touched. " +
                    "Sending is turned off. Within Toggl's hourly limit it takes a while, and carries on in the background."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                if (Prefs.togglToken(a) == null) {
                    message.text = "Add the API token again first: it's needed to delete them."
                } else {
                    TogglExport.removeSent(a)
                }
                refresh()
            }
            .show()
    }

    companion object {
        const val PROFILE_URL = "https://track.toggl.com/profile"
        /** Requests the app allows an hour: also about how many entries go in an hour. */
        private const val RATE = 25
    }
}
