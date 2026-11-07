package com.example.subtitleplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBitmapFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

/** SAF 封面的尺寸探测、解码和目录继承回归；无需连接手机或启动模拟器。 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(shadows = [BoundsBitmapFactory::class])
class FolderCoverTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val uri = Uri.parse("content://covers/tree/music/document/music%2FCamera_123.jpg")

    @Before
    fun setup() {
        ShadowBitmapFactory.setAllowInvalidImageData(false)
    }

    private fun image(format: String): ByteArray = ByteArrayOutputStream().use { out ->
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val compressFormat = if (format == "jpg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
        assertTrue(bitmap.compress(compressFormat, 90, out))
        bitmap.recycle()
        out.toByteArray()
    }

    // 保留生产函数的私有可见性，直接验证树形与歌曲兜底共用的同步解码路径。
    private fun decode(): Bitmap? = CoverLoader::class.java.getDeclaredMethod(
        "decodeContentScaled", Context::class.java, Uri::class.java, Int::class.javaPrimitiveType
    ).apply { isAccessible = true }.invoke(CoverLoader, context, uri, 120) as Bitmap?

    @Test
    fun `尺寸探测正常返回 null 但填写图片尺寸`() {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assertNull(BitmapFactory.decodeStream(ByteArrayInputStream(image("png")), null, bounds))
        assertEquals(640, bounds.outWidth)
        assertEquals(480, bounds.outHeight)
    }

    private fun assertContentDecodes(format: String) {
        val bytes = image(format)
        var opens = 0
        var closes = 0
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            opens++
            object : ByteArrayInputStream(bytes) {
                override fun close() { closes++; super.close() }
            }
        }
        val bitmap = decode()
        assertNotNull("尺寸探测返回 null 不能阻止真正解码", bitmap)
        assertEquals(160, bitmap!!.width)
        assertEquals(120, bitmap.height)
        assertEquals("探测和解码分别打开流", 2, opens)
        assertEquals("两个内容流都必须关闭", 2, closes)
    }

    @Test fun `任意文件名 JPEG 内容流可解码`() = assertContentDecodes("jpg")
    @Test fun `PNG 内容流可解码并降采样`() = assertContentDecodes("png")

    @Test
    fun `损坏图片返回 null`() {
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }
        assertNull(decode())
    }

    @Test
    fun `权限失效返回 null 不崩溃`() {
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            throw SecurityException("permission revoked")
        }
        assertNull(decode())
    }

    @Test
    fun `图片已删除返回 null 不崩溃`() {
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            throw FileNotFoundException("deleted")
        }
        assertNull(decode())
    }

    @Suppress("UNCHECKED_CAST")
    private fun pick(
        songs: Set<String>,
        candidates: Map<String, List<Pair<String, Uri>>>
    ): Map<String, Uri> {
        val scanner = LibraryScanner(context, context.contentResolver)
        val field = LibraryScanner::class.java.getDeclaredField("coverCandidates").apply { isAccessible = true }
        val collected = field.get(scanner) as MutableMap<String, MutableList<Pair<String, Uri>>>
        candidates.forEach { (folder, images) -> collected[folder] = images.toMutableList() }
        return LibraryScanner::class.java.getDeclaredMethod("pickFolderCovers", Set::class.java)
            .apply { isAccessible = true }.invoke(scanner, songs) as Map<String, Uri>
    }

    @Test
    fun `仅父目录有图时保留父封面并传给歌曲子目录`() {
        val covers = pick(setOf("女神/Disc1"), mapOf("女神" to listOf("camera.jpg" to uri)))
        assertEquals(uri, covers["女神"])
        assertEquals(uri, covers["女神/Disc1"])
    }

    @Test
    fun `根目录无直接音频时图片仍向下继承`() {
        val covers = pick(setOf("A/B"), mapOf(LibraryScanner.DEFAULT_FOLDER to listOf("封面.png" to uri)))
        assertEquals(uri, covers[LibraryScanner.DEFAULT_FOLDER])
        assertEquals(uri, covers["A/B"])
    }

    @Test
    fun `自己的封面优先于祖先且不从兄弟图片目录继承`() {
        val own = Uri.parse("content://covers/own")
        val unrelated = Uri.parse("content://covers/wallpaper")
        val covers = pick(setOf("A/B", "Other"), mapOf(
            "A" to listOf("cover.jpg" to uri),
            "A/B" to listOf("123.png" to own),
            "壁纸" to listOf("cover.png" to unrelated)
        ))
        assertEquals(own, covers["A/B"])
        assertNull(covers["Other"])
        assertFalse(covers.containsKey("壁纸"))
    }

    @Test
    fun `多根扫描不能把前一个根的候选图片带到下一个根`() {
        val provider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = 0
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): android.database.Cursor {
                val cursor = android.database.MatrixCursor(projection)
                val root = android.provider.DocumentsContract.getTreeDocumentId(uri)
                cursor.addRow(arrayOf<Any>("$root/song.mp3", "song.mp3", "audio/mpeg", 0L, 0L))
                if (root == "first") {
                    cursor.addRow(arrayOf<Any>("first/123.jpg", "123.jpg", "image/jpeg", 100L, 0L))
                }
                return cursor
            }
        }
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("covers", provider)
        val scanner = LibraryScanner(context, context.contentResolver)
        val first = scanner.scanOneRoot(Uri.parse("content://covers/tree/first"))
        val second = scanner.scanOneRoot(Uri.parse("content://covers/tree/second"))
        assertEquals(1, first.folderCovers.size)
        assertEquals(1, second.songs.size)
        assertTrue("无图的第二个根不能继承第一个根的封面", second.folderCovers.isEmpty())
    }

    @Test
    fun `封面关键词优先 其余按名称稳定挑选`() {
        val preferred = Uri.parse("content://covers/preferred")
        assertEquals(preferred, pick(setOf("A"), mapOf("A" to listOf(
            "001.jpg" to uri, "封面.png" to preferred
        )))["A"])
        assertEquals(uri, pick(setOf("A"), mapOf("A" to listOf(
            "999.jpg" to preferred, "001.jpg" to uri
        )))["A"])
    }
}
