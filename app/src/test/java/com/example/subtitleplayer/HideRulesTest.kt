package com.example.subtitleplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隐藏规则测试（2.14）。
 *
 * 回归目标：删除功能「不碰手机真实文件」，实质是按名单隐藏。
 * 判定里最容易写错的是「文件夹包含子目录」与「不要误伤同名前缀」——
 * 一旦写错，后果是整片歌曲消失（父目录被误判）或删不掉（子目录判不中）。
 */
class HideRulesTest {

    // ---- folderCoveredBy：路径分段比较 ----

    @Test
    fun `隐藏父文件夹覆盖其子文件夹`() {
        assertTrue(HideRules.folderCoveredBy("A/B/C", "A/B"))
        assertTrue(HideRules.folderCoveredBy("A/B", "A/B"))
    }

    @Test
    fun `隐藏子文件夹不覆盖父文件夹`() {
        // 删子目录不该把父目录的歌一起藏掉
        assertFalse(HideRules.folderCoveredBy("A", "A/B"))
        assertFalse(HideRules.folderCoveredBy("A/B", "A/B/C"))
    }

    @Test
    fun `同层不同名不匹配`() {
        assertFalse(HideRules.folderCoveredBy("A/C", "A/B"))
    }

    @Test
    fun `按路径分段比较 不做子串匹配`() {
        // 关键：隐藏「音声」不能把「我的音声」「音声集」也藏了
        assertFalse(HideRules.folderCoveredBy("我的音声", "音声"))
        assertFalse(HideRules.folderCoveredBy("音声集", "音声"))
        assertFalse(HideRules.folderCoveredBy("音声2", "音声"))
        assertTrue(HideRules.folderCoveredBy("音声", "音声"))
        assertTrue(HideRules.folderCoveredBy("音声/子目录", "音声"))
    }

    @Test
    fun `允许一段根名前缀错位 重扫后隐藏依然生效`() {
        // 多根合并会给同名子文件夹加「根名/」前缀。用户当初隐藏的是「周杰伦」，
        // 加了新根后它变成「根A/周杰伦」——必须仍被判中，否则「删了又回来」。
        assertTrue(HideRules.folderCoveredBy("根A/周杰伦", "周杰伦"))
    }

    @Test
    fun `两段以上前缀错位不匹配`() {
        assertFalse(HideRules.folderCoveredBy("X/Y/周杰伦", "周杰伦"))
    }

    @Test
    fun `空值安全`() {
        assertFalse(HideRules.folderCoveredBy("A", ""))
        assertFalse(HideRules.folderCoveredBy("", "A"))
        assertFalse(HideRules.folderCoveredBy("A/B", "A/B/C"))
    }

    @Test
    fun `多余斜杠被归一化`() {
        assertTrue(HideRules.folderCoveredBy("/A/B/", "/A/B/"))
        assertTrue(HideRules.folderCoveredBy("A/B/", "A/B"))
    }

    // ---- isSongHidden ----

    @Test
    fun `按 uri 精确隐藏一首`() {
        assertTrue(
            HideRules.isSongHidden("u1", "Music", setOf("u1"), emptySet())
        )
        assertFalse(
            HideRules.isSongHidden("u2", "Music", setOf("u1"), emptySet())
        )
    }

    @Test
    fun `按文件夹隐藏 覆盖子目录`() {
        assertTrue(
            HideRules.isSongHidden("u9", "A/B", emptySet(), setOf("A/B"))
        )
        assertTrue(
            HideRules.isSongHidden("u9", "A/B/C", emptySet(), setOf("A/B"))
        )
        assertFalse(
            HideRules.isSongHidden("u9", "Z", emptySet(), setOf("A/B"))
        )
    }

    @Test
    fun `名单为空时不隐藏任何歌`() {
        assertFalse(HideRules.isSongHidden("u1", "Music", emptySet(), emptySet()))
    }

    @Test
    fun `空 uri 不会误判为已隐藏`() {
        // 某些 provider 拿不到 uri 时不该被名单里任何东西匹配上
        assertFalse(HideRules.isSongHidden("", "Music", setOf("u1"), emptySet()))
    }

    // ---- isPlaylistHidden ----

    @Test
    fun `歌单自身被隐藏`() {
        assertTrue(HideRules.isPlaylistHidden("A/B", setOf("A/B")))
    }

    @Test
    fun `父歌单不因子文件夹被隐藏而消失`() {
        // 删了 A/B，A 本身若也有歌，A 的歌单要留着
        assertFalse(HideRules.isPlaylistHidden("A", setOf("A/B")))
    }

    @Test
    fun `歌单名单为空时不隐藏`() {
        assertFalse(HideRules.isPlaylistHidden("A", emptySet()))
    }
}
