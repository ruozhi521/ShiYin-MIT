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
    val size: Long = 0L,
    /**
     * 文件最后修改时间（毫秒，0 = 未知）。3.0.6 新增，供「按修改时间排序」用。
     *
     * 粉丝反馈：「每次下新的都要找一会，能不能按文件修改时间排」——下载新歌 = 修改时间最新，
     * 这正是他要的。从 SAF 的 [android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED] 读；
     * 部分 provider 不给（返回 0），排序时统一排到最后，不会乱序。
     *
     * ⚠️ 默认值 0 是刻意保留的：Song 有 50+ 处构造点，全部补参数不现实，
     * 也不该为了排序改播放器的其他代码。
     */
    val lastModified: Long = 0L
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
    val songs: List<Song>,
    /** 曲库尚未就绪时可显示持久化记录数量；正常歌单仍使用 songs.size。 */
    val displayCount: Int? = null
)

/**
 * 单个根目录的扫描结果（2.13）：
 * `label` 是根显示名（消歧前缀用），`songs`/`lyrics` 里的 folder/key 都是**相对路径**，
 * 尚未按多根规则消歧。增量添加文件夹时，把它当作「多一个根」交给合并逻辑即可。
 */
data class RootScan(
    val label: String,
    val songs: List<Song>,
    val lyrics: Map<String, LyricRef>,
    /** 该根里各文件夹挑出的封面（相对路径 → 图片 uri），多根合并时随歌单一起改名（3.0.6）。 */
    val folderCovers: Map<String, android.net.Uri> = emptyMap()
)

/** 一次扫描得到的整个音乐库。 */
data class MusicLibrary(
    val playlists: List<Playlist>,
    val allSongs: List<Song>,
    val lyrics: Map<String, LyricRef>,
    /**
     * 文件夹封面：歌单名（相对路径）→ 该文件夹里被选作封面的图片 uri（3.0.6）。
     *
     * 粉丝（B 站）：「识别文件夹内的图片为软件内封面图片，不要限制图片格式，
     * 比如必须 cover.jpg 等」。以前只认 MediaStore 的专辑封面（很多本地 ASMR 文件夹
     * 根本没有专辑信息，图片就叫 `封面.png` / `folder.jpg` / 一串数字），
     * 所以永远显示不出封面。
     *
     * 扫描时把文件夹里的**任意图片**收集起来按「命名像封面的优先」挑一张，
     * 由 [CoverLoader] 在歌曲没有内嵌封面时兜底。
     */
    val folderCovers: Map<String, android.net.Uri> = emptyMap()
)

/** 四元组（Kotlin 标准库没有 data class Quadruple，扫描器 m4s 收集用）。 */
data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
