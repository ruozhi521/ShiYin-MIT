package com.example.subtitleplayer

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 播放运行日志（1.34）：环形缓冲记录播放关键事件
 * （切歌 / 预加载 / 出声 / 失败码 / 熔断决策），供设置里「导出运行日志」
 * 一键复制发给作者定位问题（尤其无法复现的机型，如 OriginOS 6）。
 *
 * 设计：只存内存（最多 [MAX_LINES] 条，防止无限增长）；导出时拼上设备信息头，
 * 顺带落盘一份到应用私有目录（playback_log.txt）备用。
 * 与 CrashCatcher 互补：CrashCatcher 管闪退，这里管「不闪退但行为不对」。
 */
object PlaybackLog {

    private const val MAX_LINES = 300
    private val lines = ArrayDeque<String>()
    private val tsFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)

    fun log(msg: String) {
        try {
            synchronized(lines) {
                lines.addLast("${tsFormat.format(Date())} $msg")
                while (lines.size > MAX_LINES) lines.removeFirst()
            }
        } catch (_: Exception) {
            // 日志失败不影响播放
        }
    }

    /** 导出完整日志文本（含设备/系统/版本信息头）。 */
    fun dump(context: Context): String {
        val sb = StringBuilder()
        sb.append("=== 拾音运行日志 ===\n")
        try {
            val ver = try {
                context.packageManager
                    .getPackageInfo(context.packageName, 0).versionName
            } catch (e: Exception) {
                "?"
            }
            sb.append("设备: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            sb.append("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                .append(" / ${Build.VERSION.INCREMENTAL}\n")
            sb.append("版本: $ver\n")
        } catch (_: Exception) {
        }
        sb.append("---\n")
        synchronized(lines) {
            for (l in lines) sb.append(l).append('\n')
        }
        return sb.toString()
    }

    /** 落盘一份到应用私有目录（备用，正常走复制分享）。 */
    fun persist(context: Context) {
        try {
            java.io.File(context.filesDir, "playback_log.txt").writeText(dump(context))
        } catch (_: Exception) {
        }
    }
}
