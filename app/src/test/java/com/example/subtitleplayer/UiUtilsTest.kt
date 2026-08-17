package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Test

/** UiUtils 工具函数测试（纯 JVM）。 */
class UiUtilsTest {

    @Test
    fun `formatTime 零值`() {
        assertEquals("00:00", formatTime(0))
    }

    @Test
    fun `formatTime 59 秒`() {
        assertEquals("00:59", formatTime(59_000))
    }

    @Test
    fun `formatTime 整分钟`() {
        assertEquals("01:00", formatTime(60_000))
        assertEquals("59:59", formatTime(3_599_000))
    }

    @Test
    fun `formatTime 超过一小时带小时位`() {
        assertEquals("1:00:00", formatTime(3_600_000))
        assertEquals("1:01:01", formatTime(3_661_000))
    }

    @Test
    fun `formatTime 负数按 0 处理`() {
        assertEquals("00:00", formatTime(-1000))
    }
}
