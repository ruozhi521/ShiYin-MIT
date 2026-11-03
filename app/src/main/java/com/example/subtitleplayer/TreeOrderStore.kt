package com.example.subtitleplayer

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 树形目录里**每一层内部**的用户拖动顺序（3.0.6）。
 *
 * 粉丝反馈「音乐库的树形目录希望可以增加拖动排序功能」。
 *
 * 为什么不能复用 [PlaylistOrderStore]：那个存的是**扁平的**歌单名序列，
 * 而树是分层结构 —— 同一个名字（如「Disc1」）可能同时出现在 A/Disc1 与 B/Disc1 下，
 * 扁平序列分不清是哪一个。所以这里按**父路径**分组：
 *
 * ```json
 * { "": ["根目录A", "根目录B"], "根目录A": ["Disc2", "Disc1"] }
 * ```
 *
 * 空串键代表根这一层。
 *
 * 兼容策略与 [NameOrderStore] 一致：没拖过 → 返回空 → 树按原来的名称排序，行为不变；
 * 拖过 → 该层按记录排，**不在记录里的节点**（新扫描出来的）按原序追加末尾，不会丢。
 *
 * 只排**同层**：跨层拖动在树形结构里语义不明（拖进别的文件夹＝移动文件？），
 * 本功能只改显示顺序、不动任何文件，所以 UI 层也只允许同层拖动。
 */
object TreeOrderStore {

    private const val FILE_NAME = "tree_order.json"
    private const val MAX_PER_LEVEL = 500

    private val lock = Any()

    private fun file(c: Context): File = File(c.filesDir, FILE_NAME)

    /** 读取全部层的顺序：父路径 -> 该层的节点 path 序列。 */
    fun load(c: Context): Map<String, List<String>> {
        synchronized(lock) {
            val out = LinkedHashMap<String, List<String>>()
            try {
                val f = file(c)
                if (!f.exists() || f.length() == 0L) return out
                val root = JSONObject(f.readText())
                val keys = root.keys()
                while (keys.hasNext()) {
                    val parent = keys.next()
                    val arr = root.optJSONArray(parent) ?: continue
                    val list = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) {
                        val p = arr.optString(i, "")
                        if (p.isNotEmpty()) list.add(p)
                    }
                    out[parent] = list
                }
            } catch (e: Exception) {
                // 解析失败按空表处理，不影响浏览
            }
            return out
        }
    }

    /** 保存某一层的新顺序（拖拽松手时调用）。 */
    fun saveLevel(c: Context, parentPath: String, childPaths: List<String>) {
        synchronized(lock) {
            try {
                val all = load(c).toMutableMap()
                if (childPaths.isEmpty()) all.remove(parentPath) else {
                    all[parentPath] = childPaths.take(MAX_PER_LEVEL)
                }
                val root = JSONObject()
                for ((k, v) in all) {
                    val arr = org.json.JSONArray()
                    v.forEach { arr.put(it) }
                    root.put(k, arr)
                }
                val json = root.toString()
                val f = file(c)
                val tmp = File(f.parentFile, FILE_NAME + ".tmp")
                tmp.writeText(json)
                if (f.exists()) f.delete()
                if (!tmp.renameTo(f)) {
                    f.writeText(json)
                    tmp.delete()
                }
            } catch (e: Exception) {
                // 保存失败不影响展示（下次拖动重新保存）
            }
        }
    }

    /**
     * 按用户顺序重排一层节点。
     *
     * @param parentPath 这一层的父路径（根层传空串）
     * @param nodes 该层节点（已按默认规则排好）
     * @return 重排后的新列表；没记录过这一层时原样返回
     */
    fun apply(c: Context, parentPath: String, nodes: List<TreeNode>): List<TreeNode> {
        if (nodes.size <= 1) return nodes
        val saved = load(c)[parentPath] ?: return nodes
        if (saved.isEmpty()) return nodes
        val byPath = nodes.associateBy { it.path }
        val out = ArrayList<TreeNode>(nodes.size)
        val used = HashSet<String>(nodes.size)
        for (p in saved) {
            val n = byPath[p] ?: continue
            if (used.add(p)) out.add(n)
        }
        for (n in nodes) {
            if (used.add(n.path)) out.add(n)
        }
        return out
    }
}
