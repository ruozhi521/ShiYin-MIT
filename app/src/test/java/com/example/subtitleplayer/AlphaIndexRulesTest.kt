package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首字母索引纯逻辑测试（3.0.4）。
 *
 * 索引写错的后果是「点 B 跳到别处」，比不显示索引更糟，所以逐条钉住。
 */
class AlphaIndexRulesTest {

    /** 3.1：装上拼音源。索引条现在与排序共用 NameOrder 的拼音排序键。 */
    @org.junit.Before
    fun setUpPinyin() = TestPinyin.install()

    @org.junit.After
    fun tearDownPinyin() = TestPinyin.restore()


    @Test
    fun `拉丁字母取大写`() {
        assertEquals('A', AlphaIndexRules.fallbackLetterOf("abc"))
        assertEquals('Z', AlphaIndexRules.fallbackLetterOf("  zzz"))
    }

    @Test
    fun `中文与数字归到 Other`() {
        assertEquals(AlphaIndexRules.OTHER, AlphaIndexRules.fallbackLetterOf("周杰伦"))
        assertEquals(AlphaIndexRules.OTHER, AlphaIndexRules.fallbackLetterOf("2024 新歌"))
    }

    @Test
    fun `空串与 null 归到 Other 不崩`() {
        assertEquals(AlphaIndexRules.OTHER, AlphaIndexRules.fallbackLetterOf(""))
        assertEquals(AlphaIndexRules.OTHER, AlphaIndexRules.fallbackLetterOf("   "))
        assertEquals(AlphaIndexRules.OTHER, AlphaIndexRules.fallbackLetterOf(null))
    }

    @Test
    fun `索引取每个字母的首次位置`() {
        val names = listOf("Apple", "Banana", "Avocado", "Cherry")
        val index = AlphaIndexRules.buildIndex(names)
        assertEquals(listOf('A' to 0, 'B' to 1, 'C' to 3), index)
    }

    @Test
    fun `Other 桶永远排在最后`() {
        val names = listOf("周杰伦", "Apple", "2024")
        val index = AlphaIndexRules.buildIndex(names)
        assertEquals('A', index.first().first)
        assertEquals(AlphaIndexRules.OTHER, index.last().first)
        // 中文与数字同属 Other，只保留第一次出现的位置（下标 0）
        assertEquals(0, index.last().second)
    }

    @Test
    fun `字母按 A 到 Z 升序`() {
        val names = listOf("Cat", "Apple", "Banana")
        val index = AlphaIndexRules.buildIndex(names)
        assertEquals(listOf('A', 'B', 'C'), index.map { it.first })
    }

    @Test
    fun `只有一个字母时不显示索引条`() {
        assertFalse(AlphaIndexRules.hasUsefulIndex(AlphaIndexRules.buildIndex(listOf("周杰伦", "林俊杰"))))
        assertFalse(AlphaIndexRules.hasUsefulIndex(emptyList()))
    }

    @Test
    fun `两个以上字母时显示索引条`() {
        assertTrue(AlphaIndexRules.hasUsefulIndex(AlphaIndexRules.buildIndex(listOf("Apple", "Banana"))))
    }

    @Test
    fun `可注入自定义取字母规则`() {
        // 模拟 ICU：把中文也映射成拼音首字母
        val pinyin = mapOf("周杰伦" to 'Z', "林俊杰" to 'L', "Apple" to 'A')
        val index = AlphaIndexRules.buildIndex(
            listOf("周杰伦", "林俊杰", "Apple")
        ) { pinyin[it] ?: AlphaIndexRules.OTHER }
        assertEquals(listOf('A' to 2, 'L' to 1, 'Z' to 0), index)
    }

    // ---------- 拖动选中：手指 y → 第几个字母（3.0.4）----------

    @Test
    fun `手指纵坐标换算成字母下标`() {
        // 每项 100px：y=0 → 第0个，y=150 → 第1个，y=250 → 第2个
        assertEquals(0, AlphaIndexRules.indexAt(0f, 100f, 5))
        assertEquals(1, AlphaIndexRules.indexAt(150f, 100f, 5))
        assertEquals(2, AlphaIndexRules.indexAt(250f, 100f, 5))
    }

    @Test
    fun `滑到索引条上方取第一个而不是负数`() {
        assertEquals(0, AlphaIndexRules.indexAt(-80f, 100f, 5))
    }

    @Test
    fun `滑到索引条下方取最后一个而不是越界`() {
        assertEquals(4, AlphaIndexRules.indexAt(9999f, 100f, 5))
    }

    @Test
    fun `字母表为空时返回负一`() {
        assertEquals(-1, AlphaIndexRules.indexAt(50f, 100f, 0))
    }

    @Test
    fun `条目高度为零时退回第一个且不除零崩溃`() {
        assertEquals(0, AlphaIndexRules.indexAt(50f, 0f, 3))
    }

    // ---------- 与排序同源的一致性（3.1，修「定位不到」）----------

    /**
     * 端到端：乱序输入 → 先用 NameOrder 排序 → 再用同一个 letterOf 建索引，
     * 每个索引项的下标必须**正好指向该字母的第一项**。
     *
     * 这是爱发电用户那条反馈的验收标准：
     * 「滑动字母导航只会在首字符汉字的 a-z 之间滑动，排在最下面的字母首字符则定位不到」。
     * 只要这条恒成立，索引条就不可能出现「点 Z 跳到别处」。
     *
     * 3.0.8 会失败：排序用 Collator（汉字全排在字母后）、索引用 AlphabeticIndex
     * （汉字按拼音分桶），两边对同一项的位置判断相反，索引下标指向错误位置。
     */
    @Test
    fun `索引下标必须指向该字母的第一项_与排序同源`() {
        val raw = listOf(
            "周杰伦", "Apple", "安室奈美惠", "Ben", "Zero", "爱情",
            "Bob", "本兮", "Zebra", "爱"
        )
        val ordered = NameOrder.sortedByNames(raw) { it }
        val letterOf: (String) -> Char = { NameOrder.letterOf(it) }
        val index = AlphaIndexRules.buildIndex(ordered, letterOf)

        assertTrue("拼音源应产生多个桶，实际 $index", index.size >= 3)
        for ((letter, pos) in index) {
            assertEquals(
                "索引项 $letter→$pos 指向的项 `\${ordered[pos]}` 首字母不是 $letter",
                letter,
                letterOf(ordered[pos])
            )
            if (pos > 0) {
                assertTrue(
                    "{$letter} 的下标 $pos 不是该字母首次出现（前一项 `\${ordered[pos - 1]}` 同属该字母）",
                    letterOf(ordered[pos - 1]) != letter
                )
            }
        }
    }

}
