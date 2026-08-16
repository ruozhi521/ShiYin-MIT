package com.example.subtitleplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 前台播放服务：持有 MediaPlayer，提供通知栏/锁屏控制，独立于 Activity 存活，
 * 保证退到后台、锁屏也能稳定播放。
 */
class MediaPlaybackService : Service() {

    interface Listener {
        /** 歌曲切换：song 为 null 表示无歌曲；lines 为当前歌词（可能为空）。 */
        fun onSongChanged(song: Song?, lines: List<SubtitleLine>, lyricName: String?)

        /** 进度回调：lyricIndex 为当前应高亮的歌词行索引，-1 表示无。 */
        fun onProgress(position: Int, duration: Int, lyricIndex: Int)

        fun onPlayStateChanged(playing: Boolean)
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        const val ACTION_PLAY_PAUSE = "com.example.subtitleplayer.PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.subtitleplayer.NEXT"
        const val ACTION_PREV = "com.example.subtitleplayer.PREV"
        const val ACTION_TOGGLE_LYRICS = "com.example.subtitleplayer.TOGGLE_LYRICS"
        const val ACTION_ALARM_PLAY = "com.example.subtitleplayer.ALARM_PLAY"
        const val KEY_LAST_URI = "last_uri"
        const val KEY_LAST_POS = "last_pos"
        const val KEY_DESKTOP_ON = "desktop_lyrics_on"
        const val KEY_MIX_AUDIO = "mix_audio"
        const val KEY_PLAY_MODE = "play_mode"
        const val KEY_LYRICON = "lyricon_enabled"
        const val MODE_SEQUENCE = 0
        const val MODE_SHUFFLE = 1
        const val MODE_REPEAT_ONE = 2
    }

    private val binder = PlaybackBinder()
    private val handler = Handler(Looper.getMainLooper())
    private var listener: Listener? = null

    private var mediaPlayer: MediaPlayer? = null
    /** 视频 surface（视频页绑定时记录；切歌/循环后新播放器需重新绑定画面）。 */
    private var attachedVideoSurface: android.view.Surface? = null
    /** 解码后的真实视频尺寸（含旋转修正，区别于 MediaMetadataRetriever 的存储方向）。 */
    private var videoWidth = 0
    private var videoHeight = 0
    /** 视频尺寸解出后回调（视频页用于修正方向与画面比例）。 */
    var onVideoSizeChanged: ((Int, Int) -> Unit)? = null
    private var isPrepared = false
    private var durationMs = 0

    private var songs: List<Song> = emptyList()
    private var index = -1
    private var lyricMap: Map<String, LyricRef> = emptyMap()
    private var lyricLines: List<SubtitleLine> = emptyList()
    private var lyricName: String? = null
    private var lyricTrans: Map<Int, String> = emptyMap()

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var resumeAfterFocusLoss = false

    private var mediaSession: MediaSession? = null
    private var foregroundShown = false

    private var sleepRunnable: Runnable? = null

    private var desktopLyrics: DesktopLyricsOverlay? = null

    private val statePrefs by lazy {
        getSharedPreferences("play_state", Context.MODE_PRIVATE)
    }
    private var tick = 0
    private var pendingResumeMs = 0
    private var forcePlayAfterSeek = false
    private val lyriconBridge by lazy { LyriconBridge(this) }
    private var notificationArtwork: android.graphics.Bitmap? = null

