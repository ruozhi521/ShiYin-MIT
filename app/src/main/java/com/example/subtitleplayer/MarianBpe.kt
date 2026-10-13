package com.example.subtitleplayer

import java.text.Normalizer

/**
 * SentencePiece BPE 分词器（纯 Kotlin，无 native 依赖）—— 供离线翻译用。
 *
 * **为什么自己写**：sherpa-onnx 只做 ASR/TTS，**不含** SentencePiece 绑定，
 * 而 OPUS-MT（Marian）必须用 SP 分词。项目纪律是不新增第三方依赖，
 * 故按实测规则在 Kotlin 里复刻。这是 3.0 离线翻译的核心前置件之一。
 *
 * ## 实现依据（全部为实测，非推测）
 *
 * 用 sentencepiece 0.2.2 作真值，逐码点探测 + 穷举验证得出：
 *
 * - 归一化器是 `nmt_nfkc`（不是纯 NFKC）。与 JDK 的 NFKC 相比，只差两处：
 *   - **5 个码点不能走 NFKC**（[SKIP_NFKC]，如 U+FF5E 全角波浪线要保留原样）
 *   - **30 个码点会被整个删除**（[DELETED]，控制符/零宽字符等）
 *   另外有 **30 个码点被当空白折叠**（[WHITESPACE]，比 Java 的 `\s` 宽得多）
 * - BPE 合并得分满足 **`score = 2 - id`**（id 0/1/65000 除外），
 *   故**不需要解析 .spm**，只用 `vocab.json` 即可；合并时取 **id 最小的相邻对**
 * - 连续 `<unk>` 会合并成一个（spm 实测行为）
 *
 * **验证结果**：探得规则后穷举 BMP 全部 65534 个单字符 + 500 句真实日文台本，
 * 本实现与 sentencepiece 的输出 **0 处不一致**（见 [MarianBpeTest]）。
 *
 * ## 用法
 *
 * ```
 * val vocab = MarianBpe.loadVocab(vocabJsonText)     // 1.8 MB，约 0.1 s，建议只加载一次
 * val ids = MarianBpe.encode("ありがとう", vocab)     // 内容 id（不含 EOS）
 * val withEos = MarianBpe.encodeForSource("ありがとう", vocab)  // ★ 供 ONNX 用，末尾补 </s>
 * val text = MarianBpe.decode(ids, vocab)
 * ```
 */
object MarianBpe {

    // ---------------- 常量 ----------------

    /** 特殊 token 的 id（来自 tokenizer_config.json，实测）。 */
    const val EOS_ID = 0          // </s>，源文本末尾必须补它，否则模型输出无限复读
    const val UNK_ID = 1          // <unk>
    const val PAD_ID = 65000      // <pad>，同时也是 decoder_start_token_id

    /** 词表大小（vocab.json 的条目数）。 */
    const val VOCAB_SIZE = 65001

    // ---------------- 归一化规则表（实测） ----------------

    /**
     * 会被 `nmt_nfkc` **整个删除**的码点。
     * 注意 U+000B 与其余控制符不同：Python 的 `isspace()` 认它是空白，
     * 但 spm 实测是**删除**。所以这里照实测，不照 API 语义。
     */
    private val DELETED: BooleanArray = BooleanArray(0x10000).also {
        for (cp in intArrayOf(
            0x0001, 0x0002, 0x0003, 0x0004, 0x0005, 0x0006, 0x0007, 0x0008,
            0x000B, 0x000E, 0x000F, 0x0010, 0x0011, 0x0012, 0x0013, 0x0014,
            0x0015, 0x0016, 0x0017, 0x0018, 0x0019, 0x001A, 0x001B, 0x001C,
            0x001D, 0x001E, 0x001F, 0x007F, 0x008F, 0x009F
        )) it[cp] = true
    }

    /**
     * 会被折叠成**单个空格**的码点。
     * 比 Java 正则的 `\s`（只有 0x20/09/0A/0B/0C/0D）宽得多：
     * 不硬编码这张表、直接用 `\s+` 折叠会漏掉 23 个码点（NBSP、全角空格、行分隔符等）。
     * 注意 0x2581（`▁`）也在表内 —— 输入里出现它同样按空白处理。
     */
    private val WHITESPACE: BooleanArray = BooleanArray(0x10000).also {
        for (cp in intArrayOf(
            0x0009, 0x000A, 0x000C, 0x000D, 0x0020, 0x00A0, 0x1680,
            0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
            0x2008, 0x2009, 0x200A, 0x200B, 0x200C, 0x200E, 0x200F,
            0x2028, 0x2029, 0x202F, 0x205F, 0x2581, 0x3000, 0xFEFF, 0xFFFD
        )) it[cp] = true
    }

