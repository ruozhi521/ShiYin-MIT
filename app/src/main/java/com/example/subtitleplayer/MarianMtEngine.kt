package com.example.subtitleplayer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.ln

/**
 * 离线日→中翻译引擎（MarianMT + ONNX Runtime），**全本地、不联网**。
 *
 * 这是 3.0「本地离线翻译」的推理核心，配合 [MarianBpe]（分词）与模型下载器，
 * 构成完全不依赖云端的翻译路径。
 *
 * ## 模型的输入输出（实测自 ONNX 签名）
 *
 * ```
 * encoder: input_ids INT64[1,S] + attention_mask INT64[1,S]
 *            -> last_hidden_state FLOAT[1,S,512]
 * decoder: input_ids INT64[1,D] + encoder_attention_mask INT64[1,S]
 *          + encoder_hidden_states FLOAT[1,S,512]
 *            -> logits FLOAT[1,D,65001]
 * ```
 *
 * ## 三个必须遵守的约束（全部为实测结论，不是推测）
 *
 * 1. **源文本末尾必须补 `</s>`（id=0）** —— 由 [MarianBpe.encodeForSource] 负责。
 *    缺了它模型会输出无限复读：不补时 `[11506]` → `ありがとう ありがとう …`；
 *    补成 `[11506, 0]` → `多谢啦`。
 * 2. **每步要屏蔽 `<pad>`（id=65000）** —— 对应模型配置里的 `bad_words_ids=[[65000]]`。
 *    它同时是 `decoder_start_token_id`，不屏蔽会被当成普通词生成出来。
 * 3. **本模型没有 KV cache** —— decoder 的输入里没有 `past_key_values`，
 *    所以**每生成一个 token 都要把整个解码序列重算一遍**（复杂度 O(D²)）。
 *    这是手机上速度的主要瓶颈，也是 beam search 代价约 ×N 的原因；
 *    是否开启 beam 要等真机测速后定（见 [translate] 的 numBeams 参数）。
 *
 * ## 生命周期
 *
 * [load] 把两个 ONNX 会话常驻内存（约 217 MB 模型文件，运行时内存更高）。
 * 与 ASR 模型同时驻留对低端机压力较大，调用方应在用完后 [close]。
 */
object MarianMtEngine {

    // ---------------- 文件与常量 ----------------

    const val ENCODER_FILE = "encoder_model.onnx"
    const val DECODER_FILE = "decoder_model.onnx"
    const val VOCAB_FILE = "vocab.json"

    /** 单句生成上限。config.json 的 max_length=512 是「源+译」总长，这里取更保守的生成上限。 */
    const val MAX_NEW_TOKENS = 200

    /**
     * 源侧最大 token 数（含末尾 EOS）。
     *
     * `config.json` 的 `max_length=512` 是「源 token 数 + 译文 token 数」的总和上限，
     * 不是源单独的上限。ASR 偶尔会产出一整段没有标点的长行，若源侧不设限，
     * 超出后要么触发 ORT 报错、要么模型行为异常。这里给源留 400、译文留约 112，
     * 与训练时的长度分布也更接近。
     */
    private const val MAX_SOURCE_TOKENS = 400

    /** decoder 起始 token（= pad id，见 config.json 的 decoder_start_token_id）。 */
    private const val DECODER_START = MarianBpe.PAD_ID

    // ---------------- 运行状态 ----------------

    @Volatile
    private var encoder: OrtSession? = null

    @Volatile
    private var decoder: OrtSession? = null

    @Volatile
    private var vocab: MarianBpe.Vocab? = null

    private var env: OrtEnvironment? = null
    private var sessionOptions: OrtSession.SessionOptions? = null
    private var encoderOutName: String = "last_hidden_state"

    /** 模型 3 个文件是否都已就位（只查文件，不加载）。 */
    fun isModelReady(dir: File): Boolean =
        File(dir, ENCODER_FILE).let { it.isFile && it.length() > 1_000_000 } &&
            File(dir, DECODER_FILE).let { it.isFile && it.length() > 1_000_000 } &&
            File(dir, VOCAB_FILE).let { it.isFile && it.length() > 100_000 }

    /** 是否已加载并就绪。 */
    fun isLoaded(): Boolean = encoder != null && decoder != null && vocab != null

