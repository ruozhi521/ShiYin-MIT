package com.example.subtitleplayer

import android.content.Context
import android.net.Uri
import java.nio.charset.Charset

/**
 * 轻量 ID3v2 文本帧解析：直接读文件头的 TIT2（标题）/ TPE1（艺术家）。
 *
 * 为什么需要它：`MediaMetadataRetriever.extractMetadata(TITLE)` 在某些 Android
 * 版本/ROM 上对带 BOM 的 UTF-16 标签会解出乱码（实测反馈）。这个类自己遍历
 * ID3 帧并严格按帧内 encoding 解码，作为 MMR 的备选通道。读取失败返回 null，
 * 由上层回退，不影响正常流程。
 */
class Id3TagReader private constructor(
    context: Context,
    uri: Uri
) {
    private val resolver = context.contentResolver
    private val title = readFrame("TIT2")
    private val artist = readFrame("TPE1")

    private fun readFrame(wantId: String): String? {
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(10)
                if (input.read(head) != 10) return null
                if (String(head, 0, 3, Charsets.ISO_8859_1) != "ID3") return null
                val major = head[3].toInt() and 0xFF
                val flags = head[5].toInt() and 0xFF
                val tagSize =
                    ((head[6].toInt() and 0x7F) shl 21) or
                        ((head[7].toInt() and 0x7F) shl 14) or
                        ((head[8].toInt() and 0x7F) shl 7) or
                        (head[9].toInt() and 0x7F)
                if (tagSize <= 0 || tagSize > 64 * 1024 * 1024) return null
                val body = ByteArray(tagSize)
                if (input.read(body) != tagSize) return null
                if (major == 2) {
                    parseFramesV2(body, tagSize, wantId)
                } else {
                    parseFrames(body, tagSize, major, flags, wantId)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseFrames(
        body: ByteArray,
        len: Int,
        major: Int,
        flags: Int,
        wantId: String
    ): String? {
        var off = 0
        // 扩展头（flags bit6）：v2.4 synchsafe、v2.3 普通字节序
        if ((flags and 0x40) != 0) {
            if (major == 4) {
                val s = synchsafe(body, off)
                if (s > 0 && s < len - 10) off = s
            } else if (major == 3) {
                val s = ((body[off].toInt() and 0xFF) shl 24) or
                    ((body[off + 1].toInt() and 0xFF) shl 16) or
                    ((body[off + 2].toInt() and 0xFF) shl 8) or
                    (body[off + 3].toInt() and 0xFF)
                if (s in 4 until len - 10) off = s
            }
        }
        while (off + 10 <= len) {
            val id = String(body, off, 4, Charsets.ISO_8859_1)
            var valid = true
            for (k in 0 until 4) {
                val b = body[off + k].toInt() and 0xFF
                if (b < 0x20 || b > 0x7E) { valid = false; break }
            }
            if (!valid) break
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
            if (id == wantId) {
                return decodeTextFrame(body, off, size)
            }
            off += size
        }
        return null
    }

    private fun parseFramesV2(body: ByteArray, len: Int, wantId: String): String? {
        var off = 0
        val want = if (wantId == "TIT2") "TT2" else if (wantId == "TPE1") "TP1" else wantId
        while (off + 6 <= len) {
            val id = String(body, off, 3, Charsets.ISO_8859_1)
            var valid = true
            for (k in 0 until 3) {
                val b = body[off + k].toInt() and 0xFF
                if (b < 0x20 || b > 0x7E) { valid = false; break }
            }
            if (!valid) break
            val size = ((body[off + 3].toInt() and 0xFF) shl 16) or
                ((body[off + 4].toInt() and 0xFF) shl 8) or
                (body[off + 5].toInt() and 0xFF)
            off += 6
            if (size <= 0 || off + size > len) break
            if (id == want) return decodeTextFrame(body, off, size)
            off += size
        }
        return null
    }

    /** 文本帧：encoding(1) + 字符串；UTF-16 体系自动按 BOM 解码。 */
    private fun decodeTextFrame(body: ByteArray, from: Int, len: Int): String? {
        if (len < 1) return null
        val encoding = body[from].toInt() and 0xFF
        if (len < 2) return ""
        val sub = ByteArray(len - 1)
        System.arraycopy(body, from + 1, sub, 0, len - 1)
        return try {
            when (encoding) {
                0 -> String(sub, Charset.forName("ISO-8859-1")).trimEnd('\u0000')
                1 -> String(sub, Charset.forName("UTF-16")).trimEnd('\u0000') // 自动 BOM
                2 -> String(sub, Charset.forName("UTF-16BE")).trimEnd('\u0000')
                3 -> String(sub, Charset.forName("UTF-8")).trimEnd('\u0000')
                else -> null
            }.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private fun synchsafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or
            ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or
            (b[off + 3].toInt() and 0x7F)

    companion object {
        /** 读取 ID3 标题/艺术家：成功返回 Pair，失败返回 Pair(null, null)。 */
        fun read(context: Context, uri: Uri): Pair<String?, String?> {
            return try {
                val r = Id3TagReader(context, uri)
                r.title to r.artist
            } catch (e: Exception) {
                null to null
            }
        }
    }
}
