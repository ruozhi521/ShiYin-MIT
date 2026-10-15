package com.example.subtitleplayer

/**
 * 识别（ASR）模型的「完整性判定」纯规则（3.0.1）。
 *
 * 为什么要抽出来：粉丝反馈「点『生成歌词』的确定之后闪退」。
 * 查下来 [SpeechRecManager] 判「模型已就绪」只看 `文件存在 && 长度 > 1KB` ——
 * 下载中途断流留下的半截 onnx、手动导入的半个文件，都会被判成「就绪」，
 * 然后被喂给 sherpa 加载：轻则报错，重则 native 崩（用户看到的就是闪退）。
 *
 * 现在引入「下载时记下每个文件的完整大小」的账本（`asr_model/.sizes`），
 * 之后按账本核对长度；账本里没有的文件（纯手动导入、从没下载过）才退回下限判定。
 *
 * 与 [MtModelRules] 同样的理由做成纯函数、不碰 `Context`：这里全是判断、
 * 最容易改错，而在单测里造 148 MB 的 onnx 不现实。
 */
object AsrModelRules {

    /** 账本文件名（放在模型目录里，`resolvedFile` 不会把它当模型文件）。 */
    const val SIZES_FILE = ".sizes"

    /**
     * 该类文件的**合理性下限**（不是精确大小，只用来挡「明显不完整」）。
     *
     * 为什么必须**按角色分档**：账本（[SIZES_FILE]）只对**新下载**的文件有效 ——
     * 已经下过、或手动导入过的老用户没有账本，此时唯一能挡的就是这个下限。
     * 若统一用 256 KB，一个下到 30 MB 就断流的 encoder（真实约 148 MB）
     * 照样会被判「已就绪」，加固对这批用户等于没做 —— 而「点生成歌词就闪退」
     * 那位的目录里很可能正是这种半截文件。
     *
     * 数值取官方那套 `zipformer-ja-reazonspeech` 的**宽松比例**
     * （encoder ≈ 148 MB / decoder ≈ 11 MB / joiner ≈ 2.6 MB / tokens ≈ 45 KB），
     * 都留了大余量：宁可漏放一个坏文件，也不误杀一个正常文件
     * （误杀的代价是让用户白重下 165 MB）。
     */
    fun minBytesFor(name: String): Long {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".txt") -> 1024L
            lower.startsWith("encoder") -> 100L * 1024 * 1024 // 实际 ~148 MB
            lower.startsWith("decoder") -> 6L * 1024 * 1024 // 实际 ~11 MB
            lower.startsWith("joiner") -> 256L * 1024 // 实际 ~2.6 MB（int8 / fp32 两版）
            else -> 256L * 1024
        }
    }

    /**
     * 这个文件能不能当「模型就绪」用。
     *
     * @param expected 账本里记下的完整大小；`null` = 没有记录（纯手动导入、老版本升上来）
     *
     * - **有记录**：长度必须**完全相等**。下载中途断流是最常见的坏法，差一个字节都不放行
     *   —— 半截 onnx 喂给 sherpa 就是闪退的来源。
     * - **无记录**：只能退回「不低于该类型的下限」，挡掉明显坏的。
     *
     * 账本只记 `Content-Length` / `X-Linked-Size` 这类**服务器声明的长度**
     * （见 [SpeechRecManager] 的下载流程），所以记录值本身可信，不会自我循环论证。
     */
    fun isUsable(name: String, length: Long, expected: Long?): Boolean {
        if (length < minBytesFor(name)) return false
        if (expected != null && length != expected) return false
        return true
    }

    /**
     * 解析账本内容（每行 `文件名=字节数`）。**坏行直接跳过、绝不抛异常** ——
     * 账本坏了只该退化成「没有记录」，不该让整个识别功能打不开。
     */
    fun parseSizes(text: String): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (line in text.lineSequence()) {
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) continue
            val i = s.lastIndexOf('=')
            if (i <= 0 || i == s.length - 1) continue
            val name = s.substring(0, i).trim()
            val len = s.substring(i + 1).trim().toLongOrNull() ?: continue
            if (name.isEmpty() || len <= 0) continue
            out[name] = len
        }
        return out
    }

    /** 账本序列化：按文件名排序，人工核对与幂等比对都方便。 */
    fun serializeSizes(sizes: Map<String, Long>): String =
        sizes.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }
}
