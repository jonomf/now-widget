package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvJsonTest {
    @Test
    fun csvQuotesOnlyWhenNeeded() {
        assertEquals("plain", Csv.quote("plain"))
        assertEquals("\"a, b\"", Csv.quote("a, b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.quote("say \"hi\""))
        assertEquals("\"two\nlines\"", Csv.quote("two\nlines"))
    }

    @Test
    fun csvParsesQuotedCommasNewlinesAndBom() {
        val text = "﻿a,b,c\r\n1,\"x, y\",\"line1\r\nline2\"\r\n2,,\"q \"\"z\"\"\"\n\n"
        val rows = Csv.parse(text)
        assertEquals(listOf("a", "b", "c"), rows[0])
        assertEquals(listOf("1", "x, y", "line1\nline2"), rows[1])
        assertEquals(listOf("2", "", "q \"z\""), rows[2])
        assertEquals(3, rows.size)
    }

    @Test
    fun csvRoundTrip() {
        val fields = listOf("a", "b, c", "\"d\"", "e\nf", "")
        assertEquals(listOf(fields), Csv.parse(Csv.line(fields) + "\n"))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun jsonReadsWhatDaylioWrites() {
        val v = Json.parse("""{"a": [1, -2, 3.5, 1e3], "s": "q\"\\\/\né😀", "t": true, "f": false, "n": null, "o": {}}""")
            as Map<String, Any?>
        assertEquals(listOf(1L, -2L, 3.5, 1000.0), v["a"])
        assertEquals("q\"\\/\né😀", v["s"])
        assertEquals(true, v["t"])
        assertEquals(false, v["f"])
        assertEquals(null, v["n"])
        assertEquals(emptyMap<String, Any?>(), v["o"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun jsonRejectsJunk() {
        Json.parse("{\"a\": }")
    }
}
