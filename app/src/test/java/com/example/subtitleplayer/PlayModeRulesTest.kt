package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PlayModeRules 单测（3.1.2）。
 *
 * 核心目标是钉死一个**不报错的漏改**：加新模式时若 `cyclePlayMode` 里还用 `% 4`，
 * 新模式永远切不到，看起来就像「新功能没做」。
 */
class PlayModeRulesTest {

    @Test
    fun `五种模式依次循环且能回到起点`() {
        val seen = mutableListOf<Int>()
        var mode = PlayModeRules.MODE_SEQUENCE
        repeat(PlayModeRules.MODE_COUNT) {
            seen.add(mode)
            mode = PlayModeRules.nextPlayMode(mode)
        }
        assertEquals(PlayModeRules.MODE_COUNT, seen.toSet().size)
        assertEquals("转一圈必须回到起点", PlayModeRules.MODE_SEQUENCE, mode)
    }

    @Test
    fun `第五种模式确实在循环里`() {
        // 这条就是防 `% 4` 的：MODE_SINGLE_STOP 必须能从「顺序播放（播完停止）」切到
        assertEquals(
            PlayModeRules.MODE_SINGLE_STOP,
            PlayModeRules.nextPlayMode(PlayModeRules.MODE_SEQUENCE_STOP)
        )
        // 且它不是循环的终点 —— 还能回到顺序播放
        assertEquals(
            PlayModeRules.MODE_SEQUENCE,
            PlayModeRules.nextPlayMode(PlayModeRules.MODE_SINGLE_STOP)
        )
    }

    @Test
    fun `五种模式互不相同`() {
        val all = listOf(
            PlayModeRules.MODE_SEQUENCE,
            PlayModeRules.MODE_SHUFFLE,
            PlayModeRules.MODE_REPEAT_ONE,
            PlayModeRules.MODE_SEQUENCE_STOP,
            PlayModeRules.MODE_SINGLE_STOP
        )
        assertEquals(all.size, all.toSet().size)
        assertTrue("MODE_COUNT 必须覆盖全部模式", all.all { it < PlayModeRules.MODE_COUNT })
    }

    @Test
    fun `越界值按顺序播放处理 不卡死`() {
        // 旧版本存下的怪值（负数 / 超出范围）不能让界面卡在一个切不动的模式上
        assertEquals(PlayModeRules.MODE_SEQUENCE, PlayModeRules.nextPlayMode(-1))
        assertEquals(PlayModeRules.MODE_SEQUENCE, PlayModeRules.nextPlayMode(99))
    }

    @Test
    fun `Service 常量与规则对象一致`() {
        assertEquals(PlayModeRules.MODE_SEQUENCE, MediaPlaybackService.MODE_SEQUENCE)
        assertEquals(PlayModeRules.MODE_SHUFFLE, MediaPlaybackService.MODE_SHUFFLE)
        assertEquals(PlayModeRules.MODE_REPEAT_ONE, MediaPlaybackService.MODE_REPEAT_ONE)
        assertEquals(PlayModeRules.MODE_SEQUENCE_STOP, MediaPlaybackService.MODE_SEQUENCE_STOP)
        assertEquals(PlayModeRules.MODE_SINGLE_STOP, MediaPlaybackService.MODE_SINGLE_STOP)
        assertEquals(PlayModeRules.MODE_COUNT, MediaPlaybackService.MODE_COUNT)
    }
}
