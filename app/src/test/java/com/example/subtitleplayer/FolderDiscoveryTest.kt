package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「一键扫描文件库」的发现逻辑测试（3.0.1 · 方案 D）。
 *
 * 纯 JVM 即可：FolderDiscovery 的判断部分全是纯函数（真正查媒体库的 discover(context) 不在这里）。
 * 这里覆盖的正是最容易写错的地方 —— 路径转换、聚合、去嵌套与排序。
 */
class FolderDiscoveryTest {

    // ---- MediaStore 绝对路径 → SAF docId ----

    @Test
    fun `内部存储路径转 primary`() {
        assertEquals("primary:asmr/RJ1", FolderDiscovery.toDocId("/storage/emulated/0/asmr/RJ1"))
        assertEquals("primary:Music", FolderDiscovery.toDocId("/storage/emulated/0/Music"))
    }

    @Test
    fun `SD 卡卷名照搬`() {
        assertEquals("ABCD-1234:Music", FolderDiscovery.toDocId("/storage/ABCD-1234/Music"))
    }

    @Test
    fun `非标准路径或卷根返回 null`() {
        assertNull(FolderDiscovery.toDocId("/data/local/tmp"))
        assertNull(FolderDiscovery.toDocId("/storage/emulated/0"))
        assertNull(FolderDiscovery.toDocId("/storage/emulated"))
        assertNull(FolderDiscovery.toDocId(""))
    }

    // ---- 聚合 ----

    @Test
    fun `同目录计数累加且标签取相对路径`() {
        // ⚠️ aggregate 的入参是**父目录**路径（不含文件名）—— discover() 里对文件路径做过
        // substringBeforeLast('/')；传文件全路径会被当成「那个文件名本身是个目录」。
        val list = FolderDiscovery.aggregate(
            listOf(
                "/storage/emulated/0/asmr/RJ1",
                "/storage/emulated/0/asmr/RJ1",
                "/storage/emulated/0/Music"
            )
        )
        val map = list.associateBy { it.docId }
        assertEquals(2, map["primary:asmr/RJ1"]?.count)
        assertEquals(1, map["primary:Music"]?.count)
        assertEquals("asmr/RJ1", map["primary:asmr/RJ1"]?.label)
    }

    @Test
    fun `无法识别的路径被跳过`() {
        val list = FolderDiscovery.aggregate(listOf("/data/local/tmp/x.mp3"))
        assertEquals(0, list.size)
    }

    // ---- 去嵌套 ----

    @Test
    fun `被上层目录覆盖的子目录被去掉`() {
        val list = listOf(
            FoundFolder("primary:asmr/RJ1", "asmr/RJ1", 30),
            FoundFolder("primary:asmr/RJ1/SEありver", "asmr/RJ1/SEありver", 5),
            FoundFolder("primary:Music", "Music", 3)
        )
        val out = FolderDiscovery.dropNested(list).map { it.docId }
        assertEquals(listOf("primary:asmr/RJ1", "primary:Music"), out)
    }

    @Test
    fun `前缀相似但不是子目录的要保留`() {
        val list = listOf(
            FoundFolder("primary:asmr/RJ1", "asmr/RJ1", 3),
            FoundFolder("primary:asmr/RJ11", "asmr/RJ11", 2)
        )
        assertEquals(2, FolderDiscovery.dropNested(list).size)
    }

    // ---- 排序 ----

    @Test
    fun `曲目多的在前`() {
        val out = FolderDiscovery.sortForDisplay(
            listOf(
                FoundFolder("primary:Music", "Music", 3),
                FoundFolder("primary:asmr/RJ1", "asmr/RJ1", 30)
            )
        ).map { it.docId }
        assertEquals(listOf("primary:asmr/RJ1", "primary:Music"), out)
    }

    @Test
    fun `同数量时层级浅的在前`() {
        val out = FolderDiscovery.sortForDisplay(
            listOf(
                FoundFolder("primary:a/b/c", "a/b/c", 5),
                FoundFolder("primary:a", "a", 5)
            )
        ).map { it.docId }
        assertEquals(listOf("primary:a", "primary:a/b/c"), out)
    }
}
