package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频画面适配测试（2.13）。
 *
 * 回归目标：修掉「画面显示不完整 / 只剩局部 / 被拉伸」。
 * 根因是适配矩阵的**坐标空间用错**——`TextureView` 默认把画面拉伸铺满 view（fitXY），
 * `setTransform` 作用在 view 空间，所以缩放比必须是「目标尺寸 / view 尺寸」。
 * 早期实现写成「view 像素 / 视频像素」，只要 view 比视频大就会放大溢出、四周被裁。
 *
 * 关键：断言「适配后的画面**完全落在 view 内**」，这正是被破坏的性质。
 */
class VideoFitTest {

    /** 适配后画面在 view 内的实际尺寸（fitXY 语义：内容 = scale × view）。 */
    private fun contentOf(viewW: Int, viewH: Int, f: VideoFit.Fit) =
        VideoFit.contentSize(viewW, viewH, f)

    /** 画面是否完整落在 view 内（无裁切）。 */
    private fun fitsInside(viewW: Int, viewH: Int, f: VideoFit.Fit): Boolean {
        val (cw, ch) = contentOf(viewW, viewH, f)
        return cw <= viewW + 1e-3f && ch <= viewH + 1e-3f
    }

    /** 画面宽高比是否与视频一致（无变形）。注意：内容是 scale×view，不能用 scaleX/scaleY 比。 */
    private fun ratioKept(viewW: Int, viewH: Int, srcW: Int, srcH: Int, f: VideoFit.Fit): Boolean {
        val (cw, ch) = contentOf(viewW, viewH, f)
        if (ch == 0f) return false
        return kotlin.math.abs(cw / ch - srcW.toFloat() / srcH) < 1e-3f
    }

    // ---------- 核心回归：粉丝实机数据 ----------

    @Test
    fun `粉丝 852x480 视频放进 2680x1085 必须完整显示`() {
        // 粉丝日志真实数据（vivo V2338A，video surface available 2680x1085）
        val f = VideoFit.fit(2680, 1085, 852, 480)!!
        assertTrue("画面不得超出 view（旧实现会裁掉 56%），实际=$f", fitsInside(2680, 1085, f))
        assertTrue("宽高比必须保持 852:480", ratioKept(2680, 1085, 852, 480, f))
    }

    @Test
    fun `粉丝 852x480 视频放进竖屏 1116x2330 必须完整显示`() {
        val f = VideoFit.fit(1116, 2330, 852, 480)!!
        assertTrue(fitsInside(1116, 2330, f))
        assertTrue(ratioKept(1116, 2330, 852, 480, f))
    }

    @Test
    fun `弱志 2480x1116 录屏放进 2480x900 不得被拉伸`() {
        // 弱志截图实测：view 高 900 时左侧黑边 341~342px 与旧公式吻合，但比例被拉成 2.567
        val f = VideoFit.fit(2480, 900, 2480, 1116)!!
        assertTrue(fitsInside(2480, 900, f))
        assertTrue("显示比例必须等于视频的 2480:1116", ratioKept(2480, 900, 2480, 1116, f))
    }

    // ---------- 通用性质 ----------

    @Test
    fun `任意比例组合都完整落在 view 内`() {
        val views = listOf(2680 to 1085, 2480 to 900, 1116 to 2330, 1080 to 1920, 2400 to 1080)
        val videos = listOf(
            852 to 480, 1920 to 1080, 1280 to 720, 640 to 360, 3840 to 2160,
            1080 to 1920, 720 to 1280, 2480 to 1116, 100 to 1000, 2000 to 200
        )
        for ((vw, vh) in views) {
            for ((sw, sh) in videos) {
                val f = VideoFit.fit(vw, vh, sw, sh)!!
                assertTrue(
                    "view ${vw}x$vh / video ${sw}x$sh 超出 view：$f",
                    fitsInside(vw, vh, f)
                )
                assertTrue(
                    "view ${vw}x$vh / video ${sw}x$sh 比例失真：$f",
                    ratioKept(vw, vh, sw, sh, f)
                )
            }
        }
    }

    @Test
    fun `至少有一边铺满 view 且整体居中`() {
        val f = VideoFit.fit(2680, 1085, 852, 480)!!
        val (cw, ch) = contentOf(2680, 1085, f)
        val fillW = kotlin.math.abs(cw - 2680) < 1f
        val fillH = kotlin.math.abs(ch - 1085) < 1f
        assertTrue("宽度或高度应有一边铺满（否则是多余留白）", fillW || fillH)
        // 居中：两侧留白相等
        val leftGap = f.dx
        val rightGap = 2680 - (f.dx + cw)
        assertEquals("左右留白应对称", leftGap, rightGap, 1e-3f)
    }

    @Test
    fun `同比例时铺满且不缩放`() {
        val f = VideoFit.fit(2480, 1116, 2480, 1116)!!
        assertEquals(1f, f.scaleX, 1e-4f)
        assertEquals(1f, f.scaleY, 1e-4f)
        assertEquals(0f, f.dx, 1e-4f)
        assertEquals(0f, f.dy, 1e-4f)
    }

    @Test
    fun `旧实现会裁切 而新实现不会`() {
        // 直观对比：旧公式 scale = min(view/video)，在 fitXY 语义下内容 = scale*view
        val vw = 2680; val vh = 1085; val sw = 852; val sh = 480
        val legacy = minOf(vw.toFloat() / sw, vh.toFloat() / sh)
        val legacyW = legacy * vw
        val legacyH = legacy * vh
        assertTrue("旧实现确实会放大超出 view（这是被修的 bug）", legacyW > vw || legacyH > vh)

        val f = VideoFit.fit(vw, vh, sw, sh)!!
        assertTrue("新实现必须完整", fitsInside(vw, vh, f))
    }

    @Test
    fun `非正尺寸返回 null 由调用方回退`() {
        assertNull(VideoFit.fit(0, 100, 852, 480))
        assertNull(VideoFit.fit(100, 0, 852, 480))
        assertNull(VideoFit.fit(2680, 1085, 0, 480))
        assertNull(VideoFit.fit(2680, 1085, 852, 0))
        assertNull(VideoFit.fit(-1, -1, -1, -1))
    }
}
