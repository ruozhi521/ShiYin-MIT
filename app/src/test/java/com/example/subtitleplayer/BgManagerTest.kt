package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** BgManager 封面铺满裁剪参数测试（2.12.1：歌词页/播放页封面背景）。 */
class BgManagerTest {

    private fun params(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Triple<Float, Float, Float> {
        val p = BgManager.coverFitParams(srcW, srcH, dstW, dstH)
        return Triple(p[0], p[1], p[2])
    }

    @Test
    fun `正方形封面铺竖屏 取较大比例并水平居中裁切`() {
        val (scale, dx, dy) = params(100, 100, 200, 400)
        assertEquals(4f, scale, 0.0001f)
        // 放大到 400x400，比 200 宽出的部分左右各裁 100
        assertEquals(-100f, dx, 0.0001f)
        assertEquals(0f, dy, 0.0001f)
    }

    @Test
    fun `横图铺正方形 垂直方向刚好铺满不裁`() {
        val (scale, dx, dy) = params(200, 100, 100, 100)
        assertEquals(1f, scale, 0.0001f)
        assertEquals(-50f, dx, 0.0001f)
        assertEquals(0f, dy, 0.0001f)
    }

    @Test
    fun `缩放后两个方向都盖满目标区 不留白`() {
        val srcW = 512
        val srcH = 512
        val dstW = 1080
        val dstH = 1920
        val (scale, dx, dy) = params(srcW, srcH, dstW, dstH)
        val scaledW = srcW * scale
        val scaledH = srcH * scale
        assertTrue("宽度未盖满", scaledW >= dstW - 0.01f)
        assertTrue("高度未盖满", scaledH >= dstH - 0.01f)
        // 居中：裁切量左右（上下）均分
        assertEquals((dstW - scaledW) / 2f, dx, 0.01f)
        assertEquals((dstH - scaledH) / 2f, dy, 0.01f)
    }

    @Test
    fun `非正方形封面取较大比例 保证短边也盖满`() {
        // 300x200 铺 900x900：横向需 x3、纵向需 x4.5，取 4.5 才能两边都盖满
        val (scale, dx, dy) = params(300, 200, 900, 900)
        assertEquals(4.5f, scale, 0.0001f)
        assertEquals((900 - 300 * 4.5f) / 2f, dx, 0.01f)
        assertEquals((900 - 200 * 4.5f) / 2f, dy, 0.01f)
    }

    @Test
    fun `缩小场景也用等比 不拉伸`() {
        // 800x400 铺 400x200：正好 x0.5，两个方向同时贴合，无需裁切
        val (scale, dx, dy) = params(800, 400, 400, 200)
        assertEquals(0.5f, scale, 0.0001f)
        assertEquals(0f, dx, 0.0001f)
        assertEquals(0f, dy, 0.0001f)
    }

    @Test
    fun `非法尺寸退回原尺寸不位移`() {
        assertEquals(Triple(1f, 0f, 0f), params(0, 100, 200, 200))
        assertEquals(Triple(1f, 0f, 0f), params(100, 100, 0, 200))
        assertEquals(Triple(1f, 0f, 0f), params(-1, -1, -1, -1))
    }
}
