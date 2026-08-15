package com.example.subtitleplayer

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.nio.charset.Charset

/**
 * 解析 MP3 ID3v2 标签中的内嵌歌词：
 * - USLT（未同步歌词）：纯文本，无时间戳 → 行 startMs = -1（静态展示，不可跳转）
 * - SYLT（同步歌词）：带毫秒时间戳 → 转为可跳转的 SubtitleLine
 * 解析失败或没有歌词时返回 null（调用方走外部 .lrc/.vtt）。
 */
object Id3LyricsParser {

    private const val MAX_TAG_BYTES = 2 * 1024 * 1024
    private const val MAX_PIC_BYTES = 16 * 1024 * 1024

    fun parse(context: Context, uri: Uri): List<SubtitleLine>? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input -> parseStream(input) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 提取 ID3v2 APIC 帧中的内嵌封面（字节）。
     * MediaMetadataRetriever.embeddedPicture 对高分辨率封面可能返回 null，
     * 此方法作为兜底直接解析文件头的 APIC 帧。
     */
    fun extractEmbeddedPicture(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input -> extractPicture(input) }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractPicture(input: InputStream): ByteArray? {
        val header = ByteArray(10)
        if (!readFully(input, header)) return null
        if (header[0] != 'I'.code.toByte() ||
            header[1] != 'D'.code.toByte() ||
            header[2] != '3'.code.toByte()
        ) {
            return null
        }
        val major = header[3].toInt() and 0xFF
        if (major != 3 && major != 4) return null
        val tagSize = synchsafe(header, 6)
        if (tagSize <= 0 || tagSize > MAX_PIC_BYTES) return null
        val body = ByteArray(tagSize)
        val got = readFullyLen(input, body)
        if (got < 10) return null
        return findPicture(body, got, major)
    }

    private fun findPicture(body: ByteArray, len: Int, major: Int): ByteArray? {
        var off = 0
        while (off + 10 <= len) {
            val id = String(body, off, 4, Charsets.ISO_8859_1)
            val size = if (major == 4) {
                synchsafe(body, off + 4)
            } else {
                ((body[off + 4].toInt() and 0xFF) shl 24) or
                    ((body[off + 5].toInt() and 0xFF) shl 16) or
                    ((body[off + 6].toInt() and 0xFF) shl 8) or
                    (body[off + 7].toInt() and 0xFF)
            }
            off += 10
            if (size <= 0 || off + size > len) break
            if (id == "APIC") {
                return parseApicData(body, off, size)
            }
            off += size
        }
        return null
    }

    /** APIC 帧体：encoding(1) + mime(0 结尾) + 图片类型(1) + 描述(0 结尾) + 图片数据。 */
    private fun parseApicData(body: ByteArray, start: Int, size: Int): ByteArray? {
        if (size < 6) return null
        val encoding = body[start].toInt() and 0xFF
        var i = start + 1
        var mimeEnd = -1
        while (i < start + size) {
            if (body[i] == 0.toByte()) {
                mimeEnd = i
                break
            }
            i++
        }
        if (mimeEnd < 0) return null
        i = mimeEnd + 1 + 1 // mime 结束符 + 图片类型
        val term = if (encoding == 1 || encoding == 2) 2 else 1
        while (i + term <= start + size) {
            var found = true
            for (k in 0 until term) {
                if (body[i + k] != 0.toByte()) {
                    found = false
                    break
                }
            }
            if (found) break
            i++
        }
        i += term
        if (i >= start + size) return null
        return body.copyOfRange(i, start + size)
    }

