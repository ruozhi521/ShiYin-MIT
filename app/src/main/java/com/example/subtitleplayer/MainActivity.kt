package com.example.subtitleplayer

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Page 定义已提到顶层（Page.kt，2.13）：便于 MiniPlayerRules 与单测引用

    // ---- 页面视图 ----
    private lateinit var viewDiscover: View
    private lateinit var viewLibrary: View
    private lateinit var viewPlaylist: View
    private lateinit var viewSearch: View
    private lateinit var viewPlayer: View
    private lateinit var viewLyrics: View

    // ---- 底部导航 ----
    private var currentModule = MODULE_LIBRARY
    private val navTabs = LinkedHashMap<String, android.widget.TextView>()
    private var hasSong = false

    /**
     * 当前播放队列的来源页（2.11）：从哪个列表页发起的播放就记住哪个页，
     * 歌词页/播放页按返回键时先回该页（音轨界面），不再直接跳回主页。
     * null 表示队列不来自可展示的列表页（如后台恢复的全库队列）→ 返回走原逻辑。
     */
    private var queueSourcePage: Page? = null

    // ---- 迷你播放条 ----
    private lateinit var miniPlayer: View
    private lateinit var txtMiniTitle: TextView
    private lateinit var btnMiniPlay: Button
    private lateinit var miniSeekBar: SeekBar

    // ---- 发现页 ----
    private lateinit var recyclerDiscover: RecyclerView
    private lateinit var discoverAdapter: DiscoverAdapter
    private var discoverSongs: List<Song> = emptyList()

    // ---- 音乐库页 ----
    private lateinit var searchEntry: android.widget.EditText
    private lateinit var segPlaylists: TextView
    private lateinit var segArtists: TextView
    private lateinit var recyclerPlaylists: RecyclerView
    private lateinit var recyclerArtists: RecyclerView
    private lateinit var treeAdapter: FolderTreeAdapter
    private lateinit var gridAdapter: PlaylistGridAdapter
    /** 树形目录路径栈：从顶层到当前层（空 = 顶层）。 */
    private val treeStack = mutableListOf<TreeNode>()
    private var treeRoots: List<TreeNode> = emptyList()
    private lateinit var txtTreePath: TextView
    private lateinit var btnSeekBack: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var btnSeekForward: TextView
    /** 任一进度条拖动中（手指按住）：不刷新时间文本、不进入沉浸。 */
    private var seeking = false
    /** 设置对话框引用（供 UI 测试同步断言；正常流程不依赖）。 */
    internal var settingsDialog: AlertDialog? = null
    private lateinit var artistAdapter: ArtistAdapter
    private var artistGroups: List<Pair<String, List<Song>>> = emptyList()
    private var artistLoaded = false
    private var artistLoading = false

    // ---- 歌单/歌曲列表页 ----
    private lateinit var txtPlaylistTitle: TextView
    private lateinit var recyclerSongs: RecyclerView

    // ---- 搜索页 ----
    private lateinit var etSearch: android.widget.EditText
    private lateinit var txtSearchHint: TextView
    private lateinit var recyclerSearch: RecyclerView
    private lateinit var searchAdapter: SearchAdapter
    private var searchPlaylists: List<Playlist> = emptyList()
    private var searchSongs: List<Song> = emptyList()

    // ---- 播放页 ----
    private lateinit var txtPlayerTitle: TextView
    private lateinit var txtPlayerFolder: TextView
    private lateinit var imgCd: ImageView
    private lateinit var txtNowLyric: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var txtTime: TextView
    private lateinit var btnPlayPlayer: ImageButton
    private var currentCoverKey: String? = null
    /**
     * 当前歌曲封面（2.12.1）。歌词页/播放页的「封面背景」直接用这份 bitmap，
     * 避免切页时再走一遍 CoverLoader（磁盘/解析）。切歌或加载失败即置 null。
     */
    private var currentCoverBmp: Bitmap? = null
    /**
     * 上一次「歌词页/播放页是否正用封面背景」的状态（2.12.1）。
     * 只在翻转时重刷适配器配色，避免每次刷新背景都全量 notifyDataSetChanged。
     */
    private var lastCoverTextState = false
    /** 用户手动滑动歌词页的时间（4 秒冷却期内不自动回位）。 */
    private var lastLyricUserScroll = 0L
    /** 播放页沉浸模式。 */
    private var playerImmersed = false
    private val immersionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val immersionRunnable = Runnable { enterImmersion() }
    private lateinit var playerHeader: View
    private lateinit var playerControlsMain: View
    private lateinit var playerControlsExtra: View
    private lateinit var txtImmersiveLyric: android.widget.TextView
    private lateinit var bottomNav: android.view.ViewGroup

    // ---- 收藏页 ----
    private lateinit var viewFavorites: View
    private lateinit var btnFavorite: android.widget.TextView
    private lateinit var recyclerFavorites: RecyclerView
    private lateinit var txtFavoritesEmpty: TextView
    private lateinit var favoritesAdapter: SongAdapter
    private var favoriteSongs: List<Song> = emptyList()

    // ---- 歌单列表页（2.14：导航「歌单」的落地页，内含「收藏」+ 自建歌单）----
    private lateinit var viewPlaylistsPage: View
    private lateinit var recyclerPlaylistsPage: RecyclerView
    private lateinit var txtPlaylistsEmpty: TextView
    private lateinit var playlistsPageAdapter: PlaylistGridAdapter
    /** 当前展示的歌单项（第一项恒为「收藏」）。 */
    private var playlistsPageItems: List<Playlist> = emptyList()
    /** 「收藏」项在列表里的名字，点它走原有的收藏页。用常量避免与自建歌单重名歧义。 */
    private val favoritesEntryName: String get() = getString(R.string.favorites_title)

    // ---- 视频页 ----
    private lateinit var viewVideo: View
    private lateinit var viewVideos: View
    private lateinit var recyclerVideos: RecyclerView
    private lateinit var txtVideosEmpty: TextView
    private lateinit var videoAdapter: SongAdapter
    private var videoSongs: List<Song> = emptyList()
    /**
     * uri -> 是否含真实视频轨（2.13）。`isVideoFile` 要读文件头，而打开视频列表会对
     * 库里每一首都调它；缓存后同一文件只探测一次，避免列表卡顿。
     * 重新扫描库时清空（文件可能被替换）。
     */
    private val videoFlagCache =
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** 是否已有后台线程在预热 [videoFlagCache]（2.13）：避免反复进出列表时不断起线程。 */
    private val videoWarmRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    /** 预热进行中又被请求了一次（库更新）：等本轮结束再补跑（2.13）。 */
    private var videoWarmPending = false
    private lateinit var videoSurface: android.view.TextureView
    private lateinit var txtVideoHint: android.widget.TextView
    private lateinit var seekVideo: SeekBar
    private lateinit var txtVideoTime: android.widget.TextView
    private var videoSurfaceAttached = false
    private var videoSpeedUp = false
    private var videoDownX = 0f
    private var videoW = 0
    private var videoH = 0
    /**
     * 切歌后等待新视频首帧（2.13）。期间画面透明，避免 TextureView 继续显示
     * 上一个视频的残留帧（粉丝反馈「画面是之前的视频，但音频正常」）。
     */
    private var videoFramePending = false
    /**
     * 「新首帧」最早被认可的时刻（2.13）。
     * 切歌瞬间旧播放器的余帧会继续触发 onSurfaceTextureUpdated，
     * 不设时间门槛就会把旧帧误判成新帧、立刻显示出来。
     */
    private var videoFrameReadyAfter = 0L
    private val videoFrameHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** 视频页是否处于全屏（系统栏已隐藏）。 */
    private var videoImmersive = false
    private val videoLongPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var cdAnimator: ObjectAnimator? = null

    // ---- 全屏歌词页 ----
    private lateinit var recyclerLyricFull: RecyclerView
    private lateinit var btnTranslate: Button
    private lateinit var txtLyricsTitle: TextView
    private var lastSong: Song? = null
    private var translating = false
    private var transFailedLines: List<Pair<Int, String>> = emptyList()
    /** 本次会话是否已执行过断点续播（后台静默重扫不再重复拉起）。 */
    private var resumedLastSong = false
    private val translationCache by lazy {
        LyricTranslationCache.load(this)
    }

    // ---- 歌词校准（2.2）：整体 ± 偏移，应用内立即生效 + 防抖写回歌词文件 ----
    private var lyricOffsetDialog: AlertDialog? = null
    private var lyricOffsetLabelView: TextView? = null
    private var lyricOffsetSong: Song? = null
    /** 对话框里跟踪的「文件自带 [offset:] 标签」（后台读出，通常为 0）。 */
    private var lyricOffsetFileTag = 0
    /** 应用内追加偏移（写入 prefs 与歌词文件时都用它）。 */
    private var lyricOffsetExtra = 0
    private val lyricOffsetWriteHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lyricOffsetWritePending: Runnable? = null

    private lateinit var songAdapter: SongAdapter
    private lateinit var lyricAdapter: LyricAdapter

    private val prefs by lazy { getSharedPreferences("player", Context.MODE_PRIVATE) }

    private var library: MusicLibrary? = null
    private var scanning = false

    /**
     * ASR 逐窗实时翻译：已提交到第几行（2.13 增量翻译游标）。
     * 只翻译行号 ≥ 此值的「新增行」，杜绝累计全文反复重发。
     */
    private var asrTranslatedLineCount = 0
    /**
     * 失败行重试计数：uri -> (行号 -> 已失败次数)（2.13 熔断）。
     * 超过 [MAX_LINE_RETRY] 的行不再重发——否则永久失败的行会被每窗重试，
     * 把费用放大到几十倍（这是一小时 10 元的主因）。
     */
    private val transFailCount = HashMap<String, HashMap<Int, Int>>()

    /** ASR 逐窗实时翻译状态（切歌时清空）。 */
    private fun resetAsrTranslateState() {
        asrTranslatedLineCount = 0
    }

    /** 随机顺序：会话级固定种子，保证列表不闪跳（2.13）。 */
    private var randomSeed = System.currentTimeMillis()

    private var currentSongs: List<Song> = emptyList()
    private var lyricLines: List<SubtitleLine> = emptyList()
    private var durationMs = 0
    private var currentLyricHighlight = -1

    // ---- 播放服务 ----
    private var playbackService: MediaPlaybackService? = null
    private var bound = false
    private var serviceStarted = false
    private var pendingStart: Triple<List<Song>, Int, Int>? = null

    private var page = Page.DISCOVER

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? MediaPlaybackService.PlaybackBinder)?.service() ?: return
            playbackService = svc
            bound = true
            svc.setListener(serviceListener)
            val pending = pendingStart
            if (pending != null) {
                pendingStart = null
                svc.startPlaylist(
                    pending.first,
                    pending.second,
                    library?.lyrics ?: emptyMap(),
                    pending.third
                )
            } else {
                svc.pushState()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            playbackService = null
        }
    }

    private val serviceListener = object : MediaPlaybackService.Listener {
        override fun onSongChanged(song: Song?, lines: List<SubtitleLine>, lyricName: String?) {
            txtPlayerTitle.text = song?.title ?: ""
            txtPlayerFolder.text = song?.artist ?: ""
            txtMiniTitle.text = song?.title ?: ""
            updateFavoriteButton(song)
            hasSong = song != null
            findViewById<ImageButton>(R.id.btnVideo).visibility =
                if (isVideoFile(song?.uri)) View.VISIBLE else View.GONE
            // 切歌：隐藏上一个视频的残留帧，等新视频首帧再显示（2.13）
            awaitVideoFirstFrame()
            // 切歌：重置 ASR 逐窗翻译游标（新歌的行号从 0 重新计数，2.13）
            resetAsrTranslateState()
            // 底部迷你条显隐（与 showPage 共用同一个判定，避免两处副本走样）
            updateMiniPlayerVisibility()
            lyricLines = lines
            currentLyricHighlight = -1
            lyricAdapter.submit(lines)
            updateNowLyric(-1)
            lastSong = song
            transFailedLines = emptyList()
            // 翻译请求在途时不重置 translating / 按钮，防止跨歌并发翻译导致译文串歌（1.30）
            if (!translating) {
                btnTranslate.isEnabled = true
                btnTranslate.text = getString(R.string.translate)
            }
            // 切歌后倍速按钮回到 1x（Service 已重置播放速度）
            btnSpeed.text = "1x"
            val cachedTrans = song?.let { translationCache[it.uri.toString()] } ?: emptyMap()
            lyricAdapter.setTranslations(cachedTrans)
            maybeAutoTranslate(song, lines)
            imgCd.setImageResource(R.drawable.ic_music_tinted)
            currentCoverKey = song?.uri?.toString()
            // 切歌不主动清背景（会闪纯色）；等封面回调定夺——有封面铺封面，没有则回退。
            // 与 imgCd 的行为一致：旧图留到新图加载完再替换。
            if (song != null) {
                CoverLoader.load(this@MainActivity, song.uri, coverLoadSize(), folder = song.folder, songSize = song.size) { bmp ->
                    if (song.uri.toString() == currentCoverKey) {
                        if (bmp != null) {
                            imgCd.setImageBitmap(bmp)
                            currentCoverBmp = bmp
                            // 播放页主色渐变按封面算；歌词页/播放页的封面背景一并刷新
                            applyPlayerBackground(bmp)
                        } else {
                            currentCoverBmp = null
                            applyPlayerBackground(null)
                        }
                        // 无封面的歌 → cover 为 null → 自动回退自定义背景图/纯色
                        applyPageBackground()
                    }
                }
            } else {
                currentCoverBmp = null
                applyPlayerBackground(null)
                applyPageBackground()
            }
        }

        override fun onProgress(position: Int, duration: Int, lyricIndex: Int) {
            durationMs = duration
            if (seekBar.max != duration) {
                seekBar.max = duration
            }
            if (miniSeekBar.max != duration) {
                miniSeekBar.max = duration
            }
            if (page == Page.VIDEO) {
                if (seekVideo.max != duration) seekVideo.max = duration
                if (!seekVideo.isPressed) seekVideo.progress = position
                if (!seeking) {
                    txtVideoTime.text = formatTime(position) + " / " + formatTime(duration)
                }
            }
            if (!seekBar.isPressed) {
                seekBar.progress = position
            }
            if (!miniSeekBar.isPressed) {
                miniSeekBar.progress = position
            }
            // 拖动中不刷新时间文本（保持手指预览位置，onProgressChanged 已设置）
            if (!seeking) {
                updateTime(position)
            }
            updateNowLyric(lyricIndex)
            // 识别实时歌词激活时（服务里还没有该歌的正式歌词）：按已识别行本地算高亮
            var highlightIdx = lyricIndex
            if (highlightIdx < 0 && asrLiveUri != null && asrLiveLines.isNotEmpty()) {
                highlightIdx = asrLiveLines.indexOfLast { it.startMs <= position }
            }
            if (highlightIdx != currentLyricHighlight) {
                currentLyricHighlight = highlightIdx
                lyricAdapter.setCurrent(highlightIdx)
                scrollToLyric(highlightIdx)
            }
        }

        override fun onPlayStateChanged(playing: Boolean) {
            updatePlayButtons(playing)
            updateCdAnimation(playing)
            if (page == Page.VIDEO) {
                txtVideoHint.visibility = if (playing) View.GONE else View.VISIBLE
                findViewById<ImageButton>(R.id.btnVideoPlay).setImageResource(
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play
                )
            }
        }
    }

    private val treePicker =
        registerForActivityResult(OpenTreePersistable()) { uri ->
            uri ?: return@registerForActivityResult
            persistRead(uri)
            // 判断是不是新增的根（要在 addTreeUri 之前比）
            val isNewRoot = savedTreeUris().none { it.toString() == uri.toString() }
            addTreeUri(uri)
            // 2.1：这次选文件夹已同时申请写权限，扫描完成后把之前只能存应用内的识别歌词补写回音乐文件夹
            pendingLrcFlush = true
            // 2.13：新增文件夹只扫新根再与已有库合并；重选已添加过的文件夹仍走全量
            if (isNewRoot) scanLibraryIncremental(uri) else scanLibrary()
        }

    /** 是否要在下次扫描完成后补写「应用内兜底」的识别歌词（重选文件夹拿到写权限时置真）。 */
    private var pendingLrcFlush = false

    /** 自定义封面选图（复制到内部存储，无需持久授权）。 */
    private var pendingCoverTarget: String? = null
    /** 单曲封面模式下的目标歌曲（取文件大小参与封面 key，2.11）。 */
    private var pendingCoverSong: Song? = null
    /** 批量封面模式：非空时 coverPicker 回调对这批歌曲批量写单曲封面。 */
    private var pendingBatchSongs: List<Song>? = null

    // ---- 歌词识别（1.33.1 实验）----
    @Volatile
    private var asrCancelled = false
    @Volatile
    private var asrRunning = false
    /** 正在实时显示识别歌词的歌曲 uri（边听边出）；null 表示无。 */
    @Volatile
    private var asrLiveUri: String? = null
    private var asrLiveLines: List<SubtitleLine> = emptyList()
    private lateinit var txtAsrStatus: TextView
    private lateinit var btnAsrCancel: Button
    private val asrAudioPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) startAsrTranscribe(uri, null)
        }
    /** 手动导入模型文件（Android 11+ 的 Android/data 目录文件管理器进不去）。 */
    private val asrModelPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) {
                val n = SpeechRecManager.importModelFiles(this, uris)
                PlaybackLog.log("asr manual import: $n files")
                toast(
                    if (n > 0) getString(R.string.asr_import_done, n)
                    else getString(R.string.asr_import_none)
                )
                if (n > 0 && SpeechRecManager.isModelReady(this)) showAsrDialog()
            }
        }
    private val coverPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val target = pendingCoverTarget
            pendingCoverTarget = null
            val targetSong = pendingCoverSong
            pendingCoverSong = null
            val batch = pendingBatchSongs
            pendingBatchSongs = null
            android.util.Log.d("ShiYinCover", "coverPicker uri=$uri target=$target batch=${batch?.size}")
            if (uri == null || (target == null && batch == null)) {
                return@registerForActivityResult
            }
            // 批量模式：对勾选歌曲逐一写单曲封面
            // 批量模式：源图只复制一次，所有勾选歌曲共享（避免 content uri 一次性读取导致坏引用）
            if (batch != null) {
                val shared = CoverManager.copySharedCover(this, uri)
                var ok = 0
                if (shared != null) {
                    batch.forEach { s ->
                        CoverManager.setSongCoverInternal(this, s.uri.toString(), s.size, shared)
                        ok++
                        CoverLoader.invalidate(s.uri.toString())
                    }
                }
                if (ok > 0) {
                    toast(getString(R.string.batch_cover_done, ok))
                    refreshLibGrid()
                    refreshCdCover()
                } else {
                    toast(getString(R.string.cover_failed))
                }
                return@registerForActivityResult
            }
            // 到此处 batch 分支已 return；若 target 为空（单选模式必非空）直接放弃
            val t = target ?: return@registerForActivityResult
            val ok = if (t.startsWith("pl:")) {
                CoverManager.setPlaylistCover(this, t.removePrefix("pl:"), uri) != null
            } else {
                CoverManager.setSongCover(
                    this, t.removePrefix("song:"), targetSong?.size ?: 0L, uri
                ) != null
            }
            if (ok) {
                toast(getString(R.string.cover_saved))
                refreshLibGrid()
                refreshCdCover()
                // 2.14：新歌单页是独立的 adapter，封面变化要单独通知它
                refreshPlaylistsPage()
            } else {
                toast(getString(R.string.cover_failed))
            }
        }

    /** 背景图选择（复制到内部存储）。 */
    private val bgPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            if (BgManager.setBg(this, uri)) {
                applyPageBackground()
                // 封面背景开启时提示优先级，否则用户会以为背景图没生效
                toast(
                    getString(
                        if (coverBgEnabled()) R.string.bg_saved_cover_bg_on else R.string.bg_saved
                    )
                )
            } else {
                toast(getString(R.string.bg_failed))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 最先安装全局崩溃捕获（越早越好），并检测上次未读崩溃日志 → 稍后弹窗
        CrashCatcher.install(applicationContext)
        val pendingCrash = CrashCatcher.takeCrashLog(this)
        // 在 Activity 创建前应用保存的深色模式（进程级设置，不调用会回系统默认导致深色失效）
        applyDarkMode(prefs.getBoolean(KEY_DARK, false))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (pendingCrash != null) {
            AlertDialog.Builder(this)
                .setTitle("崩溃日志")
                .setMessage(pendingCrash)
                .setPositiveButton("复制") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", pendingCrash))
                    toast("已复制，发给作者即可")
                }
                .setNegativeButton("知道了", null)
                .show()
        }

        viewDiscover = findViewById(R.id.pageDiscover)
        viewLibrary = findViewById(R.id.pageLibrary)
        viewPlaylist = findViewById(R.id.pagePlaylist)
        viewSearch = findViewById(R.id.pageSearch)
        viewPlayer = findViewById(R.id.pagePlayer)
        viewLyrics = findViewById(R.id.pageLyrics)
        txtAsrStatus = findViewById(R.id.txtAsrStatus)
        btnAsrCancel = findViewById(R.id.btnAsrCancel)
        btnAsrCancel.setOnClickListener { asrCancelled = true }
        asrStatusRowVisible(false)
        playerHeader = findViewById(R.id.playerHeader)
        playerControlsMain = findViewById(R.id.playerControlsMain)
        playerControlsExtra = findViewById(R.id.playerControlsExtra)
        txtImmersiveLyric = findViewById(R.id.txtImmersiveLyric)
        bottomNav = findViewById(R.id.bottomNav)
        viewFavorites = findViewById(R.id.pageFavorites)
        btnFavorite = findViewById(R.id.btnFavorite)
        recyclerFavorites = findViewById(R.id.recyclerFavorites)
        txtFavoritesEmpty = findViewById(R.id.txtFavoritesEmpty)
        btnFavorite.setOnClickListener {
            val song = playbackService?.currentSongSafe() ?: return@setOnClickListener
            val on = FavoritesManager.toggle(this, song.uri.toString())
            toast(getString(if (on) R.string.favorited else R.string.unfavorited))
            updateFavoriteButton(song)
            if (page == Page.FAVORITES) openFavorites()
        }

        // ---- 歌单列表页（2.14）----
        viewPlaylistsPage = findViewById(R.id.pagePlaylists)
        recyclerPlaylistsPage = findViewById(R.id.recyclerPlaylistsPage)
        txtPlaylistsEmpty = findViewById(R.id.txtPlaylistsEmpty)

        viewVideo = findViewById(R.id.pageVideo)
        // 视频列表页（导航栏「视频」，2.0）
        viewVideos = findViewById(R.id.pageVideos)
        recyclerVideos = findViewById(R.id.recyclerVideos)
        txtVideosEmpty = findViewById(R.id.txtVideosEmpty)
        findViewById<Button>(R.id.btnBackVideos).setOnClickListener { backFromPlayer() }
        videoAdapter = SongAdapter(
            hasLyric = { false },
            onClick = { pos ->
                if (videoSongs.isNotEmpty()) {
                    playSong(videoSongs, pos)
                    openVideoPage()
                }
            }
        )
        recyclerVideos.layoutManager = LinearLayoutManager(this)
        recyclerVideos.adapter = videoAdapter

        videoSurface = findViewById(R.id.videoSurface)
        txtVideoHint = findViewById(R.id.txtVideoHint)
        seekVideo = findViewById(R.id.seekVideo)
        txtVideoTime = findViewById(R.id.txtVideoTime)
        findViewById<Button>(R.id.btnVideoBack).setOnClickListener { closeVideoPage() }
        findViewById<ImageButton>(R.id.btnVideo).setOnClickListener { openVideoPage() }
        findViewById<ImageButton>(R.id.btnVideoPlay).setOnClickListener {
            playbackService?.togglePlay()
        }
        videoSurface.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                st: android.graphics.SurfaceTexture, width: Int, height: Int
            ) {
                PlaybackLog.log("video surface available ${width}x$height")
                playbackService?.attachVideoSurface(android.view.Surface(st))
                videoSurfaceAttached = true
                fitVideoSurface(width, height)
            }

            override fun onSurfaceTextureSizeChanged(
                st: android.graphics.SurfaceTexture, width: Int, height: Int
            ) {
                // 方向切换/布局变化后画面区域尺寸变了，必须重新适配（1.31）。
                // 之前留空导致沿用旧 matrix：打开视频页时系统先按竖屏布局回调 available，
                // 随即转横屏，旧 matrix 把画面缩放平移到已不存在的区域——只看到视频最上方的部分。
                fitVideoSurface(width, height)
            }

            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                videoSurfaceAttached = false
                // surface 销毁时立即脱离画面，避免 MediaPlayer 持续向已销毁 surface 输出（跳歌/闪退）
                playbackService?.attachVideoSurface(null)
                return true
            }

            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {
                // 新视频首帧到达：结束「等首帧」状态，显示画面（2.13）
                // 2.13：加时间门槛——切歌瞬间旧播放器可能仍有余帧在刷，若立刻判定
                // 「首帧已到」就会把旧画面当新画面显示出来（原 bug 复现）。
                // 只有「隐藏之后又过了至少一帧时长」才认账。
                if (videoFramePending && System.currentTimeMillis() >= videoFrameReadyAfter) {
                    videoFramePending = false
                    videoFrameHandler.removeCallbacksAndMessages(null)
                    videoSurface.alpha = 1f
                    PlaybackLog.log("video first frame shown")
                }
            }
        }
        videoSurface.setOnTouchListener { v, ev -> handleVideoTouch(v, ev) }
        // 2.13：布局尺寸变化后必须重新适配画面。
        // `openVideoPage` 里的 fit 是在 post 中调用，若此刻 view 刚 GONE→VISIBLE、
        // 布局尚未完成，拿到的 width/height 可能是 0 —— 而尺寸未知时只能给「不缩放」的
        // 保守矩阵，表现为画面按原始像素铺开、**只看到左上角一块**（粉丝反馈的「只显示一部分」）。
        // 布局监听可保证：无论何时布局落定，都用真实尺寸重算一次（幂等、开销极小）。
        videoSurface.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (l == ol && t == ot && r == or && b == ob) return@addOnLayoutChangeListener
            if (page == Page.VIDEO) {
                fitVideoSurface(r - l, b - t)
            }
        }
        seekVideo.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                sb: android.widget.SeekBar, progress: Int, fromUser: Boolean
            ) {
            }

            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                seeking = false
                playbackService?.seekTo(sb.progress)
            }
        })
        (viewPlayer as SwipeFrameLayout).onHorizontalSwipe = { dir, downY -> handleSwipe(dir, downY) }
        (viewLyrics as SwipeFrameLayout).onHorizontalSwipe = { dir, downY -> handleSwipe(dir, downY) }
        // 播放页上下滑切歌（1.33）：上滑下一首、下滑上一首（歌词页不启用），带过渡动画
        (viewPlayer as SwipeFrameLayout).onVerticalSwipe = { dir ->
            swipeSwitchSong(up = dir > 0)
        }

        miniPlayer = findViewById(R.id.miniPlayer)
        txtMiniTitle = findViewById(R.id.txtMiniTitle)
        btnMiniPlay = findViewById(R.id.btnMiniPlay)
        miniSeekBar = findViewById(R.id.miniSeekBar)

        recyclerDiscover = findViewById(R.id.recyclerDiscover)
        searchEntry = findViewById(R.id.searchEntry)
        segPlaylists = findViewById(R.id.segPlaylists)
        segArtists = findViewById(R.id.segArtists)
        recyclerPlaylists = findViewById(R.id.recyclerPlaylists)
        recyclerArtists = findViewById(R.id.recyclerArtists)

        txtPlaylistTitle = findViewById(R.id.txtPlaylistTitle)
        recyclerSongs = findViewById(R.id.recyclerSongs)

        etSearch = findViewById(R.id.etSearch)
        txtSearchHint = findViewById(R.id.txtSearchHint)
        recyclerSearch = findViewById(R.id.recyclerSearch)

        txtPlayerTitle = findViewById(R.id.txtPlayerTitle)
        txtPlayerFolder = findViewById(R.id.txtPlayerFolder)
        imgCd = findViewById(R.id.imgCd)
        imgCd.setOnLongClickListener {
            val idx = playbackService?.currentIndex() ?: -1
            currentSongs.getOrNull(idx)?.let { showSongMenu(it) }
            true
        }
        // 播放页任意点击：沉浸时恢复，平时重置沉浸计时
        viewPlayer.setOnClickListener {
            if (playerImmersed) exitImmersion() else scheduleImmersion()
        }
        txtNowLyric = findViewById(R.id.txtNowLyric)
        seekBar = findViewById(R.id.seekBar)
        txtTime = findViewById(R.id.txtTime)
        btnPlayPlayer = findViewById(R.id.btnPlayPlayer)
        recyclerLyricFull = findViewById(R.id.recyclerLyricFull)
        btnTranslate = findViewById(R.id.btnTranslate)
        txtLyricsTitle = findViewById(R.id.txtLyricsTitle)

        // ---- 发现页 ----
        discoverAdapter = DiscoverAdapter { pos ->
            if (discoverSongs.isNotEmpty() && pos in discoverSongs.indices) {
                playSong(discoverSongs, pos)
            }
        }
        recyclerDiscover.layoutManager = GridLayoutManager(this, 2)
        recyclerDiscover.adapter = discoverAdapter
        findViewById<Button>(R.id.btnRefreshDiscover).setOnClickListener { loadDiscover() }

        // ---- 音乐库页：歌单展示（默认网格大图标，设置里可切树形目录）----
        // 歌单排序入口（2.13）：库页「歌单/歌手」行最右端的下拉箭头
        findViewById<TextView>(R.id.btnPlaylistSort).setOnClickListener {
            showPlaylistSortDialog()
        }
        gridAdapter = PlaylistGridAdapter(
            { pos -> playlistList().getOrNull(pos)?.let { openPlaylist(it) } },
            { pos -> playlistList().getOrNull(pos)?.let { showPlaylistCoverMenu(it.name) } }
        )
        treeAdapter = FolderTreeAdapter(
            { node -> openTreeFolder(node) },
            { pl -> openPlaylist(pl) },
            { pl -> showPlaylistCoverMenu(pl.name) }
        )
        txtTreePath = findViewById(R.id.txtTreePath)
        txtTreePath.setOnClickListener { backTree() }
        recyclerPlaylists.layoutManager = GridLayoutManager(this, 2)
        recyclerPlaylists.adapter = gridAdapter
        applyLibLayout()

        // ---- 音乐库页：歌手 ----
        artistAdapter = ArtistAdapter { pos -> openArtistSongs(pos) }
        recyclerArtists.layoutManager = LinearLayoutManager(this)
        recyclerArtists.adapter = artistAdapter

        segPlaylists.setOnClickListener { showSegment(true) }
        segArtists.setOnClickListener { showSegment(false) }
        searchEntry.setOnClickListener { showSearchPage() }

        // ---- 歌单/歌曲列表 ----
        songAdapter = SongAdapter(
            hasLyric = { song ->
                library?.let { LibraryScanner.findLyric(song, it.lyrics) != null } ?: false
            },
            onClick = { pos ->
                if (currentSongs.isNotEmpty()) {
                    playSong(currentSongs, pos)
                }
            }
        )
        recyclerSongs.layoutManager = LinearLayoutManager(this)
        recyclerSongs.adapter = songAdapter
        playlistTouchHelper.attachToRecyclerView(recyclerSongs)

        // ---- 全屏歌词 ----
        lyricAdapter = LyricAdapter { pos -> onLyricClick(pos) }
        recyclerLyricFull.layoutManager = LinearLayoutManager(this)
        recyclerLyricFull.adapter = lyricAdapter
        // 用户手动滑动时记录时间（自动回位冷却；按下与移动都刷新，松手后才开始计 5 秒）
        recyclerLyricFull.setOnTouchListener { _, ev ->
            when (ev.action) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE ->
                    lastLyricUserScroll = System.currentTimeMillis()
            }
            false
        }

        // ---- 搜索 ----
        searchAdapter = SearchAdapter(
            hasLyric = { song ->
                library?.let { LibraryScanner.findLyric(song, it.lyrics) != null } ?: false
            },
            onPlaylistClick = { pos ->
                searchPlaylists.getOrNull(pos)?.let { openPlaylist(it) }
            },
            onSongClick = { pos ->
                if (searchSongs.isNotEmpty() && pos in searchSongs.indices) {
                    playSong(searchSongs, pos)
                }
            }
        )
        recyclerSearch.layoutManager = LinearLayoutManager(this)
        recyclerSearch.adapter = searchAdapter

        favoritesAdapter = SongAdapter(
            hasLyric = { s ->
                library?.let { LibraryScanner.findLyric(s, it.lyrics) != null } ?: false
            },
            onClick = { pos -> playSong(favoriteSongs, pos) },
            onLongClick = { showSongMenu(it) }
        )
        recyclerFavorites.layoutManager = LinearLayoutManager(this)
        recyclerFavorites.adapter = favoritesAdapter

        // ---- 歌单列表页（2.14）----
        playlistsPageAdapter = PlaylistGridAdapter(
            onClick = { pos ->
                val item = playlistsPageItems.getOrNull(pos) ?: return@PlaylistGridAdapter
                // 第一项「收藏」走原有收藏页；其余是自建歌单，进歌单详情
                if (item.name == favoritesEntryName) openFavorites() else openCustomPlaylist(item.name)
            },
            onLongClick = { pos ->
                val item = playlistsPageItems.getOrNull(pos) ?: return@PlaylistGridAdapter
                if (item.name == favoritesEntryName) {
                    // 收藏是固定项，不支持改名/删除（删了收藏页就没入口了）
                    showPlaylistCoverMenu(item.name)
                } else {
                    showCustomPlaylistMenu(item.name)
                }
            }
        )
        recyclerPlaylistsPage.layoutManager = GridLayoutManager(this, 2)
        recyclerPlaylistsPage.adapter = playlistsPageAdapter
        findViewById<Button>(R.id.btnNewPlaylist).setOnClickListener { showCreatePlaylistDialog() }

        // ---- 底部导航 ----

        // ---- 播放页控制 ----
        findViewById<Button>(R.id.btnBackSongs).setOnClickListener { backFromPlayer() }
        findViewById<Button>(R.id.btnLyrics).setOnClickListener { showPage(Page.LYRICS, 1) }
        findViewById<ImageButton>(R.id.btnQueue).setOnClickListener { showQueueDialog() }
        findViewById<ImageButton>(R.id.btnLyricsIcon).setOnClickListener { showPage(Page.LYRICS, 1) }
        findViewById<ImageButton>(R.id.btnPlayMode).setOnClickListener {
            val mode = playbackService?.cyclePlayMode() ?: 0
            updatePlayModeButton(mode)
            toast(
                when (mode) {
                    MediaPlaybackService.MODE_SHUFFLE -> getString(R.string.play_mode_shuffle)
                    MediaPlaybackService.MODE_REPEAT_ONE -> getString(R.string.play_mode_repeat_one)
                    else -> getString(R.string.play_mode_sequence)
                }
            )
        }
        updatePlayModeButton(playbackService?.getPlayMode() ?: 0)
        // 歌词页返回按钮（2.11）：与系统返回键一致——队列来自歌单时直接回音轨界面
        findViewById<Button>(R.id.btnBackLyrics).setOnClickListener { backFromLyricsPage() }
        findViewById<Button>(R.id.btnGenLyric).setOnClickListener {
            // 为当前播放的歌生成歌词（需已下载识别模型；自动检测台本提升准确率）
            startAsrForCurrentSong()
        }
        // 歌词整体校准（2.2）：整体 ±0.1s / ±0.3s 偏移，写回歌词文件
        findViewById<Button>(R.id.btnLyricOffset).setOnClickListener {
            showLyricOffsetDialog()
        }
        findViewById<Button>(R.id.btnTranslate).setOnClickListener {
            translateCurrentLyric()
        }
        // 长按翻译按钮：删除当前歌曲的翻译（1.30）
        findViewById<Button>(R.id.btnTranslate).setOnLongClickListener {
            deleteCurrentTranslation()
            true
        }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener {
            playbackService?.playPrev()
        }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener {
            playbackService?.playNext()
        }
        findViewById<ImageButton>(R.id.btnTimer).setOnClickListener {
            showSleepDialog()
        }
        // ---- 快进退 / 倍速（1.25）----
        btnSeekBack = findViewById(R.id.btnSeekBack)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnSeekForward = findViewById(R.id.btnSeekForward)
        btnSeekBack.setOnClickListener { seekRelative(-seekStepSeconds() * 1000) }
        btnSeekForward.setOnClickListener { seekRelative(seekStepSeconds() * 1000) }
        btnSpeed.setOnClickListener { cycleSpeed() }
        updateSeekButtons()
        btnPlayPlayer.setOnClickListener {
            playbackService?.togglePlay()
        }
        btnMiniPlay.setOnClickListener {
            playbackService?.togglePlay()
        }
        miniPlayer.setOnClickListener { showPage(Page.PLAYER) }
        txtNowLyric.setOnClickListener { showPage(Page.LYRICS) }

        // 应用保存的主题色
        applyAccent()
        // 应用保存的背景图
        applyPageBackground()

        // ---- 列表页返回 ----
        findViewById<Button>(R.id.btnBackLib).setOnClickListener { showPage(Page.LIBRARY) }
        findViewById<Button>(R.id.btnBackSearch).setOnClickListener { showPage(Page.LIBRARY) }

        // ---- 设置 ----
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            showSettingsDialog()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // 拖动中只预览时间，不实时 seek（高频 seek 导致解码卡顿，松手才跳转）
                    updateTime(progress)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                seeking = true
                cancelImmersion() // 按住进度条期间不进入沉浸（否则进度条被隐藏）
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                seeking = false
                playbackService?.seekTo(sb.progress)
                scheduleImmersion()
            }
        })

        miniSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {}

            override fun onStartTrackingTouch(sb: SeekBar) {
                seeking = true
                cancelImmersion()
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                seeking = false
                playbackService?.seekTo(sb.progress)
                scheduleImmersion()
            }
        })

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                doSearch(s?.toString() ?: "")
            }
        })

        buildNavTabs()
        selectModule(defaultModule())
        applyAppearance()

        // 恢复上次选择的文件夹（多文件夹 KEY_TREES；savedTreeUris 兼容旧单文件夹 KEY_TREE。
        // 注意不能直接读 KEY_TREE——addTreeUri 写入 KEY_TREES 后会清掉它，读旧 key 会漏掉全部恢复）
        val roots = savedTreeUris()
        if (roots.isNotEmpty()) {
            if (roots.all { hasPersistRead(it) }) {
                if (prefs.getBoolean(KEY_AUTO_SCAN, false)) {
                    scanLibrary()
                } else {
                    loadCachedLibrary()
                    // 缓存秒开后后台静默重扫：已导入文件夹新加的文件自动出现（1.31）
                    scanLibrary(silent = true)
                }
            } else {
                toast(getString(R.string.choose_folder_again))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, MediaPlaybackService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            playbackService?.setListener(null)
            unbindService(serviceConnection)
            bound = false
            playbackService = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelImmersion()
        cdAnimator?.cancel()
        cdAnimator = null
    }

    // ---------- 页面与导航 ----------

    // ---------- 导航（用户自定义：增删/排序/默认页） ----------
    private fun selectModule(module: String) {
        currentModule = module
        val accent = ThemeManager.accent(this)
        navTabs.forEach { (m, v) ->
            v.setTextColor(if (m == module) accent else getColor(R.color.text_hint))
            v.typeface = if (m == module) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        when (module) {
            MODULE_DISCOVER -> showPage(Page.DISCOVER)
            MODULE_LIBRARY -> { showPage(Page.LIBRARY); showSegment(true) }
            MODULE_ARTISTS -> { showPage(Page.LIBRARY); showSegment(false) }
            MODULE_FAVORITES -> openPlaylistsPage()
            MODULE_VIDEO -> openVideoList()
            else -> showPage(Page.LIBRARY)
        }
    }

    /** 按配置重建底部导航 tab。 */
    private fun buildNavTabs() {
        bottomNav.removeAllViews()
        navTabs.clear()
        navModules().forEach { m ->
            val tv = android.widget.TextView(this).apply {
                text = getString(navLabel(m))
                gravity = android.view.Gravity.CENTER
                setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
                textSize = 14f
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                setOnClickListener { selectModule(m) }
            }
            navTabs[m] = tv
            bottomNav.addView(tv)
        }
    }

    private fun navModules(): List<String> {
        // 2.0 迁移：老用户已保存的自定义导航补上新模块「视频」（仅一次）
        if (!prefs.getBoolean(KEY_NAV_MIGRATED_20, false)) {
            prefs.edit().putBoolean(KEY_NAV_MIGRATED_20, true).apply()
            val saved = prefs.getString(KEY_NAV_TABS, null)
            if (saved != null && !saved.contains(MODULE_VIDEO)) {
                prefs.edit().putString(KEY_NAV_TABS, "$saved,$MODULE_VIDEO").apply()
            }
        }
        return (prefs.getString(KEY_NAV_TABS, DEFAULT_NAV)?.split(",") ?: emptyList())
            .filter { it in ALL_MODULES }.ifEmpty { ALL_MODULES }
    }

    private fun defaultModule(): String {
        val d = prefs.getString(KEY_NAV_DEFAULT, null)
        val list = navModules()
        return if (d != null && list.contains(d)) d else list.first()
    }

    private fun navLabel(module: String): Int = when (module) {
        MODULE_DISCOVER -> R.string.tab_discover
        MODULE_LIBRARY -> R.string.tab_library
        MODULE_ARTISTS -> R.string.nav_artists
        MODULE_VIDEO -> R.string.tab_video
        // 2.14：这个入口从「收藏」升级为「歌单」页（收藏变成里面的固定第一项）。
        // MODULE_FAVORITES 这个 key 值**保持不动**，老用户保存的导航配置才继续有效。
        else -> R.string.nav_playlists
    }

    /** 导航栏设置弹窗：开关显示、上下移动排序、默认启动页。 */
    private fun showNavDialog() {
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
        }
        val order = navModules().toMutableList()
        var def = defaultModule()

        fun persist() {
            if (order.isEmpty()) order.add(MODULE_LIBRARY)
            prefs.edit().putString(KEY_NAV_TABS, order.joinToString(",")).apply()
            prefs.edit().putString(KEY_NAV_DEFAULT, def).apply()
            buildNavTabs()
            selectModule(if (navTabs.containsKey(currentModule)) currentModule else navTabs.keys.first())
        }

        fun render() {
            box.removeAllViews()
            ALL_MODULES.forEach { m ->
                val visible = order.contains(m)
                val row = android.widget.LinearLayout(this@MainActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                row.addView(android.widget.TextView(this@MainActivity).apply {
                    text = getString(navLabel(m))
                    textSize = 15f
                    setTextColor(getColor(if (visible) R.color.text_primary else R.color.text_hint))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                if (m == def) {
                    row.addView(android.widget.TextView(this@MainActivity).apply {
                        text = getString(R.string.nav_default_mark)
                        textSize = 11f
                        setTextColor(getColor(R.color.accent))
                    })
                }
                if (visible) {
                    val idx = order.indexOf(m)
                    if (idx > 0) {
                        row.addView(android.widget.Button(this@MainActivity).apply {
                            text = getString(R.string.nav_move_up)
                            setOnClickListener {
                                val j = order.indexOf(m)
                                order.removeAt(j); order.add(j - 1, m)
                                persist(); render()
                            }
                        })
                    }
                    if (idx < order.size - 1) {
                        row.addView(android.widget.Button(this@MainActivity).apply {
                            text = getString(R.string.nav_move_down)
                            setOnClickListener {
                                val j = order.indexOf(m)
                                order.removeAt(j); order.add(j + 1, m)
                                persist(); render()
                            }
                        })
                    }
                }
                row.addView(android.widget.Switch(this@MainActivity).apply {
                    isChecked = visible
                    setOnCheckedChangeListener { _, checked ->
                        if (checked && !order.contains(m)) {
                            order.add(m)
                        } else if (!checked && order.contains(m)) {
                            order.remove(m)
                            if (def == m) def = order.firstOrNull() ?: m
                        }
                        persist(); render()
                    }
                })
                box.addView(row)
            }
            // 默认启动页
            box.addView(android.widget.TextView(this@MainActivity).apply {
                text = getString(R.string.nav_default_page)
                textSize = 16f
                setTextColor(getColor(R.color.text_primary))
                setPadding(0, dp(14f), 0, dp(10f))
                setOnClickListener {
                    val opts = order.map { getString(navLabel(it)) }.toTypedArray()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.nav_default_page)
                        .setItems(opts) { d, which ->
                            def = order[which]
                            persist(); render()
                            d.dismiss()
                        }
                        .show()
                }
            })
        }
        render()
        AlertDialog.Builder(this)
            .setTitle(R.string.nav_title)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 应用主题色：进度条、播放按钮、tab、列表/歌词高亮、全局蓝色按钮。 */
    private fun applyAccent() {
        val a = ThemeManager.accent(this)
        val list = android.content.res.ColorStateList.valueOf(a)
        // 1.30：进度条独立配色，未设置时跟随主题色
        val sb = prefs.getInt(KEY_SEEKBAR_COLOR, SB_DEFAULT)
        val sbList = android.content.res.ColorStateList.valueOf(if (sb == SB_DEFAULT) a else sb)
        seekBar.progressTintList = sbList
        seekBar.thumbTintList = sbList
        miniSeekBar.progressTintList = sbList
        miniSeekBar.thumbTintList = sbList
        seekVideo.progressTintList = sbList
        seekVideo.thumbTintList = sbList
        // 播放键图标保持白色（背景由全局遍历 tint，图标 tint 会与背景同色消失）
        // CD 圆形底（bg_play_circle）与全局按钮跟随主题色
        imgCd.backgroundTintList = list
        txtNowLyric.setTextColor(a)
        tintAccentViews(findViewById<View>(android.R.id.content), list)
        selectModule(currentModule)
        songAdapter.notifyDataSetChanged()
        lyricAdapter.notifyDataSetChanged()
        discoverAdapter.notifyDataSetChanged()
        refreshLibGrid()
        artistAdapter.notifyDataSetChanged()
        searchAdapter.notifyDataSetChanged()
    }

    /** 递归遍历：所有 Button/ImageButton 的背景统一 tint 为主题色（覆盖 BtnStyle/bg_play_circle）。 */
    private fun tintAccentViews(view: View, list: android.content.res.ColorStateList) {
        if (view is Button || view is ImageButton) {
            view.backgroundTintList = list
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                tintAccentViews(view.getChildAt(i), list)
            }
        }
    }

    /** 当前生效的非当前歌词颜色（未自定义时取主题 text_normal）。 */
    private fun currentIdleColor(): Int {
        val saved = prefs.getInt(KEY_LYRIC_IDLE_COLOR, IDLE_DEFAULT)
        return if (saved == IDLE_DEFAULT) getColor(R.color.text_normal) else saved
    }

    /** 当前生效的播放中歌词颜色（未自定义时跟随主题色）。 */
    private fun currentCurColor(): Int {
        val saved = prefs.getInt(KEY_LYRIC_CUR_COLOR, CUR_DEFAULT)
        return if (saved == CUR_DEFAULT) ThemeManager.accent(this) else saved
    }

    /** 当前生效的进度条颜色（未自定义时跟随主题色）。 */
    private fun currentSeekColor(): Int {
        val saved = prefs.getInt(KEY_SEEKBAR_COLOR, SB_DEFAULT)
        return if (saved == SB_DEFAULT) ThemeManager.accent(this) else saved
    }

    /**
     * 通用色板网格弹窗（1.30 抽公共实现）：每行 3 个色块，
     * 点击回调 onPicked（保存/应用/关弹窗由调用方决定）。
     */
    private fun showColorGridDialog(titleRes: Int, colors: IntArray, onPicked: (Int) -> Unit) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val cell = (56 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        var row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        box.addView(row)
        for (i in colors.indices) {
            if (i > 0 && i % 3 == 0) {
                row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
                box.addView(row)
            }
            val v = TextView(this).apply {
                text = ""
                setBackgroundColor(colors[i])
                layoutParams = android.widget.LinearLayout.LayoutParams(cell, cell).apply {
                    marginEnd = (12 * resources.displayMetrics.density).toInt()
                    bottomMargin = (12 * resources.displayMetrics.density).toInt()
                }
            }
            row.addView(v)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(titleRes))
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .create()
        @Suppress("UsePropertyAccessSyntax")
        var idx = 0
        outer@ for (i in 0 until box.childCount) {
            val childRow = box.getChildAt(i) as android.widget.LinearLayout
            for (c in 0 until childRow.childCount) {
                val cellView = childRow.getChildAt(c)
                val color = colors[idx]
                idx++
                cellView.setOnClickListener {
                    onPicked(color)
                    dialog.dismiss()
                }
                if (idx >= colors.size) break@outer
            }
        }
        dialog.show()
    }

    /** 非当前歌词颜色选择弹窗。onPicked 供主题弹窗实时刷新圆点。 */
    private fun showLyricIdleColorDialog(onPicked: ((Int) -> Unit)? = null) {
        showColorGridDialog(R.string.lyric_idle_color_title, LYRIC_IDLE_COLORS) { color ->
            prefs.edit().putInt(KEY_LYRIC_IDLE_COLOR, color).apply()
            applyAppearance()
            onPicked?.invoke(color)
            toast(getString(R.string.lyric_idle_color_saved))
        }
    }

    /** 播放中歌词颜色选择弹窗：加宽色板，避免自定义背景下看不清（1.30）。 */
    private fun showLyricCurColorDialog(onPicked: ((Int) -> Unit)? = null) {
        showColorGridDialog(R.string.lyric_cur_color_title, PALETTE_12) { color ->
            prefs.edit().putInt(KEY_LYRIC_CUR_COLOR, color).apply()
            applyAppearance()
            onPicked?.invoke(color)
            toast(getString(R.string.lyric_cur_color_saved))
        }
    }

    /** 进度条颜色选择弹窗：12 色板，长按可恢复跟随主题色（1.30）。 */
    private fun showSeekbarColorDialog(onPicked: ((Int) -> Unit)? = null) {
        showColorGridDialog(R.string.seekbar_color_title, PALETTE_12) { color ->
            prefs.edit().putInt(KEY_SEEKBAR_COLOR, color).apply()
            applyAccent()
            onPicked?.invoke(color)
            toast(getString(R.string.seekbar_color_saved))
        }
    }

    /** 桌面歌词文字颜色选择弹窗：与非当前歌词色板同一套预设。onPicked 供设置弹窗刷新按钮态。 */
    private fun showDesktopLyricColorDialog(onPicked: ((Int) -> Unit)? = null) {
        showColorGridDialog(R.string.desktop_lyrics_color_title, LYRIC_IDLE_COLORS) { color ->
            prefs.edit().putInt(KEY_DESKTOP_COLOR, color).apply()
            playbackService?.refreshDesktopLyricsStyle()
            onPicked?.invoke(color)
            toast(getString(R.string.desktop_lyrics_color_saved))
        }
    }

    private fun showAccentDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val cell = (64 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        var row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        box.addView(row)
        val cur = ThemeManager.accent(this)
        for ((i, color) in ThemeManager.PRESETS.withIndex()) {
            if (i > 0 && i % 4 == 0) {
                val newRow = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                }
                box.addView(newRow)
                row = newRow
            }
            val v = View(this).apply {
                setBackgroundColor(color)
                layoutParams = android.widget.LinearLayout.LayoutParams(cell, cell).apply {
                    marginEnd = (12 * resources.displayMetrics.density).toInt()
                    bottomMargin = (12 * resources.displayMetrics.density).toInt()
                }
                setOnClickListener {
                    ThemeManager.save(this@MainActivity, color)
                    applyAccent()
                    toast(getString(R.string.accent_saved))
                }
            }
            row.addView(v)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.accent_title)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 播放页/歌词页背景统一刷新（2.12.1）。
     * 优先级：封面背景（开关开启且有当前封面）→ 用户自定义背景图 → 播放页封面主色渐变 → 纯色。
     * 注意必须在这里一次性定夺，否则歌词页刷自定义背景图时会把播放页的主色渐变冲掉。
     */
    private fun applyPageBackground() {
        val cover = if (coverBgEnabled()) currentCoverBmp else null
        val bgUri = BgManager.bgUri(this)
        if (cover != null) {
            // 两页各建一个 Drawable 实例：Drawable 的 bounds/state 是实例级的，
            // 共享同一实例在两个 View 上会互相踩（bitmap 本身仍是共享引用，不额外占内存）
            viewPlayer.background = BgManager.coverDrawable(cover)
            viewLyrics.background = BgManager.coverDrawable(cover)
        } else {
            BgManager.apply(viewPlayer, bgUri)
            BgManager.apply(viewLyrics, bgUri)
            // 没有自定义背景图时，播放页才用封面主色渐变（暗化保证可读）
            if (bgUri == null) applyPlayerBackground(currentCoverBmp)
        }
        applyLyricTextOnCover(cover != null)
    }

    /** 封面背景开关（设置-主题）。 */
    private fun coverBgEnabled(): Boolean = prefs.getBoolean(KEY_COVER_BG, false)

    /**
     * 封面加载目标边长（2.12.1）。
     * CD 封面原本只要 400px，但同一张图现在要铺满整屏（1080p 上下），
     * 400px 放大会明显发糊。取屏幕长边并夹在 [COVER_LOAD_MIN, COVER_LOAD_MAX]：
     * 兼顾清晰度与内存（配合 CoverLoader 的 24MB LRU 上限）。
     */
    private fun coverLoadSize(): Int {
        val dm = resources.displayMetrics
        val longest = maxOf(dm.widthPixels, dm.heightPixels)
        return longest.coerceIn(COVER_LOAD_MIN, COVER_LOAD_MAX)
    }

    /**
     * 封面背景开启时把两页文字提亮（遮罩固定 70% 黑，文字必须始终浅色）；
     * 关闭时恢复主题色。播放页主色渐变背景已自带暗化，无需处理。
     */
    private fun applyLyricTextOnCover(onCover: Boolean) {
        val primary = if (onCover) getColor(R.color.cover_text_on_photo) else getColor(R.color.text_primary)
        val hint = if (onCover) getColor(R.color.cover_text_hint_on_photo) else getColor(R.color.text_hint)
        txtLyricsTitle.setTextColor(primary)
        txtPlayerTitle.setTextColor(primary)
        txtPlayerFolder.setTextColor(hint)
        txtTime.setTextColor(primary)
        txtImmersiveLyric.setTextColor(primary)
        // 译者行取色在适配器里，状态变了才重刷（避免每次刷背景都全量 notify）
        if (onCover != lastCoverTextState) {
            lastCoverTextState = onCover
            applyAppearance()
        }
    }

    /** 背景图设置弹窗。 */
    private fun showBgDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.bg_title)
            .setItems(
                arrayOf(getString(R.string.bg_set), getString(R.string.bg_clear))
            ) { _, which ->
                when (which) {
                    0 -> bgPicker.launch("image/*")
                    1 -> {
                        BgManager.clearBg(this)
                        applyPageBackground()
                        toast(getString(R.string.bg_cleared))
                    }
                }
            }
            .show()
    }

    /**
     * @param slide 切换动画方向：1 = 从右侧滑入（左滑翻页效果），-1 = 从左侧滑入，0 = 淡入
     */
    private fun showPage(p: Page, slide: Int = 0) {
        page = p
        // 视频页沉浸全屏：隐藏系统栏，把画面区域让给视频，收窄四周留白（1.31 优化）
        if (p == Page.VIDEO) enterVideoImmersive() else exitVideoImmersive()
        if (p == Page.LYRICS) {
            // 进入歌词页直接定位到当前播放行（1.31）：
            // 清掉手动滑动冷却，否则刚滑过就切页会停在原处
            lastLyricUserScroll = 0
            scrollToLyric(currentLyricHighlight)
        }
        if (p == Page.PLAYER) {
            exitImmersion()
            scheduleImmersion()
        } else {
            cancelImmersion()
            exitImmersion()
        }
        val shows = listOf(
            viewDiscover to (p == Page.DISCOVER),
            viewLibrary to (p == Page.LIBRARY),
            viewPlaylist to (p == Page.PLAYLIST),
            viewSearch to (p == Page.SEARCH),
            viewPlayer to (p == Page.PLAYER),
            viewLyrics to (p == Page.LYRICS),
            viewFavorites to (p == Page.FAVORITES),
            viewPlaylistsPage to (p == Page.PLAYLISTS),
            viewVideos to (p == Page.VIDEOS),
            viewVideo to (p == Page.VIDEO)
        )
        for ((v, show) in shows) {
            if (show && v.visibility != View.VISIBLE) {
                if (slide > 0 || slide < 0) {
                    v.alpha = 1f
                    v.translationX = if (slide > 0) {
                        resources.displayMetrics.widthPixels.toFloat()
                    } else {
                        -resources.displayMetrics.widthPixels.toFloat()
                    }
                    v.visibility = View.VISIBLE
                    v.animate().translationX(0f).setDuration(220).start()
                } else {
                    v.alpha = 0f
                    v.visibility = View.VISIBLE
                    v.animate().alpha(1f).setDuration(180).start()
                }
            } else if (!show && v.visibility == View.VISIBLE) {
                v.visibility = View.GONE
            }
        }
        // 视频页全屏：隐藏底部导航
        if (p == Page.VIDEO) {
            if (bottomNav.visibility != View.GONE) bottomNav.visibility = View.GONE
        } else if (p != Page.PLAYER && p != Page.LYRICS) {
            if (bottomNav.visibility != View.VISIBLE) bottomNav.visibility = View.VISIBLE
        }
        // 底部迷你条显隐（与 onSongChanged 共用同一个判定，避免两处副本走样）
        updateMiniPlayerVisibility()
    }

    /**
     * 底部迷你播放条显隐（单一判定点，2.13）。
     *
     * 播放页 / 歌词页 / **视频页**都不显示：它们各自有完整播放控件或全屏画面，
     * 再挂一条迷你条会出现「双进度条」或压在视频画面下方。
     *
     * 为什么抽成一个函数：此前 showPage() 与 onSongChanged() 各写了一份同样的判定，
     * 视频页只加进了其中一份 —— 于是「从视频列表点进视频」这条路径（先 onSongChanged
     * 再 showPage）漏判，视频页下方冒出迷你条（回归 bug）。判定只有一处就不会再走样。
     */
    private fun updateMiniPlayerVisibility() {
        if (!MiniPlayerRules.shouldShow(hasSong, page)) {
            if (miniPlayer.visibility != View.GONE) miniPlayer.visibility = View.GONE
        } else if (miniPlayer.visibility != View.VISIBLE) {
            miniPlayer.visibility = View.VISIBLE
            miniPlayer.alpha = 0f
            miniPlayer.translationY = 40f
            miniPlayer.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .start()
        }
    }

    private fun backFromPlayer() {
        selectModule(currentModule)
    }

    /** 导航栏「视频」：列出库中全部视频文件，点按即播放（2.0）。 */
    private fun openVideoList() {
        refreshVideoList()
        showPage(Page.VIDEOS)
        // 兜底：库就绪时通常已预热过；万一没有（如直接加载缓存后立刻进来），这里补一次
        warmVideoFlags()
    }

    /** 用当前已知的「是否含视频轨」结果刷新视频列表（2.13）。 */
    private fun refreshVideoList() {
        // 用只查缓存的版本：列表过滤在**主线程**，不能逐首读文件头。
        // 未命中时按扩展名兜底为「是视频」→ 列表先显示、预热完成后自动收窄（只会先多后准）
        videoSongs = library?.allSongs?.filter { isVideoFileCached(it.uri) } ?: emptyList()
        videoAdapter.submit(videoSongs)
        txtVideosEmpty.visibility = if (videoSongs.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * 后台补全「是否含视频轨」判定（2.13）。
     *
     * `isVideoFile` 要读文件头，而打开列表与切歌都在主线程，逐首探测会卡顿；
     * 这里在后台把结果填进 [videoFlagCache]，完成后回主线程刷新列表。
     * 尚未探测的文件在 [isVideoFileCached] 里按扩展名兜底为「是视频」，所以列表**只会先多后准**，
     * 不会把真视频漏掉。
     *
     * 状态都只在主线程读写（探测循环除外）：同一时刻只跑一个后台线程，
     * 期间若又请求了一次（库更新），等它结束再补跑一轮。
     */
    private fun warmVideoFlags() {
        val lib = library ?: return
        if (!videoWarmRunning.compareAndSet(false, true)) {
            videoWarmPending = true
            return
        }
        val targets = lib.allSongs.map { it.uri }.distinct()
        Thread {
            for (u in targets) {
                // 与 isVideoFile 共用同一条规则，避免两处判断走样
                if (!VideoFileRules.couldBeVideo(u.lastPathSegment)) continue
                val key = u.toString()
                if (videoFlagCache.containsKey(key)) continue
                videoFlagCache[key] = VideoFileRules.resolve(true, probeVideoTrack(u))
            }
            runOnUiThread {
                videoWarmRunning.set(false)
                if (videoWarmPending) {
                    videoWarmPending = false
                    warmVideoFlags() // 回主线程重新取库快照再跑一轮
                }
                if (!isFinishing && !isDestroyed) refreshVideoList()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun updatePlayModeButton(mode: Int) {
        val btn = findViewById<ImageButton>(R.id.btnPlayMode)
        val (icon, label) = when (mode) {
            MediaPlaybackService.MODE_SHUFFLE -> R.drawable.ic_shuffle to R.string.play_mode_shuffle
            MediaPlaybackService.MODE_REPEAT_ONE -> R.drawable.ic_repeat_one to R.string.play_mode_repeat_one
            else -> R.drawable.ic_repeat to R.string.play_mode_sequence
        }
        btn.setImageResource(icon)
        btn.contentDescription = getString(label)
    }

    // ---------- 左右滑动切换播放页/歌词页 ----------

    /**
     * 处理 SwipeFrameLayout 识别到的水平滑动。
     * @param dir > 0 左滑；< 0 右滑
     * @param downYLocal 按下点相对页面根布局的 Y 坐标（与 seekBar.top 同一坐标系，零换算误差）
     */
    private fun handleSwipe(dir: Int, downYLocal: Float) {
        if (dir > 0) {
            // 左滑：播放页 → 歌词页；排除底部进度条/控制区（本地坐标对比）
            if (page == Page.PLAYER &&
                downYLocal < seekBar.top - 24 * resources.displayMetrics.density
            ) {
                showPage(Page.LYRICS, 1)
            }
        } else {
            // 右滑：歌词页 → 播放页
            if (page == Page.LYRICS) showPage(Page.PLAYER, -1)
        }
    }

    // ---------- 播放页上下滑切歌（1.33）----------

    private var playerSwipeAnimating = false
    private val swipeAnimHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 上下滑切歌 + 过渡动画：页面内容向滑动方向滑出淡出 → 切歌（onSongChanged
     * 同步刷新文案/封面）→ 从反方向滑入淡入。动画只作用于内容子控件，
     * 页面背景（封面渐变/主题）保持不动，不会闪底色。
     */
    private fun swipeSwitchSong(up: Boolean) {
        if (playerSwipeAnimating) return
        playerSwipeAnimating = true
        // 动画被其他动画打断时（ViewPropertyAnimator 的 withEndAction 不会回调），
        // 用固定时序 + 兜底解锁保证手势始终可用
        val unlock = Runnable { playerSwipeAnimating = false }
        val container = viewPlayer as? android.view.ViewGroup
        val children = if (container == null) {
            emptyList()
        } else {
            (0 until container.childCount).mapNotNull { container.getChildAt(it) }
                .filter { it.visibility == View.VISIBLE }
        }
        if (children.isEmpty()) {
            if (up) playbackService?.playNext() else playbackService?.playPrev()
            swipeAnimHandler.postDelayed(unlock, 300)
            return
        }
        val off = resources.displayMetrics.heightPixels * 0.22f
        val outY = if (up) -off else off
        val inY = if (up) off else -off
        // 滑出淡出（140ms）
        children.forEach { v ->
            v.animate().translationY(outY).alpha(0f).setDuration(140).start()
        }
        swipeAnimHandler.postDelayed({
            // 此刻内容已不可见：切歌（onSongChanged 在 playNext 内同步刷新界面）
            if (up) playbackService?.playNext() else playbackService?.playPrev()
            // 从反方向滑入淡入（200ms）
            children.forEach { c ->
                if (c.visibility == View.VISIBLE) {
                    c.translationY = inY
                    c.animate().translationY(0f).alpha(1f).setDuration(200).start()
                }
            }
            swipeAnimHandler.postDelayed(unlock, 260)
        }, 150)
    }

    override fun onBackPressed() {
        // 树形目录逐级进入：先返回上一级，不退出
        if (page == Page.LIBRARY && isTreeMode() && treeStack.isNotEmpty()) {
            backTree()
            return
        }
        when (page) {
            Page.LYRICS -> backFromLyricsPage()
            Page.PLAYER -> backFromPlayerPage()
            Page.VIDEO -> closeVideoPage()
            Page.PLAYLIST, Page.SEARCH -> showPage(Page.LIBRARY)
            // 2.14：收藏页现在是「歌单」页的子页（从那里点进来），返回它才是对的
            Page.FAVORITES -> openPlaylistsPage()
            else -> super.onBackPressed()
        }
    }

    /**
     * 歌词页返回（2.11 按弱志反馈）：队列来自某个列表页时直接回该页
     * （歌单播放 → 该歌单的音轨界面），不再先绕回播放页、再退回主页。
     */
    private fun backFromLyricsPage() {
        if (backToQueueSource(slide = -1)) return
        showPage(Page.PLAYER, -1)
    }

    /** 播放页返回：同上，来源页不可用时走原逻辑（回主导航页）。 */
    private fun backFromPlayerPage() {
        if (backToQueueSource(slide = -1)) return
        backFromPlayer()
    }

    /**
     * 返回队列来源页。歌单来源时用「当前播放歌曲所在歌单」重新填充歌单页，
     * 避免浏览过别的歌单后返回看到不相干的列表。成功返回 true。
     */
    private fun backToQueueSource(slide: Int): Boolean {
        val src = queueSourcePage ?: return false
        if (src == Page.PLAYLIST) {
            val song = playbackService?.currentSongSafe()
            val lib = library
            // 2.14：先看是不是自建歌单发起的队列。自建歌单不属于 lib.playlists
            // （它是用户手写的集合），必须单独查，否则从自建歌单播放后按返回会
            // 找不到歌单、退回主导航页。
            if (song != null) {
                val uriStr = song.uri.toString()
                val own = CustomPlaylistStore.load(this).entries.firstOrNull { (_, uris) ->
                    uris.contains(uriStr)
                }
                if (own != null) {
                    openCustomPlaylist(own.key)
                    showPage(Page.PLAYLIST, slide)
                    return true
                }
            }
            val pl = if (song != null && lib != null) {
                lib.playlists.firstOrNull {
                    it.name == song.folder && it.songs.any { s -> s.uri == song.uri }
                }
            } else {
                null
            }
            if (pl != null) {
                bindPlaylist(pl)
                showPage(Page.PLAYLIST, slide)
                return true
            }
            // 当前队列没有对应歌单（全库队列等）→ 回列表页会显示旧数据，放弃
            return false
        }
        showPage(src, slide)
        return true
    }

    // ---------- 扫描与数据 ----------

    /** 已添加的扫描根目录 uri 列表（多文件夹）。 */
    private fun savedTreeUris(): List<Uri> =
        prefs.getString(KEY_TREES, null)
            ?.split(",")
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty)?.let { s -> try { Uri.parse(s) } catch (_: Exception) { null } } }
            ?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(prefs.getString(KEY_TREE, null)?.let { try { Uri.parse(it) } catch (_: Exception) { null } })

    private fun addTreeUri(uri: Uri) {
        val list = (savedTreeUris() + uri).distinctBy { it.toString() }
        prefs.edit()
            .putString(KEY_TREES, list.joinToString(",") { it.toString() })
            .remove(KEY_TREE)
            .apply()
    }

    private fun treeUri(): Uri? = savedTreeUris().firstOrNull()

    /**
     * 扫描音乐库。
     * @param silent 静默模式（启动时后台刷新）：不弹「扫描中」，仅当库内容有变化才提示，
     *               让「已导入文件夹新增的文件」不用手动重新扫描就能出现（1.31）
     */
    private fun scanLibrary(silent: Boolean = false) {
        if (scanning) return
        scanning = true
        if (!silent) toast(getString(R.string.scanning))
        Thread {
            val roots = savedTreeUris()
            val lib = try {
                LibraryScanner(this, contentResolver).scanAll(roots)
            } catch (e: Exception) {
                null
            }
            runOnUiThread { finishScan(lib, silent) }
        }.start()
    }

    /**
     * 添加文件夹后的**增量**扫描（2.13）：只扫新根，与已有库合并，不重扫原有文件夹。
     *
     * 大库（几万首）时全量重扫要遍历所有目录，而新增一个文件夹本不需要碰旧目录。
     * 做法：把「已有库」当成一个虚拟根，与新根的扫描结果一起交给 [LibraryScanner.mergeRootScans]，
     * 走的合并规则与全量完全一致。
     *
     * **安全阀**：跨根同名子文件夹会触发消歧（已有的 `Music` 也要改名 `根A/Music`），
     * 那种情况增量无法等同全量 —— 检测到撞名就自动退回全量重扫（正确性优先于速度）。
     *
     * @param newRoot 刚添加的根
     */
    private fun scanLibraryIncremental(newRoot: Uri, silent: Boolean = false) {
        if (scanning) return
        val old = library
        // 没有旧库可合并 → 没有「增量」可言，直接全量
        if (old == null || old.allSongs.isEmpty()) {
            scanLibrary(silent)
            return
        }
        scanning = true
        if (!silent) toast(getString(R.string.scanning))
        Thread {
            val scanner = LibraryScanner(this, contentResolver)
            val fresh = try {
                scanner.scanOneRoot(newRoot)
            } catch (e: Exception) {
                null
            }
            if (fresh == null) {
                runOnUiThread {
                    scanning = false
                    if (!silent) toast(getString(R.string.choose_folder_again))
                }
                return@Thread
            }
            // 撞名检测：新根的相对路径若与已有歌单名重合，消歧会改动旧歌单名 → 必须全量
            val oldNames = old.allSongs.map { it.folder }.toSet() +
                old.lyrics.keys.map { it.substringBeforeLast('/') }.toSet()
            val newRels = fresh.songs.map { it.folder }.toSet() +
                fresh.lyrics.keys.map { it.substringBeforeLast('/') }.toSet()
            val needFull = LibraryScanner.needsFullRescan(oldNames, newRels)
            if (needFull) {
                PlaybackLog.log("add folder: name clash -> full rescan")
                runOnUiThread {
                    scanning = false
                    scanLibrary(silent)
                }
                return@Thread
            }
            val merged = try {
                LibraryScanner.rebuildFromExisting(old, fresh)
            } catch (e: Exception) {
                null
            }
            if (merged == null) {
                runOnUiThread {
                    scanning = false
                    scanLibrary(silent)
                }
            } else {
                PlaybackLog.log("add folder: incremental scan ok (+${fresh.songs.size})")
                runOnUiThread { finishScan(merged, silent) }
            }
        }.start()
    }

    /**
     * 扫描完成后的统一收尾（全量与增量共用，避免两份副本走样）。
     * 负责：写缓存、更新内存库、提示、刷新界面与服务。
     */
    private fun finishScan(lib: MusicLibrary?, silent: Boolean) {
        scanning = false
        if (lib != null && lib.allSongs.isNotEmpty()) {
            LibraryCache.save(applicationContext, lib)
        }
        when {
            lib == null -> if (!silent) toast(getString(R.string.choose_folder_again))
            lib.allSongs.isEmpty() -> if (!silent) toast(getString(R.string.no_audio))
            else -> {
                val changed = library?.allSongs?.size != lib.allSongs.size ||
                    library?.playlists?.size != lib.playlists.size
                library = lib
                if (!silent || changed) {
                    toast(
                        getString(
                            R.string.loaded_summary,
                            lib.allSongs.size,
                            lib.playlists.size
                        )
                    )
                }
                onLibraryReady()
                refreshOpenViews()
                // 刚拿到音乐文件夹写权限（重选了文件夹）：把应用内兜底的识别歌词补写回音频同目录
                if (pendingLrcFlush) {
                    pendingLrcFlush = false
                    flushInternalLrc()
                }
                // 歌词映射同步到服务：ASR 新生成的 lrc 无需切歌立即可用（1.33.1）。
                // refreshLyricMap 走 onSongChanged 会把倍速按钮重置为 1x，需回写真实速度
                playbackService?.refreshLyricMap(lib.lyrics)
                val spd = playbackService?.currentSpeed() ?: 1f
                btnSpeed.text = if (spd == 1f) "1x" else "${spd}x"
            }
        }
    }

    /**
     * 把应用内兜底的识别歌词（filesDir/asr_lrc 目录里的 .lrc）补写到音频同目录。
     * 场景：2.0 及更早选文件夹时没申请写权限，识别出的 .lrc 只能存应用内；
     * 用户在 2.1 里重选一次文件夹（这次会真正授予写权限）后自动搬回音乐文件夹，
     * 不用重新识别。成功后删除应用内副本。
     */
    private fun flushInternalLrc() {
        val lib = library ?: return
        if (lib.allSongs.isEmpty()) return
        Thread {
            val n = try {
                SpeechRecManager.flushInternalLrc(this, lib.allSongs, savedTreeUris())
            } catch (e: Exception) {
                PlaybackLog.log("asr lrc flush wrapper THREW: ${e.message}")
                0
            }
            if (n > 0) runOnUiThread {
                toast(getString(R.string.asr_lrc_flushed, n))
                scanLibrary(silent = true)
            }
        }.start()
    }

    /** 扫描完成后刷新已打开的列表页，让新增/改名立即生效（1.31）。 */
    private fun refreshOpenViews() {
        if (page == Page.PLAYLIST) {
            val name = txtPlaylistTitle.text.toString()
            // 自建歌单不在 lib.playlists 里，得从自己的数据源刷新。
            // 用字段而不是按名字查——重名时按名字查会刷新错来源。
            if (playlistIsCustom) {
                openCustomPlaylist(name)
            } else {
                playlistList().firstOrNull { it.name == name }?.let { openPlaylist(it) }
            }
        } else if (page == Page.FAVORITES) {
            openFavorites()
        } else if (page == Page.PLAYLISTS) {
            refreshPlaylistsPage()
        } else if (page == Page.VIDEOS) {
            openVideoList()
        }
    }

    private fun onLibraryReady() {
        applyLibLayout()
        loadDiscover()
        artistLoaded = false
        artistGroups = emptyList()
        artistAdapter.submit(emptyList())
        if (page == Page.LIBRARY && !segArtistsShown()) {
            // 保持当前分段
        }
        // 库变了 → 之前的「是否含视频轨」判定作废（文件可能被替换/删除），清掉重新探测
        videoFlagCache.clear()
        warmVideoFlags()
        maybeResumeLastSong()
    }

    private fun segArtistsShown(): Boolean = recyclerArtists.visibility == View.VISIBLE

    private fun loadCachedLibrary() {
        val cached = LibraryCache.load(this)
        if (cached == null || cached.allSongs.isEmpty()) {
            toast(getString(R.string.no_cache))
            return
        }
        library = cached
        toast(
            getString(
                R.string.loaded_summary,
                cached.allSongs.size,
                cached.playlists.size
            )
        )
        onLibraryReady()
    }

    private fun playlistList(): List<Playlist> {
        val lib = library ?: return emptyList()
        val lists = mutableListOf(Playlist(getString(R.string.all_songs), lib.allSongs))
        lists.addAll(lib.playlists)
        // 「全部歌曲」这一项固定排在最前（不是歌单，不该参与排序，2.13）
        return applyPlaylistSort(lists)
    }

    /**
     * 音乐库页歌单排序（2.13）。
     * - 「全部歌曲」永远排第一位，不参与排序；
     * - 随机顺序用**会话级固定种子**，否则每次重绘顺序都变（用户会以为列表在跳）；
     * - 按名称用 Collator(Locale.CHINA)，中文按拼音排而不是 Unicode 码位。
     * 排序只影响展示，不改任何持久化数据。
     */
    private fun applyPlaylistSort(lists: List<Playlist>): List<Playlist> {
        if (lists.isEmpty()) return lists
        val head = lists.first()
        val rest = lists.drop(1)
        val mode = prefs.getString(KEY_PLAYLIST_SORT, SORT_DEFAULT) ?: SORT_DEFAULT
        val sorted = when (mode) {
            SORT_NAME -> PlaylistSorter.byName(rest)
            SORT_RECENT -> PlaylistSorter.byRecent(rest, RecentPlaylist.load(this))
            SORT_RANDOM -> PlaylistSorter.shuffledStable(rest, randomSeed)
            else -> rest
        }
        return listOf(head) + sorted
    }

    /** 歌单排序弹窗（点库页「歌单」右侧的下拉箭头触发）。 */
    private fun showPlaylistSortDialog() {
        val labels = arrayOf(
            getString(R.string.sort_default),
            getString(R.string.sort_name),
            getString(R.string.sort_recent),
            getString(R.string.sort_random)
        )
        val values = arrayOf(SORT_DEFAULT, SORT_NAME, SORT_RECENT, SORT_RANDOM)
        val cur = prefs.getString(KEY_PLAYLIST_SORT, SORT_DEFAULT) ?: SORT_DEFAULT
        val idx = values.indexOf(cur).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.playlist_sort)
            .setSingleChoiceItems(labels, idx) { d, which ->
                val chosen = values[which]
                // 从没播放记录时选「按最近播放」没意义：提示并保持原样
                if (chosen == SORT_RECENT && RecentPlaylist.load(this).isEmpty()) {
                    d.dismiss()
                    toast(getString(R.string.sort_recent_empty))
                    return@setSingleChoiceItems
                }
                prefs.edit().putString(KEY_PLAYLIST_SORT, chosen).apply()
                if (chosen == SORT_RANDOM) {
                    // 每次主动选「随机」都换一批顺序（同一次会话内保持稳定）
                    randomSeed = System.currentTimeMillis()
                }
                applyLibLayout()
                d.dismiss()
            }
            .show()
    }

    // ---------- 发现页 ----------

    private fun loadDiscover() {
        val lib = library ?: return
        discoverSongs = lib.allSongs.shuffled().take(8)
        discoverAdapter.submit(discoverSongs)
    }

    // ---------- 音乐库分段 ----------

    private fun showSegment(songs: Boolean) {
        segPlaylists.setBackgroundResource(if (songs) R.drawable.bg_segment_active else 0)
        segPlaylists.setTextColor(getColor(if (songs) R.color.text_primary else R.color.text_hint))
        segArtists.setBackgroundResource(if (songs) 0 else R.drawable.bg_segment_active)
        segArtists.setTextColor(getColor(if (songs) R.color.text_hint else R.color.text_primary))
        recyclerPlaylists.visibility = if (songs) View.VISIBLE else View.GONE
        recyclerArtists.visibility = if (songs) View.GONE else View.VISIBLE
        // 排序入口只对歌单视图有意义（2.13）
        findViewById<View>(R.id.btnPlaylistSort).visibility =
            if (songs) View.VISIBLE else View.GONE
        if (!songs) loadArtistsIfNeeded()
    }

    private fun loadArtistsIfNeeded() {
        if (artistLoaded || artistLoading) return
        val lib = library ?: return
        artistLoading = true
        ArtistLoader.loadArtists(this, lib.allSongs) { groups ->
            artistLoading = false
            artistLoaded = true
            artistGroups = groups
            artistAdapter.submit(groups.map { it.first to it.second.size })
        }
    }

    private fun openArtistSongs(position: Int) {
        val (name, songs) = artistGroups.getOrNull(position) ?: return
        txtPlaylistTitle.text = name
        dragEnabled = false
        currentSongs = songs
        songAdapter.submit(songs)
        showPage(Page.PLAYLIST)
    }

    // ---------- 歌单/搜索 ----------

    private fun openPlaylist(playlist: Playlist) {
        bindPlaylist(playlist)
        showPage(Page.PLAYLIST)
    }

    /** 只填充歌单页数据（标题/列表），不切页——恢复播放等后台场景用。 */
    private fun bindPlaylist(playlist: Playlist, isCustom: Boolean = false) {
        playlistIsCustom = isCustom
        txtPlaylistTitle.text = playlist.name
        dragEnabled = true
        currentSongs = applyPlaylistOrder(playlist.songs, playlist.name)
        songAdapter.submit(currentSongs)
    }

    /**
     * 当前歌单页展示的是否为**自建歌单**（2.14）。
     *
     * 为什么用字段而不是 `isCustomPlaylist(歌单名)` 现查：自建歌单和文件夹歌单允许重名
     * （用户可能建一个也叫「Music」的歌单）。按名字查会误判成自建，
     * 于是拖拽排序把顺序写进了 CustomPlaylistStore——而文件夹歌单的顺序本该写 prefs，
     * 结果是「排序当场生效、重进就还原」。
     * 由打开方显式声明来源，就不存在这种歧义。
     */
    private var playlistIsCustom = false

    // ---------- 歌单手动排序 ----------

    /** 是否允许歌单页长按拖拽排序（普通歌单 true，歌手页/搜索结果 false）。 */
    private var dragEnabled = false

    private val playlistTouchHelper by lazy {
        ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun isLongPressDragEnabled() = dragEnabled
            override fun isItemViewSwipeEnabled() = false
            override fun getMovementFlags(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Int = makeMovementFlags(
                if (dragEnabled) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0,
                0
            )
            override fun onMove(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from >= 0 && to >= 0) {
                    // 关键：用 adapter 返回的新列表同步 currentSongs——
                    // 否则排序只改了界面，保存的仍是旧顺序、播放队列也不跟随
                    currentSongs = songAdapter.move(from, to)
                }
                return true
            }
            override fun onSwiped(
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                direction: Int
            ) {
            }
            override fun clearView(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                saveCurrentPlaylistOrder()
            }
        })
    }

    /** 保存当前歌单（按标题名）的自定义顺序到本地。 */
    private fun saveCurrentPlaylistOrder() {
        val name = txtPlaylistTitle.text.toString()
        if (name.isEmpty() || !dragEnabled) return
        val uris = currentSongs.map { it.uri.toString() }
        // 2.14：自建歌单的顺序写回它自己的数据源；文件夹歌单仍走 prefs。
        // 两者数据源不同，不能混写（用字段而不是按名字查——重名时会写错地方）。
        if (playlistIsCustom) {
            // 不能直接覆盖：currentSongs 是**解析后**的列表，库中临时找不到的 uri
            // （扫描未完成、文件被移走）会被 resolveUris 跳过。直接覆盖＝那些歌被
            // 永久移出歌单。所以把这些 uri 原序追加到末尾，只调整看得见的顺序。
            val kept = CustomPlaylistStore.uris(this, name)
            val visible = uris.toHashSet()
            val merged = uris + kept.filter { it !in visible }
            CustomPlaylistStore.replaceSongs(this, name, merged)
            return
        }
        prefs.edit()
            .putString("playlist_order_$name", uris.joinToString("\n"))
            .apply()
    }

    /** 有自定义顺序则按顺序重排，否则原样返回。 */
    private fun applyPlaylistOrder(songs: List<Song>, name: String): List<Song> {
        // 2.14：自建歌单的顺序直接存在 Store 里（openCustomPlaylist 已按序取回），不读 prefs
        if (playlistIsCustom) return songs
        val saved = prefs.getString("playlist_order_$name", null) ?: return songs
        val uriOrder = saved.split("\n").filter { it.isNotEmpty() }
        if (uriOrder.size != songs.size) return songs // 歌单内容变了，顺序失效
        val byUri = songs.associateBy { it.uri.toString() }
        val reordered = uriOrder.mapNotNull { byUri[it] }
        return if (reordered.size == songs.size) reordered else songs
    }

    private fun showSearchPage() {
        etSearch.setText("")
        doSearch("")
        showPage(Page.SEARCH)
    }

    private fun doSearch(query: String) {
        val q = query.trim()
        val lib = library
        if (q.isEmpty() || lib == null) {
            searchPlaylists = emptyList()
            searchSongs = emptyList()
            searchAdapter.submit(emptyList(), emptyList())
            txtSearchHint.visibility = View.VISIBLE
            txtSearchHint.text = getString(R.string.search_prompt)
            return
        }
        val k = q.lowercase(Locale.getDefault())
        val pl = lib.playlists.filter { it.name.lowercase(Locale.getDefault()).contains(k) }
        val sg = lib.allSongs.filter { it.title.lowercase(Locale.getDefault()).contains(k) }
        searchPlaylists = pl
        searchSongs = sg
        searchAdapter.submit(pl, sg)
        txtSearchHint.visibility = if (pl.isEmpty() && sg.isEmpty()) View.VISIBLE else View.GONE
        if (pl.isEmpty() && sg.isEmpty()) {
            txtSearchHint.text = getString(R.string.search_none)
        }
    }

    // ---------- 播放（委托服务） ----------

    private fun maybeResumeLastSong() {
        // 每次会话只恢复一次：后台静默重扫替换 library 时不能再拉起播放（1.31）
        if (resumedLastSong) return
        resumedLastSong = true
        val lib = library ?: return
        val sp = getSharedPreferences("play_state", Context.MODE_PRIVATE)
        val uriStr = sp.getString(MediaPlaybackService.KEY_LAST_URI, null) ?: return
        val pos = sp.getInt(MediaPlaybackService.KEY_LAST_POS, 0)
        val song = lib.allSongs.firstOrNull { it.uri.toString() == uriStr } ?: return
        val folderPlaylist = lib.playlists.firstOrNull { pl ->
            pl.name == song.folder && pl.songs.any { it.uri == song.uri }
        }
        val resumeSongs = folderPlaylist?.songs ?: lib.allSongs
        val idx = resumeSongs.indexOfFirst { it.uri == song.uri }
        if (idx < 0) return
        // 恢复的队列来自某个文件夹歌单：后台把歌单页数据也填好（不切页），
        // 之后从歌词/播放页按返回键能直接回到这条队列的音轨界面
        if (folderPlaylist != null) {
            bindPlaylist(folderPlaylist)
            queueSourcePage = Page.PLAYLIST
        } else {
            queueSourcePage = null
        }
        ensureService()
        val svc = playbackService
        if (svc != null) {
            svc.startPlaylist(resumeSongs, idx, lib.lyrics, pos)
        } else {
            pendingStart = Triple(resumeSongs, idx, pos)
        }
        toast(getString(R.string.resumed_playback))
    }

    private fun playSong(songs: List<Song>, index: Int) {
        requestNotificationPermission()
        ensureService()
        val svc = playbackService
        if (svc != null) {
            svc.startPlaylist(songs, index, library?.lyrics ?: emptyMap())
        } else {
            pendingStart = Triple(songs, index, 0)
        }
        // 记录队列来源页：只有「列表页发起」才记（队列对话框里切歌时 page 是播放页，不能覆盖）
        if (page in queueSourcePages) queueSourcePage = page
        // 记录该歌单的最近播放时刻（2.13，「按最近播放排序」用）。
        // 用当前歌曲所属歌单名反查，而不是调用方的列表——同一个歌单可能从搜索/收藏进入。
        recordPlaylistPlayed(songs.getOrNull(index))
        showPage(Page.PLAYER)
    }

    /** 记下该歌所属歌单的播放时刻（2.13）。找不到对应歌单则忽略（如全库队列）。 */
    private fun recordPlaylistPlayed(song: Song?) {
        val s = song ?: return
        val name = library?.playlists
            ?.firstOrNull { it.songs.any { it.uri == s.uri } }
            ?.name ?: return
        RecentPlaylist.markPlayed(this, name)
    }

    /** 可作为播放队列来源、返回时可重新展示的列表页。 */
    private val queueSourcePages =
        setOf(Page.PLAYLIST, Page.SEARCH, Page.FAVORITES, Page.DISCOVER, Page.VIDEOS)

    private fun ensureService() {
        val intent = Intent(this, MediaPlaybackService::class.java)
        ContextCompat.startForegroundService(this, intent)
        serviceStarted = true
        if (!bound) {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun onLyricClick(pos: Int) {
        val line = lyricLines.getOrNull(pos) ?: return
        if (line.startMs < 0) {
            toast(getString(R.string.lyric_no_time))
            return
        }
        playbackService?.seekToAndPlay(line.startMs)
        currentLyricHighlight = pos
        lyricAdapter.setCurrent(pos)
        scrollToLyric(pos)
        updateNowLyric(pos)
    }

    // ---------- 播放页 CD / 歌词 ----------

    private fun updateNowLyric(lyricIndex: Int) {
        if (playerImmersed) {
            currentLyricHighlight = lyricIndex
            updateImmersiveLyric()
            return
        }
        val newText = if (lyricIndex >= 0 && lyricIndex < lyricLines.size) {
            lyricLines[lyricIndex].text
        } else if (lyricLines.isNotEmpty() && lyricLines[0].startMs < 0) {
            // 静态歌词（无时间戳）：显示第一句，引导去全屏歌词页
            lyricLines[0].text
        } else {
            getString(R.string.no_lyric_now)
        }
        if (txtNowLyric.text.toString() != newText) {
            txtNowLyric.text = newText
            txtNowLyric.alpha = 0f
            txtNowLyric.animate().alpha(1f).setDuration(220).start()
        }
    }

    private fun updateCdAnimation(playing: Boolean) {
        val anim = cdAnimator
            ?: ObjectAnimator.ofFloat(imgCd, View.ROTATION, 0f, 360f).apply {
                duration = 20000
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.RESTART
                interpolator = LinearInterpolator()
            }.also { cdAnimator = it }
        if (playing) {
            if (!anim.isStarted) anim.start() else anim.resume()
        } else {
            if (anim.isStarted) anim.pause()
        }
    }

    private fun scrollToLyric(idx: Int) {
        if (idx < 0) return
        // 用户手动滑动后的冷却期内不自动回位（避免卡手，5 秒）
        if (System.currentTimeMillis() - lastLyricUserScroll < 5000) return
        val lm = recyclerLyricFull.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (idx < first || idx > last) {
            val target = idx
            recyclerLyricFull.post {
                // 平滑滚动回当前行（不再瞬时跳转）
                val scroller = object : androidx.recyclerview.widget.LinearSmoothScroller(this@MainActivity) {
                    override fun getVerticalSnapPreference(): Int =
                        androidx.recyclerview.widget.LinearSmoothScroller.SNAP_TO_START
                }
                scroller.targetPosition = target
                lm.startSmoothScroll(scroller)
            }
        }
    }

    // ---------- 播放页沉浸模式 ----------

    private fun scheduleImmersion() {
        if (page != Page.PLAYER) return
        immersionHandler.removeCallbacks(immersionRunnable)
        if (!prefs.getBoolean(KEY_IMMERSION_ENABLED, true)) return
        val seconds = prefs.getInt(KEY_IMMERSION_SECONDS, 5).coerceIn(3, 120)
        immersionHandler.postDelayed(immersionRunnable, seconds * 1000L)
    }

    private fun cancelImmersion() {
        immersionHandler.removeCallbacks(immersionRunnable)
    }

    /** 沉浸：隐藏顶部栏/进度/控制行，只留封面与歌词，封面放大；同时隐藏系统栏去除白边。 */
    private fun enterImmersion() {
        if (playerImmersed || page != Page.PLAYER) return
        playerImmersed = true
        for (v in listOf(playerHeader, seekBar, txtTime, playerControlsMain, playerControlsExtra)) {
            v.animate().alpha(0f).setDuration(250).withEndAction {
                if (playerImmersed) v.visibility = View.INVISIBLE
            }
        }
        imgCd.animate().scaleX(1.22f).scaleY(1.22f).setDuration(320).start()
        // 隐藏系统状态栏/导航栏，消除上下白边
        try {
            androidx.core.view.WindowCompat.getInsetsController(window, viewPlayer)?.apply {
                systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        } catch (_: Exception) {
        }
        // 切换为多行沉浸歌词
        txtNowLyric.visibility = View.GONE
        txtImmersiveLyric.visibility = View.VISIBLE
        updateImmersiveLyric()
        // 隐藏底部导航（发现/音乐库）
        bottomNav.animate().alpha(0f).setDuration(250).withEndAction {
            if (playerImmersed) bottomNav.visibility = View.GONE
        }
    }

    /** 退出沉浸：恢复全部控件与系统栏。 */
    private fun exitImmersion() {
        if (!playerImmersed) return
        playerImmersed = false
        for (v in listOf(playerHeader, seekBar, txtTime, playerControlsMain, playerControlsExtra)) {
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).setDuration(200).start()
        }
        imgCd.animate().scaleX(1f).scaleY(1f).setDuration(250).start()
        try {
            androidx.core.view.WindowCompat.getInsetsController(window, viewPlayer)
                ?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } catch (_: Exception) {
        }
        txtImmersiveLyric.visibility = View.GONE
        txtNowLyric.visibility = View.VISIBLE
        bottomNav.visibility = View.VISIBLE
        bottomNav.animate().alpha(1f).setDuration(200).start()
    }

    /** 沉浸歌词：显示当前行 ± 前后一行（共三行）。 */
    private fun updateImmersiveLyric() {
        if (lyricLines.isEmpty()) {
            txtImmersiveLyric.text = ""
            return
        }
        val idx = currentLyricHighlight.coerceIn(0, lyricLines.size - 1)
        val parts = mutableListOf<String>()
        for (i in idx - 1..idx + 1) {
            if (i in lyricLines.indices) {
                parts.add(lyricLines[i].text)
            }
        }
        txtImmersiveLyric.text = parts.joinToString("\n")
    }

    /** 视频页全屏：隐藏系统状态栏/导航栏，画面区域最大化（1.31 收窄视频四周留白）。 */
    private fun enterVideoImmersive() {
        if (videoImmersive) return
        videoImmersive = true
        try {
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)?.apply {
                systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        } catch (_: Exception) {
        }
    }

    /** 离开视频页：恢复系统栏。 */
    private fun exitVideoImmersive() {
        if (!videoImmersive) return
        videoImmersive = false
        try {
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                ?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } catch (_: Exception) {
        }
    }

    // ---------- 定时 / 设置 ----------

    private fun showSleepDialog() {
        val options = arrayOf(
            getString(R.string.sleep_15),
            getString(R.string.sleep_30),
            getString(R.string.sleep_45),
            getString(R.string.sleep_60),
            getString(R.string.sleep_cancel)
        )
        val minutes = intArrayOf(15, 30, 45, 60, 0)
        AlertDialog.Builder(this)
            .setTitle(R.string.sleep_title)
            .setItems(options) { _, which ->
                val m = minutes[which]
                val svc = playbackService ?: return@setItems
                if (m <= 0) {
                    svc.cancelSleepTimer()
                    toast(getString(R.string.sleep_cancelled))
                } else {
                    svc.setSleepTimer(m)
                    toast(getString(R.string.sleep_set, m))
                }
            }
            .show()
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.settings_dialog, null)
        // 弹窗不在 Activity view tree 内，单独应用主题色（BtnStyle 按钮背景）
        tintAccentViews(view, android.content.res.ColorStateList.valueOf(ThemeManager.accent(this)))
        val chkAutoScan = view.findViewById<CheckBox>(R.id.chkAutoScan)
        val chkAutoTrans = view.findViewById<CheckBox>(R.id.chkAutoTrans)
        val chkTitleFromFilename = view.findViewById<CheckBox>(R.id.chkTitleFromFilename)
        val rgLyric = view.findViewById<RadioGroup>(R.id.rgLyricSize)
        val rgUi = view.findViewById<RadioGroup>(R.id.rgUiSize)
        val rgFont = view.findViewById<RadioGroup>(R.id.rgFont)

        chkAutoScan.isChecked = prefs.getBoolean(KEY_AUTO_SCAN, false)
        chkAutoTrans.isChecked = prefs.getBoolean(KEY_AUTO_TRANS, false)
        chkTitleFromFilename.isChecked = prefs.getBoolean(KEY_TITLE_FROM_FILENAME, false)
        checkByTag(rgLyric, prefs.getInt(KEY_LYRIC_SIZE, 18))
        checkByTag(rgUi, prefs.getInt(KEY_UI_SIZE, 15))
        checkByTag(rgFont, prefs.getInt(KEY_LYRIC_FONT, 0))

        // ---- 词幕（状态栏歌词）开关 ----
        val chkLyricon = view.findViewById<CheckBox>(R.id.chkLyricon)
        chkLyricon.isChecked = prefs.getBoolean(MediaPlaybackService.KEY_LYRICON, false)
        chkLyricon.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(MediaPlaybackService.KEY_LYRICON, checked).apply()
            playbackService?.setLyriconEnabled(checked)
        }

        // ---- 桌面歌词 ----
        val chkDesktopLyrics = view.findViewById<CheckBox>(R.id.chkDesktopLyrics)
        val lyricsGroup = view.findViewById<View>(R.id.lyricsGroup)
        val rgDesktopSize = view.findViewById<RadioGroup>(R.id.rgDesktopSize)
        val rgDesktopAlpha = view.findViewById<RadioGroup>(R.id.rgDesktopAlpha)
        val chkLyricsLocked = view.findViewById<CheckBox>(R.id.chkLyricsLocked)
        val chkDesktopCenter = view.findViewById<CheckBox>(R.id.chkDesktopCenter)
        val chkMixAudio = view.findViewById<CheckBox>(R.id.chkMixAudio)
        chkMixAudio.isChecked = prefs.getBoolean(KEY_MIX_AUDIO, false)
        val chkPerSong = view.findViewById<CheckBox>(R.id.chkPerSong)
        chkPerSong.isChecked = prefs.getBoolean(MediaPlaybackService.KEY_PER_SONG, false)
        val rgLibLayout = view.findViewById<RadioGroup>(R.id.rgLibLayout)
        checkByTag(rgLibLayout, if (prefs.getString(KEY_LIB_LAYOUT, "grid") == "tree") 1 else 0)
        val rgSeekStep = view.findViewById<RadioGroup>(R.id.rgSeekStep)
        checkByTag(rgSeekStep, prefs.getInt(KEY_SEEK_STEP, 10))
        val chkAlarmPlay = view.findViewById<CheckBox>(R.id.chkAlarmPlay)
        chkAlarmPlay.isChecked = prefs.getBoolean(MediaPlaybackService.KEY_ALARM_ON, false)
        val chkAlarmOnce = view.findViewById<CheckBox>(R.id.chkAlarmOnce)
        chkAlarmOnce.isChecked = prefs.getBoolean(MediaPlaybackService.KEY_ALARM_ONCE, false)
        chkAlarmPlay.setOnClickListener { showAlarmPicker(chkAlarmPlay) }
        chkDesktopLyrics.isChecked = prefs.getBoolean(KEY_DESKTOP_ON, false)
        lyricsGroup.visibility =
            if (chkDesktopLyrics.isChecked) View.VISIBLE else View.GONE
        checkByTag(rgDesktopSize, prefs.getInt(KEY_DESKTOP_SIZE, 1))
        checkByTag(rgDesktopAlpha, prefs.getInt(KEY_DESKTOP_ALPHA, 1))
        chkLyricsLocked.isChecked = prefs.getBoolean(KEY_DESKTOP_LOCKED, false)
        chkDesktopCenter.isChecked = prefs.getBoolean(KEY_DESKTOP_CENTER, false)
        // 桌面歌词文字颜色：短按选色（即时生效），长按恢复默认白色
        val btnDesktopColor = view.findViewById<Button>(R.id.btnDesktopColor)
        btnDesktopColor.setOnClickListener {
            showDesktopLyricColorDialog()
        }
        btnDesktopColor.setOnLongClickListener {
            prefs.edit().putInt(KEY_DESKTOP_COLOR, DESKTOP_COLOR_DEFAULT).apply()
            playbackService?.refreshDesktopLyricsStyle()
            toast(getString(R.string.desktop_lyrics_color_reset))
            true
        }
        // 勾选/取消即时生效（含悬浮窗权限引导），避免用户忘了点确定
        chkDesktopLyrics.setOnCheckedChangeListener { _, checked ->
            lyricsGroup.visibility = if (checked) View.VISIBLE else View.GONE
            prefs.edit().putBoolean(KEY_DESKTOP_ON, checked).apply()
            if (checked) {
                if (playbackService == null) {
                    toast(getString(R.string.desktop_lyrics_later))
                } else {
                    playbackService?.setDesktopLyrics(true)
                }
            } else {
                playbackService?.setDesktopLyrics(false)
            }
        }

        chkAutoTrans.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.auto_trans_confirm_title)
                    .setMessage(R.string.auto_trans_confirm_msg)
                    .setPositiveButton(R.string.enable) { _, _ ->
                        prefs.edit().putBoolean(KEY_AUTO_TRANS, true).apply()
                        toast(getString(R.string.auto_trans_ok))
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        chkAutoTrans.isChecked = false
                    }
                    .show()
            } else {
                prefs.edit().putBoolean(KEY_AUTO_TRANS, false).apply()
            }
        }

        settingsDialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                val titleFromFileNameChanged =
                    prefs.getBoolean(KEY_TITLE_FROM_FILENAME, false) != chkTitleFromFilename.isChecked
                prefs.edit()
                    .putBoolean(KEY_AUTO_SCAN, chkAutoScan.isChecked)
                    .putBoolean(KEY_AUTO_TRANS, chkAutoTrans.isChecked)
                    .putBoolean(KEY_TITLE_FROM_FILENAME, chkTitleFromFilename.isChecked)
                    .putInt(KEY_LYRIC_SIZE, tagOf(rgLyric))
                    .putInt(KEY_UI_SIZE, tagOf(rgUi))
                    .putInt(KEY_LYRIC_FONT, tagOf(rgFont))
                    .putInt(KEY_DESKTOP_SIZE, tagOf(rgDesktopSize))
                    .putInt(KEY_DESKTOP_ALPHA, tagOf(rgDesktopAlpha))
                    .putBoolean(KEY_DESKTOP_LOCKED, chkLyricsLocked.isChecked)
                    .putBoolean(KEY_DESKTOP_CENTER, chkDesktopCenter.isChecked)
                    .putBoolean(KEY_MIX_AUDIO, chkMixAudio.isChecked)
                    .putBoolean(MediaPlaybackService.KEY_PER_SONG, chkPerSong.isChecked)
                    .putBoolean(MediaPlaybackService.KEY_ALARM_ONCE, chkAlarmOnce.isChecked)
                    .putString(KEY_LIB_LAYOUT, if (tagOf(rgLibLayout) == 1) "tree" else "grid")
                    .putInt(KEY_SEEK_STEP, tagOf(rgSeekStep))
                    .apply()
                applyAppearance()
                applyLibLayout()
                updateSeekButtons()
                playbackService?.refreshDesktopLyricsStyle()
                // 标题显示来源变了：重新扫描让歌名立即切换（标签标题 ↔ 文件名）
                if (titleFromFileNameChanged) scanLibrary(silent = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        settingsDialog?.show()

        view.findViewById<Button>(R.id.btnRescanNow).setOnClickListener {
            settingsDialog?.dismiss()
            if (treeUri() == null) {
                treePicker.launch(null)
            } else {
                scanLibrary()
            }
        }
        view.findViewById<Button>(R.id.btnChangeFolder).setOnClickListener {
            settingsDialog?.dismiss()
            treePicker.launch(null)
        }
        // 长按「添加扫描文件夹」：清除全部已添加根，回到空库（下次可重新添加）
        view.findViewById<Button>(R.id.btnChangeFolder).setOnLongClickListener {
            prefs.edit()
                .remove(KEY_TREES)
                .remove(KEY_TREE)
                .apply()
            library = null
            toast("已清除全部扫描文件夹")
            true
        }
        view.findViewById<Button>(R.id.btnAsrEntry).setOnClickListener {
            settingsDialog?.dismiss()
            showAsrDialog()
        }
        view.findViewById<Button>(R.id.btnExportLog).setOnClickListener {
            showPlaybackLogDialog()
        }
        view.findViewById<Button>(R.id.btnAbout).setOnClickListener {
            settingsDialog?.dismiss()
            showAboutDialog()
        }
        view.findViewById<Button>(R.id.btnTransSettings).setOnClickListener {
            settingsDialog?.dismiss()
            showTransSettingsDialog()
        }
        view.findViewById<Button>(R.id.btnEqSettings).setOnClickListener {
            settingsDialog?.dismiss()
            showEqualizerDialog()
        }
        view.findViewById<Button>(R.id.btnTheme).setOnClickListener {
            settingsDialog?.dismiss()
            showThemeDialog()
        }
    }

    /** 主题设置弹窗：主题色 + 背景图 + 深色模式三合一。 */
    private fun showThemeDialog() {
        val d = resources.displayMetrics.density
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(18f), dp(8f), dp(18f), dp(8f))
        }

        // 主题色行（带当前色圆点）
        val accentRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(14f), 0, dp(14f))
            setOnClickListener { showAccentDialog() }
        }
        val dot = View(this).apply {
            setBackgroundColor(ThemeManager.accent(this@MainActivity))
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(18f), dp(18f))
        }
        accentRow.addView(dot)
        accentRow.addView(android.widget.TextView(this).apply {
            text = getString(R.string.accent_title)
            textSize = 16f
            setTextColor(getColor(R.color.text_primary))
            setPadding(dp(12f), 0, 0, 0)
        })
        box.addView(accentRow)

        // 背景图行
        box.addView(android.widget.TextView(this).apply {
            text = getString(R.string.bg_title)
            textSize = 16f
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, dp(14f), 0, dp(14f))
            setOnClickListener { showBgDialog() }
        })

        // 封面背景开关（2.12.1）：歌词页/播放页用当前音声封面铺满作背景
        box.addView(android.widget.Switch(this).apply {
            isChecked = coverBgEnabled()
            text = getString(R.string.cover_bg_title)
            textSize = 16f
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, dp(14f), 0, dp(4f))
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(KEY_COVER_BG, checked).apply()
                // 两种状态都要重刷：开启 → 立刻换封面；关闭 → 立刻退回背景图/纯色
                applyAppearance()
                applyPageBackground()
            }
        })
        // 说明小字：讲清与上面「背景图」的回退关系，免得出「设了背景图没反应」的误报
        box.addView(android.widget.TextView(this).apply {
            text = getString(R.string.cover_bg_hint)
            textSize = 12f
            setTextColor(getColor(R.color.text_hint))
            setPadding(0, 0, 0, dp(6f))
        })

        // 非当前歌词颜色行（带当前色圆点；长按恢复默认）
        val idleRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(14f), 0, dp(14f))
        }
        val idleDot = View(this).apply {
            setBackgroundColor(currentIdleColor())
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(18f), dp(18f))
        }
        idleRow.addView(idleDot)
        idleRow.addView(android.widget.TextView(this).apply {
            text = getString(R.string.lyric_idle_color_label)
            textSize = 16f
            setTextColor(getColor(R.color.text_primary))
            setPadding(dp(12f), 0, 0, 0)
        })
        idleRow.setOnClickListener { showLyricIdleColorDialog { c -> idleDot.setBackgroundColor(c) } }
        idleRow.setOnLongClickListener {
            prefs.edit().putInt(KEY_LYRIC_IDLE_COLOR, IDLE_DEFAULT).apply()
            applyAppearance()
            idleDot.setBackgroundColor(currentIdleColor())
            toast(getString(R.string.lyric_idle_color_reset))
            true
        }
        box.addView(idleRow)

        // 播放中歌词颜色行（带当前色圆点；长按恢复跟随主题色，1.30）
        val curRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(14f), 0, dp(14f))
        }
        val curDot = View(this).apply {
            setBackgroundColor(currentCurColor())
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(18f), dp(18f))
        }
        curRow.addView(curDot)
        curRow.addView(android.widget.TextView(this).apply {
            text = getString(R.string.lyric_cur_color_label)
            textSize = 16f
            setTextColor(getColor(R.color.text_primary))
            setPadding(dp(12f), 0, 0, 0)
        })
        curRow.setOnClickListener { showLyricCurColorDialog { c -> curDot.setBackgroundColor(c) } }
        curRow.setOnLongClickListener {
            prefs.edit().putInt(KEY_LYRIC_CUR_COLOR, CUR_DEFAULT).apply()
            applyAppearance()
            curDot.setBackgroundColor(currentCurColor())
            toast(getString(R.string.lyric_cur_color_reset))
            true
        }
        box.addView(curRow)

        // 进度条颜色行（带当前色圆点；长按恢复跟随主题色，1.30）
        val sbRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(14f), 0, dp(14f))
        }
        val sbDot = View(this).apply {
            setBackgroundColor(currentSeekColor())
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(18f), dp(18f))
        }
        sbRow.addView(sbDot)
        sbRow.addView(android.widget.TextView(this).apply {
            text = getString(R.string.seekbar_color_label)
            textSize = 16f
            setTextColor(getColor(R.color.text_primary))
            setPadding(dp(12f), 0, 0, 0)
        })
        sbRow.setOnClickListener { showSeekbarColorDialog { c -> sbDot.setBackgroundColor(c) } }
        sbRow.setOnLongClickListener {
            prefs.edit().putInt(KEY_SEEKBAR_COLOR, SB_DEFAULT).apply()
            applyAccent()
            sbDot.setBackgroundColor(currentSeekColor())
            toast(getString(R.string.seekbar_color_reset))
            true
        }
        box.addView(sbRow)

        // 深色模式开关（即时应用）
        box.addView(android.widget.Switch(this).apply {
            isChecked = prefs.getBoolean(KEY_DARK, false)
            text = getString(R.string.dark_mode)
            textSize = 16f
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, dp(14f), 0, dp(14f))
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(KEY_DARK, checked).apply()
                applyDarkMode(checked)
            }
        })

        // 导航栏自定义
        box.addView(android.widget.TextView(this).apply {
            text = getString(R.string.nav_title)
            textSize = 16f
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, dp(14f), 0, 0)
            setOnClickListener { showNavDialog() }
        })

        // 沉浸模式开关（默认开启）
        box.addView(android.widget.Switch(this).apply {
            isChecked = prefs.getBoolean(KEY_IMMERSION_ENABLED, true)
            text = getString(R.string.immersion_enabled_title)
            textSize = 16f
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, dp(14f), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(KEY_IMMERSION_ENABLED, checked).apply()
                if (!checked) {
                    cancelImmersion()
                    if (playerImmersed) exitImmersion()
                } else {
                    if (page == Page.PLAYER) scheduleImmersion()
                }
            }
        })

        // 沉浸进入时间（开关开启时生效）
        box.addView(android.widget.TextView(this).apply {
            text = getString(R.string.immersion_seconds_title)
            textSize = 16f
            setTextColor(getColor(R.color.text_hint))
            setPadding(0, dp(10f), 0, 0)
            setOnClickListener {
                val options = arrayOf("5 秒", "10 秒", "15 秒", "30 秒")
                val values = intArrayOf(5, 10, 15, 30)
                val cur = prefs.getInt(KEY_IMMERSION_SECONDS, 5)
                val idx = values.indexOf(cur).coerceAtLeast(0)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.immersion_seconds_title)
                    .setSingleChoiceItems(options, idx) { d, which ->
                        prefs.edit().putInt(KEY_IMMERSION_SECONDS, values[which]).apply()
                        d.dismiss()
                        toast(getString(R.string.saved))
                    }
                    .show()
            }
        })
        // 提示文案
        box.addView(android.widget.TextView(this).apply {
            text = getString(R.string.immersion_hint)
            textSize = 12f
            setTextColor(getColor(R.color.text_hint))
            setPadding(0, dp(6f), 0, dp(2f))
        })

        AlertDialog.Builder(this)
            .setTitle(R.string.theme_entry)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 播放列表 ----------

    private fun showQueueDialog() {
        if (currentSongs.isEmpty()) {
            toast(getString(R.string.queue_empty))
            return
        }
        val rv = layoutInflater.inflate(R.layout.dialog_queue, null) as androidx.recyclerview.widget.RecyclerView
        var queueAdapter: SongAdapter? = null
        queueAdapter = SongAdapter(
            hasLyric = { s ->
                library?.let { LibraryScanner.findLyric(s, it.lyrics) != null } ?: false
            },
            onClick = { pos ->
                playSong(currentSongs, pos)
                queueAdapter?.setCurrentIndex(pos)
            }
        )
        queueAdapter!!.submit(currentSongs)
        queueAdapter!!.setCurrentIndex(playbackService?.currentIndex() ?: -1)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = queueAdapter
        AlertDialog.Builder(this)
            .setTitle(R.string.queue)
            .setView(rv)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ---------- 定时开始播放 ----------

    private fun showAlarmPicker(chk: CheckBox) {
        // 取消勾选 → 关闭定时
        if (!chk.isChecked) {
            cancelAlarmPlay()
            toast(getString(R.string.alarm_play_off))
            return
        }
        // 默认时间 = 当前时间 + 2 分钟（避免误设成上次的旧时间）
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.MINUTE, 2)
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)
        val dialog = TimePickerDialog(this, { _, h, m ->
            val trigger = scheduleAlarmPlay(h, m)
            val todayEnd = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 23)
                set(java.util.Calendar.MINUTE, 59)
                set(java.util.Calendar.SECOND, 59)
            }.timeInMillis
            val dayLabel = if (trigger > todayEnd) "明天 " else ""
            toast(String.format("已设定 %s%02d:%02d 自动播放", dayLabel, h, m))
        }, hour, minute, true)
        dialog.setOnCancelListener { chk.isChecked = false }
        dialog.show()
    }

    private fun scheduleAlarmPlay(hour: Int, minute: Int): Long {
        prefs.edit()
            .putBoolean(KEY_ALARM_ON, true)
            .putInt(KEY_ALARM_HOUR, hour)
            .putInt(KEY_ALARM_MINUTE, minute)
            .apply()
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
        }
        // 闹钟直达服务（去掉广播跳转，减少失败点）；setAlarmClock 触发时系统允许后台启动前台服务
        val pi = PendingIntent.getService(
            this, 100,
            Intent(this, MediaPlaybackService::class.java)
                .setAction(MediaPlaybackService.ACTION_ALARM_PLAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // showIntent：点击状态栏闹钟图标时打开 App
        val showPi = PendingIntent.getActivity(
            this, 101,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(cal.timeInMillis, showPi), pi)
        return cal.timeInMillis
    }

    private fun cancelAlarmPlay() {
        prefs.edit().putBoolean(KEY_ALARM_ON, false).apply()
        val pi = PendingIntent.getService(
            this, 100,
            Intent(this, MediaPlaybackService::class.java)
                .setAction(MediaPlaybackService.ACTION_ALARM_PLAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
    }

    // ---------- 歌词 AI 翻译 ----------

    private fun showTransSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.trans_dialog, null)
        val etBase = view.findViewById<android.widget.EditText>(R.id.etTransBase)
        val etKey = view.findViewById<android.widget.EditText>(R.id.etTransKey)
        val etModel = view.findViewById<android.widget.EditText>(R.id.etTransModel)
        etBase.setText(prefs.getString(KEY_TRANS_BASE, ""))
        etKey.setText(prefs.getString(KEY_TRANS_KEY, ""))
        etModel.setText(prefs.getString(KEY_TRANS_MODEL, ""))
        AlertDialog.Builder(this)
            .setTitle(R.string.trans_settings)
            .setView(view)
            .setPositiveButton(R.string.trans_save) { _, _ ->
                prefs.edit()
                    .putString(KEY_TRANS_BASE, etBase.text.toString().trim())
                    .putString(KEY_TRANS_KEY, etKey.text.toString().trim())
                    .putString(KEY_TRANS_MODEL, etModel.text.toString().trim())
                    .apply()
                toast(getString(R.string.trans_saved))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 均衡器（调音） ----------

    private fun showEqualizerDialog() {
        // 打开面板即确保 EQ 挂载（用户明确要调音），未配置过也能调；未播时仍需先放
        playbackService?.ensureEqAttached()
        if (!AudioFxManager.isAttached) {
            toast(getString(R.string.eq_need_play))
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_equalizer, null)
        val eqView = view.findViewById<EqualizerView>(R.id.equalizerView)
        val btnPreset = view.findViewById<android.widget.TextView>(R.id.btnEqPreset)
        val btnCustom = view.findViewById<android.widget.TextView>(R.id.btnEqCustom)
        val btnRestore = view.findViewById<android.widget.TextView>(R.id.btnEqRestore)

        fun styleTab(btn: android.widget.TextView, sel: Boolean) {
            btn.isSelected = sel
            btn.setTextColor(
                if (sel) android.graphics.Color.WHITE
                else androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.text_normal)
            )
        }

        fun refresh() {
            val gains = AudioFxManager.currentCurve(this@MainActivity)
            val n = gains.size
            if (n == 0) return
            eqView.gains = gains
            eqView.freqs = IntArray(n) { AudioFxManager.centerFreqHz(it) }
            val r = AudioFxManager.bandRange()
            eqView.minDb = r.first
            eqView.maxDb = r.second
            val custom = AudioFxManager.currentPreset(this@MainActivity).isEmpty()
            styleTab(btnPreset, !custom)
            styleTab(btnCustom, custom)
            eqView.editable = custom
        }

        eqView.onBandChanged = { band, db ->
            AudioFxManager.applyCustomBand(this@MainActivity, band, db)
        }

        btnPreset.setOnClickListener {
            val names = AudioFxManager.PRESETS.keys.toTypedArray()
            val cur = AudioFxManager.currentPreset(this@MainActivity)
            val idx = names.indexOf(cur).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(R.string.eq_pick_preset)
                .setSingleChoiceItems(names, idx) { d, which ->
                    AudioFxManager.applyPreset(this@MainActivity, names[which])
                    refresh()
                    d.dismiss()
                }
                .show()
        }

        btnCustom.setOnClickListener {
            // 切自定义：以当前曲线为起点继续微调
            styleTab(btnPreset, false)
            styleTab(btnCustom, true)
            eqView.editable = true
        }

        btnRestore.setOnClickListener {
            AudioFxManager.restorePreset(this@MainActivity)
            refresh()
        }

        refresh()
        AlertDialog.Builder(this)
            .setTitle(null)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 自定义封面 ----------

    private fun showPlaylistCoverMenu(name: String) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(
                arrayOf(
                    getString(R.string.cover_set),
                    getString(R.string.cover_clear),
                    getString(R.string.batch_cover)
                )
            ) { _, which ->
                when (which) {
                    0 -> {
                        pendingCoverTarget = "pl:$name"
                        coverPicker.launch("image/*")
                    }
                    1 -> {
                        CoverManager.clearPlaylistCover(this, name)
                        CoverLoader.invalidate("pl:$name")
                        refreshLibGrid()
                        toast(getString(R.string.cover_cleared))
                    }
                    2 -> showBatchCoverPicker(name)
                }
            }
            .show()
    }

    /** 歌单批量设置封面：多选歌曲（含全选），统一选一张图应用到勾选歌曲。 */
    private fun showBatchCoverPicker(playlistName: String) {
        val pl = library?.playlists?.firstOrNull { it.name == playlistName }
        val songs = pl?.songs
        if (songs.isNullOrEmpty()) {
            toast(getString(R.string.batch_cover_empty))
            return
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20f), dp(16f), dp(20f), dp(8f))
        }
        val topRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val btnAll = android.widget.Button(this).apply { text = getString(R.string.select_all) }
        val btnNone = android.widget.Button(this).apply { text = getString(R.string.deselect_all) }
        topRow.addView(btnAll)
        topRow.addView(btnNone)
        box.addView(topRow)

        val rows = ArrayList<android.widget.CheckBox>()
        val listBox = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        songs.forEach { s ->
            rows.add(android.widget.CheckBox(this).apply {
                text = s.title
                textSize = 15f
                setTextColor(getColor(R.color.text_primary))
                listBox.addView(this)
            })
        }
        val scroll = android.widget.ScrollView(this)
        scroll.addView(listBox)
        box.addView(
            scroll,
            android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(320f)
            )
        )

        btnAll.setOnClickListener { rows.forEach { it.isChecked = true } }
        btnNone.setOnClickListener { rows.forEach { it.isChecked = false } }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.batch_cover_title, playlistName))
            .setView(box)
            .setPositiveButton(R.string.batch_cover_apply) { d, _ ->
                val checked = songs.filterIndexed { i, _ -> rows[i].isChecked }
                if (checked.isEmpty()) {
                    toast(getString(R.string.batch_cover_empty))
                } else {
                    pendingBatchSongs = checked
                    coverPicker.launch("image/*")
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 歌曲长按统一菜单：收藏 + 添加到歌单 + 封面设置。 */
    private fun showSongMenu(song: Song) {
        val fav = FavoritesManager.isFavorite(this, song.uri.toString())
        // 2.14：已在某自建歌单里时，菜单里给「从本歌单移除」。
        // 必须限定 page == PLAYLIST——txtPlaylistTitle 是歌单页的控件，
        // 在收藏/搜索/库页时它保留的是「上一次进过的歌单名」，
        // 不判定页面就会在别处误显示「从本歌单移除」，点下去移除的是不相干的歌单。
        val inPlaylistPage = page == Page.PLAYLIST && playlistIsCustom
        val curPlaylist = if (inPlaylistPage) txtPlaylistTitle.text.toString() else ""
        val inCurrent = inPlaylistPage &&
            CustomPlaylistStore.uris(this, curPlaylist).contains(song.uri.toString())
        val labels = mutableListOf(
            getString(if (fav) R.string.unfavorite else R.string.favorite),
            getString(R.string.playlist_add_to),
            getString(R.string.cover_set),
            getString(R.string.cover_clear)
        )
        if (inCurrent) labels.add(getString(R.string.playlist_remove_from))
        AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        val on = FavoritesManager.toggle(this, song.uri.toString())
                        toast(getString(if (on) R.string.favorited else R.string.unfavorited))
                        updateFavoriteButton(song)
                        refreshPlaylistsPage()
                    }
                    1 -> showAddToPlaylistDialog(song)
                    2 -> {
                        pendingCoverTarget = "song:${song.uri}"
                        pendingCoverSong = song
                        coverPicker.launch("image/*")
                    }
                    3 -> {
                        CoverManager.clearSongCover(this, song.uri.toString(), song.size)
                        CoverLoader.invalidate(song.uri.toString())
                        refreshCdCover()
                        refreshLibGrid()
                        toast(getString(R.string.cover_cleared))
                    }
                    4 -> {
                        // 只从歌单里移除，不动手机里的文件
                        CustomPlaylistStore.remove(this, curPlaylist, song.uri.toString())
                        toast(getString(R.string.playlist_removed))
                        openCustomPlaylist(curPlaylist)
                        refreshPlaylistsPage()
                    }
                }
            }
            .show()
    }

    /** 播放页心形按钮状态同步。 */
    private fun updateFavoriteButton(song: Song?) {
        if (!::btnFavorite.isInitialized) return
        val fav = song != null && FavoritesManager.isFavorite(this, song.uri.toString())
        btnFavorite.text = if (fav) "\u2665" else "\u2661"
        btnFavorite.setTextColor(
            if (fav) android.graphics.Color.parseColor("#E53935")
            else getColor(R.color.text_hint)
        )
    }

    /** 打开收藏列表页。 */
    private fun openFavorites() {
        favoriteSongs = library?.allSongs?.filter {
            FavoritesManager.isFavorite(this, it.uri.toString())
        } ?: emptyList()
        favoritesAdapter.submit(favoriteSongs)
        txtFavoritesEmpty.visibility =
            if (favoriteSongs.isEmpty()) View.VISIBLE else View.GONE
        showPage(Page.FAVORITES)
    }

    // ---------- 歌单列表页与自定义歌单（2.14）----------

    /**
     * 打开歌单列表页：固定第一项「收藏」+ 用户自建歌单。
     *
     * 为什么「收藏」放在这里而不是继续单列一个导航页：粉丝的诉求是「能自由搭配的播放列表」，
     * 而收藏本质就是一个只能加不能减的单曲集合。并进同一个列表后，
     * 「收藏」= 系统预置歌单、自建 = 用户歌单，语义一致，导航栏也少一个入口。
     */
    private fun openPlaylistsPage() {
        refreshPlaylistsPage()
        showPage(Page.PLAYLISTS)
    }

    /** 重新计算歌单列表页内容（收藏 + 自建）并刷新。 */
    private fun refreshPlaylistsPage() {
        if (!::playlistsPageAdapter.isInitialized) return
        val items = mutableListOf<Playlist>()
        // 第一项恒为「收藏」：封面用收藏里第一首的封面，数量是收藏数
        val favSongs = library?.allSongs?.filter {
            FavoritesManager.isFavorite(this, it.uri.toString())
        } ?: emptyList()
        items.add(Playlist(favoritesEntryName, favSongs))
        // 自建歌单：按存储顺序（创建顺序）
        val own = CustomPlaylistStore.load(this)
        for ((name, uris) in own) {
            items.add(Playlist(name, resolveUris(uris)))
        }
        playlistsPageItems = items
        playlistsPageAdapter.submit(items)
        txtPlaylistsEmpty.visibility =
            if (own.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 把 uri 列表还原成 Song 列表（跳过已失效的 uri——文件可能被删或移走）。 */
    private fun resolveUris(uris: List<String>): List<Song> {
        val lib = library ?: return emptyList()
        if (uris.isEmpty()) return emptyList()
        val byUri = lib.allSongs.associateBy { it.uri.toString() }
        return uris.mapNotNull { byUri[it] }
    }

    /**
     * 打开自建歌单详情：复用歌单页（Page.PLAYLIST），播放/拖拽排序/返回全部现成。
     * 必须传 isCustom = true，否则拖拽排序会把顺序写进 prefs（文件夹歌单的数据源），
     * 自建歌单的顺序反而没保存。
     */
    private fun openCustomPlaylist(name: String) {
        val songs = resolveUris(CustomPlaylistStore.uris(this, name))
        // 空歌单进去是一片白，用户不知道下一步做什么 —— 给一句指引
        if (songs.isEmpty()) toast(getString(R.string.playlist_songs_empty))
        bindPlaylist(Playlist(name, songs), isCustom = true)
        showPage(Page.PLAYLIST)
    }

    /** 新建歌单：输入名称。 */
    private fun showCreatePlaylistDialog() {
        val et = android.widget.EditText(this).apply {
            hint = getString(R.string.playlist_name_hint)
            setSingleLine(true)
        }
        val box = android.widget.FrameLayout(this).apply {
            val m = dp(20f)
            setPadding(m, dp(8f), m, 0)
            addView(
                et,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.playlist_create)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = et.text.toString().trim()
                when {
                    name.isEmpty() || name.length > CustomPlaylistStore.MAX_NAME_LEN ->
                        toast(
                            getString(
                                R.string.playlist_name_invalid,
                                CustomPlaylistStore.MAX_NAME_LEN
                            )
                        )
                    // 与「收藏」入口重名会造成点击歧义，直接拒绝
                    name == favoritesEntryName -> toast(getString(R.string.playlist_exists))
                    CustomPlaylistStore.create(this, name) -> {
                        toast(getString(R.string.playlist_created, name))
                        refreshPlaylistsPage()
                    }
                    else -> toast(getString(R.string.playlist_exists))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 自建歌单长按菜单：重命名 / 删除 / 设置封面。 */
    private fun showCustomPlaylistMenu(name: String) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(
                arrayOf(
                    getString(R.string.playlist_rename),
                    getString(R.string.playlist_delete),
                    getString(R.string.cover_set),
                    getString(R.string.cover_clear)
                )
            ) { _, which ->
                when (which) {
                    0 -> showRenamePlaylistDialog(name)
                    1 -> confirmDeletePlaylist(name)
                    // 自建歌单的封面与文件夹歌单共用同一套封面存储（按歌单名），
                    // 所以直接用现有的设置封面流程即可
                    2 -> {
                        pendingCoverTarget = "pl:$name"
                        coverPicker.launch("image/*")
                    }
                    3 -> {
                        CoverManager.clearPlaylistCover(this, name)
                        CoverLoader.invalidate("pl:$name")
                        refreshPlaylistsPage()
                        toast(getString(R.string.cover_cleared))
                    }
                }
            }
            .show()
    }

    /** 重命名自建歌单。 */
    private fun showRenamePlaylistDialog(old: String) {
        val et = android.widget.EditText(this).apply {
            hint = getString(R.string.playlist_name_hint)
            setSingleLine(true)
            setText(old)
            setSelection(old.length)
        }
        val box = android.widget.FrameLayout(this).apply {
            val m = dp(20f)
            setPadding(m, dp(8f), m, 0)
            addView(
                et,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.playlist_rename)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = et.text.toString().trim()
                when {
                    name.isEmpty() || name.length > CustomPlaylistStore.MAX_NAME_LEN ->
                        toast(
                            getString(
                                R.string.playlist_name_invalid,
                                CustomPlaylistStore.MAX_NAME_LEN
                            )
                        )
                    name == favoritesEntryName -> toast(getString(R.string.playlist_exists))
                    CustomPlaylistStore.rename(this, old, name) -> {
                        toast(getString(R.string.playlist_renamed, name))
                        refreshPlaylistsPage()
                    }
                    else -> toast(getString(R.string.playlist_exists))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 删除自建歌单。
     *
     * **只删歌单记录，绝不碰手机里的音频文件**（用户明确要求）。
     * 文案里也把这一点写给用户看，避免他们以为会删文件而不敢用。
     */
    private fun confirmDeletePlaylist(name: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.playlist_delete)
            .setMessage(getString(R.string.playlist_delete_confirm, name))
            .setPositiveButton(R.string.ok) { _, _ ->
                CustomPlaylistStore.delete(this, name)
                CoverManager.clearPlaylistCover(this, name)
                toast(getString(R.string.playlist_deleted, name))
                refreshPlaylistsPage()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 「添加到歌单」：列出自建歌单（含「新建歌单」入口）。 */
    private fun showAddToPlaylistDialog(song: Song) {
        val uri = song.uri.toString()
        val names = CustomPlaylistStore.names(this)
        val labels = names.toMutableList()
        labels.add(getString(R.string.playlist_create))
        AlertDialog.Builder(this)
            .setTitle(R.string.playlist_add_to)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == names.size) {
                    showCreatePlaylistDialog()
                } else {
                    val name = names[which]
                    if (CustomPlaylistStore.add(this, name, uri)) {
                        toast(getString(R.string.playlist_added_to, name))
                    } else {
                        toast(getString(R.string.playlist_already_in, name))
                    }
                    refreshPlaylistsPage()
                }
            }
            .show()
    }

    // ---------- 视频播放页 ----------
    /**
     * 该文件是否能作为视频播放（是否含**真实视频轨**）。
     *
     * 2.13 修复：原实现只看扩展名（`.mp4` / `.m4v` 一律算视频），
     * 但音声作品的纯音频文件常被改名为 `.mp4`（实测 `トラック01/03/06` 均只有一条 aac 音轨、
     * 零条视频轨）。这类文件混进视频列表后点开只有声音、画面是黑的或残留上一部视频的帧。
     *
     * **会读文件头**（首次判定某文件时有 IO），别在列表循环里逐首调用；
     * 列表过滤请用 [isVideoFileCached]。
     */
    private fun isVideoFile(uri: android.net.Uri?): Boolean {
        if (uri == null) return false
        // 扩展名不像视频容器 → 直接否（零 IO）
        if (!VideoFileRules.couldBeVideo(uri.lastPathSegment)) return false
        val key = uri.toString()
        videoFlagCache[key]?.let { return it }
        val has = VideoFileRules.resolve(true, probeVideoTrack(uri))
        videoFlagCache[key] = has
        return has
    }

    /**
     * 只查缓存的版本（2.13）：**不读文件头**，主线程可安全逐首调用。
     * 缓存未命中时按扩展名兜底为「是视频」，所以列表只会**先多后准**、不会漏掉真视频。
     * 结果由 [warmVideoFlags] 在后台补全后触发刷新。
     */
    private fun isVideoFileCached(uri: android.net.Uri?): Boolean {
        if (uri == null) return false
        if (!VideoFileRules.couldBeVideo(uri.lastPathSegment)) return false
        return videoFlagCache[uri.toString()] ?: true
    }

    /**
     * 探测文件是否含视频轨。
     * @return true/false = 探测成功；null = 无法判断（调用方按扩展名兜底）
     *
     * 用 `applicationContext`：本方法会被后台预热线程调用，不该持有 Activity 引用。
     */
    private fun probeVideoTrack(uri: android.net.Uri): Boolean? {
        return try {
            val ex = android.media.MediaExtractor()
            ex.setDataSource(applicationContext, uri, null)
            var has = false
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    has = true
                    break
                }
            }
            ex.release()
            has
        } catch (e: Exception) {
            PlaybackLog.log("video track probe failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /** 打开视频页：方向跟随视频真实比例（横屏视频横屏、竖屏视频竖屏），绑定画面。 */
    private fun openVideoPage() {
        val song = playbackService?.currentSongSafe() ?: return
        videoW = 0
        videoH = 0
        try {
            val r = android.media.MediaMetadataRetriever()
            r.setDataSource(this, song.uri)
            var mw = r
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            var mh = r
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            // MMR 给的是「存储尺寸」，不含旋转角（2.13 修复）：
            // 手机竖拍的视频常以横屏存储 + rotation=90，直接用会让宽高比颠倒，
            // fitVideoSurface 按错比例缩放 → 画面被压成一条窄带/只显示一部分。
            val rot = r
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            r.release()
            if (rot == 90 || rot == 270) {
                val t = mw
                mw = mh
                mh = t
            }
            videoW = mw
            videoH = mh
            PlaybackLog.log("video open: mmr ${mw}x$mh rot=$rot")
        } catch (e: Exception) {
            PlaybackLog.log("video open: mmr FAILED ${e.javaClass.simpleName}: ${e.message}")
        }
        // 优先用播放器解码后的真实尺寸（retriever 给的是存储方向，旋转视频会错）
        val (sw, sh) = playbackService?.currentVideoSize() ?: (0 to 0)
        if (sw > 0 && sh > 0) {
            videoW = sw
            videoH = sh
        }
        PlaybackLog.log(
            "video open: final size ${videoW}x$videoH (player=${sw}x$sh) " +
                "surfaceAttached=$videoSurfaceAttached"
        )
        requestedOrientation = when {
            videoW > videoH ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            videoH > videoW ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        // 播放器后续解出/修正尺寸时同步
        playbackService?.onVideoSizeChanged = { w, h -> onVideoSizeReady(w, h) }
        showPage(Page.VIDEO)
        // 2.13：进入视频页后按「补绑 → 隐藏旧帧 → 适配画面」的顺序处理，缺一不可：
        // ① 补绑：从视频列表点进来时 TextureView 的 SurfaceTexture 可能已存在且可见
        //    （view 只是 GONE→VISIBLE），不会再触发 onSurfaceTextureAvailable，
        //    不主动绑定就是**全黑**（粉丝反馈「点别的就黑了」）。
        // ② 隐藏旧帧：必须**在绑定之后**判断——否则 videoSurfaceAttached 还是 false，
        //    隐藏会被跳过，新视频首帧到来前一直显示上一个视频的残留帧（粉丝反馈的原 bug）。
        //    放在这里而不是只靠 onSongChanged：点视频时执行顺序是
        //    `playSong()`（内部 showPage(PLAYER)）→ `openVideoPage()`（showPage(VIDEO)），
        //    切歌那一刻 page 还是 PLAYER，onSongChanged 里那次调用会被跳过。
        // ③ 适配：不能干等 onVideoSizeChanged——部分视频/机型不回调该事件，
        //    画面会一直沿用**上一个视频的矩阵**（放大到只剩局部，或位移出可视区变黑）。
        videoSurface.post {
            if (page != Page.VIDEO) return@post
            ensureVideoSurfaceBound()
            awaitVideoFirstFrame()
            fitVideoSurface(videoSurface.width, videoSurface.height)
        }
        seekVideo.max = playbackService?.currentDuration() ?: 0
        txtVideoHint.visibility =
            if (playbackService?.isPlayingSafe() == true) View.GONE else View.VISIBLE
        findViewById<ImageButton>(R.id.btnVideoPlay).setImageResource(
            if (playbackService?.isPlayingSafe() == true) R.drawable.ic_pause
            else R.drawable.ic_play
        )
    }

    /** 播放器解出真实视频尺寸：修正方向并重新适配画面。 */
    private fun onVideoSizeReady(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || page != Page.VIDEO) return
        val changed = videoW != w || videoH != h
        videoW = w
        videoH = h
        PlaybackLog.log("video onSizeReady ${w}x$h changed=$changed attached=$videoSurfaceAttached")
        val target = when {
            w > h -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            h > w -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (target != requestedOrientation) {
            requestedOrientation = target
        }
        // 2.13：尺寸解出来是「能画了」的信号，此刻必须确保 surface 已绑定
        // （切歌瞬间 attachVideoSurface 可能因播放器未就绪被延后）
        ensureVideoSurfaceBound()
        if (changed && videoSurfaceAttached) {
            // post 到下一帧：旋转刚触发时 view 尺寸可能还没完成重排，
            // 立刻取 width/height 会拿到旧值（1.31 错位修复）
            videoSurface.post {
                if (videoSurfaceAttached && page == Page.VIDEO) {
                    fitVideoSurface(videoSurface.width, videoSurface.height)
                }
            }
        } else if (videoSurfaceAttached) {
            fitVideoSurface(videoSurface.width, videoSurface.height)
        }
    }

    /**
     * 确保视频画面已绑定到播放器（2.13）。
     *
     * 为什么必须主动做：`onSurfaceTextureAvailable` 只在 SurfaceTexture **首次创建**时回调。
     * 而这个 TextureView 长住在布局里（只切 visibility），从视频列表点进另一个视频时
     * SurfaceTexture 早就存在了 → **不会再回调 available** → 没人调 attachVideoSurface
     * → MediaPlayer 没有输出目标 → **全黑**（粉丝反馈「点别的就黑了」）。
     * 这里在每次进入视频页时检查并补绑；幂等（重复绑定只是重设一次 surface）。
     */
    private fun ensureVideoSurfaceBound() {
        if (!videoSurface.isAvailable) {
            PlaybackLog.log("video surface not available yet, wait for callback")
            return
        }
        if (videoSurfaceAttached) return
        val st = videoSurface.surfaceTexture
        if (st == null) {
            PlaybackLog.log("video surface isAvailable but surfaceTexture null")
            return
        }
        playbackService?.attachVideoSurface(android.view.Surface(st))
        videoSurfaceAttached = true
        PlaybackLog.log("video surface rebound on page open")
    }

    /**
     * 视频画面完整显示（fit：等比缩放、居中、宁可黑边不裁切）。
     * 画面区域 = 视频 FrameLayout，不含底部进度条。
     *
     * 关键：`TextureView` 默认把画面**拉伸铺满**自己的边界（fitXY），
     * 且 `setTransform` 的矩阵作用在 **view 空间** —— 所以缩放比必须是「目标尺寸 / view 尺寸」。
     * 早先写成「view 像素 / 视频像素」会二次缩放：view 比视频大时画面被放大溢出、四周被裁，
     * 表现为「只显示一部分 / 画面不完整 / 被拉伸」（粉丝与弱志实测均为此）。
     * 几何计算抽到 [VideoFit]（纯函数 + 单测覆盖），这里只负责落地成矩阵。
     *
     * 另：任何一次调用都必须给出确定矩阵 —— 尺寸不全时退回 [idleTransform]，
     * 绝不能什么都不做（那会沿用上一个视频的矩阵）。
     */
    private fun fitVideoSurface(viewW: Int, viewH: Int) {
        val f = VideoFit.fit(viewW, viewH, videoW, videoH)
        if (f == null) {
            // 尺寸不全：退回「按 16:9 估」的中性变换，目的是覆盖掉上一个视频的矩阵
            videoSurface.setTransform(idleTransform(viewW, viewH))
            PlaybackLog.log(
                "video fit fallback (size unknown) v=${videoW}x$videoH view=${viewW}x$viewH"
            )
            return
        }
        applyFit(f)
        PlaybackLog.log("video fit ${videoW}x$videoH -> ${viewW}x$viewH")
    }

    /** 把适配结果落成矩阵。 */
    private fun applyFit(f: VideoFit.Fit) {
        val m = android.graphics.Matrix()
        m.setScale(f.scaleX, f.scaleY)
        m.postTranslate(f.dx, f.dy)
        videoSurface.setTransform(m)
    }

    /**
     * 尺寸未知时的中性变换（2.13）：按横屏 16:9 估算，**按比例完整放进** view。
     * 唯一目的是主动覆盖掉上一个视频的矩阵；绝不放大到只剩局部。
     */
    private fun idleTransform(viewW: Int, viewH: Int): android.graphics.Matrix {
        val m = android.graphics.Matrix()
        VideoFit.fit(viewW, viewH, 1920, 1080)?.let {
            m.setScale(it.scaleX, it.scaleY)
            m.postTranslate(it.dx, it.dy)
        }
        return m
    }

    /** 关闭视频页：脱离画面（播放不中断），恢复竖屏。 */
    private fun closeVideoPage() {
        videoLongPressHandler.removeCallbacksAndMessages(null)
        // 清掉「等首帧」状态，避免回到视频页时残留隐藏态（2.13）
        videoFrameHandler.removeCallbacksAndMessages(null)
        videoFramePending = false
        videoSurface.alpha = 1f
        if (videoSpeedUp) {
            videoSpeedUp = false
            playbackService?.setSpeed(1f)
        }
        playbackService?.attachVideoSurface(null)
        videoSurfaceAttached = false
        playbackService?.onVideoSizeChanged = null
        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        showPage(Page.PLAYER)
    }

    /** 视频页触摸：单击暂停/播放，点左半屏回退 10s、右半屏快进 10s，长按 2 倍速。 */
    private fun handleVideoTouch(v: View, ev: android.view.MotionEvent): Boolean {
        when (ev.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                videoDownX = ev.x
                videoSpeedUp = false
                videoLongPressHandler.removeCallbacksAndMessages(null)
                videoLongPressHandler.postDelayed({
                    videoSpeedUp = true
                    playbackService?.setSpeed(2f)
                }, 400)
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                videoLongPressHandler.removeCallbacksAndMessages(null)
                if (videoSpeedUp) {
                    videoSpeedUp = false
                    playbackService?.setSpeed(1f)
                } else {
                    val svc = playbackService
                    val dur = svc?.currentDuration() ?: 0
                    val pos = svc?.currentPosition() ?: 0
                    if (videoDownX < v.width / 2f) {
                        svc?.seekTo((pos - 10000).coerceAtLeast(0))
                    } else {
                        svc?.seekTo((pos + 10000).coerceAtMost(dur))
                    }
                }
            }
        }
        return true
    }

    private fun refreshCdCover() {
        // 用 Service 当前歌曲（不依赖 Activity 队列，后台/闹钟恢复播放也能刷新）
        val song = playbackService?.currentSongSafe() ?: return
        android.util.Log.d("ShiYinCover", "refreshCdCover song=${song.uri} key=$currentCoverKey")
        CoverLoader.invalidate(song.uri.toString())
        imgCd.setImageResource(R.drawable.ic_music_tinted)
        CoverLoader.load(this, song.uri, coverLoadSize(), folder = song.folder, songSize = song.size) { bmp ->
            android.util.Log.d("ShiYinCover", "refreshCdCover bmp=${bmp != null} key=$currentCoverKey")
            if (song.uri.toString() != currentCoverKey) return@load
            // 清除封面且无内嵌/歌单封面兜底时 bmp 为 null → 背景退回自定义图/纯色（2.12.1）
            if (bmp != null) {
                imgCd.setImageBitmap(bmp)
                currentCoverBmp = bmp
                applyPlayerBackground(bmp)
            } else {
                imgCd.setImageResource(R.drawable.ic_music_tinted)
                currentCoverBmp = null
                applyPlayerBackground(null)
            }
            // 手动设置/清除封面后，封面背景要同步换图
            applyPageBackground()
        }
    }

    /**
     * 切歌时隐藏旧帧（2.13）。
     * TextureView 会保留上一个视频的最后一帧，新视频首帧到来前一直显示旧画面——
     * 粉丝反馈的「点不同视频画面是之前的视频，但音频正常」就是这个。
     * 做法：先把画面设为透明（露出黑色底），等 onSurfaceTextureUpdated 收到新首帧再显示。
     * 必须有超时兜底：个别设备/纯音频不回调 updated，否则会一直黑屏。
     *
     * 2.13 收紧：这条「先隐藏」是**主动制造黑屏**，一旦首帧回不来就是用户看到的全黑。
     * 因此①只在确实在视频页、且 surface 已绑定（有新帧可期）时才隐藏；
     * ②超时从 2 秒缩到 1.2 秒，把最坏黑屏时间压到最短。
     */
    private fun awaitVideoFirstFrame() {
        if (page != Page.VIDEO) return
        // surface 没绑上时不可能有新帧到达，隐藏等于直接黑屏 —— 此时保持原画面
        if (!videoSurfaceAttached || !videoSurface.isAvailable) {
            PlaybackLog.log("video awaitFirstFrame skipped (surface not ready)")
            return
        }
        videoFramePending = true
        videoFrameReadyAfter = System.currentTimeMillis() + FRAME_GRACE_MS
        videoSurface.alpha = 0f
        videoFrameHandler.removeCallbacksAndMessages(null)
        videoFrameHandler.postDelayed({
            // 超时兜底：还没等到新帧就直接显示（宁可短暂旧帧，不能黑屏）
            if (videoFramePending) {
                videoFramePending = false
                videoSurface.alpha = 1f
                PlaybackLog.log("video first-frame timeout, show anyway")
            }
        }, 1200)
    }

    /** 播放页背景：封面背景 > 自定义背景图 > 封面主色渐变（暗化保证可读）。 */
    private fun applyPlayerBackground(cover: Bitmap?) {
        // 封面背景开启且有封面时，以封面铺满为准，不再叠主色渐变（2.12.1）
        if (coverBgEnabled() && currentCoverBmp != null) return
        val bgUri = BgManager.bgUri(this)
        if (bgUri != null) {
            BgManager.apply(viewPlayer, bgUri)
            return
        }
        if (cover == null) {
            viewPlayer.background = null
            return
        }
        try {
            val color = Bitmap.createScaledBitmap(cover, 1, 1, true).getPixel(0, 0)
            val blend = { c: Int, ratio: Float ->
                android.graphics.Color.rgb(
                    (android.graphics.Color.red(c) * ratio).toInt().coerceIn(0, 255),
                    (android.graphics.Color.green(c) * ratio).toInt().coerceIn(0, 255),
                    (android.graphics.Color.blue(c) * ratio).toInt().coerceIn(0, 255)
                )
            }
            val gd = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(blend(color, 0.5f), blend(color, 0.25f))
            )
            viewPlayer.background = gd
        } catch (e: Exception) {
            viewPlayer.background = null
        }
    }

    private fun translationConfig(): LyricTranslator.Config? =
        LyricTranslator.configFrom(
            prefs.getString(KEY_TRANS_BASE, null),
            prefs.getString(KEY_TRANS_KEY, null),
            prefs.getString(KEY_TRANS_MODEL, null)
        )

    /**
     * 该行是否已达重试上限、应跳过（2.13 熔断）。
     * 防止「永久失败的行」被每个窗口反复重发把费用放大几十倍。
     */
    private fun shouldSkipLine(uriKey: String, line: Int): Boolean =
        (transFailCount[uriKey]?.get(line) ?: 0) >= MAX_LINE_RETRY

    /**
     * 记录一次翻译尝试的结果（2.13 熔断）。
     * 成功的行清掉计数（说明只是偶发失败，下次不再拦）；失败的行累计。
     */
    private fun recordTranslateAttempt(
        uriKey: String,
        attempted: List<Pair<Int, String>>,
        result: LyricTranslator.TransResult
    ) {
        val m = transFailCount.getOrPut(uriKey) { HashMap() }
        for ((line, _) in attempted) {
            if (line in result.translations) {
                m.remove(line)
            } else {
                m[line] = (m[line] ?: 0) + 1
            }
        }
    }

    private fun translateCurrentLyric() {
        val lines = lyricLines
        if (lines.isEmpty()) {
            toast(getString(R.string.no_lyric))
            return
        }
        val cfg = translationConfig()
        if (cfg == null) {
            toast(getString(R.string.trans_no_key))
            showTransSettingsDialog()
            return
        }
        if (translating) return
        translating = true
        btnTranslate.isEnabled = false
        btnTranslate.text = getString(R.string.translating)

        val reqKey = lastSong?.uri?.toString() ?: ""
        val cache = translationCache.getOrPut(reqKey) { HashMap() }
        val toTranslate = if (transFailedLines.isNotEmpty()) {
            transFailedLines
        } else {
            lines.withIndex().filter { it.index !in cache }.map { it.index to it.value.text }
        }
        if (toTranslate.isEmpty()) {
            translating = false
            btnTranslate.isEnabled = true
            btnTranslate.text = getString(R.string.translate)
            toast(getString(R.string.trans_ok))
            return
        }

        Thread {
            val result = try {
                LyricTranslator.translate(toTranslate, cfg)
            } catch (e: Exception) {
                LyricTranslator.TransResult(emptyMap(), "请求异常：${e.message}")
            }
            runOnUiThread {
                translating = false
                btnTranslate.isEnabled = true
                btnTranslate.text = getString(R.string.translate)
                // 译文永远写进发起请求那首歌的缓存（reqKey），与当前播放哪首无关
                cache.putAll(result.translations)
                LyricTranslationCache.save(applicationContext, translationCache)
                // 只有请求发起时的歌仍是当前歌，才刷新界面状态，避免旧歌数据串进新歌（1.30）
                if (lastSong?.uri?.toString() == reqKey) {
                    transFailedLines = toTranslate.filter { it.first !in result.translations }
                    lyricAdapter.setTranslations(translationCache[reqKey] ?: emptyMap())
                    playbackService?.reloadLyricTranslations()
                    when {
                        result.translations.isEmpty() && result.error != null ->
                            showTransError("翻译失败：${result.error}")
                        result.translations.isEmpty() ->
                            toast(getString(R.string.trans_all_fail))
                        transFailedLines.isNotEmpty() && result.error != null ->
                            showTransError("部分翻译失败：${result.error}")
                        transFailedLines.isNotEmpty() ->
                            toast(getString(R.string.trans_partial_fail, transFailedLines.size))
                        else -> toast(getString(R.string.trans_ok))
                    }
                }
            }
        }.start()
    }

    /** 用可滚动对话框显示翻译错误（完整内容，不受 toast 两行限制）。 */
    private fun showTransError(msg: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.translate)
            .setMessage(msg)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    /** 删除当前歌曲已保存的翻译（长按翻译按钮触发，1.30）。 */
    private fun deleteCurrentTranslation() {
        val song = lastSong ?: return
        val uriKey = song.uri.toString()
        if (translationCache[uriKey].isNullOrEmpty()) {
            toast(getString(R.string.trans_none))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.translate)
            .setMessage(R.string.trans_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (translating) {
                    toast(getString(R.string.trans_busy))
                    return@setPositiveButton
                }
                translationCache.remove(uriKey)
                LyricTranslationCache.save(applicationContext, translationCache)
                transFailedLines = emptyList()
                lyricAdapter.setTranslations(emptyMap())
                playbackService?.reloadLyricTranslations()
                toast(getString(R.string.trans_deleted))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 歌词校准（2.2）：整体 ± 偏移 ----------

    private fun showLyricOffsetDialog() {
        val song = lastSong
        if (song == null) {
            toast(getString(R.string.no_lyric))
            return
        }
        if (asrRunning) {
            toast(getString(R.string.offset_busy_asr))
            return
        }
        lyricOffsetSong = song
        lyricOffsetExtra = LyricOffset.load(this, song.uri.toString())
        lyricOffsetFileTag = 0

        val txtOffset = TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, dp(8f), 0, dp(8f))
        }
        lyricOffsetLabelView = txtOffset

        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        for (delta in intArrayOf(-300, -100, 100, 300)) {
            val b = Button(this).apply {
                text = String.format(Locale.US, "%+.1fs", delta / 1000.0)
                setOnClickListener {
                    lyricOffsetExtra = (lyricOffsetExtra + delta).coerceIn(-60000, 60000)
                    applyLyricOffsetNow()
                }
            }
            // 四个按钮等分一行（weight=1），窄屏也不会把 +0.3s 挤出对话框
            val lp = android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            lp.setMargins(dp(3f), 0, dp(3f), 0)
            b.layoutParams = lp
            row.addView(b)
        }

        val rowReset = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.offset_reset)
                setOnClickListener {
                    // 清零 = 整体归零：追加层补上「负的文件标签」，随后的防抖写入会把
                    // 文件里的 [offset:] 标签一并移除（整体偏移真正回到 0）
                    lyricOffsetExtra = (0 - lyricOffsetFileTag).coerceIn(-60000, 60000)
                    applyLyricOffsetNow()
                }
            })
        }

        val txtHint = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.text_hint))
            setPadding(0, dp(10f), 0, 0)
            text = getString(R.string.offset_hint)
        }

        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24f), dp(16f), dp(24f), dp(4f))
            addView(txtOffset)
            addView(row)
            addView(rowReset)
            addView(txtHint)
        }

        lyricOffsetDialog = AlertDialog.Builder(this)
            .setTitle(R.string.offset_title)
            .setView(box)
            .setNegativeButton(R.string.close, null)
            .show()
            .also { d -> d.setOnDismissListener { lyricOffsetLabelView = null } }
        refreshLyricOffsetLabel()

        // 后台读歌词文件：显示文件自带的 [offset:] 标签（解析器已应用，通常为 0）
        val songKey = song.uri.toString()
        Thread {
            val tag = try {
                val text = readLyricSourceText(song)
                if (text != null) LyricOffset.detectOffsetTag(text) else 0
            } catch (e: Exception) {
                0
            }
            runOnUiThread {
                if (lyricOffsetSong?.uri?.toString() == songKey &&
                    lyricOffsetDialog?.isShowing == true
                ) {
                    lyricOffsetFileTag = tag
                    refreshLyricOffsetLabel()
                }
            }
        }.start()
    }

    private fun refreshLyricOffsetLabel() {
        val v = lyricOffsetLabelView ?: return
        v.text = getString(
            R.string.offset_current,
            fmtOffsetMs(lyricOffsetExtra + lyricOffsetFileTag),
            fmtOffsetMs(lyricOffsetFileTag)
        )
    }

    private fun fmtOffsetMs(ms: Int): String =
        if (ms == 0) "0s" else String.format(Locale.US, "%+.1fs", ms / 1000.0)

    private fun applyLyricOffsetNow() {
        val song = lyricOffsetSong ?: return
        // 发起校准时的歌已经切走：不再把偏移写进别的歌（1.30 串歌教训）
        if (playbackService?.currentSongSafe()?.uri?.toString() != song.uri.toString()) return
        playbackService?.applyLyricOffset(lyricOffsetExtra)
        // onSongChanged 会把倍速按钮重置为 1x，需回写真实倍速（1.33 的坑）
        val spd = playbackService?.currentSpeed() ?: 1f
        btnSpeed.text = if (spd == 1f) "1x" else "${spd}x"
        refreshLyricOffsetLabel()
        scheduleLyricOffsetFileWrite(song)
    }

    /** 立即生效；600ms 防抖后写回歌词文件（连点只写最后一次，不卡 UI）。 */
    private fun scheduleLyricOffsetFileWrite(song: Song) {
        lyricOffsetWritePending?.let { lyricOffsetWriteHandler.removeCallbacks(it) }
        // 到点写入时的追加偏移以本次调度为准（中途切到别的歌也不串值）
        val extraAtSchedule = lyricOffsetExtra
        val r = Runnable { writeLyricOffsetToFile(song, extraAtSchedule) }
        lyricOffsetWritePending = r
        lyricOffsetWriteHandler.postDelayed(r, 600)
    }

    /**
     * 把 追加偏移 合并写进歌词文件的 [offset:] 标签（后台线程）。
     * 成功后应用内追加偏移清零（由文件标签接管）；写不进去（内嵌歌词/无权限）则保留在应用内。
     */
    private fun writeLyricOffsetToFile(song: Song, extraMs: Int) {
        val songKey = song.uri.toString()
        Thread {
            var wroteOk = false
            var unchanged = false
            var newFileTag = 0
            try {
                val text = readLyricSourceText(song)
                if (text != null && LyricOffset.hasTimeTag(text)) {
                    newFileTag = LyricOffset.detectOffsetTag(text) + extraMs
                    val newText = LyricOffset.buildLrcWithOffset(text, newFileTag)
                    unchanged = newText == text
                    if (!unchanged) {
                        wroteOk = writeLyricSourceText(song, newText)
                    }
                    // 写成功立刻清掉追加偏移（文件标签从此接管），把「文件+应用内双重偏移」
                    // 的时间窗口压到最小（进程在两步之间被杀才会出现双重偏移）
                    if (wroteOk || unchanged) {
                        try { LyricOffset.save(this@MainActivity, songKey, 0) } catch (e: Exception) { }
                    }
                }
            } catch (e: Exception) {
                wroteOk = false
            }
            runOnUiThread {
                if (wroteOk || unchanged) {
                    lyricOffsetExtra = 0
                    if (lyricOffsetSong?.uri?.toString() == songKey) {
                        lyricOffsetFileTag = newFileTag
                        refreshLyricOffsetLabel()
                    }
                    toast(getString(R.string.offset_saved_file))
                } else {
                    toast(getString(R.string.offset_in_app_only))
                }
            }
        }.start()
    }

    /** 读出本歌歌词源文本：外置 .lrc（与播放同一匹配规则）优先，其次应用内识别兜底。 */
    private fun readLyricSourceText(song: Song): String? {
        val ref = library?.let { LibraryScanner.findLyric(song, it.lyrics) }
        if (ref != null && ref.displayName.endsWith(".lrc", true)) {
            val bytes = contentResolver.openInputStream(ref.uri)?.use { it.readBytes() }
            if (bytes != null && bytes.isNotEmpty()) return LyricOffset.decodeLrcText(bytes)
        }
        val stem = song.fileStem.ifBlank {
            song.uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: ""
        }
        if (stem.isNotBlank()) {
            val f = java.io.File(filesDir, "asr_lrc/$stem.lrc")
            if (f.exists()) return LyricOffset.decodeLrcText(f.readBytes())
        }
        return null
    }

    /** 整串覆写歌词源文件（先拿到输出流再写入，内容已在内存备好）。 */
    private fun writeLyricSourceText(song: Song, text: String): Boolean {
        val ref = library?.let { LibraryScanner.findLyric(song, it.lyrics) }
        if (ref != null && ref.displayName.endsWith(".lrc", true)) {
            try {
                val os = contentResolver.openOutputStream(ref.uri)
                if (os != null) {
                    os.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    return true
                }
            } catch (e: Exception) {
            }
        }
        val stem = song.fileStem.ifBlank {
            song.uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: ""
        }
        if (stem.isNotBlank()) {
            val f = java.io.File(filesDir, "asr_lrc/$stem.lrc")
            if (f.exists()) {
                return try {
                    f.writeText(text)
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }
        return false
    }

    /**
     * 自动翻译：开关开启时，播放非中文歌词且未翻译过的歌曲自动翻译。
     * 约束：仅非中文歌词；每首歌只翻译一遍（缓存已存在或已尝试过则跳过）。
     */
    private fun maybeAutoTranslate(song: Song?, lines: List<SubtitleLine>) {
        if (!prefs.getBoolean(KEY_AUTO_TRANS, false)) return
        if (song == null || lines.isEmpty()) return
        if (translating) return
        if (translationConfig() == null) return
        val uriKey = song.uri.toString()
        if (translationCache.containsKey(uriKey)) return
        if (LyricTranslator.isChinesePrimarily(lines)) return
        translateCurrentLyric()
    }

    private fun showAboutDialog() {
        val view = layoutInflater.inflate(R.layout.about_dialog, null)
        view.findViewById<TextView>(R.id.txtAboutSupport).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_URL)))
            } catch (e: Exception) {
                toast("无法打开浏览器")
            }
        }
        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** 导出播放运行日志（1.34）：滚动窗口展示 + 一键复制，用户发给作者定位播放异常。 */
    /** 歌词识别入口（1.33.1 实验）：模型未就绪先引导下载，就绪后选音频生成 lrc。 */
    private fun showAsrDialog() {
        val ready = SpeechRecManager.isModelReady(this)
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.asr_title)
            .setMessage(getString(if (ready) R.string.asr_ready_msg else R.string.asr_need_model_msg))
            .setNegativeButton(R.string.close, null)
        if (ready) {
            builder.setPositiveButton(R.string.asr_pick_audio) { _, _ ->
                try {
                    asrAudioPicker.launch(arrayOf("audio/*"))
                } catch (e: Exception) {
                    toast(getString(R.string.asr_failed, e.message))
                }
            }
        } else {
            builder.setPositiveButton(R.string.asr_download_model) { _, _ ->
                showAsrDownloadDialog()
            }
            builder.setNeutralButton(R.string.asr_import_model) { _, _ ->
                asrModelPicker.launch(arrayOf("*/*"))
            }
        }
        builder.show()
    }

    /** 模型下载（后台线程 + 进度弹窗）。 */
    private fun showAsrDownloadDialog() {
        asrCancelled = false
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.asr_title)
            .setMessage(getString(R.string.asr_downloading))
            .setNegativeButton(R.string.cancel) { _, _ -> asrCancelled = true }
            .show()
        SpeechRecManager.downloadModel(
            this,
            onProgress = { idx, pct ->
                runOnUiThread {
                    if (!dlg.isShowing) return@runOnUiThread
                    dlg.setMessage(getString(R.string.asr_downloading_file, idx + 1, pct))
                }
            },
            onDone = { ok, err ->
                runOnUiThread {
                    if (dlg.isShowing) dlg.dismiss()
                    PlaybackLog.log("asr model download ok=$ok err=$err")
                    if (ok) {
                        toast(getString(R.string.asr_model_ready))
                        showAsrDialog()
                    } else {
                        toast(getString(R.string.asr_model_failed, err ?: ""))
                    }
                }
            },
            isCancelled = { asrCancelled }
        )
    }

    /** 检测音频同目录的台本文件（.txt/.srt），返回文本（供热词提升识别率）。 */
    private fun findScriptText(uri: Uri, trees: List<Uri>): String? {
        val docId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (e: Exception) {
            return null
        }
        for (tree in trees) {
            try {
                val treeDocId = DocumentsContract.getTreeDocumentId(tree)
                if (docId != treeDocId && !docId.startsWith("$treeDocId/")) continue
                val parentDocId = if (docId.contains('/')) docId.substringBeforeLast('/') else treeDocId
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, parentDocId
                )
                // 2.14：先收集本目录全部 (docId, 文件名)，再在内存里筛选。
                // 识别「自动序号残留」需要知道原名在不在（见 LrcResidueRules），
                // 而原名可能排在编号文件之后，所以必须收完再判，不能边扫边判。
                val entries = mutableListOf<Pair<String, String>>()
                contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    ),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        entries.add(id to name)
                    }
                }
                val allNames = entries.map { it.second }.toHashSet()
                val candidates = mutableListOf<Pair<Uri, String>>()
                for ((id, name) in entries) {
                    val lower = name.lowercase(Locale.getDefault())
                    if (lower.endsWith(".txt") || lower.endsWith(".srt")) {
                        // 跳过 SAF 自动序号残留（如 台本(1).txt）——那多半是识别产物，
                        // 当台本用会把上一次的歌词喂回去，越滚越脏（粉丝反馈的闭环）。
                        if (LrcResidueRules.isResidue(name, allNames)) {
                            PlaybackLog.log("asr script skip residue: $name")
                            continue
                        }
                        candidates.add(
                            DocumentsContract.buildDocumentUriUsingTree(tree, id) to name
                        )
                    }
                }
                for ((u, name) in candidates) {
                    val text = try {
                        contentResolver.openInputStream(u)?.use { input ->
                            val bytes = input.readBytes()
                            String(bytes, 0, minOf(bytes.size, 200_000), Charsets.UTF_8)
                        }
                    } catch (e: Exception) {
                        null
                    }
                    if (!text.isNullOrBlank()) {
                        PlaybackLog.log("asr script found: $name (${text.length} chars)")
                        return text
                    }
                }
            } catch (e: Exception) {
            }
        }
        return null
    }

    /** 歌词页「生成歌词」：检测台本 → 提示用台本识别（更准）或直接实时识别。 */
    private fun startAsrForCurrentSong() {
        val song = lastSong
        when {
            // 2.14 防呆：识别进行中就不要再弹这个对话框。
            // 旧实现只在 startAsrTranscribe 里拦 asrRunning，但对话框已经弹出来了——
            // 用户看到对话框会以为可以再来一次，反复点「生成歌词」，每次都走到
            // createLrcTarget；这正是「lrc(1)/(2)/(3)」残留的入口（粉丝反馈）。
            asrRunning -> toast(getString(R.string.asr_busy_running))
            song == null -> toast(getString(R.string.no_song))
            !SpeechRecManager.isModelReady(this) -> showAsrDialog()
            else -> {
                val script = findScriptText(song.uri, savedTreeUris())
                val builder = AlertDialog.Builder(this)
                    .setTitle(R.string.asr_gen_short)
                if (script != null) {
                    builder.setMessage(R.string.asr_script_found)
                        .setPositiveButton(R.string.asr_use_script) { _, _ ->
                            startAsrTranscribe(song.uri, script)
                        }
                        .setNeutralButton(R.string.asr_start_direct) { _, _ ->
                            startAsrTranscribe(song.uri, null)
                        }
                        .setNegativeButton(R.string.cancel, null)
                } else {
                    builder.setMessage(R.string.asr_gen_confirm)
                        .setPositiveButton(R.string.ok) { _, _ ->
                            startAsrTranscribe(song.uri, null)
                        }
                        .setNegativeButton(R.string.cancel, null)
                }
                builder.show()
            }
        }
    }

    private fun asrStatusRowVisible(visible: Boolean) {
        runOnUiThread {
            findViewById<View>(R.id.asrStatusRow).visibility =
                if (visible) View.VISIBLE else View.GONE
            // 2.14 防呆：识别进行中把「生成歌词」置灰。
            // 与 startAsrForCurrentSong 开头的拦截是双保险——按钮灰掉让用户一眼看出
            // 「现在不能再点」，而不是点了弹提示；两者都指向同一个目的：
            // 不让识别被重复触发（重复触发正是 lrc(1)/(2)/(3) 残留的来源）。
            findViewById<Button>(R.id.btnGenLyric)?.isEnabled = !visible
        }
    }

    private fun updateAsrStatus(sec: Int, total: Int, lineCount: Int) {
        runOnUiThread {
            txtAsrStatus.text = getString(R.string.asr_status_running, sec, total, lineCount)
        }
    }

    /**
     * 非阻塞实时识别（2.0）：不弹进度框，歌词页顶部显示状态行，
     * 已识别的行实时浮现在歌词列表里并跟随播放高亮（边听边出）；
     * 识别比实时快约 10 倍，整首歌词很快就能完整呈现。
     */
    private fun startAsrTranscribe(uri: Uri, scriptText: String?) {
        if (asrRunning) {
            toast(getString(R.string.asr_busy_running))
            return
        }
        asrRunning = true
        asrCancelled = false
        asrLiveUri = null
        asrLiveLines = emptyList()
        // 重置逐窗翻译游标与失败计数（2.13）：本次识别从第 0 行重新开始
        resetAsrTranslateState()
        val uriKey = uri.toString()
        if (uriKey == lastSong?.uri?.toString()) {
            // 为当前播放的歌识别：激活「边听边出」实时歌词（含逐窗翻译预览）
            asrLiveUri = uriKey
        }
        asrStatusRowVisible(true)
        updateAsrStatus(0, 0, 0)
        val trees = savedTreeUris()
        Thread {
            SpeechRecManager.transcribe(
                this,
                uri,
                trees,
                scriptText,
                onProgress = { sec, total ->
                    runOnUiThread {
                        txtAsrStatus.text = getString(
                            R.string.asr_status_running, sec, total, asrLiveLines.size
                        )
                    }
                },
                onWindowDone = { linesSoFar ->
                    // 识别线程：逐窗实时翻译（已配置 API 时），更新预览。
                    // 2.13 关键改造（成本止损）：
                    // 旧实现用 `linesSoFar.filter { 行号 !in cache }` 求待翻译行——linesSoFar 是
                    // **累计全文**，于是任何一行只要没进 cache，就会在**后续每个窗口被重发一次**。
                    // 翻译失败的行（模型跳过 / 解析失败 / 网络错）因此被反反复复重发，
                    // 一小时音频模拟实测可放大到 60 倍费用（理想 0.16 元 → 全失败 8.7 元）。
                    // 现在改为「只翻译本窗新增行」+ 失败行重试上限，双保险。
                    //
                    // 另外：非当前播放歌的识别不做逐窗翻译——逐窗翻译只为「边听边出」预览，
                    // 非当前歌没有预览需求，等识别完成后一次翻完更省（走 translateGeneratedLyrics）。
                    val cfg = translationConfig()
                    if (cfg != null && asrLiveUri != null) {
                        try {
                            val cache = translationCache.getOrPut(uriKey) { HashMap() }
                            // 增量：只取「行号 ≥ 上次已提交游标」的新增行
                            val newCount = linesSoFar.size - asrTranslatedLineCount
                            if (newCount > 0) {
                                val todo = (asrTranslatedLineCount until linesSoFar.size)
                                    .map { i -> i to linesSoFar[i].text }
                                    .filter { it.first !in cache && !shouldSkipLine(uriKey, it.first) }
                                // 游标无论如何都推进：已处理过的行不再进入下一窗的「新增」
                                asrTranslatedLineCount = linesSoFar.size
                                if (todo.isNotEmpty()) {
                                    val result = try {
                                        LyricTranslator.translate(todo, cfg)
                                    } catch (e: Exception) {
                                        LyricTranslator.TransResult(emptyMap(), "请求异常：${e.message}")
                                    }
                                    if (result.translations.isNotEmpty()) {
                                        cache.putAll(result.translations)
                                        LyricTranslationCache.save(applicationContext, translationCache)
                                    }
                                    // 记账：成功清空失败计数；失败累计（超限后 shouldSkipLine 拦掉）
                                    recordTranslateAttempt(uriKey, todo, result)
                                    PlaybackLog.log(
                                        "asr live translate: ok=${result.translations.size}/${todo.size} " +
                                            "err=${result.error}"
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            PlaybackLog.log("asr live translate THREW: ${e.message}")
                        }
                    }
                    runOnUiThread {
                        val live = uriKey == lastSong?.uri?.toString()
                        asrLiveLines = linesSoFar.map { l ->
                            SubtitleLine((l.startSec * 1000).toInt(), (l.endSec * 1000).toInt(), l.text)
                        }
                        if (live) {
                            // 边听边出：歌词列表实时替换为已识别行，跟随播放高亮
                            lyricLines = asrLiveLines
                            lyricAdapter.submit(asrLiveLines)
                            lyricAdapter.setTranslations(translationCache[uriKey] ?: emptyMap())
                            val pos = playbackService?.currentPosition() ?: 0
                            val idx = asrLiveLines.indexOfLast { it.startMs <= pos }
                            currentLyricHighlight = idx
                            lyricAdapter.setCurrent(idx)
                            scrollToLyric(idx)
                            updateNowLyric(idx)
                        }
                        txtAsrStatus.text = getString(
                            R.string.asr_status_running,
                            (linesSoFar.lastOrNull()?.endSec ?: 0.0).toInt(),
                            (linesSoFar.lastOrNull()?.endSec ?: 0.0).toInt(),
                            linesSoFar.size
                        )
                    }
                },
                isCancelled = { asrCancelled }
            ) { ok, msg, savedWhere, lines ->
                runOnUiThread {
                    asrRunning = false
                    asrStatusRowVisible(false)
                    PlaybackLog.log("asr transcribe ok=$ok msg=$msg saved=$savedWhere lines=${lines?.size}")
                    if (ok) {
                        val cfg = translationConfig()
                        if (cfg != null && lines != null && translationCache[uriKey].isNullOrEmpty()) {
                            // 兜底：逐窗翻译全部失败过，至少补一次完整翻译
                            translateGeneratedLyrics(uriKey, lines)
                        }
                        // 应用内兜底时提示重选文件夹升级写权限（2.0 修复写拒绝）
                        if (savedWhere?.contains("应用内") == true) {
                            toast(getString(R.string.asr_done_internal, savedWhere ?: ""))
                        } else {
                            toast(getString(R.string.asr_done, savedWhere ?: ""))
                        }
                        // 只重扫所在文件夹（2.0）：秒级完成，比整库重扫快得多
                        rescanFolderForAsr(uri)
                        val spd = playbackService?.currentSpeed() ?: 1f
                        btnSpeed.text = if (spd == 1f) "1x" else "${spd}x"
                    } else if (msg == "已取消") {
                        toast("已取消识别")
                    } else {
                        toast(getString(R.string.asr_failed, msg ?: ""))
                    }
                }
            }
        }.start()
    }

    /**
     * 直接翻译 ASR 生成的歌词（不必播放该歌）：
     * 译文按歌曲 uri 写入翻译缓存；若该歌正在播放，立即刷新界面与桌面歌词。
     * 已翻译过的行跳过（省 API 费用），失败的行可在播放时点「翻译」重试。
     */
    private fun translateGeneratedLyrics(uriKey: String, lines: List<LrcLine>) {
        if (translating) {
            toast(getString(R.string.trans_busy))
            return
        }
        val cfg = translationConfig()
        if (cfg == null) {
            toast(getString(R.string.trans_no_key))
            showTransSettingsDialog()
            return
        }
        translating = true
        toast(getString(R.string.asr_translating))
        val cache = translationCache.getOrPut(uriKey) { HashMap() }
        val toTranslate = lines.mapIndexed { i, l -> i to l.text }.filter { it.first !in cache }
        if (toTranslate.isEmpty()) {
            translating = false
            toast(getString(R.string.trans_ok))
            return
        }
        Thread {
            val result = try {
                LyricTranslator.translate(toTranslate, cfg)
            } catch (e: Exception) {
                LyricTranslator.TransResult(emptyMap(), "请求异常：${e.message}")
            }
            runOnUiThread {
                translating = false
                cache.putAll(result.translations)
                LyricTranslationCache.save(applicationContext, translationCache)
                if (lastSong?.uri?.toString() == uriKey) {
                    lyricAdapter.setTranslations(translationCache[uriKey] ?: emptyMap())
                    playbackService?.reloadLyricTranslations()
                }
                val failed = toTranslate.size - result.translations.size
                PlaybackLog.log(
                    "asr translate done: ${result.translations.size}/${toTranslate.size} err=${result.error}"
                )
                when {
                    result.translations.isEmpty() && result.error != null ->
                        toast(getString(R.string.asr_failed, "翻译失败：${result.error}"))
                    result.translations.isEmpty() -> toast(getString(R.string.trans_all_fail))
                    failed > 0 -> toast(getString(R.string.asr_translated_partial, failed))
                    else -> toast(getString(R.string.asr_translated_done))
                }
            }
        }.start()
    }

    /**
     * 只重扫识别歌曲所在的文件夹并局部合并进曲库（2.0）。
     * 比整库重扫快得多；树权限匹配不上时回退整库重扫。
     */
    private fun rescanFolderForAsr(audioUri: Uri) {
        val docId = try {
            DocumentsContract.getDocumentId(audioUri)
        } catch (e: Exception) {
            null
        }
        val tree = if (docId == null) null else savedTreeUris().firstOrNull { t ->
            val td = try {
                DocumentsContract.getTreeDocumentId(t)
            } catch (e: Exception) {
                null
            }
            td != null && (docId == td || docId.startsWith("$td/"))
        }
        if (docId == null || tree == null) {
            scanLibrary(silent = true)
            return
        }
        val treeDocId = DocumentsContract.getTreeDocumentId(tree)
        val parentDocId = if (docId.contains('/')) docId.substringBeforeLast('/') else treeDocId
        Thread {
            try {
                val prefix = "$parentDocId/"
                // 复用现有曲库里这个文件夹的歌单名（含多根前缀，保证归类一致）
                val folderName = library?.allSongs?.firstOrNull { s ->
                    try {
                        DocumentsContract.getDocumentId(s.uri).startsWith(prefix)
                    } catch (e: Exception) {
                        false
                    }
                }?.folder ?: parentDocId.substringAfterLast('/')
                val (newSongs, newLyrics) = LibraryScanner(this, contentResolver)
                    .scanFolder(tree, parentDocId, folderName)
                runOnUiThread {
                    mergeScannedFolder(parentDocId, folderName, newSongs, newLyrics)
                }
            } catch (e: Exception) {
                PlaybackLog.log("asr folder rescan THREW: ${e.message}")
                runOnUiThread { scanLibrary(silent = true) }
            }
        }.start()
    }

    /** 把单文件夹扫描结果合并进曲库并全面刷新界面与播放服务。 */
    private fun mergeScannedFolder(
        parentDocId: String,
        folderName: String,
        newSongs: List<Song>,
        newLyrics: Map<String, LyricRef>
    ) {
        val lib = library
        if (lib == null) {
            scanLibrary(silent = true)
            return
        }
        val prefix = "$parentDocId/"
        fun inFolder(s: Song): Boolean = try {
            DocumentsContract.getDocumentId(s.uri).startsWith(prefix)
        } catch (e: Exception) {
            false
        }
        val kept = lib.allSongs.filterNot { inFolder(it) }
        val all = (kept + newSongs).sortedBy { it.title }
        // 歌词 key 按「完整文件夹路径」精确清除（不能前缀匹配，防误删子文件夹歌词）
        val lyrics = lib.lyrics
            .filterKeys { k -> k.substringBeforeLast('/') != folderName }
            .toMutableMap()
        newLyrics.forEach { (k, v) -> lyrics[k] = v }
        val playlists = all.groupBy { it.folder }
            .map { Playlist(it.key, it.value.sortedBy { s -> s.title }) }
            .sortedBy { it.name }
        val newLib = MusicLibrary(playlists, all, lyrics)
        LibraryCache.save(applicationContext, newLib)
        library = newLib
        onLibraryReady()
        refreshOpenViews()
        // 新 lrc 已在歌词映射里：当前歌无需切歌立即显示
        playbackService?.refreshLyricMap(newLib.lyrics)
        val spd = playbackService?.currentSpeed() ?: 1f
        btnSpeed.text = if (spd == 1f) "1x" else "${spd}x"
    }

    private fun showPlaybackLogDialog() {
        val text = PlaybackLog.dump(this)
        PlaybackLog.persist(this)
        val scroll = android.widget.ScrollView(this)
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 11f
        tv.typeface = Typeface.MONOSPACE
        val d = resources.displayMetrics.density
        tv.setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
        scroll.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("运行日志")
            .setView(scroll)
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("shiyin_log", text))
                toast(getString(R.string.playback_log_copied))
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ---------- 快进退 / 倍速（1.25）----------

    private fun seekStepSeconds(): Int = prefs.getInt(KEY_SEEK_STEP, 10)

    /** 相对当前进度快进/快退 N 秒（播放页按钮）。 */
    private fun seekRelative(deltaMs: Int) {
        val svc = playbackService ?: return
        val pos = svc.currentPosition()
        val dur = svc.currentDuration()
        svc.seekTo((pos + deltaMs).coerceIn(0, if (dur > 0) dur else Int.MAX_VALUE))
    }

    /** 倍速循环：1x → 1.5x → 2x → 3x → 4x → 1x。 */
    private fun cycleSpeed() {
        val cur = playbackService?.currentSpeed() ?: 1f
        val next = SPEED_LIST.firstOrNull { it > cur + 0.01f } ?: SPEED_LIST[0]
        playbackService?.setSpeed(next)
        btnSpeed.text = if (next == 1f) "1x" else "${next}x"
    }

    /** 刷新快进退按钮文本（跟随设置里的秒数）。 */
    private fun updateSeekButtons() {
        val s = seekStepSeconds()
        btnSeekBack.text = "-${s}s"
        btnSeekForward.text = "+${s}s"
        btnSpeed.text = "1x"
    }

    // ---------- 树形目录：逐级进入（1.27）----------

    private fun isTreeMode(): Boolean = prefs.getString(KEY_LIB_LAYOUT, "grid") == "tree"

    /** 点击目录：进入下一级。 */
    private fun openTreeFolder(node: TreeNode) {
        treeStack.add(node)
        refreshTree()
    }

    /**
     * 面包屑/返回键：回到上一级。
     *
     * 注意：这里**不能**写 treeStack.removeLast()。本项目 compileSdk 36，而 Java 21 给
     * java.util.List 加了默认方法 removeLast()（SequencedCollection），Kotlin 解析时
     * 「成员优先于扩展」，会编译成 invokeinterface java.util.List.removeLast —— 在
     * Android 14 及以下（没有该方法）直接 NoSuchMethodError 闪退，Android 15+ 正常，
     * 与「部分机型点面包屑返回上一级闪退」的反馈吻合。改用 removeAt，全版本安全。
     */
    private fun backTree() {
        if (treeStack.isNotEmpty()) treeStack.removeAt(treeStack.size - 1)
        refreshTree()
    }

    /** 渲染当前层节点 + 面包屑路径。 */
    private fun refreshTree() {
        // 进入某文件夹时，如果它自己也有歌（即同时有音声和子文件夹），
        // 把「本文件夹的歌曲」作为第一行再列子文件夹 —— 以前单击进来只列子文件夹、
        // 这层的音声看不到，只能长按目录才能打开（用户反馈：点进去只见子文件夹）。
        val nodes: List<TreeNode> = if (treeStack.isEmpty()) {
            treeRoots
        } else {
            val cur = treeStack.last()
            val own = cur.playlist?.let { pl ->
                TreeNode(
                    name = getString(R.string.folder_own_songs),
                    path = cur.path,
                    depth = cur.depth + 1,
                    playlist = pl
                )
            }
            if (own != null) listOf(own) + cur.children else cur.children
        }
        treeAdapter.submit(nodes)
        txtTreePath.text = if (treeStack.isEmpty()) {
            getString(R.string.root_dir)
        } else {
            "‹ " + treeStack.joinToString(" > ") { it.name }
        }
        txtTreePath.visibility = if (isTreeMode()) View.VISIBLE else View.GONE
    }

    /** 音乐库歌单展示布局：网格大图标（默认）/ 树形目录，切换时重挂 adapter 并刷新数据。 */
    private fun applyLibLayout() {
        val mode = prefs.getString(KEY_LIB_LAYOUT, "grid")
        val list = playlistList()
        if (mode == "tree") {
            // 直接设置，不做 is 判断——GridLayoutManager 继承 LinearLayoutManager，is 判断有陷阱
            recyclerPlaylists.layoutManager = LinearLayoutManager(this)
            if (recyclerPlaylists.adapter !== treeAdapter) {
                recyclerPlaylists.adapter = treeAdapter
            }
            treeRoots = LibraryTree.build(list)
            treeStack.clear()
            refreshTree()
        } else {
            recyclerPlaylists.layoutManager = GridLayoutManager(this, 2)
            if (recyclerPlaylists.adapter !== gridAdapter) {
                recyclerPlaylists.adapter = gridAdapter
            }
            gridAdapter.submit(list)
        }
        txtTreePath.visibility = if (mode == "tree") View.VISIBLE else View.GONE
    }

    /** 封面/主题变更后刷新两种布局的列表（当前可见的立即生效）。 */
    private fun refreshLibGrid() {
        gridAdapter.notifyDataSetChanged()
        treeAdapter.notifyDataSetChanged()
    }

    private fun applyAppearance() {
        val idleColor = prefs.getInt(KEY_LYRIC_IDLE_COLOR, IDLE_DEFAULT)
        val curColor = prefs.getInt(KEY_LYRIC_CUR_COLOR, CUR_DEFAULT)
        // 封面背景开启时：非当前行/译文行都要提亮（浅色主题的 text_normal/text_hint
        // 压在 70% 黑遮罩上会糊掉，2.12.1）。用户显式自定义过的颜色尊重用户选择。
        val onCover = coverBgEnabled() && currentCoverBmp != null
        val idleResolved = when {
            idleColor != IDLE_DEFAULT -> idleColor
            onCover -> getColor(R.color.cover_lyric_idle_on_photo)
            else -> -1
        }
        lyricAdapter.applyStyle(
            prefs.getInt(KEY_LYRIC_SIZE, 18),
            prefs.getInt(KEY_LYRIC_FONT, 0),
            idleResolved,
            if (curColor == CUR_DEFAULT) -1 else curColor,
            if (onCover) getColor(R.color.cover_text_hint_on_photo) else -1
        )
        val uiSize = prefs.getInt(KEY_UI_SIZE, 15)
        songAdapter.applyUiSize(uiSize)
        searchAdapter.applyUiSize(uiSize)
        gridAdapter.applyUiSize(uiSize)
        treeAdapter.applyUiSize(uiSize)
        discoverAdapter.applyUiSize(uiSize)
        // 2.14：新建的「歌单」页也用同一个字号设置（网格样式与库页一致）
        if (::playlistsPageAdapter.isInitialized) playlistsPageAdapter.applyUiSize(uiSize)
    }

    private fun applyDarkMode(dark: Boolean) {
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    // ---------- UI 更新 ----------

    private fun updatePlayButtons(playing: Boolean) {
        btnPlayPlayer.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
        btnMiniPlay.text = getString(if (playing) R.string.pause else R.string.play)
        btnMiniPlay.setCompoundDrawablesWithIntrinsicBounds(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play,
            0, 0, 0
        )
    }

    private fun updateTime(pos: Int) {
        txtTime.text = String.format(
            Locale.getDefault(), "%s / %s",
            formatTime(pos),
            if (durationMs > 0) formatTime(durationMs) else "--:--"
        )
    }

    // ---------- 权限 ----------

    private fun persistRead(uri: Uri) {
        // 读、写分开 take：某些文件提供方只授予读权限，一次同时请求读写会
        // 因写权限未授予而整体抛 SecurityException——连读权限都保不住（1.33 的老坑）。
        // 分开 try 就能「有多少拿多少」；2.1 起选文件夹时已同时申请写权限。
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // 忽略：个别文件提供方不支持持久化权限
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // 未授予写权限：识别歌词会走应用内兜底，重选一次文件夹即可
        }
    }

    private fun hasPersistRead(uri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    companion object {
        private const val KEY_TREE = "tree_uri"
        private const val KEY_TREES = "tree_uris"
        private const val KEY_AUTO_SCAN = "auto_scan"
        private const val KEY_IMMERSION_SECONDS = "immersion_seconds"
        private const val KEY_IMMERSION_ENABLED = "immersion_enabled"
        private const val MODULE_DISCOVER = "discover"
        private const val MODULE_LIBRARY = "library"
        private const val MODULE_ARTISTS = "artists"
        private const val MODULE_FAVORITES = "favorites"
        private const val MODULE_VIDEO = "video"
        private const val KEY_NAV_TABS = "nav_tabs"
        private const val KEY_NAV_DEFAULT = "nav_default"
        private const val KEY_NAV_MIGRATED_20 = "nav_migrated_2_0"
        private val ALL_MODULES =
            listOf(MODULE_DISCOVER, MODULE_LIBRARY, MODULE_ARTISTS, MODULE_FAVORITES, MODULE_VIDEO)
        private const val DEFAULT_NAV = "discover,library,artists,favorites,video"
        private const val KEY_DARK = "dark_mode"
        /** 2.12.1：歌词页/播放页用当前封面作背景（设置-主题 开关）。 */
        private const val KEY_COVER_BG = "cover_background"
        /** 封面背景加载分辨率下限/上限：太低铺满会糊，太高白占内存。 */
        private const val COVER_LOAD_MIN = 800
        private const val COVER_LOAD_MAX = 1080
        private const val KEY_LYRIC_SIZE = "lyric_size"
        private const val KEY_UI_SIZE = "ui_size"
        private const val KEY_LYRIC_FONT = "lyric_font"
        private const val KEY_TRANS_BASE = "trans_base"
        private const val KEY_TRANS_KEY = "trans_key"
        private const val KEY_TRANS_MODEL = "trans_model"
        private const val KEY_AUTO_TRANS = "auto_translate"
        /**
         * 单行翻译失败的最大重试次数（2.13 熔断）。
         * 超过后该行不再重发，避免永久失败行把费用放大几十倍。
         */
        private const val MAX_LINE_RETRY = 2
        /** 歌单排序方式（2.13）。 */
        private const val KEY_PLAYLIST_SORT = "playlist_sort"
        private const val SORT_DEFAULT = "default"
        private const val SORT_NAME = "name"
        private const val SORT_RECENT = "recent"
        private const val SORT_RANDOM = "random"
        /**
         * 切歌后「新首帧」的等待宽限期（2.13）。
         * 旧播放器的余帧会在这个时间内继续触发 onSurfaceTextureUpdated，
         * 期间到达的帧不算新视频的首帧。
         */
        private const val FRAME_GRACE_MS = 120L
        private const val KEY_TITLE_FROM_FILENAME = "title_from_filename"
        private const val KEY_DESKTOP_ON = "desktop_lyrics_on"
        private const val KEY_DESKTOP_SIZE = "desktop_lyrics_size"
        private const val KEY_DESKTOP_ALPHA = "desktop_lyrics_alpha"
        private const val KEY_DESKTOP_LOCKED = "desktop_lyrics_locked"
        private const val KEY_DESKTOP_CENTER = "desktop_lyrics_center"
        private const val KEY_MIX_AUDIO = "mix_audio"
        private const val KEY_LIB_LAYOUT = "lib_layout"
        private const val KEY_SEEK_STEP = "seek_step"
        private const val KEY_LYRIC_IDLE_COLOR = "lyric_idle_color"
        private const val IDLE_DEFAULT = 0 // -1 会与"未设"冲突，用 0 表示默认 text_normal
        // 1.30：新增主题靛蓝（八种主题色的第一种），共 9 色，每行 3 个正好三行
        private val LYRIC_IDLE_COLORS = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF9CA3AF.toInt(),
            0xFFF59E0B.toInt(), 0xFFE0245E.toInt(), 0xFF06B6D4.toInt(),
            0xFF8B5CF6.toInt(), 0xFF4ADE80.toInt(), 0xFF4A6CF7.toInt()
        )
        private const val KEY_LYRIC_CUR_COLOR = "lyric_cur_color"
        private const val CUR_DEFAULT = 0 // 0 = 未自定义，跟随主题色
        private const val KEY_SEEKBAR_COLOR = "seekbar_color"
        private const val SB_DEFAULT = 0 // 0 = 未自定义，跟随主题色
        // 1.30：加宽 12 色板（非当前歌词 9 色 + 蓝/绿/粉三个主题色），播放中歌词与进度条共用
        private val PALETTE_12 = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF9CA3AF.toInt(),
            0xFFF59E0B.toInt(), 0xFFE0245E.toInt(), 0xFF06B6D4.toInt(),
            0xFF8B5CF6.toInt(), 0xFF4ADE80.toInt(), 0xFF4A6CF7.toInt(),
            0xFF1976D2.toInt(), 0xFF0E9F6E.toInt(), 0xFFEC4899.toInt()
        )
        private const val KEY_DESKTOP_COLOR = "desktop_lyrics_color"
        private const val DESKTOP_COLOR_DEFAULT = -1 // 白色
        private val SPEED_LIST = floatArrayOf(1f, 1.5f, 2f, 3f, 4f)
        private const val KEY_ALARM_ON = "alarm_play_on"
        private const val KEY_ALARM_HOUR = "alarm_play_hour"
        private const val KEY_ALARM_MINUTE = "alarm_play_minute"
        private const val SUPPORT_URL = "https://www.ifdian.net/a/ruozhi521"
    }

    /** 选择整个文件夹并请求可持久化读+写权限。 */
    private class OpenTreePersistable : ActivityResultContract<Void?, Uri?>() {
        override fun createIntent(context: Context, input: Void?): Intent {
            // 【2.1 关键修复】原来只带 FLAG_GRANT_READ_URI_PERMISSION：
            // 系统就不会授予写权限 → 识别歌词写同目录 .lrc 必然 Permission Denial
            // → 100% 落进「应用内兜底」（所有机型、所有用户都一样，重选文件夹也没用）。
            // 必须把写权限一起申请，SAF 才能在同目录 createDocument 出 .lrc。
            return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
            return if (resultCode == Activity.RESULT_OK) intent?.data else null
        }
    }
}