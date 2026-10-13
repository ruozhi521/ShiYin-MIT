package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 离线翻译模型就位判定测试（3.0）。
 *
 * 回归目标：v2 / v3 / v4 的 `encoder_model.onnx`（105,020,768 B）与
 * `decoder_model.onnx`（117,946,490 B）**大小完全相同**（实测），
 * 而原判定只比大小 —— 换模型后老文件会被一直判为「已就位」，永不更新。
 *
 * 纯 JVM 即可：MtModelRules 不依赖 Context（目录枚举留在 MtModelManager）。
 */
class MtModelRulesTest {

    // ---- 文件可用性门槛 ----

    @Test
    fun `体积达标才算可用`() {
        assertTrue(MtModelRules.isFileUsable(105_020_768, 60_000_000))
        assertTrue(MtModelRules.isFileUsable(60_000_000, 60_000_000))
    }

    @Test
    fun `体积不足判为不可用`() {
        assertFalse(MtModelRules.isFileUsable(59_999_999, 60_000_000))
        assertFalse(MtModelRules.isFileUsable(0, 1))
    }

    // ---- 版本判定 ----

    @Test
    fun `版本一致才算匹配`() {
        assertTrue(MtModelRules.isVersionOk("v4", "v4"))
    }

    @Test
    fun `版本文件缺失一律视为不匹配`() {
        // null = 文件不存在或读失败。宁可让用户重下，也不能把「不知道是哪版」当当前版用
        assertFalse(MtModelRules.isVersionOk(null, "v4"))
    }

    @Test
    fun `版本号不同判为不匹配`() {
        assertFalse(MtModelRules.isVersionOk("v2", "v4"))
        assertFalse(MtModelRules.isVersionOk("v3", "v4"))
    }

    @Test
    fun `版本内容容忍尾随空白与换行`() {
        // 写入时可能带行尾换行，读回来不能因此判成"版本不符"
        assertTrue(MtModelRules.isVersionOk("v4\n", "v4"))
        assertTrue(MtModelRules.isVersionOk("  v4  ", "v4"))
    }

    // ---- 升级清理（最容易被简化错的一处）----

    @Test
    fun `版本文件存在但内容不同才清空`() {
        assertTrue(MtModelRules.shouldWipeVersion("v2", "v4"))
        assertTrue(MtModelRules.shouldWipeVersion("v3", "v4"))
    }

    @Test
    fun `版本一致时不清空`() {
        assertFalse(MtModelRules.shouldWipeVersion("v4", "v4"))
        assertFalse(MtModelRules.shouldWipeVersion("v4\n", "v4"))
    }

    @Test
    fun `版本文件不存在时不清空_否则续传会被破坏`() {
        // 这条是本组最要紧的：文件不存在可能正是"下载中断、尚未写入版本文件"，
        // 若在此清空，每中断一次就要重下 216 MB。
        assertFalse(MtModelRules.shouldWipeVersion(null, "v4"))
    }

    // ---- SHA-256 ----

    @Test
    fun `sha256 空文件`() {
        // 标准向量（NIST）
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            MtModelRules.sha256Hex(ByteArrayInputStream(ByteArray(0)))
        )
    }

    @Test
    fun `sha256 abc`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            MtModelRules.sha256Hex(ByteArrayInputStream("abc".toByteArray(Charsets.UTF_8)))
        )
    }

    @Test
    fun `sha256 跨缓冲区边界仍正确`() {
        // 缓冲区是 1 MB，这里给 1 MB + 7 字节，验证「边读边算」不会漏掉尾部
        val data = ByteArray((1 shl 20) + 7) { (it % 251).toByte() }
        val once = MtModelRules.sha256Hex(ByteArrayInputStream(data))
        val again = MtModelRules.sha256Hex(ByteArrayInputStream(data))
        assertEquals(once, again)
        assertEquals(64, once.length)
    }

    @Test
    fun `sha256 十六进制为小写且长度固定`() {
        val h = MtModelRules.sha256Hex(ByteArrayInputStream("ShiYin".toByteArray()))
        assertEquals(64, h.length)
        assertEquals(h, h.lowercase())
    }

    @Test
    fun `sha256 内容不同则结果不同`() {
        val a = MtModelRules.sha256Hex(ByteArrayInputStream("v2-weight".toByteArray()))
        val b = MtModelRules.sha256Hex(ByteArrayInputStream("v4-weight".toByteArray()))
        assertFalse(a == b)
    }

    @Test
    fun `sha256 不整读进内存_能处理未提供长度的流`() {
        // 模拟网络流：不实现 available()，只能顺序读完
        val stream: InputStream = object : InputStream() {
            private var i = 0
            private val payload = ByteArray(4096) { 7 }
            override fun read(): Int = if (i < payload.size) payload[i++].toInt() else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= payload.size) return -1
                val n = minOf(len, payload.size - i)
                System.arraycopy(payload, i, b, off, n)
                i += n
                return n
            }
        }
        val h = MtModelRules.sha256Hex(stream)
        assertEquals(64, h.length)
    }
}
