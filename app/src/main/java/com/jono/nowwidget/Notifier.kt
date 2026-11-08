package com.jono.nowwidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.widget.RemoteViews

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

    fun update(ctx: Context, s: Snapshot) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!Prefs.lockNotification(ctx)) {
            nm.cancel(ID)
            return
        }
        if (!nm.areNotificationsEnabled()) return
        ensureChannel(nm)

        val title = s.act?.let { it.emoji + " " + it.name } ?: "What's up now?"
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
            .setCustomContentView(collapsed(ctx, s))
            .setCustomBigContentView(expanded(ctx, s))

        val cur = s.cur
        if (cur != null) {
            b.setWhen(cur.start).setShowWhen(true).setUsesChronometer(true)
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
        Store.picks(s.buttons, s.act, Prefs.lockButtons(ctx), s.scores, s.smart).forEach { a ->
            rv.addView(R.id.row, WidgetRenderer.slot(ctx, R.layout.slot, a, a.id == s.act?.id))
        }
        return rv
    }

    private fun expanded(ctx: Context, s: Snapshot): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.notif_big)
        WidgetRenderer.grid(ctx, rv, R.id.grid, R.layout.row_fixed, R.layout.slot, s, BIG_COLS, BIG_ROWS)
        WidgetRenderer.people(ctx, rv, R.id.people_row, s, BIG_PEOPLE, names = true)
        return rv
    }
}
