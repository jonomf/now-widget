package com.jono.nowwidget.core

import java.security.MessageDigest

/**
 * The Now hub's records (see hub/PROTOCOL.md): every row of the log as a JSON object with a global
 * id and a version, shared with the Windows Now widget through a Google Sheet. Pure, so it's
 * unit-tested. Records are plain maps; [HubJson] writes them with sorted keys, so the same content
 * always gives the same text and the same [hash].
 */
object HubRecords {
    const val IDLE = "idle"

    const val ACT = 'a'
    const val LINK = 'l'
    const val MOOD = 'm'
    const val TAG = 't'
    const val ENTRY = 'e'
    const val MOMENT = 'n'

    fun kindOf(letter: Char): String = when (letter) {
        ACT -> "act"
        LINK -> "link"
        MOOD -> "mood"
        TAG -> "tag"
        ENTRY -> "entry"
        MOMENT -> "moment"
        else -> throw IllegalArgumentException("letter $letter")
    }

    fun letterOf(kind: String): Char? = when (kind) {
        "act" -> ACT
        "link" -> LINK
        "mood" -> MOOD
        "tag" -> TAG
        "entry" -> ENTRY
        "moment" -> MOMENT
        else -> null
    }

    /** This install's id for one of its own rows: `ph-3f9a1c:e:812`. */
    fun ownId(device: String, letter: Char, localId: Long): String = "$device:$letter:$localId"

    /** The local row id when [gid] is one of this install's own rows of that kind; null otherwise. */
    fun ownLocal(device: String, letter: Char, gid: String): Long? {
        val prefix = "$device:$letter:"
        return if (gid.startsWith(prefix)) gid.substring(prefix.length).toLongOrNull() else null
    }

    /** Newest version wins; on a tie, the higher device name (every device decides the same way). */
    fun wins(v: Long, dev: String, haveV: Long, haveDev: String): Boolean =
        if (v != haveV) v > haveV else dev > haveDev

    /** The content's hash: everything but the version and device, so a re-send of the same content isn't a change. */
    fun hash(rec: Map<String, Any?>): String {
        val text = HubJson.write(rec.filterKeys { it != "v" && it != "dev" && it != "seq" })
        val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun act(gid: String, a: Act): Map<String, Any?> = mapOf(
        "k" to "act", "id" to gid, "name" to a.name, "emoji" to a.emoji, "color" to a.color.toLong(),
        "pos" to a.pos.toLong(), "show" to a.show, "group" to a.group, "archived" to a.archived,
    )

    fun link(gid: String, l: Link): Map<String, Any?> = mapOf(
        "k" to "link", "id" to gid, "title" to l.title, "kind" to l.kind, "emoji" to l.emoji,
        "pos" to l.pos.toLong(), "pinned" to l.pinned, "archived" to l.archived,
    )

    fun mood(gid: String, m: Mood): Map<String, Any?> = mapOf(
        "k" to "mood", "id" to gid, "name" to m.name, "level" to m.level.toLong(), "emoji" to m.emoji,
        "pos" to m.pos.toLong(), "archived" to m.archived,
    )

    fun tag(gid: String, t: FeelingTag): Map<String, Any?> = mapOf(
        "k" to "tag", "id" to gid, "name" to t.name, "emoji" to t.emoji, "pos" to t.pos.toLong(), "archived" to t.archived,
    )

    /** An entry: ids of other records are already global ([act] is [IDLE] for doing nothing). */
    fun entry(gid: String, start: Long, act: String, links: Collection<String>, ctx: String?, title: String?, extras: Collection<String>): Map<String, Any?> =
        mapOf(
            "k" to "entry", "id" to gid, "act" to act, "start" to start,
            "links" to links.distinct().sorted(), "ctx" to ctx, "title" to title,
            "extras" to extras.filter { it != act }.distinct().sorted(),
        )

    fun moment(
        gid: String, ts: Long, kind: String, title: String, text: String, mood: String?, entry: String?,
        tags: Collection<String>, refs: Collection<Pair<String, String>>, src: String?,
    ): Map<String, Any?> = mapOf(
        "k" to "moment", "id" to gid, "ts" to ts, "kind" to kind, "title" to title, "text" to text,
        "mood" to mood, "entry" to entry, "tags" to tags.distinct().sorted(),
        "refs" to refs.distinct().sortedWith(compareBy({ it.first }, { it.second })).map { listOf(it.first, it.second) },
        "src" to src,
    )

    fun tombstone(gid: String, kind: String): Map<String, Any?> = mapOf("k" to kind, "id" to gid, "del" to true)

    // --- Reading a record that came from the hub ---

    fun str(r: Map<String, Any?>, key: String): String? = (r[key] as? String)

    fun long(r: Map<String, Any?>, key: String): Long? = when (val v = r[key]) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        else -> null
    }

    fun bool(r: Map<String, Any?>, key: String): Boolean = r[key] == true

    fun ids(r: Map<String, Any?>, key: String): List<String> =
        (r[key] as? List<*>)?.mapNotNull { it as? String }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList()

    fun refs(r: Map<String, Any?>): List<Pair<String, String>> =
        (r["refs"] as? List<*>)?.mapNotNull { p ->
            val l = p as? List<*> ?: return@mapNotNull null
            val k = l.getOrNull(0) as? String
            val id = l.getOrNull(1) as? String
            if (k != null && id != null) k to id else null
        } ?: emptyList()
}

/** Writes JSON with object keys sorted, so the same content always gives the same text. */
object HubJson {
    fun write(v: Any?): String = StringBuilder().also { append(it, v) }.toString()

    private fun append(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> str(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Long, is Int, is Short, is Byte -> sb.append(v.toString())
            is Double -> sb.append(if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString() else v.toString())
            is Float -> append(sb, v.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                v.entries.map { it.key.toString() to it.value }.sortedBy { it.first }.forEachIndexed { i, (k, x) ->
                    if (i > 0) sb.append(',')
                    str(sb, k)
                    sb.append(':')
                    append(sb, x)
                }
                sb.append('}')
            }
            is Collection<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x ->
                    if (i > 0) sb.append(',')
                    append(sb, x)
                }
                sb.append(']')
            }
            else -> str(sb, v.toString())
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' || c == ' ' || c == ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
