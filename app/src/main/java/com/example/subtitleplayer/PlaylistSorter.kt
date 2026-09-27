package com.example.subtitleplayer

import java.text.Collator
import java.util.Locale

/**
 * 歌单排序纯逻辑（2.13）。
 * 抽成 object 是为了能上 JVM 单测（不依赖 Android 框架）——
 * 排序规则涉及中文排序、缺失记录兜底、随机稳定性，都是容易写错的地方。
 */
object PlaylistSorter {

    /**
     * 按名称排序（中文按拼音）。
     * 用 Collator(Locale.CHINA) 而非默认比较：Kotlin 默认按 Unicode 码位，
     * 中文会排得完全不符合直觉（「周」U+5468 会排在「安」U+5B89 前面）。
     *
     * 注意：Collator 官方明确**不保证自反性**（compare(a,b)==0 不代表 a==b，
     * 例如它可能忽略某些标点差异）。直接用它会破坏 Comparator 的全序契约，
     * 触发 `Comparison method violates its general contract!` 崩溃。
     * 因此 compare 返回 0 时用原始字符串比较兜底，保证严格全序。
     */
    fun byName(lists: List<Playlist>): List<Playlist> {
        val collator = Collator.getInstance(Locale.CHINA)
        return lists.sortedWith { a, b ->
            val c = collator.compare(a.name, b.name)
            if (c != 0) c else a.name.compareTo(b.name)
        }
    }

    /**
     * 按最近播放排序：时间戳大的在前；从没播过（无记录）的排最后。
     *
     * 只用时间戳一个键，不追加 `thenBy { it.name }`（2.13 修正）：
     * - Kotlin 的 `sortedWith` 是**稳定排序**，同一时间戳（含全部无记录 = 0）会保持输入原顺序 ——
     *   这正是我们想要的「没听过的歌单按扫描顺序排在末尾」；
     * - 原先追加的 `thenBy { it.name }` 用的是 **Unicode 码位**，而 [byName] 用的是 **拼音**，
     *   同一批歌单在两种排序下尾部顺序不一致，会让用户觉得「排序乱了」。
     *   统一为「名字相关一律走 [byName]」，本函数不碰名字。
     */
    fun byRecent(lists: List<Playlist>, recent: Map<String, Long>): List<Playlist> {
        return lists.sortedWith(compareByDescending<Playlist> { recent[it.name] ?: 0L })
    }

    /**
     * 稳定随机：同一 seed 得到同一顺序。
     * 不能直接用 shuffled()——它每次调用都换顺序，列表重绘时会「跳动」。
     */
    fun shuffledStable(lists: List<Playlist>, seed: Long): List<Playlist> {
        if (lists.size <= 1) return lists
        val idx = lists.indices.toMutableList()
        val rnd = java.util.Random(seed)
        // Fisher-Yates
        for (i in idx.indices.reversed()) {
            val j = rnd.nextInt(i + 1)
            val t = idx[i]
            idx[i] = idx[j]
            idx[j] = t
        }
        return idx.map { lists[it] }
    }
}
