package com.jono.nowwidget.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The write protocol and the write-target decision, against a fake ledger and a fake vault folder. */
class ExportProtocolTest {
    /** Mirrors the SQL: `dirty` is a counter, cleared only if it's still what was read. */
    private class Ledger : DayLedger {
        val dirty = HashMap<String, Int>()
        val hashes = HashMap<String, String>()
        val docs = HashMap<String, String>()
        fun mark(key: String) {
            dirty[key] = (dirty[key] ?: 0) + 1
        }

        override fun gen(key: String) = dirty[key] ?: 0
        override fun hash(key: String): String? = hashes[key]
        override fun docId(key: String): String? = docs[key]
        override fun saveHash(key: String, docId: String, hash: String) {
            docs[key] = docId
            hashes[key] = hash
        }

        override fun clearIfUnchanged(key: String, gen: Int): Boolean {
            if ((dirty[key] ?: 0) == gen) dirty[key] = 0
            return (dirty[key] ?: 0) == 0
        }
    }

    private class Folder : NoteFolder {
        val files = LinkedHashMap<String, Pair<String, String>>() // docId -> (name, text)
        val touched = ArrayList<String>() // names created or written, in order
        var failWriteAfter: Int? = null // chars written before the "crash"
        var beforeWrite: () -> Unit = {}
        var failCreate = false
        private var n = 0

        fun add(name: String, text: String): String {
            val id = "doc${n++}"
            files[id] = name to text
            return id
        }

        override fun files(): List<NoteFile> = files.map { NoteFile(it.key, it.value.first) }
        override fun head(docId: String): String = files.getValue(docId).second.take(256)
        override fun create(name: String): String {
            if (failCreate) throw IOException("no space")
            touched.add(name)
            return add(name, "")
        }

        override fun write(docId: String, text: String) {
            val name = files.getValue(docId).first
            touched.add(name)
            beforeWrite()
            val cut = failWriteAfter
            if (cut != null) {
                files[docId] = name to text.take(cut)
                throw IOException("interrupted")
            }
            files[docId] = name to text
        }
    }

    private val key = "2025-4-9"
    private val name = "2025-4-9 (now).md"
    private val body = "#log-now\nAt [[The Loft]]\n"

    private fun run(l: Ledger, f: Folder, force: Boolean = false, text: String? = body, nm: String = name) =
        ExportProtocol.writeDay(l, f, { f.files() }, key, nm, force) { text }

    @Test
    fun aWrittenDayIsCleanAndHashed() {
        val l = Ledger().also { it.mark(key) }
        val f = Folder()
        assertEquals(ExportProtocol.Result.Written, run(l, f))
        assertEquals(0, l.gen(key))
        assertEquals(ObsidianDay.hash(body), l.hash(key))
        assertEquals(body, f.files.values.single().second)
        // Next time it's unchanged: the file isn't opened.
        l.mark(key)
        f.touched.clear()
        assertEquals(ExportProtocol.Result.Unchanged, run(l, f))
        assertEquals(0, l.gen(key))
        assertTrue(f.touched.isEmpty())
    }

    @Test
    fun aWriteCutShortLeavesTheDayDirtyAndIsRedone() {
        val l = Ledger().also { it.mark(key) }
        val f = Folder()
        f.failWriteAfter = 5
        val r = run(l, f)
        assertTrue(r is ExportProtocol.Result.Failed)
        // Dirty still, and the hash can't match anything.
        assertEquals(1, l.gen(key))
        assertEquals("", l.hash(key))
        assertEquals("#log-", f.files.values.single().second)
        // The next ordinary (not forced) run rewrites the whole note.
        f.failWriteAfter = null
        assertEquals(ExportProtocol.Result.Written, run(l, f))
        assertEquals(body, f.files.values.single().second)
        assertEquals(0, l.gen(key))
        assertEquals(ObsidianDay.hash(body), l.hash(key))
    }

    @Test
    fun aForcedRewriteThatIsCutShortIsAlsoRedone() {
        val l = Ledger().also { it.mark(key) }
        val f = Folder()
        assertEquals(ExportProtocol.Result.Written, run(l, f))
        // Same text, forced (the "Write today" button), cut short: the old hash must not survive.
        f.failWriteAfter = 3
        assertTrue(run(l, f, force = true) is ExportProtocol.Result.Failed)
        assertEquals("", l.hash(key))
        l.mark(key) // the next job
        f.failWriteAfter = null
        assertEquals(ExportProtocol.Result.Written, run(l, f))
        assertEquals(body, f.files.values.single().second)
    }

