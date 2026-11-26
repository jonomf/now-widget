package com.jono.nowwidget

import android.content.Context
import android.net.Uri
import com.jono.nowwidget.core.DiaryDay
import com.jono.nowwidget.core.Mentions
import com.jono.nowwidget.core.LinkKind
import com.jono.nowwidget.core.TreeEntry
import com.jono.nowwidget.core.VaultNotes
import com.jono.nowwidget.core.VaultWalk
import com.jono.nowwidget.core.WalkFile
import java.io.IOException

/** What a scan did. */
class ScanResult(
    val notes: Int,
    val read: Int,
    val created: Int,
    val matched: Int,
    val removed: Int,
    val unreadable: Int,
    val dayNotes: Int = 0,
    val fromMentions: Int = 0,
    val folders: Int = 0,
    val merged: Int = 0,
    val truncated: Boolean = false,
) {
    fun text(): String = "$notes notes" + (if (folders > 1) " in $folders folders" else "") + (if (created > 0) ", $created new links" else "") +
        (if (removed > 0) ", $removed gone" else "") + (if (unreadable > 0) ", $unreadable couldn't be read" else "") +
        (if (dayNotes > 0) ", $dayNotes daily notes" else "") +
        (if (fromMentions > 0) ", $fromMentions people and places from them" else "") +
        (if (merged > 0) ", $merged merged with their notes" else "") +
        (if (truncated) " (stopped at ${VaultWalk.MAX_FILES} files or ${VaultWalk.MAX_DEPTH} folders deep)" else "")
}

/**
 * Reads the vault's notes in every folder (read-only) into the link table. Incremental: only files
 * that are new or changed are opened, and only their first 4 KB. Run on a background thread.
 */
object VaultIndex {
    /**
     * [deep] also re-reads files whose provider gives no last-modified time (otherwise those are
     * only read once). [progress] gets short status lines.
     */
    fun scan(ctx: Context, deep: Boolean, progress: (String) -> Unit): ScanResult {
        val tree = Uri.parse(Prefs.vaultTree(ctx) ?: throw IOException("No vault folder is chosen"))
        val root = Prefs.vaultRoot(ctx) ?: throw IOException("No vault folder is chosen")
        progress("Listing the vault…")
        // Every folder (Obsidian finds [[Name]] by file name anywhere), but not .obsidian, .trash, _templates.
        val walk = VaultWalk.walk(root, { id ->
            VaultAccess.list(ctx, tree, id).map { TreeEntry(it.docId, it.name, it.modified, it.isDir) }
        })
        val files = walk.files.filter { VaultNotes.candidate(it.entry.name) }
        val cache = Store.vaultCache(ctx)

        val present = files.map { it.entry.docId }.toHashSet()
        val gone = cache.keys.filter { it !in present }
        // A note moved to another folder comes back with a new id: it's the same note.
        val moved = VaultWalk.moves(
            gone.associateWith { cache[it]!!.name },
            files.filter { it.entry.docId !in cache }.associate { it.entry.docId to it.entry.name },
        )
        val toRead = files.filter { w ->
            val f = w.entry
            val c = cache[f.docId]
            c == null || c.name != f.name || (if (f.modified > 0L) c.modified != f.modified else deep)
        }
        class Read(val u: VaultUpsert, val w: WalkFile)
        val reads = ArrayList<Read>()
        var unreadable = 0
        var lastError: Exception? = null
        toRead.forEachIndexed { i, w ->
            if (i % 40 == 0) progress("Reading notes… $i of ${toRead.size}")
            val f = w.entry
            try {
                val head = VaultAccess.readHead(ctx, tree, f.docId)
                val note = VaultNotes.parse(f.name, head.split("\n"))
                if (note != null) reads.add(Read(VaultUpsert(f.docId, f.name, f.modified, note, moved[f.docId]), w))
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                unreadable++
                lastError = e
            }
        }
        val failure = lastError
        if (toRead.isNotEmpty() && unreadable == toRead.size && failure != null) throw failure

        // Two notes with one title (21H): the one with a type tag, then the shallower one, comes
        // first, so it's the one that makes the link; the other joins that link.
        val ordered = VaultWalk.byTitle(reads, { it.u.note.title }, { it.u.note.kind }, { it.w.depth }, { it.w.path.joinToString("/") })
            .values.flatten().sortedWith(compareBy<Read>({ if (it.u.note.kind == LinkKind.OTHER) 1 else 0 }, { it.w.depth }))
        progress("Updating links…")
        val m = Store.applyVaultScan(ctx, ordered.map { it.u }, gone)
        Store.adoptNoteKinds(ctx)
        val merged = Store.mergeMentionLinks(ctx)
        Prefs.setIndexCounts(ctx, files.size, walk.folders)
        val (dayNotes, fromMentions) = readMentions(ctx, tree, walk, deep, progress)
        // Names that resolve to a note now: merge again after the ranking pass too.
        Store.mergeMentionLinks(ctx)
        return ScanResult(files.size, toRead.size, m.created, m.matched, m.removed, unreadable, dayNotes, fromMentions,
            folders = walk.folders, merged = merged, truncated = walk.truncated)
    }

