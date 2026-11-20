package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The wire, scripted: each request is answered by the first rule that matches, or by [default]. */
class FakeTransport : TogglTransport {
    class Sent(val method: String, val path: String, val body: String?)

    val sent = ArrayList<Sent>()
    private val rules = ArrayList<Pair<(Sent) -> Boolean, (Sent) -> TogglReply>>()
    var default: (Sent) -> TogglReply = { TogglReply(200, "{}", 20, 3000L) }

    /** Answers requests where [match] holds; the rule added last wins. */
    fun on(match: (Sent) -> Boolean, answer: (Sent) -> TogglReply) {
        rules.add(0, match to answer)
    }

    fun on(method: String, pathStart: String, answer: (Sent) -> TogglReply) = on({ it.method == method && it.path.startsWith(pathStart) }, answer)

    override fun send(method: String, path: String, body: String?): TogglReply {
        val s = Sent(method, path, body)
        sent.add(s)
        val rule = rules.firstOrNull { it.first(s) }
        return (rule?.second ?: default)(s)
    }

    fun paths() = sent.map { it.method + " " + it.path }
}

class FakeClientStore : TogglClientStore {
    override val lock = Any()
    var budget: String? = null
    val projectMap = HashMap<Long, HashMap<String, Long>>()
    val listedSet = HashSet<Long>()
    val noProj = HashSet<Long>()
    val refusedSet = HashSet<String>()

    override fun budget() = budget
    override fun saveBudget(v: String) { budget = v }
    override fun projects(ws: Long): Map<String, Long> = projectMap[ws] ?: emptyMap()
    override fun addProjects(ws: Long, add: Map<String, Long>) { projectMap.getOrPut(ws) { HashMap() }.putAll(add) }
    override fun forgetProject(ws: Long, key: String) { projectMap[ws]?.remove(key) }
    override fun listed(ws: Long) = ws in listedSet
    override fun setListed(ws: Long, on: Boolean) { if (on) listedSet.add(ws) else listedSet.remove(ws) }
    override fun noProjects(ws: Long) = ws in noProj
    override fun setNoProjects(ws: Long) { noProj.add(ws) }
    override fun refused(ws: Long, key: String) = "$ws/$key" in refusedSet
    override fun addRefused(ws: Long, key: String) { refusedSet.add("$ws/$key") }
}

class TogglClientTest {
    private val ws = 77L
    private var clock = 1_000_000_000L
    private val sleeps = ArrayList<Long>()
    private val transport = FakeTransport()
    private val store = FakeClientStore()
    private val warnings = ArrayList<String>()
    private val client = TogglHttpClient(transport, store, { clock }, { clock += it; sleeps.add(it) }, warnings)

    private fun want(n: Int = 1, project: String = "Hobby"): TogglWant {
        val start = 1_700_000_000L + n * 3600L
        return TogglWant("2025-4-9/e$n", ws, project, "🎨 $project", listOf("Sam", "now"), start, start + 1800L)
    }

    private fun json(vararg kv: Pair<String, Any>) = "{" + kv.joinToString(",") { "\"${it.first}\":" + (it.second.let { v -> if (v is String) "\"$v\"" else v.toString() }) } + "}"

    /** Answers like Toggl: entries get ids from 9000, projects from 700, both lists empty. */
    private fun friendlyToggl() {
        var entry = 9000L
        var project = 700L
        transport.on("POST", "/workspaces/$ws/time_entries") { TogglReply(200, json("id" to entry++), 20, 3000L) }
        transport.on("POST", "/workspaces/$ws/projects") { TogglReply(200, json("id" to project++), 20, 3000L) }
        transport.on("GET", "/workspaces/$ws/projects") { TogglReply(200, "[]", 20, 3000L) }
    }

    // --- Pending records when the POST's answer is lost ---

    private class Day {
        val ledger = FakeTogglLedger()
        val stats = TogglStats()
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
    }

    private fun writeDay(d: Day, w: TogglWant) = TogglSync.writeDay(client, d.ledger, "2025-4-9", 0L, 86_400_000L, listOf(w), d.stats, d.errors, d.warnings, clock)

    @Test
    fun aNetworkErrorAfterThePostKeepsThePendingRecord() {
        friendlyToggl()
        transport.on({ it.method == "POST" && it.path.endsWith("/time_entries") }) { throw TogglMaybeSent("Toggl didn't answer (SocketTimeoutException)") }
        val d = Day()
        try {
            writeDay(d, want())
            fail("should stop")
        } catch (e: TogglUnknown) {
            assertEquals(StopKind.NETWORK, e.kind)
        }
        assertTrue(d.ledger.records.getValue("2025-4-9/e1").pending)
        assertEquals(120_000L, TogglBackoff.delayMs(TogglUnknown("x"), 3))
    }

