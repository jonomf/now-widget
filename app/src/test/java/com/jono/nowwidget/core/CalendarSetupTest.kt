package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarSetupTest {
    private val me = "jono@gmail.com"
    private val g = "com.google"

    private fun row(id: Long, name: String, syncId: String?, synced: Boolean = true, visible: Boolean = true, access: Int = 700, color: Int = 0xFF0B8043.toInt()) =
        CalRow(id, CalIdentity(me, g, syncId, name), color, access, visible, synced)

    private val log = row(10, "Log", "log@group.calendar.google.com")
    private val work = row(11, "Work", "work@group.calendar.google.com")
    private val play = row(12, "Play", "play@group.calendar.google.com")
    private val rows = listOf(log, work, play)

    @Test
    fun refsRoundTrip() {
        val r = log.ref()
        assertEquals(r, CalRef.decode(r.encode()))
        val local = CalRef(5, CalIdentity("Phone", "LOCAL", null, "My log"))
        assertEquals(local, CalRef.decode(local.encode()))
        assertNull(CalRef.decode(""))
        assertNull(CalRef.decode("12"))
        val m = mapOf(work.identity.key to log.ref())
        assertEquals(m, CalendarSetup.decodeAreaLogs(CalendarSetup.encodeAreaLogs(m)))
    }

    @Test
    fun matchByIdThenReFindByIdentity() {
        assertEquals(log, CalendarMatch.resolve(log.ref(), rows))
        // The row id now belongs to another calendar, and Log came back under a new id.
        val swapped = listOf(row(10, "Holidays", "holidays@x"), log.copy(id = 40), work)
        assertEquals(40L, CalendarMatch.resolve(log.ref(), swapped)?.id)
        // Renamed in Google Calendar: the sync id still matches.
        assertEquals(10L, CalendarMatch.resolve(log.ref(), listOf(log.copy(identity = log.identity.copy(name = "Diary"))))?.id)
        // Gone: nothing, not whatever has its id now.
        assertNull(CalendarMatch.resolve(log.ref(), listOf(row(10, "Holidays", "holidays@x"))))
        // Another account's calendar with the same sync id is a different calendar.
        assertNull(CalendarMatch.resolve(log.ref(), listOf(log.copy(identity = log.identity.copy(account = "other@gmail.com")))))
        // No sync id (local): the name decides; two with that name is ambiguous.
        val l = CalRow(5, CalIdentity("Phone", "LOCAL", null, "My log"), 0, 700, true, true)
        assertEquals(6L, CalendarMatch.resolve(l.ref(), listOf(l.copy(id = 6)))?.id)
        assertNull(CalendarMatch.resolve(l.ref(), listOf(l.copy(id = 6), l.copy(id = 7))))
    }

    @Test
    fun resolvesAllGood() {
        val s = CalendarSetup.resolve(rows, log.ref(), false, mapOf("Work" to work.ref(), "Play" to play.ref(), "" to null), emptyMap())
        assertNull(s.problem)
        assertEquals(setOf(10L), s.logCalendars)
        assertEquals(10L, s.target("Work"))
        assertEquals(11L, s.areaOf("Work "))
        assertNull(s.areaOf(""))
        assertEquals(setOf(11L, 12L), s.areaCalendars)
    }

    @Test
    fun swappedLogCalendarStopsEverything() {
        val swapped = listOf(row(10, "Holidays", "holidays@x"), work, play)
        val s = CalendarSetup.resolve(swapped, log.ref(), false, mapOf("Work" to work.ref()), emptyMap())
        assertTrue(s.problem!!.startsWith("Pick the log calendar again"))
        assertTrue(s.logCalendars.isEmpty())
        assertNull(s.target("Work"))
    }

    @Test
    fun swappedAreaStopsEverything() {
        val s = CalendarSetup.resolve(listOf(log, play), log.ref(), false, mapOf("Work" to work.ref()), emptyMap())
        assertTrue(s.problem!!.startsWith("Pick the area for Work again"))
        assertTrue(s.logCalendars.isEmpty())
    }

    @Test
    fun unsyncedOrHiddenLogCalendarStopsEverything() {
        listOf(log.copy(synced = false), log.copy(visible = false)).forEach { bad ->
            val s = CalendarSetup.resolve(listOf(bad, work), log.ref(), false, mapOf("Work" to work.ref()), emptyMap())
            assertTrue(s.problem!!.contains("isn't synced and shown"))
            assertTrue(s.logCalendars.isEmpty())
        }
        val ro = CalendarSetup.resolve(listOf(log.copy(access = 200)), log.ref(), false, emptyMap(), emptyMap())
        assertTrue(ro.problem!!.startsWith("The app can't add events"))
        assertEquals("No log calendar chosen.", CalendarSetup.resolve(rows, null, false, emptyMap(), emptyMap()).problem)
    }

    @Test
    fun perAreaLogsOnlyForMappedAreas() {
        val workLog = row(20, "Work log", "wl@x")
        val playLog = row(21, "Play log", "pl@x")
        val all = rows + workLog + playLog
        val logs = mapOf(work.identity.key to workLog.ref(), play.identity.key to playLog.ref())
        val s = CalendarSetup.resolve(all, log.ref(), true, mapOf("Work" to work.ref()), logs)
        assertNull(s.problem)
        // Play isn't mapped by any group: its log calendar isn't chosen.
        assertEquals(setOf(10L, 20L), s.chosen)
        assertEquals(20L, s.target("Work"))
        assertEquals(10L, s.target("Other"))
        assertEquals(mapOf(work.identity.key to workLog.ref()), CalendarSetup.prune(logs, mapOf("Work" to work.ref())))
        // A per-area log calendar that's gone stops everything.
        val gone = CalendarSetup.resolve(rows, log.ref(), true, mapOf("Work" to work.ref()), logs)
        assertTrue(gone.problem!!.startsWith("Pick the log calendar for Work again"))
    }

    @Test
    fun hiddenAreasStillCount_unsyncedOnesAreNamed() {
        val hidden = work.copy(visible = false)
        val off = play.copy(synced = false)
        val s = CalendarSetup.resolve(listOf(log, hidden, off), log.ref(), false, mapOf("Work" to work.ref(), "Play" to play.ref()), emptyMap())
        assertNull(s.problem)
        assertEquals(setOf(11L, 12L), s.areaCalendars)
        assertEquals(listOf(12L), s.unsyncedAreas.map { it.id })
    }
}
