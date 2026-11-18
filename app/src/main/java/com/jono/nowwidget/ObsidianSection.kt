package com.jono.nowwidget

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * The "Obsidian" part of the settings screen: choose the vault folder once (the system folder
 * picker, no permission), see what was written and what went wrong, write on demand, stop.
 * The activity passes its onActivityResult here.
 */
class ObsidianSection(private val a: Activity) {
    val view: LinearLayout = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val status = TextView(a).apply {
        textSize = 14f
        setPadding(0, a.dp(4), 0, a.dp(8))
    }
    private lateinit var pick: Button
    private lateinit var today: Button
    private lateinit var all: Button
    private lateinit var rescan: Button
    private lateinit var stop: Button
    private lateinit var imported: Switch

    init {
        view.addView(a.heading("Obsidian"))
        view.addView(a.note(
            "Writes one note per day, like 2026-11-17 (now), into your vault, so your log shows up next to your daily notes. " +
                "It only ever writes its own (now) notes, and never reads them back. Everything stays on this phone and Obsidian Sync carries it on."
        ))
        pick = button("Choose vault folder") { choose() }
        view.addView(pick)
        view.addView(a.note(
            "Can't find your vault in the picker? Obsidian can keep a vault where other apps can't see it. " +
                "Move the vault to a shared folder such as Documents, or pick the folder that holds it. " +
                "Android won't let any app use the top of the phone's storage or the Download folder itself."
        ))
        view.addView(status)
        today = button("Write today now") { ObsidianExport.writeToday(a) }
        all = button("Write all days") { confirmAll() }
        rescan = button("Rescan vault notes") { ObsidianExport.rescan(a) }
        stop = button("Stop exporting") { confirmStop() }
        view.addView(today)
        view.addView(all)
        view.addView(rescan)
        view.addView(stop)
        imported = Switch(a).apply {
            text = "Also write days from imported history"
            textSize = 16f
            isChecked = Prefs.exportImported(a)
            setPadding(0, a.dp(10), 0, a.dp(10))
            setOnCheckedChangeListener { _, on -> Prefs.setExportImported(a, on) }
        }
        view.addView(imported)
        view.addView(a.note(
            "Off: days that only have Daylio check-ins (2019 to 2021) stay in the app. If you turn it on, tap Write all days to make those notes."
        ))

        view.addView(a.label("Add this line to your _templates/index template"))
        view.addView(TextView(a).apply {
            text = TEMPLATE_LINE
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(0, a.dp(2), 0, a.dp(4))
        })
        view.addView(button("Copy") {
            a.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("Obsidian template line", TEMPLATE_LINE))
            Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show()
        })
        view.addView(a.note("Put it where the (VRChat) note is embedded. The app never edits your templates or your own notes."))
        refresh()
    }

    private fun button(label: String, onTap: () -> Unit) = Button(a).apply {
        text = label
        setOnClickListener { onTap() }
    }

    /** Redraws the status and which buttons can be used. Call on the main thread. */
    fun refresh() {
        val on = ObsidianExport.configured(a)
        val busy = ObsidianExport.busy
        val lines = ArrayList<String>()
        if (!on) {
            lines.add("No vault folder chosen.")
        } else {
            lines.add("Folder: " + Prefs.vaultName(a).ifBlank { "(chosen)" })
            val last = Prefs.vaultLastExport(a)
            lines.add(if (last == 0L) "Nothing written yet." else "Last note written: " + stamp(last))
            val scan = Prefs.vaultLastScan(a)
            if (scan != 0L) {
                lines.add("Vault notes: " + Prefs.vaultScanText(a) + " (scanned " + stamp(scan) + ")")
            }
        }
        if (busy.isNotBlank()) lines.add(busy)
        val scanProblem = Prefs.vaultScanError(a)
        if (scanProblem.isNotBlank()) lines.add("Scan problem: $scanProblem")
        val problem = Prefs.vaultError(a)
        if (problem.isNotBlank()) lines.add("Problem: $problem")
        status.text = lines.joinToString("\n")
        pick.text = if (on) "Change vault folder" else "Choose vault folder"
        val idle = busy.isBlank()
        today.isEnabled = on && idle
        all.isEnabled = on && idle
        rescan.isEnabled = on && idle
        stop.isEnabled = on
        imported.isChecked = Prefs.exportImported(a)
    }

    private fun stamp(ms: Long): String = Fmt.day(ms) + ", " + Fmt.time(a, ms)

    private fun choose() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        try {
            a.startActivityForResult(i, REQUEST_VAULT)
        } catch (e: Exception) {
            Prefs.setVaultError(a, "This phone has no folder picker")
            refresh()
        }
    }

    /** True when the result was this section's. */
    fun onResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_VAULT) return false
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return true
        try {
            a.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: Exception) {
            Prefs.setVaultError(a, "Android wouldn't keep access to that folder: " + (e.message ?: e.javaClass.simpleName))
            refresh()
            return true
        }
        status.text = "Looking for your vault…"
        Thread {
            try {
                val found = VaultAccess.findVaults(a, uri)
                a.runOnUiThread { accept(uri, found) }
            } catch (e: Exception) {
                a.runOnUiThread {
                    Prefs.setVaultError(a, "That folder couldn't be read: " + (e.message ?: e.javaClass.simpleName))
                    refresh()
                }
            }
        }.start()
        return true
    }

    private fun accept(tree: Uri, found: List<VaultFile>) {
        when {
            found.isEmpty() -> {
                Prefs.setVaultError(
                    a,
                    "No Obsidian vault in that folder (no .obsidian folder). Pick the vault folder, or the folder that holds it."
                )
                release(tree)
                refresh()
            }
            found.size == 1 && found[0].guessedNotes > 0 -> {
                val v = found[0]
                AlertDialog.Builder(a)
                    .setTitle("Use this folder?")
                    .setMessage(
                        "\"" + v.name + "\" has no .obsidian folder that the phone can see, but it holds " +
                            v.guessedNotes + " notes. Use it as the vault?"
                    )
                    .setNegativeButton("Cancel") { _, _ ->
                        release(tree)
                        refresh()
                    }
                    .setPositiveButton("Use it") { _, _ -> use(tree, v) }
                    .show()
            }
            found.size == 1 -> use(tree, found[0])
            else -> {
                val names = found.map { it.name }.toTypedArray<CharSequence>()
                AlertDialog.Builder(a)
                    .setTitle("Which vault?")
                    .setItems(names) { _, which -> use(tree, found[which]) }
                    .setNegativeButton("Cancel") { _, _ ->
                        release(tree)
                        refresh()
                    }
                    .show()
            }
        }
    }

    private fun use(tree: Uri, vault: VaultFile) {
        val before = Prefs.vaultTree(a)
        // A different vault: what was written and indexed before doesn't apply to it.
        if (before != tree.toString() || Prefs.vaultRoot(a) != vault.docId) {
            Store.clearExportState(a)
            Store.clearVaultCache(a)
        }
        Prefs.setVault(a, tree.toString(), vault.docId, vault.name)
        // The old folder is let go only once the new one is the setup (release never lets go of the one in use).
        if (before != null && before != tree.toString()) release(Uri.parse(before))
        Prefs.setVaultError(a, "")
        Prefs.setVaultScanError(a, "")
        refresh()
        ObsidianExport.startUp(a)
    }

    /**
     * Lets go of a folder permission, except the one the current setup uses: cancelling a dialog or
     * picking the same folder again must leave it working.
     */
    private fun release(tree: Uri) {
        if (tree.toString() == Prefs.vaultTree(a)) return
        try {
            a.contentResolver.releasePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: Exception) {
            // Already gone; nothing to release.
        }
    }

    private fun confirmAll() {
        AlertDialog.Builder(a)
            .setMessage("Write a note for every day that has something logged? Notes that haven't changed are rewritten with the same text. This can take a minute.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Write") { _, _ -> ObsidianExport.writeAll(a) }
            .show()
    }

    private fun confirmStop() {
        AlertDialog.Builder(a)
            .setMessage("Stop writing notes and let go of the vault folder? Notes already written stay where they are.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Stop") { _, _ ->
                val held = Prefs.vaultTree(a)
                Prefs.setVault(a, null, null, "")
                held?.let { release(Uri.parse(it)) }
                Prefs.setVaultError(a, "")
                Prefs.setVaultScanError(a, "")
                Store.clearExportState(a)
                Store.clearVaultCache(a)
                refresh()
                WidgetRenderer.refresh(a)
            }
            .show()
    }

    companion object {
        const val REQUEST_VAULT = 6

        /** What goes in `_templates/index template`, exactly. */
        val TEMPLATE_LINE = "![[<% tp.date.now(\"YYYY-M-D\") %> (now)]]"
    }
}
