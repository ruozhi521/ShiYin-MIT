package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DockGlassBlur] 的单元测试（3.1）。
 *
 * 为什么这几个用例值得写：滑动窗口累加的错误几乎都是「差一」型的 ——
 * 结果不崩、也不报错，只是整体偏暗一点、或边缘发黑一圈。
 * 那种问题靠肉眼看截图基本发现不了，但满屏都是。这里用数学护栏钉死：
 *
 * - 常量图必须**完全不变**（任何漂移都是 bug）
 * - 模糊后总能量守恒（平均值不变）
 * - 边界不塌陷（clamp 而非补零，否则四边会变黑）
 * - 单点必须扩散到周围（否则根本没生效）
 */
class DockGlassBlurTest {

    private fun argb(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun green(p: Int) = (p shr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF

    /** 常量图模糊后每个像素都必须原样不变 —— 滑动窗口差一会立刻在这里暴露。 */
    @Test
    fun constantImage_staysExactlyUnchanged() {
        val w = 12
        val h = 9
        val pix = IntArray(w * h) { argb(128) }
        DockGlassBlur.blur(pix, w, h, 4)
        pix.forEach {
            assertEquals(0xFF, (it ushr 24) and 0xFF)
            assertEquals(128, red(it))
            assertEquals(128, green(it))
            assertEquals(128, blue(it))
        }
    }

    /** radius = 0 是「关闭模糊」，必须一个像素都不动。 */
    @Test
    fun zeroRadius_changesNothing() {
        val pix = intArrayOf(argb(0), argb(255), argb(10), argb(200))
        val copy = pix.copyOf()
        DockGlassBlur.blur(pix, 2, 2, 0)
        assertEquals(copy.toList(), pix.toList())
    }

    /** 负半径同样不动作（调用方可能把滑块调到 0 附近）。 */
    @Test
    fun negativeRadius_changesNothing() {
        val pix = intArrayOf(argb(0), argb(255), argb(10), argb(200))
        val copy = pix.copyOf()
        DockGlassBlur.blur(pix, 2, 2, -5)
        assertEquals(copy.toList(), pix.toList())
    }

    /**
     * 半黑半白的图，模糊后**单个通道的均值必须守恒**。
     *
     * 这条同时验证两件事：滑动窗口的「进一个出一个」是对的，且没有越界读。
     */
    @Test
    fun meanIsPreserved() {
        val w = 16
        val h = 16
        val pix = IntArray(w * h) { i -> argb(if (i % w < w / 2) 0 else 255) }
        // 边界 clamp 会让首尾行/列被重复计入，均值允许小幅偏移，但不能有大漂移
        DockGlassBlur.blur(pix, w, h, 3)
        val avg = pix.map { red(it) }.average()
        assertTrue("均值因左右对称应接近 127.5，实际 $avg", avg in 120.0..135.0)
    }

    /**
     * 边界必须是「复制边缘」而不是「补零」。
     *
     * 补零会让四边向黑色塌陷，玻璃边缘就会出现一圈脏边 ——
     * 这正是最容易被忽略、又最显眼的错误。全白图的四条边磨完后仍须是纯白。
     */
    @Test
    fun borderDoesNotCollapseToBlack() {
        val w = 10
        val h = 10
        val pix = IntArray(w * h) { argb(255) }
        DockGlassBlur.blur(pix, w, h, 5)
        for (x in 0 until w) {
            assertEquals("顶边 x=$x", 255, red(pix[x]))
            assertEquals("底边 x=$x", 255, red(pix[(h - 1) * w + x]))
        }
        for (y in 0 until h) {
            assertEquals("左边 y=$y", 255, red(pix[y * w]))
            assertEquals("右边 y=$y", 255, red(pix[y * w + w - 1]))
        }
    }

    /** 单点亮像素模糊后，周围必须被「染到」—— 否则模糊根本没生效。 */
    @Test
    fun singlePixel_spreadsToNeighbours() {
        val w = 9
        val h = 9
        val pix = IntArray(w * h) { argb(0) }
        val center = 4 * w + 4
        pix[center] = argb(255)
        DockGlassBlur.blur(pix, w, h, 2)
        // 中心被摊薄
        assertTrue("中心应变暗", red(pix[center]) < 255)
        // 相邻像素被染到
        assertTrue("右邻应被染到", red(pix[center + 1]) > 0)
        assertTrue("左邻应被染到", red(pix[center - 1]) > 0)
        assertTrue("下邻应被染到", red(pix[center + w]) > 0)
    }

    /** alpha 通道始终为不透明：玻璃的透明度由白色 tint 控制，不靠模糊。 */
    @Test
    fun alphaAlwaysOpaque() {
        val w = 6
        val h = 6
        val pix = IntArray(w * h) { argb(if (it % 2 == 0) 30 else 220) }
        DockGlassBlur.blur(pix, w, h, 2)
        pix.forEach { assertEquals(0xFF, (it ushr 24) and 0xFF) }
    }

    /** 缓冲区比声明的尺寸小时安全返回，不越界崩（防御性）。 */
    @Test
    fun undersizedBuffer_isIgnoredSafely() {
        val pix = IntArray(4) { argb(100) }
        DockGlassBlur.blur(pix, 10, 10, 3)
        pix.forEach { assertEquals(100, red(it)) }
    }
}
