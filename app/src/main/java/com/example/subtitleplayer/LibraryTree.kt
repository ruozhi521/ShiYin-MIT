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
}

object LibraryTree {

    /** 从扁平歌单列表构建文件夹树（歌单名 = 相对路径，如 A/B/周杰伦）。 */
    fun build(playlists: List<Playlist>): List<TreeNode> {
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
        // 排序：纯目录在前，其余按名称。
        // （2.13）树形目录**始终**按名称排，不跟随库页的「随机/最近播放」——
        // 树形是「按文件夹路径浏览」的心智模型，打乱会让人找不到文件夹。
        // 用 Collator(Locale.CHINA) 让中文按拼音排（默认比较按 Unicode 码位，「周」会排在「安」前）。
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
        fun sort(list: MutableList<TreeNode>) {
            list.sortWith { a, b ->
                val d = (a.playlist != null).compareTo(b.playlist != null)
                // Collator 不保证自反性（compare==0 不代表相等），必须用原串比较兜底，
                // 否则 sortWith 可能抛 "Comparison method violates its general contract!"
                if (d != 0) d else {
                    val c = collator.compare(a.name, b.name)
                    if (c != 0) c else a.name.compareTo(b.name)
                }
            }
            for (n in list) sort(n.children)
        }
        sort(roots)
        return roots
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
