package com.example.subtitleplayer

import java.io.InputStream
import java.security.MessageDigest

/**
 * 离线翻译模型的「就位判定」纯规则（3.0）。
 *
 * 为什么要抽出来：[MtModelManager] 的判定依赖 `Context` 与 60 MB 级文件，
 * 在单测里构造不现实；而这四条判定恰恰是最容易改错的地方 ——
 * 版本语义、续传保护、以及「大小相同但版本不同」这个真实存在的坑。
 * 目录枚举与文件读写仍留在 [MtModelManager]，这里只放判断。
 */
object MtModelRules {

    /** 文件长度是否达到「可用」门槛。 */
    fun isFileUsable(length: Long, minBytes: Long): Boolean = length >= minBytes

    /**
     * 版本文件内容是否等于期望版本。
     *
     * `null`（文件不存在 / 读失败）一律视为**不匹配**：宁可让用户重下一次，
     * 也不能把「不知道是哪版」的模型当成当前版本来用。
     * 内容做 `trim()`，容忍写入时的尾随换行或空白。
     */
    fun isVersionOk(content: String?, expected: String): Boolean =
        content?.trim() == expected

    /**
     * 是否应清空目录里的旧模型。
     *
     * **关键语义（别简化成 `content != expected`）**：
     * - 版本文件**存在**且内容不同 → 确实是另一个版本 → 清空重下
     * - 版本文件**不存在**（`content == null`）→ 首次下载，或下载中断尚未写入
     *   → **不清**，否则每中断一次就要重下 216 MB（续传保护）
     *
     * 即：只有「明确是另一个版本」才清；「不知道是哪版」按续传处理。
     */
    fun shouldWipeVersion(content: String?, expected: String): Boolean =
        content != null && content.trim() != expected

    /**
     * 流式计算 SHA-256（十六进制小写）。
     *
     * 用 1 MB 缓冲边读边算，**绝不整读进内存** —— 100 MB 的 ONNX
     * 在低端机上一次性读进去会直接 OOM。
     */
    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        val sb = StringBuilder(64)
        for (b in md.digest()) sb.append(String.format("%02x", b.toInt() and 0xff))
        return sb.toString()
    }
}
