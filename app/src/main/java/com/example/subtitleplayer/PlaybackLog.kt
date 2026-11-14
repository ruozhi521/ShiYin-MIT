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
 * 与 CrashCatcher 互补：CrashCatcher 管闪退，这里管「不闪退但行为不对」。
 *
 * ## 两层缓冲（3.1 重做落盘）
 *
 * - **内存**：[MAX_LINES] 条环形缓冲，随进程消失；
 * - **磁盘**：追加写，超过 [FILE_MAX_BYTES] 时**保留尾部**重写。
 *
 * 为什么磁盘要保留尾部而不是清空：粉丝反馈过「崩溃后导出的日志里没有崩溃前的内容」——
 * 有价值的恰恰是最新的那几条。所以轮转砍掉的是头部旧记录。
 *
 * ## 3.1 修掉的三个缺陷（用户实测「日志从 10/15 一直堆到现在，非常多」）
 *
 * 1. **`persist()` 自我膨胀（最严重）**：它调用 `dump()` 读整个文件，再写回**同一个文件**。
 *    于是每打开一次「运行日志」弹窗，文件就多叠加一份自己的内容 —— 指数级膨胀。
 *    该函数已删除：弹窗本来就已拿到 `dump()` 的字符串，落盘纯属多余。
 * 2. **`delete()` 返回值没检查**：原轮转写的是 `if (f.length() > MAX) f.delete()`，
 *    文件被占用或删除失败时静默继续追加，于是永远轮转不动。现在改成「读尾部 → 原子重写」，
 *    不依赖删除是否成功。
 * 3. **阈值过大 + 导出无上限**：512KB ≈ 4000 行，一个月都触发不到；且 `dump()`
 *    全量返回，弹窗一次渲染上万行。现在落盘上限 256KB、导出只给最后 [DUMP_MAX_LINES] 行。
 */
object PlaybackLog {

    private const val MAX_LINES = 300
    private val lines = ArrayDeque<String>()
    private val tsFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)

    private const val FILE_NAME = "playback_log.txt"

    /** 落盘文件上限。超过就保留尾部重写（256 KB ≈ 2000 行，够定位最近几次问题）。 */
    private const val FILE_MAX_BYTES = 256 * 1024

    /** 轮转时保留多少行（砍头部、留最近的）。 */
    private const val TRIM_KEEP_LINES = 400

    /** 导出时最多返回多少行。上万行塞进 TextView 会卡，也没有阅读价值。 */
    private const val DUMP_MAX_LINES = 400

    /** 绑定后的应用上下文；未绑定时只走内存。 */
    private var appContext: android.content.Context? = null

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
            // 超限先缩（保留尾部），再追加。不依赖 File.delete() 是否成功。
            if (f.length() > FILE_MAX_BYTES) trimFile(f)
            java.io.FileOutputStream(f, true).use { out ->
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (_: Exception) {
            // 落盘失败同样不影响功能
        }
    }

    /**
     * 把文件缩到 [TRIM_KEEP_LINES] 行（保留**最新**的）。
     *
     * ## 为什么从尾部读而不是 readLines
     *
     * 用户的文件可能已经膨胀到几 MB（旧版的自我膨胀 bug 造成的）。
     * `readLines()` 会把整个文件读进内存再取尾巴 —— 而这段逻辑跑在 `log()` 里，
     * 即**启动路径上**，等于把旧 bug 的代价转成启动卡顿。
     *
     * 所以用 `RandomAccessFile` 从尾部读固定字节，无论文件多大成本都恒定。
     *
     * ## 为什么用 rename 而不是 delete
     *
     * 老代码 `f.delete()` 失败时静默继续追加，是日志堆一个月的直接原因。
     * rename 是原子操作，也不会出现「删了但没写成功」留下空文件的情况。
     */
    private fun trimFile(f: java.io.File) {
        try {
            // 只从尾部读 256KB —— 足够装下 TRIM_KEEP_LINES 行，且与文件总大小无关
            val readBytes = minOf(f.length(), 256L * 1024).toInt()
            if (readBytes <= 0) return
            val buf = ByteArray(readBytes)
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(f.length() - readBytes)
                raf.readFully(buf)
            }
            val tail = String(buf, Charsets.UTF_8)
            val allLines = tail.split('\n').filter { it.isNotBlank() }
            if (allLines.size <= TRIM_KEEP_LINES) return
            // 可能第一行是被截断的半行（从中间切开），丢掉它
            val kept = allLines.subList(allLines.size - TRIM_KEEP_LINES, allLines.size)
            val tmp = java.io.File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(kept.joinToString("\n") + "\n", Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                // rename 失败（极少数文件系统）→ 退回直接覆盖写
                f.writeText(kept.joinToString("\n") + "\n", Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: Exception) {
            // 缩容失败就让它继续长，下次 log 再试，不影响功能
        }
    }

    /**
     * 导出日志文本（含设备/系统/版本信息头）。
     *
     * 优先用落盘文件（含崩溃前内容）；文件为空时回退内存缓冲。
     * 只返回最后 [DUMP_MAX_LINES] 行 —— 用户要的是「最近出了什么问题」，
     * 不是一整月的流水账。
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
            if (f.exists()) f.readLines(Charsets.UTF_8) else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val source = if (fromFile.isNotEmpty()) {
            fromFile
        } else {
            synchronized(lines) { lines.toList() }
        }
        if (source.isEmpty()) return sb.toString()

        // 只给尾部，并在头部标一行说明 —— 免得用户以为日志只有这么点、怀疑我们截断了重要内容
        val shown = if (source.size > DUMP_MAX_LINES) {
            sb.append("（共 ${source.size} 行，以下为最近 $DUMP_MAX_LINES 行）\n")
            source.subList(source.size - DUMP_MAX_LINES, source.size)
        } else {
            source
        }
        for (l in shown) sb.append(l).append('\n')
        return sb.toString()
    }
}
