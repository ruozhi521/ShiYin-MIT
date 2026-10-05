package com.example.subtitleplayer

/**
 * 「隐藏」（用户眼里的删除）的判定规则（2.14）。
 *
 * 设计前提：**不碰手机里的真实文件**（弱志明确要求）。所以「删除」的实质是
 * 把内容记进隐藏名单，让它在界面里不再出现。
 *
 * 名单有两类：
 * - **歌曲 uri**：精确隐藏某一首；
 * - **文件夹路径**：隐藏该文件夹**及其全部子文件夹**（「这个系列我全不要了」）。
 *
 * 抽成纯函数是为了能单测（"文件夹是否包含子目录"这种判定最容易写错，
 * 一旦写错就是整片歌曲消失或删不掉）。
 */
internal object HideRules {

    /**
     * [folder] 是否落在 [hiddenFolder] 的范围内（含自身与全部子文件夹）。
     *
     * 按**路径分段**比较，不做子串匹配——否则隐藏「音声」会误伤「我的音声」。
     *
     * 允许最多 1 段根名前缀：多根合并时同名子文件夹会被加上「根名/」前缀消歧
     * （见 `LibraryScanner.mergedFolderName`），所以用户当初隐藏的 `周杰伦`
     * 在加了新根之后可能变成 `根A/周杰伦`。允许这一段前缀错位，
     * 才能让隐藏**在重新扫描后依然有效**（否则用户会觉得"删了又回来了"）。
     * 代价是隐藏 `周杰伦` 也会隐藏 `Download/周杰伦` 这种同名深层目录——
     * 属少见情形，且恢复页里看得到、可撤销。
     */
    fun folderCoveredBy(folder: String, hiddenFolder: String): Boolean {
        val f = folder.split('/').filter { it.isNotEmpty() }
        val h = hiddenFolder.split('/').filter { it.isNotEmpty() }
        if (h.isEmpty() || f.size < h.size) return false
        // 起点 0 = 完全对齐；起点 1 = 跳过一段根名前缀
        for (start in 0..1) {
            if (start + h.size > f.size) continue
            var all = true
            for (i in h.indices) {
                if (f[start + i] != h[i]) {
                    all = false
                    break
                }
            }
            if (all) return true
        }
        return false
    }

    /**
     * 这首歌是否应被隐藏。
     *
     * @param uri 歌曲 uri 字符串
     * @param folder 歌曲所属文件夹（相对路径，与曲库里的 `Song.folder` 同源）
     * @param hiddenUris 被单独隐藏的歌曲 uri
     * @param hiddenFolders 被隐藏的文件夹路径
     */
    fun isSongHidden(
        uri: String,
        folder: String,
        hiddenUris: Set<String>,
        hiddenFolders: Set<String>
    ): Boolean {
        if (uri.isNotEmpty() && uri in hiddenUris) return true
        if (hiddenFolders.isEmpty()) return false
        for (h in hiddenFolders) {
            if (folderCoveredBy(folder, h)) return true
        }
        return false
    }

    /**
     * 这个歌单（文件夹）是否应被隐藏——歌单名本身就是文件夹路径。
     * 隐藏了子文件夹时，父文件夹的歌单**不该**跟着消失，所以这里只判"自己是否在范围内"。
     */
    fun isPlaylistHidden(playlistName: String, hiddenFolders: Set<String>): Boolean {
        if (hiddenFolders.isEmpty()) return false
        for (h in hiddenFolders) {
            if (folderCoveredBy(playlistName, h)) return true
        }
        return false
    }
}
