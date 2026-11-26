package com.jono.nowwidget

import android.content.Context
import com.jono.nowwidget.core.StartShift

/**
 * The start-time chips ("−30", "＋5", Undo) on the widget, the panel and the lock screen: the glue
 * between core/StartShift.kt and the Store. Every change goes through the Store.
 */
object StartControls {

    /** A tap on the [minutes] chip, drawn for entry [entryId] (a stale chip for another entry does nothing). */
    fun tap(ctx: Context, entryId: Long, minutes: Int) {
        val now = System.currentTimeMillis()
        val s = Store.previewShift(ctx, minutes, now) ?: return
        if (s.entryId != entryId) return
        when (val t = StartShift.tap(s, minutes, Prefs.askOverMin(ctx), Prefs.shiftArmed(ctx), now)) {
            is StartShift.Tap.Apply -> apply(ctx, t.shift, now)
            is StartShift.Tap.Arm -> {
                Prefs.setShiftArmed(ctx, t.armed)
                FlowJob.schedule(ctx, StartShift.ARM_MS, FlowJob.START_JOB_ID)
            }
            StartShift.Tap.Nothing -> {}
        }
    }

    /** Applies [s] (the panel's "Set time…" after its confirm, or a chip). */
    fun apply(ctx: Context, s: StartShift.Shift, now: Long = System.currentTimeMillis()): Boolean {
        if (!Store.applyShift(ctx, s)) return false
        Prefs.setShiftArmed(ctx, null)
        Prefs.setShiftUndo(ctx, StartShift.Undo.of(s, now))
        FlowJob.schedule(ctx, StartShift.UNDO_MS, FlowJob.START_JOB_ID)
        return true
    }

    fun undo(ctx: Context, entryId: Long) {
        val u = Prefs.shiftUndo(ctx) ?: return
        if (u.entryId != entryId) return
        Store.undoShift(ctx, u)
        Prefs.setShiftUndo(ctx, null)
    }

    /** The adjustment that can still be undone (and whose note the head line shows), or null. */
    fun liveUndo(ctx: Context, cur: com.jono.nowwidget.core.Entry?, now: Long): StartShift.Undo? =
        Prefs.shiftUndo(ctx)?.takeIf { StartShift.undoLive(it, cur, now) }
}
