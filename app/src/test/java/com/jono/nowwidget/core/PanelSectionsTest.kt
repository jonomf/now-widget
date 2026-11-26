package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.jono.nowwidget.core.PanelSections.Section

class PanelSectionsTest {
    private fun m(id: Long, ts: Long, entry: Long?, tags: List<Long> = emptyList()) =
        Moment(id, ts, MomentKind.FEELING, "", "", null, entry, tags)

    @Test
    fun whichSectionsShow() {
        assertEquals(listOf(Section.DOING, Section.AT, Section.WITH, Section.FEELING), PanelSections.visible(idle = false, flowRunning = false))
        assertEquals(listOf(Section.DOING, Section.FEELING), PanelSections.visible(idle = true, flowRunning = false))
        assertEquals(emptyList<Section>(), PanelSections.visible(idle = false, flowRunning = true))
        assertEquals(emptyList<Section>(), PanelSections.visible(idle = true, flowRunning = true))
    }

    @Test
    fun latestCheckInIsTheNewestOnTheEntry() {
        val ms = listOf(m(1, 100, 7), m(3, 300, 7), m(2, 300, 7), m(4, 400, 8), m(5, 500, null))
        assertEquals(3L, PanelSections.latestCheckIn(ms, 7, null)!!.id) // same time: the higher id
        assertEquals(4L, PanelSections.latestCheckIn(ms, 8, null)!!.id)
        assertNull(PanelSections.latestCheckIn(ms, 9, null))
        // Imported ones don't count.
        assertEquals(1L, PanelSections.latestCheckIn(ms, 7, null, imported = setOf(2L, 3L))!!.id)
    }

    @Test
    fun whenIdleTheLatestLooseOneSinceTheGap() {
        val ms = listOf(m(1, 100, null), m(2, 200, 7), m(3, 300, null))
        assertEquals(3L, PanelSections.latestCheckIn(ms, null, 250)!!.id)
        assertNull(PanelSections.latestCheckIn(ms, null, 350))      // one from before the gap isn't this one
        assertEquals(3L, PanelSections.latestCheckIn(ms, null, null)!!.id)
    }

    @Test
    fun topTagsMostUsedFirstAndChosenOnesStay() {
        val tags = (1L..10L).map { FeelingTag(it, "t$it", "", it.toInt(), it == 10L) }
        val uses = mapOf(5L to 9, 2L to 3)
        val top = PanelSections.topTags(tags, uses, on = listOf(9L), max = 3)
        assertEquals(listOf(5L, 2L, 1L, 9L), top.map { it.id })
        // An archived tag only while it's on the check-in.
        assertEquals(true, PanelSections.topTags(tags, uses, on = listOf(10L), max = 20).any { it.id == 10L })
        assertEquals(false, PanelSections.topTags(tags, uses, on = emptyList(), max = 20).any { it.id == 10L })
        assertEquals(listOf(1L, 3L), PanelSections.toggle(listOf(1L, 2L, 3L), 2L))
        assertEquals(listOf(1L, 4L), PanelSections.toggle(listOf(1L), 4L))
    }
}
