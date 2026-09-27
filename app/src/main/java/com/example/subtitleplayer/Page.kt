package com.example.subtitleplayer

/**
 * 应用页面（2.13：从 MainActivity 内的嵌套枚举提为顶层）。
 *
 * 提出来是为了让 [MiniPlayerRules] 与单测能直接引用，不必让测试加载庞大的
 * MainActivity（里面引用大量 Android 类型，纯 JVM 测试下加载不安全）。
 * 本枚举是纯 Kotlin，无 Android 依赖。
 */
internal enum class Page {
    DISCOVER, LIBRARY, PLAYLIST, SEARCH, PLAYER, LYRICS, FAVORITES, VIDEOS, VIDEO
}
