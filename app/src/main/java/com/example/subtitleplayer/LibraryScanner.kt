package com.example.subtitleplayer

import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.util.Locale

/**
 * 标签编码兜底（顶层函数，扫描与缓存加载共用）：
 * MediaMetadataRetriever 对 GBK 等非标准编码的 ID3 标签会解出乱码
 * （每字节变成 Latin-1 高位字符，如 "å¾®ç¬"），直接当歌名会显示乱码。
 * 特征明显时返回 null，上层回退用文件名（SAF 文件名是 UTF-8，正常）。
 */
internal fun cleanTag(s: String?): String? {
    if (s.isNullOrBlank()) return null
    val high = s.count { it.code in 0x80..0xFF && it != '\uFFFD' }  // Latin-1 高位（替换字符不计高位）
    val repl = s.count { it == '\uFFFD' }
    val cjk = s.count { it.code in 0x4E00..0x9FFF }   // 正常 CJK 字符
    // 低位 ASCII + 少量高位（拉丁/符号）→ 视为正常（拉丁名字、标签）
    if (high == 0 && repl == 0) return s
    // 明显乱码：高位字符占绝大多数 且 没有 CJK → GBK/UTF-8 被误读成 Latin-1；替换字符多也判乱码
    if ((high + repl) * 3 > s.length * 2 && cjk == 0) return null
    return s
}

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
        "mp4", "m4v", "m4s"
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

    /**
     * 多根目录合并扫描：依次扫描每个根，合并 songs（按 uri 去重）与 lyrics；
     * 若不止一个根，歌单名加根前缀（如「根名/子文件夹」），避免不同根的
     * 同名子文件夹串成一个歌单。
     */
    fun scanAll(roots: List<Uri>): MusicLibrary {
        if (roots.isEmpty()) return MusicLibrary(emptyList(), emptyList(), emptyMap())
        if (roots.size == 1) return scan(roots[0])
        val folderSongs = mutableMapOf<String, MutableList<Song>>()
        val lyrics = mutableMapOf<String, LyricRef>()
        val all = mutableListOf<Song>()
        val seenSongs = HashSet<String>()
        val seenLyrics = HashSet<String>()
        for (root in roots) {
            val rootId = DocumentsContract.getTreeDocumentId(root)
            val rootLabel = rootDisplayName(root)
            val sub = mutableMapOf<String, MutableList<Song>>()
            val subLyrics = mutableMapOf<String, LyricRef>()
            val subAll = mutableListOf<Song>()
            scanDir(root, rootId, null, sub, subLyrics, subAll)
            // 合并
            for (song in subAll) {
                if (seenSongs.add(song.uri.toString())) {
                    all.add(song)
                    val key = "$rootLabel/${song.folder}"
                    folderSongs.getOrPut(key) { mutableListOf() }.add(song)
                }
            }
            for ((k, ref) in subLyrics) {
                if (seenLyrics.add(k)) {
                    lyrics["$rootLabel/$k"] = ref
                }
            }
        }
        val playlists = folderSongs.map { (name, songs) ->
            Playlist(name, songs.sortedBy { it.title })
        }.sortedBy { it.name }
        return MusicLibrary(
            playlists = playlists,
            allSongs = all.sortedBy { it.title },
            lyrics = lyrics
        )
    }

    /** 多根时歌单前缀：取 SAF 根文档名（如 primary:Download 末段），失败回退 "根i"。 */
    private fun rootDisplayName(root: Uri): String {
        val leaf = root.lastPathSegment ?: return "根"
        return leaf.substringAfterLast(':').ifBlank { "根" }
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
        // b 站缓存目录的 entry.json 标题（m4s 文件名是数字，用视频标题代替）
        var dirEntryTitle: String? = null
        // m4s 先收集后统一处理（视频流/音频流去重，见循环后逻辑）
        val m4sCandidates = mutableListOf<Triple<Uri, String, String>>() // uri, name, folder
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
                        name.equals("entry.json", true) -> {
                            // b 站缓存视频信息：{"title": "...", "page_data": {"part": "分P"}}
                            dirEntryTitle = readJsonTitle(
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            )
                        }
                        isAudio(mime, name) -> {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            val folder = folderPath ?: DEFAULT_FOLDER
                            if (extOf(name) == "m4s") {
                                m4sCandidates.add(Triple(uri, name, folder))
                            } else {
                                val (tagTitle, tagArtist) = readTags(uri, name)
                                // folderPath 为相对根目录完整路径（同名子文件夹不再合并）
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

        // m4s 收录（b 站 DASH 音视频分离）：
        // 1. 优先纯音频 m4s（audio.m4s，播放最稳、能出声）
        // 2. 其次含音频轨的 m4s（video.m4s 若含双轨）
        // 3. 纯视频 m4s（无音频轨）跳过——无声且无时长，收进来只有坏体验
        val audioOnlyM4s = m4sCandidates.filter {
            hasAudioTrack(it.first) && !hasVideoTrack(it.first)
        }
        val pickedM4s =
            if (audioOnlyM4s.isNotEmpty()) audioOnlyM4s
            else m4sCandidates.filter { hasAudioTrack(it.first) }
        for ((uri, name, folder) in pickedM4s) {
            val (tagTitle, tagArtist) = readTags(uri, name)
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

        // b 站缓存：纯数字文件名的 m4s 用 entry.json 的视频标题
        if (!dirEntryTitle.isNullOrBlank() && songsHere.isNotEmpty()) {
            songsHere.replaceAll { s ->
                if (s.title.matches(Regex("\\d+"))) s.copy(title = dirEntryTitle) else s
            }
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

    /**
     * 读取内嵌标题/艺术家。
     * - **MP3 一律用 Id3TagReader 自解析**：MediaMetadataRetriever 对含非法 UTF-8 字节的
     *   metadata 会触发 JNI NewStringUTF abort（SIGABRT，进程直接崩，try/catch 拦不住，
     *   粉丝实测闪退即此）。自解析按帧字节解码，永不崩溃。
     * - 其他格式（M4A/FLAC/WAV/AAC/OGG/m4s）metadata 为标准 UTF-8，MMR 风险低，保持原路径。
     */
    private fun readTags(uri: Uri, name: String): Pair<String?, String?> {
        if (extOf(name) == "mp3") {
            return Id3TagReader.read(context, uri)
        }
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(context, uri)
            val title = cleanTag(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
            val artist = cleanTag(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
            r.release()
            title to artist
        } catch (e: Exception) {
            null to null
        }
    }

    /** 读 b 站缓存 entry.json 的视频标题（title 或 page_data.part）。 */
    private fun readJsonTitle(uri: Uri): String? {
        return try {
            val text = resolver.openInputStream(uri)
                ?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: return null
            val jo = JSONObject(text)
            jo.optString("title").takeIf { it.isNotBlank() }
                ?: jo.optJSONObject("page_data")?.optString("part")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** m4s 是否有音频轨道（b 站缓存同时有视频流/音频流两个 m4s，视频流要跳过）。读取失败保守返回 true。 */
    private fun hasAudioTrack(uri: Uri): Boolean {
        return try {
            val ex = MediaExtractor()
            ex.setDataSource(context, uri, null)
            var has = false
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    has = true
                    break
                }
            }
            ex.release()
            has
        } catch (e: Exception) {
            true
        }
    }

    /** m4s 是否有视频轨道（b 站 video.m4s 含视频轨，可进视频页播放）。 */
    private fun hasVideoTrack(uri: Uri): Boolean {
        return try {
            val ex = MediaExtractor()
            ex.setDataSource(context, uri, null)
            var has = false
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    has = true
                    break
                }
            }
            ex.release()
            has
        } catch (e: Exception) {
            false
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
            // 全局兜底（限同文件夹或旧缓存无路径格式）：只用文件名来源（uri 完整名/fileStem），
            // 不用 title——粉丝案例：音声 title 带序号前缀（如 "02. 歌名"）会撞到别的文件夹
            // 同系列文件的歌词 key，导致"毫不相干的歌词"错配。跨文件夹一律不匹配。
            val nameStems = linkedSetOf<String>()
            fileNameFromUri(song.uri).takeIf { it.isNotBlank() }?.let { nameStems.add(it) }
            if (song.fileStem.isNotBlank()) nameStems.add(song.fileStem)
            val lowerNameStems = nameStems.map { lower(it) }
            // 同一歌词的双 key（歌名.mp3 + 歌名 指向同一 ref）先去重再计数
            val matches = lyrics.filterKeys { k ->
                val inFolder = k.startsWith("$folder/") || !k.contains('/')
                inFolder && lowerNameStems.any { st -> k == st || k.endsWith("/$st") }
            }.values.distinct()
            return if (matches.size == 1) matches.first() else null
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
