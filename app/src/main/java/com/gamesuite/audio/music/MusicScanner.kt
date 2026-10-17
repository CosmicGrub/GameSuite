package com.gamesuite.audio.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** Pure helpers for deciding what counts as an audio file. */
object AudioFiles {
    private val EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "amr", "mka", "mid", "midi", "3gp"
    )

    fun isAudio(displayName: String?, mimeType: String?): Boolean {
        val mime = mimeType?.lowercase()
        if (mime != null && mime.startsWith("audio/")) return true
        // Some providers report application/octet-stream for audio; fall back to the extension.
        if (mime == null || mime == "application/octet-stream" || mime == "application/ogg") {
            val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return ext in EXTENSIONS
        }
        return false
    }

    /** "01 - My Song.mp3" -> "01 - My Song". */
    fun titleFromFileName(displayName: String?): String {
        val name = displayName?.trim().orEmpty()
        if (name.isEmpty()) return ""
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && name.length - dot <= 6) name.substring(0, dot) else name
    }
}

/**
 * Walks a Storage Access Framework tree with [DocumentsContract] (androidx.documentfile is not a
 * dependency) and reads title/artist/duration with [MediaMetadataRetriever]. Blocking: call from a
 * background thread. Every per-file failure is tolerated; a failure to read the tree root throws.
 */
class MusicScanner(private val context: Context) {

    fun scan(
        treeUri: Uri,
        maxTracks: Int = MAX_TRACKS,
        isCancelled: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {}
    ): List<MusicTrack> {
        val found = ArrayList<MusicTrack>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        walk(treeUri, rootId, 0, found, maxTracks, isCancelled, onProgress)
        return found.sortedWith(compareBy({ it.title.lowercase() }, { it.id }))
    }

    private fun walk(
        tree: Uri,
        parentDocId: String,
        depth: Int,
        out: MutableList<MusicTrack>,
        maxTracks: Int,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ) {
        if (depth > MAX_DEPTH || out.size >= maxTracks || isCancelled()) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val dirs = ArrayList<String>()
        val files = ArrayList<Triple<String, String, String?>>() // docId, name, mime
        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        ) ?: return
        cursor.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0) ?: continue
                val name = c.getString(1) ?: ""
                val mime = c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) dirs.add(docId)
                else if (AudioFiles.isAudio(name, mime)) files.add(Triple(docId, name, mime))
            }
        }
        for ((docId, name, _) in files) {
            if (out.size >= maxTracks || isCancelled()) return
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            out.add(readTrack(uri, name))
            onProgress(out.size)
        }
        for (dirId in dirs) {
            if (out.size >= maxTracks || isCancelled()) return
            walk(tree, dirId, depth + 1, out, maxTracks, isCancelled, onProgress)
        }
    }

    private fun readTrack(uri: Uri, displayName: String): MusicTrack {
        var title = ""
        var artist = ""
        var duration = 0L
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            // Unreadable metadata is fine: fall back to the file name.
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        if (title.isBlank()) title = AudioFiles.titleFromFileName(displayName)
        if (title.isBlank()) title = displayName.ifBlank { uri.lastPathSegment ?: "Track" }
        return MusicTrack(
            id = uri.toString(),
            uri = uri.toString(),
            title = title.trim(),
            artist = artist.trim(),
            durationMs = duration.coerceAtLeast(0L)
        )
    }

    companion object {
        const val MAX_TRACKS = 5000
        const val MAX_DEPTH = 8
    }
}

/** Persists [MusicLibraryData] as one small JSON file in app-private storage. Never throws. */
class MusicLibraryStore(context: Context) {
    private val file = File(context.filesDir, "music_library.json")
    private val tmp = File(context.filesDir, "music_library.json.tmp")

    fun load(): MusicLibraryData = try {
        if (file.exists()) MusicLibraryCodec.decode(file.readText()) else MusicLibraryData()
    } catch (_: Exception) {
        MusicLibraryData()
    }

    /** @return true when the data reached disk. */
    fun save(data: MusicLibraryData): Boolean = try {
        tmp.writeText(MusicLibraryCodec.encode(data))
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    } catch (_: Exception) {
        false
    }
}