    /** A whole daily note is small (about 1 KB); this is far more than any. */
    private const val DAY_NOTE_MAX = 512 * 1024

    /**
     * The people and places ranking: reads the dated daily notes in any folder (not the app's own
     * `(now)` ones) that are new or changed since the last scan, in full, and keeps what each
     * mentions; then works out every link's score again. Returns (daily notes known, links the mentions made).
     */
    private fun readMentions(ctx: Context, tree: Uri, walk: VaultWalk.Result, deep: Boolean, progress: (String) -> Unit): Pair<Int, Int> {
        val dated = walk.files.map { it.entry }.mapNotNull { f -> Mentions.datedNote(f.name)?.let { f to it } }
        val cache = Store.dayNoteCache(ctx)
        val toRead = dated.filter { (f, _) ->
            val c = cache[f.docId]
            c == null || c.first != f.name || (if (f.modified > 0L) c.second != f.modified else deep)
        }
        val read = ArrayList<Store.DayNoteRead>()
        toRead.forEachIndexed { i, (f, ymd) ->
            if (i % 100 == 0) progress("Reading daily notes… $i of ${toRead.size}")
            try {
                val text = VaultAccess.readHead(ctx, tree, f.docId, DAY_NOTE_MAX)
                val day = DiaryDay.startOf(ymd.first, ymd.second, ymd.third)
                read.add(Store.DayNoteRead(f.docId, f.name, f.modified, day, Mentions.parseNote(text)))
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                // One unreadable note doesn't stop the ranking; it's tried again next scan.
            }
        }
        val present = dated.map { it.first.docId }.toHashSet()
        val gone = cache.keys.filter { it !in present }
        if (read.isNotEmpty() || gone.isNotEmpty()) progress("Ranking people and places…")
        Store.applyDayNotes(ctx, read, gone)
        val r = Store.applyRanking(ctx, System.currentTimeMillis(), lastOpen(ctx, tree, walk.root))
        Prefs.setRankLastRead(ctx, System.currentTimeMillis())
        return Store.dayNoteCount(ctx) to r.created
    }

    /** `.obsidian/workspace-mobile.json` (or `workspace.json`) "lastOpenFiles", for a small boost; empty when it can't be read. */
    private fun lastOpen(ctx: Context, tree: Uri, root: List<TreeEntry>): Set<String> = try {
        val dot = root.firstOrNull { it.isDir && it.name == ".obsidian" }
        if (dot == null) emptySet() else {
            val inside = VaultAccess.list(ctx, tree, dot.docId)
            val f = inside.firstOrNull { it.name == "workspace-mobile.json" } ?: inside.firstOrNull { it.name == "workspace.json" }
            if (f == null) emptySet() else Mentions.lastOpen(VaultAccess.readHead(ctx, tree, f.docId, DAY_NOTE_MAX))
        }
    } catch (e: SecurityException) {
        emptySet()
    } catch (e: Exception) {
        emptySet()
    }
}
