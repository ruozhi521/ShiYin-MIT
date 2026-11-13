package com.example.subtitleplayer

import android.media.MediaPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词点击跳转精度规则（3.0.8）单测。
 *
 * 背景：充电用户反馈点歌词跳转「时间轴定位不准」，只有部分音频有问题；
 * iQOO 自带播放器同样不准，salt player 正常 —— 指向引擎的 seek 语义，
 * 不是歌词文件。两层修法都在 [SeekPrecisionRules] 里，这里逐条钉住。
 */
class SeekPrecisionRulesTest {

    // ---------- 落点精度：必须用 SEEK_CLOSEST ----------

    @Test
    fun `精确模式用 SEEK_CLOSEST`() {
        assertEquals(MediaPlayer.SEEK_CLOSEST, SeekPrecisionRules.mediaPlayerMode(true))
    }

    @Test
    fun `非精确模式沿用旧的同步帧语义`() {
        assertEquals(
            MediaPlayer.SEEK_PREVIOUS_SYNC,
            SeekPrecisionRules.mediaPlayerMode(false)
        )
    }

    // ---------- 是否已经跳到位 ----------

    @Test
    fun `误差在容差内视为已到位`() {
        assertTrue(SeekPrecisionRules.hasLanded(60_000, 60_000))
        assertTrue(SeekPrecisionRules.hasLanded(60_000, 59_800))
        assertTrue(SeekPrecisionRules.hasLanded(60_000, 60_350))
    }

    @Test
    fun `误差超出容差视为没到位`() {
        // 500ms 已经超出 400ms 容差：典型 VBR 外推偏差就落在这个量级
        assertFalse(SeekPrecisionRules.hasLanded(60_000, 59_500))
        assertFalse(SeekPrecisionRules.hasLanded(60_000, 60_500))
    }

    @Test
    fun `引擎报负数位置不算到位`() {
        assertFalse(SeekPrecisionRules.hasLanded(60_000, -1))
    }

    // ---------- 对外报告哪个位置 ----------

    @Test
    fun `没有 seek 进行时直接报真实位置`() {
        assertEquals(12_345, SeekPrecisionRules.reportPosition(-1, 12_345, 0))
    }

    @Test
    fun `seek 中引擎还在旧位置时报目标值`() {
        // 点了 60s，引擎还停在 10s：必须报 60s，否则刚点高的歌词行被弹回
        assertEquals(60_000, SeekPrecisionRules.reportPosition(60_000, 10_000, 100))
    }

    @Test
    fun `seek 落定后交还给引擎`() {
        assertEquals(59_900, SeekPrecisionRules.reportPosition(60_000, 59_900, 200))
    }

    @Test
    fun `超时后无条件交还给引擎以免进度条卡死`() {
        // 引擎始终没跳过去（seek 失败 / 不回调），超时后不能再报目标值
        assertEquals(
            10_000,
            SeekPrecisionRules.reportPosition(60_000, 10_000, SeekPrecisionRules.LOCK_TIMEOUT_MS)
        )
    }
}
