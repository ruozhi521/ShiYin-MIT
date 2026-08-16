package com.example.subtitleplayer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 后台线程读取所有歌曲的音频元数据歌手信息（METADATA_KEY_ARTIST）。
 * 结果按歌手分组并持久化到本地文件：只有首次（或新增歌曲时）需要读取，
 * 之后秒开。无歌手归为「未知歌手」。
 */
object ArtistLoader {

    private const val FILE_NAME = "artists.json"
    private val pool = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** uri -> artist 缓存（内存 + 磁盘持久化） */
    private val artistCache = HashMap<String, String>()
    private var loading = false
    /** 加载期间到达的回调：完成后统一执行，避免丢失（loading 竞态导致永远卡加载）。 */
    private val pendingCallbacks = ArrayList<(List<Pair<String, List<Song>>>) -> Unit>()

    /** 返回 歌手名 -> 歌曲列表（有序），在主线程回调。 */
    fun loadArtists(
        context: Context,
        songs: List<Song>,
        callback: (List<Pair<String, List<Song>>>) -> Unit
    ) {
        if (loading) {
            synchronized(pendingCallbacks) { pendingCallbacks.add(callback) }
            return
        }
        val allCached = songs.all { artistCache.containsKey(it.uri.toString()) }
        if (allCached) {
            callback(groupArtists(songs))
            return
        }
        // 首次：尝试加载磁盘缓存，若已覆盖全部歌曲则直接返回
        if (artistCache.isEmpty()) {
            artistCache.putAll(loadDisk(context))
            if (songs.all { artistCache.containsKey(it.uri.toString()) }) {
                callback(groupArtists(songs))
                return
            }
        }
        val appContext = context.applicationContext
        loading = true
        pool.execute {
            try {
                for (song in songs) {
                    val uriStr = song.uri.toString()
                    synchronized(artistCache) {
                        if (artistCache.containsKey(uriStr)) return@synchronized
                    }
                    val artist = try {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(appContext, song.uri)
                        val a = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        retriever.release()
                        a
                    } catch (e: Exception) {
                        null
                    }
                    synchronized(artistCache) {
                        artistCache[uriStr] = artist?.trim()?.takeIf { it.isNotEmpty() }
                            ?: "未知歌手"
                    }
                }
                saveDisk(appContext)
                val result = groupArtists(songs)
                mainHandler.post {
                    loading = false
                    callback(result)
                    synchronized(pendingCallbacks) {
                        val copy = ArrayList(pendingCallbacks)
                        pendingCallbacks.clear()
                        for (c in copy) c(result)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    loading = false
                    val empty = emptyList<Pair<String, List<Song>>>()
                    callback(empty)
                    synchronized(pendingCallbacks) {
                        val copy = ArrayList(pendingCallbacks)
                        pendingCallbacks.clear()
                        for (c in copy) c(empty)
                    }
                }
            }
        }
    }

    private fun groupArtists(songs: List<Song>): List<Pair<String, List<Song>>> {
        val groups = LinkedHashMap<String, MutableList<Song>>()
        for (song in songs) {
            val artist = synchronized(artistCache) {
                artistCache[song.uri.toString()] ?: "未知歌手"
            }
            groups.getOrPut(artist) { mutableListOf() }.add(song)
        }
        return groups.map { (name, list) -> name to list.sortedBy { it.title } }
            .sortedBy { it.first }
    }

    private fun saveDisk(context: Context) {
        try {
            val root = JSONObject()
            synchronized(artistCache) {
                for ((k, v) in artistCache) root.put(k, v)
            }
            File(context.filesDir, FILE_NAME).writeText(root.toString())
        } catch (e: Exception) {
            // 缓存失败不影响主流程
        }
    }

    private fun loadDisk(context: Context): Map<String, String> {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return emptyMap()
            val root = JSONObject(file.readText())
            val map = HashMap<String, String>()
            root.keys().forEach { k -> map[k] = root.getString(k) }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