    @Test
    fun aServerErrorOnThePostKeepsItPendingToo() {
        friendlyToggl()
        transport.on({ it.method == "POST" && it.path.endsWith("/time_entries") }) { TogglReply(503, "unavailable") }
        val d = Day()
        try {
            writeDay(d, want())
            fail("should stop")
        } catch (e: TogglUnknown) {
            // the entry may have been made
        }
        assertTrue(d.ledger.records.getValue("2025-4-9/e1").pending)
    }

    @Test
    fun anAnswerWithoutAnIdKeepsItPending() {
        friendlyToggl()
        transport.on({ it.method == "POST" && it.path.endsWith("/time_entries") }) { TogglReply(200, "{}") }
        val d = Day()
        try {
            writeDay(d, want())
            fail("should stop")
        } catch (e: TogglUnknown) {
        }
        assertTrue(d.ledger.records.getValue("2025-4-9/e1").pending)
    }

    @Test
    fun noConnectionOrARefusalLeavesNothingPending() {
        friendlyToggl()
        transport.on({ it.method == "POST" && it.path.endsWith("/time_entries") }) { throw TogglNotSent("Couldn't reach Toggl (UnknownHostException)") }
        val d = Day()
        try {
            writeDay(d, want())
            fail("should stop")
        } catch (e: TogglStop) {
            assertEquals(StopKind.NETWORK, e.kind)
            assertFalse(e is TogglUnknown)
        }
        assertTrue(d.ledger.records.isEmpty())
        // A 429 was received: nothing was made either.
        val t2 = FakeTransport()
        t2.default = { TogglReply(429, "slow down") }
        val c2 = TogglHttpClient(t2, FakeClientStore(), { clock }, { clock += it }, ArrayList())
        val d2 = Day()
        try {
            TogglSync.writeDay(c2, d2.ledger, "2025-4-9", 0L, 86_400_000L, listOf(want()), d2.stats, d2.errors, d2.warnings, clock)
            fail("should stop")
        } catch (e: TogglStop) {
            assertEquals(StopKind.RATE, e.kind)
        }
        assertTrue(d2.ledger.records.isEmpty())
    }

    @Test
    fun aGetThatTimesOutIsJustAStop() {
        transport.default = { throw TogglMaybeSent("slow") }
        try {
            client.entriesSince(0L)
            fail("should stop")
        } catch (e: TogglStop) {
            assertEquals(StopKind.NETWORK, e.kind)
            assertFalse(e is TogglUnknown)
        }
    }

    // --- Quota ---

    @Test
    fun aQuotaReplyIsReadFromItsHeadersAndClosesTheBucket() {
        transport.default = { TogglReply(402, "You have hit your hourly limit for API calls. Current limit will reset in 1234 seconds.", 0, 1234L) }
        try {
            client.delete(ws, 5L)
            fail("should stop")
        } catch (e: TogglStop) {
            assertEquals(StopKind.QUOTA, e.kind)
            assertEquals(1_236_000L, e.waitMs)
        }
        assertEquals(1, transport.sent.size)
        // The next request isn't even sent.
        try {
            client.delete(ws, 6L)
            fail("should stop")
        } catch (e: TogglStop) {
            assertEquals(StopKind.QUOTA, e.kind)
            assertTrue(e.waitMs in 1_000_000L..1_300_000L)
        }
        assertEquals(1, transport.sent.size)
        // The other bucket (/me) still works.
        transport.default = { TogglReply(200, "[]") }
        assertTrue(client.entriesSince(0L).isEmpty())
    }

