package com.example.subtitleplayer

/**
 * 玻璃背景的纯计算模糊（3.1）。
 *
 * 从 [DockGlassDrawable] 抽出来单独成文件，唯一目的是**可测**：
 * 类里那段逻辑只依赖 `IntArray`，不碰 Bitmap / Canvas / Context，
 * 于是能在 JVM 单测里直接跑（见 DockGlassBlurTest）。
 *
 * 为什么这件事值得专门做一次：box blur 的滑动窗口极易写错 —— 差一、
 * 忘减旧值、边界 clamp 漏掉，症状都是「图偏了一点」「边缘发黑」这种
 * 肉眼看不出、但满屏都是的问题。而真机截图验证这条路当时不通，
 * 所以只能靠单测把数学钉死。
 *
 * ## 为什么是三级 box blur
 *
 * 一次 box blur 是「方形平摊」，两三次叠加后逼近高斯（中心权重大、边缘衰减），
 * 这就是经典的三重 box blur 近似。相比直接算高斯核，复杂度从 O(r²) 降到 O(r)，
 * 且可以分离成横向+纵向两趟。
 */
object DockGlassBlur {

    /** 半径上限。再大在降采样图上会糊成纯色块，没有视觉收益。 */
    const val MAX_RADIUS = 40

    /** 叠加次数。3 次是「近似高斯」的常用取值。 */
    private const val PASSES = 3

    /**
     * 就地模糊 `pix`（ARGB_8888 排布，行优先）。
     *
     * @param radius 模糊半径（像素）。<= 0 时不动，调用方不必自己判断。
     */
    fun blur(pix: IntArray, w: Int, h: Int, radius: Int) {
        if (radius <= 0 || w <= 0 || h <= 0) return
        if (pix.size < w * h) return
        val r = radius.coerceAtMost(MAX_RADIUS)
        // 复用同一块临时缓冲：三次 pass 各 new 一次会白白加重 GC
        val tmp = IntArray(w * h)
        repeat(PASSES) { boxPass(pix, tmp, w, h, r) }
    }

    /**
     * 一趟可分离 box blur（先横后竖）。
     *
     * 用**滑动窗口**累加：每格不是重新读 2r+1 个像素，而是「加上新进来的、
     * 减去滑出去的」，单格成本恒定，与 r 无关。
     *
     * 边界处理用 clamp（越界索引取最近的边界像素）而不是当作透明：
     * 透明会让边缘逐渐变黑/变淡，一圈脏边比模糊本身更显眼。
     */
    private fun boxPass(pix: IntArray, tmp: IntArray, w: Int, h: Int, r: Int) {
        val n = r * 2 + 1

        // ---- 横向：pix -> tmp ----
        for (y in 0 until h) {
            val row = y * w
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val c = pix[row + i.coerceIn(0, w - 1)]
                sr += (c shr 16) and 0xFF
                sg += (c shr 8) and 0xFF
                sb += c and 0xFF
            }
            for (x in 0 until w) {
                tmp[row + x] = pack(sr / n, sg / n, sb / n)
                val leaving = pix[row + (x - r).coerceIn(0, w - 1)]
                val entering = pix[row + (x + r + 1).coerceIn(0, w - 1)]
                sr += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                sg += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                sb += (entering and 0xFF) - (leaving and 0xFF)
            }
        }

        // ---- 纵向：tmp -> pix ----
        for (x in 0 until w) {
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val c = tmp[i.coerceIn(0, h - 1) * w + x]
                sr += (c shr 16) and 0xFF
                sg += (c shr 8) and 0xFF
                sb += c and 0xFF
            }
            for (y in 0 until h) {
                pix[y * w + x] = pack(sr / n, sg / n, sb / n)
                val leaving = tmp[(y - r).coerceIn(0, h - 1) * w + x]
                val entering = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sr += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                sg += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                sb += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }

    /** 组装成不透明 ARGB。模糊不参与 alpha 通道，玻璃的透明度由白色 tint 控制。 */
    private fun pack(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
