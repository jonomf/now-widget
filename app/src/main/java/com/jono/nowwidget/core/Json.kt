package com.jono.nowwidget.core

/**
 * A small JSON reader: objects become Map<String, Any?>, arrays List<Any?>, numbers Long or
 * Double, plus String, Boolean and null. Enough for Daylio backups, and it runs the same in JVM
 * tests as on the phone (Android's org.json is only a stub there).
 */
class Json private constructor(private val s: String) {
    private var i = 0

    companion object {
        fun parse(text: String): Any? {
            val p = Json(text.trimStart('﻿'))
            p.ws()
            val v = p.value()
            p.ws()
            if (p.i != p.s.length) p.fail("Unexpected text after the end")
            return v
        }
    }

    private fun fail(msg: String): Nothing = throw IllegalArgumentException("Bad JSON at $i: $msg")

    private fun ws() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
    }

    private fun value(): Any? {
        if (i >= s.length) fail("Unexpected end")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> word("true", true)
            'f' -> word("false", false)
            'n' -> word("null", null)
            else -> if (c == '-' || c in '0'..'9') num() else fail("Unexpected '$c'")
        }
    }

    private fun word(w: String, v: Any?): Any? {
        if (!s.startsWith(w, i)) fail("Expected $w")
        i += w.length
        return v
    }

    private fun obj(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        i++
        ws()
        if (i < s.length && s[i] == '}') {
            i++
            return out
        }
        while (true) {
            ws()
            if (i >= s.length || s[i] != '"') fail("Expected a key")
            val k = str()
            ws()
            if (i >= s.length || s[i] != ':') fail("Expected ':'")
            i++
            ws()
            out[k] = value()
            ws()
            if (i >= s.length) fail("Unexpected end")
            when (s[i]) {
                ',' -> i++
                '}' -> {
                    i++
                    return out
                }
                else -> fail("Expected ',' or '}'")
            }
        }
    }

    private fun arr(): List<Any?> {
        val out = ArrayList<Any?>()
        i++
        ws()
        if (i < s.length && s[i] == ']') {
            i++
            return out
        }
        while (true) {
            ws()
            out.add(value())
            ws()
            if (i >= s.length) fail("Unexpected end")
            when (s[i]) {
                ',' -> i++
                ']' -> {
                    i++
                    return out
                }
                else -> fail("Expected ',' or ']'")
            }
        }
    }

    private fun str(): String {
        i++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) fail("Unterminated string")
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= s.length) fail("Unterminated escape")
                    when (val e = s[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) fail("Short \\u escape")
                            val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("Bad \\u escape")
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> fail("Bad escape '$e'")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun num(): Any {
        val start = i
        if (s[i] == '-') i++
        while (i < s.length && s[i] in '0'..'9') i++
        var isInt = true
        if (i < s.length && s[i] == '.') {
            isInt = false
            i++
            while (i < s.length && s[i] in '0'..'9') i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            isInt = false
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            while (i < s.length && s[i] in '0'..'9') i++
        }
        val t = s.substring(start, i)
        if (isInt) t.toLongOrNull()?.let { return it }
        return t.toDoubleOrNull() ?: fail("Bad number '$t'")
    }
}
