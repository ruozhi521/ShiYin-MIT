package com.example.subtitleplayer

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 把扫描结果缓存到应用私有目录，供「关闭启动时自动扫描」时秒开。
 * 缓存的 uri 是 SAF 持久化授权的文档 uri，权限仍在时可直接播放。
 */
object LibraryCache {

    private const val FILE_NAME = "library.json"

    /** 从 SAF 文档 uri 提取文件名（去扩展名），用于乱码标题回退。 */
    private fun stemFromUri(uriStr: String): String {
        val raw = uriStr.substringAfterLast('/')
        val decoded = try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (e: Exception) {
            raw
        }
        return stemOf(decoded)
    }

    fun save(context: Context, lib: MusicLibrary) {
        try {
            val songs = JSONArray()
            for (s in lib.allSongs) {
                songs.put(
                    JSONObject()
                        .put("title", s.title)
                        .put("uri", s.uri.toString())
                        .put("folder", s.folder)
                        .put("artist", s.artist)
                        .put("fileStem", s.fileStem)
                        .put("size", s.size)
                        // 3.0.6：修改时间要随缓存持久化，否则重启后「按修改时间排序」全变 0 失效
                        .put("lastModified", s.lastModified)
                )
            }
            val lyrics = JSONArray()
            for (ref in lib.lyrics.values) {
                lyrics.put(
                    JSONObject()
                        .put("name", ref.displayName)
                        .put("uri", ref.uri.toString())
                        .put("folder", ref.folder)
                )
            }
            val root = JSONObject()
                .put("songs", songs)
                .put("lyrics", lyrics)
            // 3.1：把**歌单顺序**也存下来。
            //
            // 为什么必须存：load() 现在不再排序（排序要触发 ICU 初始化，实测拖慢 3 秒），
            // 而 groupBy 出来的歌单顺序是「按首首歌出现的顺序」，与用户以前看到的
            // 「按名称排序」不一致 —— 不存的话升级后歌单顺序会莫名改变。
            //
            // 存名字数组最省：几十个字符串，读回来按它重排一次即可（纯字符串比较，
            // 不碰拼音）；名字对不上的（缓存与库不同步）自动落到末尾，不会丢。
            val playlistOrder = JSONArray()
            for (p in lib.playlists) playlistOrder.put(p.name)
            root.put("playlistOrder", playlistOrder)
            // 3.0.6：文件夹封面也要缓存（否则重启后文件夹封面丢，只剩内嵌封面）
            if (lib.folderCovers.isNotEmpty()) {
                val covers = JSONObject()
                for ((k, v) in lib.folderCovers) covers.put(k, v.toString())
                root.put("folderCovers", covers)
            }
            File(context.filesDir, FILE_NAME).writeText(root.toString())
        } catch (e: Exception) {
            // 缓存失败不影响主流程
        }
    }

