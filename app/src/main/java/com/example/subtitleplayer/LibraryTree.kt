package com.example.subtitleplayer

/**
 * 音乐库文件夹树节点（Poweramp 式缩进树）。
 * [path] 为相对根目录的完整路径，[playlist] 仅叶子挂载（该文件夹本身有歌曲）。
 */
class TreeNode(
    val name: String,
    val path: String,
    val depth: Int,
    var playlist: Playlist?,
    val children: MutableList<TreeNode> = mutableListOf()
) {
    /** 是否有子文件夹（用于决定行显示为目录还是歌单）。 */
    val isDir: Boolean get() = children.isNotEmpty()

    /** 本文件夹及全部子文件夹的歌曲总数（目录行显示）。 */
    val totalSongs: Int
        get() = (playlist?.songs?.size ?: 0) + children.sumOf { it.totalSongs }

    /**
     * 本子树里最近一次播放的时间戳（没有任何播放记录时 0）。
     *
     * 「按最近播放」排序时目录节点也要能浮动：只读自己的 [playlist] 的话，
     * 纯目录节点恒为 0、会被全部压到底部，看起来就像「排序没生效」。
     */
    internal fun bestRecent(recent: Map<String, Long>): Long {
        var best = playlist?.let { recent[it.name] ?: 0L } ?: 0L
        for (c in children) {
            val v = c.bestRecent(recent)
            if (v > best) best = v
        }
        return best
    }

    /**
     * 本子树里最新的文件修改时间（取不到时为 0）（3.0.6）。
     *
     * 与 [bestRecent] 同一个道理：「按修改时间」排序时，纯目录节点若只读自己的
     * [playlist] 就会恒为 0、全被压到底部，看起来像「排序没生效」。
     */
    internal fun bestModified(): Long {
        var best = playlist?.songs?.maxOfOrNull { it.lastModified } ?: 0L
        for (c in children) {
            val v = c.bestModified()
            if (v > best) best = v
        }
        return best
    }
}

object LibraryTree {

    /**
     * 树形视图的排序方式（3.0.6）。
     *
     * 与 [MainActivity] 的 `SORT_*` 是同一批 **prefs 值**（字符串必须一致）。
     * 值放在这里是因为排序逻辑本身在 [build]，取值/存值在 Activity。
     */
    const val SORT_DEFAULT = "default"
    const val SORT_NAME = "name"
    const val SORT_RECENT = "recent"
    const val SORT_RANDOM = "random"
    /** 按文件修改时间（3.0.6）：取子树里最新的那首歌，新的在前。值须与 Activity 的 SORT_MODIFIED 一致。 */
    const val SORT_MODIFIED = "modified"

    /**
     * 从扁平歌单列表构建文件夹树（歌单名 = 相对路径，如 A/B/周杰伦）。
     *
     * @param sortMode 同级节点的排序方式。**默认 [SORT_NAME]**，保持 2.13~3.0.5 的行为。
     *   3.0.6 之前这里写死了「始终按名称排」，导致音乐库页的排序下拉在树形模式下
     *   看起来毫无反应（选了随机/最近，树还是原样）——弱志 2026-11-04 反馈的正是这个。
     * @param recent 歌单名 → 最近播放时间戳，仅 [SORT_RECENT] 用
     * @param seed   仅 [SORT_RANDOM] 用；同一 seed 同一顺序，避免每次刷新乱跳
     */
    fun build(
        playlists: List<Playlist>,
        sortMode: String = SORT_NAME,
        recent: Map<String, Long> = emptyMap(),
        seed: Long = 0L
    ): List<TreeNode> {
        val roots = mutableListOf<TreeNode>()
        val byPath = HashMap<String, TreeNode>()
        for (p in playlists) {
            val parts = p.name.split('/').filter { it.isNotBlank() }
            if (parts.isEmpty()) continue
            var parent: TreeNode? = null
            var path = ""
            for ((i, part) in parts.withIndex()) {
                path = if (path.isEmpty()) part else "$path/$part"
                val node = byPath.getOrPut(path) {
                    TreeNode(part, path, i, null).also {
                        if (parent == null) roots.add(it) else parent!!.children.add(it)
                    }
                }
                parent = node
            }
            // 最后一层挂歌单（该文件夹本身有歌）；仅空位挂载，避免覆盖同名虚拟歌单
            if (parent != null && parent.playlist == null) parent.playlist = p
        }
        sortTree(roots, sortMode, recent, seed)
        return roots
    }

    /** 按 [sortMode] 递归排序整棵树。抽出来便于单测直接验。 */
    fun sortTree(
        roots: MutableList<TreeNode>,
        sortMode: String,
        recent: Map<String, Long> = emptyMap(),
        seed: Long = 0L
    ) {
        // Collator 不保证自反性（compare==0 不代表相等），必须用原串比较兜底，
        // 否则 sortWith 可能抛 "Comparison method violates its general contract!"
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
        val byName = Comparator<TreeNode> { a, b ->
            val c = collator.compare(a.name, b.name)
            if (c != 0) c else a.name.compareTo(b.name)
        }

        fun sortLevel(list: MutableList<TreeNode>, levelKey: String) {
            when (sortMode) {
                SORT_RECENT -> {
                    // 时间戳大的在前；同一时间戳（含从未播放）保持原顺序（稳定排序）。
                    // 目录取子树里最新的那条，否则纯目录会全被压到底部。
                    val score = list.associateWith { it.bestRecent(recent) }
                    list.sortWith(
                        compareByDescending<TreeNode> { score[it] ?: 0L }.then(byName)
                    )
                }

                SORT_RANDOM -> {
                    // 稳定随机：同一 seed + 同一层路径 → 同一顺序，滚动/重绘都不会跳
                    val rnd = java.util.Random(seed xor levelKey.hashCode().toLong())
                    for (i in list.indices.reversed()) {
                        val j = rnd.nextInt(i + 1)
                        val t = list[i]
                        list[i] = list[j]
                        list[j] = t
                    }
                }

                SORT_MODIFIED -> {
                    // 3.0.6：按文件修改时间，新的在前。目录取子树里最新的那条
                    // （否则纯目录恒为 0、全被压到底部，看着像"排序没生效"）。
                    val score = list.associateWith { it.bestModified() }
                    list.sortWith(
                        compareByDescending<TreeNode> { score[it] ?: 0L }.then(byName)
                    )
                }

                else -> {
                    // 名称排序（含 SORT_DEFAULT 的兜底）：纯目录在前，其余按名称。
                    // 默认顺序下用户拖过的顺序由 TreeOrderStore 在 refreshTree 里覆盖。
                    list.sortWith(
                        Comparator<TreeNode> { a, b ->
                            val d = (a.playlist != null).compareTo(b.playlist != null)
                            if (d != 0) d else byName.compare(a, b)
                        }
                    )
                }
            }
            for (n in list) sortLevel(n.children, n.path)
        }

        sortLevel(roots, "")
    }

    /** 按展开状态把树拍平成可见行列表。 */
    fun flatten(roots: List<TreeNode>, expanded: Set<String>): List<TreeNode> {
        val out = mutableListOf<TreeNode>()
        fun walk(nodes: List<TreeNode>) {
            for (n in nodes) {
                out.add(n)
                if (expanded.contains(n.path)) walk(n.children)
            }
        }
        walk(roots)
        return out
    }
}
