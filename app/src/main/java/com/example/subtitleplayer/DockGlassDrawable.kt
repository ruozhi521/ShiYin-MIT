package com.example.subtitleplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View

/**
 * 底部浮层的「液态玻璃」背景（3.1）。
 *
 * ## 为什么不能直接用 `View.setRenderEffect(createBlurEffect(...))`
 *
 * 它模糊的是**整个视图的渲染结果，包括视图自己的文字和图标**。套在导航条上
 * 就是五个 tab 的字全糊掉，那是 bug 不是效果。而 `Window.setBackgroundBlurRadius`
 * 模糊的是**窗口之后的内容**（桌面），不是 App 内部的页面 —— 在这里没用。
 *
 * ## 可行做法
 *
 * 把下层（`pageContainer`）画进一张 Bitmap → 模糊 → 当作自己的背景画出来：
 *
 * 1. **捕获**：`src.draw(canvas)`。浮层与 pageContainer 是 FrameLayout 下的
 *    **兄弟节点**，`src.draw()` 只画页面子树，不会把自己画进去。
 * 2. **降采样**：先按 [DOWNSCALE] 缩小再画。像素量降到 1/36，模糊成本随之下降；
 *    放大回去时双线性插值本身又带一层柔化，观感反而更像毛玻璃。
 * 3. **只光栅化需要的那一块**：`translate(-left, -top)` 把浮层位置挪到画布原点，
 *    画布尺寸就是浮层尺寸 —— 成本与浮层大小成正比，而不是与整页成正比。
 * 4. **模糊**：三级 box blur 近似高斯（[DockGlassBlur]）。
 * 5. **叠加**：主题底色按「玻璃浓度」盖上，再用 DST_IN 遮罩切圆角
 *    （用遮罩而不是 `clipPath`，因为 `clipPath` 不抗锯齿，边缘会起毛刺）。
 *
 * ## 刷新率（3.1 第二次返工的重点）
 *
 * 上一版把捕获写在 [draw] 里，靠外部 250ms 定时 `invalidate()` 驱动 —— 等于 4fps。
 * 界面是 120Hz，滑动时玻璃里的内容明显滞后，「跟高刷新率界面对比特别别扭」
 * （弱志反馈）。这是设计错误，不是参数没调好。
 *
 * 现在拆成两步：
 * - 调用方在 **VSYNC 每帧**调 [updateForFrame] 更新快照（Choreographer 驱动）；
 * - [draw] 只负责把已有快照贴上去，很便宜。
 *
 * 这样玻璃与页面同帧，滑动时内容是真的跟着动的。
 *
 * ## 成本控制
 *
 * 每帧「遍历页面子树 + 降采样重绘 + 三级模糊」在慢机器上可能吃掉整个帧预算。
 * 所以 [updateForFrame] 会看上一帧的实际耗时：超预算就**跳过这一帧**（隔帧更新）。
 * 少更新一帧几乎看不出来，但把主线程卡住是明显能感觉到的 —— 宁可玻璃慢半拍。
 */
