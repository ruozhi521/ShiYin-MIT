package com.example.subtitleplayer

import java.text.Collator
import java.util.Locale

/**
 * 歌曲列表排序（3.0.6）。
 *
 * 源自 B 站粉丝反馈：
 * - 「添加更多排序，正序倒序，添加到媒体库时间，修改时间等」
 * - 「可以增加一个按文件修改时间排序吗，因为每次下新的都要找一会」
 *
 * 抽成 object 是为了上 JVM 单测 —— 排序涉及中文拼音、缺失值兜底、稳定性，
 * 都是容易写错的地方（[PlaylistSorter] 同一套思路）。
 */
object SongSorter {

    /** 按标题（中文按拼音）。与 [PlaylistSorter.byName] 同一套 Collator 纪律。 */
    const val NAME = "song_name"
    /** 按文件修改时间，新的在前。 */
    const val MODIFIED = "song_modified"
    /** 按加入媒体库的时间（本地首次扫描到的时间），新的在前。 */
    const val ADDED = "song_added"
    /** 扫描顺序（保持曲库原样，不排）。 */
    const val NONE = "song_none"

    val ALL = listOf(NONE, NAME, MODIFIED, ADDED)

    /**
     * @param desc true = 倒序（名称 Z→A / 时间旧→新）
     * @param added 歌曲 uri → 首次收录时间戳（毫秒）
     */
    fun sort(
        songs: List<Song>,
        mode: String,
        desc: Boolean,
        added: Map<String, Long>
    ): List<Song> {
        if (mode == NONE || songs.size <= 1) return songs
        val collator = Collator.getInstance(Locale.CHINA)

        // ⚠️ 倒序**不能**用 `sorted().reversed()` 实现：那会把「时间缺失排最后」
        // 这条规则一起反转，变成缺失值全跑到最前面（原本就是这么写错的，
        // 被 SongSorterTest 的「缺失永远排最后」两条用例当场抓出）。
        // 所以 desc 必须在比较器**内部**处理，缺失值的判定不参与反转。
        val cmp: Comparator<Song> = when (mode) {
            NAME -> Comparator { a, b ->
                val c = collator.compare(a.title, b.title)
                // Collator 不保证自反性，必须原串兜底，否则 sortWith 可能抛
                // "Comparison method violates its general contract!"
                val base = if (c != 0) c else a.title.compareTo(b.title)
                if (desc) -base else base
            }

            MODIFIED -> Comparator { a, b ->
                val ta = a.lastModified
                val tb = b.lastModified
                when {
                    // 时间缺失（0）统一排到最后，**不论正序倒序** —— 放在中间会显得"排序坏了"
                    ta == 0L && tb == 0L -> a.title.compareTo(b.title)
                    ta == 0L -> 1
                    tb == 0L -> -1
                    desc -> tb.compareTo(ta)
                    else -> ta.compareTo(tb)
                }
            }

            ADDED -> Comparator { a, b ->
                val ta = added[a.uri.toString()] ?: 0L
                val tb = added[b.uri.toString()] ?: 0L
                when {
                    ta == 0L && tb == 0L -> a.title.compareTo(b.title)
                    ta == 0L -> 1
                    tb == 0L -> -1
                    desc -> tb.compareTo(ta)
                    else -> ta.compareTo(tb)
                }
            }

            else -> return songs
        }

        return songs.sortedWith(cmp)
    }
}
