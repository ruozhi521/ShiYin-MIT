package com.example.subtitleplayer

import android.media.MediaPlayer
import kotlin.math.abs

/**
 * 歌词点击跳转的落点策略（3.0.8）。
 *
 * 充电用户反馈：点歌词跳转后「时间轴定位不准」，但**只有部分音频**有问题；
 * 他用的是 iQOO，**自带音乐播放器同样不准**，而 salt player / potplayer 完全正常。
 *
 * 这条对照信息把矛头指向了引擎，不是歌词文件：iQOO 自带播放器和拾音默认路径
 * 用的都是 `android.media.MediaPlayer`，salt player 走的是自己的解码栈。
 *
 * 根因有两层，缺一不可：
 *
 * **一、落点精度**。`MediaPlayer.seekTo(int)` 等价于 `SEEK_PREVIOUS_SYNC` ——
 * 一律跳到目标**之前**的最近同步帧，不是目标位置本身。MP3 一帧约 26ms，
 * 正常偏移两三帧不明显；但 VBR（可变比特率）文件在没有 Xing/VBRI 头时，
 * 系统按 CBR 线性外推时间戳，跳转可能偏到几百毫秒 —— 这就是「只有部分音频」。
 * 修法是改用 `seekTo(long, SEEK_CLOSEST)`（API 26 起可用，本项目 minSdk 26）。
 *
 * **二、高亮被弹回**。seek 是**异步**的，返回时播放器还在旧位置。而进度轮询
 * 每 300ms 读一次位置去算当前歌词行 —— 若 seek 尚未落定，读到的是旧位置，
 * 刚点高的那一行立刻被弹回原来的行。文件越大、seek 越慢，越容易看到这个现象。
 * 修法是 seek 期间把「对外报告的位置」锁定成目标值，落定后再交还给引擎。
 *
 * 这里只放**纯逻辑**，便于单测；真正的引擎调用在 [MediaPlaybackService]。
 */
object SeekPrecisionRules {

    /**
     * seek 落定后，实际位置与目标差多少算「已经跳到位」（毫秒）。
     *
     * 不能取太小：`SEEK_CLOSEST` 落到最近**帧**，MP3 帧长 26ms、AAC 约 21ms，
     * 加上解码器上报位置的粒度，正常会有几十毫秒残差。取 400ms 是为了容忍
     * VBR 外推出的更大误差，同时又不至于把「真没跳过去」误判成到位。
     */
    const val LANDED_TOLERANCE_MS = 400

    /**
     * 锁定位置的兜底超时（毫秒）。
     *
     * 万一是引擎压根不回调 / seek 失败，不能让位置永远停在目标值上 ——
     * 那样进度条会卡死不动。超时后无条件交还给引擎。
     */
    const val LOCK_TIMEOUT_MS = 1_500L

    /** MediaPlayer 的 seek 模式：精确跳转用 SEEK_CLOSEST，否则沿用旧的同步帧语义。 */
    fun mediaPlayerMode(precise: Boolean): Int =
        if (precise) MediaPlayer.SEEK_CLOSEST else MediaPlayer.SEEK_PREVIOUS_SYNC

    /** 这次 seek 是否已经落定（可以解除位置锁定）。 */
    fun hasLanded(targetMs: Int, actualMs: Int): Boolean =
        actualMs >= 0 && abs(actualMs - targetMs) <= LANDED_TOLERANCE_MS

    /**
     * seek 期间对外报告哪个位置。
     *
     * @param lockTargetMs 正在进行中的 seek 目标；-1 表示没有 seek 在进行
     * @param actualMs 引擎当前位置
     * @param elapsedMs 从发起 seek 到现在过了多久
     */
    fun reportPosition(lockTargetMs: Int, actualMs: Int, elapsedMs: Long): Int = when {
        lockTargetMs < 0 -> actualMs
        elapsedMs >= LOCK_TIMEOUT_MS -> actualMs   // 兜底：别把进度条卡死
        hasLanded(lockTargetMs, actualMs) -> actualMs
        else -> lockTargetMs
    }
}
