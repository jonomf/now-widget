package com.jono.nowwidget

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where your time went, plus setup. Also the "settings" entry in the widget's long-press menu. */
class MainActivity : Activity() {
    private lateinit var nowLine: TextView
    private lateinit var stats: LinearLayout
    private lateinit var periodButtons: List<Button>
    private lateinit var stepButton: Button
    private lateinit var lockCountButton: Button
    private lateinit var sizes: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updaterButton: Button
    private var periodDays = 1
    private val redraw: () -> Unit = { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Opened from the long-press menu's widget settings: nothing to configure, just say OK.
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        }

        val pad = dp(24)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        nowLine = TextView(this).apply {
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        }
        col.addView(nowLine)
        col.addView(button("Change what I'm doing") { startActivity(Intent(this, PanelActivity::class.java)) })

        // --- Where the time went ---
        col.addView(heading("Where the time went"))
        val periods = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        periodButtons = listOf(1 to "Today", 7 to "7 days", 30 to "30 days").map { (days, label) ->
            Button(this).apply {
                text = label
                setOnClickListener {
                    periodDays = days
                    refresh()
                }
            }.also { periods.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        col.addView(periods)
        stats = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(stats)
        col.addView(button("Timeline and edits") { startActivity(Intent(this, TimelineActivity::class.java)) })

        // --- Customize ---
        col.addView(heading("Customize"))
        col.addView(button("Activities") { manage(ManageActivity.MODE_ACTIVITIES) })
        col.addView(button("People") { manage(ManageActivity.MODE_PEOPLE) })
        col.addView(
            note("Tap a different activity to switch. Switched by mistake? Tap the right one within a minute and the mistake disappears.")
        )
        stepButton = button("") {
            val options = listOf(0, 5, 10, 15, 30)
            val next = options[(options.indexOf(Prefs.stepMinutes(this)) + 1) % options.size]
            Prefs.setStepMinutes(this, next)
            refresh()
        }
        col.addView(stepButton)
        col.addView(toggle(
            "Smart button order",
            Prefs.smartOrder(this),
        ) { on -> Prefs.setSmartOrder(this, on) })
        col.addView(note("When your activities don't all fit, the buttons show what you usually do at this time of day."))

        // --- Lock screen ---
        col.addView(heading("Lock screen"))
        col.addView(toggle(
            "Lock screen buttons",
            Prefs.lockNotification(this),
        ) { on ->
            Prefs.setLockNotification(this, on)
            if (on) askForNotifications()
        })
        lockCountButton = button("") {
            val next = if (Prefs.lockButtons(this) >= 6) 3 else Prefs.lockButtons(this) + 1
            Prefs.setLockButtons(this, next)
            WidgetRenderer.refresh(this)
        }
        col.addView(lockCountButton)
        col.addView(note("This is a silent notification. Your current activity shows on the lock screen, so anyone holding your phone could see it. Expand it for more buttons and people."))
        col.addView(button("Open app info") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        // --- Data ---
        col.addView(heading("Your data"))
        col.addView(note("Everything stays on this phone. The app has no internet permission."))
        col.addView(button("Export CSV") { startExport() })

        // --- Home screen ---
        col.addView(heading("Your widgets"))
        col.addView(button("Add widget to home screen") { pinWidget() })
        sizes = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
        }
        col.addView(sizes)

        // --- Updates ---
        col.addView(heading("Updates"))
        updateStatus = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, dp(8))
        }
        col.addView(updateStatus)
        updaterButton = button("") {
            startActivity(
                UpdateStatus.updaterIntent(this)
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse(UpdateStatus.UPDATER_RELEASES))
            )
        }
        col.addView(updaterButton)
        col.addView(button("Get the latest build in the browser") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)))
        })

        setContentView(ScrollView(this).apply { addView(col) })
        askForNotifications()
    }

    override fun onResume() {
        super.onResume()
        WidgetRenderer.listeners.add(redraw)
        WidgetRenderer.refresh(this)
    }

    override fun onPause() {
        WidgetRenderer.listeners.remove(redraw)
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        WidgetRenderer.refresh(this)
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            Prefs.lockNotification(this) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun refresh() {
        val s = WidgetRenderer.snapshot(this)
        nowLine.text = s.act?.let { a ->
            a.emoji + " " + a.name + "  " + Fmt.dur(s.now - (s.cur?.start ?: s.now))
        } ?: "Nothing right now"
        val step = Prefs.stepMinutes(this)
        stepButton.text = "Tapping the current one again: " + if (step == 0) "turn it off" else "back $step min"
        lockCountButton.text = "Lock screen buttons: ${Prefs.lockButtons(this)}"
        sizes.text = WidgetRenderer.describeSizes(this)
        val build = UpdateStatus.currentBuild(this)
        val latest = UpdateStatus.latest(this)
        val hasUpdater = UpdateStatus.updaterIntent(this) != null
        updateStatus.text = when {
            !hasUpdater -> "You're on build $build. Install Widget Updater to get an update symbol on the widget."
            latest > build -> "Build $latest is available (you're on $build)."
            else -> "You're on build $build. The widget shows a small ↑ when there's a new build."
        }
        updaterButton.text = when {
            !hasUpdater -> "Get Widget Updater"
            latest > build -> "Update now"
            else -> "Open Widget Updater"
        }
        periodButtons.forEachIndexed { i, b -> b.alpha = if (listOf(1, 7, 30)[i] == periodDays) 1f else 0.55f }
        drawStats(s.now)
    }

    private fun drawStats(now: Long) {
        stats.removeAllViews()
        val from = Fmt.addDays(Fmt.startOfDay(now), -(periodDays - 1))
        val spans = Store.spans(this, from, now)
        val acts = Store.allActs(this).associateBy { it.id }
        val people = Store.allPeople(this).associateBy { it.id }

        if (spans.isEmpty()) {
            stats.addView(note("Nothing logged in this period yet."))
            return
        }
        if (periodDays == 1) {
            stats.addView(
                RibbonView(this).apply {
                    set(spans, acts.mapValues { it.value.color }, from, Fmt.addDays(from, 1))
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(18)).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(8)
                },
            )
        }

        val total = spans.sumOf { it.ms }
        val byAct = HashMap<Long, Long>()
        val byGroup = HashMap<String, Long>()
        val groupColor = HashMap<String, Int>()
        val byPerson = HashMap<Long, Long>()
        spans.forEach { s ->
            val a = acts[s.entry.activityId]
            byAct[s.entry.activityId] = (byAct[s.entry.activityId] ?: 0L) + s.ms
            val g = a?.group?.ifBlank { "Ungrouped" } ?: "Ungrouped"
            byGroup[g] = (byGroup[g] ?: 0L) + s.ms
            if (a != null && g !in groupColor) groupColor[g] = a.color
            s.entry.people.forEach { p -> byPerson[p] = (byPerson[p] ?: 0L) + s.ms }
        }

        byAct.entries.sortedByDescending { it.value }.forEach { (id, ms) ->
            val a = acts[id]
            stats.addView(statRow(if (a != null) a.emoji + " " + a.name else "Removed", ms, total, a?.color ?: GREY))
        }
        if (byGroup.size > 1) {
            stats.addView(subheading("Groups"))
            byGroup.entries.sortedByDescending { it.value }.forEach { (g, ms) ->
                stats.addView(statRow(g, ms, total, groupColor[g] ?: GREY))
            }
        }
        if (byPerson.isNotEmpty()) {
            stats.addView(subheading("With people"))
            byPerson.entries.sortedByDescending { it.value }.forEach { (id, ms) ->
                val p = people[id]
                stats.addView(statRow(p?.let { (it.badge + " " + it.name) } ?: "Removed", ms, total, ACCENT))
            }
        }
    }

    private fun subheading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun button(label: String, onTap: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onTap() }
    }

    private fun toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 16f
        isChecked = on
        setPadding(0, dp(10), 0, dp(10))
        setOnCheckedChangeListener { _, checked ->
            onChange(checked)
            WidgetRenderer.refresh(this@MainActivity)
        }
    }

    private fun manage(mode: String) {
        startActivity(Intent(this, ManageActivity::class.java).putExtra(ManageActivity.EXTRA_MODE, mode))
    }

    private fun pinWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, NowWidget::class.java), null, null)
        } else {
            Toast.makeText(this, "Long-press your home screen → Widgets → Now", Toast.LENGTH_LONG).show()
        }
    }

    private fun startExport() {
        val name = "now-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".csv"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, name)
        startActivityForResult(i, REQUEST_EXPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK || uri == null) return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(csv().toByteArray()) }
            Toast.makeText(this, "Exported", Toast.LENGTH_SHORT).show()
        } catch (e: java.io.IOException) {
            Toast.makeText(this, "Couldn't save the file", Toast.LENGTH_LONG).show()
        }
    }

    private fun csv(): String {
        val acts = Store.allActs(this).associateBy { it.id }
        val people = Store.allPeople(this).associateBy { it.id }
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder("start,end,minutes,activity,group,people,note\n")
        Store.spans(this, 0L, System.currentTimeMillis()).forEach { s ->
            val a = acts[s.entry.activityId]
            val names = s.entry.people.mapNotNull { people[it]?.name }.joinToString("; ")
            val fields = listOf(
                f.format(Date(s.from)), f.format(Date(s.to)), (s.ms / 60_000L).toString(),
                a?.name ?: "", a?.group ?: "", names, s.entry.note,
            )
            sb.append(fields.joinToString(",") { quote(it) }).append('\n')
        }
        return sb.toString()
    }

    private fun quote(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    companion object {
        private const val RELEASES_URL = "https://github.com/jonomf/now-widget/releases/latest"
        private const val REQUEST_EXPORT = 2
        private const val GREY = 0xFF9A9A9A.toInt()
        private const val ACCENT = 0xFF5B8DEF.toInt()
    }
}
