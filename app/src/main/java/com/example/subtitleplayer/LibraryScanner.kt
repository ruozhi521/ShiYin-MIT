package com.example.subtitleplayer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.util.Locale

/**
 * 通过 SAF（系统文件选择器授权）递归扫描一个文件夹树：
 * - 每个子文件夹成为一个歌单（根目录散落的音频归入 [DEFAULT_FOLDER]）
 * - 自动记录所有 .lrc/.vtt/.txt 歌词，供按文件名匹配
 */
class LibraryScanner(private val resolver: ContentResolver) {

    private val audioExts = setOf(
        "mp3", "m4a", "wav", "flac", "aac", "ogg", "opus", "amr", "wma", "mid", "midi"
    )
    private val lyricExts = setOf("lrc", "vtt", "txt")

    fun scan(treeUri: Uri): MusicLibrary {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val folderSongs = mutableMapOf<String, MutableList<Song>>()
        val lyrics = mutableMapOf<String, LyricRef>()
        val all = mutableListOf<Song>()
        scanDir(treeUri, rootId, null, folderSongs, lyrics, all)

        val playlists = folderSongs.map { (name, songs) ->
            Playlist(name, songs.sortedBy { it.title })
        }.sortedBy { it.name }

        return MusicLibrary(
            playlists = playlists,
            allSongs = all.sortedBy { it.title },
            lyrics = lyrics
        )
    }

    private fun scanDir(
        treeUri: Uri,
        docId: String,
        folderName: String?,
        folderSongs: MutableMap<String, MutableList<Song>>,
        lyrics: MutableMap<String, LyricRef>,
        all: MutableList<Song>
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val subDirs = mutableListOf<Pair<String, String>>()
        val songsHere = mutableListOf<Song>()
        try {
            resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> {
                            subDirs.add(id to name)
                        }
                        isAudio(mime, name) -> {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            songsHere.add(Song(name, uri, folderName ?: DEFAULT_FOLDER))
                        }
                        isLyric(name) -> {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            val ref = LyricRef(name, uri)
                            val stem = stemOf(name)
                            lyrics[lower(stem)] = ref
                            val doubleStem = stemOf(stem)
                            if (doubleStem != stem) {
                                lyrics[lower(doubleStem)] = ref
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 单个文件夹读取失败不影响整体
        }

        if (songsHere.isNotEmpty()) {
            val list = folderSongs.getOrPut(songsHere[0].folder) { mutableListOf() }
            list.addAll(songsHere)
            all.addAll(songsHere)
        }
        for ((id, name) in subDirs) {
            scanDir(treeUri, id, name, folderSongs, lyrics, all)
        }
    }

    private fun isAudio(mime: String, name: String): Boolean {
        if (mime.startsWith("audio/")) return true
        return extOf(name) in audioExts
    }

    private fun isLyric(name: String): Boolean = extOf(name) in lyricExts

    private fun extOf(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i >= 0) name.substring(i + 1).lowercase(Locale.getDefault()) else ""
    }

    companion object {
        const val DEFAULT_FOLDER = "根目录"

        private fun stemOf(name: String): String {
            val i = name.lastIndexOf('.')
            return if (i > 0) name.substring(0, i) else name
        }

        private fun lower(s: String): String = s.lowercase(Locale.getDefault())

        /** 按歌曲文件名查找歌词：支持 歌名.lrc 与 歌名.mp3.lrc 两种命名。 */
        fun findLyric(song: Song, lyrics: Map<String, LyricRef>): LyricRef? {
            val full = song.title.lowercase(Locale.getDefault())
            val stem = stemOf(song.title).lowercase(Locale.getDefault())
            return lyrics[stem] ?: lyrics[full]
        }
    }
}
