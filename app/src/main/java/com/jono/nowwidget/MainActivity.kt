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
import com.jono.nowwidget.core.Csv
import com.jono.nowwidget.core.Daylio
import com.jono.nowwidget.core.DiaryDay
import com.jono.nowwidget.core.LinkKind
import com.jono.nowwidget.core.levelFace
import java.text.SimpleDateFormat
import java.util.Calendar
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
        col.addView(button("People, places and contexts") { manage(ManageActivity.MODE_LINKS) })
        col.addView(button("Moods") { manage(ManageActivity.MODE_MOODS) })
        col.addView(button("Feelings") { manage(ManageActivity.MODE_TAGS) })
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
        col.addView(note("This is a silent notification. Your current activity shows on the lock screen, so anyone holding your phone could see it. Expand it for more buttons and people. Moods, feelings and notes never show there."))
        col.addView(button("Open app info") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        // --- Data ---
        col.addView(heading("Your data"))
        col.addView(note("Everything stays on this phone. The app has no internet permission."))
        col.addView(button("Export CSV") { startExport(REQUEST_EXPORT, "now-", "") })
        col.addView(button("Export check-ins (CSV)") { startExport(REQUEST_EXPORT_MOMENTS, "now-checkins-", "") })
        col.addView(button("Export feelings (Daylio CSV)") { startExport(REQUEST_EXPORT_FEELINGS, "now-feelings-", "") })
        col.addView(button("Import from Daylio") { startActivity(Intent(this, ImportActivity::class.java)) })
        col.addView(note("Imports a Daylio backup (.daylio) or Daylio CSV. You choose where each Daylio activity goes first. Importing the same backup twice adds nothing."))

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

        ClaudeLink.addSection(this, col) { WidgetRenderer.refresh(this) }

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
        val from = DiaryDay.add(Fmt.today(), -(periodDays - 1))
        val spans = Store.spans(this, from, now)
        val acts = Store.allActs(this).associateBy { it.id }
        val links = Store.allLinks(this).associateBy { it.id }

        if (spans.isEmpty()) {
            stats.addView(note("Nothing logged in this period yet."))
            drawMoods(from, now)
            return
        }
        if (periodDays == 1) {
            stats.addView(
                RibbonView(this).apply {
                    set(spans, acts.mapValues { it.value.color }, from, DiaryDay.add(from, 1))
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
        val byPlace = HashMap<Long, Long>()
        spans.forEach { s ->
            val a = acts[s.entry.activityId]
            byAct[s.entry.activityId] = (byAct[s.entry.activityId] ?: 0L) + s.ms
            val g = a?.group?.ifBlank { "Ungrouped" } ?: "Ungrouped"
            byGroup[g] = (byGroup[g] ?: 0L) + s.ms
            if (a != null && g !in groupColor) groupColor[g] = a.color
            s.entry.links.forEach { id ->
                when (links[id]?.kind) {
                    LinkKind.PERSON -> {
                        byPerson[id] = (byPerson[id] ?: 0L) + s.ms
                    }
                    LinkKind.PLACE -> {
                        byPlace[id] = (byPlace[id] ?: 0L) + s.ms
                    }
                }
            }
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
                val p = links[id]
                stats.addView(statRow(p?.let { (it.badge + " " + it.title) } ?: "Removed", ms, total, ACCENT))
            }
        }
        if (byPlace.isNotEmpty()) {
            stats.addView(subheading("Places"))
            byPlace.entries.sortedByDescending { it.value }.forEach { (id, ms) ->
                val p = links[id]
                stats.addView(statRow(p?.let { (it.badge + " " + it.title) } ?: "Removed", ms, total, ACCENT))
            }
        }
        drawMoods(from, now)
    }

    /** How you felt in the period: check-ins, the average mood (1 is best) and the top feelings. */
    private fun drawMoods(from: Long, to: Long) {
        val moods = Store.allMoods(this).associateBy { it.id }
        val moments = Store.moments(this, from, to)
        val rated = moments.mapNotNull { m -> m.moodId?.let { moods[it] } }
        val tagCounts = HashMap<Long, Int>()
        moments.forEach { m -> m.tags.forEach { tagCounts[it] = (tagCounts[it] ?: 0) + 1 } }
        if (rated.isEmpty() && tagCounts.isEmpty()) return
        stats.addView(subheading("Mood"))
        if (rated.isNotEmpty()) {
            val avg = rated.sumOf { it.level }.toDouble() / rated.size
            val face = levelFace(Math.round(avg).toInt())
            stats.addView(note(
                "${rated.size} check-in" + (if (rated.size == 1) "" else "s") +
                    " · average $face " + String.format(Locale.US, "%.1f", avg) + " (1 is best)"
            ))
            val byMood = rated.groupingBy { it.id }.eachCount()
            stats.addView(note(byMood.entries.sortedWith(compareBy({ moods[it.key]?.level ?: 9 }, { moods[it.key]?.pos ?: 0 }))
                .joinToString("   ") { (id, n) -> moodFace(moods[id]) + " " + n }))
        }
        if (tagCounts.isNotEmpty()) {
            val tags = Store.allTags(this).associateBy { it.id }
            stats.addView(note("Most felt: " + tagCounts.entries.sortedByDescending { it.value }.take(6)
                .joinToString(", ") { (id, n) -> (tags[id]?.name ?: "?") + " " + n }))
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

    private fun startExport(request: Int, prefix: String, suffix: String) {
        val name = prefix + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + suffix + ".csv"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, name)
        startActivityForResult(i, request)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        val text = when (requestCode) {
            REQUEST_EXPORT -> csv()
            REQUEST_EXPORT_FEELINGS -> feelingsCsv()
            REQUEST_EXPORT_MOMENTS -> momentsCsv()
            else -> return
        }
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            Toast.makeText(this, "Exported", Toast.LENGTH_SHORT).show()
        } catch (e: java.io.IOException) {
            Toast.makeText(this, "Couldn't save the file", Toast.LENGTH_LONG).show()
        }
    }

    /** Every span. The note column holds the texts of that entry's notes and feelings. */
    private fun csv(): String {
        val acts = Store.allActs(this).associateBy { it.id }
        val links = Store.allLinks(this).associateBy { it.id }
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val texts = HashMap<Long, MutableList<String>>()
        Store.moments(this, 0L, Long.MAX_VALUE).forEach { m ->
            val e = m.entryId
            if (e != null && m.text.isNotBlank()) texts.getOrPut(e) { ArrayList() }.add(m.text)
        }
        val sb = StringBuilder("start,end,minutes,activity,group,people,note,place\n")
        Store.spans(this, 0L, System.currentTimeMillis()).forEach { s ->
            val a = acts[s.entry.activityId]
            val linked = s.entry.links.mapNotNull { links[it] }
            val fields = listOf(
                f.format(Date(s.from)), f.format(Date(s.to)), (s.ms / 60_000L).toString(),
                a?.name ?: "", a?.group ?: "",
                linked.filter { it.kind == LinkKind.PERSON }.joinToString("; ") { it.title },
                texts[s.entry.id].orEmpty().joinToString("\n"),
                linked.filter { it.kind == LinkKind.PLACE }.joinToString("; ") { it.title },
            )
            sb.append(Csv.line(fields)).append('\n')
        }
        return sb.toString()
    }

    /**
     * Every note and feeling, with or without a mood or an activity, plus what its activity was
     * linked to. Nothing is left out, unlike the Daylio layout.
     */
    private fun momentsCsv(): String {
        val moods = Store.allMoods(this).associateBy { it.id }
        val tags = Store.allTags(this).associateBy { it.id }
        val acts = Store.allActs(this).associateBy { it.id }
        val links = Store.allLinks(this).associateBy { it.id }
        val refs = Store.refsFor(this)
        val entries = Store.spans(this, 0L, Long.MAX_VALUE).associateBy { it.entry.id }
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder("time,kind,mood,level,feelings,title,text,activity,people,place,context,also\n")
        Store.moments(this, 0L, Long.MAX_VALUE).forEach { m ->
            val mood = m.moodId?.let { moods[it] }
            val e = m.entryId?.let { entries[it]?.entry }
            val linked = e?.links.orEmpty().mapNotNull { links[it] }
            val also = refs[m.id].orEmpty().mapNotNull { (kind, id) ->
                if (kind == "activity") acts[id]?.name else links[id]?.title
            }
            val fields = listOf(
                f.format(Date(m.ts)),
                m.kind,
                mood?.name ?: "",
                mood?.level?.toString() ?: "",
                m.tags.mapNotNull { tags[it]?.name }.joinToString("; "),
                m.title,
                m.text,
                e?.let { acts[it.activityId]?.name } ?: "",
                linked.filter { it.kind == LinkKind.PERSON }.joinToString("; ") { it.title },
                linked.filter { it.kind == LinkKind.PLACE }.joinToString("; ") { it.title },
                e?.contextId?.let { links[it]?.title } ?: "",
                also.joinToString("; "),
            )
            sb.append(Csv.line(fields)).append('\n')
        }
        return sb.toString()
    }

    /** Feelings in Daylio's CSV layout. Tags, and the activities and links an import mapped, are its activities. */
    private fun feelingsCsv(): String {
        val moods = Store.allMoods(this).associateBy { it.id }
        val tags = Store.allTags(this).associateBy { it.id }
        val acts = Store.allActs(this).associateBy { it.id }
        val links = Store.allLinks(this).associateBy { it.id }
        val refs = Store.refsFor(this)
        val c = Calendar.getInstance()
        val rows = Store.allFeelings(this).mapNotNull { m ->
            val mood = m.moodId?.let { moods[it] } ?: return@mapNotNull null
            c.timeInMillis = m.ts
            val names = m.tags.mapNotNull { tags[it]?.name } + refs[m.id].orEmpty().mapNotNull { (kind, id) ->
                if (kind == "activity") acts[id]?.name else links[id]?.title
            }
            Daylio.CsvRow(
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
                mood.name, names.distinct(), m.title, m.text,
            )
        }
        return Daylio.toCsv(rows)
    }

    companion object {
        private const val RELEASES_URL = "https://github.com/jonomf/now-widget/releases/latest"
        private const val REQUEST_EXPORT = 2
        private const val REQUEST_EXPORT_FEELINGS = 3
        private const val REQUEST_EXPORT_MOMENTS = 4
        private const val GREY = 0xFF9A9A9A.toInt()
        private const val ACCENT = 0xFF5B8DEF.toInt()
    }
}
