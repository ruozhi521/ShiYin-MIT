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
}
