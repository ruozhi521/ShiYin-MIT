package com.example.subtitleplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 定时开始播放闹钟触发：到点拉起前台播放服务，由服务恢复上次播放。
 */
class AlarmPlayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != MediaPlaybackService.ACTION_ALARM_PLAY) return
        try {
            val start = Intent(context, MediaPlaybackService::class.java)
                .setAction(MediaPlaybackService.ACTION_ALARM_PLAY)
            context.startForegroundService(start)
        } catch (e: Exception) {
            // 后台启动限制等异常：忽略（下次闹钟还会触发）
        }
    }
}
