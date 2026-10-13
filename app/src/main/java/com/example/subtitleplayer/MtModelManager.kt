package com.example.subtitleplayer

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 离线翻译模型的下载器（3.0）。
 *
 * 模型不进 APK，走「可选下载」—— 与现有 ASR 模型一致的使用体验：
 * 未下载时设置页给出引导，下载完成后即可离线翻译。
 *
 * ## 需要哪几个文件（共 3 个，约 216 MB）
 *
 * | 文件 | 大小 | 用途 |
 * |---|---|---|
 * | `encoder_model.onnx` | 100.2 MB | 编码器（fp16） |
 * | `decoder_model.onnx` | 112.5 MB | 解码器（fp16，无 KV cache） |
 * | `vocab.json` | 1.8 MB | 词表 65001 + BPE 合并得分 |
 *
 * **为什么不要 `source.spm` / `target.spm`**：实测确认两者与 `vocab.json`
 * 的 piece↔id 映射**完全一致**（全量 65001 条 0 差异），且两者彼此的
 * encode/decode 行为也完全一致（差异只在内部元数据的路径字符串）。
 * 所以只用 `vocab.json` 一张表即可同时承担日文编码与中文解码，
 * 省掉 2.6 MB 与一个 protobuf 解析器。
 *
 * ## 下载机制（沿用 ASR 模型那套已验证的做法，但刻意不共用代码）
 *
 * 与 [SpeechRecManager] 同套路：镜像源按序尝试、手动跟随重定向、
 * 首发字节 `'<'` 判为 HTML 错误页、收满长度立即结束、读超时当 EOF。
 *
 * **为什么不把两边抽成公共类**：`SpeechRecManager` 是 ASR 的生产路径，
 * 2.0/2.1 期间为「下载总卡在 tokens.txt」等问题反复调过，现在稳定。
 * 为共用而重构它、风险大于收益，故此处独立实现（逻辑同源、可读性一致）。
 */
object MtModelManager {

    /** 模型目录名（`filesDir` 下）。 */
    private const val MODEL_DIR = "mt_model"

    /**
     * 模型版本号（3.0 新增）。
     *
     * **为什么需要它**：v2 / v3 / v4 的 `encoder_model.onnx`（105,020,768 B）与
     * `decoder_model.onnx`（117,946,490 B）**大小完全相同**（实测），而下载判定
     * 原先只比大小 —— 若将来换成大小相同的新版，已下过旧版的设备**永远不会更新**。
     *
     * 现在的做法：下载成功后把本常量写进 [VERSION_FILE]；判定就位时要求
     * 版本文件内容 == 本常量，否则视为未下载（设置页显示"未下载"，点下载会
     * 自动清掉旧文件重下）。
     *
     * **换模型时只需改这里**（与 HF 仓库的版本对应）。
     *
     * 注：v4 是第一个对外版本（3.0 尚未推送，线上市面 2.16 无离线翻译功能），
     * 故当前不存在需要迁移的老设备；本机制是为 v5/v6 预留。
     */
    private const val MODEL_VERSION = "v4"

    /** 记录已下载模型版本的标记文件（内容即 [MODEL_VERSION]）。 */
    private const val VERSION_FILE = "model_version.txt"

    /** 模型仓库（HuggingFace）。文件走 LFS，下载时会被 302 重定向到对象存储。 */
    private const val REPO = "ruozhi521/shiyin-mt-ja-zh-onnx"

    /**
     * 需要下载的文件（顺序即下载顺序；先小后大，失败能尽早暴露）。
     *
     * `minBytes` 取「接近真实体积」而非「大于 0」，用于识别**被截断的下载**：
     * `vocab.json` 走 307 到 `/api/resolve-cache`，最终响应是 Cloudflare 的 chunked
     * （既无 Content-Length 也无 X-Linked-Size，实测），拿不到总长度，
     * 只能靠这个门槛挡住半截文件。真实体积见下表。
     *
     * `sha256` 为 v4 的实测值（3.0 新增），下载完成后**全量校验**：
     * 长度对得上但内容损坏（镜像返回 HTML、传输中途出错）也能拦住。
     * 之所以只在下完时算、不在每次判定时算 —— 216 MB 全量哈希耗时数秒，
     * 放进判定会导致每次进设置页都卡顿；判定改用 [VERSION_FILE]（O(1)）。
     *
     * ⚠️ 三处已实测的边界（决定为什么不能靠"抽样哈希"省时间）：
     * - `encoder_model.onnx`：头/中/尾片段 v2 与 v4 均不同
     * - `decoder_model.onnx`：**头部片段 v2 与 v4 相同**，中/尾才不同
     * - `vocab.json`：v2 与 v4 **逐字节完全相同** → 它不能用于区分版本
     */
    private val FILES = listOf(
        // 实测 1,871,214 B（v2/v4 相同）
        FileSpec(
            "vocab.json", 1_500_000,
            "475501ee1f35876ec50894ef34ef88e949cc92c688da24ccb26abcc3281c0256"
        ),
        // 实测 105,020,768 B
        FileSpec(
            "encoder_model.onnx", 60_000_000,
            "b98e850b37d960295a23158e47db13633286d2e11c575d69475bf3bf6689f993"
        ),
        // 实测 117,946,490 B
        FileSpec(
            "decoder_model.onnx", 60_000_000,
            "9380a66e508b9b1a6630b3b47c06a8a183c669b3674cb24446e3f84c4408b2aa"
        )
    )

