package com.example.subtitleplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底部迷你播放条显隐判定测试（2.13）。
 *
 * 回归目标：视频页曾漏判导致迷你条冒出来，与视频页自己的进度条叠成双进度条
 * （粉丝反馈的回归 bug）。根因是同一个判定在两处各写了一份、只改了一处。
 *
 * 纯 JVM 即可：Page 是顶层纯 Kotlin 枚举，MiniPlayerRules 也不依赖 Android。
 */
class MiniPlayerRulesTest {

    @Test
    fun `视频页不显示迷你条（本次修复的核心回归点）`() {
        assertFalse(
            "视频页有完整播放控件，再挂迷你条会出现双进度条",
            MiniPlayerRules.shouldShow(hasSong = true, page = Page.VIDEO)
        )
        // 注意：迷你条显隐不应取决于「是否从视频列表进入」，只看当前页面
        assertFalse(MiniPlayerRules.shouldShow(hasSong = true, page = Page.VIDEO))
    }

    @Test
    fun `播放页与歌词页不显示迷你条`() {
        assertFalse(MiniPlayerRules.shouldShow(true, Page.PLAYER))
        assertFalse(MiniPlayerRules.shouldShow(true, Page.LYRICS))
    }

    @Test
    fun `内容页显示迷你条`() {
        for (p in listOf(Page.DISCOVER, Page.LIBRARY, Page.PLAYLIST, Page.SEARCH, Page.FAVORITES, Page.VIDEOS)) {
            assertTrue("$p 应显示迷你条", MiniPlayerRules.shouldShow(true, p))
        }
    }

    @Test
    fun `没有歌曲时任何页面都不显示`() {
        for (p in Page.values()) {
            assertFalse("无歌曲时 $p 不该显示迷你条", MiniPlayerRules.shouldShow(false, p))
        }
    }

    @Test
    fun `全屏页面集合恰好是 播放页 歌词页 视频页`() {
        // 新增全屏页面时本测试会失败，提醒同步更新 MiniPlayerRules —— 防止再次漏判
        val noMini = Page.values().filter { !MiniPlayerRules.shouldShow(true, it) }
        assertTrue(
            "不显示迷你条的页面应恰好是 PLAYER/LYRICS/VIDEO，实际=$noMini",
            noMini.toSet() == setOf(Page.PLAYER, Page.LYRICS, Page.VIDEO)
        )
    }
}