    /** 只读文件头部：ID3v2 标签位于文件最前，无需读取整个音频文件。 */
    private fun parseStream(input: InputStream): List<SubtitleLine>? {
        val header = ByteArray(10)
        if (!readFully(input, header)) return null
        if (header[0] != 'I'.code.toByte() ||
            header[1] != 'D'.code.toByte() ||
            header[2] != '3'.code.toByte()
        ) {
            return null
        }
        val major = header[3].toInt() and 0xFF
        if (major != 3 && major != 4) return null
        val tagSize = synchsafe(header, 6)
        if (tagSize <= 0 || tagSize > MAX_TAG_BYTES) return null

        val body = ByteArray(tagSize)
        val got = readFullyLen(input, body)
        if (got < 10) return null
        return parseBody(body, got, major)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun readFullyLen(input: InputStream, buf: ByteArray): Int {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    private fun parseBody(body: ByteArray, bodyLen: Int, major: Int): List<SubtitleLine>? {
        val usltLines = mutableListOf<String>()
        val syltLines = mutableListOf<SubtitleLine>()
        var pos = 0
        while (pos + 10 <= bodyLen) {
            if (body[pos] == 0.toByte()) break // padding 开始
            val frameId = String(body, pos, 4, Charsets.ISO_8859_1)
            val rawSize = IntArray(4)
            for (i in 0 until 4) rawSize[i] = body[pos + 4 + i].toInt() and 0xFF
            val frameSize = if (major == 4) {
                synchsafeRaw(rawSize)
            } else {
                (rawSize[0] shl 24) or (rawSize[1] shl 16) or (rawSize[2] shl 8) or rawSize[3]
            }
            val dataStart = pos + 10
            if (frameSize <= 0 || dataStart + frameSize > bodyLen) break
            val data = ByteArray(frameSize)
            System.arraycopy(body, dataStart, data, 0, frameSize)
            when (frameId) {
                "USLT" -> parseUslt(data)?.let { usltLines.addAll(it) }
                "SYLT" -> parseSylt(data)?.let { syltLines.addAll(it) }
            }
            pos = dataStart + frameSize
        }
        if (syltLines.isNotEmpty()) return syltLines
        if (usltLines.isNotEmpty()) {
            return usltLines.map { SubtitleLine(-1, Int.MAX_VALUE, it) }
        }
        return null
    }

    /** USLT: encoding(1) + lang(3) + descriptor\0 + lyrics text */
    private fun parseUslt(data: ByteArray): List<String>? {
        if (data.size < 5) return null
        val encoding = data[0].toInt() and 0xFF
        val descEnd = findTerminator(data, 4, encoding)
        if (descEnd < 0) return null
        val text = decodeText(data, descEnd + terminatorLen(encoding), data.size, encoding)
            .replace("\u0000", " ")
            .trim()
        if (text.isEmpty()) return null
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** SYLT: encoding(1) + lang(3) + format(1) + type(1) + descriptor\0 + [text\0 + ts(4)]* */
    private fun parseSylt(data: ByteArray): List<SubtitleLine>? {
        if (data.size < 7) return null
        val encoding = data[0].toInt() and 0xFF
        val format = data[4].toInt() and 0xFF
        if (format != 1) return null // 只支持毫秒时间戳
        val descEnd = findTerminator(data, 6, encoding)
        if (descEnd < 0) return null

        val out = mutableListOf<SubtitleLine>()
        var pos = descEnd + terminatorLen(encoding)
        val tlen = terminatorLen(encoding)
        while (pos + 4 <= data.size) {
            val textEnd = findTerminator(data, pos, encoding)
            if (textEnd < 0 || textEnd + tlen + 4 > data.size) break
            val text = decodeText(data, pos, textEnd, encoding).trim()
            val ts = ((data[textEnd + tlen].toInt() and 0xFF) shl 24) or
                ((data[textEnd + tlen + 1].toInt() and 0xFF) shl 16) or
                ((data[textEnd + tlen + 2].toInt() and 0xFF) shl 8) or
                (data[textEnd + tlen + 3].toInt() and 0xFF)
            if (text.isNotEmpty()) {
                out.add(SubtitleLine(ts, Int.MAX_VALUE, text))
            }
            pos = textEnd + tlen + 4
        }
        if (out.isEmpty()) return null
        // 补齐 endMs
        for (i in 0 until out.size - 1) {
            out[i] = SubtitleLine(out[i].startMs, out[i + 1].startMs - 1, out[i].text)
        }
        return out
    }

    // ---------- helpers ----------

    private fun synchsafe(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0x7F) shl 21) or
            ((b[offset + 1].toInt() and 0x7F) shl 14) or
            ((b[offset + 2].toInt() and 0x7F) shl 7) or
            (b[offset + 3].toInt() and 0x7F)

    private fun synchsafeRaw(raw: IntArray): Int =
        ((raw[0] and 0x7F) shl 21) or ((raw[1] and 0x7F) shl 14) or
            ((raw[2] and 0x7F) shl 7) or (raw[3] and 0x7F)

    /** 查找文本终结符（encoding 0/3 单字节 0x00；1/2 UTF-16 双字节 0x00 0x00），返回索引或 -1 */
    private fun findTerminator(data: ByteArray, from: Int, encoding: Int): Int {
        val wide = encoding == 1 || encoding == 2
        var i = from
        while (i + (if (wide) 1 else 0) < data.size) {
            if (data[i].toInt() == 0) {
                if (!wide) return i
                if (i + 1 < data.size && data[i + 1].toInt() == 0) return i
                i++
            } else {
                i++
            }
        }
        return -1
    }

    private fun terminatorLen(encoding: Int): Int =
        if (encoding == 1 || encoding == 2) 2 else 1

    private fun decodeText(data: ByteArray, from: Int, to: Int, encoding: Int): String {
        if (to <= from) return ""
        val len = to - from
        val sub = ByteArray(len)
        System.arraycopy(data, from, sub, 0, len)
        return try {
            when (encoding) {
                0 -> String(sub, Charset.forName("ISO-8859-1"))
                1 -> String(sub, Charset.forName("UTF-16")) // 自动处理 BOM
                2 -> String(sub, Charset.forName("UTF-16BE"))
                else -> String(sub, Charset.forName("UTF-8"))
            }
        } catch (e: Exception) {
            ""
        }
    }
}
