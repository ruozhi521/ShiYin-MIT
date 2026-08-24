package com.example.subtitleplayer

import android.content.Context
import android.content.SharedPreferences
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 闪退自动收集 + 下次启动弹窗显示崩溃日志。
 *
 * 设计（本 App 完全本地、不联网，不做云上报）：
 * - 安装一个全局 UncaughtExceptionHandler，崩溃时把完整堆栈存进 SharedPreferences，
 *   然后交给原 handler 继续（闪退流程不改）。
 * - MainActivity 下次启动时检测到未读崩溃日志 → 弹窗显示堆栈 + 一键「复制」，
 *   用户把内容发给作者即可定位。
 */
object CrashCatcher {

    private const val PREF = "crash_log"
    private const val KEY_LOG = "last_crash"
    private const val KEY_TS = "last_crash_ts"
    private var installed = false

    /** 在 MainActivity 最早期调用一次，安装全局捕获。重复调用安全。 */
    fun install(applicationContext: Context) {
        if (installed) return
        installed = true
        val prefs = applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("=== 拾音闪退日志 ${System.currentTimeMillis()} ===")
                pw.println("线程: ${thread.name} ($thread)")
                throwable.printStackTrace(pw)
                pw.println()
                prefs.edit()
                    .putString(KEY_LOG, sw.toString())
                    .putLong(KEY_TS, System.currentTimeMillis())
                    .apply()
            } catch (_: Exception) {
                // 保存失败不影响闪退流程
            }
            prev?.uncaughtException(thread, throwable)
        }
    }

    /** 取未读崩溃日志；无则返回 null，并清空，防止重复弹窗。 */
    fun takeCrashLog(context: Context): String? {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val log = prefs.getString(KEY_LOG, null) ?: return null
        prefs.edit().remove(KEY_LOG).remove(KEY_TS).apply()
        return log
    }
}
