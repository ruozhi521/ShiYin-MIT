package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面命名规则测试（3.1.1）。
 *
 * 用例文件名全部取自音声（ASMR）素材的真实命名习惯 —— B站粉丝原话：
 * 「检测封面时可以读取 rj 号文件夹下美的（封面）（有logo）等字样的图片吗」。
 */
class CoverPickRulesTest {

    @Test
    fun `中文封面字样归为明确封面`() {
        assertEquals(0, CoverPickRules.tierOf("封面.png"))
        assertEquals(0, CoverPickRules.tierOf("（封面）.jpg"))
        assertEquals(0, CoverPickRules.tierOf("表紙.jpg"))
        assertEquals(0, CoverPickRules.tierOf("ジャケット.png"))
    }

    @Test
    fun `英文常见封面名分档正确`() {
        assertEquals(0, CoverPickRules.tierOf("cover.jpg"))
        assertEquals(0, CoverPickRules.tierOf("Cover_Front.png"))
        // folder 归第 1 档（比 cover 弱），单独确认一下预期
        assertEquals(1, CoverPickRules.tierOf("folder.jpg"))
    }

    @Test
    fun `DLsite 包里的パッケージ归为明确封面`() {
        assertEquals(0, CoverPickRules.tierOf("パッケージ.png"))
        assertEquals(0, CoverPickRules.tierOf("RJ01234567_パッケージ.jpg"))
    }

    @Test
    fun `RJ 号开头的图归为明确封面`() {
        assertEquals(0, CoverPickRules.tierOf("RJ01234567.jpg"))
        assertEquals(0, CoverPickRules.tierOf("rj123456_img_main.png"))
        assertEquals(0, CoverPickRules.tierOf("RJ3012345_表紙.jpg"))
    }

    @Test
    fun `有logo与主图归为第二档`() {
        // 名字里同时带「封面」时按第 0 档算 —— 那是最强的信号
        assertEquals(0, CoverPickRules.tierOf("（封面）（有logo）.png"))
        assertEquals(1, CoverPickRules.tierOf("有logo.jpg"))
        assertEquals(1, CoverPickRules.tierOf("主图.png"))
        assertEquals(1, CoverPickRules.tierOf("サムネイル.jpg"))
    }

    @Test
    fun `看不出用途的图排最后`() {
        assertEquals(2, CoverPickRules.tierOf("001.jpg"))
        assertEquals(2, CoverPickRules.tierOf("サンプル1.jpg"))
        assertEquals(2, CoverPickRules.tierOf("IMG_20240101_123456.jpg"))
        assertFalse(CoverPickRules.isHinted("001.jpg"))
        assertTrue(CoverPickRules.isHinted("封面.png"))
    }

    @Test
    fun `明确封面优先于有logo与杂图`() {
        // 这是本次修复的核心：原来这三张都「未命中关键词」，只能按字典序取 001.jpg
        val best = CoverPickRules.pickBest(
            listOf("001.jpg", "（有logo）.png", "封面.png")
        )
        assertEquals("封面.png", best)
    }

    @Test
    fun `无明确封面时有logo优先于杂图`() {
        val best = CoverPickRules.pickBest(listOf("001.jpg", "002.jpg", "有logo.png"))
        assertEquals("有logo.png", best)
    }

    @Test
    fun `都是杂图时按名称稳定挑选`() {
        val list = listOf("002.jpg", "001.jpg", "003.jpg")
        assertEquals("001.jpg", CoverPickRules.pickBest(list))
        // 顺序打乱结果不变 —— 避免「同一张专辑有时显示这张有时那张」
        assertEquals("001.jpg", CoverPickRules.pickBest(list.reversed()))
    }

    @Test
    fun `RJ 号图胜过试听截图`() {
        val best = CoverPickRules.pickBest(
            listOf("サンプル1.jpg", "サンプル2.jpg", "RJ01234567_img_main.jpg")
        )
        assertEquals("RJ01234567_img_main.jpg", best)
    }

    @Test
    fun `空列表返回null`() {
        assertEquals(null, CoverPickRules.pickBest(emptyList()))
    }

    @Test
    fun `大小写不影响判定`() {
        assertEquals(0, CoverPickRules.tierOf("COVER.JPG"))
        assertEquals(0, CoverPickRules.tierOf("Cover.Png"))
        assertEquals(CoverPickRules.tierOf("cover.jpg"), CoverPickRules.tierOf("COVER.JPG"))
    }
}
