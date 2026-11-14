package com.example.subtitleplayer

import java.util.Locale

/**
 * 名称排序与首字母索引的**统一规则**（3.1）。
 *
 * ## 为什么需要把这两件事合到一个文件
 *
 * 3.0.8 的实测缺陷（你在爱发电反馈的两条）本质是同一个病：**排序和索引条各用一套规则**。
 *
 * - 排序走 `Collator(Locale.CHINA)`。实测（JDK 的 RuleBasedCollator，Android 的 ICU
 *   collator 行为一致）：
 *   ```
 *   [Apple, Bob, Zebra, 爱, 爱情, 周杰伦]
 *   ```
 *   汉字**全部排在拉丁字母之后**。
 * - 首字母条走 `android.icu.text.AlphabeticIndex` 拼音分桶：「爱」落在 A 区、「周」落在 Z 区。
 *
 * 两边对「爱」的位置判断完全相反 —— 索引条以为它在列表最前面，实际在最后面。
 * 于是滑动索引条时只能在汉字那一小段里跳（那些恰好都被分到某些字母），
 * 「排在最下面的字母首字符定位不到」。
 *
 * 修法不是调参数，而是**让两者共用同一个键**：先算出拼音排序键，
 * 排序按它比较，首字母也取它的第一个字符。物理上不可能再不一致。
 *
 * ## 第二个缺陷：数字按逐位比较
 *
 * `Collator` 不比较数值，实测：
 * ```
 * [RJ01234567, RJ1234, RJ600000, RJ999]   // 期望 RJ999 < RJ1234 < RJ01234567 < RJ600000
 * [10, 100, 2, 9]                          // 期望 2 < 9 < 10 < 100
 * ```
 * 所以这里自带**自然排序**：把字符串切成「数字段 / 非数字段」，数字段按整体数值比。
 *
 * ## 为什么拼音转换要由外部注入
 *
 * 汉字转拼音要用 `android.icu.text.Transliterator`，纯 JVM 单测里没有它。
 * 沿用工程里 [AlphaIndexRules] 已有的做法：把转换函数做成参数，运行时注入 ICU 版，
 * 单测注入固定映射 —— 排序/自然段/大小写这些**纯逻辑**在 JVM 上就能验死。
 */
object NameOrder {

    /**
     * 汉字→拼音的转换器（**懒初始化**）。
     *
     * ## 为什么改成懒加载
     *
     * 上一版在 `onCreate` 里直接调 `installIcuPinyin()`，也就是**每次启动都在主线程**
     * 初始化 ICU：`Transliterator.getInstance("Han-Latin")` 首次要加载并编译转写规则，
     * 这一步本身就慢。而绝大多数启动场景根本用不到拼音（没开「按名称排序」、
     * 列表项数不到显示索引条的门槛）—— 白付一次初始化成本。
     *
     * 现在改成用到时才初始化：真正要排序/建索引时才会付这一次钱，
     * 且那时通常已经在后台线程（扫描）或列表已有数据（UI 不敏感）。
     *
     * ## 可写性
     *
     * 单测要注入固定映射，所以保持 var。赋值即视为「已就绪」，
     * 不会再触发 ICU 初始化。
     */
    var pinyinProvider: ((String) -> String)?
        get() {
            ensurePinyin()
            return provider
        }
        set(value) {
            provider = value
            pinyinReady = true
            // ⚠️ 拼音源一变，之前缓存的排序键就全废了（那是按旧源算的）。
            // 不清的话：单测里 install()/restore() 切换源之后，仍会读到上一条用例
            // 留下的旧键；生产里若将来支持运行时切换（比如换语言），排序会静默错乱。
            synchronized(keyCache) { keyCache.clear() }
        }

    @Volatile
    private var provider: ((String) -> String)? = null

    @Volatile
    private var pinyinReady = false

    /** 首次访问时才创建 ICU 转写器；失败则退化为 null（原串排序），不影响可用性。 */
    private fun ensurePinyin() {
        if (pinyinReady) return
        synchronized(this) {
            if (pinyinReady) return
            provider = createIcuPinyin()
            pinyinReady = true
        }
    }

    private fun createIcuPinyin(): ((String) -> String)? = try {
        val t = android.icu.text.Transliterator.getInstance("Han-Latin")
        // ⚠️ 必须显式声明类型并**单独赋值再返回**：直接写
        // `{ s -> ... }` 紧跟上一行会被 Kotlin 解析成上一行的 trailing lambda
        // （getInstance 有重载，于是报出一堆「Int 期望」的怪错）。
        val f: (String) -> String = { s ->
            try {
                t.transliterate(s)
            } catch (e: Throwable) {
                s
            }
        }
        f
    } catch (e: Throwable) {
        // ICU 不可用（极少见）：退化为原串排序，不影响可用性
        PlaybackLog.log("pinyin unavailable: ${e.javaClass.simpleName}")
        null
    }