    private val progressRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null && isPrepared) {
                val pos = mp.currentPosition
                listener?.onProgress(pos, durationMs, lyricIndexAt(pos))
                desktopLyrics?.updateText(lyricTextAt(pos))
                tick++
                if (tick % 4 == 0) lyriconBridge.syncPosition(pos)
                if (tick % 30 == 0) savePosition()
                handler.postDelayed(this, 300)
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // 混合播放模式：不响应任何焦点变化（与其他音频同时出声，互不打断）
        if (isMixAudioOn()) return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeAfterFocusLoss = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeAfterFocusLoss = isPlaying()
                pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeAfterFocusLoss) {
                    resumeAfterFocusLoss = false
                    play()
                }
            }
            else -> {
                // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK 等：保持播放
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()
        initMediaSession()
        // 词幕开关（设置里可关，默认关闭）
        lyriconBridge.enabled = getSharedPreferences("player", Context.MODE_PRIVATE)
            .getBoolean(KEY_LYRICON, false)
    }

    /** 设置页开关：开启/关闭状态栏歌词推送（词幕）。 */
    fun setLyriconEnabled(on: Boolean) {
        getSharedPreferences("player", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LYRICON, on).apply()
        lyriconBridge.applyEnabled(on)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlay()
            ACTION_PREV -> playPrev()
            ACTION_NEXT -> playNext()
            ACTION_TOGGLE_LYRICS -> toggleDesktopLyrics()
            ACTION_ALARM_PLAY -> {
                android.util.Log.d("ShiYinAlarm", "onStartCommand: ACTION_ALARM_PLAY")
                playLast()
            }
        }
        // 服务每次启动（含后台重建）时恢复桌面歌词开关状态
        if (isDesktopLyricsOn()) setDesktopLyrics(true)
        showForeground()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        lyriconBridge.destroy()
        savePosition()
        handler.removeCallbacks(progressRunnable)
        desktopLyrics?.hide()
        abandonFocus()
        mediaPlayer?.release()
        mediaPlayer = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    // ---------- 对外 API（通过 Binder） ----------

    inner class PlaybackBinder : android.os.Binder() {
        fun service(): MediaPlaybackService = this@MediaPlaybackService
    }

    fun setListener(l: Listener?) {
        listener = l
    }

    fun startPlaylist(
        songs: List<Song>,
        index: Int,
        lyricMap: Map<String, LyricRef>,
        resumeMs: Int = 0,
        forcePlay: Boolean = false
    ) {
        this.songs = songs
        this.index = index
        this.lyricMap = lyricMap
        this.pendingResumeMs = resumeMs
        this.forcePlayAfterSeek = forcePlay
        playCurrent()
    }

    fun currentIndex(): Int = index

    fun togglePlay() {
        if (mediaPlayer?.isPlaying == true) pause() else play()
    }

    fun playPrev() {
        if (songs.isEmpty()) return
        index = when (getPlayMode()) {
            MODE_SHUFFLE -> randomIndex()
            else -> (index - 1 + songs.size) % songs.size
        }
        playCurrent()
    }

    fun playNext() {
        if (songs.isEmpty()) return
        index = when (getPlayMode()) {
            MODE_SHUFFLE -> randomIndex()
            else -> (index + 1) % songs.size
        }
        playCurrent()
    }

    /** 随机选一首（避免与当前相同）。 */
    private fun randomIndex(): Int {
        if (songs.size <= 1) return index
        var r = index
        while (r == index) r = kotlin.random.Random.nextInt(songs.size)
        return r
    }

    /** 播放模式：0 顺序 / 1 随机 / 2 单曲循环。 */
    fun getPlayMode(): Int =
        getSharedPreferences("player", Context.MODE_PRIVATE).getInt(KEY_PLAY_MODE, MODE_SEQUENCE)

    /** 点击循环切换模式，返回新模式。 */
    fun cyclePlayMode(): Int {
        val next = (getPlayMode() + 1) % 3
        getSharedPreferences("player", Context.MODE_PRIVATE)
            .edit().putInt(KEY_PLAY_MODE, next).apply()
        return next
    }

    fun seekTo(ms: Int) {
        val mp = mediaPlayer ?: return
        if (!isPrepared) return
        try {
            mp.seekTo(ms.coerceIn(0, durationMs))
        } catch (e: Exception) {
            // ignore
        }
    }

    /** 跳到指定毫秒，若当前暂停则同时开始播放（歌词点击跳转用）。 */
    fun seekToAndPlay(ms: Int) {
        seekTo(ms)
        if (!isPlaying()) play()
    }

    /** 视频页：绑定画面 surface；传 null 表示脱离画面（setSurface(null) 后继续纯音频播放）。 */
    fun attachVideoSurface(surface: android.view.Surface?) {
        attachedVideoSurface = surface
        val mp = mediaPlayer ?: return
        try {
            if (surface != null) {
                // 先清空再重设：强制重建渲染通道让画面输出。
                // 注意：不在此处 pause/seek（seek 在部分设备会卡住解码器导致进度冻结），
                // 播放/暂停一律走 Service 的 play()/pause() 保持状态一致。
                mp.setSurface(null)
                mp.setSurface(surface)
                android.util.Log.d("ShiYinVideo", "attachVideoSurface: rebind surface")
            } else {
                mp.setSurface(null)
                android.util.Log.d("ShiYinVideo", "attachVideoSurface: detach")
            }
        } catch (e: Exception) {
            android.util.Log.e("ShiYinVideo", "attachVideoSurface failed: ${e.message}")
        }
    }

    /** 视频页：播放速度（长按 2x 用；1f 恢复正常）。 */
    fun setSpeed(speed: Float) {
        val mp = mediaPlayer ?: return
        try {
            if (speed >= 1f) {
                mp.playbackParams = android.media.PlaybackParams().setSpeed(speed)
            }
        } catch (e: Exception) {
        }
    }

    fun currentPosition(): Int = try {
        mediaPlayer?.currentPosition ?: 0
    } catch (e: Exception) {
        0
    }

    fun currentDuration(): Int = durationMs

    /** 当前视频解码尺寸（0,0 表示未知/音频）。 */
    fun currentVideoSize(): Pair<Int, Int> = videoWidth to videoHeight

    /** 定时开始播放：恢复上次播放的歌曲与进度；无记录时兜底播放第一个歌单。 */
    private fun playLast() {
        android.util.Log.d(
            "ShiYinAlarm",
            "playLast: lastUri=${statePrefs.getString(KEY_LAST_URI, null)}"
        )
        val lastUri = statePrefs.getString(KEY_LAST_URI, null)
        if (!lastUri.isNullOrEmpty()) {
            try {
                val uri = android.net.Uri.parse(lastUri)
                val pos = statePrefs.getInt(KEY_LAST_POS, 0)
                // 优先从库缓存取完整歌曲信息（tag 标题/艺术家/文件夹），
                // 避免状态栏歌词显示文件名、艺术家为空
                val lib = LibraryCache.load(this)
                val cached = lib?.allSongs?.firstOrNull { it.uri.toString() == lastUri }
                if (cached != null) {
                    android.util.Log.d("ShiYinAlarm", "playLast: restore cached uri=$lastUri pos=$pos")
                    startPlaylist(
                        listOf(cached), 0, lib?.lyrics ?: emptyMap(), pos, forcePlay = true
                    )
                } else {
                    // 文件不在库中（被移走/未扫描）：回退文件名（去扩展名）+ 文件夹兜底艺术家
                    val title = queryDisplayName(uri)
                        ?.substringBeforeLast(".")
                        ?.takeIf { it.isNotBlank() } ?: "音乐"
                    val folder = queryFolder(uri)
                    android.util.Log.d("ShiYinAlarm", "playLast: restore raw uri=$lastUri pos=$pos")
                    startPlaylist(
                        listOf(Song(title, uri, folder, artist = folder)),
                        0, emptyMap(), pos, forcePlay = true
                    )
                }
                return
            } catch (e: Exception) {
                android.util.Log.d("ShiYinAlarm", "playLast: restore failed ${e.message}")
            }
        }
        // 兜底：播放第一个歌单（连续播放），保证到点一定有声音
        try {
            val lib = LibraryCache.load(this) ?: return
            val pl = lib.playlists.firstOrNull() ?: return
            android.util.Log.d("ShiYinAlarm", "playLast: fallback playlist=${pl.name} songs=${pl.songs.size}")
            startPlaylist(pl.songs, 0, lib.lyrics, 0, forcePlay = true)
        } catch (e: Exception) {
            android.util.Log.d("ShiYinAlarm", "playLast: fallback failed ${e.message}")
        }
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    } catch (e: Exception) {
        null
    }

    private fun queryFolder(uri: android.net.Uri): String = try {
        val path = uri.lastPathSegment ?: ""
        val parts = path.split("/").filter { it.isNotEmpty() }
        parts.getOrNull(parts.size - 2) ?: ""
    } catch (e: Exception) {
        ""
    }

    /** 持久化当前歌曲与进度，供下次启动断点续播。 */
    private fun savePosition() {
        val mp = mediaPlayer ?: return
        if (!isPrepared) return
        val song = currentSong() ?: return
        statePrefs.edit()
            .putString(KEY_LAST_URI, song.uri.toString())
            .putInt(KEY_LAST_POS, mp.currentPosition)
            .apply()
    }

    /** 定时关闭：minutes 分钟后自动暂停；minutes <= 0 表示取消。 */
    fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) return
        sleepRunnable = Runnable {
            sleepRunnable = null
            pause()
        }
        handler.postDelayed(sleepRunnable!!, minutes * 60_000L)
    }

    fun cancelSleepTimer() {
        sleepRunnable?.let { handler.removeCallbacks(it) }
        sleepRunnable = null
    }

    /** 让新绑定的客户端立即拿到当前完整状态。 */
    fun pushState() {
        listener?.onSongChanged(currentSong(), lyricLines, lyricName)
        val mp = mediaPlayer
        if (mp != null && isPrepared) {
            listener?.onProgress(mp.currentPosition, durationMs, lyricIndexAt(mp.currentPosition))
        }
        listener?.onPlayStateChanged(isPlaying())
    }

    // ---------- 播放 ----------

    private fun currentSong(): Song? = songs.getOrNull(index)

    /** 外部（播放页红心等）获取 Service 当前歌曲，不依赖 Activity 队列。 */
    fun currentSongSafe(): Song? = currentSong()

    private fun playCurrent() {
        val song = currentSong() ?: return
        releasePlayer()

        loadLyric(song)
        lyriconBridge.syncSong(song, lyricLines, lyricTrans, 0)
        loadNotificationArtwork(song)
        listener?.onSongChanged(song, lyricLines, lyricName)

        val mp = MediaPlayer()
        try {
            mp.setDataSource(this, song.uri)
            // 视频页打开中：新播放器重新绑定画面（否则循环/切歌后画面不动）
            attachedVideoSurface?.let { surf ->
                try {
                    mp.setSurface(surf)
                    android.util.Log.d("ShiYinVideo", "playCurrent: rebind surface $surf")
                } catch (e: Exception) {
                    android.util.Log.e("ShiYinVideo", "playCurrent rebind failed: ${e.message}")
                }
            }
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setOnPreparedListener { player ->
                isPrepared = true
                durationMs = player.duration
                lyriconBridge.syncSong(currentSong(), lyricLines, lyricTrans, durationMs)
                val resume = pendingResumeMs
                pendingResumeMs = 0
                if (resume > 0 && resume < durationMs) {
                    player.seekTo(resume)
                    if (forcePlayAfterSeek) {
                        // 定时开始播放（闹钟）：恢复进度后直接响，不等用户点播放
                        forcePlayAfterSeek = false
                        play()
                    } else {
                        updateAll(false)
                    }
                } else {
                    forcePlayAfterSeek = false
                    play()
                }
            }
            mp.setOnVideoSizeChangedListener { _, w, h ->
                android.util.Log.d("ShiYinVideo", "onVideoSizeChanged: ${w}x$h")
                if (w > 0 && h > 0) {
                    videoWidth = w
                    videoHeight = h
                    onVideoSizeChanged?.invoke(w, h)
                }
            }
            mp.setOnCompletionListener {
                // 播放完成：按播放模式决定下一首（单曲循环重播当前）
                when (getPlayMode()) {
                    MODE_REPEAT_ONE -> {
                        seekTo(0)
                        // 单曲循环不重建播放器，强制重设 surface 触发画面刷新（部分设备 seek 后无新帧）
                        attachedVideoSurface?.let { surf ->
                            try {
                                mp.setSurface(null)
                                mp.setSurface(surf)
                            } catch (e: Exception) {
                            }
                        }
                        play()
                    }
                    MODE_SHUFFLE -> {
                        if (songs.size > 1) {
                            index = randomIndex()
                            playCurrent()
                        }
                    }
                    else -> playNext()
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                listener?.onSongChanged(null, emptyList(), null)
                true
            }
            mp.prepareAsync()
            mediaPlayer = mp
            // 挂载均衡器（每次新建播放器都需重新挂载）并恢复上次曲线
            try {
                if (AudioFxManager.attach(mp.audioSessionId)) {
                    AudioFxManager.restoreSaved(this)
                }
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            try {
                mp.release()
            } catch (_: Exception) {
            }
            mediaPlayer = null
        }
    }

    private fun play() {
        val mp = mediaPlayer ?: return
        if (!isPrepared) return
        if (!requestFocus()) {
            android.util.Log.d("ShiYinAlarm", "play: audio focus denied")
            return
        }
        if (durationMs > 0 && mp.currentPosition >= durationMs) {
            mp.seekTo(0)
        }
        mp.start()
        android.util.Log.d("ShiYinAlarm", "play: started")
        lyriconBridge.syncPlaybackState(true)
        updateAll(true)
    }

    private fun pause() {
        savePosition()
        cancelSleepTimer()
        mediaPlayer?.pause()
        abandonFocus()
        lyriconBridge.syncPlaybackState(false)
        updateAll(false)
    }

    private fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false

    fun isPlayingSafe(): Boolean = isPlaying()

    private fun updateAll(playing: Boolean) {
        handler.removeCallbacks(progressRunnable)
        if (playing) {
            handler.post(progressRunnable)
        }
        showForeground()
        updateMediaSession(playing)
        listener?.onPlayStateChanged(playing)
    }

    private fun releasePlayer() {
        handler.removeCallbacks(progressRunnable)
        isPrepared = false
        mediaPlayer?.release()
        mediaPlayer = null
        durationMs = 0
    }

    // ---------- 歌词 ----------

    private fun loadLyric(song: Song) {
        lyricLines = emptyList()
        lyricName = null
        // 载入该歌的本地译文缓存（桌面歌词双行显示用）
        lyricTrans = try {
            LyricTranslationCache.load(this)[song.uri.toString()] ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
        // 1. 外部 .lrc / .vtt 文件优先
        val ref = LibraryScanner.findLyric(song, lyricMap)
        if (ref != null) {
            try {
                val bytes =
                    contentResolver.openInputStream(ref.uri)?.use { it.readBytes() } ?: return
                val parsed = SubtitleParser.parse(decodeText(bytes))
                if (parsed.isNotEmpty()) {
                    lyricLines = parsed
                    lyricName = ref.displayName
                    return
                }
            } catch (e: Exception) {
                // 外部歌词读取失败则尝试内嵌
            }
        }
        // 2. 内嵌歌词兜底（USLT / SYLT）
        val embedded = Id3LyricsParser.parse(this, song.uri)
        if (embedded != null && embedded.isNotEmpty()) {
            lyricLines = embedded
            lyricName = "内嵌歌词"
        }
    }

    private fun lyricIndexAt(pos: Int): Int {
        if (lyricLines.isEmpty()) return -1
        if (lyricLines[0].startMs < 0) return -1 // 静态歌词（无时间戳），不参与高亮
        var idx = -1
        for (i in lyricLines.indices) {
            if (lyricLines[i].startMs <= pos) {
                idx = i
            } else {
                break
            }
        }
        return idx
    }

    /** 当前进度对应的歌词文本（桌面歌词用）；有译文时显示 原文+译文 双行。 */
    private fun lyricTextAt(pos: Int): String {
        val idx = lyricIndexAt(pos)
        if (idx < 0) return ""
        val text = lyricLines[idx].text
        val trans = lyricTrans[idx]
        return if (trans != null && trans.isNotEmpty() && trans != text) {
            "$text\n$trans"
        } else {
            text
        }
    }

    /** 翻译完成后由 Activity 调用：重新载入当前歌的译文缓存，刷新桌面歌词。 */
    fun reloadLyricTranslations() {
        val song = currentSong() ?: return
        lyricTrans = try {
            LyricTranslationCache.load(this)[song.uri.toString()] ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
        desktopLyrics?.updateText(lyricTextAt(mediaPlayer?.currentPosition ?: 0))
    }

    // ---------- 桌面歌词 ----------

    fun isDesktopLyricsOn(): Boolean = getSharedPreferences("player", Context.MODE_PRIVATE)
        .getBoolean(KEY_DESKTOP_ON, false)

    /** 通知栏按钮触发：切换桌面歌词开/关。 */
    fun toggleDesktopLyrics() {
        setDesktopLyrics(!isDesktopLyricsOn())
    }

    /** 开启/关闭桌面歌词；未授权悬浮窗权限时提示并跳转系统设置引导授权。 */
    fun setDesktopLyrics(on: Boolean) {
        val sp = getSharedPreferences("player", Context.MODE_PRIVATE)
        if (on) {
            if (!Settings.canDrawOverlays(this)) {
                toast(getString(R.string.desktop_lyrics_perm_needed))
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (e: Exception) {
                    // 部分 ROM 不支持该页面；上面的 Toast 已提示用户去设置里手动开启
                }
                return
            }
            val overlay = desktopLyrics ?: DesktopLyricsOverlay(this).also { desktopLyrics = it }
            overlay.show()
            overlay.updateText(lyricTextAt(mediaPlayer?.currentPosition ?: 0))
            sp.edit().putBoolean(KEY_DESKTOP_ON, true).apply()
        } else {
            desktopLyrics?.hide()
            sp.edit().putBoolean(KEY_DESKTOP_ON, false).apply()
        }
        showForeground()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 字号/透明度/锁定设置变更后，让已显示的悬浮窗立即刷新样式。 */
    fun refreshDesktopLyricsStyle() {
        desktopLyrics?.refreshStyle()
    }

    // ---------- 音频焦点 ----------

    private fun requestFocus(): Boolean {
        // 混合播放模式：不请求音频焦点，也不响应焦点变化
        if (isMixAudioOn()) return true
        val am = audioManager ?: return true
        val afr = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { audioFocusRequest = it }
        return am.requestAudioFocus(afr) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** 混合播放开关：开启后不请求/不响应音频焦点，可与其他音频同时播放。 */
    private fun isMixAudioOn(): Boolean =
        getSharedPreferences("player", Context.MODE_PRIVATE).getBoolean(KEY_MIX_AUDIO, false)

    private fun abandonFocus() {
        val am = audioManager ?: return
        audioFocusRequest?.let {
            try {
                am.abandonAudioFocusRequest(it)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    // ---------- 通知与媒体会话 ----------

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.playback_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    private fun initMediaSession() {
        mediaSession = MediaSession(this, "SubtitlePlayer").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onSkipToNext() = playNext()
                override fun onSkipToPrevious() = playPrev()
                override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
                override fun onStop() = pause()
            })
            isActive = true
        }
    }

    private fun showForeground() {
        if (foregroundShown) {
            val playing = isPlaying()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(playing))
            return
        }
        foregroundShown = true
        val notification = buildNotification(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(playing: Boolean): Notification {
        val song = currentSong()
        val title = song?.title ?: getString(R.string.app_name)
        val text = song?.artist ?: song?.folder ?: getString(R.string.no_song)

        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prevPi = servicePi(ACTION_PREV, 1)
        val ppPi = servicePi(ACTION_PLAY_PAUSE, 2)
        val nextPi = servicePi(ACTION_NEXT, 3)
        val lyricsPi = servicePi(ACTION_TOGGLE_LYRICS, 4)

        val playIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        val playLabel = getString(if (playing) R.string.pause else R.string.play)
        val lyricsLabel =
            getString(if (isDesktopLyricsOn()) R.string.desktop_lyrics_off else R.string.desktop_lyrics_on)

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(title)
            .setContentText(text)
            .setLargeIcon(notificationArtwork)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                Notification.Action.Builder(R.drawable.ic_prev, getString(R.string.prev), prevPi).build()
            )
            .addAction(
                Notification.Action.Builder(playIcon, playLabel, ppPi).build()
            )
            .addAction(
                Notification.Action.Builder(R.drawable.ic_next, getString(R.string.next), nextPi).build()
            )
            .addAction(
                Notification.Action.Builder(R.drawable.ic_lyric, lyricsLabel, lyricsPi).build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun servicePi(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MediaPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateMediaSession(playing: Boolean) {
        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val ps = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SEEK_TO
            )
            .setState(state, (mediaPlayer?.currentPosition ?: 0).toLong(), if (playing) 1f else 0f)
            .build()
        mediaSession?.setPlaybackState(ps)

        val song = currentSong()
        val mb = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, song?.title ?: getString(R.string.app_name))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, song?.artist ?: song?.folder ?: "")
        mediaSession?.setMetadata(mb.build())
    }

    /** 异步加载封面，用于媒体通知与 MediaSession artwork。 */
    private fun loadNotificationArtwork(song: Song?) {
        if (song == null) {
            notificationArtwork = null
            return
        }
        CoverLoader.load(this, song.uri, 96) { bmp ->
            notificationArtwork = bmp
            showForeground()
            updateMediaSession(isPlaying())
        }
    }

    // ---------- 文本解码（与旧代码一致） ----------

    private fun decodeText(bytes: ByteArray): String {
        val data = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(data)).toString()
        } catch (e: CharacterCodingException) {
            try {
                String(data, Charset.forName("GBK"))
            } catch (e2: Exception) {
                String(data, StandardCharsets.UTF_8)
            }
        }
    }
}
