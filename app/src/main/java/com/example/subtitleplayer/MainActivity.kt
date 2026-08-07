package com.example.subtitleplayer

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.GestureDetector
import android.view.MotionEvent
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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class Page { DISCOVER, LIBRARY, PLAYLIST, SEARCH, PLAYER, LYRICS }

    // ---- 页面视图 ----
    private lateinit var viewDiscover: View
    private lateinit var viewLibrary: View
    private lateinit var viewPlaylist: View
    private lateinit var viewSearch: View
    private lateinit var viewPlayer: View
    private lateinit var viewLyrics: View

    // ---- 底部导航 ----
    private lateinit var tabDiscover: TextView
    private lateinit var tabLibrary: TextView
    private var currentTab = 0
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
            txtPlayerFolder.text = song?.folder ?: ""
            txtMiniTitle.text = song?.title ?: ""
            hasSong = song != null
            if (song != null) {
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
            } else {
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
                    }
                }
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
        }
    }

    private val treePicker =
        registerForActivityResult(OpenTreePersistable()) { uri ->
            uri ?: return@registerForActivityResult
            persistRead(uri)
            prefs.edit().putString(KEY_TREE, uri.toString()).apply()
            scanLibrary(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewDiscover = findViewById(R.id.pageDiscover)
        viewLibrary = findViewById(R.id.pageLibrary)
        viewPlaylist = findViewById(R.id.pagePlaylist)
        viewSearch = findViewById(R.id.pageSearch)
        viewPlayer = findViewById(R.id.pagePlayer)
        viewLyrics = findViewById(R.id.pageLyrics)
        viewPlayer.setOnTouchListener { _, e ->
            pageFlingDetector.onTouchEvent(e)
            false
        }
        viewLyrics.setOnTouchListener { _, e ->
            pageFlingDetector.onTouchEvent(e)
            false
        }

        tabDiscover = findViewById(R.id.tabDiscover)
        tabLibrary = findViewById(R.id.tabLibrary)
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
        gridAdapter = PlaylistGridAdapter { pos ->
            playlistList().getOrNull(pos)?.let { openPlaylist(it) }
        }
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

        // ---- 全屏歌词 ----
        lyricAdapter = LyricAdapter { pos -> onLyricClick(pos) }
        recyclerLyricFull.layoutManager = LinearLayoutManager(this)
        recyclerLyricFull.adapter = lyricAdapter

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

        // ---- 底部导航 ----
        tabDiscover.setOnClickListener { selectTab(0) }
        tabLibrary.setOnClickListener { selectTab(1) }

        // ---- 播放页控制 ----
        findViewById<Button>(R.id.btnBackSongs).setOnClickListener { backFromPlayer() }
        findViewById<Button>(R.id.btnLyrics).setOnClickListener { showPage(Page.LYRICS) }
        findViewById<ImageButton>(R.id.btnQueue).setOnClickListener { showQueueDialog() }
        findViewById<ImageButton>(R.id.btnLyricsIcon).setOnClickListener { showPage(Page.LYRICS) }
        findViewById<Button>(R.id.btnBackLyrics).setOnClickListener { showPage(Page.PLAYER) }
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

        selectTab(0)
        applyAppearance()

        // 恢复上次选择的文件夹
        val saved = prefs.getString(KEY_TREE, null)
        if (saved != null) {
            val uri = Uri.parse(saved)
            if (hasPersistRead(uri)) {
                if (prefs.getBoolean(KEY_AUTO_SCAN, true)) {
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
        cdAnimator?.cancel()
        cdAnimator = null
    }

    // ---------- 页面与导航 ----------

    private fun selectTab(tab: Int) {
        currentTab = tab
        tabDiscover.setTextColor(getColor(if (tab == 0) R.color.accent else R.color.text_hint))
        tabDiscover.typeface = if (tab == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tabLibrary.setTextColor(getColor(if (tab == 1) R.color.accent else R.color.text_hint))
        tabLibrary.typeface = if (tab == 1) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        showPage(if (tab == 0) Page.DISCOVER else Page.LIBRARY)
    }

    private fun showPage(p: Page) {
        page = p
        val shows = listOf(
            viewDiscover to (p == Page.DISCOVER),
            viewLibrary to (p == Page.LIBRARY),
            viewPlaylist to (p == Page.PLAYLIST),
            viewSearch to (p == Page.SEARCH),
            viewPlayer to (p == Page.PLAYER),
            viewLyrics to (p == Page.LYRICS)
        )
        for ((v, show) in shows) {
            if (show && v.visibility != View.VISIBLE) {
                v.alpha = 0f
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).setDuration(180).start()
            } else if (!show && v.visibility == View.VISIBLE) {
                v.visibility = View.GONE
            }
        }
        // 播放页/歌词页不显示底部迷你条，避免双进度条
        if (p == Page.PLAYER || p == Page.LYRICS) {
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
        showPage(if (currentTab == 0) Page.DISCOVER else Page.LIBRARY)
    }

    // ---------- 左右滑动切换播放页/歌词页 ----------

    private val pageFlingDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (Math.abs(velocityX) < Math.abs(velocityY) || Math.abs(velocityX) < 900) {
                    return false
                }
                if (velocityX < 0) {
                    if (page == Page.PLAYER) showPage(Page.LYRICS)
                } else {
                    if (page == Page.LYRICS) showPage(Page.PLAYER)
                }
                return true
            }
        })
    }

    override fun onBackPressed() {
        when (page) {
            Page.LYRICS -> showPage(Page.PLAYER)
            Page.PLAYER -> backFromPlayer()
            Page.PLAYLIST, Page.SEARCH -> showPage(Page.LIBRARY)
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
                LibraryScanner(contentResolver).scan(uri)
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
        currentSongs = songs
        songAdapter.submit(songs)
        showPage(Page.PLAYLIST)
    }

    // ---------- 歌单/搜索 ----------

    private fun openPlaylist(playlist: Playlist) {
        txtPlaylistTitle.text = playlist.name
        currentSongs = playlist.songs
        songAdapter.submit(playlist.songs)
        showPage(Page.PLAYLIST)
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
        val lm = recyclerLyricFull.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (idx < first || idx > last) {
            val target = idx
            recyclerLyricFull.post {
                lm.scrollToPositionWithOffset(target, recyclerLyricFull.height / 3)
            }
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
        val chkAutoScan = view.findViewById<CheckBox>(R.id.chkAutoScan)
        val chkDark = view.findViewById<CheckBox>(R.id.chkDark)
        val chkAutoTrans = view.findViewById<CheckBox>(R.id.chkAutoTrans)
        val rgLyric = view.findViewById<RadioGroup>(R.id.rgLyricSize)
        val rgUi = view.findViewById<RadioGroup>(R.id.rgUiSize)
        val rgFont = view.findViewById<RadioGroup>(R.id.rgFont)

        chkAutoScan.isChecked = prefs.getBoolean(KEY_AUTO_SCAN, true)
        chkDark.isChecked = prefs.getBoolean(KEY_DARK, false)
        chkAutoTrans.isChecked = prefs.getBoolean(KEY_AUTO_TRANS, false)
        checkByTag(rgLyric, prefs.getInt(KEY_LYRIC_SIZE, 18))
        checkByTag(rgUi, prefs.getInt(KEY_UI_SIZE, 15))
        checkByTag(rgFont, prefs.getInt(KEY_LYRIC_FONT, 0))

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
                    .putBoolean(KEY_DARK, chkDark.isChecked)
                    .putBoolean(KEY_AUTO_TRANS, chkAutoTrans.isChecked)
                    .putInt(KEY_LYRIC_SIZE, tagOf(rgLyric))
                    .putInt(KEY_UI_SIZE, tagOf(rgUi))
                    .putInt(KEY_LYRIC_FONT, tagOf(rgFont))
                    .apply()
                applyAppearance()
                applyDarkMode(chkDark.isChecked)
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

    // ---------- 歌词 AI 翻译 ----------

    private fun showTransSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.trans_dialog, null)
        val etBase = view.findViewById<android.widget.EditText>(R.id.etTransBase)
        val etKey = view.findViewById<android.widget.EditText>(R.id.etTransKey)
        val etModel = view.findViewById<android.widget.EditText>(R.id.etTransModel)
        etBase.setText(prefs.getString(KEY_TRANS_BASE, DEFAULT_TRANS_BASE))
        etKey.setText(prefs.getString(KEY_TRANS_KEY, ""))
        etModel.setText(prefs.getString(KEY_TRANS_MODEL, DEFAULT_TRANS_MODEL))
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

    private fun translationConfig(): LyricTranslator.Config? {
        val key = prefs.getString(KEY_TRANS_KEY, "")?.trim()
        if (key.isNullOrEmpty()) return null
        return LyricTranslator.Config(
            baseUrl = prefs.getString(KEY_TRANS_BASE, DEFAULT_TRANS_BASE)!!.trim(),
            apiKey = key,
            model = prefs.getString(KEY_TRANS_MODEL, DEFAULT_TRANS_MODEL)!!.trim()
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
                emptyMap()
            }
            runOnUiThread {
                translating = false
                btnTranslate.isEnabled = true
                btnTranslate.text = getString(R.string.translate)
                cache.putAll(result)
                LyricTranslationCache.save(applicationContext, translationCache)
                transFailedLines = toTranslate.filter { it.first !in result }
                lyricAdapter.setTranslations(
                    translationCache[lastSong?.uri?.toString()] ?: emptyMap()
                )
                when {
                    result.isEmpty() -> toast(getString(R.string.trans_all_fail))
                    transFailedLines.isNotEmpty() ->
                        toast(getString(R.string.trans_partial_fail, transFailedLines.size))
                    else -> toast(getString(R.string.trans_ok))
                }
            }
        }.start()
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
        private const val KEY_DARK = "dark_mode"
        private const val KEY_LYRIC_SIZE = "lyric_size"
        private const val KEY_UI_SIZE = "ui_size"
        private const val KEY_LYRIC_FONT = "lyric_font"
        private const val KEY_TRANS_BASE = "trans_base"
        private const val KEY_TRANS_KEY = "trans_key"
        private const val KEY_TRANS_MODEL = "trans_model"
        private const val KEY_AUTO_TRANS = "auto_translate"
        private const val DEFAULT_TRANS_BASE = "https://api.deepseek.com/v1"
        private const val DEFAULT_TRANS_MODEL = "deepseek-chat"
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