    /**
     * 排序键缓存。
     *
     * 为什么必须有：一次 `LibraryCache.load()` 里同一批歌名会被排序**两遍**
     * （一遍按文件夹分组内排序、一遍 allSongs 整体排序），没有缓存就是两次全量转写。
     * 歌名重复度还很高（同名不同格式、同专辑多曲），缓存命中率不低。
     *
     * 容量设上限：库可能有几万首，不能无限增长。超限直接整体清空 ——
     * 排序是批量操作，清空后重新填充比维护 LRU 链表更简单，也不会错。
     */
    private val keyCache = HashMap<String, String>(512)
    private const val KEY_CACHE_MAX = 4096

    /**
     * 带缓存的排序键。UI 线程（索引条）与后台线程（扫描/读缓存）都会调用，
     * 所以加锁 —— 排序本身是纯函数，锁粒度放在缓存读写上即可。
     */
    fun keyOf(name: String?): String {
        val src = name?.trim().orEmpty()
        if (src.isEmpty()) return ""
        synchronized(keyCache) {
            keyCache[src]?.let { return it }
        }
        val v = sortKey(src, pinyinProvider)
        synchronized(keyCache) {
            if (keyCache.size > KEY_CACHE_MAX) keyCache.clear()
            keyCache[src] = v
        }
        return v
    }

    /** 用全局拼音源取首字母（索引条用，与排序同源）。走缓存，避免每次刷新重转全部名字。 */
    fun letterOf(name: String?): Char {
        val key = keyOf(name)
        val first = key.firstOrNull() ?: return AlphaIndexRules.OTHER
        val upper = first.uppercaseChar()
        return if (upper in 'A'..'Z') upper else AlphaIndexRules.OTHER
    }

    /**
     * 用全局拼音源排序（走缓存，每首歌名只转一次、跨多次排序复用）。
     *
     * ⚠️ 实现里用 [keyOf] 而不是 [sortKey]：后者是纯函数、没有缓存，
     * 而 `LibraryCache.load()` 会把同一批歌名连排两遍（分组内 + allSongs），
     * 走纯函数等于全量转写两遍 —— 启动变慢的主因之一就在这里。
     */
    fun <T> sortedByNames(items: List<T>, nameOf: (T) -> String?): List<T> {
        if (items.size <= 1) return items
        val keyed = items.map { it to keyOf(nameOf(it)) }
        return keyed.sortedWith(Comparator { p1, p2 ->
            val c = naturalCompare(p1.second, p2.second)
            if (c != 0) c else (nameOf(p1.first) ?: "").compareTo(nameOf(p2.first) ?: "")
        }).map { it.first }
    }

    /**
     * 用全局拼音源构造比较器。**每次比较都会重算两侧的键**，
     * 仅适合元素很少的场合（如树形目录单层几十个节点）；
     * 大列表请用 [sortedByNames]。
     */
    fun nameComparator(): Comparator<String> = Comparator { a, b ->
        val c = naturalCompare(keyOf(a), keyOf(b))
        if (c != 0) c else a.compareTo(b)
    }

    /**
     * 生成排序键：汉字转拼音、统一小写。
     *
     * @param toPinyin 汉字→拼音的转换（如「周杰伦」→"Zhou Jie Lun"）。
     *        传 null 时退化为原串（ICU 不可用的极端情况），此时中文按码位排，
     *        至少不会崩，也与 3.0.8 之前的行为一致。
     */
    fun sortKey(name: String?, toPinyin: ((String) -> String)?): String {
        val src = name?.trim().orEmpty()
        if (src.isEmpty()) return ""
        // ⚠️ 只在**含汉字**时才走 ICU 转写。
        //
        // 这一条是性能命门：`Transliterator.transliterate()` 比字符比较慢约三个数量级，
        // 而音乐库里大量是纯英文、纯假名、纯数字的歌名（日文歌尤其多）。
        // 不加判断的话，几千首歌的库在主线程转写几千次 —— 实机表现就是启动明显变慢。
        // 而纯英文/假名本来没有汉字可转，转了也是原样返回，纯属浪费。
        val hasHan = src.any { isHan(it) }
        val converted = if (toPinyin != null && hasHan) {
            try {
                toPinyin(src)
            } catch (e: Throwable) {
                // 转换失败不能影响排序，退回原串
                src
            }
        } else {
            src
        }
        // 去掉拼音音调残留的空白（"Zhou Jie Lun" → "zhoujielun"）。
        // 不去空格的话「周杰伦」会以 "zhou jie lun" 参与比较，空格会影响
        // 「周杰伦」与「周杰」这类前缀关系的相对位置。
        return converted.lowercase(Locale.ROOT).filterNot { it.isWhitespace() }
    }

