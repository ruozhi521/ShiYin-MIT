package com.example.subtitleplayer

import android.net.Uri

/** 一首音频。 */
data class Song(
    val title: String,
    val uri: Uri,
    val folder: String,
    val artist: String = "",
    /** 文件名去扩展名（小写由匹配方处理）；歌词匹配用它，避免 ID3 标签标题与文件名不一致。 */
    val fileStem: String = ""
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