    /**
     * 加载模型（幂等：已加载则直接返回 true）。**耗时较长（秒级），勿在主线程调用。**
     *
     * @return 成功与否；失败时内部已 [close]，不会留下半加载状态。
     */
    @Synchronized
    fun load(dir: File): Boolean {
        if (isLoaded()) return true
        return try {
            val e = OrtEnvironment.getEnvironment()
            // 线程数限制到 4：手机上大核通常就这么几个，给多了反而因调度变慢
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            }
            val t0 = System.currentTimeMillis()
            val enc = e.createSession(File(dir, ENCODER_FILE).absolutePath, opts)
            val dec = e.createSession(File(dir, DECODER_FILE).absolutePath, opts)
            val v = MarianBpe.loadVocab(File(dir, VOCAB_FILE).readText(Charsets.UTF_8))
            if (v.size() != MarianBpe.VOCAB_SIZE) {
                throw IllegalStateException("vocab.json 条目数异常：${v.size()}（期望 ${MarianBpe.VOCAB_SIZE}）")
            }
            env = e
            sessionOptions = opts
            encoder = enc
            decoder = dec
            vocab = v
            // 用实际输出名，避免写死（模型固定，但取一下更稳）
            encoderOutName = enc.outputNames.firstOrNull() ?: "last_hidden_state"
            PlaybackLog.log("mt load ok in ${System.currentTimeMillis() - t0}ms out=$encoderOutName")
            true
        } catch (t: Throwable) {
            PlaybackLog.log("mt load FAILED: ${t.javaClass.simpleName}: ${t.message}")
            close()
            false
        }
    }

    /** 释放两个会话（模型内存占大头）。可重复调用。 */
    @Synchronized
    fun close() {
        runCatching { encoder?.close() }
        runCatching { decoder?.close() }
        runCatching { sessionOptions?.close() }
        encoder = null
        decoder = null
        sessionOptions = null
        vocab = null
        // env 是进程级单例，保留不关，避免影响其它使用者（sherpa 内部也用到 ORT）
    }

    /**
     * 翻译一句日文。**耗时较长（每句可达数百毫秒到数秒），勿在主线程调用。**
     *
     * @param numBeams 1=贪心（快）；>1 为 beam search（质量通常更好，耗时约 ×numBeams）。
     * @return 中文译文；失败或空输入返回空串。
     */
    @Synchronized
    fun translate(text: String, numBeams: Int = 1): String {
        val enc = encoder ?: return ""
        val dec = decoder ?: return ""
        val v = vocab ?: return ""
        val e = env ?: return ""

        // ★ 必须补 </s>，否则无限复读（见类注释约束 1）
        var srcIds = MarianBpe.encodeForSource(text, v)
        if (srcIds.size <= 1) return ""   // 只有 EOS，说明原文没有有效内容

        // 源过长时截断（保留末尾 EOS）：模型 max_length 是「源+译」总和，
        // 源占满会让译文没有余量，极端情况还会让 ORT 直接报错。
        if (srcIds.size > MAX_SOURCE_TOKENS) {
            val cut = srcIds.copyOf(MAX_SOURCE_TOKENS)
            cut[cut.size - 1] = MarianBpe.EOS_ID
            srcIds = cut
            PlaybackLog.log("mt source truncated to $MAX_SOURCE_TOKENS tokens")
        }

        val srcLen = srcIds.size
        val srcLongs = LongArray(srcLen) { srcIds[it].toLong() }
        val maskLongs = LongArray(srcLen) { 1L }

        val idsT = OnnxTensor.createTensor(e, LongBuffer.wrap(srcLongs),
            longArrayOf(1L, srcLen.toLong()))
        val maskT = OnnxTensor.createTensor(e, LongBuffer.wrap(maskLongs),
            longArrayOf(1L, srcLen.toLong()))
        try {
            val encResult = enc.run(mapOf("input_ids" to idsT, "attention_mask" to maskT))
            try {
                val hidden = encResult.get(encoderOutName).orElse(null) as? OnnxTensor
                    ?: run {
                        PlaybackLog.log("mt encode: 输出 '$encoderOutName' 缺失")
                        return ""
                    }
                val genIds = if (numBeams > 1) {
                    MtDecode.beamSearch(
                        startToken = DECODER_START,
                        numBeams = numBeams,
                        maxNewTokens = MAX_NEW_TOKENS,
                        eosId = MarianBpe.EOS_ID,
                        nextLogits = { seq -> runDecoderRow(e, dec, hidden, maskLongs, seq) }
                    )
                } else {
                    MtDecode.greedySearch(
                        startToken = DECODER_START,
                        maxNewTokens = MAX_NEW_TOKENS,
                        eosId = MarianBpe.EOS_ID,
                        nextLogits = { seq -> runDecoderRow(e, dec, hidden, maskLongs, seq) }
                    )
                }
                return MarianBpe.decode(genIds, v)
            } finally {
                runCatching { encResult.close() }
            }
        } catch (t: Throwable) {
            PlaybackLog.log("mt translate failed: ${t.javaClass.simpleName}: ${t.message}")
            return ""
        } finally {
            runCatching { idsT.close() }
            runCatching { maskT.close() }
        }
    }

    /**
     * 跑一次 decoder 前向，返回**最后一行 logits 的拷贝**（长度 [MarianBpe.VOCAB_SIZE]）。
     * 返回空数组表示出错（调用方据此终止解码）。
     */
    private fun runDecoderRow(
        e: OrtEnvironment,
        dec: OrtSession,
        hidden: OnnxTensor,
        maskLongs: LongArray,
        seq: IntArray
    ): FloatArray {
        val d = seq.size
        val seqLongs = LongArray(d) { seq[it].toLong() }
        val idsT = OnnxTensor.createTensor(e, LongBuffer.wrap(seqLongs),
            longArrayOf(1L, d.toLong()))
        // encoder_attention_mask 每步都一样，但 ORT 的 tensor 生命周期由调用方管，
        // 这里复用一个局部实例（比每步新建略省，且保证在本函数内有效）
        val maskT = OnnxTensor.createTensor(e, LongBuffer.wrap(maskLongs),
            longArrayOf(1L, maskLongs.size.toLong()))
        try {
            val r = dec.run(
                mapOf(
                    "input_ids" to idsT,
                    "encoder_attention_mask" to maskT,
                    "encoder_hidden_states" to hidden
                )
            )
            try {
                val logits = r.get(0) as? OnnxTensor ?: return FloatArray(0)
                val fb = logits.floatBuffer
                // 形状 [1, D, V]，取最后一步那一行
                val base = (d - 1) * MarianBpe.VOCAB_SIZE
                if (fb.capacity() < base + MarianBpe.VOCAB_SIZE) {
                    PlaybackLog.log("mt decoder: logits 形状异常 cap=${fb.capacity()} need=${base + MarianBpe.VOCAB_SIZE}")
                    return FloatArray(0)
                }
                val out = FloatArray(MarianBpe.VOCAB_SIZE)
                fb.position(base)
                fb.get(out, 0, MarianBpe.VOCAB_SIZE)
                return out
            } finally {
                runCatching { r.close() }
            }
        } catch (t: Throwable) {
            PlaybackLog.log("mt decoder failed: ${t.javaClass.simpleName}: ${t.message}")
            return FloatArray(0)
        } finally {
            runCatching { idsT.close() }
            runCatching { maskT.close() }
        }
    }
}