    @Test
    fun fiveRequestsLeftStopsTheRun() {
        transport.default = { TogglReply(200, "", 5, 900L) }
        client.delete(ws, 1L)
        try {
            client.delete(ws, 2L)
            fail("should stop")
        } catch (e: TogglStop) {
            assertEquals(StopKind.QUOTA, e.kind)
        }
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun atMostTwentyFiveRequestsAnHourAndTheyAreSpaced() {
        transport.default = { TogglReply(200, "") }
        repeat(RequestBudget.LIMIT) { client.delete(ws, it.toLong()) }
        assertEquals(RequestBudget.LIMIT, transport.sent.size)
        assertEquals(RequestBudget.LIMIT - 1, sleeps.size)
        assertTrue(sleeps.all { it in 1L..RequestBudget.SPACING_MS })
        try {
            client.delete(ws, 99L)
            fail("should stop")
        } catch (e: TogglStop) {
            assertEquals(StopKind.QUOTA, e.kind)
        }
        assertEquals(RequestBudget.LIMIT, transport.sent.size)
        // An hour on, requests go again.
        clock += 3_700_000L
        client.delete(ws, 100L)
        assertEquals(RequestBudget.LIMIT + 1, transport.sent.size)
    }

    @Test
    fun theCountIsSavedBeforeEachRequestGoes() {
        var saved: String? = null
        transport.default = { saved = store.budget; TogglReply(200, "") }
        client.delete(ws, 1L)
        assertTrue(saved!!.startsWith("t:" + clock))
    }

    // --- Status codes ---

    @Test
    fun entryStatusCodes() {
        transport.on("DELETE", "/workspaces/$ws/time_entries/1") { TogglReply(404, "") }
        transport.on("PUT", "/workspaces/$ws/time_entries/2") { TogglReply(403, "no") }
        transport.on("POST", "/workspaces/$ws/time_entries") { TogglReply(403, "no") }
        transport.default = { TogglReply(200, "{}") }
        try { client.delete(ws, 1L); fail() } catch (e: TogglGone) { }
        try { client.update(ws, 2L, want(project = ""), true); fail() } catch (e: TogglRejected) { assertEquals(403, e.code) }
        try { client.create(want(project = ""), true); fail() } catch (e: TogglStop) { assertEquals(StopKind.AUTH, e.kind) }
    }

    @Test
    fun theRequestsLookRight() {
        friendlyToggl()
        val id = client.create(want(), true)
        assertEquals(9000L, id)
        val post = transport.sent.last()
        assertEquals("POST", post.method)
        assertEquals("/workspaces/$ws/time_entries", post.path)
        assertTrue(post.body!!.contains("\"project_id\":700"))
        assertTrue(post.body!!.contains("\"tags\":[\"Sam\",\"now\"]"))
        transport.default = { TogglReply(200, "[]") }
        client.entriesSince(1_700_000_123L)
        assertEquals("/me/time_entries?since=1700000123", transport.sent.last().path)
        client.delete(ws, 55L)
        assertEquals("DELETE /workspaces/$ws/time_entries/55", transport.sent.last().method + " " + transport.sent.last().path)
        assertNull(transport.sent.last().body)
    }

    @Test
    fun theAccountAndItsWorkspaces() {
        transport.on("GET", "/me") { TogglReply(200, """{"id":4242,"email":"x"}""") }
        transport.on("GET", "/me/workspaces") { TogglReply(200, """[{"id":77,"name":"Home"},{"id":78},{"name":"x"}]""") }
        assertEquals(4242L, client.me())
        assertEquals(listOf(77L to "Home", 78L to "Workspace 78"), client.workspaces())
        transport.on("GET", "/me/workspaces") { TogglReply(200, "not json") }
        try {
            client.workspaces()
            fail("should stop")
        } catch (e: TogglStop) {
            assertEquals(StopKind.SERVER, e.kind)
        }
    }

    // --- Projects ---

    @Test
    fun projectsAreListedOnceThenMadeOnceThenRemembered() {
        friendlyToggl()
        client.create(want(1), true)
        assertEquals(
            listOf("GET /workspaces/$ws/projects?per_page=200&page=1", "POST /workspaces/$ws/projects", "POST /workspaces/$ws/time_entries"),
            transport.paths(),
        )
        assertEquals(mapOf("hobby" to 700L), store.projects(ws))
        transport.sent.clear()
        // Same activity: straight to the entry, with the remembered id.
        client.create(want(2), true)
        assertEquals(listOf("POST /workspaces/$ws/time_entries"), transport.paths())
        assertTrue(transport.sent[0].body!!.contains("\"project_id\":700"))
        // Another activity: made without listing again. Names match without regard to case.
        transport.sent.clear()
        client.create(want(3, "Read"), true)
        assertEquals(listOf("POST /workspaces/$ws/projects", "POST /workspaces/$ws/time_entries"), transport.paths())
        transport.sent.clear()
        client.create(want(4, "HOBBY"), true)
        assertEquals(listOf("POST /workspaces/$ws/time_entries"), transport.paths())
    }

    @Test
    fun anExistingProjectIsUsedNotMadeAgain() {
        friendlyToggl()
        transport.on("GET", "/workspaces/$ws/projects") { TogglReply(200, """[{"id":31,"name":"Hobby"},{"id":32,"name":"Other"}]""") }
        client.create(want(1), true)
        assertEquals(listOf("GET /workspaces/$ws/projects?per_page=200&page=1", "POST /workspaces/$ws/time_entries"), transport.paths())
        assertTrue(transport.sent[1].body!!.contains("\"project_id\":31"))
        assertEquals(mapOf("hobby" to 31L, "other" to 32L), store.projects(ws))
    }

    @Test
    fun aProjectListThatCantBeReadIsNotMarkedListed() {
        transport.on("GET", "/workspaces/$ws/projects") { TogglReply(200, "<html>oops</html>") }
        try {
            client.create(want(1), true)
            fail("should stop")
        } catch (e: TogglStop) {
            assertEquals(StopKind.SERVER, e.kind)
        }
        assertFalse(store.listed(ws))
        assertTrue(store.projects(ws).isEmpty())
    }

    @Test
    fun aPlanWithoutProjectsSendsEntriesWithoutOneAndStopsAsking() {
        friendlyToggl()
        transport.on("POST", "/workspaces/$ws/projects") { TogglReply(402, "This feature is only available in paid plans") }
        client.create(want(1), true)
        assertTrue(store.noProjects(ws))
        assertFalse(transport.sent.last().body!!.contains("project_id"))
        assertEquals(1, warnings.size)
        transport.sent.clear()
        client.create(want(2), true)
        assertEquals(listOf("POST /workspaces/$ws/time_entries"), transport.paths())
    }

    @Test
    fun aRefusedProjectNameIsRememberedAndNotTriedEveryRun() {
        friendlyToggl()
        transport.on("POST", "/workspaces/$ws/projects") { TogglReply(400, "\"Name has already been taken\"") }
        client.create(want(1), true)
        assertFalse(transport.sent.last().body!!.contains("project_id"))
        assertTrue(store.refused(ws, "hobby"))
        transport.sent.clear()
        // A later run, with a fresh client: no project request at all.
        val again = TogglHttpClient(transport, store, { clock }, { clock += it }, ArrayList())
        again.create(want(2), true)
        assertEquals(listOf("POST /workspaces/$ws/time_entries"), transport.paths())
        // "Send now" clears the refusals (Prefs.clearTogglProjectRefusals), after which it is tried again.
        store.refusedSet.clear()
        transport.sent.clear()
        again.create(want(3), true)
        assertEquals("POST /workspaces/$ws/projects", transport.paths()[0])
    }

    @Test
    fun aBadRequestOnAStaleProjectForgetsItAndRetriesOnce() {
        friendlyToggl()
        store.addProjects(ws, mapOf("hobby" to 31L))
        store.setListed(ws, true)
        var posts = 0
        transport.on({ it.method == "POST" && it.path.endsWith("/time_entries") }) {
            posts++
            if (it.body!!.contains("\"project_id\":31")) TogglReply(400, "\"Project 31 doesn't exist\"") else TogglReply(200, "{\"id\":9100}")
        }
        // The list now has the real one.
        transport.on("GET", "/workspaces/$ws/projects") { TogglReply(200, """[{"id":44,"name":"Hobby"}]""") }
        val id = client.create(want(1), true)
        assertEquals(9100L, id)
        assertEquals(2, posts)
        assertTrue(transport.sent.last().body!!.contains("\"project_id\":44"))
        assertEquals(mapOf("hobby" to 44L), store.projects(ws))
        assertTrue(store.listed(ws))
    }

    @Test
    fun theProjectRetryHappensOnlyOnce() {
        friendlyToggl()
        store.addProjects(ws, mapOf("hobby" to 31L))
        store.setListed(ws, true)
        transport.on({ it.method == "POST" && it.path.endsWith("/time_entries") }) { TogglReply(400, "\"Project is archived\"") }
        transport.on("GET", "/workspaces/$ws/projects") { TogglReply(200, """[{"id":44,"name":"Hobby"}]""") }
        try {
            client.create(want(1), true)
            fail("should be refused")
        } catch (e: TogglRejected) {
            assertEquals(400, e.code)
        }
        assertEquals(2, transport.sent.count { it.method == "POST" && it.path.endsWith("/time_entries") })
    }

    @Test
    fun aBadRequestAboutTagsDoesNotTouchTheProject() {
        friendlyToggl()
        store.addProjects(ws, mapOf("hobby" to 31L))
        store.setListed(ws, true)
        transport.on({ it.method == "POST" && it.path.endsWith("/time_entries") }) { TogglReply(400, "\"Tag name is too long\"") }
        try {
            client.create(want(1), true)
            fail("should be refused")
        } catch (e: TogglRejected) {
        }
        assertEquals(1, transport.sent.size)
        assertEquals(mapOf("hobby" to 31L), store.projects(ws))
    }
}
