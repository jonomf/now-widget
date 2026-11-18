package com.jono.nowwidget

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

/** One file or folder directly inside a folder of the vault, as the provider lists it. */
class VaultFile(
    val docId: String,
    val name: String,
    val modified: Long,
    val isDir: Boolean,
    /** Above 0: no .obsidian folder was seen, and this folder was only guessed to be the vault from its note count. */
    val guessedNotes: Int = 0,
)

/**
 * Everything that talks to the vault folder, through DocumentsContract directly (no androidx).
 * The folder is a tree URI you picked once; Android keeps the permission. Every function can
 * throw (IOException, SecurityException, IllegalArgumentException...): callers catch and record it.
 * Never call from the main thread.
 */
object VaultAccess {
    private val COLUMNS = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )

    fun docUri(tree: Uri, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    /** The folder's direct children. Some providers give no last-modified time: that reads as 0. */
    fun list(ctx: Context, tree: Uri, parentDocId: String): List<VaultFile> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val cursor = ctx.contentResolver.query(children, COLUMNS, null, null, null)
            ?: throw IOException("The folder couldn't be listed")
        val out = ArrayList<VaultFile>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val modified = if (c.isNull(2)) 0L else c.getLong(2)
                val mime = if (c.isNull(3)) "" else c.getString(3)
                out.add(VaultFile(id, name, modified, mime == DocumentsContract.Document.MIME_TYPE_DIR))
            }
        }
        return out
    }

    /** A document's display name, or null when the provider doesn't say. */
    fun nameOf(ctx: Context, tree: Uri, docId: String): String? {
        val cursor = ctx.contentResolver.query(
            docUri(tree, docId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        ) ?: return null
        cursor.use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }

    /** The first [max] bytes of a file as text (UTF-8; a cut-off last character is harmless). */
    fun readHead(ctx: Context, tree: Uri, docId: String, max: Int = 4096): String {
        val input = ctx.contentResolver.openInputStream(docUri(tree, docId))
            ?: throw IOException("A note couldn't be opened")
        input.use { s ->
            val buf = ByteArray(max)
            var n = 0
            while (n < max) {
                val r = s.read(buf, n, max - n)
                if (r < 0) break
                n += r
            }
            return String(buf, 0, n, Charsets.UTF_8)
        }
    }

    /**
     * Makes an empty file with exactly this name in [parentDocId] and returns its document id.
     * Some providers change the name (adding an extension, or " (1)"): that's fixed by renaming,
     * and if it can't be, the stray file is removed and this throws.
     */
    fun create(ctx: Context, tree: Uri, parentDocId: String, name: String): String {
        val resolver = ctx.contentResolver
        val made = DocumentsContract.createDocument(resolver, docUri(tree, parentDocId), "application/octet-stream", name)
            ?: throw IOException("The folder wouldn't make $name")
        var uri: Uri = made
        val got = nameOf(ctx, tree, DocumentsContract.getDocumentId(uri))
        if (got != null && got != name) {
            val renamed = try {
                DocumentsContract.renameDocument(resolver, uri, name)
            } catch (e: Exception) {
                null
            }
            val now = renamed ?: uri
            val check = nameOf(ctx, tree, DocumentsContract.getDocumentId(now))
            if (check != null && check != name) {
                try {
                    DocumentsContract.deleteDocument(resolver, now)
                } catch (e: Exception) {
                    // Nothing more to do; the error below says what happened.
                }
                throw IOException("The folder named the new file \"$check\" instead of \"$name\"")
            }
            uri = now
        }
        return DocumentsContract.getDocumentId(uri)
    }

    /** Replaces the whole file, UTF-8, straight into it ("wt" truncates): no temp files that would sync. */
    fun write(ctx: Context, tree: Uri, docId: String, text: String) {
        val out = ctx.contentResolver.openOutputStream(docUri(tree, docId), "wt")
            ?: throw IOException("A note couldn't be opened for writing")
        out.use { s ->
            s.write(text.toByteArray(Charsets.UTF_8))
            s.flush()
        }
    }

    /** The name of the picked folder. */
    fun treeName(ctx: Context, tree: Uri): String {
        val id = DocumentsContract.getTreeDocumentId(tree)
        return nameOf(ctx, tree, id) ?: id.substringAfterLast('/').substringAfterLast(':').ifBlank { "vault" }
    }

    /**
     * Where the vault is inside the picked folder: the folder itself when it has a .obsidian folder,
     * otherwise the folders inside it that do (so picking the parent of the vault works). Without
     * any .obsidian folder visible, a folder that holds a lot of notes is taken as the vault.
     */
    fun findVaults(ctx: Context, tree: Uri): List<VaultFile> {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val top = list(ctx, tree, rootId)
        if (top.any { it.isDir && it.name == ".obsidian" }) return listOf(VaultFile(rootId, treeName(ctx, tree), 0L, true))
        val inside = top.filter { it.isDir && !it.name.startsWith(".") }.take(120).filter { d ->
            try {
                list(ctx, tree, d.docId).any { it.isDir && it.name == ".obsidian" }
            } catch (e: Exception) {
                false
            }
        }
        if (inside.isNotEmpty()) return inside
        val notes = top.count { !it.isDir && it.name.endsWith(".md", ignoreCase = true) }
        if (notes >= 20) return listOf(VaultFile(rootId, treeName(ctx, tree), 0L, true, guessedNotes = notes))
        return emptyList()
    }
}
