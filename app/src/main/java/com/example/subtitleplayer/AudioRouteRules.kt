package com.example.subtitleplayer

/**
 * 音频输出路由规则（3.1.1）。
 *
 * ## 为什么需要它
 *
 * B站粉丝反馈两条：
 * 1. 「使用同时有线耳机加无线耳机时，在优先使用有线耳机播放的情况下，直接拔出有线耳机，
 *    会瞬间切换到无线耳机播放，**不会暂停**」
 * 2. 「使用小尾巴+耳机线的组合时，不拔小尾巴的情况下直接拔耳机线不会暂停，插入耳机线也不会暂停」
 *
 * 3.1.1 之前的实现只注册了一个 `ACTION_AUDIO_BECOMING_NOISY` 广播。这个广播的触发条件是
 * **音频即将改由扬声器外放**，所以：
 *
 * - 有线耳机拔掉 → 落到扬声器 → 广播会发 → 能暂停 ✅
 * - 有线耳机拔掉 → 系统自动切到另一只蓝牙耳机 → **没有落到扬声器** → 广播不发 ❌
 *   这正是反馈 1：声音直接从耳机 A 跳到耳机 B，用户来不及反应就外放了。
 *
 * 所以必须换一个判据：**跟踪「当前输出设备」的变化**，只要原来的私人设备（有线/蓝牙/USB）
 * 被换成了别的设备，就暂停 —— 不管是换成扬声器还是换成另一只耳机。
 *
 * ## 关于反馈 2 的诚实结论
 *
 * 小尾巴（USB-C 转 3.5mm）+ 耳机线的组合，拔掉的是**插在小尾巴上的耳机插头**。
 * 对 Android 来说 USB 音频设备自始至终都在线，音频路由没有任何变化，
 * 系统层面**不存在**能感知这一动作的公开 API —— 这不是没做，是拿不到。
 * 唯一能感知的是 USB 设备本身的插拔（[Kind.USB] 从有到无），那一条已经覆盖。
 *
 * 抽成纯函数是为了能脱离 Android 框架单测：设备类型是 int 常量，
 * 判断逻辑只依赖类型序列。
 */
internal object AudioRouteRules {

    /** 输出设备的粗略分类（只区分「会不会外放扰民」相关的几类）。 */
    enum class Kind {
        /** 内置扬声器：本来就是外放，路由变到它要暂停（防突然出声）。 */
        SPEAKER,

        /** 听筒（通话用）。 */
        EARPIECE,

        /** 3.5mm 有线耳机 / 耳麦。 */
        WIRED,

        /** USB 音频（小尾巴、USB 耳机、USB 声卡）。 */
        USB,

        /** 蓝牙（A2DP / SCO / BLE）。 */
        BLUETOOTH,

        /** 其他或判断不了。 */
        OTHER
    }

    /**
     * [android.media.AudioDeviceInfo] 的 type 常量 → [Kind]。
     *
     * 用数字字面量而不是 `AudioDeviceInfo.TYPE_*` 常量，是因为其中
     * `TYPE_BLE_HEADSET` / `TYPE_BLE_SPEAKER` 要 API 31，而 minSdk 是 26；
     * 常量在编译期就内联了，引用它们只会在 lint 上留一条 NewApi 提示，
     * 但对于纯逻辑模块，直接写字面量 + 注释更清楚，也便于单测传任意值。
     */
    fun kindOf(type: Int): Kind = when (type) {
        1 -> Kind.EARPIECE                    // TYPE_BUILTIN_EARPIECE
        2 -> Kind.SPEAKER                     // TYPE_BUILTIN_SPEAKER
        3, 4 -> Kind.WIRED                    // TYPE_WIRED_HEADSET / TYPE_WIRED_HEADPHONES
        7, 8 -> Kind.BLUETOOTH                // TYPE_BLUETOOTH_SCO / TYPE_BLUETOOTH_A2DP
        26, 27 -> Kind.BLUETOOTH              // TYPE_BLE_HEADSET / TYPE_BLE_SPEAKER（API 31）
        11, 12, 22 -> Kind.USB                // TYPE_USB_DEVICE / TYPE_USB_ACCESSORY / TYPE_USB_HEADSET
        else -> Kind.OTHER
    }

    /**
     * 从当前可用输出设备里挑出「系统最可能正在用的那个」。
     *
     * Android 没有公开 API 直接查询「当前激活的输出设备」（`getDevices` 只返回可用列表），
     * 所以按系统的**优先级**近似：私人设备优先于外放，同类里耳机优先于耳麦。
     * 顺序与 AudioService 的路由优先级基本一致，对「拔掉后会不会外放」这个判断足够。
     */
    fun activeOutput(types: List<Int>): Kind {
        val kinds = types.map { kindOf(it) }.toSet()
        return when {
            Kind.USB in kinds -> Kind.USB
            Kind.WIRED in kinds -> Kind.WIRED
            Kind.BLUETOOTH in kinds -> Kind.BLUETOOTH
            Kind.SPEAKER in kinds -> Kind.SPEAKER
            Kind.EARPIECE in kinds -> Kind.EARPIECE
            else -> Kind.OTHER
        }
    }

    /**
     * 输出设备从 [before] 变成 [after] 时，是否应当暂停播放。
     *
     * 判据是「**原本在私人设备上听，后来换了**」——换成扬声器要暂停（防外放），
     * 换成另一只耳机/音箱**同样要暂停**（这正是反馈 1 说的「瞬间切到无线耳机」）。
     *
     * 不该暂停的三种情况：
     * - 路由没变（插拔一个未被使用的设备时会出现）；
     * - 本来就在外放（扬声器/听筒/未知），换成什么都不算「丢失私人设备」；
     * - 变化前后都是同一个 [Kind]（例如两只蓝牙之间切换，用户明确在听，
     *   强行暂停反而打断；这条留作保守取值，宁少暂停不多暂停）。
     */
    fun shouldPauseOnRouteChange(before: Kind, after: Kind): Boolean {
        if (before == after) return false
        return when (before) {
            Kind.WIRED, Kind.USB, Kind.BLUETOOTH -> true
            Kind.SPEAKER, Kind.EARPIECE, Kind.OTHER -> false
        }
    }
}
