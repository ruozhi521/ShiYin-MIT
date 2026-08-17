package com.example.subtitleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/** Id3LyricsParser 字节解析测试（构造 ID3v2 帧字节，纯 JVM）。 */
class Id3LyricsParserTest {

    // ID3v2.3 帧：id(4) + size(4 大端) + flags(2) + data
    private fun frame(id: String, data: ByteArray): ByteArray {
        val out = ByteArray(10 + data.size)
        val idBytes = id.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(idBytes, 0, out, 0, 4)
        out[4] = ((data.size shr 24) and 0xFF).toByte()
        out[5] = ((data.size shr 16) and 0xFF).toByte()
        out[6] = ((data.size shr 8) and 0xFF).toByte()
        out[7] = (data.size and 0xFF).toByte()
        System.arraycopy(data, 0, out, 10, data.size)
        return out
    }

    @Test
    fun `USLT 纯文本歌词解析`() {
        // encoding=0(ISO-8859-1) + lang(3) + desc\0 + text
        val data = byteArrayOf(0, 'z'.code.toByte(), 'h'.code.toByte(), '0'.code.toByte(), 0) +
            "第一句\n第二句".toByteArray(Charsets.UTF_8)
        val lines = Id3LyricsParser.parseUslt(data)
        assertNotNull(lines)
        assertEquals(2, lines!!.size)
        assertEquals("第一句", lines[0])
    }

    @Test
    fun `SYLT 同步歌词带时间戳解析`() {
        // encoding=0 + lang(3) + format(1)=1 + type(1) + desc\0 + [text\0 + ts(4 大端)]
        val text1 = "第一句".toByteArray(Charsets.UTF_8)
        val text2 = "第二句".toByteArray(Charsets.UTF_8)
        val ts1 = 1000 // 大端 4 字节
        val ts2 = 3000
        val data = byteArrayOf(
            0, 'z'.code.toByte(), 'h'.code.toByte(), '0'.code.toByte(),
            1, 1, 0 // format=1, type=1, desc 结束
        ) + text1 + byteArrayOf(0,
            (ts1 shr 24).toByte(), (ts1 shr 16).toByte(), (ts1 shr 8).toByte(), ts1.toByte()
        ) + text2 + byteArrayOf(0,
            (ts2 shr 24).toByte(), (ts2 shr 16).toByte(), (ts2 shr 8).toByte(), ts2.toByte()
        )
        val lines = Id3LyricsParser.parseSylt(data)
        assertNotNull(lines)
        assertEquals(2, lines!!.size)
        assertEquals("第一句", lines[0].text)
        assertEquals(1000, lines[0].startMs)
        assertEquals(3000, lines[1].startMs)
        // 第一行 endMs 补齐为下一行 start-1
        assertEquals(2999, lines[0].endMs)
    }

    @Test
    fun `parseBody 只认歌词帧忽略其他帧`() {
        val txxx = frame("TIT2", byteArrayOf(0) + "标题".toByteArray(Charsets.UTF_8))
        val usltData = byteArrayOf(0, 'z'.code.toByte(), 'h'.code.toByte(), '0'.code.toByte(), 0) +
            "歌词内容".toByteArray(Charsets.UTF_8)
        val uslt = frame("USLT", usltData)
        val body = txxx + uslt
        val lines = Id3LyricsParser.parseBody(body, body.size, 3)
        assertNotNull(lines)
        assertEquals(1, lines!!.size)
        assertEquals("歌词内容", lines[0].text)
    }

    @Test
    fun `无歌词帧返回 null`() {
        val txxx = frame("TIT2", byteArrayOf(0) + "标题".toByteArray(Charsets.UTF_8))
        assertNull(Id3LyricsParser.parseBody(txxx, txxx.size, 3))
    }

    @Test
    fun `非 ID3 头返回 null`() {
        val junk = "not an mp3".toByteArray()
        val lines = Id3LyricsParser.parseStream(ByteArrayInputStream(junk))
        assertNull(lines)
    }
}
