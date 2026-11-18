package com.jono.nowwidget.core

/** One file in the vault folder, as listed. */
data class NoteFile(val docId: String, val name: String)

/** The folder the notes go into. The real one is [com.jono.nowwidget.VaultAccess]; tests use a fake. */
interface NoteFolder {
    /** The files directly in the vault folder (folders left out). */
    fun files(): List<NoteFile>

    /** The first bytes of a file as text. */
    fun head(docId: String): String

    /** Makes an empty file with exactly this name and returns its document id. */
    fun create(name: String): String

    /** Replaces the whole file. May fail or be cut short part way. */
    fun write(docId: String, text: String)
}

/**
 * What the export remembers per day: whether it's dirty and how often it was marked ([gen], 0 when
 * clean), and the hash of the text last written ("" while a write is under way or was cut short).
 */
interface DayLedger {
    fun gen(key: String): Int
    fun hash(key: String): String?

    /** The document id this day's note was last written to (or was being written to), if known. */
    fun docId(key: String): String?
    fun saveHash(key: String, docId: String, hash: String)

    /** Clears the dirty mark only if the day wasn't marked again since it was at [gen]. */
    fun clearIfUnchanged(key: String, gen: Int): Boolean
}

/** Where a day's note goes, decided from a folder listing without touching anything. */
object WriteTarget {
    private val NAME = Regex("^\\d{4}-\\d{1,2}-\\d{1,2} \\(now\\)\\.md$")
    private val COPY = Regex("^(\\d{4}-\\d{1,2}-\\d{1,2}) \\(now\\) \\(\\d+\\)\\.md$")

    sealed class Decision {
        /** Write into this existing file (it's ours: it starts with the tag, or it is empty). */
        data class Use(val docId: String) : Decision()

        /** There's no such file: make it. */
        object Create : Decision()

        /** Don't touch anything; [why] is for the settings screen. */
        data class Refuse(val why: String) : Decision()
    }

    /** True for exactly `YYYY-M-D (now).md`, the only name the app ever writes. */
    fun isOurName(name: String): Boolean = NAME.matches(name)

    /** Names like `2025-4-9 (now) (1).md` that a sync tool makes: never written to, only reported. */
    fun copiesOf(day: String, files: List<NoteFile>): List<String> =
        files.map { it.name }.filter { COPY.matchEntire(it)?.groupValues?.get(1) == day }

    /**
     * Looks only at the file with exactly [name]; a `(now) (1)` copy is never chosen. A file that
     * exists must start with `#log-now` (a leading BOM is fine) or be empty, else it is somebody's
     * note and is left alone. The one exception is [interrupted], the document id of a write of
     * ours that was cut short (it may hold only the first few characters): that file is ours.
     */
    fun decide(name: String, files: List<NoteFile>, interrupted: String? = null, head: (String) -> String): Decision {
        if (!isOurName(name)) return Decision.Refuse("\"$name\" isn't a name this app writes")
        val same = files.filter { it.name == name }
        if (same.isEmpty()) return Decision.Create
        val file = same[0]
        if (interrupted != null && file.docId == interrupted) return Decision.Use(file.docId)
        val text = head(file.docId).removePrefix("﻿")
        val first = text.lineSequence().firstOrNull()?.trim() ?: ""
        if (text.isNotBlank() && first != ObsidianDay.FIRST_LINE) {
            return Decision.Refuse("$name isn't one of this app's notes (its first line isn't ${ObsidianDay.FIRST_LINE}), so it was left alone")
        }
        return Decision.Use(file.docId)
    }
}

/** Which dirty days a run still has to do. */
object ExportProtocol {
    sealed class Result {
        object Written : Result()
        object Unchanged : Result()
        /** Nothing to write for this day (no content, or only imported check-ins). The mark is cleared. */
        object Skipped : Result()
        /** The target is somebody else's file. The day stays dirty, and isn't retried until it changes. */
        data class Refused(val why: String) : Result()
        data class Failed(val why: String) : Result()
    }

    /**
     * Writes one day, in an order that survives being cut short anywhere.
     *  1. Read the day's change counter, then render: a change that lands from here on makes the
     *     counter differ, so the day stays dirty.
     *  2. Pick the target (nothing is changed if it's refused).
     *  3. Set the stored hash to "" (keeping the document id) and only then open the file: a write
     *     cut short leaves a hash that matches nothing, so the next run rewrites the whole note, and
     *     the document id lets it recognise its own half-written file.
     *  4. After the write succeeds: store the new hash, then clear the dirty mark if the counter
     *     is still what it was in step 1.
     * Any failure leaves the day dirty. [render] returns null when there's nothing to write.
     */
    fun writeDay(
        ledger: DayLedger,
        folder: NoteFolder,
        listing: () -> List<NoteFile>,
        key: String,
        name: String,
        force: Boolean,
        render: () -> String?,
    ): Result {
        val gen = ledger.gen(key)
        val stored = ledger.hash(key)
        val text = try {
            render()
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        }
        if (text == null) {
            ledger.clearIfUnchanged(key, gen)
            return Result.Skipped
        }
        val hash = ObsidianDay.hash(text)
        if (!force && stored == hash) {
            ledger.clearIfUnchanged(key, gen)
            return Result.Unchanged
        }
        try {
            val docId = when (val d = WriteTarget.decide(name, listing(), if (stored == "") ledger.docId(key) else null, folder::head)) {
                is WriteTarget.Decision.Refuse -> return Result.Refused(d.why)
                is WriteTarget.Decision.Use -> d.docId
                WriteTarget.Decision.Create -> folder.create(name)
            }
            ledger.saveHash(key, docId, "")
            folder.write(docId, text)
            ledger.saveHash(key, docId, hash)
            ledger.clearIfUnchanged(key, gen)
            return Result.Written
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * The days a run should do now: dirty days not tried yet, or marked again since they were tried
     * ([attempted] holds each tried day's counter). A day that failed or was refused is not tried
     * again in the same run, so a run always ends.
     */
    fun pending(dirty: Map<String, Int>, attempted: Map<String, Int>): List<String> =
        dirty.filter { (k, g) -> attempted[k] != g }.keys.toList()
}
