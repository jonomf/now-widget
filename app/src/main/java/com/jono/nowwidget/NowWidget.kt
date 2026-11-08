package com.jono.nowwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

class NowWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetRenderer.refresh(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        val id = intent.data?.lastPathSegment?.toLongOrNull()
        when (intent.action) {
            ACTION_TAP -> if (id != null) {
                Store.tap(ctx, id)
                WidgetRenderer.refresh(ctx)
            }
            ACTION_PERSON -> if (id != null) {
                Store.togglePerson(ctx, id)
                WidgetRenderer.refresh(ctx)
            }
            // Android 14+ lets you swipe the notification away. Keep it there; it can be
            // turned off in the app instead.
            ACTION_DISMISSED -> WidgetRenderer.refresh(ctx)
        }
    }

    companion object {
        private const val ACTION_TAP = "com.jono.nowwidget.TAP"
        private const val ACTION_PERSON = "com.jono.nowwidget.PERSON"
        private const val ACTION_DISMISSED = "com.jono.nowwidget.DISMISSED"

        private fun broadcast(ctx: Context, action: String, uri: String): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, 0,
                Intent(action, Uri.parse(uri), ctx, NowWidget::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        fun tapIntent(ctx: Context, activityId: Long) = broadcast(ctx, ACTION_TAP, "now://activity/$activityId")
        fun personIntent(ctx: Context, personId: Long) = broadcast(ctx, ACTION_PERSON, "now://person/$personId")
        fun dismissedIntent(ctx: Context) = broadcast(ctx, ACTION_DISMISSED, "now://dismissed/0")
    }
}

/** Puts the widget's timer and the notification back after a restart or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        WidgetRenderer.refresh(ctx)
    }
}