/**
 * 解码策略 —— **纯逻辑，不依赖 ONNX**，便于在 JVM 单测里直接验证。
 *
 * 之所以单独抽出来：ONNX 会话要 native 库，单测跑不了；
 * 而「取哪个 token」「beam 怎么剪枝」这些才是容易写错的地方。
 */
internal object MtDecode {

    /**
     * 贪心解码：每步取 logits 最大的 token（跳过 [MarianBpe.PAD_ID]）。
     *
     * @param nextLogits 给定解码序列（含起始 token），返回下一步 logits。
     * @return 生成的 token 序列（不含起始 token，不含 EOS）。
     */
    fun greedySearch(
        startToken: Int,
        maxNewTokens: Int,
        eosId: Int,
        /**
         * no-repeat n-gram 的 n（见 [bannedNgrams]）。0 = 关闭。
         *
         * 默认 3：实测 37 句 ASMR 台本，纯贪心复读 2 句（5.4%），
         * 开 3-gram 阻断后 **0 句复读**，且总耗时反而从 16.3s 降到 8.5s
         * ——因为不再陷进复读循环跑满 200 token。beam4 同样 0 复读但要 23.4s。
         * 故「贪心 + no-repeat」质量与 beam 持平、速度最快。
         */
        noRepeatNgramSize: Int = DEFAULT_NO_REPEAT_NGRAM,
        nextLogits: (IntArray) -> FloatArray
    ): IntArray {
        val seq = ArrayList<Int>(maxNewTokens + 1)
        seq.add(startToken)
        val out = ArrayList<Int>(maxNewTokens)
        var step = 0
        while (step < maxNewTokens) {
            val logits = nextLogits(seq.toIntArray())
            if (logits.isEmpty()) break
            val banned = bannedNgrams(seq, noRepeatNgramSize)
            var next = argmax(logits, MarianBpe.PAD_ID, banned)
            // 整个词表都被禁时退回普通 argmax：宁可重复一次，也不能提前截断
            if (next < 0) next = argmax(logits, MarianBpe.PAD_ID)
            if (next < 0 || next == eosId) break
            out.add(next)
            seq.add(next)
            step++
        }
        return out.toIntArray()
    }

