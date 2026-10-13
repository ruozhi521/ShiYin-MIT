package com.example.subtitleplayer

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 网格横向间距（3.0）。
 *
 * **为什么需要它**：音乐库页与歌单页的歌单网格是 `GridLayoutManager(this, 2)`，
 * 而 `item_playlist_grid.xml` 里**只有垂直方向的 margin**，没有任何水平间距 —— 
 * 两列卡片左右**完全贴死**（实机 dump 实测列间距 = 0px，卡1 `x 53~540`、卡2 `x 540~1027`）。
 * 弱志反馈「app 内截图太紧凑了，适当分开一些比较好」（附了对照图）。
 *
 * 目标稿的比例：卡宽 126、列间距 17 → `17/126 ≈ 13.5%`。
 * 设备上卡宽约 173dp，同比例约 24dp，故默认取 [DEFAULT_SPACING_DP] = 24。
 *
 * **为什么用均摊公式而不是「每项左右各半」**：
 * 「左右各半」会让**最外侧**也缩进半个间距，卡片不再与页面 padding 对齐（左右边缘内缩）。
 * 这里用 `left = c*S/n`、`right = S-(c+1)*S/n`：
 * - 第 0 列：left=0（紧贴页面 padding，与现状一致）、right=S/2
 * - 最后一列：left=S/2、right=0
 * - 相邻两项相加 = S/2 + S/2 = **正好 S**；左右外侧 = 0
 * 即「间距全在卡片之间，外侧不缩进」。
 *
 * **垂直方向默认 0**：item 自身已有 `layout_marginBottom`，再叠加会双重累加。
 * 需要时显式传 [verticalPx]。
 */
class GridSpacingDecoration(
    private val spanCount: Int,
    private val horizontalPx: Int,
    private val verticalPx: Int = 0
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        outRect.setEmpty()
        // ⚠️ 只在网格布局下生效。
        // recyclerPlaylists 会被复用：网格模式用 GridLayoutManager，**树形模式换成
        // LinearLayoutManager**（`applyLibLayout` 里切的）。若是列表还按列算 offset，
        // 每一项都会拿到左右不等的内缩，树形目录看起来会歪。故非网格直接不加。
        if (parent.layoutManager !is GridLayoutManager) return
        val pos = parent.getChildAdapterPosition(view)
        if (pos == RecyclerView.NO_POSITION) return
        // 用 spanIndex 而不是 pos % spanCount：后者在跨列条目上会算错（本网格虽都是单列，
        // 但用 spanIndex 更稳妥，且不受 header 类条目影响）
        val lp = view.layoutParams
        val column = if (lp is GridLayoutManager.LayoutParams) {
            lp.spanIndex
        } else {
            pos % spanCount
        }
        outRect.left = column * horizontalPx / spanCount
        outRect.right = horizontalPx - (column + 1) * horizontalPx / spanCount
        outRect.bottom = verticalPx
    }

    companion object {
        /** 目标稿比例（17/126 ≈ 13.5%）换算到设备卡宽所得的列间距。 */
        const val DEFAULT_SPACING_DP = 24
    }
}
