package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AudioRouteRules 纯逻辑测试（不依赖 Android 框架，设备类型用 int 字面量）。 */
class AudioRouteRulesTest {

    private val speaker = 2        // TYPE_BUILTIN_SPEAKER
    private val earpiece = 1       // TYPE_BUILTIN_EARPIECE
    private val wiredHeadset = 3   // TYPE_WIRED_HEADSET
    private val wiredPhones = 4    // TYPE_WIRED_HEADPHONES
    private val btA2dp = 8         // TYPE_BLUETOOTH_A2DP
    private val usbDevice = 11     // TYPE_USB_DEVICE
    private val usbHeadset = 22    // TYPE_USB_HEADSET

    @Test
    fun `设备类型映射到正确分类`() {
        assertEquals(AudioRouteRules.Kind.SPEAKER, AudioRouteRules.kindOf(speaker))
        assertEquals(AudioRouteRules.Kind.EARPIECE, AudioRouteRules.kindOf(earpiece))
        assertEquals(AudioRouteRules.Kind.WIRED, AudioRouteRules.kindOf(wiredHeadset))
        assertEquals(AudioRouteRules.Kind.WIRED, AudioRouteRules.kindOf(wiredPhones))
        assertEquals(AudioRouteRules.Kind.BLUETOOTH, AudioRouteRules.kindOf(btA2dp))
        assertEquals(AudioRouteRules.Kind.USB, AudioRouteRules.kindOf(usbDevice))
        assertEquals(AudioRouteRules.Kind.USB, AudioRouteRules.kindOf(usbHeadset))
        assertEquals(AudioRouteRules.Kind.OTHER, AudioRouteRules.kindOf(999))
    }

    @Test
    fun `BLE 设备（API31 常量）也归入蓝牙`() {
        assertEquals(AudioRouteRules.Kind.BLUETOOTH, AudioRouteRules.kindOf(26))
        assertEquals(AudioRouteRules.Kind.BLUETOOTH, AudioRouteRules.kindOf(27))
    }

    @Test
    fun `私人设备优先于扬声器`() {
        assertEquals(
            AudioRouteRules.Kind.WIRED,
            AudioRouteRules.activeOutput(listOf(speaker, wiredPhones))
        )
        assertEquals(
            AudioRouteRules.Kind.BLUETOOTH,
            AudioRouteRules.activeOutput(listOf(speaker, btA2dp))
        )
        assertEquals(
            AudioRouteRules.Kind.USB,
            AudioRouteRules.activeOutput(listOf(speaker, usbHeadset))
        )
    }

    @Test
    fun `只有扬声器时就是扬声器`() {
        assertEquals(AudioRouteRules.Kind.SPEAKER, AudioRouteRules.activeOutput(listOf(speaker)))
        assertEquals(AudioRouteRules.Kind.OTHER, AudioRouteRules.activeOutput(emptyList()))
    }

    @Test
    fun `有线耳机拔到扬声器要暂停`() {
        assertTrue(
            AudioRouteRules.shouldPauseOnRouteChange(
                AudioRouteRules.Kind.WIRED, AudioRouteRules.Kind.SPEAKER
            )
        )
    }

    /**
     * 这条是本次修复的核心：粉丝「有线耳机拔出后瞬间切到无线耳机，不会暂停」。
     * 此时音频**没有经过扬声器**，ACTION_AUDIO_BECOMING_NOISY 不会发，
     * 必须由路由变化本身触发暂停。
     */
    @Test
    fun `有线耳机切到蓝牙也要暂停`() {
        assertTrue(
            AudioRouteRules.shouldPauseOnRouteChange(
                AudioRouteRules.Kind.WIRED, AudioRouteRules.Kind.BLUETOOTH
            )
        )
    }

    @Test
    fun `蓝牙断开到扬声器要暂停`() {
        assertTrue(
            AudioRouteRules.shouldPauseOnRouteChange(
                AudioRouteRules.Kind.BLUETOOTH, AudioRouteRules.Kind.SPEAKER
            )
        )
    }

    @Test
    fun `USB 声卡拔出要暂停`() {
        assertTrue(
            AudioRouteRules.shouldPauseOnRouteChange(
                AudioRouteRules.Kind.USB, AudioRouteRules.Kind.SPEAKER
            )
        )
    }

    @Test
    fun `本来就在外放 换成什么都不暂停`() {
        assertFalse(
            AudioRouteRules.shouldPauseOnRouteChange(
                AudioRouteRules.Kind.SPEAKER, AudioRouteRules.Kind.WIRED
            )
        )
        assertFalse(
            AudioRouteRules.shouldPauseOnRouteChange(
                AudioRouteRules.Kind.SPEAKER, AudioRouteRules.Kind.BLUETOOTH
            )
        )
        assertFalse(
            AudioRouteRules.shouldPauseOnRouteChange(
                AudioRouteRules.Kind.OTHER, AudioRouteRules.Kind.SPEAKER
            )
        )
    }

    @Test
    fun `同类设备之间切换不暂停`() {
        assertFalse(
            AudioRouteRules.shouldPauseOnRouteChange(
                AudioRouteRules.Kind.BLUETOOTH, AudioRouteRules.Kind.BLUETOOTH
            )
        )
    }

    @Test
    fun `插入耳机（扬声器到耳机）不暂停`() {
        assertFalse(
            AudioRouteRules.shouldPauseOnRouteChange(
                AudioRouteRules.Kind.SPEAKER, AudioRouteRules.Kind.WIRED
            )
        )
        assertFalse(
            AudioRouteRules.shouldPauseOnRouteChange(
                AudioRouteRules.Kind.SPEAKER, AudioRouteRules.Kind.USB
            )
        )
    }
}
