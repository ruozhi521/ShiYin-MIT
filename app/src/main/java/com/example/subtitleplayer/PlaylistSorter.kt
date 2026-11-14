package com.example.subtitleplayer


/**
 * 歌单排序纯逻辑（2.13）。
 * 抽成 object 是为了能上 JVM 单测（不依赖 Android 框架）——
 * 排序规则涉及中文排序、缺失记录兜底、随机稳定性，都是容易写错的地方。
 */
object PlaylistSorter {

    /**
     * 按名称排序（3.1：改走 [NameOrder]，汉字与字母按拼音**混排**，数字按整体数值）。
     *
     * ## 3.0.8 的两个实测缺陷（爱发电用户反馈）
     *
     * 旧实现用 `Collator(Locale.CHINA)`，实测行为（见 [NameOrder] 类注释）：
     * - `[Apple, Bob, Zebra, 爱, 爱情, 周杰伦]` —— 汉字**全部排在拉丁字母之后**，
     *   而首字母条按 ICU 拼音把「爱」分到 A 区 → 滑动索引必然定位错乱；
     * - 数字逐位比较，`RJ600000` 排在 `RJ999` 前面。
     *
     * 现在排序与索引条共用 [NameOrder] 的同一个排序键，物理上不可能再不一致。
     */
    fun byName(lists: List<Playlist>): List<Playlist> {
        // 预计算键：每个歌单只做一次拼音转换（n log n 次比较里不再重复转）
        return NameOrder.sortedByNames(lists) { it.name }
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
     * 按文件修改时间排序（3.0.6）：取歌单里**最新**那首歌的修改时间，新的在前。
     *
     * 弱志：「我目前只有歌曲支持修改时间排序，我觉得歌单也应该支持上」——
     * 歌单本身没有「修改时间」这个概念，能代表它新旧的就是里面最新的那首歌。
     * 所以刚下载新歌的那个文件夹会自动浮到前面，正好对上「每次下新的都要找一会」。
     *
     * 时间取不到的（旧缓存没有 lastModified = 0）排最后；全部为 0 时顺序不变
     * （Kotlin 的 sortedWith 是稳定排序）。
     */
    fun byModified(lists: List<Playlist>): List<Playlist> {
        return lists.sortedWith(
            compareByDescending<Playlist> { pl -> pl.songs.maxOfOrNull { it.lastModified } ?: 0L }
        )
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
