package com.jono.nowwidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.jono.nowwidget.core.DayRecap
import com.jono.nowwidget.core.DiaryDay
import com.jono.nowwidget.core.LinkKind
import com.jono.nowwidget.core.Recap

/**
 * The evening recap notification: once a day at the time he sets, on its own silent channel, and
 * only when the day has something to fix. It shows counts only ("3 gaps and 1 unlabelled today"),
 * never activities, people, moods or notes, so it's safe on the lock screen. Tapping it opens
 * [RecapActivity]. Timed with JobScheduler (no exact-alarm permission): not before the time, and
 * Android runs it within about 15 minutes after.
 */
object RecapNotice {
    private const val CHANNEL = "now_recap"
    private const val ID = 2
    const val JOB_ID = 4213
    private const val SLACK_MS = 15L * 60_000L

    /** The recap of the diary day containing [dayMs] (off the main thread: it reads the day). */
    fun recap(ctx: Context, dayMs: Long, now: Long = System.currentTimeMillis()): DayRecap {
        val day = DiaryDay.start(dayMs)
        val end = DiaryDay.add(day, 1)
        val places = Store.allLinks(ctx).filter { it.kind == LinkKind.PLACE }.map { it.id }.toSet()
        return Recap.build(
            dayStart = day,
            dayEnd = end,
            entries = Store.dayEntries(ctx, day, end),
            moments = Store.moments(ctx, day, end),
            now = now,
            otherId = Store.otherActivityIfAny(ctx),
            placeIds = places,
            settings = Prefs.recapSettings(ctx),
        )
    }

    enum class Outcome { SHOWN, NOTHING_TO_FIX, BLOCKED }

    /**
     * Shows today's recap notification when there's something to fix, else takes any old one away.
     * Call off the main thread.
     */
    fun check(ctx: Context): Outcome {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return Outcome.BLOCKED
        val now = System.currentTimeMillis()
        val r = recap(ctx, now, now)
        if (!r.notify) {
            nm.cancel(ID)
            return Outcome.NOTHING_TO_FIX
        }
        if (!nm.areNotificationsEnabled()) return Outcome.BLOCKED
        ensureChannel(nm)
        val open = PendingIntent.getActivity(
            ctx, ID,
            Intent(ctx, RecapActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Counts only: this text is fine on the lock screen.
        val text = Recap.summary(r)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Evening recap")
            .setContentText(text)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        // One id: a new day's recap replaces the last one.
        nm.notify(ID, n)
        return Outcome.SHOWN
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL) != null) return
        // Silent by default; he can make it audible in Android's settings for this channel.
        val c = NotificationChannel(CHANNEL, "Evening recap", NotificationManager.IMPORTANCE_LOW)
        c.setDescription("Once a day, when today has gaps or entries to fill in. Counts only.")
        c.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(c)
    }

    /**
     * Sets the next run, or cancels it when the recap is off. [fresh]: after a run or a settings
     * change. Otherwise (app open, boot, update) a run still set is kept, and one missed a little
     * while ago runs now ([Recap.next]). Cheap.
     */
    fun schedule(ctx: Context, fresh: Boolean = false) {
        try {
            val scheduler = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (!Prefs.recapOn(ctx)) {
                scheduler.cancel(JOB_ID)
                return
            }
            val now = System.currentTimeMillis()
            val next = Recap.next(
                now, Prefs.recapTime(ctx), Prefs.recapDue(ctx), Prefs.recapLastRun(ctx),
                pending = scheduler.getPendingJob(JOB_ID) != null, fresh = fresh,
            ) ?: return
            Prefs.setRecapDue(ctx, next.due)
            val delay = maxOf(0L, next.runAt - now)
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, RecapJob::class.java))
                    .setMinimumLatency(delay)
                    .setOverrideDeadline(delay + SLACK_MS)
                    .build()
            )
        } catch (e: RuntimeException) {
            // Scheduling is tried again on the next app open.
        }
    }
}

/** Runs at the recap time: shows the notification if there's something to fix, then sets tomorrow's run. */
class RecapJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread {
            try {
                Prefs.setRecapLastRun(app, System.currentTimeMillis())
                if (Prefs.recapOn(app)) RecapNotice.check(app)
            } catch (e: Throwable) {
                // Nothing shown tonight; tomorrow's run is still set below.
            }
            // Finish first: scheduling this job's id again while it runs would stop it.
            jobFinished(params, false)
            RecapNotice.schedule(app, fresh = true)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
