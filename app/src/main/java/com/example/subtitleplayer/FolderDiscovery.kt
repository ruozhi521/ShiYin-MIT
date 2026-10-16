package com.example.subtitleplayer

import android.content.Context
import android.provider.MediaStore

/** 媒体库里发现的一个「含音频的文件夹」。 */
data class FoundFolder(
    /** SAF docId，如 `primary:asmr/RJ1700278` —— 可直接与已授权树比对，也用于授权时预定位。 */
    val docId: String,
    /** 给用户看的位置（相对存储根的路径），如 `asmr/RJ1700278`。 */
    val label: String,
    /** 该文件夹里的音频文件数。 */
    val count: Int
)

/**
 * 「一键扫描文件库」的发现逻辑（3.0.1 · 方案 D）。
 *
 * **为什么只能做到这个程度**：Android 11+ 明确**禁止 SAF 授权内部存储根 / Download 根 /
 * `Android/data` / `Android/obb`** —— 所以「一次授权整个手机」在系统层面就不可能。
 * 能替用户省掉的，是「自己翻遍目录去找文件夹」这一步：先问系统媒体库
 * 「手机里到底哪些文件夹有音频」，再把它们**由多到少**排好摆出来，
 * 用户勾选后逐个授权（授权时用 `EXTRA_INITIAL_URI` 直接定位到那一个，不用再翻）。
 *
 * 为什么用媒体库而不是直接遍历文件系统：Android 11+ 没有 `MANAGE_EXTERNAL_STORAGE`
 * 就遍历不了全盘，而那个权限要用户去系统设置手动开（方案 B，改动大、引导重）。
 * 媒体库只要一个运行时权限，且**不写入任何东西**、只读。
 *
 * 纯函数（[toDocId] / [aggregate] / [dropNested] / [sortForDisplay]）与查询分离，
 * 便于单测 —— 判断逻辑才是最容易写错的部分。
 */
object FolderDiscovery {

    /**
     * MediaStore 的 `DATA` 绝对路径 → SAF docId。
     *
     * 例：`/storage/emulated/0/asmr/RJ1/a.mp3` → `primary:asmr/RJ1`；
     * `/storage/ABCD-1234/Music/b.mp3` → `ABCD-1234:Music`（SD 卡卷名照搬）。
     * 非 `/storage/...` 形态（或就在卷根）返回 null，调用方跳过。
     */
    fun toDocId(absPath: String): String? {
        val marker = "/storage/"
        if (!absPath.startsWith(marker)) return null
        val rest = absPath.substring(marker.length)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val volume = rest.substring(0, slash)
        var rel = rest.substring(slash + 1)
        if (volume == "emulated") {
            // /storage/emulated/<userId>/剩余 —— 去掉用户号那一段
            val s2 = rel.indexOf('/')
            if (s2 < 0) return null
            rel = rel.substring(s2 + 1)
        }
        rel = rel.trim('/')
        if (rel.isEmpty()) return null
        val safVolume = if (volume == "emulated") "primary" else volume
        return "$safVolume:$rel"
    }

    /**
     * 把「音频文件所在的绝对目录」聚合成文件夹（纯函数）。
     *
     * ⚠️ 入参必须是**父目录**路径（**不含文件名**）—— [discover] 里对 MediaStore 的文件路径
     * 做过 `substringBeforeLast('/')`；直接传文件全路径会被当成「文件名本身是个目录」。
     *
     * 同一文件夹出现多次 → 计数累加；标签取 docId 里冒号后的相对路径。
     */
    fun aggregate(parentPaths: List<String>): List<FoundFolder> {
        val counts = LinkedHashMap<String, Int>()
        for (p in parentPaths) {
            val doc = toDocId(p) ?: continue
            counts[doc] = (counts[doc] ?: 0) + 1
        }
        return counts.entries.map { (doc, n) ->
            FoundFolder(doc, doc.substringAfter(':'), n)
        }
    }

    /**
     * 去掉被上层目录覆盖的子目录 —— 授权上层就能扫到下层，没必要让用户多点几次。
     *
     * 例：同时发现 `primary:asmr/RJ1` 与 `primary:asmr/RJ1/SEありver` → 只留前者。
     */
    fun dropNested(list: List<FoundFolder>): List<FoundFolder> {
        val docs = list.map { it.docId }
        return list.filter { f ->
            docs.none { other -> other != f.docId && f.docId.startsWith("$other/") }
        }
    }

    /**
     * 展示排序：曲目多的在前；其次**层级浅的**（外层目录一次覆盖更多）；最后按路径字典序。
     */
    fun sortForDisplay(list: List<FoundFolder>): List<FoundFolder> =
        list.sortedWith(
            compareByDescending<FoundFolder> { it.count }
                .thenBy { it.docId.count { c -> c == '/' } }
                .thenBy { it.docId }
        )

    /**
     * 查系统媒体库，返回手机里所有含音频的文件夹（已聚合、去嵌套、排好序）。
     *
     * 需要 `READ_MEDIA_AUDIO`（API 33+）或 `READ_EXTERNAL_STORAGE`（其余）；
     * 没有权限时系统返回空游标 → 这里返回空表，调用方负责先要权限。
     */
    fun discover(context: Context): List<FoundFolder> {
        val parents = ArrayList<String>()
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.DATA),
                null,
                null,
                null
            )?.use { c ->
                val idx = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                if (idx >= 0) {
                    while (c.moveToNext()) {
                        val p = c.getString(idx) ?: continue
                        val parent = p.substringBeforeLast('/', "")
                        if (parent.isNotEmpty()) parents.add(parent)
                    }
                }
            }
        } catch (e: Exception) {
            PlaybackLog.log("folder discover THREW: ${e.javaClass.simpleName}: ${e.message}")
        }
        PlaybackLog.log("folder discover: ${parents.size} audio files")
        return dropNested(sortForDisplay(aggregate(parents)))
    }
}
