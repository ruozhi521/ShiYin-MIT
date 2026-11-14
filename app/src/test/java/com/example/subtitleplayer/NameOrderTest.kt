package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NameOrder] 的单元测试（3.1）。
 *
 * 对应爱发电用户 2026-11-14 反馈的两条：
 * 1. 「数字排序是以首位数字大小排序而不是整体数字大小排序」
 *    —— 早期六位数 RJ 号排在八位数 0 开头后面。
 * 2. 「汉字首字符会排在字母首字符前面……滑动字母导航只会在首字符汉字的（a-z）之间滑动，
 *    排在最下面的字母首字符则定位不到」—— 排序与索引条用了两套规则。
 *
 * 这里的用例全部来自真机现象，不是凭空构造：先失败、再改对，才有意义。
 *
 * 注意：拼音转换在 JVM 里没有 ICU，所以测试注入一个**固定映射**的假转换器。
 * 这足够验死排序算法本身（自然段、比较方向、全序稳定性），
 * 而「汉字→拼音」的正确性由 Android ICU 保证，不属于本类的职责。
 */
class NameOrderTest {

    /** 假拼音转换：只处理测试里出现的几个字，其余原样返回。 */
    private val fakePinyin: (String) -> String = { s ->
        buildString {
            for (ch in s) {
                when (ch) {
                    '爱' -> append("ai")
                    '周' -> append("zhou")
                    '杰' -> append("jie")
                    '伦' -> append("lun")
                    '安' -> append("an")
                    '本' -> append("ben")
                    else -> append(ch)
                }
            }
        }
    }

    private fun sorted(list: List<String>): List<String> =
        NameOrder.sortByNames(list, fakePinyin) { it }

    // ---------- 一、数字按整体数值 ----------

    @Test
    fun numbers_compareByWholeValue_notByFirstDigit() {
        // 反馈原文场景：RJ 号位数不同，旧的逐位比较会按首字符排（'0' < '6'），
        // 把 RJ01234567 塞到最前面。
        //
        // 按整体数值：999 < 1234 < 600000 < 1234567
        // 注意 RJ01234567 去掉前导零后是 1234567（7 位），比 RJ600000（6 位）大，
        // 所以它排最后 —— 这正是用户要的「按整体大小」。
        val got = sorted(listOf("RJ600000", "RJ01234567", "RJ1234", "RJ999"))
        assertEquals(
            listOf("RJ999", "RJ1234", "RJ600000", "RJ01234567"),
            got
        )
    }

    @Test
    fun pureNumbers_sortNumerically() {
        assertEquals(listOf("2", "9", "10", "100"), sorted(listOf("10", "9", "100", "2")))
    }

    @Test
    fun longerDigitRun_wins() {
        // 位数不同时，位数多的数值必定更大（不需要真的算成数字，避免溢出）
        assertEquals(
            listOf("track2", "track10", "track100"),
            sorted(listOf("track100", "track2", "track10"))
        )
    }

    @Test
    fun leadingZeros_doNotChangeValueOrder() {
        // 前导零不影响数值：007 与 7 同值，但仍要能稳定分出先后（此处长的排后），
        // 关键是它们都排在 8 之前，不会因为「字符 '0' < '8'」而跑到最前面。
        val got = sorted(listOf("08", "007", "7", "9"))
        assertEquals(listOf("7", "007", "08", "9"), got)
    }

    // ---------- 二、汉字与字母统一排序（关键修复）----------

    @Test
    fun hanAndLatin_areOrderedByPinyin_notBlocked() {
        // 3.0.8 的实测结果是 [Apple, Bob, Zebra, 爱, 爱情, 周杰伦]
        // —— 汉字全被排到最后，导致索引条定位错乱。
        // 期望按拼音混排：爱(ai) < 安(an) < Apple(apple) < 本(ben) < Bob(bob) < 周(zhou)
        val got = sorted(listOf("Apple", "爱", "Bob", "爱情", "Zebra", "周杰伦"))
        assertEquals(
            listOf("爱", "爱情", "Apple", "Bob", "Zebra", "周杰伦"),
            got
        )
    }

