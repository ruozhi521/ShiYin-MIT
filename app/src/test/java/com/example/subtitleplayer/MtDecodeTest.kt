package com.example.subtitleplayer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MtDecode] 的解码策略单测 —— 纯逻辑，不加载 ONNX（native 库在 JVM 里跑不了）。
 *
 * 为什么要单独测这块：ONNX 会话本身由模型保证正确，真正容易写错的是
 * 「每步取哪个 token」「beam 如何剪枝」「PAD 有没有被屏蔽」这些逻辑，
 * 而它们一旦错了表现是「译文变复读机」或「输出 <pad>」，很难从现象定位。
 */
class MtDecodeTest {

    private val pad = MarianBpe.PAD_ID
    private val eos = MarianBpe.EOS_ID

    // ---------------- argmax ----------------

    @Test
    fun `argmax 取最大值下标`() {
        assertEquals(2, MtDecode.argmax(floatArrayOf(0f, 1f, 5f, 2f), -1))
        assertEquals(3, MtDecode.argmax(floatArrayOf(-9f, -8f, -7f, -6f), -1))
    }

    /** `<pad>`(=decoder_start) 必须被跳过，否则会被当成普通词生成出来。 */
    @Test
    fun `argmax 跳过被屏蔽的 token`() {
        // 下标 1 的值最大，但它是被屏蔽的那个
        assertEquals(2, MtDecode.argmax(floatArrayOf(0f, 9f, 5f, 1f), 1))
        // 全部被屏蔽时返回 -1
        assertEquals(-1, MtDecode.argmax(floatArrayOf(-1f), 0))
    }

    // ---------------- greedy ----------------

    /** 正常路径：逐步 argmax，遇 EOS 停下，且结果不含起始 token 与 EOS。 */
    @Test
    fun `贪心解码逐步取最大并在 EOS 停止`() {
        // 第 0 步选 2，第 1 步选 3，第 2 步选 EOS
        val script = mapOf(
            1 to floatArrayOf(0f, -1f, 5f, 1f),      // seq=[start] -> 选 2
            2 to floatArrayOf(0f, -1f, 1f, 5f),      // seq=[start,2] -> 选 3
            3 to floatArrayOf(9f, -1f, 1f, 1f),      // seq=[start,2,3] -> 选 EOS(0)
        )
        val out = MtDecode.greedySearch(1, 20, eos) { seq -> script.getValue(seq.size) }
        assertArrayEquals(intArrayOf(2, 3), out)
    }

    /** 到 maxNewTokens 仍未出 EOS，应截断而不是无限跑。 */
    @Test
    fun `贪心解码在步数上限截断`() {
        val out = MtDecode.greedySearch(1, 3, eos) { floatArrayOf(-9f, -9f, 5f, 1f) }
        assertEquals(3, out.size)
        assertArrayEquals(intArrayOf(2, 2, 2), out)
    }

    /** 第一步就 EOS：结果为空（模型认为无需翻译）。 */
    @Test
    fun `贪心解码首步即 EOS 返回空`() {
        val out = MtDecode.greedySearch(1, 20, eos) { floatArrayOf(9f, -1f, 1f, 1f) }
        assertEquals(0, out.size)
    }

    /** logits 为空数组表示上游出错，应立刻停止而非死循环。 */
    @Test
    fun `贪心解码遇空 logits 立即停止`() {
        val out = MtDecode.greedySearch(1, 20, eos) { FloatArray(0) }
        assertEquals(0, out.size)
    }

    /** 起始 token 必须原样作为第一次前向的输入（Marian 的 decoder_start）。 */
    @Test
    fun `首次前向的输入只含起始 token`() {
        val seen = ArrayList<IntArray>()
        MtDecode.greedySearch(pad, 2, eos) { seq ->
            seen.add(seq.copyOf())
            floatArrayOf(0f, 0f, 5f, 1f)
        }
        assertEquals(2, seen.size)
        assertArrayEquals(intArrayOf(pad), seen[0])
        assertArrayEquals(intArrayOf(pad, 2), seen[1])
    }

    // ---------------- logSoftmaxTopK ----------------

    @Test
    fun `logSoftmaxTopK 按概率降序返回`() {
        // logits: index0=1.0, index1=0.0, index2=3.0, index3=2.0, index4=-5.0
        // 降序应为 2(3.0) > 3(2.0) > 0(1.0)
        val logits = floatArrayOf(1f, 0f, 3f, 2f, -5f)
        val top = MtDecode.logSoftmaxTopK(logits, 3, -1)
        assertEquals(3, top.size)
        assertEquals(listOf(2, 3, 0), top.map { it.first })
        // log 概率必须单调递减且为负
        assertTrue(top[0].second > top[1].second)
        assertTrue(top[1].second > top[2].second)
        assertTrue(top.all { it.second < 0.0 })
    }