    /**
     * 这些码点**不能**交给 NFKC 处理（nmt_nfkc 保留原样）。
     * 最典型的是 U+FF5E（全角波浪线）：NFKC 会把它变成 U+007E `~`，
     * 但 nmt_nfkc 保留，编码结果因此不同（`▁～` vs `▁~`）。
     */
    private val SKIP_NFKC: IntArray = intArrayOf(0x0344, 0xA7F2, 0xA7F3, 0xA7F4, 0xFF5E)

    /** 码点 → 在 [SKIP_NFKC] 中的下标（-1 表示不需要跳过）。 */
    private val SKIP_NFKC_INDEX: IntArray = IntArray(0x10000) { -1 }.also { idx ->
        for ((i, cp) in SKIP_NFKC.withIndex()) if (cp <= 0xFFFF) idx[cp] = i
    }

    /** 占位用的补充平面私有区起点（BMP 之外，不会与真实字符冲突）。 */
    private const val PLACEHOLDER_BASE = 0xF0000

    // ---------------- 词表 ----------------

    /**
     * 词表。用 [loadVocab] 从 vocab.json 构建，构建一次后应复用
     * （1.8 MB JSON，每次重解析约 0.1 s，没必要重复）。
     */
    class Vocab internal constructor(
        internal val pieceToId: HashMap<String, Int>,
        internal val idToPiece: Array<String>
    ) {
        fun size(): Int = idToPiece.size
    }

    /**
     * 解析 vocab.json（`{"<piece>": <id>, ...}`）。
     *
     * 手写解析而不引 JSON 库：格式简单固定（字符串键 + 整数），
     * 且这样单测可在纯 JVM 下跑，不必依赖 Robolectric 的 org.json 桩。
     *
     * 注意 **不能按 JSON 里出现的顺序当 id** —— `<pad>`(65000) 排在 `した`(2) 前面，
     * 必须按 key 对应 value 建立 id → piece 映射。
     */
    fun loadVocab(json: String): Vocab {
        val map = HashMap<String, Int>(VOCAB_SIZE * 2)
        val arr = arrayOfNulls<String>(VOCAB_SIZE)
        var maxId = 0
        var i = json.indexOf('{')
        if (i < 0) throw IllegalArgumentException("vocab.json 格式异常：找不到 {")
        i++
        val n = json.length
        while (i < n) {
            // 跳过空白与逗号
            while (i < n && (json[i] == ' ' || json[i] == '\n' || json[i] == '\r' ||
                        json[i] == '\t' || json[i] == ',')) i++
            if (i >= n || json[i] == '}') break
            if (json[i] != '"') throw IllegalArgumentException("vocab.json 格式异常：位置 $i 期望字符串键")

            // 解析键（JSON 字符串，含 \uXXXX 转义）
            i++
            val sb = StringBuilder(16)
            while (i < n && json[i] != '"') {
                val c = json[i]
                if (c == '\\') {
                    i++
                    when (json[i]) {
                        'u' -> {
                            sb.append(json.substring(i + 1, i + 5).toInt(16).toChar())
                            i += 5
                        }
                        'n' -> { sb.append('\n'); i++ }
                        't' -> { sb.append('\t'); i++ }
                        'r' -> { sb.append('\r'); i++ }
                        'b' -> { sb.append('\b'); i++ }
                        'f' -> { sb.append('\u000C'); i++ }
                        else -> { sb.append(json[i]); i++ }
                    }
                } else {
                    sb.append(c)
                    i++
                }
            }
            i++ // 收尾引号

            // 跳到冒号后的数字
            while (i < n && json[i] != ':') i++
            i++
            while (i < n && json[i] == ' ') i++
            val start = i
            while (i < n && json[i] != ',' && json[i] != '}' && json[i] != '\n') i++
            val id = json.substring(start, i).trim().toInt()

            val piece = sb.toString()
            map[piece] = id
            if (id in 0 until VOCAB_SIZE) arr[id] = piece
            if (id > maxId) maxId = id
        }
        // 兜底：若 vocab.json 位数与 VOCAB_SIZE 不符，按实际最大值收缩
        val out = if (maxId + 1 == VOCAB_SIZE) {
            @Suppress("UNCHECKED_CAST")
            arr as Array<String>
        } else {
            Array(maxId + 1) { arr[it] ?: "" }
        }
        return Vocab(map, out)
    }

    // ---------------- 归一化 ----------------

