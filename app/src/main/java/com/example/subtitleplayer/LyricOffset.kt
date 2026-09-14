package com.example.subtitleplayer

import android.content.Context
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 歌词整体偏移（校准）。
 *
 * 语义：偏移 = 文件自带 [offset:] 标签 + 应用内追加偏移（prefs）。
 * - 解析器 SubtitleParser 已把文件标签算进每行 startMs（parseLrc 读第一个 [offset:]）
 * - 应用内追加偏移按歌曲 uri 记忆（重启保留），由 Service.loadLyric 在解析后统一平移
 * - 「写入歌词文件」把 追加偏移 合并进文件的 [offset:] 标签（替换已有标签），成功后清零追加偏移，
 *   这样其他播放器打开同一个 lrc 也是对齐的
 *
 * 与 SubtitleLine/LrcLine 解耦的纯逻辑，便于单测。
 */
object LyricOffset {

    private const val PREFS_NAME = "lyric_offsets"
    private const val MAX_OFFSET_MS = 60_000

    /** lrc 标准 offset 标签：允许负数与正号，兼容大小写与标签内空格。 */
    private val OFFSET_TAG_RE =
        Regex("""\[ *offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    /** lrc 时间标签（无时间戳的纯文本歌词不能写 offset 标签，否则会显示成歌词行）。 */
    private val LRC_TIME_RE = Regex("""\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]""")

    /** 是否是带时间戳的 lrc（只有这种才值得写 offset 标签进文件）。 */
    fun hasTimeTag(text: String): Boolean = LRC_TIME_RE.containsMatchIn(text)

    // ---------- 应用内追加偏移（SharedPreferences 记忆） ----------

    fun load(context: Context, uriKey: String): Int =
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(uriKey, 0)
        } catch (e: Exception) {
            0
        }

    fun save(context: Context, uriKey: String, offsetMs: Int) {
        try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val clamped = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
            if (clamped == 0) sp.edit().remove(uriKey).apply()
            else sp.edit().putInt(uriKey, clamped).apply()
        } catch (e: Exception) {
        }
    }

    // ---------- 行平移（解析完成后在内存中整体挪动） ----------

    fun applyToLines(lines: List<SubtitleLine>, offsetMs: Int): List<SubtitleLine> {
        if (offsetMs == 0 || lines.isEmpty()) return lines
        return lines.map { line ->
            // lrc 行 endMs=Int.MAX_VALUE：不能加偏移（会溢出），保持原值
            val end = if (line.endMs == Int.MAX_VALUE) line.endMs else line.endMs + offsetMs
            line.copy(startMs = line.startMs + offsetMs, endMs = end)
        }
    }

    // ---------- 写入歌词文件：[offset:±ms] 标签 ----------

    /** 读出歌词文本里现有的 offset 标签值（没有/解析失败返回 0）。 */
    fun detectOffsetTag(text: String): Int =
        OFFSET_TAG_RE.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /**
     * 生成带新 offset 标签的 lrc 文本：
     * - 移除所有旧 offset 标签（解析器只认第一个，残留标签会造成双重偏移）
     * - offsetMs 为 0 时不再写入标签（恢复原样）
     */
    fun buildLrcWithOffset(text: String, offsetMs: Int): String {
        val stripped = OFFSET_TAG_RE.replace(text, "").trimStart('\n')
        return if (offsetMs == 0) stripped else "[offset:${offsetMs}]\n$stripped"
    }

    // ---------- 文本解码（与 MediaPlaybackService 内嵌歌词同规则，公开给歌词写入用） ----------

    fun decodeLrcText(bytes: ByteArray): String {
        val data = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(data)).toString()
        } catch (e: CharacterCodingException) {
            try {
                String(data, charset("GBK"))
            } catch (e2: Exception) {
                String(data, StandardCharsets.UTF_8)
            }
        }
    }
}
