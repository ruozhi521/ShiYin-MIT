package com.example.subtitleplayer

import java.util.Locale

/**
 * 「这个文件能不能当视频播」的判定规则（2.13）。
 *
 * 背景：音声作品的纯音频文件常被改名为 `.mp4`（实测 `トラック01/03/06` 只有一条 aac 音轨、
 * 零条视频轨）。旧实现只看扩展名就认定是视频，导致它们混进「视频」列表，
 * 点开只有声音、画面黑屏或残留上一部视频的帧。
 *
 * 抽成纯函数是为了能单元测试（真正的轨道探测在 Android 侧，测不了），
 * 同时把「探测失败怎么办」这条兜底语义写死在一个地方。
 */
object VideoFileRules {

    /** 可能承载视频的容器扩展名（小写，不含点）。 */
    private val VIDEO_CONTAINER_EXTS = setOf("mp4", "m4v", "m4s")

    /** 扩展名是否属于「可能是视频」的容器。不是则无需读文件头，直接判否。 */
    fun couldBeVideo(fileName: String?): Boolean {
        val ext = extOf(fileName) ?: return false
        return ext in VIDEO_CONTAINER_EXTS
    }

    /**
     * 结合轨道探测结果给出最终判定。
     *
     * @param couldBeVideo [couldBeVideo] 的结果（扩展名层面）
     * @param probe 轨道探测结果：true=有视频轨，false=无，null=探测失败/无法判断
     *
     * 探测失败时**兜底为 true**（当作视频）：宁可多显示一个（点开是黑的），
     * 也不能让真视频从列表里消失——列表少东西用户无法自行补救。
     */
    fun resolve(couldBeVideo: Boolean, probe: Boolean?): Boolean {
        if (!couldBeVideo) return false
        return probe ?: true
    }

    /** 取小写扩展名；无扩展名返回 null。 */
    private fun extOf(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val i = name.lastIndexOf('.')
        if (i <= 0 || i == name.length - 1) return null
        return name.substring(i + 1).lowercase(Locale.ROOT)
    }
}