    @Test
    fun `logSoftmaxTopK 跳过被屏蔽的 token`() {
        val logits = floatArrayOf(1f, 9f, 3f, 2f)
        val top = MtDecode.logSoftmaxTopK(logits, 4, 1)   // 屏蔽下标 1
        assertTrue("被屏蔽的 token 不该出现", top.none { it.first == 1 })
        assertEquals(listOf(2, 3, 0), top.map { it.first })
    }

    /**
     * 数值稳定：logits 整体很大时也不能溢出成 NaN/Inf。
     * （float 在 1e9 量级的间距约 64，`1e9f-1f` 实际等于 `1e9f`，故这里用跨度足够大的值。）
     */
    @Test
    fun `logSoftmaxTopK 在极大值下不溢出`() {
        val logits = floatArrayOf(1e30f, 1e20f, -1e30f)
        val top = MtDecode.logSoftmaxTopK(logits, 2, -1)
        assertEquals(2, top.size)
        assertTrue("不该出现 NaN/Inf", top.all { it.second.isFinite() })
        assertTrue(top[0].second > top[1].second)
        assertEquals(0, top[0].first)
    }

    @Test
    fun `logSoftmaxTopK 对空数组安全`() {
        // 空数组：没有任何候选
        assertTrue(MtDecode.logSoftmaxTopK(FloatArray(0), 3, -1).isEmpty())
        // 屏蔽掉仅有的一个有效项后，无候选可选
        assertTrue(MtDecode.logSoftmaxTopK(floatArrayOf(5f), 3, 0).isEmpty())
        // 两个元素屏蔽一个，应只剩另一个
        val one = MtDecode.logSoftmaxTopK(floatArrayOf(5f, 1f), 3, 0)
        assertEquals(1, one.size)
        assertEquals(1, one[0].first)
        // k 大于可用项数时按实际数量返回
        assertEquals(2, MtDecode.logSoftmaxTopK(floatArrayOf(5f, 1f), 9, -1).size)
    }

    // ---------------- beam search ----------------

    /**
     * beam 应能找到贪心错过的更优路径。
     *
     * 构造：第一步 token2 的即时分数略高（贪心必选它），但它第二步**没有好选择**
     * （logits 均匀 → logP≈-ln5）；token3 第一步略低，第二步却直接给出高分 EOS。
     * 故按总路径分数比，token3 更优 —— beam 应选 3、贪心选 2。
     */
    @Test
    fun `beam search 能找到优于贪心的路径`() {
        val start = 1
        fun logitsFor(seq: IntArray): FloatArray = when (seq.size) {
            // 第一步：2 分略高于 3；EOS 与其它 token 都压到 -9，第一步不会结束
            1 -> floatArrayOf(-9f, -9f, 5f, 4f, -9f)
            // 第二步：路径2 无好选择（均匀，logP≈-1.61）；路径3 直接高分 EOS
            2 -> if (seq[1] == 2) floatArrayOf(0f, 0f, 0f, 0f, 0f)
            else floatArrayOf(0f, -9f, -9f, -9f, -9f)
            else -> FloatArray(0)
        }
        val greedy = MtDecode.greedySearch(start, 5, eos) { logitsFor(it) }
        val beam = MtDecode.beamSearch(start, 2, 5, eos) { logitsFor(it) }
        assertArrayEquals("贪心应选即时分更高的 2", intArrayOf(2), greedy)
        assertArrayEquals("beam 应选整体更优的 3", intArrayOf(3), beam)
    }

    /** beam=1 时退化为贪心（避免两套实现行为不一致）。 */
    @Test
    fun `beam 为 1 时等价于贪心`() {
        fun logitsFor(seq: IntArray): FloatArray = when (seq.size) {
            1 -> floatArrayOf(0f, 0f, 5f, 4f, 0f)
            2 -> floatArrayOf(9f, 0f, 1f, 1f, 1f)
            else -> FloatArray(0)
        }
        val greedy = MtDecode.greedySearch(1, 5, eos) { logitsFor(it) }
        val beam = MtDecode.beamSearch(1, 1, 5, eos) { logitsFor(it) }
        assertArrayEquals(greedy, beam)
    }

