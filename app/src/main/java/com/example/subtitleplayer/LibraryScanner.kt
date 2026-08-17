package com.example.subtitleplayer

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import java.util.Locale

/**
 * 通过 SAF（系统文件选择器授权）递归扫描一个文件夹树：
 * - 每个子文件夹成为一个歌单（根目录散落的音频归入 [DEFAULT_FOLDER]）
 * - 自动记录所有 .lrc/.vtt/.txt 歌词，供按文件名匹配
 */
class LibraryScanner(
    private val context: Context,
    private val resolver: ContentResolver
) {

    private val audioExts = setOf(
        "mp3", "m4a", "wav", "flac", "aac", "ogg", "opus", "amr", "wma", "mid", "midi",
        "mp4", "m4v"
    )
    private val lyricExts = setOf("lrc", "vtt", "txt", "srt")

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
        folderPath: String?,
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
                            val (tagTitle, tagArtist) = readTags(uri)
                            // folderPath 为相对根目录完整路径（同名子文件夹不再合并）
                            val folder = folderPath ?: DEFAULT_FOLDER
                            songsHere.add(
                                Song(
                                    title = tagTitle?.takeIf { it.isNotBlank() } ?: stemOf(name),
                                    uri = uri,
                                    folder = folder,
                                    artist = tagArtist?.takeIf { it.isNotBlank() }
                                        ?: folder.substringAfterLast('/'),
                                    fileStem = stemOf(name)
                                )
                            )
                        }
                        isLyric(name) -> {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            val folder = folderPath ?: DEFAULT_FOLDER
                            val ref = LyricRef(name, uri, folder)
                            val stem = stemOf(name)
                            lyrics["$folder/${lower(stem)}"] = ref
                            val doubleStem = stemOf(stem)
                            if (doubleStem != stem) {
                                lyrics["$folder/${lower(doubleStem)}"] = ref
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
            // 子目录名拼上父路径：A/周杰伦 与 B/周杰伦 各自独立成歌单
            val childPath = if (folderPath == null) name else "$folderPath/$name"
            scanDir(treeUri, id, childPath, folderSongs, lyrics, all)
        }
    }

    private fun isAudio(mime: String, name: String): Boolean {
        if (mime.startsWith("audio/")) return true
        return extOf(name) in audioExts
    }

    /** 读取内嵌标题/艺术家（读文件头，不整读）。失败返回 null，回退文件名/文件夹名。 */
    private fun readTags(uri: Uri): Pair<String?, String?> {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(context, uri)
            val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            r.release()
            title to artist
        } catch (e: Exception) {
            null to null
        }
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

        /**
         * 按歌曲查找歌词：
         * 1) 精确匹配：同文件夹 + 文件名 stem（歌词文件名可能带 .mp3 后缀，双 stem 已注册）
         * 2) 全局兜底：仅当全局唯一（同名歌曲不同目录时宁可读不到也不串行）
         */
        fun findLyric(song: Song, lyrics: Map<String, LyricRef>): LyricRef? {
            val folder = song.folder
            // 匹配候选（按精确度排序，全部尝试）：
            // 1. uri 完整文件名（含音频扩展名，如 歌名.mp3）——命中各自的 歌名.mp3.vtt；
            //    避免同名不同格式（歌名.mp3 + 歌名.flac 各带格式后缀歌词）时兜底 key 互相覆盖错配
            // 2. fileStem（新扫描/新缓存，文件名去扩展名）
            // 3. title / title 去扩展名（最后兜底；某些 provider 的 uri 是数字 id 拿不到文件名）
            val stems = linkedSetOf<String>()
            fileNameFromUri(song.uri).takeIf { it.isNotBlank() }?.let { stems.add(it) }
            if (song.fileStem.isNotBlank()) stems.add(song.fileStem)
            stems.add(stemOf(song.title))
            stems.add(song.title)
            // 新格式（带路径）精确匹配
            for (st in stems) {
                lyrics["$folder/${lower(st)}"]?.let { return it }
            }
            // 全局兜底：跨目录同名歌词有多个时返回 null，避免串行
            val lowerStems = stems.map { lower(it) }
            val matches = lyrics.filterKeys { k ->
                lowerStems.any { st -> k == st || k.endsWith("/$st") }
            }
            return if (matches.size == 1) matches.values.first() else null
        }

        /** 从 SAF 文档 uri 提取完整文件名（保留扩展名，如 歌名.mp3）。旧缓存 Song 无 fileStem 时用。 */
        private fun fileNameFromUri(uri: Uri): String {
            val raw = uri.lastPathSegment ?: return ""
            val decoded = try {
                java.net.URLDecoder.decode(raw, "UTF-8")
            } catch (e: Exception) {
                raw
            }
            return decoded.substringAfterLast('/')
        }
    }
}
