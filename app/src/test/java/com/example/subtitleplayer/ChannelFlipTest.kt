package com.example.subtitleplayer

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * 左右声道交换的纯逻辑测试（3.0.3）。
 *
 * `ChannelFlipProcessor` 本身依赖 media3 运行时不便于单测，故把交换算法抽成
 * [swapStereoInPlace] 单独验证 —— 交换写错会直接导致「声音变了但没换边」或杂音。
 */
class ChannelFlipTest {

    @Test
    fun `左右声道被正确交换`() {
        val a = shortArrayOf(1, 2, 3, 4, 5, 6)
        swapStereoInPlace(a)
        assertArrayEquals(shortArrayOf(2, 1, 4, 3, 6, 5), a)
    }

    @Test
    fun `交换两次等于还原`() {
        val original = shortArrayOf(100, -100, 32767, -32768)
        val a = original.copyOf()
        swapStereoInPlace(a)
        swapStereoInPlace(a)
        assertArrayEquals(original, a)
    }

    @Test
    fun `奇数个样本时最后一个不动`() {
        val a = shortArrayOf(1, 2, 3)
        swapStereoInPlace(a)
        assertArrayEquals(shortArrayOf(2, 1, 3), a)
    }

    @Test
    fun `空数组与单样本不崩`() {
        val empty = ShortArray(0)
        swapStereoInPlace(empty)
        assertArrayEquals(ShortArray(0), empty)
        val one = shortArrayOf(7)
        swapStereoInPlace(one)
        assertArrayEquals(shortArrayOf(7), one)
    }
}