    fun load(context: Context): MusicLibrary? {
        return try {
            val t0 = System.currentTimeMillis()
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return null
            val text = file.readText()
            val tRead = System.currentTimeMillis() - t0
            val root = JSONObject(text)
            val tJson = System.currentTimeMillis() - t0

            val songsArr = root.optJSONArray("songs") ?: return null
            val songs = mutableListOf<Song>()
            for (i in 0 until songsArr.length()) {
                val o = songsArr.getJSONObject(i)
                val title = cleanTag(o.optString("title", "")) ?: o.optString("fileStem", "")
                    .ifBlank { stemFromUri(o.getString("uri")) }
                songs.add(
                    Song(
                        title,
                        Uri.parse(o.getString("uri")),
                        o.getString("folder"),
                        o.optString("artist", ""),
                        o.optString("fileStem", ""),
                        // 旧缓存无 size 字段 → 0：单曲封面 key 退回纯文件名（与旧版兼容）
                        o.optLong("size", 0L),
                        // 旧缓存无 lastModified → 0：按修改时间排序时排最后，不乱序
                        o.optLong("lastModified", 0L)
                    )
                )
            }
            if (songs.isEmpty()) return null
            val tSongs = System.currentTimeMillis() - t0

            val lyricsArr = root.optJSONArray("lyrics") ?: JSONArray()
            val lyrics = mutableMapOf<String, LyricRef>()
            for (i in 0 until lyricsArr.length()) {
                val o = lyricsArr.getJSONObject(i)
                val folder = o.optString("folder", "")
                val ref = LyricRef(o.getString("name"), Uri.parse(o.getString("uri")), folder)
                val stem = stemOf(o.getString("name")).lowercase(Locale.getDefault())
                // 新格式带路径；旧缓存无 folder 时回退无路径 key（findLyric 全局唯一兜底兼容）
                val key = if (folder.isNotBlank()) "$folder/$stem" else stem
                lyrics[key] = ref
                val doubleStem = stemOf(stem)
                if (doubleStem != stem) {
                    val key2 = if (folder.isNotBlank()) "$folder/$doubleStem" else doubleStem
                    lyrics[key2] = ref
                }
            }
            val tLyrics = System.currentTimeMillis() - t0

            val byFolder = songs.groupBy { it.folder }

            // ⚠️ 3.1 关键修正：load() 里**完全不排序**，一个拼音都不碰。
            //
            // 实机日志实测（1100 首）：onCreate 只要 285ms，而 load 花了 3371ms ——
            // 全卡在排序上。排序要走拼音，第一次用会触发 ICU 初始化
            // （加载 Han-Latin 规则，一两秒），外加逐个歌名转写。
            //
            // 而这一层排序**本来就是多余的**：
            //
            // 1. **allSongs**：save() 写进去的就是扫描时的顺序，而扫描产出的
            //    allSongs 早已按 NameOrder 排好 —— 磁盘顺序就是想要的顺序。
            // 2. **playlists**：Kotlin 的 groupBy 返回 LinkedHashMap，保持插入顺序，
            //    所以歌单按「其第一首歌出现的顺序」排列，本身稳定有序。
            //    而显示时 playlistList() → applyPlaylistSort() 还会按用户
            //    选择的方式（名称/最近/随机/拖拽）重新排一次 —— 这里排了也会被覆盖。
            //
            // 结果：load() 变成纯解析，不再依赖 ICU、不再阻塞，空白期大幅缩短。
            //
            // ⚠️ 但「不排序」会带来一个副作用：groupBy 的顺序是「按首首歌出现的顺序」，
            // 而用户以前看到的是按名称排的。所以顺序改为**从缓存里读回来**
            // （save 时存了 playlistOrder），纯字符串比较、不碰拼音。
            val grouped = byFolder.map { (name, list) -> Playlist(name, list) }
            val orderArr = root.optJSONArray("playlistOrder")
            val playlists = if (orderArr != null && orderArr.length() > 0) {
                val byName = grouped.associateBy { it.name }
                val out = ArrayList<Playlist>(grouped.size)
                val used = HashSet<String>(grouped.size)
                for (i in 0 until orderArr.length()) {
                    val n = orderArr.optString(i, "")
                    val p = byName[n] ?: continue
                    if (used.add(n)) out.add(p)
                }
                // 缓存里没记录的新歌单（缓存与库不同步时）追加到末尾，不丢
                for (p in grouped) if (used.add(p.name)) out.add(p)
                out
            } else {
                // 旧缓存没有 playlistOrder 字段：**也不排序**，直接用 groupBy 顺序。
                //
                // 为什么不退回 NameOrder 排一次：排序会触发 ICU 初始化（1-2 秒），
                // 那正是要消灭的开销。而紧跟其后的静默重扫会以新顺序重写缓存
                // （扫描器的歌单是 NameOrder 排好的），所以只有升级后第一次启动
                // 顺序略有不同，第二次起就正确了 —— 拿这个换掉每次启动 1-2 秒，值得。
                grouped
            }
            val tGroup = System.currentTimeMillis() - t0

            // 3.0.6：文件夹封面（可选字段，旧缓存没有 → 空表）
            val covers = mutableMapOf<String, android.net.Uri>()
            root.optJSONObject("folderCovers")?.let { o ->
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = o.optString(k, "")
                    if (v.isNotBlank()) covers[k] = Uri.parse(v)
                }
            }

            PlaybackLog.log(
                "cache parse: read=${tRead}ms json=${tJson}ms songs=${tSongs}ms " +
                    "lyrics=${tLyrics}ms group=${tGroup}ms n=${songs.size}"
            )

            MusicLibrary(
                playlists = playlists,
                allSongs = songs,
                lyrics = lyrics,
                folderCovers = covers
            )
        } catch (e: Exception) {
            PlaybackLog.log("cache load failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun stemOf(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0) name.substring(0, i) else name
    }
}
