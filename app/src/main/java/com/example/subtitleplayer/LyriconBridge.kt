package com.example.subtitleplayer

import android.content.Context
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song as LyriconSong
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider

/**
 * 词幕（LyricOn）Provider 桥接：把拾音的播放状态与歌词推送给词幕中心服务，
 * 配合 LSPosed 词幕模块实现状态栏/悬浮歌词。
 * 词幕未安装或中心服务不可达时所有调用静默失败（不影响正常播放）。
 */
class LyriconBridge(private val context: Context) {

    private var provider: LyriconProvider? = null

    /** 开关：关闭时所有推送静默跳过。 */
    var enabled: Boolean = true
        private set

    private fun ensure(): LyriconProvider? {
        if (!enabled) return null
        if (provider == null) {
            provider = try {
                LyriconFactory.createProvider(context).also {
                    it.autoSync = true
                    it.register()
                }
            } catch (e: Exception) {
                null
            }
        }
        return provider
    }

    /** 开关变更：开启时注册并等待同步，关闭时注销。 */
    fun setEnabled(on: Boolean) {
        enabled = on
        if (on) ensure() else destroy()
    }

    /** 同步当前歌曲与歌词（行级 + 译文）。song 为 null 时清空。 */
    fun syncSong(
        song: Song?,
        lyricLines: List<SubtitleLine>,
        translations: Map<Int, String>,
        durationMs: Int
    ) {
        val p = ensure() ?: return
        try {
            if (song == null) {
                p.player.setSong(null)
                return
            }
            val lines = lyricLines.mapIndexedNotNull { idx, line ->
                if (line.text.isBlank()) return@mapIndexedNotNull null
                RichLyricLine(
                    begin = line.startMs.toLong(),
                    end = (if (line.endMs > line.startMs) line.endMs else line.startMs + 1000).toLong(),
                    text = line.text,
                    secondary = null,
                    translation = translations[idx]?.takeIf { it.isNotBlank() },
                    words = null
                )
            }
            p.player.setSong(
                LyriconSong(
                    id = song.uri.toString(),
                    name = song.title,
                    artist = song.folder.ifBlank { null },
                    duration = durationMs.toLong(),
                    lyrics = lines
                )
            )
            p.player.setDisplayTranslation(true)
        } catch (e: Exception) {
            // 词幕未安装 / 中心服务不可达：静默
        }
    }

    fun syncPosition(posMs: Int) {
        val p = provider ?: return
        try {
            p.player.setPosition(posMs.toLong())
        } catch (_: Exception) {
        }
    }

    fun syncPlaybackState(playing: Boolean) {
        val p = provider ?: return
        try {
            p.player.setPlaybackState(playing)
        } catch (_: Exception) {
        }
    }

    /** 无歌词歌曲：发送纯文本（当前歌曲标题等）。 */
    fun sendText(text: String?) {
        val p = ensure() ?: return
        try {
            p.player.sendText(text)
        } catch (_: Exception) {
        }
    }

    fun destroy() {
        try {
            provider?.unregister()
            provider?.destroy()
        } catch (_: Exception) {
        }
        provider = null
    }
}