    private class FileSpec(val name: String, val minBytes: Long, val sha256: String)

    /**
     * 镜像源（按序尝试）。与 ASR 一致：`hf-mirror.com` 国内可直连放最前，
     * HuggingFace 原站作回退（需 VPN）。
     */
    private val SOURCES = listOf(
        "https://hf-mirror.com/$REPO/resolve/main/",
        "https://huggingface.co/$REPO/resolve/main/"
    )

    fun modelDir(context: Context): File = File(context.filesDir, MODEL_DIR)

    /**
     * 已下载的模型是否就是当前 [MODEL_VERSION]（读版本标记文件）。
     *
     * **为什么不比对文件 sha256**：216 MB 全量哈希在手机上一次要数秒，
     * 而本函数会被设置页反复调用（每次切 provider、每次刷新状态）——
     * 放进去会明显卡顿。版本文件是 O(1) 的，且由下载流程在 sha 校验通过后才写，
     * 可信度等同（写它 = 三个文件都过了 sha）。
     */
    private fun versionMatches(context: Context): Boolean {
        val vf = File(modelDir(context), VERSION_FILE)
        if (!vf.isFile) return false
        val content = try {
            vf.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
        return MtModelRules.isVersionOk(content, MODEL_VERSION)
    }

    /**
     * 某个文件是否已就位：**体积达标 且 模型版本相符**（供 UI 判定用）。
     *
     * 「版本相符」是 3.0 新增的必要条件：v2/v3/v4 的 encoder 与 decoder
     * **大小完全相同**（实测 105,020,768 / 117,946,490），只比大小的话，
     * 换模型后老文件会被一直判为「已就位」，永远不会重新下载。
     */
    private fun resolved(context: Context, spec: FileSpec): File? {
        if (!versionMatches(context)) return null
        return fileUsable(context, spec)
    }

    /**
     * 文件本身是否可用（**不要求版本匹配**）。仅供下载流程内部用。
     *
     * 为什么下载流程不能复用 [resolved]：那样会让**续传失效**——
     * 下载中断后版本文件尚未写入，重试时 [resolved] 判所有文件都「未就位」，
     * 已下好的 200 MB 会被重下。这里只判文件本身，保住续传能力；
     * 版本切换则由 [wipeIfOtherVersion] 在下载开始前一次性处理。
     */
    private fun fileUsable(context: Context, spec: FileSpec): File? {
        val f = File(modelDir(context), spec.name)
        if (!f.isFile) return null
        return if (MtModelRules.isFileUsable(f.length(), spec.minBytes)) f else null
    }

    /**
     * 若目录里躺着**别的版本**的模型，清空它（升级路径）。
     *
     * 判据刻意区分两种「版本文件缺失」（详见 [MtModelRules.shouldWipeVersion]）：
     * - **存在但内容 ≠ [MODEL_VERSION]** → 确实是旧版本 → 清空重下
     * - **不存在** → 可能从未下载成功、也可能正在下载中断 → **不清空**，
     *   让下载流程走续传（否则中断一次就要重下 216 MB）
     *
     * 即：只有「明确是另一个版本」才清；「不知道是哪版」按续传处理。
     */
    private fun wipeIfOtherVersion(dir: File) {
        val vf = File(dir, VERSION_FILE)
        if (!vf.isFile) return
        val cur = try {
            vf.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return
        }
        if (!MtModelRules.shouldWipeVersion(cur, MODEL_VERSION)) return
        PlaybackLog.log("mt model version change ${cur.trim()} -> $MODEL_VERSION, wiping old files")
        dir.listFiles()?.forEach { f ->
            if (!f.delete()) PlaybackLog.log("mt model wipe failed: ${f.name}")
        }
    }

    /**
     * 3 个文件是否齐备（只查文件，不加载模型）。
     *
     * 同样要求版本相符 —— 否则设置页会显示「已就绪」，而实际是旧版模型。
     */
    fun isModelReady(context: Context): Boolean =
        versionMatches(context) && MarianMtEngine.isModelReady(modelDir(context))

    /** 已就位文件数（供设置页显示「2/3」之类）。 */
    fun readyCount(context: Context): Int = FILES.count { resolved(context, it) != null }

    fun totalCount(): Int = FILES.size

    /**
     * 下载模型（在后台线程内执行）。幂等：已就位的文件会跳过。
     *
     * @param onProgress 参数为 (第几个文件 0-based, 该文件百分比 0..100)
     * @param onDone 结束时回调；`ok=false` 时 `errMsg` 说明是哪个文件、什么错
     * @param isCancelled 返回 true 则尽快中止（保留已下好的文件，下次可续）
     */
    fun downloadModel(
        context: Context,
        onProgress: (fileIndex: Int, filePercent: Int) -> Unit,
        onDone: (ok: Boolean, errMsg: String?) -> Unit,
        isCancelled: () -> Boolean = { false }
    ) {
        Thread {
            val dir = modelDir(context).apply { mkdirs() }
            // 换版本时先清空旧文件（3.0）。只有「明确是另一个版本」才清；
            // 版本文件缺失（首次下载 / 中断续传）一律保留，避免重下 216 MB。
            wipeIfOtherVersion(dir)
            for ((idx, spec) in FILES.withIndex()) {
                // 用 fileUsable 而非 resolved：下载流程不要求版本匹配，
                // 否则中断续传时版本文件尚未写入，已下好的文件会被重下。
                if (fileUsable(context, spec) != null) {
                    onProgress(idx, 100)
                    continue
                }
                var ok = false
                var lastErr: String? = null
                // 与 ASR 一致：镜像小文件请求时好时坏，整体重试 4 轮，退避 3/6/9 秒
                attempt@ for (attempt in 0 until 4) {
                    if (isCancelled()) {
                        onDone(false, "已取消")
                        return@Thread
                    }
                    if (attempt > 0) {
                        PlaybackLog.log("mt model retry #${attempt + 1} for ${spec.name}")
                        try {
                            Thread.sleep(3000L * attempt)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            onDone(false, "已取消")
                            return@Thread
                        }
                    }
                    for (base in SOURCES) {
                        if (isCancelled()) {
                            onDone(false, "已取消")
                            return@Thread
                        }
                        try {
                            if (fetchOne(base + spec.name, File(dir, spec.name),
                                    spec, idx, onProgress, isCancelled)) {
                                ok = true
                                break@attempt
                            }
                            lastErr = "${spec.name} 内容异常（可能 404 页面）"
                        } catch (e: Exception) {
                            lastErr = "${spec.name} ${e.javaClass.simpleName}: ${e.message}"
                            File(dir, "${spec.name}.tmp").delete()
                        }
                    }
                }
                if (!ok) {
                    PlaybackLog.log("mt model download FAIL ${spec.name}: $lastErr")
                    onDone(false, lastErr ?: "下载失败")
                    return@Thread
                }
            }
            // 三个文件全部下载且逐个通过 sha256 校验后，才写版本标记（3.0）。
            // 这一步是「就位判定」的依据：写成功 = 三个文件都真的过了校验。
            // 写失败不影响本次使用（文件已就位），只是下次会重新判定为未下载。
            try {
                File(dir, VERSION_FILE).writeText(MODEL_VERSION, Charsets.UTF_8)
                PlaybackLog.log("mt model version written: $MODEL_VERSION")
            } catch (e: Exception) {
                PlaybackLog.log("mt model version write failed: ${e.message}")
            }
            PlaybackLog.log("mt model download ALL DONE")
            onDone(true, null)
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /**
     * 下载单个文件到 `target.tmp` 再改名。返回 true 表示成功。
     *
     * 几处关键处理都沿用 ASR 的踩坑经验：
     * - **首发字节 `'<'` 判 HTML**：镜像站 404 时会返回一个 HTML 页面，
     *   若不拦会被当成模型文件存下来（体积也"看着正常"）。
     * - **收满 total 立即结束**：hf-mirror 等代理不会主动断流，等 EOF 会挂到读超时。
     * - **读超时当 EOF**：`vocab.json` 这类非 LFS 小文件走 `/api/resolve-cache`
     *   （Cloudflare chunked，既无 Content-Length 也不主动断流）。
     * - **长度不足绝不当成功**：否则会把截断的模型当好的，加载时才报错。
     */
    private fun fetchOne(
        url: String,
        target: File,
        spec: FileSpec,
        idx: Int,
        onProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        val (conn, total) = openDownload(url)
        val tmp = File(target.parentFile, "${spec.name}.tmp")
        var acc = 0L
        var sane = true
        try {
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var first = true
                    while (true) {
                        if (isCancelled()) throw InterruptedException("cancelled")
                        if (total > 0 && acc >= total) break
                        val n = input.read(buf)
                        if (n < 0) break
                        if (first && n > 0 && buf[0] == '<'.code.toByte()) {
                            sane = false
                            break
                        }
                        first = false
                        out.write(buf, 0, n)
                        acc += n
                        if (total > 0) {
                            onProgress(idx, ((acc * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            PlaybackLog.log("mt model read timeout as EOF name=${spec.name} acc=$acc total=$total")
        } finally {
            conn.disconnect()
        }

        if (!sane || acc < spec.minBytes) {
            tmp.delete()
            return false
        }
        if (total > 0 && acc < total) {
            // 截断的文件绝不能放行，否则加载时才报错、白下载 100 MB
            PlaybackLog.log("mt model short ${spec.name} acc=$acc total=$total")
            tmp.delete()
            return false
        }
        // 内容校验：全量 sha256 与仓库现值比对（3.0 新增）。
        // 长度对得上但内容损坏（镜像返回错误页、传输中途出错）也能拦住。
        // 只在下完时算、不在每次判定时算 —— 216 MB 全量哈希在手机上要数秒，
        // 放进判定会让每次进设置页都卡；判定改用版本文件（见 MODEL_VERSION）。
        val got = sha256Of(tmp)
        if (!got.equals(spec.sha256, ignoreCase = true)) {
            PlaybackLog.log("mt model sha mismatch ${spec.name} got=$got want=${spec.sha256}")
            tmp.delete()
            return false
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        onProgress(idx, 100)
        PlaybackLog.log("mt model OK ${spec.name} <- $url ($acc B)")
        return true
    }

    /**
     * 计算文件的 SHA-256（十六进制小写）。失败返回空串（调用方按不匹配处理）。
     *
     * 实现放在 [MtModelRules.sha256Hex]（便于单测）；这里只负责
     * 「开流 + 兜异常」——文件读不了时不能让下载线程崩掉。
     */
    private fun sha256Of(f: File): String = try {
        f.inputStream().use { MtModelRules.sha256Hex(it) }
    } catch (e: Exception) {
        PlaybackLog.log("mt model sha256 failed ${f.name}: ${e.message}")
        ""
    }

    /**
     * 打开下载连接（手动跟随重定向，最多 5 跳），并尽力解析出总长度。
     *
     * 手动跟随的原因见 [SpeechRecManager.openDownload] 的注释：自动跟随会丢掉
     * 中间响应头里的长度信息，导致只能死等 EOF。这里还额外带 `Range: bytes=0-`，
     * 让响应回 `Content-Range` 从而拿到总长（HuggingFace 的 LFS 会 302 到
     * 对象存储，最终响应未必有 Content-Length）。
     */
    private fun openDownload(url: String): Pair<HttpURLConnection, Long> {
        var urlStr = url
        var total = -1L
        var hops = 0
        while (true) {
            val c = URL(urlStr).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "ShiYin/3.0")
            // 明确不要压缩：gzip 会让 Content-Length 与实际读到的字节数对不上
            c.setRequestProperty("Accept-Encoding", "identity")
            c.setRequestProperty("Range", "bytes=0-")
            val code = c.responseCode
            parseTotalLen(c)?.let { if (it > 0) total = it }
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                hops++
                if (loc.isNullOrBlank() || hops > 5) {
                    throw java.io.IOException("redirect $code without usable location")
                }
                urlStr = try {
                    java.net.URI(urlStr).resolve(loc).toString()
                } catch (e: Exception) {
                    loc
                }
                continue
            }
            if (code !in 200..299) {
                c.disconnect()
                throw java.io.IOException("HTTP $code")
            }
            if (total <= 0 && c.contentLengthLong > 0) total = c.contentLengthLong
            return c to total
        }
    }

    /** 响应头里的总长度：`X-Linked-Size`，或 `Content-Range: bytes 0-45753/45754` 里的 45754。 */
    private fun parseTotalLen(c: HttpURLConnection): Long? = try {
        c.getHeaderField("X-Linked-Size")?.trim()?.toLongOrNull()
            ?: c.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
    } catch (e: Exception) {
        null
    }

    /** 删除已下载的模型（设置页「删除模型」用）。返回是否全部删净。 */
    fun deleteModel(context: Context): Boolean {
        val dir = modelDir(context)
        if (!dir.isDirectory) return true
        var allGone = true
        dir.listFiles()?.forEach { f ->
            if (!f.delete()) allGone = false
        }
        if (allGone) dir.delete()
        PlaybackLog.log("mt model deleted, ok=$allGone")
        return allGone
    }
}
