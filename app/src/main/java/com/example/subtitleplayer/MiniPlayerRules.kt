package com.example.subtitleplayer

/**
 * 底部迷你播放条的显隐判定（2.13）。
 *
 * 抽成纯函数的直接原因：2.13 出现过一次回归——`showPage()` 与 `onSongChanged()` 各写了
 * 一份同样的判定，视频页只加进了其中一份。而「从视频列表点进视频」的执行顺序是
 * `onSongChanged()`（那时 page 还是 PLAYER）→ `showPage(VIDEO)`，两份判定不一致时
 * 视频页下方就冒出迷你条（与视频页自己的进度条叠成双进度条）。
 *
 * 现在判定只有这一处，且被单测覆盖：将来新增全屏页面时，测试会提醒你一并更新。
 */
internal object MiniPlayerRules {

    /**
     * 该页面是否显示底部迷你条。
     *
     * 播放页 / 歌词页 / 视频页都**不显示**：它们各自有完整播放控件或全屏画面，
     * 再挂一条迷你条会出现双进度条，或压在视频画面下方。
     *
     * @param hasSong 当前是否有正在播放的歌；没有则一律不显示
     */
    fun shouldShow(hasSong: Boolean, page: Page): Boolean {
        if (!hasSong) return false
        return page != Page.PLAYER && page != Page.LYRICS && page != Page.VIDEO
    }
}
