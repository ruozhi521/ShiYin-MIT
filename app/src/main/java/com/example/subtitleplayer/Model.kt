package com.example.subtitleplayer

import android.net.Uri

/** 一首音频。 */
data class Song(
    val title: String,
    val uri: Uri,
    val folder: String,
    val artist: String = "",
    /** 文件名去扩展名（小写由匹配方处理）；歌词匹配用它，避免 ID3 标签标题与文件名不一致。 */
    val fileStem: String = "",
    /**
     * 文件字节数。单曲自定义封面用它和文件名一起组成稳定 key（2.11）：
     * 只用文件名时，不同文件夹里同名歌曲会共用同一封面（用户实测串图）；
     * 加上大小后「移动文件」仍稳定（移动不改内容），「同名同大小」概率可忽略。
     * 旧缓存/兜底构造 size=0，封面 key 退回纯文件名（与 2.1 行为一致）。
     */
    val size: Long = 0L
)

/** 一个歌词文件引用（播放时再读取内容）。 */
data class LyricRef(
    val displayName: String,
    val uri: Uri,
    /** 所在文件夹（相对根目录路径），用于歌词精确匹配（同名歌曲不串行）。 */
    val folder: String = ""
)

/** 一个歌单（对应扫描到的一个文件夹）。 */
data class Playlist(
    val name: String,
    val songs: List<Song>
)

/** 一次扫描得到的整个音乐库。 */
data class MusicLibrary(
    val playlists: List<Playlist>,
    val allSongs: List<Song>,
    val lyrics: Map<String, LyricRef>
)

/** 四元组（Kotlin 标准库没有 data class Quadruple，扫描器 m4s 收集用）。 */
data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
