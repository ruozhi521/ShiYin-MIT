package com.example.subtitleplayer

import android.content.Context
import android.media.audiofx.Equalizer

/**
 * 均衡器（调音）核心：管理系统 audiofx.Equalizer。
 * - 跟随播放器的 audioSessionId（播放器每次新建，特效需重新挂载）
 * - 预设用 10 段参考曲线（31Hz~16kHz），按设备实际频段中心频率对数插值重采样
 * - 配置持久化到 SharedPreferences（"eq"）
 */
object AudioFxManager {

    private const val PREFS = "eq"
    private const val KEY_PRESET = "preset"      // 当前预设名（空 = 自定义）
    private const val KEY_CUR = "cur"            // 当前曲线："dB,dB,..."（按设备频段顺序）
    private const val KEY_DEFAULT_PRESET = "default_preset" // 恢复预设的基准曲线

    /** 10 段参考频率（Hz）。 */
    private val REF_FREQS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    /** 预设：名 -> 10 段增益（dB，-12 ~ +12）。 */
    val PRESETS: Map<String, FloatArray> = linkedMapOf(
        "平坦" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "流行" to floatArrayOf(1f, 2f, 3f, 1f, 0f, -1f, -1f, 0f, 1f, 2f),
        "摇滚" to floatArrayOf(4f, 3f, 0f, -2f, -1f, 0f, 2f, 3f, 4f, 5f),
        "舞曲" to floatArrayOf(5f, 3f, 0f, 0f, 0f, -1f, 0f, 2f, 3f, 4f),
        "古典" to floatArrayOf(3f, 2f, 1f, 0f, 0f, -1f, 0f, 1f, 2f, 3f),
        "人声" to floatArrayOf(-1f, -1f, 0f, 2f, 3f, 2f, 1f, 0f, -1f, -1f),
        "重低音" to floatArrayOf(6f, 5f, 3f, 1f, 0f, 0f, -1f, -2f, -3f, -3f),
        "高音增强" to floatArrayOf(-3f, -2f, -1f, 0f, 0f, 1f, 2f, 3f, 4f, 5f)
    )

    private var eq: Equalizer? = null
    private var sessionId = 0

    /** 挂载到播放器（返回 false = 设备不支持均衡器）。 */
    fun attach(newSessionId: Int): Boolean {
        if (newSessionId == sessionId && eq != null) return true
        release()
        sessionId = newSessionId
        return try {
            eq = Equalizer(0, newSessionId)
            eq != null
        } catch (e: Exception) {
            eq = null
            false
        }
    }

    fun release() {
        try {
            eq?.release()
        } catch (_: Exception) {
        }
        eq = null
    }

    val isAttached: Boolean get() = eq != null

    fun numberOfBands(): Int = try {
        (eq?.numberOfBands ?: 0).toInt()
    } catch (e: Exception) {
        0
    }

    /** 频段中心频率（Hz）。 */
    fun centerFreqHz(band: Int): Int = try {
        val mhz = (eq?.getCenterFreq(band) ?: 0).toLong()
        (mhz / 1000L).toInt()
    } catch (e: Exception) {
        0
    }

    /** 增益范围（dB，如 -15 ~ +15）。 */
    fun bandRange(): Pair<Float, Float> = try {
        val r = eq?.bandLevelRange
        if (r != null && r.size == 2) Pair(r[0] / 100f, r[1] / 100f) else Pair(-12f, 12f)
    } catch (e: Exception) {
        Pair(-12f, 12f)
    }

    /** 设置某频段增益（dB）。 */
    fun setBand(band: Int, gainDb: Float) {
        try {
            val range = bandRange()
            val g = gainDb.coerceIn(range.first, range.second)
            eq?.setBandLevel(band, (g * 100).toInt())
        } catch (_: Exception) {
        }
    }

    fun getBand(band: Int): Float = try {
        ((eq?.getBandLevel(band) ?: 0) / 100f)
    } catch (e: Exception) {
        0f
    }

    /**
     * 把 10 段预设曲线重采样到设备频段。
     * 按中心频率在 log 频率轴插值；参考频率之外取端点值。
     */
    fun resampleToDevice(gains10: FloatArray): FloatArray {
        val n = numberOfBands()
        if (n == 0) return FloatArray(0)
        if (n == 10) return gains10.copyOf()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val f = centerFreqHz(i).coerceAtLeast(1)
            out[i] = interpolateLog(REF_FREQS, gains10, f)
        }
        return out
    }

    private fun interpolateLog(freqs: IntArray, vals: FloatArray, f: Int): Float {
        val n = freqs.size
        if (f <= freqs[0]) return vals[0]
        if (f >= freqs[n - 1]) return vals[n - 1]
        val lf = Math.log(f.toDouble())
        for (i in 1 until n) {
            val l0 = Math.log(freqs[i - 1].toDouble())
            val l1 = Math.log(freqs[i].toDouble())
            if (lf <= l1) {
                val t = ((lf - l0) / (l1 - l0)).toFloat()
                return vals[i - 1] + (vals[i] - vals[i - 1]) * t
            }
        }
        return vals[n - 1]
    }

    // ---------- 持久化 ----------

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun currentPreset(c: Context): String = sp(c).getString(KEY_PRESET, "") ?: ""

    fun applyPreset(c: Context, name: String) {
        val g = PRESETS[name] ?: return
        val bands = resampleToDevice(g)
        if (bands.isEmpty()) return
        for (i in bands.indices) setBand(i, bands[i])
        sp(c).edit().putString(KEY_PRESET, name).apply()
        saveCur(c, bands)
    }

    /** 应用自定义曲线（拖动滑块时逐段调用）。 */
    fun applyCustomBand(c: Context, band: Int, gainDb: Float) {
        setBand(band, gainDb)
        sp(c).edit().putString(KEY_PRESET, "").apply()
        saveCur(c, currentCurve(c, gainDb, band))
    }

    /** 恢复预设：回到最近一次预设的原始曲线（没选过预设则全部归 0）。 */
    fun restorePreset(c: Context) {
        val name = currentPreset(c)
        val base = if (name.isNotEmpty() && PRESETS.containsKey(name)) {
            PRESETS[name]!!
        } else {
            FloatArray(10) { 0f }
        }
        val bands = resampleToDevice(base)
        if (bands.isEmpty()) return
        for (i in bands.indices) setBand(i, bands[i])
        sp(c).edit().putString(KEY_PRESET, name).apply()
        saveCur(c, bands)
    }

    /** 读取当前各频段增益（dB）。 */
    fun currentCurve(c: Context, replace: Float? = null, atBand: Int = -1): FloatArray {
        val n = numberOfBands()
        val out = FloatArray(n)
        if (n > 0) {
            for (i in 0 until n) {
                if (i == atBand && replace != null) out[i] = replace else out[i] = getBand(i)
            }
        }
        return out
    }

    private fun saveCur(c: Context, curve: FloatArray) {
        if (curve.isEmpty()) return
        sp(c).edit().putString(KEY_CUR, curve.joinToString(",") { it.toString() }).apply()
    }

    /** 恢复上次保存的曲线（应用启动 / 切歌重挂载时）。 */
    fun restoreSaved(c: Context) {
        val s = sp(c).getString(KEY_CUR, "") ?: ""
        if (s.isEmpty()) return
        val parts = s.split(",").mapNotNull { it.toFloatOrNull() }
        for (i in parts.indices) setBand(i, parts[i])
    }
}
