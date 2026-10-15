package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ASR 模型完整性判定测试（3.0.1）。
 *
 * 回归目标：粉丝反馈「点『生成歌词』的确定之后就闪退」。
 * 原判定只看「文件存在 && 长度 > 1KB」，下载中途断流留下的半截 onnx
 * （或手动导入的半个文件）会被判成「已就绪」，喂给 sherpa 就可能 native 崩。
 *
 * 纯 JVM 即可：AsrModelRules 不依赖 Context（文件读写留在 SpeechRecManager）。
 */
class AsrModelRulesTest {

    private val encoder = "encoder-epoch-99-avg-1.int8.onnx"
    private val decoderName = "decoder-epoch-99-avg-1.onnx"
    private val joinerName = "joiner-epoch-99-avg-1.int8.onnx"
    private val tokens = "tokens.txt"

    // ---- 账本核对：长度对不上就不放行 ----

    @Test
    fun `长度与账本一致才算可用`() {
        assertTrue(AsrModelRules.isUsable(encoder, 155_123_456, 155_123_456))
    }

    @Test
    fun `半截文件被拒`() {
        assertFalse(AsrModelRules.isUsable(encoder, 30_000_000, 155_123_456))
    }

    @Test
    fun `多出一个字节也拒`() {
        assertFalse(AsrModelRules.isUsable(encoder, 155_123_457, 155_123_456))
    }

    // ---- 没有账本记录时退回按角色的下限判定 ----

    @Test
    fun `无记录时接近完整大小的文件放行`() {
        assertTrue(AsrModelRules.isUsable(encoder, 145_000_000, null))
        assertTrue(AsrModelRules.isUsable(decoderName, 11_000_000, null))
    }

    @Test
    fun `无记录时半截的 onnx 仍被拒`() {
        // 下到一半就断流的 encoder（真实约 148 MB）：老用户目录里正是这种，
        // 统一 256 KB 的通用下限拦不住它 —— 必须按角色分档。
        assertFalse(AsrModelRules.isUsable(encoder, 30_000_000, null))
        assertFalse(AsrModelRules.isUsable(decoderName, 2_000_000, null))
        assertFalse(AsrModelRules.isUsable(encoder, 1024, null))
        assertFalse(AsrModelRules.isUsable(encoder, 0, null))
    }

    @Test
    fun `joiner 下限留足余量以免误杀`() {
        assertTrue(AsrModelRules.isUsable(joinerName, 600_000, null))
        assertFalse(AsrModelRules.isUsable(joinerName, 100_000, null))
    }

    @Test
    fun `tokens 有单独放宽的下限`() {
        assertTrue(AsrModelRules.isUsable(tokens, 2048, null))
        assertFalse(AsrModelRules.isUsable(tokens, 512, null))
    }

    @Test
    fun `账本记录同样管着 tokens`() {
        assertTrue(AsrModelRules.isUsable(tokens, 45_754, 45_754))
        assertFalse(AsrModelRules.isUsable(tokens, 20_000, 45_754))
    }

    // ---- 账本解析与序列化 ----

    @Test
    fun `解析账本`() {
        val map = AsrModelRules.parseSizes("$encoder=155123456\n$tokens=45754")
        assertEquals(155_123_456L, map[encoder])
        assertEquals(45_754L, map[tokens])
    }

    @Test
    fun `坏行全跳过且不抛异常`() {
        val text = "\n# 注释\n没有等号\n=123\n${encoder}=abc\n$tokens=\n$tokens=100\n"
        val map = AsrModelRules.parseSizes(text)
        assertEquals(1, map.size)
        assertEquals(100L, map[tokens])
    }

    @Test
    fun `空账本解析为空表`() {
        assertTrue(AsrModelRules.parseSizes("").isEmpty())
        assertTrue(AsrModelRules.parseSizes("   \n\n").isEmpty())
    }

    @Test
    fun `序列化按文件名排序且可原样解析回来`() {
        val text = AsrModelRules.serializeSizes(mapOf(tokens to 100L, encoder to 200L))
        assertEquals("$encoder=200\n$tokens=100", text)
        assertEquals(mapOf(tokens to 100L, encoder to 200L), AsrModelRules.parseSizes(text))
    }
}
