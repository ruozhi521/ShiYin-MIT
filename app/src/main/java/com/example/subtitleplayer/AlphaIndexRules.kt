package com.example.subtitleplayer

/**
 * 首字母索引的纯逻辑（3.0.4）。
 *
 * 需求来源（粉丝）：搜索栏下方那一行自己用不到，**右侧加个首字母导航会实用得多**。
 *
 * 为什么不在这里直接调 `android.icu.text.AlphabeticIndex`：
 * 那个类只存在于 Android 运行时（`android.icu.*`），纯 JVM 单测里没有。
 * 所以把「取字母」抽象成 [letterOf] 参数由调用方注入 ——
 * 运行时注入 ICU 版（中文按拼音分桶），单测注入固定规则，两边都能验。
 */
object AlphaIndexRules {

    /** 既不是字母也不是数字时的归并桶（原实现用 `#`，与常见通讯录一致）。 */
    const val OTHER = '#'

    /**
     * 兜底取字母：拉丁字母取大写、阿拉伯数字归 [OTHER]、其余（含中文）也归 [OTHER]。
     *
     * 只在 ICU 不可用时使用。中文全落进 `#` 意味着索引条退化成一个桶，
     * 调用方据此可以决定干脆不显示索引条（见 [hasUsefulIndex]）。
     */
    fun fallbackLetterOf(name: String?): Char {
        val first = name?.trim()?.firstOrNull() ?: return OTHER
        val upper = first.uppercaseChar()
        return if (upper in 'A'..'Z') upper else OTHER
    }

    /**
     * 由「名字列表」算出索引表：字母 → 该字母的首个位置（升序、去重）。
     *
     * 返回的字母按 A→Z 排、[OTHER] 永远放最后（与通讯录习惯一致）。
     * 位置是**原列表下标**，调用方直接拿去 `scrollToPosition`。
     */
    fun buildIndex(names: List<String>, letterOf: (String) -> Char = ::fallbackLetterOf): List<Pair<Char, Int>> {
        val firstAt = LinkedHashMap<Char, Int>()
        names.forEachIndexed { i, name ->
            val letter = letterOf(name)
            if (!firstAt.containsKey(letter)) firstAt[letter] = i
        }
        val letters = firstAt.keys.sortedWith(
            compareBy({ it == OTHER }, { it })
        )
        return letters.map { it to firstAt.getValue(it) }
    }

    /**
     * 这个索引值不值得显示成一条竖排索引条。
     *
     * 只有 1 个字母时（例如全是中文且没有 ICU）索引条没有导航价值，
     * 还会白占列表右侧空间，所以宁可不显示。
     */
    fun hasUsefulIndex(index: List<Pair<Char, Int>>): Boolean = index.size >= 2
}
