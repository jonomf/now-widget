package com.jono.nowwidget.core

import com.jono.nowwidget.core.CheckInNav.Save
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInNavTest {
    private fun m(id: Long, ts: Long, text: String = "hi", mood: Long? = null, tags: List<Long> = emptyList(), title: String = "") =
        Moment(id, ts, MomentKind.NOTE, title, text, mood, null, tags)

    @Test
    fun ordersByTimeThenId() {
        val r = CheckInNav.order(listOf(m(3, 20), m(2, 20), m(1, 30)))
        assertEquals(listOf(2L, 3L, 1L), r.map { it.id })
    }

    @Test
    fun opensOnTheNewestOrBlank() {
        assertEquals(2, CheckInNav.openAt(3))
        assertEquals(0, CheckInNav.openAt(0))
    }

    @Test
    fun steppingStopsAtTheEndsAndGoesPastNewestToBlank() {
        assertNull(CheckInNav.prev(0, 3))
        assertEquals(1, CheckInNav.prev(2, 3))
        assertEquals(2, CheckInNav.prev(3, 3)) // from the blank to the newest
        assertEquals(3, CheckInNav.next(2, 3)) // past the newest: blank
        assertNull(CheckInNav.next(3, 3))
        assertNull(CheckInNav.prev(0, 0))
        assertNull(CheckInNav.next(0, 0))
    }

    @Test
    fun emptyAndChanged() {
        assertTrue(CheckInNav.isEmpty(null, emptyList(), "  \n"))
        assertFalse(CheckInNav.isEmpty(1, emptyList(), ""))
        val l = m(1, 1, "hello", 2, listOf(5, 6))
        assertFalse(CheckInNav.changed(l, 2, setOf(6L, 5L), " hello "))
        assertTrue(CheckInNav.changed(l, 3, listOf(5L, 6L), "hello"))
        assertTrue(CheckInNav.changed(l, 2, listOf(5L), "hello"))
        assertTrue(CheckInNav.changed(l, 2, listOf(5L, 6L), "hello!"))
        assertFalse(CheckInNav.changed(null, null, emptyList(), ""))
        assertTrue(CheckInNav.changed(null, null, emptyList(), "x"))
    }

    @Test
    fun saveActions() {
        val l = m(1, 1, "hello", 2)
        assertEquals(Save.NONE, CheckInNav.saveAction(null, null, emptyList(), "", false))
        assertEquals(Save.CREATE, CheckInNav.saveAction(null, null, emptyList(), "x", false))
        assertEquals(Save.NONE, CheckInNav.saveAction(l, 2, emptyList(), "hello", false))
        assertEquals(Save.UPDATE, CheckInNav.saveAction(l, 2, emptyList(), "hello there", false))
        assertEquals(Save.ASK_DELETE, CheckInNav.saveAction(l, null, emptyList(), "", false))
        assertEquals(Save.NONE, CheckInNav.saveAction(l, null, emptyList(), "", true))
        assertEquals(Save.UPDATE, CheckInNav.saveAction(l, 2, emptyList(), "bye", true))
        // An imported moment with a title is kept even when emptied.
        assertEquals(Save.UPDATE, CheckInNav.saveAction(m(2, 1, "a", title = "Daylio"), null, emptyList(), "", false))
    }

    @Test
    fun headerSaysWhichOne() {
        assertEquals("New check-in", CheckInNav.header(null, "3:15 PM"))
        assertEquals("Editing 3:15 PM check-in", CheckInNav.header(m(1, 1), "3:15 PM"))
    }
}
