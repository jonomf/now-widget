package com.jono.nowwidget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.jono.nowwidget.core.CheckInFlow
import com.jono.nowwidget.core.Entry
import com.jono.nowwidget.core.FlowEffect
import com.jono.nowwidget.core.FlowEntry
import com.jono.nowwidget.core.FlowState
import com.jono.nowwidget.core.FlowStep
import com.jono.nowwidget.core.FlowTap
import com.jono.nowwidget.core.Link
import com.jono.nowwidget.core.LinkKind

/**
 * The check-in flow on the widget (WHERE → WHO → FEELING after a switch): the glue between
 * core/CheckInFlow.kt, the Store and the saved state. Every change goes through the Store.
 */
object CheckIn {

    /** The entry as the flow sees it: its place and people among [links]. */
    fun flowEntry(cur: Entry?, links: List<Link>): FlowEntry? {
        if (cur == null) return null
        val kinds = links.associate { it.id to it.kind }
        return FlowEntry(
            entryId = cur.id,
            activityId = cur.activityId,
            place = cur.links.filter { kinds[it] == LinkKind.PLACE }.minOrNull(),
            people = cur.links.filter { kinds[it] == LinkKind.PERSON }.toSet(),
        )
    }

    /** The links that can be chips on the widget. */
    fun chipLinks(links: List<Link>, kind: String): List<Link> = links.filter { it.kind == kind && !it.archived && it.chip }

    /** A switch to entry [entryId] happened: start the flow (or end any old one when it's all off). */
    fun start(ctx: Context, entryId: Long) {
        val now = System.currentTimeMillis()
        val cur = Store.current(ctx)
        if (cur == null || cur.id != entryId) {
            end(ctx)
            return
        }
        val links = Store.allLinks(ctx)
        val places = chipLinks(links, LinkKind.PLACE)
        val people = chipLinks(links, LinkKind.PERSON)
        val e = flowEntry(cur, links) ?: return
        val defaultPlace = CheckInFlow.defaultPlace(e.place, Store.lastPlaceBefore(ctx, cur), places.map { it.id }.toSet())
        val defaultPeople = CheckInFlow.defaultPeople(e.people, Store.previousPeople(ctx, cur), people.map { it.id }.toSet())
        val available = setOfNotNull(
            FlowStep.WHERE.takeIf { places.isNotEmpty() },
            FlowStep.WHO.takeIf { people.isNotEmpty() },
            FlowStep.FEELING.takeIf { Store.allMoods(ctx).any { !it.archived } },
        )
        val st = CheckInFlow.start(e, Prefs.flowSettings(ctx), defaultPlace, defaultPeople, now, available)
        if (st == null) {
            end(ctx)
            return
        }
        apply(ctx, CheckInFlow.startEffects(st, e), now)
        save(ctx, st, now)
    }

    /** A tap on a flow chip or button. */
    fun tap(ctx: Context, t: FlowTap, shownStep: FlowStep?) {
        val now = System.currentTimeMillis()
        val state = FlowState.decode(Prefs.flowState(ctx))
        val e = flowEntry(Store.current(ctx), Store.allLinks(ctx))
        val r = CheckInFlow.tap(state, e, t, now, shownStep)
        apply(ctx, r.effects, now)
        if (r.state == null) end(ctx) else save(ctx, r.state, now)
    }

    /**
     * The flow to show now, or null. A flow that timed out, or whose entry changed (another
     * switch, an edit, turned off), is dropped here.
     */
    fun current(ctx: Context, cur: Entry?, links: List<Link>, now: Long): FlowState? {
        val raw = Prefs.flowState(ctx) ?: return null
        val live = CheckInFlow.reconcile(FlowState.decode(raw), flowEntry(cur, links), now)
        if (live == null) end(ctx)
        return live
    }

    fun end(ctx: Context) {
        if (Prefs.flowState(ctx) != null) Prefs.setFlowState(ctx, null)
        ctx.getSystemService(JobScheduler::class.java)?.cancel(FlowJob.JOB_ID)
    }

    private fun save(ctx: Context, st: FlowState, now: Long) {
        Prefs.setFlowState(ctx, st.encode())
        FlowJob.schedule(ctx, st.deadline - now)
    }

    private fun apply(ctx: Context, effects: List<FlowEffect>, now: Long) {
        effects.forEach { f ->
            when (f) {
                is FlowEffect.SetPlace -> Store.setPlace(ctx, f.entryId, f.placeId)
                is FlowEffect.SetPeople -> Store.setPeople(ctx, f.entryId, f.people)
                is FlowEffect.AddFeeling -> Store.addMoment(ctx, now, "", "", f.moodId, emptyList(), f.entryId)
            }
        }
    }
}

/** Redraws the widget when the check-in flow times out, so it goes back to the buttons by itself. */
class FlowJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread {
            try {
                WidgetRenderer.refresh(app)
            } catch (e: Throwable) {
                // The next redraw catches up.
            }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        const val JOB_ID = 4206
        private const val SLACK_MS = 30_000L

        fun schedule(ctx: Context, inMs: Long) {
            val scheduler = ctx.getSystemService(JobScheduler::class.java) ?: return
            val delay = maxOf(0L, inMs) + 1_000L
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, FlowJob::class.java))
                .setMinimumLatency(delay)
                .setOverrideDeadline(delay + SLACK_MS)
                .build()
            scheduler.schedule(info)
        }
    }
}
