package com.example.subtitleplayer

/**
 * 视频画面适配的几何计算（2.13）。
 *
 * 为什么单独抽成纯函数：`TextureView.setTransform` 的矩阵作用在 **view 空间**，
 * 而 TextureView 默认把画面**拉伸铺满**自己的边界（fitXY）。
 * 因此缩放比必须是「目标尺寸 / **view 尺寸**」，**不能**写成「view 像素 / 视频像素」——
 * 后者等于把「已经铺满 view 的内容」再缩放一次：view 比视频大时（手机普遍如此）scale > 1，
 * 画面被放大出可视区、四周被裁掉，表现为「画面显示不完整 / 只剩局部」。
 *
 * 抽出来还为了让单元测试能锁死这个几何（纯计算，不依赖 Android 运行时）。
 */
object VideoFit {

    /** 适配结果：把内容缩放 (scaleX, scaleY) 后平移 (dx, dy) 使其居中。 */
    data class Fit(val scaleX: Float, val scaleY: Float, val dx: Float, val dy: Float)

    /**
     * 求「把 `srcW×srcH` 的画面按比例**完整放进** `viewW×viewH`」所需的变换。
     * 宁可留黑边，绝不裁切。任一尺寸非法（<= 0）时返回 null，由调用方回退。
     */
    fun fit(viewW: Int, viewH: Int, srcW: Int, srcH: Int): Fit? {
        if (viewW <= 0 || viewH <= 0 || srcW <= 0 || srcH <= 0) return null
        val ratio = srcW.toFloat() / srcH
        // 先按 view 宽度铺满，算出对应高度；若超高说明应改为「高度铺满」
        var newW = viewW.toFloat()
        var newH = newW / ratio
        if (newH > viewH) {
            newH = viewH.toFloat()
            newW = newH * ratio
        }
        return Fit(
            scaleX = newW / viewW,
            scaleY = newH / viewH,
            dx = (viewW - newW) / 2f,
            dy = (viewH - newH) / 2f
        )
    }

    /**
     * 适配后画面在 view 里的实际显示尺寸。
     * 依据 fitXY 语义：未施加矩阵时内容 == view 尺寸，施加 (scaleX, scaleY) 后为两者相乘。
     */
    fun contentSize(viewW: Int, viewH: Int, f: Fit): Pair<Float, Float> =
        f.scaleX * viewW to f.scaleY * viewH
}
