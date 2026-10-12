package com.example.subtitleplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「文件能否作为视频播放」的判定规则测试（2.13）。
 *
 * 回归目标：音声作品的纯音频文件常被改名为 `.mp4`（实测 `トラック01/03/06` 只有一条
 * aac 音轨、零条视频轨）。旧实现只看扩展名就收进「视频」列表，点开只有声音、
 * 画面黑屏或残留上一部视频的帧。
 */
class VideoFileRulesTest {

    // ---------- 扩展名层：不是视频容器就零 IO 直接否 ----------

    @Test
    fun `mp4 m4v m4s 属于可能为视频的容器`() {
        assertTrue(VideoFileRules.couldBeVideo("a.mp4"))
        assertTrue(VideoFileRules.couldBeVideo("a.m4v"))
        assertTrue(VideoFileRules.couldBeVideo("a.m4s"))
    }

    @Test
    fun `扩展名大小写不敏感`() {
        assertTrue(VideoFileRules.couldBeVideo("A.MP4"))
        assertTrue(VideoFileRules.couldBeVideo("Clip.M4V"))
        assertTrue(VideoFileRules.couldBeVideo("video.M4S"))
    }

    @Test
    fun `纯音频扩展名一律不是视频`() {
        for (n in listOf("a.mp3", "a.flac", "a.wav", "a.m4a", "a.ogg", "a.aac", "a.opus")) {
            assertFalse("$n 不该被当作视频容器", VideoFileRules.couldBeVideo(n))
        }
    }

    @Test
    fun `无扩展名与空名不当作视频`() {
        assertFalse(VideoFileRules.couldBeVideo("noext"))
        assertFalse(VideoFileRules.couldBeVideo(""))
        assertFalse(VideoFileRules.couldBeVideo(null))
        assertFalse(VideoFileRules.couldBeVideo("trailing."))
    }

    // ---------- 轨道探测结果层 ----------

    @Test
    fun `mp4 但有零条视频轨 判否（本次修复的核心）`() {
        // トラック01/03/06：.mp4 后缀，实际只有一条 aac 音轨
        assertFalse(VideoFileRules.resolve(couldBeVideo = true, probe = false))
    }

    @Test
    fun `mp4 且有视频轨 判是`() {
        assertTrue(VideoFileRules.resolve(couldBeVideo = true, probe = true))
        assertTrue(VideoFileRules.resolve(couldBeVideo = true, probe = true))
    }

    @Test
    fun `探测失败时兜底为是 避免真视频从列表消失`() {
        // 文件损坏 / 该机型解不了此编码：宁可多显示一个，也不能漏掉真视频
        assertTrue(VideoFileRules.resolve(couldBeVideo = true, probe = null))
    }

    @Test
    fun `非视频容器直接否 即使探测结果为是`() {
        assertFalse(VideoFileRules.resolve(couldBeVideo = false, probe = true))
        assertFalse(VideoFileRules.resolve(couldBeVideo = false, probe = null))
    }

    @Test
    fun `真实场景 トラック01 mp4 纯音频不进视频列表`() {
        // 名字来自粉丝实际文件；扩展名像视频，但探测出无视频轨
        val name = "トラック01.mp4"
        val could = VideoFileRules.couldBeVideo(name)
        assertTrue("扩展名层应先放行去探测", could)
        assertFalse("探测出无视频轨 → 不该进视频列表", VideoFileRules.resolve(could, false))
    }

    @Test
    fun `真实场景 屏幕录制 mp4 有视频轨进视频列表`() {
        val name = "Screenrecorder-2026-09-27.mp4"
        val could = VideoFileRules.couldBeVideo(name)
        assertTrue(VideoFileRules.resolve(could, true))
    }
}
