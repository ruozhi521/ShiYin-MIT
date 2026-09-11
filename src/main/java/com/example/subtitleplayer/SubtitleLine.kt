package com.example.subtitleplayer

/**
 * 一行字幕/歌词。点击跳转使用 [startMs]。
 */
data class SubtitleLine(
    val startMs: Int,
    val endMs: Int,
    val text: String
)
