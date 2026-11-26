package com.jono.nowwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jono.nowwidget.core.FlowStep
import com.jono.nowwidget.core.FlowTap

class NowWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ClaudeLink.syncShortcut(ctx)
        WidgetRenderer.refresh(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        val id = intent.data?.lastPathSegment?.toLongOrNull()
        when (intent.action) {
            ACTION_TAP -> if (id != null) {
                // A switch to a different activity starts the where → who → feeling flow.
                Store.tap(ctx, id)?.let { CheckIn.start(ctx, it) }
                WidgetRenderer.refresh(ctx)
            }
            // Start-time chips: now://shift/<entry>/<minutes|undo|now>. A chip drawn for another entry does nothing.
            ACTION_SHIFT -> {
                val seg = intent.data?.pathSegments
                val entry = seg?.getOrNull(0)?.toLongOrNull()
                val what = seg?.getOrNull(1)
                if (entry != null && what != null) {
                    when (what) {
                        "undo" -> StartControls.undo(ctx, entry)
                        // "Now ✓": the start as it was; after an adjustment, that's its undo.
                        "now" -> StartControls.undo(ctx, entry)
                        else -> what.toIntOrNull()?.let { StartControls.tap(ctx, entry, it) }
                    }
                }
                WidgetRenderer.refresh(ctx)
            }
            ACTION_FLOW -> {
                parseFlow(intent.data)?.let { (step, tap) -> CheckIn.tap(ctx, tap, step) }
                WidgetRenderer.refresh(ctx)
            }
            // Person chips from build 6 used ACTION_PERSON; people kept their ids as links.
            ACTION_LINK, ACTION_PERSON -> if (id != null) {
                Store.toggleLink(ctx, id)
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
        private const val ACTION_LINK = "com.jono.nowwidget.LINK"
        private const val ACTION_DISMISSED = "com.jono.nowwidget.DISMISSED"
        private const val ACTION_FLOW = "com.jono.nowwidget.FLOW"
        private const val ACTION_SHIFT = "com.jono.nowwidget.SHIFT"

        private fun broadcast(ctx: Context, action: String, uri: String): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, 0,
                Intent(action, Uri.parse(uri), ctx, NowWidget::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        fun tapIntent(ctx: Context, activityId: Long) = broadcast(ctx, ACTION_TAP, "now://activity/$activityId")
        fun linkIntent(ctx: Context, linkId: Long) = broadcast(ctx, ACTION_LINK, "now://link/$linkId")
        fun dismissedIntent(ctx: Context) = broadcast(ctx, ACTION_DISMISSED, "now://dismissed/0")

        /** A start-time chip for entry [entryId]: minutes ("-30", "5"), "undo" or "now". */
        fun shiftIntent(ctx: Context, entryId: Long, what: String) =
            broadcast(ctx, ACTION_SHIFT, "now://shift/$entryId/$what")

        /** A check-in flow chip or button, drawn for [step]: now://flow/<step>/<key>/<id>. */
        fun flowIntent(ctx: Context, step: FlowStep, key: String, id: Long) =
            broadcast(ctx, ACTION_FLOW, "now://flow/${step.name}/$key/$id")

        private fun parseFlow(uri: Uri?): Pair<FlowStep, FlowTap>? {
            val seg = uri?.pathSegments ?: return null
            if (seg.size != 3) return null
            val step = FlowStep.values().firstOrNull { it.name == seg[0] } ?: return null
            val id = seg[2].toLongOrNull() ?: return null
            val tap = when (seg[1]) {
                "pick" -> FlowTap.Pick(id)
                "mood" -> FlowTap.Mood(id)
                "skip" -> FlowTap.Skip
                "next" -> FlowTap.Next
                "none" -> FlowTap.None
                "done" -> FlowTap.Done
                else -> return null
            }
            return step to tap
        }
    }
}

/** Puts the widget's timer and the notification back after a restart or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        WidgetRenderer.refresh(ctx)
    }
}
