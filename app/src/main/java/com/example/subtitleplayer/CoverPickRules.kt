package com.example.subtitleplayer

import java.util.Locale

/**
 * 「哪张图最像封面」的命名规则（3.1.1）。
 *
 * ## 为什么单独抽出来
 *
 * B站粉丝：「检测封面时可以读取 rj 号文件夹下美的（封面）（有logo）等字样的图片吗」。
 *
 * 3.0.6 那套规则只有 9 个关键词（cover / folder / front / album / jacket / 封面 /
 * ジャケット / 表紙 / 収録），是从普通话音乐专辑推出来的。而音声（ASMR）素材里，
 * 封面图的实际命名完全是另一套：
 *
 * - DLsite 下载包里常见 `RJ01234567_img_main.jpg`、`パッケージ.png`、`サムネイル.jpg`
 * - 中文搬运/汉化版常见 `封面.jpg`、`（封面）（有logo）.png`、`主图`、`大图`
 * - 有的直接就用 RJ 号当文件名：`RJ01234567.jpg`
 *
 * 规则缺失的直接后果：这些名字一个都不命中关键词，于是退化成「按文件名排序取第一个」，
 * 常常挑到 `サンプル1.jpg`（试听截图）或 `001.jpg` 之类的杂图。
 *
 * ## 分档而不是布尔值
 *
 * 只保留「命中/未命中」两档时，命中关键词的图之间仍然只能按名称排序 ——
 * 「封面_有logo.png」和「封面.png」谁是封面就靠字典序瞎猜。分三档后，
 * `封面` / `cover` / `パッケージ` 这类明确的排在 `有logo` / `main` 这类辅助的前面。
 *
 * 纯字符串逻辑，不依赖 Android，可以直接单测。
 */
internal object CoverPickRules {

    /**
     * 第 0 档：几乎必然是封面。
     *
     * 全部用小写比较；中文/日文不受大小写影响，一并放进来省一次转换。
     */
    private val TIER0 = arrayOf(
        "封面", "表紙", "ジャケット", "ジャケ",
        "cover", "front", "jacket", "package", "パッケージ"
    )

    /**
     * 第 1 档：很可能是封面，但优先级低于第 0 档。
     *
     * `有logo` 是粉丝原话里明确提到的写法；`サムネ`/`サムネイル`（缩略图）、
     * `main`/`メイン`（主图）在 DLsite 包里极常见。
     */
    private val TIER1 = arrayOf(
        "folder", "album", "disc", "disk",
        "サムネ", "サムネイル", "メイン", "main",
        "パッケージ画像", "有logo", "主图", "大图", "写真", "イラスト", "収録"
    )

    /** 纯 RJ 号文件名（`RJ01234567.jpg` / `rj123456.png`）：DLsite 素材的直接命名习惯。 */
    private val RJ_NAME = Regex("""^rj\d{4,}""")

    /**
     * 给文件名分档：0 = 明确封面，1 = 可能是封面，2 = 看不出。
     *
     * 判分只看**文件名主干**（调用方可传带扩展名的原名，这里自行忽略扩展名不必要，
     * 因为关键词不会命中常见扩展名，但 RJ 正则要容忍后面的 `_img_main` 之类尾巴）。
     */
    fun tierOf(fileName: String): Int {
        val n = fileName.lowercase(Locale.ROOT)
        if (RJ_NAME.containsMatchIn(n)) return 0
        if (TIER0.any { n.contains(it) }) return 0
        if (TIER1.any { n.contains(it) }) return 1
        return 2
    }

    /** 名字里是否带封面迹象（第 0、1 档都算）。 */
    fun isHinted(fileName: String): Boolean = tierOf(fileName) < 2

    /**
     * 比较两张图谁更该当封面。
     *
     * 顺序：**档位 → 名称**。名称用字典序保证结果稳定可预期
     * （扫描顺序受文件系统影响，不稳定就会「同一张专辑有时显示这张有时那张」）。
     */
    fun compare(a: String, b: String): Int {
        val ta = tierOf(a)
        val tb = tierOf(b)
        if (ta != tb) return ta - tb
        return a.lowercase(Locale.ROOT).compareTo(b.lowercase(Locale.ROOT))
    }

    /** 从候选里挑一个最强的；空列表返回 null。 */
    fun pickBest(names: List<String>): String? =
        names.minWithOrNull { a, b -> compare(a, b) }
}