    @Test
    fun letterOf_usesSameKeyAsSort() {
        // 索引条的分桶必须与排序同源，否则「定位不到」
        assertEquals('A', NameOrder.letterOf("爱", fakePinyin))
        assertEquals('A', NameOrder.letterOf("Apple", fakePinyin))
        assertEquals('B', NameOrder.letterOf("Bob", fakePinyin))
        assertEquals('Z', NameOrder.letterOf("周杰伦", fakePinyin))
    }

    @Test
    fun letterOf_sortsConsistentlyWithListOrder() {
        // 更强的保证：整个列表的首字母序列必须单调不减（A→Z→#）。
        // 只要这条成立，索引条就一定能定位到每一段。
        val names = listOf("Apple", "爱", "Bob", "周杰伦", "安", "Zebra", "本")
        val ordered = sorted(names)
        val letters = ordered.map { NameOrder.letterOf(it, fakePinyin) }
        val lettersWithoutOther = letters.filter { it != AlphaIndexRules.OTHER }
        val sortedLetters = lettersWithoutOther.sorted()
        assertEquals(
            "首字母序列应随排序单调不减，实际 $letters",
            sortedLetters,
            lettersWithoutOther
        )
    }

    @Test
    fun digits_goToOtherBucket() {
        assertEquals(AlphaIndexRules.OTHER, NameOrder.letterOf("007", fakePinyin))
        assertEquals(AlphaIndexRules.OTHER, NameOrder.letterOf("", fakePinyin))
        assertEquals(AlphaIndexRules.OTHER, NameOrder.letterOf("  ", fakePinyin))
        assertEquals(AlphaIndexRules.OTHER, NameOrder.letterOf("★歌", fakePinyin))
    }

    // ---------- 三、自然排序的边界 ----------

    @Test
    fun naturalCompare_prefixShorterFirst() {
        assertTrue(NameOrder.naturalCompare("abc", "abcd") < 0)
        assertTrue(NameOrder.naturalCompare("abcd", "abc") > 0)
    }

    @Test
    fun naturalCompare_equalForIdentical() {
        assertEquals(0, NameOrder.naturalCompare("abc", "abc"))
        assertEquals(0, NameOrder.naturalCompare("", ""))
    }

    @Test
    fun naturalCompare_leadingZeroTieBreakKeepsStrictOrder() {
        // 数值相等时用长度兜底，保证严格全序（否则排序可能抛 contract 异常）
        val a = NameOrder.naturalCompare("007", "7")
        val b = NameOrder.naturalCompare("7", "007")
        assertTrue("同值必须能分出先后，实际 a=$a b=$b", a != 0 && b != 0)
        assertEquals(0, a + b)
    }

    @Test
    fun mixedDigitsAndLetters() {
        assertEquals(
            listOf("a1b2", "a1b10", "a2"),
            sorted(listOf("a2", "a1b10", "a1b2"))
        )
    }

    // ---------- 四、容错 ----------

    @Test
    fun sortKey_trimAndLowercase() {
        assertEquals("apple", NameOrder.sortKey("  Apple  ", null))
        assertEquals("", NameOrder.sortKey(null, null))
        assertEquals("", NameOrder.sortKey("   ", null))
    }

    @Test
    fun pinyinSpacesAreRemoved() {
        // "Zhou Jie Lun" 与 "zhoujielun" 必须同键，否则前缀比较会错位
        assertEquals("zhoujielun", NameOrder.sortKey("周杰伦", fakePinyin))
    }

    @Test
    fun throwingPinyinConverter_fallsBackToRaw() {
        val boom: (String) -> String = { throw IllegalStateException("no icu") }
        // 不能抛出去，必须退回原串
        assertEquals("abc", NameOrder.sortKey("ABC", boom))
    }

    @Test
    fun stableOrderForIdenticalNames() {
        val got = NameOrder.sortByNames(listOf("b", "a", "a", "c"), fakePinyin) { it }
        assertEquals(listOf("a", "a", "b", "c"), got)
    }

    @Test
    fun emptyAndSingleList_returnedAsIs() {
        assertEquals(emptyList<String>(), NameOrder.sortByNames(emptyList<String>(), fakePinyin) { it })
        assertEquals(listOf("x"), NameOrder.sortByNames(listOf("x"), fakePinyin) { it })
    }
}
