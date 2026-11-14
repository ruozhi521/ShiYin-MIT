package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别模型规格（3.1 多模型）单测。
 *
 * 重点钉住两件容易出错的事：
 * 1. **日语和韩语的主文件名完全相同**（都叫 `encoder-epoch-99-avg-1.int8.onnx`），
 *    如果目录不隔离就会互相覆盖 —— 这是加韩语最容易踩的坑。
 * 2. **modelingUnit 必须跟着模型走**：日语 cjkchar（按字）、韩语 bpe（按子词）。
 *    写死成 cjkchar 会让韩语识别出一堆乱码。
 */
class AsrModelSpecsTest {

    @Test
    fun `默认模型是日语`() {
        assertEquals("ja", AsrModelSpecs.DEFAULT_ID)
        assertEquals("ja", AsrModelSpecs.byId(null).id)
        // 老用户没存过 id 时必须落到日语，行为与 3.1 之前一致
        assertEquals("ja", AsrModelSpecs.byId("").id)
        assertEquals("ja", AsrModelSpecs.byId("unknown-id").id)
    }

    @Test
    fun `按 id 能取到对应模型`() {
        assertEquals("ja", AsrModelSpecs.byId("ja").id)
        assertEquals("ko", AsrModelSpecs.byId("ko").id)
    }

    @Test
    fun `日语用 cjkchar 切分且不需要 bpe模型`() {
        val ja = AsrModelSpecs.JA
        assertEquals("cjkchar", ja.modelingUnit)
        assertFalse(ja.needsBpeModel)
        assertFalse(ja.files.any { it.role == "bpe" })
    }

    @Test
    fun `韩语用 bpe 切分且必须带 bpe模型`() {
        val ko = AsrModelSpecs.KO
        assertEquals("bpe", ko.modelingUnit)
        assertTrue(ko.needsBpeModel)
        // 缺 bpe.model 时 sherpa 加载会失败，所以它必须在文件清单里
        assertTrue(ko.files.any { it.role == "bpe" })
    }

    @Test
    fun `两个模型的主文件名相同所以必须靠目录隔离`() {
        val jaEnc = AsrModelSpecs.JA.files.first { it.role == "encoder" }.candidates
        val koEnc = AsrModelSpecs.KO.files.first { it.role == "encoder" }.candidates
        // 正是因为这里相等，AsrModelSpecs 的 id 才必须进目录名
        assertEquals(jaEnc, koEnc)
        assertNotEquals(AsrModelSpecs.JA.id, AsrModelSpecs.KO.id)
    }

    @Test
    fun `每个模型都声明了至少一个镜像源且以斜杠结尾`() {
        for (spec in AsrModelSpecs.ALL) {
            assertTrue("${spec.id} 没有镜像源", spec.sources.isNotEmpty())
            for (src in spec.sources) {
                assertTrue("$src 应以 / 结尾（拼接文件名要用）", src.endsWith("/"))
            }
        }
    }

    @Test
    fun `文件角色不重复`() {
        for (spec in AsrModelSpecs.ALL) {
            val roles = spec.files.map { it.role }
            assertEquals("${spec.id} 有重复角色", roles.size, roles.toSet().size)
        }
    }

    @Test
    fun `必须包含 encoder decoder joiner tokens 四项`() {
        for (spec in AsrModelSpecs.ALL) {
            val roles = spec.files.map { it.role }.toSet()
            for (required in listOf("encoder", "decoder", "joiner", "tokens")) {
                assertTrue("${spec.id} 缺少 $required", roles.contains(required))
            }
        }
    }

    @Test
    fun `体积声明是正数`() {
        for (spec in AsrModelSpecs.ALL) {
            assertTrue("${spec.id} 体积应为正", spec.sizeMb > 0)
        }
    }
}