    /**
     * `nmt_nfkc` 归一化：删除 → NFKC → 再删 → 空白折叠 → 句首补 `▁`、空格转 `▁`。
     *
     * 顺序不能乱：先删再 NFKC，因为 NFKC 可能把某字符**变成**删除表里的字符
     * （所以第 3 步要再删一次）。
     */
    fun normalize(text: String): String {
        if (text.isEmpty()) return ""

        // 1) 删除表 + 为「跳过 NFKC」的字符放占位
        val sb = StringBuilder(text.length + 4)
        val phToOrig = HashMap<Int, Int>(8)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp <= 0xFFFF && DELETED[cp]) continue
            val si = if (cp <= 0xFFFF) SKIP_NFKC_INDEX[cp] else -1
            if (si >= 0) {
                val ph = PLACEHOLDER_BASE + si
                sb.appendCodePoint(ph)
                phToOrig[ph] = cp
            } else {
                sb.appendCodePoint(cp)
            }
        }

        // 2) NFKC
        val nfkc = Normalizer.normalize(sb.toString(), Normalizer.Form.NFKC)

        // 3) 还原占位 + 再删一次（NFKC 可能新产生删除表字符）
        val sb2 = StringBuilder(nfkc.length)
        i = 0
        while (i < nfkc.length) {
            val cp = nfkc.codePointAt(i)
            i += Character.charCount(cp)
            val orig = phToOrig[cp]
            if (orig != null) {
                sb2.appendCodePoint(orig)
                continue
            }
            if (cp <= 0xFFFF && DELETED[cp]) continue
            sb2.appendCodePoint(cp)
        }

        // 4) 空白折叠成单个空格（按 WHITESPACE 表，不用 \s）
        val sb3 = StringBuilder(sb2.length + 1)
        var prevWs = false
        i = 0
        while (i < sb2.length) {
            val cp = sb2.codePointAt(i)
            i += Character.charCount(cp)
            if (cp <= 0xFFFF && WHITESPACE[cp]) {
                if (!prevWs) sb3.append(' ')
                prevWs = true
            } else {
                sb3.appendCodePoint(cp)
                prevWs = false
            }
        }
        // 去首尾空格
        var start = 0
        var end = sb3.length
        while (start < end && sb3[start] == ' ') start++
        while (end > start && sb3[end - 1] == ' ') end--
        if (start >= end) return ""

        // 5) 句首补 ▁（add_dummy_prefix），空格转 ▁（escape_whitespaces）
        val body = sb3.substring(start, end)
        val out = StringBuilder(body.length + 1)
        out.append('\u2581')
        for (ch in body) out.append(if (ch == ' ') '\u2581' else ch)
        return out.toString()
    }

    // ---------------- 编码 ----------------

    /**
     * BPE 编码，返回内容 id（**不含** EOS）。
     *
     * 合并规则：每轮找出「合并后在词表里、且 id 最小」的相邻对并合并，直到无可合并。
     * 取 id 最小等价于取 score 最大（`score = 2 - id`）。
     */
    fun encode(text: String, vocab: Vocab): IntArray {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return IntArray(0)

        // 按 Unicode 码点切成符号（补充平面字符占 2 个 char）
        val syms = ArrayList<String>(normalized.length)
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            val nc = Character.charCount(cp)
            syms.add(normalized.substring(i, i + nc))
            i += nc
        }

        // 反复合并最优相邻对
        while (syms.size > 1) {
            var bestIdx = -1
            var bestId = Int.MAX_VALUE
            for (k in 0 until syms.size - 1) {
                val id = vocab.pieceToId[syms[k] + syms[k + 1]] ?: continue
                if (id < bestId) {
                    bestId = id
                    bestIdx = k
                }
            }
            if (bestIdx < 0) break
            syms[bestIdx] = syms[bestIdx] + syms[bestIdx + 1]
            syms.removeAt(bestIdx + 1)
        }

        // 映射到 id，并把连续 <unk> 合并成一个
        val out = ArrayList<Int>(syms.size)
        for (s in syms) {
            val id = vocab.pieceToId[s] ?: UNK_ID
            if (id == UNK_ID && out.isNotEmpty() && out.last() == UNK_ID) continue
            out.add(id)
        }
        return out.toIntArray()
    }

    /**
     * 编码**源文本**并补 `</s>`（EOS），供 ONNX 推理使用。
     *
     * ⚠️ **必须用这个方法而不是裸 [encode]**：Marian/OPUS-MT 约定源侧要带 EOS，
     * 缺了它模型会输出无限复读（实测 `[11506]` → `ありがとう ありがとう …`；
     * 补成 `[11506, 0]` → `多谢啦`）。`transformers` 的 MarianTokenizer 会自动补，
     * 裸用 sentencepiece 时必须自己来 —— 这是 3.0 最容易踩的坑。
     */
    fun encodeForSource(text: String, vocab: Vocab): IntArray {
        val ids = encode(text, vocab)
        val out = IntArray(ids.size + 1)
        System.arraycopy(ids, 0, out, 0, ids.size)
        out[ids.size] = EOS_ID
        return out
    }

    // ---------------- 解码 ----------------

    /**
     * 解码为目标语言文本：跳过特殊 token，`▁` 还原成空格，并去首尾空白。
     */
    fun decode(ids: IntArray, vocab: Vocab): String {
        val sb = StringBuilder(ids.size * 2)
        for (id in ids) {
            if (id == EOS_ID || id == UNK_ID || id == PAD_ID) continue
            if (id < 0 || id >= vocab.idToPiece.size) continue
            sb.append(vocab.idToPiece[id])
        }
        return sb.toString().replace('\u2581', ' ').trim()
    }
}
