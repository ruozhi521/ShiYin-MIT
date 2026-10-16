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

    /** 落盘文件名与上限（超限就整体重开，避免无限增长；512 KB ≈ 4000 行）。 */
    private const val FILE_NAME = "playback_log.txt"
    private const val FILE_MAX_BYTES = 512 * 1024

    /** 绑定后的应用上下文；未绑定时只走内存（与旧行为一致）。 */
    private var appContext: android.content.Context? = null

    /**
     * 绑定应用上下文（在 MainActivity 最早期调用一次，与 [CrashCatcher.install] 并列）。
     *
     * 绑定后每条日志**同时追加落盘**。为什么非做不可：内存环形缓冲（[MAX_LINES]）随进程一起消失，
     * 而闪退 = 进程直接死 —— 粉丝反馈「崩溃过一次之后重开，导出的日志里完全没有崩溃前的内容」，
     * 正是因为导出的其实是**重开后**的新日志。落盘才留得住最后那几条（能看出崩在哪一步）。
     */
    fun attach(context: android.content.Context) {
        appContext = context.applicationContext
    }

    fun log(msg: String) {
        val line = "${tsFormat.format(Date())} $msg"
        try {
            synchronized(lines) {
                lines.addLast(line)
                while (lines.size > MAX_LINES) lines.removeFirst()
            }
        } catch (_: Exception) {
            // 日志失败不影响播放
        }
        val ctx = appContext ?: return
        try {
            val f = java.io.File(ctx.filesDir, FILE_NAME)
            if (f.length() > FILE_MAX_BYTES) f.delete() // 简单轮转：超限就从零开始
            java.io.FileOutputStream(f, true).use { out ->
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (_: Exception) {
            // 落盘失败同样不影响功能
        }
    }

    /**
     * 导出完整日志文本（含设备/系统/版本信息头）。
     *
     * 3.0.1：**优先用落盘文件** —— 它含崩溃前的内容（内存缓冲在崩溃后重开就清了）；
     * 文件不存在或为空时回退到内存缓冲，保证老行为不变。
     */
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
        val fromFile = try {
            val f = java.io.File(context.filesDir, FILE_NAME)
            if (f.exists()) f.readText(Charsets.UTF_8) else ""
        } catch (e: Exception) {
            ""
        }
        if (fromFile.isNotBlank()) {
            sb.append(fromFile)
        } else {
            synchronized(lines) {
                for (l in lines) sb.append(l).append('\n')
            }
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
