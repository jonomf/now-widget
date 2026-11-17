package com.jono.nowwidget.core

/** CSV the way spreadsheets and Daylio write it: quotes around fields with commas, quotes or newlines. */
object Csv {
    fun quote(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    fun line(fields: List<String>): String = fields.joinToString(",") { quote(it) }

    /** Every row, fields unquoted. Quoted fields may hold commas, doubled quotes and newlines. */
    fun parse(text: String): List<List<String>> {
        val s = text.trimStart('﻿')
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        var any = false // anything on the current row yet
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        quoted = false
                    }
                } else if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') {
                    // A Windows line break inside a note: keep it as a plain newline.
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> {
                        quoted = true
                        any = true
                    }
                    ',' -> {
                        row.add(field.toString())
                        field.setLength(0)
                        any = true
                    }
                    '\r' -> {}
                    '\n' -> {
                        if (any || field.isNotEmpty()) {
                            row.add(field.toString())
                            rows.add(row)
                        }
                        row = ArrayList()
                        field.setLength(0)
                        any = false
                    }
                    else -> {
                        field.append(c)
                        any = true
                    }
                }
            }
            i++
        }
        if (any || field.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }
}
