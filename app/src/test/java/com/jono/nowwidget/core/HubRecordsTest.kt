package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubRecordsTest {
    @Test
    fun ownIdsRoundTripAndOtherDevicesDontMatch() {
        val gid = HubRecords.ownId("ph-3f9a1c", HubRecords.ENTRY, 812)
        assertEquals("ph-3f9a1c:e:812", gid)
        assertEquals(812L, HubRecords.ownLocal("ph-3f9a1c", HubRecords.ENTRY, gid))
        assertNull("another kind", HubRecords.ownLocal("ph-3f9a1c", HubRecords.MOMENT, gid))
        assertNull("another device", HubRecords.ownLocal("ph-000000", HubRecords.ENTRY, gid))
        assertNull("a PC id", HubRecords.ownLocal("ph-3f9a1c", HubRecords.ENTRY, "pc-1a2b3c:e:5f2e9d"))
    }

    @Test
    fun newestWinsAndTiesGoToTheHigherDevice() {
        assertTrue(HubRecords.wins(2, "pc-a", 1, "ph-z"))
        assertFalse(HubRecords.wins(1, "ph-z", 2, "pc-a"))
        assertTrue(HubRecords.wins(5, "ph-b", 5, "pc-a"))
        assertFalse(HubRecords.wins(5, "pc-a", 5, "ph-b"))
    }

    @Test
    fun theHashIgnoresVersionAndDeviceAndKeyOrder() {
        val a = HubRecords.entry("x:e:1", 1000L, "x:a:2", listOf("x:l:9", "x:l:3"), null, null, listOf("x:a:4"))
        val b = HubRecords.entry("x:e:1", 1000L, "x:a:2", listOf("x:l:3", "x:l:9", "x:l:3"), null, null, listOf("x:a:4", "x:a:2"))
        assertEquals("links sorted and distinct, the primary never an extra", HubRecords.hash(a), HubRecords.hash(b))
        assertEquals(HubRecords.hash(a), HubRecords.hash(a + mapOf("v" to 99L, "dev" to "pc-1", "seq" to 4L)))
        assertNotEquals(HubRecords.hash(a), HubRecords.hash(a + mapOf("start" to 1001L)))
    }

    @Test
    fun jsonIsCanonicalAndReadsBack() {
        val rec = HubRecords.moment(
            "ph-1:n:7", 5L, "note", "", "line \"one\"\nand\ttwo \\ ☕", null, "ph-1:e:3",
            listOf("ph-1:t:2"), listOf("link" to "ph-1:l:5", "activity" to "ph-1:a:1"), null,
        )
        val text = HubJson.write(rec)
        assertTrue(text.startsWith("{\"entry\":"))
        @Suppress("UNCHECKED_CAST")
        val back = Json.parse(text) as Map<String, Any?>
        assertEquals(rec["text"], back["text"])
        assertEquals(listOf("activity" to "ph-1:a:1", "link" to "ph-1:l:5"), HubRecords.refs(back))
        assertEquals(listOf("ph-1:t:2"), HubRecords.ids(back, "tags"))
        assertEquals(5L, HubRecords.long(back, "ts"))
        assertNull(HubRecords.str(back, "mood"))
        assertEquals(text, HubJson.write(back))
    }

    @Test
    fun aPcEntryReadsAsTheProtocolSays() {
        val pc = "{\"k\":\"entry\",\"id\":\"pc-1a2b3c:e:5f\",\"v\":1732650000123,\"dev\":\"pc-1a2b3c\",\"act\":\"ph-3f9a1c:a:4\"," +
            "\"start\":1732650000000,\"links\":[\"ph-3f9a1c:l:2\"],\"ctx\":null,\"title\":null,\"extras\":[]}"
        @Suppress("UNCHECKED_CAST")
        val r = Json.parse(pc) as Map<String, Any?>
        assertEquals("entry", HubRecords.str(r, "k"))
        assertEquals(HubRecords.ENTRY, HubRecords.letterOf("entry"))
        assertEquals(1732650000123L, HubRecords.long(r, "v"))
        assertEquals(4L, HubRecords.ownLocal("ph-3f9a1c", HubRecords.ACT, HubRecords.str(r, "act")!!))
        assertEquals(listOf("ph-3f9a1c:l:2"), HubRecords.ids(r, "links"))
        assertFalse(HubRecords.bool(r, "del"))
        assertTrue(HubRecords.bool(Json.parse("{\"del\":true}") as Map<String, Any?>, "del"))
    }

    @Test
    fun idleIsItsOwnId() {
        val e = HubRecords.entry("ph-1:e:1", 0L, HubRecords.IDLE, emptyList(), null, null, emptyList())
        assertEquals("idle", e["act"])
    }
}