    @Test
    fun theHashIsBlankedBeforeTheFileIsOpened() {
        val l = Ledger().also { it.mark(key) }
        val f = Folder()
        val seen = ArrayList<String?>()
        f.beforeWrite = { seen.add(l.hash(key)) }
        run(l, f)
        assertEquals(listOf<String?>(""), seen)
    }

    @Test
    fun aChangeDuringTheWriteKeepsTheDayDirty() {
        val l = Ledger().also { it.mark(key) }
        val f = Folder()
        f.beforeWrite = { l.mark(key) } // something is logged while the file is being written
        assertEquals(ExportProtocol.Result.Written, run(l, f))
        assertEquals(2, l.gen(key))
        // Its hash is of what was written, but the day is still dirty, so the next run looks again.
        f.beforeWrite = {}
        assertEquals(ExportProtocol.Result.Unchanged, run(l, f, text = body))
        assertEquals(0, l.gen(key))
        assertEquals(ExportProtocol.Result.Written, run(l.also { it.mark(key) }, f, text = body + "- 9a Eat\n"))
    }

    @Test
    fun aChangeWhileRenderingKeepsTheDayDirty() {
        val l = Ledger().also { it.mark(key) }
        val f = Folder()
        val r = ExportProtocol.writeDay(l, f, { f.files() }, key, name, false) {
            l.mark(key)
            body
        }
        assertEquals(ExportProtocol.Result.Written, r)
        assertEquals(2, l.gen(key))
    }

    @Test
    fun aFailureAnywhereLeavesTheDayDirty() {
        val l = Ledger().also { it.mark(key) }
        val f = Folder()
        f.failCreate = true
        assertTrue(run(l, f) is ExportProtocol.Result.Failed)
        assertEquals(1, l.gen(key))
        assertNull(l.hash(key))
        val r = ExportProtocol.writeDay(l, f, { f.files() }, key, name, false) { throw IllegalStateException("boom") }
        assertTrue(r is ExportProtocol.Result.Failed)
        assertEquals(1, l.gen(key))
    }

    @Test
    fun nothingToWriteClearsTheMark() {
        val l = Ledger().also { it.mark(key) }
        val f = Folder()
        assertEquals(ExportProtocol.Result.Skipped, run(l, f, text = null))
        assertEquals(0, l.gen(key))
        assertTrue(f.files.isEmpty())
    }

    @Test
    fun pendingIsDirtyDaysNotJustTriedAtTheSameCounter() {
        val dirty = mapOf("a" to 1, "b" to 3, "c" to 2)
        assertEquals(setOf("a", "b", "c"), ExportProtocol.pending(dirty, emptyMap()).toSet())
        // Tried and failed (still the same counter): not again in this run.
        assertEquals(listOf("c"), ExportProtocol.pending(dirty, mapOf("a" to 1, "b" to 3)))
        // Marked again since: tried again.
        assertEquals(setOf("b", "c"), ExportProtocol.pending(dirty, mapOf("a" to 1, "b" to 2, "c" to 1)).toSet())
        assertTrue(ExportProtocol.pending(emptyMap(), mapOf("a" to 1)).isEmpty())
    }

    // --- Which file is written ---

    @Test
    fun aFileThatIsNotOursIsRefusedAndLeftAlone() {
        val l = Ledger().also { it.mark(key) }
        val f = Folder()
        val id = f.add(name, "# My own note about the day\n")
        val r = run(l, f)
        assertTrue(r is ExportProtocol.Result.Refused)
        assertEquals("# My own note about the day\n", f.files.getValue(id).second)
        assertTrue(f.touched.isEmpty())
        assertEquals(1, l.gen(key))
        assertNull(l.hash(key))
    }

    @Test
    fun theTagIsRequiredOnTheFirstLineButABomIsFine() {
        val f = Folder()
        val files = { f.files() }
        val own = f.add(name, "﻿#log-now\nold\n")
        assertEquals(WriteTarget.Decision.Use(own), WriteTarget.decide(name, files(), null, f::head))
        val f2 = Folder()
        f2.add(name, "\n#log-now\n")
        // The tag on a later line doesn't count; blank lines don't make an empty file.
        assertTrue(WriteTarget.decide(name, f2.files(), null, f2::head) is WriteTarget.Decision.Refuse)
        val f3 = Folder()
        f3.add(name, "#log-now-ish\n")
        assertTrue(WriteTarget.decide(name, f3.files(), null, f3::head) is WriteTarget.Decision.Refuse)
        val f4 = Folder()
        f4.add(name, "#log-now \r\nmore")
        assertTrue(WriteTarget.decide(name, f4.files(), null, f4::head) is WriteTarget.Decision.Use)
    }

