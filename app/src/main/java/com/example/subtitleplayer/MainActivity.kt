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
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import android.widget.Toast
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

    private enum class Page { DISCOVER, LIBRARY, PLAYLIST, SEARCH, PLAYER, LYRICS, FAVORITES, VIDEO }

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
    private lateinit var gridAdapter: PlaylistGridAdapter
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

    // ---- 视频页 ----
    private lateinit var viewVideo: View
    private lateinit var videoSurface: android.view.TextureView
    private lateinit var txtVideoHint: android.widget.TextView
    private lateinit var seekVideo: SeekBar
    private lateinit var txtVideoTime: android.widget.TextView
    private var videoSurfaceAttached = false
    private var videoSpeedUp = false
    private var videoDownX = 0f
    private var videoW = 0
    private var videoH = 0
    private val videoLongPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var cdAnimator: ObjectAnimator? = null

    // ---- 全屏歌词页 ----
    private lateinit var recyclerLyricFull: RecyclerView
    private lateinit var btnTranslate: Button
    private var lastSong: Song? = null
    private var translating = false
    private var transFailedLines: List<Pair<Int, String>> = emptyList()
    private val translationCache by lazy {
        LyricTranslationCache.load(this)
    }

    private lateinit var songAdapter: SongAdapter
    private lateinit var lyricAdapter: LyricAdapter

    private val prefs by lazy { getSharedPreferences("player", Context.MODE_PRIVATE) }

    private var library: MusicLibrary? = null
    private var scanning = false

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
            // 播放页/歌词页显示时不拉起底部迷你条（避免双进度条），换歌也不复现
            if (song != null && page != Page.PLAYER && page != Page.LYRICS) {
                if (miniPlayer.visibility != View.VISIBLE) {
                    miniPlayer.visibility = View.VISIBLE
                    miniPlayer.alpha = 0f
                    miniPlayer.translationY = 40f
                    miniPlayer.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(250)
                        .start()
                }
            } else if (song == null) {
                miniPlayer.visibility = View.GONE
            }
            lyricLines = lines
            currentLyricHighlight = -1
            lyricAdapter.submit(lines)
            updateNowLyric(-1)
            lastSong = song
            transFailedLines = emptyList()
            translating = false
            btnTranslate.isEnabled = true
            btnTranslate.text = getString(R.string.translate)
            val cachedTrans = song?.let { translationCache[it.uri.toString()] } ?: emptyMap()
            lyricAdapter.setTranslations(cachedTrans)
            maybeAutoTranslate(song, lines)
            imgCd.setImageResource(R.drawable.ic_music_tinted)
            currentCoverKey = song?.uri?.toString()
            if (song != null) {
                CoverLoader.load(this@MainActivity, song.uri, 400) { bmp ->
                    if (bmp != null && song.uri.toString() == currentCoverKey) {
                        imgCd.setImageBitmap(bmp)
                        applyPlayerBackground(bmp)
                    }
                }
            } else {
                applyPlayerBackground(null)
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
                txtVideoTime.text = formatTime(position) + " / " + formatTime(duration)
            }
            if (!seekBar.isPressed) {
                seekBar.progress = position
            }
            if (!miniSeekBar.isPressed) {
                miniSeekBar.progress = position
            }
            updateTime(position)
            updateNowLyric(lyricIndex)
            if (lyricIndex != currentLyricHighlight) {
                currentLyricHighlight = lyricIndex
                lyricAdapter.setCurrent(lyricIndex)
                scrollToLyric(lyricIndex)
            }
        }

        override fun onPlayStateChanged(playing: Boolean) {
            updatePlayButtons(playing)
            updateCdAnimation(playing)
            if (page == Page.VIDEO) {
                txtVideoHint.visibility = if (playing) View.GONE else View.VISIBLE
            }
        }
    }

    private val treePicker =
        registerForActivityResult(OpenTreePersistable()) { uri ->
            uri ?: return@registerForActivityResult
            persistRead(uri)
            prefs.edit().putString(KEY_TREE, uri.toString()).apply()
            scanLibrary(uri)
        }

    /** 自定义封面选图（复制到内部存储，无需持久授权）。 */
    private var pendingCoverTarget: String? = null
    /** 批量封面模式：非空时 coverPicker 回调对这批 uri 批量写单曲封面。 */
    private var pendingBatchSongs: List<String>? = null
    private val coverPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = pendingCoverTarget
            pendingCoverTarget = null
            val batch = pendingBatchSongs
            pendingBatchSongs = null
            if (uri == null || (target == null && batch == null)) {
                return@registerForActivityResult
            }
            // 批量模式：对勾选歌曲逐一写单曲封面
            if (target == null) return@registerForActivityResult
            if (batch != null) {
                var ok = 0
                batch.forEach { su ->
                    if (CoverManager.setSongCover(this, su, uri) != null) {
                        ok++
                        CoverLoader.invalidate(su)
                    }
                }
                if (ok > 0) {
                    toast(getString(R.string.batch_cover_done, ok))
                    gridAdapter.notifyDataSetChanged()
                    refreshCdCover()
                } else {
                    toast(getString(R.string.cover_failed))
                }
                return@registerForActivityResult
            }
            val ok = if (target.startsWith("pl:")) {
                CoverManager.setPlaylistCover(this, target.removePrefix("pl:"), uri) != null
            } else {
                CoverManager.setSongCover(this, target.removePrefix("song:"), uri) != null
            }
            if (ok) {
                toast(getString(R.string.cover_saved))
                gridAdapter.notifyDataSetChanged()
                refreshCdCover()
            } else {
                toast(getString(R.string.cover_failed))
            }
        }

    /** 背景图选择（复制到内部存储）。 */
    private val bgPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            if (BgManager.setBg(this, uri)) {
                applyPageBackground()
                toast(getString(R.string.bg_saved))
            } else {
                toast(getString(R.string.bg_failed))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 在 Activity 创建前应用保存的深色模式（进程级设置，不调用会回系统默认导致深色失效）
        applyDarkMode(prefs.getBoolean(KEY_DARK, false))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewDiscover = findViewById(R.id.pageDiscover)
        viewLibrary = findViewById(R.id.pageLibrary)
        viewPlaylist = findViewById(R.id.pagePlaylist)
        viewSearch = findViewById(R.id.pageSearch)
        viewPlayer = findViewById(R.id.pagePlayer)
        viewLyrics = findViewById(R.id.pageLyrics)
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

        viewVideo = findViewById(R.id.pageVideo)
        videoSurface = findViewById(R.id.videoSurface)
        txtVideoHint = findViewById(R.id.txtVideoHint)
        seekVideo = findViewById(R.id.seekVideo)
        txtVideoTime = findViewById(R.id.txtVideoTime)
        findViewById<Button>(R.id.btnVideoBack).setOnClickListener { closeVideoPage() }
        findViewById<ImageButton>(R.id.btnVideo).setOnClickListener { openVideoPage() }
        videoSurface.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                st: android.graphics.SurfaceTexture, width: Int, height: Int
            ) {
                playbackService?.attachVideoSurface(android.view.Surface(st))
                videoSurfaceAttached = true
                fitVideoSurface(width, height)
            }

            override fun onSurfaceTextureSizeChanged(
                st: android.graphics.SurfaceTexture, width: Int, height: Int
            ) {
            }

            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                videoSurfaceAttached = false
                // surface 销毁时立即脱离画面，避免 MediaPlayer 持续向已销毁 surface 输出（跳歌/闪退）
                playbackService?.attachVideoSurface(null)
                return true
            }

            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {
            }
        }
        videoSurface.setOnTouchListener { v, ev -> handleVideoTouch(v, ev) }
        seekVideo.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                sb: android.widget.SeekBar, progress: Int, fromUser: Boolean
            ) {
            }

            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {
            }

            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                playbackService?.seekTo(sb.progress)
            }
        })
        (viewPlayer as SwipeFrameLayout).onHorizontalSwipe = { dir, downY -> handleSwipe(dir, downY) }
        (viewLyrics as SwipeFrameLayout).onHorizontalSwipe = { dir, downY -> handleSwipe(dir, downY) }

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

        // ---- 发现页 ----
        discoverAdapter = DiscoverAdapter { pos ->
            if (discoverSongs.isNotEmpty() && pos in discoverSongs.indices) {
                playSong(discoverSongs, pos)
            }
        }
        recyclerDiscover.layoutManager = GridLayoutManager(this, 2)
        recyclerDiscover.adapter = discoverAdapter
        findViewById<Button>(R.id.btnRefreshDiscover).setOnClickListener { loadDiscover() }

        // ---- 音乐库页：歌单网格 ----
        gridAdapter = PlaylistGridAdapter(
            { pos -> playlistList().getOrNull(pos)?.let { openPlaylist(it) } },
            { pos -> playlistList().getOrNull(pos)?.let { showPlaylistCoverMenu(it.name) } }
        )
        recyclerPlaylists.layoutManager = GridLayoutManager(this, 2)
        recyclerPlaylists.adapter = gridAdapter

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
            hasLyric = { s -> library?.lyrics?.containsKey(s.uri.toString()) == true },
            onClick = { pos -> playSong(favoriteSongs, pos) },
            onLongClick = { showSongMenu(it) }
        )
        recyclerFavorites.layoutManager = LinearLayoutManager(this)
        recyclerFavorites.adapter = favoritesAdapter

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
        findViewById<Button>(R.id.btnBackLyrics).setOnClickListener { showPage(Page.PLAYER, -1) }
        findViewById<Button>(R.id.btnTranslate).setOnClickListener {
            translateCurrentLyric()
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
                    playbackService?.seekTo(progress)
                    updateTime(progress)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}

            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        miniSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    playbackService?.seekTo(progress)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}

            override fun onStopTrackingTouch(sb: SeekBar) {}
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

        // 恢复上次选择的文件夹
        val saved = prefs.getString(KEY_TREE, null)
        if (saved != null) {
            val uri = Uri.parse(saved)
            if (hasPersistRead(uri)) {
                if (prefs.getBoolean(KEY_AUTO_SCAN, false)) {
                    scanLibrary(uri)
                } else {
                    loadCachedLibrary()
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
            MODULE_FAVORITES -> openFavorites()
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

    private fun navModules(): List<String> =
        (prefs.getString(KEY_NAV_TABS, DEFAULT_NAV)?.split(",") ?: emptyList())
            .filter { it in ALL_MODULES }.ifEmpty { ALL_MODULES }

    private fun defaultModule(): String {
        val d = prefs.getString(KEY_NAV_DEFAULT, null)
        val list = navModules()
        return if (d != null && list.contains(d)) d else list.first()
    }

    private fun navLabel(module: String): Int = when (module) {
        MODULE_DISCOVER -> R.string.tab_discover
        MODULE_LIBRARY -> R.string.tab_library
        MODULE_ARTISTS -> R.string.nav_artists
        else -> R.string.nav_favorites
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
        seekBar.progressTintList = list
        seekBar.thumbTintList = list
        miniSeekBar.progressTintList = list
        miniSeekBar.thumbTintList = list
        // 播放键图标保持白色（背景由全局遍历 tint，图标 tint 会与背景同色消失）
        // CD 圆形底（bg_play_circle）与全局按钮跟随主题色
        imgCd.backgroundTintList = list
        txtNowLyric.setTextColor(a)
        tintAccentViews(findViewById<View>(android.R.id.content), list)
        selectModule(currentModule)
        songAdapter.notifyDataSetChanged()
        lyricAdapter.notifyDataSetChanged()
        discoverAdapter.notifyDataSetChanged()
        gridAdapter.notifyDataSetChanged()
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

    /** 主题色选择弹窗：预设色板。 */
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

    /** 应用背景图到播放页/歌词页。 */
    private fun applyPageBackground() {
        val uri = BgManager.bgUri(this)
        BgManager.apply(viewPlayer, uri)
        BgManager.apply(viewLyrics, uri)
    }

    /** 背景图设置弹窗。 */
    private fun showBgDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.bg_title)
            .setItems(
                arrayOf(getString(R.string.bg_set), getString(R.string.bg_clear))
            ) { _, which ->
                when (which) {
                    0 -> bgPicker.launch(arrayOf("image/*"))
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
        // 播放页/歌词页不显示底部迷你条，避免双进度条
        if (p == Page.PLAYER || p == Page.LYRICS || p == Page.VIDEO) {
            if (miniPlayer.visibility != View.GONE) {
                miniPlayer.visibility = View.GONE
            }
        } else if (hasSong && miniPlayer.visibility != View.VISIBLE) {
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

    override fun onBackPressed() {
        when (page) {
            Page.LYRICS -> showPage(Page.PLAYER, -1)
            Page.PLAYER -> backFromPlayer()
            Page.VIDEO -> closeVideoPage()
            Page.PLAYLIST, Page.SEARCH, Page.FAVORITES -> showPage(Page.LIBRARY)
            else -> super.onBackPressed()
        }
    }

    // ---------- 扫描与数据 ----------

    private var currentTreeUri: Uri? = null

    private fun treeUri(): Uri? = currentTreeUri ?: prefs.getString(KEY_TREE, null)?.let { Uri.parse(it) }

    private fun scanLibrary(uri: Uri) {
        if (scanning) return
        scanning = true
        toast(getString(R.string.scanning))
        Thread {
            val lib = try {
                LibraryScanner(this, contentResolver).scan(uri)
            } catch (e: Exception) {
                null
            }
            if (lib != null && lib.allSongs.isNotEmpty()) {
                LibraryCache.save(applicationContext, lib)
            }
            runOnUiThread {
                scanning = false
                when {
                    lib == null -> toast(getString(R.string.choose_folder_again))
                    lib.allSongs.isEmpty() -> toast(getString(R.string.no_audio))
                    else -> {
                        library = lib
                        toast(
                            getString(
                                R.string.loaded_summary,
                                lib.allSongs.size,
                                lib.playlists.size
                            )
                        )
                        onLibraryReady()
                    }
                }
            }
        }.start()
    }

    private fun onLibraryReady() {
        gridAdapter.submit(playlistList())
        loadDiscover()
        artistLoaded = false
        artistGroups = emptyList()
        artistAdapter.submit(emptyList())
        if (page == Page.LIBRARY && !segArtistsShown()) {
            // 保持当前分段
        }
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
        return lists
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
        if (!songs) loadArtistsIfNeeded()
    }

    private fun loadArtistsIfNeeded() {
        if (artistLoaded || artistLoading) return
        val lib = library ?: return
        artistLoading = true
        toast(getString(R.string.loading_artists))
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
        txtPlaylistTitle.text = playlist.name
        dragEnabled = true
        currentSongs = applyPlaylistOrder(playlist.songs, playlist.name)
        songAdapter.submit(currentSongs)
        showPage(Page.PLAYLIST)
    }

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
                    songAdapter.move(from, to)
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
        prefs.edit()
            .putString("playlist_order_$name", uris.joinToString("\n"))
            .apply()
    }

    /** 有自定义顺序则按顺序重排，否则原样返回。 */
    private fun applyPlaylistOrder(songs: List<Song>, name: String): List<Song> {
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
        showPage(Page.PLAYER)
    }

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
        val idx = currentLyricHighlight.coerceIn(0, lyricLines.size - 1)
        val parts = mutableListOf<String>()
        for (i in idx - 1..idx + 1) {
            if (i in lyricLines.indices) {
                parts.add(lyricLines[i].text)
            }
        }
        txtImmersiveLyric.text = parts.joinToString("\n")
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
        val rgLyric = view.findViewById<RadioGroup>(R.id.rgLyricSize)
        val rgUi = view.findViewById<RadioGroup>(R.id.rgUiSize)
        val rgFont = view.findViewById<RadioGroup>(R.id.rgFont)

        chkAutoScan.isChecked = prefs.getBoolean(KEY_AUTO_SCAN, false)
        chkAutoTrans.isChecked = prefs.getBoolean(KEY_AUTO_TRANS, false)
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
        val chkMixAudio = view.findViewById<CheckBox>(R.id.chkMixAudio)
        chkMixAudio.isChecked = prefs.getBoolean(KEY_MIX_AUDIO, false)
        val chkAlarmPlay = view.findViewById<CheckBox>(R.id.chkAlarmPlay)
        chkAlarmPlay.isChecked = prefs.getBoolean(KEY_ALARM_ON, false)
        chkAlarmPlay.setOnClickListener { showAlarmPicker(chkAlarmPlay) }
        chkDesktopLyrics.isChecked = prefs.getBoolean(KEY_DESKTOP_ON, false)
        lyricsGroup.visibility =
            if (chkDesktopLyrics.isChecked) View.VISIBLE else View.GONE
        checkByTag(rgDesktopSize, prefs.getInt(KEY_DESKTOP_SIZE, 1))
        checkByTag(rgDesktopAlpha, prefs.getInt(KEY_DESKTOP_ALPHA, 1))
        chkLyricsLocked.isChecked = prefs.getBoolean(KEY_DESKTOP_LOCKED, false)
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

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                prefs.edit()
                    .putBoolean(KEY_AUTO_SCAN, chkAutoScan.isChecked)
                    .putBoolean(KEY_AUTO_TRANS, chkAutoTrans.isChecked)
                    .putInt(KEY_LYRIC_SIZE, tagOf(rgLyric))
                    .putInt(KEY_UI_SIZE, tagOf(rgUi))
                    .putInt(KEY_LYRIC_FONT, tagOf(rgFont))
                    .putInt(KEY_DESKTOP_SIZE, tagOf(rgDesktopSize))
                    .putInt(KEY_DESKTOP_ALPHA, tagOf(rgDesktopAlpha))
                    .putBoolean(KEY_DESKTOP_LOCKED, chkLyricsLocked.isChecked)
                    .putBoolean(KEY_MIX_AUDIO, chkMixAudio.isChecked)
                    .apply()
                applyAppearance()
                playbackService?.refreshDesktopLyricsStyle()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()

        view.findViewById<Button>(R.id.btnRescanNow).setOnClickListener {
            dialog.dismiss()
            val uri = treeUri()
            if (uri == null) {
                treePicker.launch(null)
            } else {
                scanLibrary(uri)
            }
        }
        view.findViewById<Button>(R.id.btnChangeFolder).setOnClickListener {
            dialog.dismiss()
            treePicker.launch(null)
        }
        view.findViewById<Button>(R.id.btnAbout).setOnClickListener {
            dialog.dismiss()
            showAboutDialog()
        }
        view.findViewById<Button>(R.id.btnTransSettings).setOnClickListener {
            dialog.dismiss()
            showTransSettingsDialog()
        }
        view.findViewById<Button>(R.id.btnEqSettings).setOnClickListener {
            dialog.dismiss()
            showEqualizerDialog()
        }
        view.findViewById<Button>(R.id.btnTheme).setOnClickListener {
            dialog.dismiss()
            showThemeDialog()
        }
    }

    /** 主题设置弹窗：主题色 + 背景图 + 深色模式三合一。 */
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

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
            hasLyric = { s -> library?.lyrics?.containsKey(s.uri.toString()) == true },
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
                        coverPicker.launch(arrayOf("image/*"))
                    }
                    1 -> {
                        CoverManager.clearPlaylistCover(this, name)
                        CoverLoader.invalidate("pl:$name")
                        gridAdapter.notifyDataSetChanged()
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
                    .map { it.uri.toString() }
                if (checked.isEmpty()) {
                    toast(getString(R.string.batch_cover_empty))
                } else {
                    pendingBatchSongs = checked
                    coverPicker.launch(arrayOf("image/*"))
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSongCoverMenu(song: Song) {
        AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(
                arrayOf(getString(R.string.cover_set), getString(R.string.cover_clear))
            ) { _, which ->
                when (which) {
                    0 -> {
                        pendingCoverTarget = "song:${song.uri}"
                        coverPicker.launch(arrayOf("image/*"))
                    }
                    1 -> {
                        CoverManager.clearSongCover(this, song.uri.toString())
                        CoverLoader.invalidate(song.uri.toString())
                        refreshCdCover()
                        gridAdapter.notifyDataSetChanged()
                        toast(getString(R.string.cover_cleared))
                    }
                }
            }
            .show()
    }

    /** 刷新播放页 CD 封面（设置/清除单曲封面后）。 */
    /** 歌曲长按统一菜单：收藏 + 封面设置。 */
    private fun showSongMenu(song: Song) {
        val fav = FavoritesManager.isFavorite(this, song.uri.toString())
        AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(
                arrayOf(
                    getString(if (fav) R.string.unfavorite else R.string.favorite),
                    getString(R.string.cover_set),
                    getString(R.string.cover_clear)
                )
            ) { _, which ->
                when (which) {
                    0 -> {
                        val on = FavoritesManager.toggle(this, song.uri.toString())
                        toast(getString(if (on) R.string.favorited else R.string.unfavorited))
                        updateFavoriteButton(song)
                    }
                    1 -> {
                        pendingCoverTarget = "song:${song.uri}"
                        coverPicker.launch(arrayOf("image/*"))
                    }
                    2 -> {
                        CoverManager.clearSongCover(this, song.uri.toString())
                        CoverLoader.invalidate(song.uri.toString())
                        refreshCdCover()
                        gridAdapter.notifyDataSetChanged()
                        toast(getString(R.string.cover_cleared))
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

    // ---------- 视频播放页 ----------
    private fun isVideoFile(uri: android.net.Uri?): Boolean {
        val p = uri?.lastPathSegment?.lowercase(Locale.getDefault()) ?: return false
        return p.endsWith(".mp4") || p.endsWith(".m4v")
    }

    /** 打开视频页：方向跟随视频比例（横屏视频横屏、竖屏视频竖屏），绑定画面。 */
    private fun openVideoPage() {
        val song = playbackService?.currentSongSafe() ?: return
        videoW = 0
        videoH = 0
        try {
            val r = android.media.MediaMetadataRetriever()
            r.setDataSource(this, song.uri)
            videoW = r
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            videoH = r
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            r.release()
        } catch (e: Exception) {
        }
        requestedOrientation = when {
            videoW > videoH ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            videoH > videoW ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        showPage(Page.VIDEO)
        seekVideo.max = playbackService?.currentDuration() ?: 0
        txtVideoHint.visibility =
            if (playbackService?.isPlayingSafe() == true) View.GONE else View.VISIBLE
    }

    /** 视频画面等比适配（letterbox 居中），避免竖屏视频被拉伸。 */
    private fun fitVideoSurface(viewW: Int, viewH: Int) {
        if (videoW <= 0 || videoH <= 0 || viewW <= 0 || viewH <= 0) return
        val scale = minOf(
            viewW.toFloat() / videoW,
            viewH.toFloat() / videoH
        )
        val m = android.graphics.Matrix()
        m.setScale(scale, scale, viewW / 2f, viewH / 2f)
        videoSurface.setTransform(m)
    }

    /** 关闭视频页：脱离画面（播放不中断），恢复竖屏。 */
    private fun closeVideoPage() {
        videoLongPressHandler.removeCallbacksAndMessages(null)
        if (videoSpeedUp) {
            videoSpeedUp = false
            playbackService?.setSpeed(1f)
        }
        playbackService?.attachVideoSurface(null)
        videoSurfaceAttached = false
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
        val idx = playbackService?.currentIndex() ?: -1
        val song = currentSongs.getOrNull(idx) ?: return
        CoverLoader.invalidate(song.uri.toString())
        imgCd.setImageResource(R.drawable.ic_music_tinted)
        CoverLoader.load(this, song.uri, 400) { bmp ->
            if (bmp != null && song.uri.toString() == currentCoverKey) {
                imgCd.setImageBitmap(bmp)
                applyPlayerBackground(bmp)
            }
        }
    }

    /** 播放页背景：自定义背景图优先；无则用封面主色渐变（暗化保证可读）。 */
    private fun applyPlayerBackground(cover: Bitmap?) {
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

    private fun translationConfig(): LyricTranslator.Config? {
        val key = prefs.getString(KEY_TRANS_KEY, "")?.trim()
        if (key.isNullOrEmpty()) return null
        val base = prefs.getString(KEY_TRANS_BASE, "")?.trim().orEmpty()
        val model = prefs.getString(KEY_TRANS_MODEL, "")?.trim().orEmpty()
        return LyricTranslator.Config(
            baseUrl = base.ifEmpty { DEFAULT_TRANS_BASE },
            apiKey = key,
            model = model.ifEmpty { DEFAULT_TRANS_MODEL }
        )
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

        val uriKey = lastSong?.uri?.toString() ?: ""
        val cache = translationCache.getOrPut(uriKey) { HashMap() }
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
                cache.putAll(result.translations)
                LyricTranslationCache.save(applicationContext, translationCache)
                transFailedLines = toTranslate.filter { it.first !in result.translations }
                lyricAdapter.setTranslations(
                    translationCache[lastSong?.uri?.toString()] ?: emptyMap()
                )
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
        if (isChineseLyric(lines)) return
        translateCurrentLyric()
    }

    /** 判断歌词是否为中文为主：含明显假名（日文）判定非中文；否则汉字占比 ≥ 30% 视为中文，不自动翻译。 */
    private fun isChineseLyric(lines: List<SubtitleLine>): Boolean {
        var cjk = 0
        var kana = 0
        var total = 0
        for (line in lines) {
            for (ch in line.text) {
                if (ch.isWhitespace()) continue
                total++
                if (ch in '\u3040'..'\u30ff') {
                    kana++ // 平假名/片假名
                } else if (ch in '\u4e00'..'\u9fff') {
                    cjk++
                }
            }
        }
        if (total == 0) return true
        // 假名占比 ≥ 5% → 判定为日语（日汉字再多也照常翻译；日语歌假名通常占 30%+）
        if (kana.toFloat() / total >= 0.05f) return false
        return cjk.toFloat() / total >= 0.3f
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

    private fun tagOf(rg: RadioGroup): Int =
        rg.findViewById<android.view.View>(rg.checkedRadioButtonId)
            ?.tag?.toString()?.toIntOrNull() ?: 0

    private fun checkByTag(rg: RadioGroup, value: Int) {
        for (i in 0 until rg.childCount) {
            val child = rg.getChildAt(i)
            if (child.tag?.toString()?.toIntOrNull() == value) {
                (child as? RadioButton)?.isChecked = true
                return
            }
        }
    }

    private fun applyAppearance() {
        lyricAdapter.applyStyle(
            prefs.getInt(KEY_LYRIC_SIZE, 18),
            prefs.getInt(KEY_LYRIC_FONT, 0)
        )
        val uiSize = prefs.getInt(KEY_UI_SIZE, 15)
        songAdapter.applyUiSize(uiSize)
        searchAdapter.applyUiSize(uiSize)
        gridAdapter.applyUiSize(uiSize)
        discoverAdapter.applyUiSize(uiSize)
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
            formatTime(pos), formatTime(durationMs)
        )
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", m, s)
        }
    }

    // ---------- 权限 ----------

    private fun persistRead(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // 部分文件提供方不支持持久化权限，忽略
        }
    }

    private fun hasPersistRead(uri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val KEY_TREE = "tree_uri"
        private const val KEY_AUTO_SCAN = "auto_scan"
        private const val KEY_IMMERSION_SECONDS = "immersion_seconds"
        private const val KEY_IMMERSION_ENABLED = "immersion_enabled"
        private const val MODULE_DISCOVER = "discover"
        private const val MODULE_LIBRARY = "library"
        private const val MODULE_ARTISTS = "artists"
        private const val MODULE_FAVORITES = "favorites"
        private const val KEY_NAV_TABS = "nav_tabs"
        private const val KEY_NAV_DEFAULT = "nav_default"
        private val ALL_MODULES =
            listOf(MODULE_DISCOVER, MODULE_LIBRARY, MODULE_ARTISTS, MODULE_FAVORITES)
        private const val DEFAULT_NAV = "discover,library,artists,favorites"
        private const val KEY_DARK = "dark_mode"
        private const val KEY_LYRIC_SIZE = "lyric_size"
        private const val KEY_UI_SIZE = "ui_size"
        private const val KEY_LYRIC_FONT = "lyric_font"
        private const val KEY_TRANS_BASE = "trans_base"
        private const val KEY_TRANS_KEY = "trans_key"
        private const val KEY_TRANS_MODEL = "trans_model"
        private const val KEY_AUTO_TRANS = "auto_translate"
        private const val KEY_DESKTOP_ON = "desktop_lyrics_on"
        private const val KEY_DESKTOP_SIZE = "desktop_lyrics_size"
        private const val KEY_DESKTOP_ALPHA = "desktop_lyrics_alpha"
        private const val KEY_DESKTOP_LOCKED = "desktop_lyrics_locked"
        private const val KEY_MIX_AUDIO = "mix_audio"
        private const val KEY_ALARM_ON = "alarm_play_on"
        private const val KEY_ALARM_HOUR = "alarm_play_hour"
        private const val KEY_ALARM_MINUTE = "alarm_play_minute"
        private const val DEFAULT_TRANS_BASE = "https://api.deepseek.com/v1"
        private const val DEFAULT_TRANS_MODEL = "deepseek-v4-flash"
        private const val SUPPORT_URL = "https://www.ifdian.net/a/ruozhi521"
    }

    /** 选择整个文件夹并请求可持久化读权限。 */
    private class OpenTreePersistable : ActivityResultContract<Void?, Uri?>() {
        override fun createIntent(context: Context, input: Void?): Intent {
            return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
            return if (resultCode == Activity.RESULT_OK) intent?.data else null
        }
    }
}