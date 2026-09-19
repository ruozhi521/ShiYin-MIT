package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Test

/** CoverManager.songKey 稳定 key 测试（2.11：文件名|大小，同名不同文件夹不串图）。 */
class CoverManagerTest {

    @Test
    fun `带大小时 key 是 文件名竖线大小`() {
        assertEquals("序章.mp3|12345", CoverManager.songKey("content://docs/document/A%2F%E5%BA%8F%E7%AB%A0.mp3", 12345L))
    }

    @Test
    fun `大小为 0 时退回纯文件名 key 兼容 21 旧数据`() {
        assertEquals("序章.mp3", CoverManager.songKey("content://docs/document/A%2F%E5%BA%8F%E7%AB%A0.mp3", 0L))
    }

    @Test
    fun `同文件名不同大小得到不同 key`() {
        val a = CoverManager.songKey("content://docs/document/A%2Fsong.mp3", 111L)
        val b = CoverManager.songKey("content://docs/document/B%2Fsong.mp3", 222L)
        assertEquals(false, a == b)
    }

    @Test
    fun `同文件名同大小不同文件夹 key 相同 移动文件后仍命中`() {
        val a = CoverManager.songKey("content://docs/document/A%2Fsong.mp3", 111L)
        val b = CoverManager.songKey("content://docs/document/B%2Fsong.mp3", 111L)
        assertEquals(a, b)
    }

    @Test
    fun `uri 带查询参数时截断`() {
        assertEquals("s.mp3|7", CoverManager.songKey("content://x/document/s.mp3?token=1", 7L))
    }
}
