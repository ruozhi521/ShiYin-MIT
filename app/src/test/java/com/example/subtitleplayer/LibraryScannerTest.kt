package com.example.subtitleplayer

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** findLyric 歌词匹配测试（Robolectric：Uri 可用）。覆盖 1.24 修过的全部匹配场景。 */
@RunWith(RobolectricTestRunner::class)
class LibraryScannerTest {

    private fun song(
        fileName: String,
        folder: String,
        title: String = fileName,
        fileStem: String = fileName.substringBeforeLast('.')
    ): Song {
        val uri = Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3A" +
                folder.replace("/", "%2F") + "%2F" + fileName
        )
        return Song(title, uri, folder, artist = "", fileStem = fileStem)
    }

    private fun ref(name: String, folder: String): LyricRef {
        val uri = Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3A" +
                folder.replace("/", "%2F") + "%2F" + name
        )
        return LyricRef(name, uri, folder)
    }

    /** 标准注册：歌词文件 歌名.mp3.vtt 注册 歌名.mp3 与 歌名 双 key。 */
    private fun register(lyrics: MutableMap<String, LyricRef>, folder: String, lyricName: String) {
        val r = ref(lyricName, folder)
        val stem = lyricName.substringBeforeLast('.')
        lyrics["$folder/${stem.lowercase()}"] = r
        val doubleStem = stem.substringBeforeLast('.')
        if (doubleStem != stem) {
            lyrics["$folder/${doubleStem.lowercase()}"] = r
        }
    }

    @Test
    fun `歌名 mp3 匹配 歌名 mp3 vtt（双 stem）`() {
        val lyrics = mutableMapOf<String, LyricRef>()
        register(lyrics, "Music", "歌名.mp3.vtt")
        val s = song("歌名.mp3", "Music")
        val hit = LibraryScanner.findLyric(s, lyrics)
        assertNotNull(hit)
        assertEquals("歌名.mp3.vtt", hit!!.displayName)
    }

    @Test
    fun `歌名 mp3 匹配无格式后缀歌词 歌名 vtt`() {
        val lyrics = mutableMapOf<String, LyricRef>()
        register(lyrics, "Music", "歌名.vtt")
        val s = song("歌名.mp3", "Music")
        val hit = LibraryScanner.findLyric(s, lyrics)
        assertNotNull(hit)
        assertEquals("歌名.vtt", hit!!.displayName)
    }

    @Test
    fun `同名不同格式各配格式后缀歌词时各归各`() {
        val lyrics = mutableMapOf<String, LyricRef>()
        // 后注册 flac 的，会覆盖 Music/歌名 兜底 key——精确匹配必须靠 uri 完整文件名
        register(lyrics, "Music", "歌名.mp3.vtt")
        register(lyrics, "Music", "歌名.flac.vtt")
        val mp3 = LibraryScanner.findLyric(song("歌名.mp3", "Music"), lyrics)
        val flac = LibraryScanner.findLyric(song("歌名.flac", "Music"), lyrics)
        assertNotNull(mp3)
        assertNotNull(flac)
        assertEquals("歌名.mp3.vtt", mp3!!.displayName)
        assertEquals("歌名.flac.vtt", flac!!.displayName)
    }

    @Test
    fun `旧缓存无 fileStem 时按 uri 文件名推导匹配`() {
        val lyrics = mutableMapOf<String, LyricRef>()
        register(lyrics, "Music", "歌名.mp3.vtt")
        val s = song("歌名.mp3", "Music", title = "标签标题不一致", fileStem = "")
        val hit = LibraryScanner.findLyric(s, lyrics)
        assertNotNull(hit)
        assertEquals("歌名.mp3.vtt", hit!!.displayName)
    }

    @Test
    fun `title 与文件名不同时不再依赖 title 匹配`() {
        val lyrics = mutableMapOf<String, LyricRef>()
        register(lyrics, "Music", "歌名.mp3.vtt")
        val s = song("歌名.mp3", "Music", title = "七里香", fileStem = "歌名")
        val hit = LibraryScanner.findLyric(s, lyrics)
        assertNotNull(hit)
        assertEquals("歌名.mp3.vtt", hit!!.displayName)
    }

    @Test
    fun `uri 是数字 id 拿不到文件名时用 title 兜底`() {
        val lyrics = mutableMapOf<String, LyricRef>()
        register(lyrics, "Music", "七里香.vtt")
        val s = Song(
            title = "七里香",
            uri = Uri.parse("content://some.provider/document/12345"),
            folder = "Music",
            artist = "",
            fileStem = ""
        )
        val hit = LibraryScanner.findLyric(s, lyrics)
        assertNotNull(hit)
        assertEquals("七里香.vtt", hit!!.displayName)
    }

    @Test
    fun `旧缓存无 folder 前缀的歌词走全局兜底`() {
        // 旧 LibraryCache 格式：key 无路径（如 "歌名.mp3"）
        val lyrics = mutableMapOf<String, LyricRef>()
        val r = ref("歌名.mp3.vtt", "Music")
        lyrics["歌名.mp3"] = r
        lyrics["歌名"] = r
        val s = song("歌名.mp3", "Music")
        val hit = LibraryScanner.findLyric(s, lyrics)
        assertNotNull(hit)
    }

    @Test
    fun `跨目录同名歌词多个时全局兜底返回 null 不串行`() {
        val lyrics = mutableMapOf<String, LyricRef>()
        lyrics["A/歌名.mp3"] = ref("歌名.mp3.vtt", "A")
        lyrics["B/歌名.mp3"] = ref("歌名.mp3.vtt", "B")
        // C 目录的歌，uri 文件名 歌名.mp3
        val s = Song(
            title = "歌名",
            uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AC%2F%E6%AD%8C%E5%90%8D.mp3"),
            folder = "C",
            artist = "",
            fileStem = "歌名"
        )
        assertNull(LibraryScanner.findLyric(s, lyrics))
    }

    @Test
    fun `title 带序号前缀时不匹配其他文件夹的同系列歌词`() {
        // 粉丝案例：音声文件名不带序号、title 带 "02. " 前缀；
        // 另一个文件夹有 "02. 歌名.vtt"（同系列文件的歌词）——绝不能匹配过来
        val lyrics = mutableMapOf<String, LyricRef>()
        register(lyrics, "FolderB", "02. 歌名.vtt")
        val s = Song(
            title = "02. 歌名",
            uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AFolderA%2F%E6%AD%8C%E5%90%8D.mp3"),
            folder = "FolderA",
            artist = "",
            fileStem = "歌名"
        )
        assertNull("跨文件夹 title 撞名不应匹配", LibraryScanner.findLyric(s, lyrics))
    }

    @Test
    fun `title 撞名在同文件夹内仍可精确匹配`() {
        // 同文件夹内：歌词按 title（带序号）命名，音声 title 与之相同 → 应匹配（同文件夹不算错配）
        val lyrics = mutableMapOf<String, LyricRef>()
        register(lyrics, "FolderA", "02. 歌名.vtt")
        val s = Song(
            title = "02. 歌名",
            uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AFolderA%2F%E6%AD%8C%E5%90%8D.mp3"),
            folder = "FolderA",
            artist = "",
            fileStem = "歌名"
        )
        val hit = LibraryScanner.findLyric(s, lyrics)
        assertNotNull(hit)
        assertEquals("02. 歌名.vtt", hit!!.displayName)
    }

    // ---- 多根合并歌单命名（1.29：唯一相对路径不加根前缀，跨根同名才消歧）----

    @Test
    fun `唯一文件夹不加根前缀`() {
        // 根A 有 Music/Pop，根B 有 Rock —— 各自唯一，直接用原名
        val counts = mapOf("Music/Pop" to 1, "Rock" to 1)
        assertEquals("Music/Pop", LibraryScanner.mergedFolderName("根A", "Music/Pop", counts))
        assertEquals("Rock", LibraryScanner.mergedFolderName("根B", "Rock", counts))
    }

    @Test
    fun `跨根同名子文件夹加根前缀消歧`() {
        // 两个根都有 Music → 各自加前缀；根目录散曲（DEFAULT_FOLDER）同理
        val counts = mapOf("Music" to 2, "根目录" to 2)
        assertEquals("根A/Music", LibraryScanner.mergedFolderName("根A", "Music", counts))
        assertEquals("根B/Music", LibraryScanner.mergedFolderName("根B", "Music", counts))
        assertEquals("根A/根目录", LibraryScanner.mergedFolderName("根A", "根目录", counts))
    }

    @Test
    fun `统计表缺失的相对路径视为唯一`() {
        assertEquals("Music", LibraryScanner.mergedFolderName("根A", "Music", emptyMap()))
    }

    // ---- 文件夹路径后缀对齐（1.31：多根前缀重命名前后混用仍可匹配）----

    @Test
    fun `歌单带根前缀而歌词 key 不带时仍可匹配`() {
        // 新扫描后歌单名变 "Download/周杰伦"，歌词 map 还是旧 key "周杰伦/歌名"
        val lyrics = mutableMapOf<String, LyricRef>()
        register(lyrics, "周杰伦", "歌名.mp3.vtt")
        val hit = LibraryScanner.findLyric(song("歌名.mp3", "Download/周杰伦"), lyrics)
        assertNotNull(hit)
        assertEquals("歌名.mp3.vtt", hit!!.displayName)
    }

    @Test
    fun `歌词 key 带根前缀而歌单不带时仍可匹配`() {
        val lyrics = mutableMapOf<String, LyricRef>()
        register(lyrics, "Download/周杰伦", "歌名.mp3.vtt")
        val hit = LibraryScanner.findLyric(song("歌名.mp3", "周杰伦"), lyrics)
        assertNotNull(hit)
        assertEquals("歌名.mp3.vtt", hit!!.displayName)
    }

    @Test
    fun `后缀对齐命中多份时拒绝匹配不串行`() {
        // 两个根的同名子文件夹消歧后各有一份同名歌词，folder 只给 "Music" 无法区分 → 拒配
        val lyrics = mutableMapOf<String, LyricRef>()
        register(lyrics, "根A/Music", "歌名.mp3.vtt")
        register(lyrics, "根B/Music", "歌名.mp3.vtt")
        assertNull(LibraryScanner.findLyric(song("歌名.mp3", "Music"), lyrics))
    }
}
