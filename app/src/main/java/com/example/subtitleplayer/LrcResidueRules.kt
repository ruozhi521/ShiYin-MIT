package com.example.subtitleplayer

import java.util.regex.Pattern

/**
 * 「SAF 自动序号残留」识别规则（2.14）。
 *
 * 背景：SAF 的 `DocumentsContract.createDocument` **同名不覆盖**——同目录已有
 * `歌名.lrc` 时再建一个，系统会自动改名为 `歌名(1).lrc`，再来一次是 `歌名(2).lrc`。
 * 用户反复点「生成歌词」（或早期版本没有防呆）就会攒下一串这样的残留，
 * 粉丝截图里 `lrc.txt / lrc(1).txt / lrc(2).txt / lrc(3).txt` 正是这个来源。
 *
 * 危害有两条：
 * 1. 残留会被「台本检测」扫到并当成台本回读 —— 于是下一轮识别拿上一次的产物当输入，
 *    越滚越脏（粉丝原话：「直接用原有的台本来识别」）；
 * 2. 残留参与歌词注册，往歌词表里塞进 `歌名(1)` 这类无意义 key，与正规 key 抢位。
 *
 * 判定语义（重要，别改成无条件剔除）：**只有当同目录确实存在未被编号的原名时**，
 * 带编号的那个才认定为残留。用户真把文件命名成 `歌名(1).lrc` 而目录里没有 `歌名.lrc`，
 * 那它就是唯一的一份歌词，必须照常生效——宁可留一个垃圾，也不能让歌词凭空消失。
 *
 * 抽成纯函数是为了能单测（目录枚举在 Android 侧，测不了）。
 */
internal object LrcResidueRules {

    /** 匹配 `基础名(n).扩展名`，n 为纯数字序号。 */
    private val AUTO_NUMBER = Pattern.compile("""^(.*)\((\d+)\)(\.[^.]*)$""")

    /**
     * 若 [fileName] 形如 `基础名(序号).扩展名`，返回其基础名（如 `歌名(1).lrc` → `歌名.lrc`）；
     * 不是自动序号名（或序号为空、基础名为空）时返回 null。
     */
    fun autoNumberBase(fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        val m = AUTO_NUMBER.matcher(fileName)
        if (!m.matches()) return null
        val base = m.group(1)
        val ext = m.group(3)
        if (base.isEmpty()) return null
        return base + ext
    }

    /**
     * [fileName] 是否为同目录重复文件的自动序号残留。
     *
     * @param fileName 待判定的文件名
     * @param siblings 同目录下的全部文件名（用于确认原名是否存在）
     */
    fun isResidue(fileName: String?, siblings: Set<String>): Boolean {
        val base = autoNumberBase(fileName) ?: return false
        // 原名不存在 → 这份编号文件是唯一副本，不能当残留剔除
        return siblings.any { it.equals(base, ignoreCase = true) }
    }
}