    /** no-repeat n-gram 的默认 n。见 [greedySearch] 的实测说明。 */
    const val DEFAULT_NO_REPEAT_NGRAM = 3

    private val EMPTY_INT = IntArray(0)

    /**
     * no-repeat n-gram 阻断集合：返回「若生成之，就会与已生成序列构成重复 n-gram」的 token。
     *
     * 做法：取出末尾 n-1 个 token 作为前缀，在已生成序列里找所有相同前缀，
     * 把它们后面紧跟的那个 token 收集起来禁止 —— 这样不会出现第二次相同的 n-gram。
     *
     * 这是防「复读机」的关键：贪心解码一旦选错一步，容易自我强化成
     * 「晚安,射出来吧射出来吧射出来吧…」这样的死循环（实测 5.4% 命中率）。
     *
     * @param seq 已生成序列（含起始 token）
     * @return 被禁 token；无禁项时返回空数组（不分配）
     */
    fun bannedNgrams(seq: List<Int>, n: Int): IntArray {
        if (n <= 0 || seq.size < n) return EMPTY_INT
        val start = seq.size - (n - 1)
        var out: IntArray? = null
        var cnt = 0
        for (i in 0..seq.size - n) {
            var same = true
            for (k in 0 until n - 1) {
                if (seq[i + k] != seq[start + k]) {
                    same = false
                    break
                }
            }
            if (!same) continue
            val tok = seq[i + n - 1]
            if (out == null) out = IntArray(4)
            else if (cnt == out.size) out = out.copyOf(cnt * 2)
            out[cnt++] = tok
        }
        return if (out == null) EMPTY_INT else out.copyOf(cnt)
    }

    /**
     * 取最大值的下标，跳过 [blockId]（对应 bad_words_ids）与 [banned]（no-repeat n-gram）。
     * 全被跳过时返回 -1。
     *
     * [banned] 用小的 IntArray 而非 Set：它是每步都调用的热路径，
     * 而禁项通常只有 0~2 个，线性扫描比哈希更快且无装箱分配。
     */
    fun argmax(logits: FloatArray, blockId: Int, banned: IntArray = EMPTY_INT): Int {
        var best = -1
        var bestV = Float.NEGATIVE_INFINITY
        for (i in logits.indices) {
            if (i == blockId) continue
            var skip = false
            for (b in banned) {
                if (b == i) {
                    skip = true
                    break
                }
            }
            if (skip) continue
            val v = logits[i]
            if (v > bestV || (v == bestV && best < 0)) {
                bestV = v
                best = i
            }
        }
        return best
    }

