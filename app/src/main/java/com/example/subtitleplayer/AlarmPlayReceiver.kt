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
        val start = Intent(context, MediaPlaybackService::class.java)
            .setAction(MediaPlaybackService.ACTION_ALARM_PLAY)
        try {
            context.startForegroundService(start)
        } catch (e: Exception) {
            // 个别 ROM 仍限制后台启动前台服务：退化为普通启动，服务内部会自行转前台
            try {
                context.startService(start)
            } catch (e2: Exception) {
                // 都失败则静默（下次闹钟还会触发）
            }
        }
    }
}