    /**
     * 是否是汉字（含扩展 A 区与常用兼容区）。
     *
     * 只需要覆盖实际会出现在歌曲名里的范围，不用穷举 Unicode 全部 CJK 区段：
     * 日文假名（3040-30FF）、拉丁、数字都不算汉字 —— 它们转写后仍是自己，
     * 送进 ICU 纯属浪费。
     */
    private fun isHan(c: Char): Boolean {
        val code = c.code
        return (code in 0x4E00..0x9FFF) ||   // CJK 统一表意文字
            (code in 0x3400..0x4DBF) ||       // 扩展 A
            (code in 0xF900..0xFAFF)          // 兼容表意文字
    }

    /**
     * 首字母：取排序键的第一个字母/数字。
     *
     * 与排序**同源**（都来自 [sortKey]），所以索引条的分桶与列表实际顺序一定对得上。
     * 数字归入 [AlphaIndexRules.OTHER]（与通讯录习惯一致，`#` 放最后）。
     */
    fun letterOf(name: String?, toPinyin: ((String) -> String)?): Char {
        val key = sortKey(name, toPinyin)
        val first = key.firstOrNull() ?: return AlphaIndexRules.OTHER
        val upper = first.uppercaseChar()
        return if (upper in 'A'..'Z') upper else AlphaIndexRules.OTHER
    }

    /**
     * 自然排序：数字段按整体数值，其余按字符。
     *
     * 例：
     * - `RJ999` < `RJ1234` < `RJ01234567` < `RJ600000`（数值 999 < 1234 < 1234567 < 600000）
     * - `2` < `9` < `10` < `100`
     *
     * 前导零不参与数值判断（`RJ01234567` 与 `RJ1234567` 视为同值），
     * 同值时按原串长度再按字典序兜底，保证严格全序 —— 否则会触发
     * `Comparison method violates its general contract!`。
     */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                // 各自取出完整数字段
                val si = i
                while (i < a.length && a[i].isDigit()) i++
                val sj = j
                while (j < b.length && b[j].isDigit()) j++
                val segA = a.substring(si, i)
                val segB = b.substring(sj, j)
                val numA = segA.trimStart('0')
                val numB = segB.trimStart('0')
                // 有效位数不同 → 位数多的数值更大
                if (numA.length != numB.length) return numA.length - numB.length
                // 位数相同 → 字典序即数值序
                val c = numA.compareTo(numB)
                if (c != 0) return c
                // 数值相等：前导零多的排后面，保证全序稳定
                if (segA.length != segB.length) return segA.length - segB.length
            } else {
                if (ca != cb) return ca.compareTo(cb)
                i++
                j++
            }
        }
        // 前缀关系：短的在前
        return (a.length - i) - (b.length - j)
    }

    /**
     * 按自然排序比较两个名字（先转排序键再比）。
     *
     * ⚠️ 排序大量元素时**不要**用它直接当 Comparator：每次比较都会重新算拼音，
     * O(n log n) 次转换会把性能拖垮。请用 [sortedNames] / [namesComparator] 的预计算版本。
     */
    fun compareNames(a: String?, b: String?, toPinyin: ((String) -> String)?): Int {
        val ka = sortKey(a, toPinyin)
        val kb = sortKey(b, toPinyin)
        val c = naturalCompare(ka, kb)
        // 键完全相同时用原串兜底，保证严格全序
        if (c != 0) return c
        return (a ?: "").compareTo(b ?: "")
    }

    /**
     * 预计算排序键后排序（推荐用法）。
     *
     * @param nameOf 从元素取出用于排序的名字
     */
    fun <T> sortByNames(
        items: List<T>,
        toPinyin: ((String) -> String)?,
        nameOf: (T) -> String?
    ): List<T> {
        if (items.size <= 1) return items
        // 每个元素只转换一次拼音：先建 key，再排序。
        // ⚠️ 不能写成 `sortedWith { (x, kx), (y, ky) -> ... }` ——
        // Comparator 的 lambda 是**两个独立参数**，不是单参数 Pair，无法结构解构。
        val keyed = items.map { it to sortKey(nameOf(it), toPinyin) }
        return keyed.sortedWith(Comparator { p1, p2 ->
            val c = naturalCompare(p1.second, p2.second)
            if (c != 0) c else (nameOf(p1.first) ?: "").compareTo(nameOf(p2.first) ?: "")
        }).map { it.first }
    }
}
