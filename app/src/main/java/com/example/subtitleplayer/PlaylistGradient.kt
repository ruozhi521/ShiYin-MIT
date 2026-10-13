package com.example.subtitleplayer

import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * 无封面歌单的渐变底色（3.0）。
 *
 * 概念图里没有封面的歌单卡片是**素雅的渐变块**（不是纯图标），弱志要求
 * 「引入更多配色，低亮度 + 渐变配色，不用特别鲜艳」。
 *
 * 设计要点：
 * - **按歌单名稳定选色**：同一歌单每次进出的颜色必须一致，否则列表滚动/重绘时
 *   颜色乱跳（用 [name.hashCode] 做种子，不用随机数）。同样的做法见
 *   [PlaylistSorter.shuffledStable] 的「稳定随机」思路。
 * - **色板是资源**（`values/colors.xml` 与 `values-night/colors.xml` 同名覆盖），
 *   所以日夜模式自动换色，这里不判断主题。
 * - **三色阶**（起/中/止）而非双色：低端屏颜色带宽窄，双色过渡容易出现色带（banding）。
 * - 只是**兜底**：有自设封面 / 有歌曲封面时仍优先用图片（见 [PlaylistGridAdapter]）。
 */
object PlaylistGradient {

    /**
     * 八个色板的资源 id，按 `pl_gradN_a/b/c` 三阶组织。
     * 顺序即哈希取模的顺序——**调整顺序会让所有歌单换色**，非必要不要动。
     */
    private val PALETTES: List<Triple<Int, Int, Int>> = listOf(
        Triple(R.color.pl_grad1_a, R.color.pl_grad1_b, R.color.pl_grad1_c), // 蓝灰
        Triple(R.color.pl_grad2_a, R.color.pl_grad2_b, R.color.pl_grad2_c), // 粉
        Triple(R.color.pl_grad3_a, R.color.pl_grad3_b, R.color.pl_grad3_c), // 淡紫
        Triple(R.color.pl_grad4_a, R.color.pl_grad4_b, R.color.pl_grad4_c), // 薄荷
        Triple(R.color.pl_grad5_a, R.color.pl_grad5_b, R.color.pl_grad5_c), // 燕麦
        Triple(R.color.pl_grad6_a, R.color.pl_grad6_b, R.color.pl_grad6_c), // 雾蓝
        Triple(R.color.pl_grad7_a, R.color.pl_grad7_b, R.color.pl_grad7_c), // 陶土
        Triple(R.color.pl_grad8_a, R.color.pl_grad8_b, R.color.pl_grad8_c)  // 浅橄榄
    )

    fun paletteCount(): Int = PALETTES.size

    /** 歌单名 → 色板下标（稳定）。 */
    fun indexFor(name: String): Int {
        val h = name.hashCode()
        // hashCode 可能为负：先抹掉符号位再取模，避免得到负下标
        return (h and 0x7FFFFFFF) % PALETTES.size
    }

    /**
     * 通用渐变底（3.0）：按任意稳定 key 选色板。
     *
     * 歌单网格用**歌单名**、发现页卡片用**歌曲 uri** —— 共用同一套色板，
     * 保证「全 app 所有无封面占位色」观感统一。
     *
     * 用 hashCode 而不是随机数：同一首歌/歌单每次进出的颜色必须一致，
     * 否则列表滚动、重绘（RecyclerView 复用 Holder）时颜色会乱跳。
     *
     * @param cornerRadiusDp 圆角半径（dp）。
     */
    fun drawable(context: android.content.Context, key: String, cornerRadiusDp: Float): GradientDrawable {
        val t = PALETTES[indexFor(key)]
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                context.getColor(t.first),
                context.getColor(t.second),
                context.getColor(t.third)
            )
        ).apply {
            cornerRadius = cornerRadiusDp * context.resources.displayMetrics.density
        }
    }

    /**
     * 圆形渐变底（3.0）：发现页「更多随机推荐」长条卡的封面是**圆的**。
     *
     * 用 `shape = OVAL` 而不是「超大 cornerRadius 的矩形」——
     * oval 的 outline 是真正的圆，低 dpi 屏边缘更稳（radius 版依赖插值，会有棱角）。
     */
    fun circleDrawable(context: android.content.Context, key: String): GradientDrawable {
        val t = PALETTES[indexFor(key)]
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                context.getColor(t.first),
                context.getColor(t.second),
                context.getColor(t.third)
            )
        ).apply {
            shape = GradientDrawable.OVAL
        }
    }

    /**
     * 生成该歌单的渐变 drawable（左上 → 右下 45°）。
     *
     * `context` 用于解析颜色资源（日夜模式不同取值）。
     */
    fun drawableFor(context: android.content.Context, name: String): GradientDrawable =
        drawable(context, name, 20f)

    /** 便于单测：某下标色板的三个色值（已解析）。 */
    fun colorsOf(context: android.content.Context, index: Int): IntArray {
        val t = PALETTES[index % PALETTES.size]
        return intArrayOf(context.getColor(t.first), context.getColor(t.second), context.getColor(t.third))
    }

    /** 便于单测：判断是否真的是渐变（三色不全相同）。 */
    fun isGradient(colors: IntArray): Boolean =
        colors.size >= 2 && colors.any { it != colors[0] }

    /** 便于单测：颜色是否偏亮（用于校验"低亮度"约束是否被破坏）。 */
    fun luminance(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
}
