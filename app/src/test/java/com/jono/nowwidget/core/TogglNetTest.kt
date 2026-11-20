package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TogglNetTest {
    private fun stop(r: TogglReply, project: Boolean = false) = (TogglHttp.classify(r, project) as TogglHttp.Outcome.Stop).stop

    @Test
    fun okAndGoneAndRejected() {
        assertTrue(TogglHttp.classify(TogglReply(200, "{}")) is TogglHttp.Outcome.Ok)
        assertTrue(TogglHttp.classify(TogglReply(204, "")) is TogglHttp.Outcome.Ok)
        assertTrue(TogglHttp.classify(TogglReply(404, "not found")) is TogglHttp.Outcome.Gone)
        val r = TogglHttp.classify(TogglReply(400, "\"Tag name too long\"")) as TogglHttp.Outcome.Rejected
        assertEquals(400, r.code)
        assertTrue(r.why.contains("Tag name too long"))
        assertFalse(r.why.contains("\""))
    }

    @Test
    fun quotaReplies() {
        val s = stop(TogglReply(402, "You have hit your hourly limit for API calls. Current limit will reset in 1234 seconds.", 0, 1234))
        assertEquals(StopKind.QUOTA, s.kind)
        assertEquals(1_236_000L, s.waitMs)
        // Without headers the body still says so, and says how long.
        val b = stop(TogglReply(402, "hourly limit. Current limit will reset in 90 seconds"))
        assertEquals(StopKind.QUOTA, b.kind)
        assertEquals(92_000L, b.waitMs)
        // Quota text with no number: wait ten minutes.
        assertEquals(602_000L, stop(TogglReply(402, "Too many requests")).waitMs)
    }

    @Test
    fun aPlainPaymentRequiredIsThePlan() {
        val s = stop(TogglReply(402, "This feature is only available in paid plans"))
        assertEquals(StopKind.PLAN, s.kind)
        assertTrue(s.fatal)
        assertNull(TogglBackoff.delayMs(s, 1))
    }

    @Test
    fun authAndProjects() {
        assertEquals(StopKind.AUTH, stop(TogglReply(401, "")).kind)
        assertEquals(StopKind.AUTH, stop(TogglReply(403, "no")).kind)
        assertEquals(StopKind.PLAN, stop(TogglReply(403, "Projects are not available", null, null), project = true).kind)
        assertEquals(StopKind.PLAN, stop(TogglReply(402, "Pro feature", null, null), project = true).kind)
        assertTrue(stop(TogglReply(401, "")).fatal)
    }

    @Test
    fun rateAndServer() {
        val r = stop(TogglReply(429, "slow down"))
        assertEquals(StopKind.RATE, r.kind)
        assertEquals(60_000L, r.waitMs)
        assertEquals(StopKind.SERVER, stop(TogglReply(503, "")).kind)
        assertFalse(r.fatal)
    }

    @Test
    fun backoffDoublesToAnHour() {
        val net = TogglStop(StopKind.NETWORK, "offline")
        // No connection waits a fixed two minutes, not a doubling one.
        assertEquals(List(8) { 2 }, (1..8).map { (TogglBackoff.delayMs(net, it)!! / 60_000L).toInt() })
        assertEquals(120_000L, TogglBackoff.delayMs(TogglUnknown("lost"), 5))
        // 429 and 5xx double up to an hour.
        val srv = TogglStop(StopKind.SERVER, "oops")
        assertEquals(listOf(1, 2, 4, 8, 16, 32, 60, 60), (1..8).map { (TogglBackoff.delayMs(srv, it)!! / 60_000L).toInt() })
        // Toggl's own wait counts when it is longer.
        assertEquals(120_000L, TogglBackoff.delayMs(TogglStop(StopKind.RATE, "x", 120_000L), 1))
        // A full quota waits for its reset, not the back-off.
        assertEquals(1_000_000L, TogglBackoff.delayMs(TogglStop(StopKind.QUOTA, "x", 1_000_000L), 5))
        assertEquals(60_000L, TogglBackoff.delayMs(TogglStop(StopKind.QUOTA, "x", 0L), 1))
        assertEquals(3_600_000L, TogglBackoff.exponential(100))
    }

    @Test
    fun budgetAllowsTwentyFiveAnHour() {
        val b = RequestBudget()
        val t0 = 1_000_000_000L
        repeat(RequestBudget.LIMIT) { b.record(t0 + it * 2_000L) }
        assertEquals(25, b.used(t0 + 60_000L))
        assertEquals(t0 + 3_600_000L, b.freesAt(t0 + 60_000L))
        assertEquals(0, b.left(TogglBucket.WORKSPACE, t0 + 60_000L))
        // The oldest ages out an hour after it was sent.
        val wait = b.waitMs(TogglBucket.WORKSPACE, t0 + 60_000L)
        assertEquals(3_600_000L - 60_000L + 1_000L, wait)
        assertEquals(0L, b.waitMs(TogglBucket.WORKSPACE, t0 + 3_600_000L + 1_000L))
    }

    @Test
    fun budgetSpacesRequests() {
        val b = RequestBudget()
        assertEquals(0L, b.paceMs(5_000L))
        b.record(5_000L)
        assertEquals(1_100L, b.paceMs(5_000L))
        assertEquals(400L, b.paceMs(5_700L))
        assertEquals(0L, b.paceMs(6_100L))
    }

    @Test
    fun lowRemainingHeaderClosesJustThatBucket() {
        val b = RequestBudget()
        val now = 10_000_000L
        b.onReply(TogglBucket.WORKSPACE, now, 6, 1200L, false)
        assertEquals(0L, b.waitMs(TogglBucket.WORKSPACE, now))
        b.onReply(TogglBucket.WORKSPACE, now, 5, 1200L, false)
        assertEquals(1_201_000L, b.waitMs(TogglBucket.WORKSPACE, now))
        assertEquals(0L, b.waitMs(TogglBucket.ME, now))
        assertEquals(0L, b.waitMs(TogglBucket.WORKSPACE, now + 1_201_000L))
        // A quota reply closes it too, ten minutes when Toggl named no reset.
        val c = RequestBudget()
        c.onReply(TogglBucket.ME, now, null, null, true)
        assertEquals(600_000L, c.waitMs(TogglBucket.ME, now))
    }

    @Test
    fun leftIsTheLowerOfOursAndTogglsWord() {
        val b = RequestBudget()
        val now = 10_000_000L
        repeat(3) { b.record(now - 1000L * it) }
        assertEquals(22, b.left(TogglBucket.WORKSPACE, now))
        b.onReply(TogglBucket.WORKSPACE, now, 12, 3000L, false)
        assertEquals(7, b.left(TogglBucket.WORKSPACE, now))
        // Their number stops counting after its reset time.
        assertEquals(22, b.left(TogglBucket.WORKSPACE, now + 3_002_000L))
    }

    @Test
    fun budgetSurvivesBeingSavedAsText() {
        val b = RequestBudget()
        val now = 50_000_000L
        b.record(now - 2000L)
        b.record(now - 1000L)
        b.onReply(TogglBucket.ME, now, 3, 900L, false)
        val c = RequestBudget.decode(b.encode())
        assertEquals(2, c.used(now))
        assertEquals(901_000L, c.waitMs(TogglBucket.ME, now))
        assertEquals(b.encode(), c.encode())
        assertEquals(0, RequestBudget.decode(null).used(now))
        assertEquals(0, RequestBudget.decode("garbage|t:x,,y|b:=|r:a=b@c").used(now))
    }

    @Test
    fun oldRequestsAgeOutButAClockGoingBackwardsKeepsTheCount() {
        val b = RequestBudget()
        val now = 100_000_000L
        b.record(now - 4_000_000L)
        b.record(now - 1_000L)
        assertEquals(1, b.used(now))
        // The clock jumps back a day: what was counted still counts, and nothing waits more than an hour.
        repeat(RequestBudget.LIMIT - 1) { b.record(now + it) }
        val back = now - 86_400_000L
        assertEquals(RequestBudget.LIMIT, b.used(back))
        assertEquals(RequestBudget.WINDOW_MS + 1_000L, b.waitMs(TogglBucket.WORKSPACE, back))
    }

    @Test
    fun aForbiddenEntryIsRefusedNotABadToken() {
        val r = TogglHttp.classify(TogglReply(403, "no"), entryLevel = true) as TogglHttp.Outcome.Rejected
        assertEquals(403, r.code)
        assertEquals(StopKind.AUTH, stop(TogglReply(403, "no")).kind)
    }

    @Test
    fun oldestDayFirst() {
        assertEquals(
            listOf("2025-4-9", "2025-4-10", "2025-12-1", "2026-1-3"),
            TogglSync.oldestFirst(listOf("2026-1-3", "2025-12-1", "2025-4-10", "2025-4-9")),
        )
    }
}
