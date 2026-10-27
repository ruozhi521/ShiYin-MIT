package com.example.subtitleplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 左右声道反转（3.0.3）。
 *
 * 为什么只能用 ExoPlayer 实现：**MediaPlayer 没有任何交换声道的 API** ——
 * Android 的 `AudioEffect` 标准库只有均衡器 / 混响 / 虚拟环绕 / 低音增强，
 * 都做不了 L/R 交换。能做声道操作的只有 media3 的 `AudioProcessor`（在 PCM 层逐帧交换），
 * 所以开启本功能时播放服务会临时切到 ExoPlayer 引擎（见 [MediaPlaybackService.engineShouldUseExo]）。
 *
 * 需求来源（贴吧用户）：音声 / 舔耳类内容经常长时间只在一侧出声，
 * 有一键换边就不用死等作品本身的编排。
 *
 * 只在 **16bit 立体声**上生效；其它格式（单声道、浮点、多声道）直接抛
 * [AudioProcessor.UnhandledAudioFormatException] 让 ExoPlayer 跳过这个处理器，绝不破坏播放。
 */
@UnstableApi
class ChannelFlipProcessor : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.channelCount != 2
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // 采样率、声道数都不变，只是交换左右
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size < 4) {
            // 不足一个立体声帧（4 字节）：原样透传，绝不吞数据
            if (size > 0) {
                val out = replaceOutputBuffer(size)
                out.put(inputBuffer)
                out.flip()
            }
            return
        }
        val samples = ShortArray(size / 2)
        inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(samples)
        inputBuffer.position(inputBuffer.position() + (size / 2) * 2)
        swapStereoInPlace(samples)
        val out = replaceOutputBuffer((size / 2) * 2)
        out.order(ByteOrder.nativeOrder()).asShortBuffer().put(samples)
        out.position((size / 2) * 2)
        out.flip()
    }
}

/**
 * 就地把「L R L R …」交换成「R L R L …」（纯函数，便于单测）。
 *
 * 抽出来是为了能在 JVM 单测里验证交换正确性 —— `AudioProcessor` 本身依赖 media3 运行时，
 * 不适合在纯单测里构造。
 */
fun swapStereoInPlace(samples: ShortArray) {
    var i = 0
    while (i + 1 < samples.size) {
        val l = samples[i]
        samples[i] = samples[i + 1]
        samples[i + 1] = l
        i += 2
    }
}