    /** 结果不得包含起始 token，也不得带尾部 EOS。 */
    @Test
    fun `beam 结果剔除起始与 EOS`() {
        val beam = MtDecode.beamSearch(1, 3, 6, eos) { seq ->
            if (seq.size == 1) floatArrayOf(0f, 0f, 5f, 4f, 1f)
            else floatArrayOf(9f, 0f, 1f, 1f, 1f)   // 立刻 EOS
        }
        assertTrue("不该以起始 token 开头", beam.none { it == 1 })
        assertTrue("不该含 EOS", beam.none { it == eos })
    }

    /** 上游一直出错（空 logits）时不能死循环。 */
    @Test
    fun `beam 遇空 logits 终止`() {
        val beam = MtDecode.beamSearch(1, 4, 100, eos) { FloatArray(0) }
        assertEquals(0, beam.size)
    }

    /** beam 的每一步都要跳过 PAD。 */
    @Test
    fun `beam 跳过被屏蔽的 token`() {
        val beam = MtDecode.beamSearch(1, 2, 4, eos) { seq ->
            if (seq.size == 1) floatArrayOf(0f, 100f, 5f, 4f, 1f)  // PAD 值最大
            else floatArrayOf(9f, 100f, 1f, 1f, 1f)
        }
        assertTrue("不该生成 <pad>", beam.none { it == pad })
    }

    // ---------------- no-repeat n-gram（3.0 防复读）----------------

    /**
     * 阻断集合的语义：末尾 (n-1) 个 token 作前缀，凡是「已出现过的相同前缀」
     * 后面紧跟的 token 都要禁止 —— 否则会构成第二次相同的 n-gram。
     */
    @Test
    fun `no-repeat 阻断会构成重复的 token`() {
        // seq=[1,2,3,2,3] 末尾前缀=(2,3)，在 i=1 处已出现 → 再选 2 会重复 3-gram
        assertEquals(listOf(2), MtDecode.bannedNgrams(listOf(1, 2, 3, 2, 3), 3).toList())
        // 无相同前缀 → 不禁任何 token
        assertEquals(0, MtDecode.bannedNgrams(listOf(1, 2, 3, 4, 5), 3).size)
    }

    /** 边界：序列短于 n、或 n<=0（关闭）时，都不该禁任何 token。 */
    @Test
    fun `no-repeat 在序列过短或关闭时不禁 token`() {
        assertEquals(0, MtDecode.bannedNgrams(listOf(1, 2), 3).size)
        assertEquals(0, MtDecode.bannedNgrams(emptyList(), 3).size)
        assertEquals(0, MtDecode.bannedNgrams(listOf(1, 2, 3, 2, 3), 0).size)
        assertEquals(0, MtDecode.bannedNgrams(listOf(1, 2, 3, 2, 3), -1).size)
    }

    /**
     * 端到端：这是**真实缺陷的回归测试** —— token 2 恒为最大时纯贪心会一直吐 2
     * （真机上表现为「晚安,射出来吧射出来吧射出来吧…」，37 句里命中 2 句）。
     * 开启 no-repeat 后必须打断这个循环。
     */
    @Test
    fun `贪心开启 no-repeat 后不再无限复读`() {
        // eosId 传 -1（不存在）以免提前停止；blockId=PAD_ID 超出本词表范围，无影响
        val flat = { _: IntArray -> floatArrayOf(-9f, -9f, 5f, 1f) }
        val raw = MtDecode.greedySearch(1, 12, -1, 0, flat)
        val fixed = MtDecode.greedySearch(1, 12, -1, MtDecode.DEFAULT_NO_REPEAT_NGRAM, flat)

        assertTrue("纯贪心本应持续复读，实际=$raw", longestRun(raw, 2) >= 4)
        assertTrue(
            "开 no-repeat 后不该出现 4 连以上重复，实际=$fixed",
            longestRun(fixed, 2) <= 3
        )
    }

    /** 统计 [v] 在 [seq] 中最长的连续出现次数。 */
    private fun longestRun(seq: IntArray, v: Int): Int {
        var best = 0
        var cur = 0
        for (x in seq) {
            if (x == v) {
                cur++
                if (cur > best) best = cur
            } else {
                cur = 0
            }
        }
        return best
    }

    /** 关闭 no-repeat（n=0）时，行为必须与旧版贪心完全一致，避免回归。 */
    @Test
    fun `no-repeat 关闭时与旧行为一致`() {
        val script = mapOf(
            1 to floatArrayOf(0f, -1f, 5f, 1f),
            2 to floatArrayOf(0f, -1f, 1f, 5f),
            3 to floatArrayOf(9f, -1f, 1f, 1f),
        )
        val out = MtDecode.greedySearch(1, 20, eos, 0) { seq -> script.getValue(seq.size) }
        assertArrayEquals(intArrayOf(2, 3), out)
    }
}
