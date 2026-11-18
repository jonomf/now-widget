package com.jono.nowwidget

import android.content.Context
import android.net.Uri
import com.jono.nowwidget.core.VaultNotes
import java.io.IOException

/** What a scan did. */
class ScanResult(val notes: Int, val read: Int, val created: Int, val matched: Int, val removed: Int, val unreadable: Int) {
    fun text(): String = "$notes notes" + (if (created > 0) ", $created new links" else "") +
        (if (removed > 0) ", $removed gone" else "") + (if (unreadable > 0) ", $unreadable couldn't be read" else "")
}

/**
 * Reads the vault's root notes (read-only) into the link table. Incremental: only files that are
 * new or changed are opened, and only their first 4 KB. Run on a background thread.
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
        val files = VaultAccess.list(ctx, tree, root).filter { !it.isDir && VaultNotes.candidate(it.name) }
        val cache = Store.vaultCache(ctx)

        val toRead = files.filter { f ->
            val c = cache[f.docId]
            c == null || c.name != f.name || (if (f.modified > 0L) c.modified != f.modified else deep)
        }
        val upserts = ArrayList<VaultUpsert>()
        var unreadable = 0
        var lastError: Exception? = null
        toRead.forEachIndexed { i, f ->
            if (i % 40 == 0) progress("Reading notes… $i of ${toRead.size}")
            try {
                val head = VaultAccess.readHead(ctx, tree, f.docId)
                val note = VaultNotes.parse(f.name, head.split("\n"))
                if (note != null) upserts.add(VaultUpsert(f.docId, f.name, f.modified, note))
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                unreadable++
                lastError = e
            }
        }
        val failure = lastError
        if (toRead.isNotEmpty() && unreadable == toRead.size && failure != null) throw failure

        val present = files.map { it.docId }.toHashSet()
        val gone = cache.keys.filter { it !in present }
        progress("Updating links…")
        val m = Store.applyVaultScan(ctx, upserts, gone)
        return ScanResult(files.size, toRead.size, m.created, m.matched, m.removed, unreadable)
    }
}