    /**
     * 对 logits 做 log-softmax（跳过 [blockId]），返回 (token, logProb) 的 top-k（按 logProb 降序）。
     * 用 log-softmax 是为了让多步分数可累加比较（beam search 需要）。
     *
     * 实现上用**手动插入维护 top-k**，而不是对整段排序：词表有 65001 项，
     * beam search 每步每束都要调用一次，整段排序的常数太大。
     * 内部拿**原始 logit** 比较即可（减的是同一个 logZ，单调性不变），
     * 这样也避免先转 log 概率再比较带来的精度损失。
     */
    fun logSoftmaxTopK(logits: FloatArray, k: Int, blockId: Int): List<Pair<Int, Double>> {
        if (k <= 0) return emptyList()

        var maxV = Float.NEGATIVE_INFINITY
        for (i in logits.indices) {
            if (i == blockId) continue
            if (logits[i] > maxV) maxV = logits[i]
        }
        if (maxV == Float.NEGATIVE_INFINITY) return emptyList()
        var sum = 0.0
        for (i in logits.indices) {
            if (i == blockId) continue
            sum += exp((logits[i] - maxV).toDouble())
        }
        if (sum <= 0.0) return emptyList()
        val logZ = maxV + ln(sum)

        // 维护按 logit 降序的两个定长数组（插入式，O(V·k) 且无额外分配）
        val topLogit = FloatArray(k) { Float.NEGATIVE_INFINITY }
        val topIdx = IntArray(k) { -1 }
        var filled = 0
        for (i in logits.indices) {
            if (i == blockId) continue
            val v = logits[i]
            if (filled < k) {
                var pos = filled
                while (pos > 0 && topLogit[pos - 1] < v) {
                    topLogit[pos] = topLogit[pos - 1]
                    topIdx[pos] = topIdx[pos - 1]
                    pos--
                }
                topLogit[pos] = v
                topIdx[pos] = i
                filled++
            } else if (v > topLogit[k - 1]) {
                var pos = k - 1
                while (pos > 0 && topLogit[pos - 1] < v) {
                    topLogit[pos] = topLogit[pos - 1]
                    topIdx[pos] = topIdx[pos - 1]
                    pos--
                }
                topLogit[pos] = v
                topIdx[pos] = i
            }
        }

        val out = ArrayList<Pair<Int, Double>>(filled)
        for (i in 0 until filled) out.add(topIdx[i] to (topLogit[i] - logZ))
        return out
    }

    /**
     * beam search（简化实现：不做长度惩罚、不做早停）。
     *
     * 代价：每步对每个 beam 跑一次完整 decoder 前向（模型无 KV cache），
     * 故耗时约为贪心的 numBeams 倍。
     *
     * @return 分数最高那条假设生成的 token（不含起始 token，不含 EOS）。
     */
    fun beamSearch(
        startToken: Int,
        numBeams: Int,
        maxNewTokens: Int,
        eosId: Int,
        nextLogits: (IntArray) -> FloatArray
    ): IntArray {
        if (numBeams <= 1) {
            return greedySearch(startToken, maxNewTokens, eosId, nextLogits = nextLogits)
        }

        class Hyp(val ids: IntArray, val score: Double, val done: Boolean)

        var active = listOf(Hyp(intArrayOf(startToken), 0.0, false))
        val finished = ArrayList<Hyp>()

        var step = 0
        while (step < maxNewTokens && active.isNotEmpty()) {
            val cands = ArrayList<Hyp>(active.size * numBeams)
            for (h in active) {
                val logits = nextLogits(h.ids)
                if (logits.isEmpty()) continue
                val top = logSoftmaxTopK(logits, numBeams, MarianBpe.PAD_ID)
                for ((id, lp) in top) {
                    val nids = h.ids + id
                    val nscore = h.score + lp
                    val cand = Hyp(nids, nscore, id == eosId)
                    if (cand.done) finished.add(cand) else cands.add(cand)
                }
            }
            if (cands.isEmpty()) {
                active = emptyList()
                break
            }
            // 保留分数最高的 numBeams 条继续扩展
            cands.sortByDescending { it.score }
            active = if (cands.size > numBeams) cands.subList(0, numBeams).toList() else cands
            step++
        }

        // 未完成的假设也参与比较（没有 EOS 时只能选它们）
        val pool = ArrayList<Hyp>(finished.size + active.size)
        pool.addAll(finished)
        pool.addAll(active)
        if (pool.isEmpty()) return IntArray(0)
        // 按累加 log 概率选：与上面注释一致，本实现不做长度惩罚
        val best = pool.maxByOrNull { it.score } ?: pool[0]
        // 去掉起始 token 与结尾的 EOS
        val ids = best.ids
        var end = ids.size
        if (end > 0 && ids[end - 1] == eosId) end--
        return if (end <= 1) IntArray(0) else ids.copyOfRange(1, end)
    }
}