    @Test
    fun aHalfWrittenFileOfOursIsRecognisedButAnotherNoteWithTheSameStartIsNot() {
        val f = Folder()
        val id = f.add(name, "#lo")
        // Without the record of an interrupted write it's somebody else's note.
        assertTrue(WriteTarget.decide(name, f.files(), null, f::head) is WriteTarget.Decision.Refuse)
        assertEquals(WriteTarget.Decision.Use(id), WriteTarget.decide(name, f.files(), id, f::head))
        // The record must be for this very file.
        assertTrue(WriteTarget.decide(name, f.files(), "other", f::head) is WriteTarget.Decision.Refuse)
    }

    @Test
    fun anEmptyFileItCreatedIsAccepted() {
        val l = Ledger().also { it.mark(key) }
        val f = Folder()
        val id = f.add(name, "")
        assertEquals(ExportProtocol.Result.Written, run(l, f))
        assertEquals(body, f.files.getValue(id).second)
        assertEquals(1, f.files.size)
    }

    @Test
    fun noteCopiesAreIgnored() {
        val f = Folder()
        f.add("2025-4-9 (now) (1).md", "somebody else's text")
        // Only a copy exists: the real name is created, the copy isn't used.
        assertEquals(WriteTarget.Decision.Create, WriteTarget.decide(name, f.files(), null, f::head))
        val own = f.add(name, "#log-now\n")
        assertEquals(WriteTarget.Decision.Use(own), WriteTarget.decide(name, f.files(), null, f::head))
        assertEquals(listOf("2025-4-9 (now) (1).md"), WriteTarget.copiesOf(key, f.files()))
        assertTrue(WriteTarget.copiesOf("2025-4-10", f.files()).isEmpty())
        val l = Ledger().also { it.mark(key) }
        run(l, f)
        assertEquals("somebody else's text", f.files.values.first().second)
    }

    @Test
    fun onlyTheExactNameIsEverTargeted() {
        val good = listOf("2025-4-9 (now).md", "2025-12-31 (now).md", "2025-1-1 (now).md")
        good.forEach { assertTrue(it, WriteTarget.isOurName(it)) }
        val bad = listOf(
            "2025-4-9.md", "2025-4-9 (now) (1).md", "2025-04-9 (now).md".replace("04", "004"), "2025-4-9 (long).md", "Sam.md",
            "2025-4-9 (now).md.md", "../2025-4-9 (now).md", "2025-4-9 (now).txt", " 2025-4-9 (now).md", "2025-4-9  (now).md",
            "2025-4-9 (Now).md", "(now).md", "",
        )
        bad.forEach { assertFalse(it, WriteTarget.isOurName(it)) }
        bad.forEach { n ->
            val l = Ledger().also { it.mark(key) }
            val f = Folder()
            f.add("Sam.md", "keep me")
            val r = ExportProtocol.writeDay(l, f, { f.files() }, key, n, true) { body }
            assertTrue(n, r is ExportProtocol.Result.Refused)
            assertTrue(n, f.touched.isEmpty())
            assertEquals(1, f.files.size)
        }
    }

    @Test
    fun aRunOnlyEverTouchesNowNotes() {
        val f = Folder()
        f.add("Sam.md", "#person")
        f.add("2025-4-9.md", "daily")
        f.add("2025-4-9 (long).md", "long")
        val l = Ledger()
        listOf("2025-4-9", "2025-4-10", "2025-4-11").forEach { k ->
            l.mark(k)
            ExportProtocol.writeDay(l, f, { f.files() }, k, "$k (now).md", false) { body }
        }
        assertEquals(setOf("2025-4-9 (now).md", "2025-4-10 (now).md", "2025-4-11 (now).md"), f.touched.toSet())
        f.touched.forEach { assertTrue(it, WriteTarget.isOurName(it)) }
        assertEquals("#person", f.files.values.first().second)
        assertNotNull(f.files.values.firstOrNull { it.first == "2025-4-10 (now).md" })
    }
}