class DockGlassDrawable(
    private val host: View,
    private val source: () -> View?,
    private val alphaPercent: () -> Int,
    private val blurPx: () -> Int,
    private val cornerDp: Float,
    private val tintColor: () -> Int,
    private val strokeColor: Int = 0x66FFFFFF
) : Drawable() {

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = host.resources.displayMetrics.density
        color = strokeColor
    }
    /** 复用的 Canvas：每帧 new 一个会持续制造垃圾，滚动时累积成卡顿。 */
    private val workCanvas = Canvas()

    /** 当前用于显示的（已模糊的）快照。 */
    private var snapshot: Bitmap? = null
    /** Drawable 自身的 alpha（0-255）。必须单独存：tint 的 color 每帧重设会覆盖 paint.alpha。 */
    private var drawableAlpha = 255
    /** 上一次捕获+模糊的总耗时（毫秒），用于超预算时跳帧。 */
    private var lastCostMs = 0L

    /**
     * 更新快照。**由调用方在每一帧开头调用**（见 MainActivity 的 Choreographer 循环）。
     *
     * @param frameBudgetMs 允许占用的时间。上一次超过它就先跳过这一帧。
     * @return 快照是否真的更新了。只有 true 才需要 invalidate ——
     *         否则每帧无条件重绘两个浮层，等于用「一直重绘」换「看起来流畅」，
     *         那是把开销从玻璃转嫁到整个界面，反而更糟。
     */
    fun updateForFrame(frameBudgetMs: Long = 6L): Boolean {
        val b = bounds
        if (b.isEmpty) return false
        if (lastCostMs > frameBudgetMs) {
            // 上一帧太贵：本帧跳过捕获。清零让下一帧重新试一次，
            // 否则一旦某帧慢了就会永久停在「一直跳」的状态。
            lastCostMs = 0
            return false
        }
        return capture(b)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val r = RectF(0f, 0f, w, h)
        val radius = cornerDp * host.resources.displayMetrics.density

        // 第一帧（还没被 updateForFrame 喂过）兜底抓一次，避免开局是一块纯色
        if (snapshot == null) capture(b)

        val bmp = snapshot
        val layer = canvas.saveLayer(r, null)
        if (bmp != null && !bmp.isRecycled) {
            canvas.drawBitmap(bmp, null, r, bitmapPaint)
        } else {
            // 兜底：捕获失败（页面还没布局 / 源不可见）时至少给一层不透明底色，
            // 否则浮层全透明，导航文字直接压在下层内容上，看不清。
            tintPaint.color = tintColor() or (0xFF shl 24)
            canvas.drawRect(r, tintPaint)
        }
        // 浓度叠加：数值越高越不透明，「透过去」越少。
        // ⚠️ 底色跟随主题（日间 surface 白、夜间深灰），不能写死白色 ——
        // 夜间翻成白玻璃会发灰，与 #101014 的页面完全脱节。
        val baseA = alphaPercent().coerceIn(0, 100) * 255 / 100
        tintPaint.color = (tintColor() and 0x00FFFFFF) or
            ((baseA * drawableAlpha / 255) shl 24)
        canvas.drawRect(r, tintPaint)
        // 圆角遮罩（DST_IN：遮罩 alpha 为 0 的地方被抠掉）
        canvas.drawRoundRect(r, radius, radius, maskPaint)
        canvas.restoreToCount(layer)

        // 1dp 高光描边：玻璃的「边」靠它读出来，否则模糊底与页面糊成一片
        canvas.drawRoundRect(r, radius, radius, strokePaint)
    }

    /**
     * 捕获下层区域、模糊，存进 [snapshot]。失败时保留上一张（比闪成纯白好）。
     *
     * @return 是否成功更新了快照。调用方据此决定要不要 invalidate。
     */
    private fun capture(b: Rect): Boolean {
        val src = source() ?: return false
        if (src.width <= 0 || src.height <= 0 || !src.isShown) return false

        // 浮层相对捕获源的位置（两者坐标系不同，必须用窗口坐标相减换算）
        val hostLoc = IntArray(2).also { host.getLocationInWindow(it) }
        val srcLoc = IntArray(2).also { src.getLocationInWindow(it) }
        val left = (hostLoc[0] - srcLoc[0]).toFloat()
        val top = (hostLoc[1] - srcLoc[1]).toFloat()

        val bw = (b.width() / DOWNSCALE).coerceAtLeast(1)
        val bh = (b.height() / DOWNSCALE).coerceAtLeast(1)

        // 尺寸不变就复用同一张 Bitmap：每帧新建会持续制造 GC 压力
        var bmp = snapshot
        if (bmp == null || bmp.isRecycled || bmp.width != bw || bmp.height != bh) {
            bmp = try {
                Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            } catch (e: Throwable) {
                PlaybackLog.log("dock glass alloc failed: ${e.message}")
                return false
            }
            snapshot?.recycle()
            snapshot = bmp
        }

        val t0 = System.currentTimeMillis()
        try {
            workCanvas.setBitmap(bmp)
            // Canvas 状态在 setBitmap 后不可预期，显式 save/restore 兜住
            val sc = workCanvas.save()
            // 先铺页面底色：pageContainer 自身没有 background（底色在根 FrameLayout 上），
            // 不铺的话透明区域靠后面的 tint 补，会偏白、与真实页面有色差。
            workCanvas.drawColor(host.context.getColor(R.color.bg))
            workCanvas.scale(1f / DOWNSCALE, 1f / DOWNSCALE)
            workCanvas.translate(-left, -top)
            src.draw(workCanvas)
            workCanvas.restoreToCount(sc)
        } catch (e: Throwable) {
            // 视频页有 SurfaceView，或某个 Drawable 异常时 draw 可能抛；
            // 玻璃只是装饰，绝不能因此让界面崩掉。
            PlaybackLog.log("dock glass capture failed: ${e.message}")
            return false
        }

        val radius = (blurPx() / DOWNSCALE).coerceIn(0, 40)
        if (radius > 0) blurInPlace(bmp, radius)
        lastCostMs = System.currentTimeMillis() - t0
        return true
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        bitmapPaint.alpha = drawableAlpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bitmapPaint.colorFilter = colorFilter
    }

    /**
     * 提供圆角 outline。
     *
     * ⚠️ 漏了会出三个视觉 bug：
     * 1. **阴影变直角**：`View.elevation` 的阴影形状取自 background 的 outline，
     *    没有 outline 就是矩形阴影，配在圆角浮层下会露出四个尖角。
     * 2. **波纹溢出圆角**：`clipToOutline = true` 依赖同一个 outline 做裁切。
     * 3. **只在玻璃模式下出现**：实心模式用 GradientDrawable 自带 outline，
     *    所以这类「只有开某个开关才犯」的 bug 最难发现。
     */
    override fun getOutline(outline: android.graphics.Outline) {
        val b = bounds
        if (b.isEmpty) {
            outline.setEmpty()
            return
        }
        outline.setRoundRect(b, cornerDp * host.resources.displayMetrics.density)
    }

    @Deprecated("Deprecated in Drawable, still required by the abstract API")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /**
         * 降采样倍数。
         *
         * 从 4 提到 6：每帧都要重算，像素量再降 2.25 倍（1/36）。
         * 玻璃本来就是糊的，放大回去的柔化几乎看不出差别，但换来的是
         * 每帧成本大降 —— 这是「刷新率跟得上」的前提。
         */
        private const val DOWNSCALE = 6

        /** 就地模糊：取像素 → 交给纯函数算 → 写回，省掉一次 Bitmap 分配。 */
        private fun blurInPlace(bmp: Bitmap, radius: Int) {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return
            val pix = IntArray(w * h)
            bmp.getPixels(pix, 0, w, 0, 0, w, h)
            DockGlassBlur.blur(pix, w, h, radius)
            bmp.setPixels(pix, 0, w, 0, 0, w, h)
        }
    }
}
