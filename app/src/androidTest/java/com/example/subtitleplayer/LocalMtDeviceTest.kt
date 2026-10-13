package com.example.subtitleplayer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * 本地离线翻译 —— **真机/模拟器**验证（3.0，m7）。
 *
 * ## 为什么需要这个测试（JVM 单测覆盖不到的地方）
 *
 * 1. **`packagingOptions.pickFirst` 的真实后果**
 *    `onnxruntime-android` 与 `sherpa-onnx` 都打包 `lib/<abi>/libonnxruntime.so`，
 *    项目用 pickFirst 留下了一份。此前只做过**静态符号分析**（读 ELF 的
 *    DT_NEEDED/SONAME，确认两侧 JNI 都只用 ORT 的 C API `OrtGetApiBase`），
 *    但"符号存在"不等于"运行期能用" —— 必须真机跑一次才算数。
 *
 * 2. **两者能否在同一进程共存**
 *    `libsherpa-onnx-jni.so` 与 `libonnxruntime4j_jni.so` 都 NEEDED 那个同名库。
 *    本测试按「先 ORT 后 sherpa」的顺序加载（最容易暴露冲突的顺序）。
 *
 * 3. **端到端真推理**
 *    分词器（MarianBpe）已由 JVM 单测对齐 sentencepiece；但 ONNX 会话创建、
 *    张量形状、贪心解码衔接，只有真机才能验证。
 *
 * 模型文件（约 216 MB）由脚本 `_pustmt.sh` 预先 push 到 `filesDir/mt_model`；
 * 未就绪时该用例打印 SKIP 而非失败，避免 CI 上无模型时误红。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LocalMtDeviceTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * 1️⃣ ORT 环境可创建 —— 这一步会 dlopen `libonnxruntime4j_jni.so`，
     * 进而按 NEEDED 拉入被 pickFirst 留下的那份 `libonnxruntime.so`。
     * 只要这一句不抛，pickFirst 的**加载层**风险即被排除。
     */
    @Test
    fun test1_OrtEnvironmentCreatable() {
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        assertTrue("OrtEnvironment 为 null", env != null)
        println("ORT-OK " + env.toString())
    }

    /**
     * 2️⃣ 与 sherpa 共存 —— 在**已加载 ORT 之后**再加载 sherpa 的 native 库。
     * 若无共存问题，应不抛异常；若 pickFirst 留错了版本，这里会报
     * `dlopen failed` / `UnsatisfiedLinkError`。
     */
    @Test
    fun test2_CoexistWithSherpa() {
        System.loadLibrary("sherpa-onnx-jni")
        System.loadLibrary("sherpa-onnx-c-api")
        println("SHERPA-OK 共存加载成功")
    }

    /**
     * 3️⃣ 端到端真推理 —— 需要模型已 push。验证「加载 → 编码 → 贪心解码 → 解码」全链路。
     *
     * 断言口径刻意保守：**只断言"译文非空且为中文"**，不写死具体译文 ——
     * 写死会因模型微调版本变化而假红，而"是否产出合理中文"才是这一步要验的。
     */
    @Test
    fun test3_LocalMtEndToEnd() {
        val dir = MtModelManager.modelDir(ctx)
        if (!MarianMtEngine.isModelReady(dir)) {
            println("SKIP-MT 模型未就绪: " + dir.absolutePath)
            return
        }
        assertTrue("模型加载失败", MarianMtEngine.load(dir))

        val cases = listOf(
            "ありがとう",
            "おやすみなさい",
            "だって今日土曜日だよ"
        )
        for (t in cases) {
            val zh = MarianMtEngine.translate(t, 1)
            println("MT  [$t] -> [$zh]")
            assertTrue("译文为空: $t", zh.isNotEmpty())
            assertTrue(
                "译文不含中文: $t -> $zh",
                zh.any { it.code in 0x4E00..0x9FFF }
            )
        }
        MarianMtEngine.close()
    }
}
