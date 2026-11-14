package com.example.subtitleplayer

import org.junit.After
import org.junit.Before

/**
 * 单测用的拼音转换器（3.1）。
 *
 * ## 为什么需要它
 *
 * 3.1 起排序与首字母索引都依赖 [NameOrder.pinyinProvider]（汉字→拼音）。
 * 生产环境用 `android.icu.text.Transliterator` 注入，但**纯 JVM 单测里没有 ICU**，
 * provider 为 null 时会退化成「按 Unicode 码位排」—— 于是
 * 「周」(U+5468) 会排在「安」(U+5B89) 前面，所有断言拼音序的测试全部失败。
 *
 * 那些断言本身是对的（产品就该按拼音排），错的是测试环境缺少拼音源。
 * 所以正确做法是**给测试补上拼音源**，而不是把断言改宽松 ——
 * 改断言会让测试永远通过，却再也验不出真实行为。
 *
 * ## 为什么用固定映射而不是真的 ICU
 *
 * 测试只关心「排序是否按拼音走」这个**行为**，不关心某个字的拼音准不准
 * （那是 ICU 的职责）。固定映射确定性强、不依赖 Robolectric 对 android.icu
 * 的支持程度，也不会因为 ICU 版本变化让测试飘。
 *
 * 用到哪个字就加哪个字，`install()` 里集中维护。
 */
object TestPinyin {

    /** 测试里出现过、需要转拼音的字。新增用例时在这里补。 */
    private val MAP: Map<Char, String> = mapOf(
        '周' to "zhou", '杰' to "jie", '伦' to "lun",
        '安' to "an", '室' to "shi", '奈' to "nai", '美' to "mei", '惠' to "hui",
        '和' to "he", '桥' to "qiao",
        '林' to "lin", '俊' to "jun",
        '爱' to "ai", '情' to "qing",
        '本' to "ben",
        '歌' to "ge", '手' to "shou"
    )

    val provider: (String) -> String = { s ->
        buildString {
            for (ch in s) append(MAP[ch] ?: ch.toString())
        }
    }

    /** 在 [Before] 里调用：装上拼音源。 */
    fun install() {
        NameOrder.pinyinProvider = provider
    }

    /**
     * 在 [After] 里调用：恢复成 null。
     *
     * ⚠️ 必须清掉：测试类共享同一个 JVM 与 [NameOrder] 单例，
     * 留着会污染同一批次里其他测试的行为。
     */
    fun restore() {
        NameOrder.pinyinProvider = null
    }
}

/**
 * 让测试类一行接入：[NameOrder.pinyinProvider] 在每条用例前后自动装/卸。
 *
 * 用法：`class XxxTest : PinyinTestBase() { ... }`
 * （JUnit4 的 @Before/@After 在父类里同样生效）
 */
abstract class PinyinTestBase {
    @Before
    fun installTestPinyin() = TestPinyin.install()

    @After
    fun restoreTestPinyin() = TestPinyin.restore()
}
