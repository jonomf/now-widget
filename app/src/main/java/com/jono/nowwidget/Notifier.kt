package com.jono.nowwidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.widget.RemoteViews
import com.jono.nowwidget.core.CheckInFlow
import com.jono.nowwidget.core.FlowCell
import com.jono.nowwidget.core.FlowControl
import com.jono.nowwidget.core.FlowState

/**
 * The lock screen buttons. A notification works on every Android version, and its buttons run
 * without unlocking because they only send a broadcast to this app.
 */
object Notifier {
    private const val CHANNEL = "now_status"
    private const val ID = 1
    private const val BIG_COLS = 5
    private const val BIG_ROWS = 3
    private const val BIG_PEOPLE = 5
    private const val BIG_FLOW_ROWS = 2

    fun update(ctx: Context, s: Snapshot) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!Prefs.lockNotification(ctx)) {
            nm.cancel(ID)
            return
        }
        if (!nm.areNotificationsEnabled()) return
        ensureChannel(nm)

        // Where and who may show here (their names already do); how you feel never does: on the
        // feeling step the notification is back to the usual buttons.
        val flow = s.flow?.takeIf { CheckInFlow.onLockScreen(it) }
        val title = s.act?.let { a -> a.emoji + " " + (if (flow != null) CheckInFlow.head(flow, a.name) else a.name) }
            ?: "Nothing right now"
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setSubText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(WidgetRenderer.openApp(ctx))
            .setDeleteIntent(NowWidget.dismissedIntent(ctx))
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(if (flow != null) flowCollapsed(ctx, s, flow) else collapsed(ctx, s))
            .setCustomBigContentView(if (flow != null) flowExpanded(ctx, s, flow) else expanded(ctx, s))

        val since = s.cur?.start ?: s.idleSince
        if (since != null) {
            b.setWhen(since).setShowWhen(true).setUsesChronometer(true)
        } else {
            b.setShowWhen(false)
        }
        nm.notify(ID, b.build())
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL) != null) return
        // Default importance so it isn't hidden as "silent" on the lock screen, but with no sound
        // or buzz, because it changes every time you tap.
        val c = NotificationChannel(CHANNEL, "Lock screen buttons", NotificationManager.IMPORTANCE_DEFAULT)
        c.setSound(null, null)
        c.enableVibration(false)
        c.setShowBadge(false)
        c.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(c)
    }

    private fun collapsed(ctx: Context, s: Snapshot): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.notif_collapsed)
        rv.removeAllViews(R.id.row)
        s.picks(Prefs.lockButtons(ctx)).forEach { a ->
            rv.addView(R.id.row, WidgetRenderer.slot(ctx, R.layout.slot, a, a.id == s.act?.id))
        }
        return rv
    }

    /** One row: the best chips, then Next (Where: tapping a place is already Next). */
    private fun flowCollapsed(ctx: Context, s: Snapshot, flow: FlowState): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.notif_collapsed)
        val n = maxOf(2, Prefs.lockButtons(ctx))
        val cells = CheckInFlow.window(s.flowOptions, n - 1).map { FlowCell(it) }
        // notif_collapsed is itself the row: the chips, then Next as a real button.
        rv.removeAllViews(R.id.row)
        WidgetRenderer.flowRow(ctx, rv, R.id.row, R.layout.slot, flow.step, cells)
        rv.addView(R.id.row, WidgetRenderer.flowButton(ctx, flow.step, FlowControl.NEXT))
        return rv
    }

    private fun flowExpanded(ctx: Context, s: Snapshot, flow: FlowState): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.notif_big)
        val view = CheckInFlow.block(flow, "", s.flowOptions, BIG_COLS, BIG_FLOW_ROWS)
        WidgetRenderer.flowGrid(ctx, rv, R.id.grid, R.layout.row_fixed, R.layout.slot, view.copy(steps = null))
        rv.removeAllViews(R.id.people_row)
        rv.setViewVisibility(R.id.people_row, android.view.View.GONE)
        return rv
    }

    private fun expanded(ctx: Context, s: Snapshot): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.notif_big)
        WidgetRenderer.grid(ctx, rv, R.id.grid, R.layout.row_fixed, R.layout.slot, s, BIG_COLS, BIG_ROWS)
        WidgetRenderer.people(ctx, rv, R.id.people_row, s, BIG_PEOPLE, names = true)
        return rv
    }
}
