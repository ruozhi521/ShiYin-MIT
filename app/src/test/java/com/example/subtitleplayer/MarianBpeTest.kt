package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MarianBpe] 的正确性验证 —— 与官方 sentencepiece 的真值逐句对比。
 *
 * 真值由本地 venv 的 sentencepiece 0.2.2 生成（见 `_mt_testdata/` 的生成脚本），
 * 用例全部为**自造句子**，不含任何训练语料（训练数据源自汉化组译文，不可分发）。
 *
 * 纯 JVM 即可跑：MarianBpe 不依赖任何 Android 类型，故不需要 Robolectric。
 */
class MarianBpeTest {

    private val vocab: MarianBpe.Vocab by lazy {
        val text = resource("mt/vocab.json")
        MarianBpe.loadVocab(text)
    }

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: error("测试资源缺失：$name")

    /** 读取 TSV（`键\t值`），返回列表。 */
    private fun tsv(name: String): List<Pair<String, String>> =
        resource(name).lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val p = line.split('\t')
                p[0] to (p.getOrNull(1) ?: "")
            }.toList()

    // ---------------- 词表 ----------------

    @Test
    fun `词表大小与特殊 token 正确`() {
        assertEquals(MarianBpe.VOCAB_SIZE, vocab.size())
        assertEquals(0, MarianBpe.EOS_ID)
        assertEquals(1, MarianBpe.UNK_ID)
        assertEquals(65000, MarianBpe.PAD_ID)
    }

    /** vocab.json 里 `<pad>`(65000) 排在 `した`(2) 之前，必须按 value 当 id，不能按出现顺序。 */
    @Test
    fun `id 不被 JSON 键顺序干扰`() {
        val ids = MarianBpe.encode("ありがとう", vocab)
        assertEquals(listOf(11506), ids.toList())
    }

    // ---------------- 编码：与 sentencepiece 真值全量对比 ----------------

    @Test
    fun `编码与 sentencepiece 完全一致`() {
        val cases = tsv("mt/encode_cases.tsv")
        assertTrue("用例数太少，疑似资源没读到", cases.size >= 50)

        var failed = 0
        val errs = StringBuilder()
        for ((text, expectStr) in cases) {
            val expect = if (expectStr.isBlank()) emptyList()
            else expectStr.split(',').map { it.toInt() }
            val actual = MarianBpe.encode(text, vocab).toList()
            if (actual != expect) {
                failed++
                if (failed <= 10) {
                    errs.append("\n  文本=${text.map { it.code.toString(16) }}")
                    errs.append("\n    期望=$expect")
                    errs.append("\n    实际=$actual")
                }
            }
        }
        assertEquals("编码不一致 $failed/${cases.size} 处$errs", 0, failed)
    }

    // ---------------- 解码 ----------------

    @Test
    fun `解码与 sentencepiece 完全一致`() {
        val cases = tsv("mt/decode_cases.tsv")
        assertTrue(cases.size >= 5)
        var failed = 0
        val errs = StringBuilder()
        for ((idsStr, expect) in cases) {
            val ids = idsStr.split(',').filter { it.isNotBlank() }.map { it.toInt() }.toIntArray()
            val actual = MarianBpe.decode(ids, vocab)
            if (actual != expect) {
                failed++
                if (failed <= 10) errs.append("\n  ids=$idsStr 期望=$expect 实际=$actual")
            }
        }
        assertEquals("解码不一致 $failed/${cases.size} 处$errs", 0, failed)
    }

    /** 编码 → 解码 往返：对目标语言侧应还原原文（▁→空格 后）。 */
    @Test
    fun `解码还原中文`() {
        val cases = tsv("mt/decode_cases.tsv")
        for ((_, expect) in cases) {
            val ids = MarianBpe.encode(expect, vocab)
            assertEquals(expect, MarianBpe.decode(ids, vocab))
        }
    }

    // ---------------- 规则表（实测得出的三张表，防止改错） ----------------

    @Test
    fun `归一化规则表与实测一致`() {
        val rules = tsv("mt/rules.tsv").toMap()
        assertEquals("删除表条数", 30, rules.getValue("deleted").split(',').size)
        assertEquals("空白表条数", 30, rules.getValue("whitespace").split(',').size)
        assertEquals("跳过NFKC条数", 5, rules.getValue("skip_nfkc").split(',').size)
    }

    /** U+FF5E 全角波浪线：nmt_nfkc **保留**，不能交给 JDK 的 NFKC 变成 `~`。 */
    @Test
    fun `全角波浪线不走 NFKC`() {
        assertEquals(listOf(4420), MarianBpe.encode("\uFF5E", vocab).toList())
        // 纯 NFKC 会得到 12676（▁~），若实现错了会在这里暴露
        assertNotEquals(listOf(12676), MarianBpe.encode("\uFF5E", vocab).toList())
    }

    /** 零宽字符在空白表里：单独出现时被折叠掉，编码为空。 */
    @Test
    fun `零宽与空白字符被折叠`() {
        assertEquals(0, MarianBpe.encode("\u200B", vocab).size)
        assertEquals(0, MarianBpe.encode("\uFEFF", vocab).size)
        assertEquals(0, MarianBpe.encode("\u00A0", vocab).size)
        assertEquals(0, MarianBpe.encode("\u3000", vocab).size)
        assertEquals(0, MarianBpe.encode("   ", vocab).size)
        assertEquals(0, MarianBpe.encode("", vocab).size)
    }

    /** 删除表里的控制符被整个删掉，不产生 unk。 */
    @Test
    fun `删除表字符被剔除`() {
        // U+0007 应被删除，前后字符照常编码
        assertEquals(
            MarianBpe.encode("ありがとう", vocab).toList(),
            MarianBpe.encode("あり\u0007がとう", vocab).toList()
        )
        // U+000B：Python 的 isspace 认它是空白，但 spm 实测是「删除」——按删除处理
        assertEquals(
            MarianBpe.encode("ありがとう", vocab).toList(),
            MarianBpe.encode("あり\u000Bがとう", vocab).toList()
        )
    }

    /** 半角片假名要经 NFKC 展开后再分词。 */
    @Test
    fun `半角片假名经 NFKC 展开`() {
        // ｷﾞｬｰ = U+FF77 U+FF9E U+FF6C U+FF70（4 个半角片假名字符，NFKC 后成「ギャー」）
        val half = MarianBpe.encode("\uFF77\uFF9E\uFF6C\uFF70", vocab).toList()
        assertTrue("半角片假名不该编码为空", half.isNotEmpty())
        assertEquals(listOf(53808, 10752, 53846), half)

        // 浊点单独成字符（ｶﾞ = ｶ + 浊点），NFKC 会合成为「ガ」
        assertEquals(listOf(5924), MarianBpe.encode("\uFF76\uFF9E", vocab).toList())
        // 促音・长音符
        assertEquals(listOf(7317), MarianBpe.encode("\uFF83\uFF9A\uFF8B\uFF9E", vocab).toList())
    }

    /** 句首必须补 ▁（add_dummy_prefix），否则首 token 会错。 */
    @Test
    fun `句首补下划线`() {
        val ids = MarianBpe.encode("ありがとう", vocab).toList()
        assertEquals(listOf(11506), ids)
        // 11506 对应的 piece 应以 ▁ 开头
        assertTrue("首个 piece 应以 ▁ 开头", vocab.idToPiece[11506].startsWith("\u2581"))
    }

    /** 长串里的空格要转成 ▁，多个连续空白折叠成一个。 */
    @Test
    fun `空格转下划线且折叠`() {
        val a = MarianBpe.encode("A B", vocab).toList()
        val b = MarianBpe.encode("A    B", vocab).toList()
        val c = MarianBpe.encode("A\u3000B", vocab).toList()   // 全角空格
        assertEquals(a, b)
        assertEquals(a, c)
    }

    // ---------------- encodeForSource：EOS 是硬性要求 ----------------

    /**
     * ⚠️ 3.0 最关键的约定：源文本末尾必须补 `</s>`。
     * 缺了它模型输出无限复读（实测），所以这里必须锁死。
     */
    @Test
    fun `源编码末尾补 EOS`() {
        val plain = MarianBpe.encode("ありがとう", vocab).toList()
        val forSrc = MarianBpe.encodeForSource("ありがとう", vocab).toList()
        assertEquals(plain, forSrc.dropLast(1))
        assertEquals(MarianBpe.EOS_ID, forSrc.last())

        // 空文本也要得到「仅 EOS」
        val emptySrc = MarianBpe.encodeForSource("", vocab).toList()
        assertEquals(listOf(MarianBpe.EOS_ID), emptySrc)
    }

    // ---------------- 稳定性 ----------------

    /** 相同输入多次编码结果必须一致（无状态泄漏、无随机性）。 */
    @Test
    fun `编码可重复`() {
        val t = "こんにちは、今日はいい天気ですね"
        val first = MarianBpe.encode(t, vocab).toList()
        repeat(5) { assertEquals(first, MarianBpe.encode(t, vocab).toList()) }
    }

    /** 纯空白文本不该崩、且编码为空。 */
    @Test
    fun `空白与边界输入不崩`() {
        for (t in listOf("", " ", "\n", "\t", "\u3000\u3000", "\u200B\u200B")) {
            assertTrue("空白输入应得空结果：${t.map { it.code }}",
                MarianBpe.encode(t, vocab).isEmpty())
        }
        // 补充平面字符（emoji 之类）走 unk，但不能抛异常
        val s = MarianBpe.encode("あ\uD83D\uDE00い", vocab)
        assertTrue(s.isNotEmpty())
    }
}
