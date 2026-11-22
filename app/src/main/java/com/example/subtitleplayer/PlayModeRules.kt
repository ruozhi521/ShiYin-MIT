package com.example.subtitleplayer

/**
 * 播放模式的纯逻辑（3.1.2）。
 *
 * 抽出来只为一件事：**取模不能写死数字**。
 *
 * 加第五种模式（[MODE_SINGLE_STOP]）时，`cyclePlayMode` 里原来是 `% 4` ——
 * 新模式永远切不到，界面看起来就像「新功能没做」，而且不报错、极难发现。
 * 放在 Service 里没法单测（要 SharedPreferences + Android 运行时），
 * 所以把「下一个模式是谁」提成纯函数，用测试钉死。
 */
internal object PlayModeRules {

    const val MODE_SEQUENCE = 0
    const val MODE_SHUFFLE = 1
    const val MODE_REPEAT_ONE = 2
    /** 顺序播放：播到**队列末尾**才停。 */
    const val MODE_SEQUENCE_STOP = 3
    /** 单曲播放：**每首**播完就停（音声需求）。 */
    const val MODE_SINGLE_STOP = 4

    /**
     * 模式总数。
     *
     * ⚠️ 这个值和上面的常量必须一起维护；[nextPlayMode] 依赖它。
     */
    const val MODE_COUNT = 5

    /**
     * 点击一次循环切换后的模式。
     *
     * @param current 当前模式（越界值按 0 处理，避免旧版本存下的怪值把界面卡死）
     */
    fun nextPlayMode(current: Int): Int =
        if (current !in 0 until MODE_COUNT) MODE_SEQUENCE else (current + 1) % MODE_COUNT
}
